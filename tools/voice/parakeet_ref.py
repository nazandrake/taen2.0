#!/usr/bin/env python3
"""Reference implementation of the Taen voice pipeline in numpy + onnxruntime.

It is the independent oracle for the Kotlin code in helium314.keyboard.latin.voice: log-mel features as NeMo computes them for
parakeet-tdt-0.6b-v3 (16 kHz, preemphasis 0.97, n_fft 512, window 400, hop 160, 128 Slaney mel bins, log(x + 2^-24), per-feature
normalisation) and the TDT greedy search of the exported encoder, decoder and joiner. Used by tools/voice/make_fixtures.py (golden
fixtures for the unit tests) and for the comparison with sherpa-onnx. Not part of the app or the release build.

usage: parakeet_ref.py <model dir> <16 kHz mono wav> [...]
"""
import sys
import wave

import numpy as np
import onnxruntime as ort

SR = 16000
N_FFT = 512
WIN = 400
HOP = 160
N_MELS = 128
PREEMPH = 0.97
LOG_GUARD = 2.0 ** -24
BLANK = 8192
NUM_DURATIONS = 5
MAX_SYMBOLS_PER_FRAME = 10


def hz_to_mel(f):
    f = np.asarray(f, dtype=np.float64)
    f_sp = 200.0 / 3
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(f >= min_log_hz, min_log_mel + np.log(np.maximum(f, 1e-9) / min_log_hz) / logstep, f / f_sp)


def mel_to_hz(m):
    m = np.asarray(m, dtype=np.float64)
    f_sp = 200.0 / 3
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(m >= min_log_mel, min_log_hz * np.exp(logstep * (m - min_log_mel)), f_sp * m)


def mel_filters():
    """librosa.filters.mel(sr=16000, n_fft=512, n_mels=128, fmin=0, fmax=8000, htk=False, norm='slaney'), shape (128, 257)."""
    fft_freqs = np.linspace(0, SR / 2, N_FFT // 2 + 1)
    mel_pts = np.linspace(hz_to_mel(0.0), hz_to_mel(SR / 2), N_MELS + 2)
    hz = mel_to_hz(mel_pts)
    fdiff = np.diff(hz)
    ramps = hz[:, None] - fft_freqs[None, :]
    lower = -ramps[:-2] / fdiff[:-1, None]
    upper = ramps[2:] / fdiff[1:, None]
    weights = np.maximum(0, np.minimum(lower, upper))
    enorm = 2.0 / (hz[2:N_MELS + 2] - hz[:N_MELS])
    return weights * enorm[:, None]


def features(pcm):
    """pcm: float32 in [-1, 1]. Returns (128, frames) float32, normalised per feature."""
    x = np.asarray(pcm, dtype=np.float64)
    x = np.concatenate([x[:1], x[1:] - PREEMPH * x[:-1]])
    pad = N_FFT // 2
    x = np.pad(x, (pad, pad))  # centre=True, zero padding
    window = np.zeros(N_FFT)
    window[(N_FFT - WIN) // 2:(N_FFT - WIN) // 2 + WIN] = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(WIN) / WIN)  # periodic Hann
    frames = 1 + (len(x) - N_FFT) // HOP
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(frames)[:, None]
    spec = np.fft.rfft(x[idx] * window[None, :], axis=1)
    power = spec.real ** 2 + spec.imag ** 2
    mel = power @ mel_filters().T
    logmel = np.log(mel + LOG_GUARD)
    seq = len(pcm) // HOP  # NeMo: frames of the signal, the last (centre) frame is dropped
    logmel = logmel[:max(seq, 1)]
    mean = logmel.mean(axis=0)
    std = logmel.std(axis=0, ddof=1) if len(logmel) > 1 else np.zeros(N_MELS)
    return ((logmel - mean) / (std + 1e-5)).T.astype(np.float32)


class Model:
    def __init__(self, model_dir):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        self.enc = ort.InferenceSession(f"{model_dir}/encoder.int8.onnx", opts, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(f"{model_dir}/decoder.int8.onnx", opts, providers=["CPUExecutionProvider"])
        self.join = ort.InferenceSession(f"{model_dir}/joiner.int8.onnx", opts, providers=["CPUExecutionProvider"])
        self.tokens = {}
        for line in open(f"{model_dir}/tokens.txt", encoding="utf-8"):
            piece, _, i = line.rstrip("\n").rpartition(" ")
            self.tokens[int(i)] = piece

    def encode(self, feats):
        out, lens = self.enc.run(None, {"audio_signal": feats[None], "length": np.array([feats.shape[1]], dtype=np.int64)})
        return out[0, :, :int(lens[0])]  # (1024, T')

    def decode_step(self, token, h, c):
        out, _, h2, c2 = self.dec.run(None, {"targets": np.array([[token]], dtype=np.int32), "target_length": np.array([1], dtype=np.int32),
                                             "states.1": h, "onnx::Slice_3": c})
        return out[:, :, 0:1], h2, c2

    def transcribe(self, pcm):
        enc = self.encode(features(pcm))
        h = np.zeros((2, 1, 640), dtype=np.float32)
        c = np.zeros((2, 1, 640), dtype=np.float32)
        dec_out, h, c = self.decode_step(BLANK, h, c)
        t, emitted, tokens = 0, 0, []
        while t < enc.shape[1]:
            logits = self.join.run(None, {"encoder_outputs": enc[None, :, t:t + 1], "decoder_outputs": dec_out})[0].reshape(-1)
            token = int(np.argmax(logits[:BLANK + 1]))
            skip = int(np.argmax(logits[BLANK + 1:BLANK + 1 + NUM_DURATIONS]))
            if token != BLANK:
                tokens.append(token)
                dec_out, h, c = self.decode_step(token, h, c)
                emitted += 1
            if token == BLANK and skip == 0:
                skip = 1
            if skip > 0:
                t += skip
                emitted = 0
            elif emitted >= MAX_SYMBOLS_PER_FRAME:
                t += 1
                emitted = 0
        return "".join(self.tokens[i] for i in tokens).replace("▁", " ").strip(), tokens


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, "16 kHz mono 16-bit wav expected"
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0


if __name__ == "__main__":
    model = Model(sys.argv[1])
    for path in sys.argv[2:]:
        print(path, "|", model.transcribe(read_wav(path))[0])
