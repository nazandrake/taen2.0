// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The feature extraction against the numpy oracle (tools/voice/parakeet_ref.py, make_fixtures.py): the full 128 x 40 matrix of a fixed
 * 0.4 s signal. Oracle values were produced by the independent implementation, not by this code.
 */
class FbankTest {
    private fun testSignal() = FloatArray(6400) { n ->
        (0.3 * sin(2 * PI * 440.0 * n / 16000) + 0.2 * sin(2 * PI * 1234.5 * n / 16000 + 0.5) +
            0.1 * sin(2 * PI * 3000.0 * n / 16000) * ((n % 800) / 800.0)).toFloat()
    }

    @Test fun matchesTheOracleOnTheFixedSignal() {
        val lines = javaClass.getResourceAsStream("/fbank_golden.txt")!!.bufferedReader().readLines()
        val frames = lines[0].toInt()
        assertEquals(40, frames)
        assertEquals(frames, Fbank.frameCount(6400))
        val actual = Fbank.compute(testSignal())
        assertEquals(Fbank.N_MELS * frames, actual.size)
        var worst = 0.0
        for (m in 0 until Fbank.N_MELS) {
            val row = lines[1 + m].split(' ').map { it.toDouble() }
            assertEquals(frames, row.size)
            for (t in 0 until frames) worst = maxOf(worst, abs(row[t] - actual[m * frames + t]))
        }
        assertTrue(worst < 2e-3, "largest difference to the oracle $worst")
    }

    @Test fun everyMelBinIsNormalisedOverTime() { // property of the normalisation: mean 0 and standard deviation 1 (n - 1) per bin
        val frames = Fbank.frameCount(6400)
        val out = Fbank.compute(testSignal())
        for (m in 0 until Fbank.N_MELS) {
            val row = DoubleArray(frames) { out[m * frames + it].toDouble() }
            val mean = row.average()
            val std = Math.sqrt(row.sumOf { (it - mean) * (it - mean) } / (frames - 1))
            assertTrue(abs(mean) < 1e-4, "mean of bin $m is $mean")
            assertTrue(abs(std - 1.0) < 1e-3, "standard deviation of bin $m is $std")
        }
    }

    @Test fun frameCountFollowsNemoAndNeverDropsBelowOne() {
        assertEquals(1, Fbank.frameCount(0))
        assertEquals(1, Fbank.frameCount(159))
        assertEquals(1, Fbank.frameCount(160))
        assertEquals(2, Fbank.frameCount(320))
        assertEquals(100, Fbank.frameCount(16000))
        assertEquals(1000, Fbank.frameCount(160000))
    }

    @Test fun onlyTheGivenLengthIsUsed() {
        val full = testSignal()
        val longer = full.copyOf(8000).also { for (i in 6400 until 8000) it[i] = 0.9f } // samples after [length] must not matter
        assertEquals(Fbank.compute(full).toList(), Fbank.compute(longer, 6400).toList())
    }

    @Test fun emptyAndTinyInputGivesOneFrameOfZeros() {
        val none = Fbank.compute(FloatArray(0))
        assertEquals(Fbank.N_MELS, none.size)
        assertTrue(none.all { it == 0f })
        assertEquals(Fbank.N_MELS, Fbank.compute(floatArrayOf(0.5f, -0.5f, 0.25f)).size)
    }

    @Test fun silenceStaysFinite() {
        val out = Fbank.compute(FloatArray(16000))
        assertTrue(out.all { it.isFinite() })
        assertEquals(Fbank.N_MELS * 100, out.size)
    }

    @Test fun aLouderCopyOfTheSameSoundGivesTheSameFeatures() { // log-mel plus per-bin normalisation: a gain is a constant offset that the mean removes
        // a noise floor keeps every bin far above the 2^-24 log guard (a pure tone leaves high bins at the guard, where a gain is not a constant offset)
        var seed = 12345L
        val quiet = testSignal().also { s ->
            for (i in s.indices) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L)
                s[i] += (((seed ushr 33).toInt() % 2000) - 1000) / 1000f * 0.4f // loud enough that even a mel bin with a tiny filter weight stays far above the guard
            }
        }
        val loud = FloatArray(quiet.size) { quiet[it] * 4f }
        val a = Fbank.compute(quiet)
        val b = Fbank.compute(loud)
        // The lowest mel bins have filters narrower than the FFT bin spacing (31.25 Hz): some hold almost no weight, their power sits at the
        // log guard and a gain is not an offset there. That is how the model was trained (same filters in NeMo), so only the bins from 40 up are compared.
        val frames = Fbank.frameCount(quiet.size)
        var worst = 0.0
        for (m in 40 until Fbank.N_MELS) for (t in 0 until frames) worst = maxOf(worst, abs(a[m * frames + t] - b[m * frames + t]).toDouble())
        assertTrue(worst < 5e-3, "gain changed the features by $worst")
    }

    @Test fun everyWorkingArrayIsZeroedWhenTheFeaturesAreReturned() { // P20: the pre-emphasised signal, the log-mel rows and the FFT buffers
        var scratch: List<DoubleArray>? = null
        val out = Fbank.compute(testSignal(), 6400) { scratch = it }
        assertEquals(Fbank.compute(testSignal()).toList(), out.toList()) // positive control: the real result is unchanged and was computed
        assertTrue(out.any { it != 0f })
        val arrays = scratch!!
        assertEquals(4 + Fbank.N_MELS, arrays.size)
        assertEquals(6400 + 512, arrays[0].size) // the signal, with its centre padding
        assertEquals(40, arrays[4].size) // a log-mel row: one value per frame
        assertTrue(arrays.all { row -> row.all { it == 0.0 } }, "a working array still holds audio-derived values")
    }
}
