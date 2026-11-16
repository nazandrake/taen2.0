#!/usr/bin/env python3
"""Transcribe a sample directory (prepare_samples.py) with the numpy reference of parakeet_ref.py, or with sherpa-onnx.

usage: eval_ref.py ref|sherpa <model dir> <sample dir> <out hyps.tsv>
Prints the real-time factor per utterance (audio length / decode time is not what is shown: RTF = time / audio length).
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parakeet_ref  # noqa: E402

kind, model_dir, sample_dir, out_path = sys.argv[1:5]
names = sorted(n for n in os.listdir(sample_dir) if n.endswith(".wav"))
if kind == "sherpa":
    import sherpa_onnx
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx", decoder=f"{model_dir}/decoder.int8.onnx", joiner=f"{model_dir}/joiner.int8.onnx",
        tokens=f"{model_dir}/tokens.txt", model_type="nemo_transducer", num_threads=4, provider="cpu")
else:
    model = parakeet_ref.Model(model_dir)
with open(out_path, "w", encoding="utf-8") as out:
    for n in names:
        pcm = parakeet_ref.read_wav(os.path.join(sample_dir, n))
        t0 = time.time()
        if kind == "sherpa":
            s = rec.create_stream()
            s.accept_waveform(16000, pcm)
            rec.decode_stream(s)
            text = s.result.text.strip()
        else:
            text = model.transcribe(pcm)[0]
        dt = time.time() - t0
        print(f"{n} rtf={dt / (len(pcm) / 16000):.2f} | {text}")
        out.write(f"{n[:-4]}\t{text}\n")
