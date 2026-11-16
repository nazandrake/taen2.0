// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mel-spectrogram (logarithmic) features as NeMo computes them for parakeet-tdt-0.6b-v3 (the model card and the exported metadata: feat_dim 128,
 * normalize_type per_feature): 16 kHz, pre-emphasis 0.97, a 400 sample periodic Hann window in a 512 point FFT, hop 160, centre
 * padding with zeros, power spectrum, 128 Slaney mel bins from 0 to 8 kHz (librosa), log(x + 2^-24), then every mel bin is
 * normalised over time (mean, standard deviation with n - 1, plus 1e-5). Oracle: tools/voice/parakeet_ref.py, compared in FbankTest.
 * Pure Kotlin (no Android types): it runs in the app and in the host module voice-engine. Nothing here keeps or logs audio.
 */
object Fbank {
    const val SAMPLE_RATE = 16000
    const val N_FFT = 512
    const val WIN = 400
    const val HOP = 160
    const val N_MELS = 128
    private const val PREEMPH = 0.97
    private const val LOG_GUARD = 5.9604644775390625e-8 // 2^-24
    private const val BINS = N_FFT / 2 + 1

    /** Number of feature frames for [samples] samples (NeMo drops the last centre frame). */
    fun frameCount(samples: Int): Int = max(samples / HOP, 1)

    private val window = DoubleArray(N_FFT).also { w ->
        val offset = (N_FFT - WIN) / 2
        for (i in 0 until WIN) w[offset + i] = 0.5 - 0.5 * cos(2 * PI * i / WIN) // periodic Hann, centred in the FFT frame
    }

    // radix-2 FFT tables
    private val cosTable = DoubleArray(N_FFT / 2) { cos(2 * PI * it / N_FFT) }
    private val sinTable = DoubleArray(N_FFT / 2) { -sin(2 * PI * it / N_FFT) }
    private val bitReverse = IntArray(N_FFT).also { r ->
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (i in 0 until N_FFT) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private class MelRow(val start: Int, val weights: DoubleArray)

    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3
        val minLogMel = 1000.0 / fSp
        val logStep = ln(6.4) / 27.0
        return if (f >= 1000.0) minLogMel + ln(f / 1000.0) / logStep else f / fSp
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3
        val minLogMel = 1000.0 / fSp
        val logStep = ln(6.4) / 27.0
        return if (m >= minLogMel) 1000.0 * exp(logStep * (m - minLogMel)) else fSp * m
    }

    /** librosa.filters.mel(sr=16000, n_fft=512, n_mels=128, fmin=0, fmax=8000, htk=False, norm="slaney") as sparse rows. */
    private val melRows: List<MelRow> = run {
        val fftFreqs = DoubleArray(BINS) { it * (SAMPLE_RATE / 2.0) / (BINS - 1) }
        val melPoints = DoubleArray(N_MELS + 2) { hzToMel(0.0) + (hzToMel(SAMPLE_RATE / 2.0) - hzToMel(0.0)) * it / (N_MELS + 1) }
        val hz = DoubleArray(N_MELS + 2) { melToHz(melPoints[it]) }
        List(N_MELS) { m ->
            val full = DoubleArray(BINS) { k ->
                val lower = -(hz[m] - fftFreqs[k]) / (hz[m + 1] - hz[m])
                val upper = (hz[m + 2] - fftFreqs[k]) / (hz[m + 2] - hz[m + 1])
                max(0.0, minOf(lower, upper)) * (2.0 / (hz[m + 2] - hz[m]))
            }
            val first = full.indexOfFirst { it != 0.0 }
            val last = full.indexOfLast { it != 0.0 }
            if (first < 0) MelRow(0, DoubleArray(0)) else MelRow(first, full.copyOfRange(first, last + 1))
        }
    }

    /**
     * Features of the first [length] samples of [pcm] (floats in -1..1) as one array, mel bin major: element `m * frames + t`
     * is bin m of frame t, which is the [1, 128, frames] layout the encoder takes. [frameCount] gives `frames`.
     */
    fun compute(pcm: FloatArray, length: Int = pcm.size): FloatArray = compute(pcm, length, null)

    /**
     * [scratchCleared] is for the tests only: it receives every working array (signal, per-bin log-mel rows, FFT buffers) after they were
     * overwritten with zeros, so a test can prove that no audio-derived data stays behind in them.
     */
    internal fun compute(pcm: FloatArray, length: Int, scratchCleared: ((List<DoubleArray>) -> Unit)?): FloatArray {
        require(length in 0..pcm.size)
        val frames = frameCount(length)
        val pad = N_FFT / 2
        // pre-emphasised signal with zero centre padding; index i of this array is sample i - pad
        val signal = DoubleArray(length + 2 * pad)
        val logMel = Array(N_MELS) { DoubleArray(frames) }
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = DoubleArray(BINS)
        try {
            return features(pcm, length, frames, pad, signal, logMel, re, im, power)
        } finally {
            signal.fill(0.0)
            for (row in logMel) row.fill(0.0)
            re.fill(0.0); im.fill(0.0); power.fill(0.0)
            scratchCleared?.invoke(listOf(signal, re, im, power) + logMel)
        }
    }

    private fun features(pcm: FloatArray, length: Int, frames: Int, pad: Int, signal: DoubleArray, logMel: Array<DoubleArray>,
                         re: DoubleArray, im: DoubleArray, power: DoubleArray): FloatArray {
        for (i in 0 until length) signal[pad + i] = if (i == 0) pcm[0].toDouble() else pcm[i] - PREEMPH * pcm[i - 1]
        for (t in 0 until frames) {
            val base = t * HOP
            for (i in 0 until N_FFT) {
                val j = bitReverse[i]
                re[j] = signal[base + i] * window[i]
                im[j] = 0.0
            }
            fft(re, im)
            for (k in 0 until BINS) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until N_MELS) {
                val row = melRows[m]
                var sum = 0.0
                for (i in row.weights.indices) sum += row.weights[i] * power[row.start + i]
                logMel[m][t] = ln(sum + LOG_GUARD)
            }
        }
        val out = FloatArray(N_MELS * frames)
        for (m in 0 until N_MELS) {
            val row = logMel[m]
            var mean = 0.0
            for (v in row) mean += v
            mean /= frames
            var variance = 0.0
            for (v in row) variance += (v - mean) * (v - mean)
            val std = if (frames > 1) sqrt(variance / (frames - 1)) else 0.0
            for (t in 0 until frames) out[m * frames + t] = ((row[t] - mean) / (std + 1e-5)).toFloat()
        }
        return out
    }

    /** In-place iterative radix-2 FFT; the input is already in bit-reversed order. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var size = 2
        while (size <= N_FFT) {
            val half = size / 2
            val step = N_FFT / size
            var start = 0
            while (start < N_FFT) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
