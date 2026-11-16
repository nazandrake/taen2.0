// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransducerSpeechEngineTest {
    private class Recording : TransducerModel, AutoCloseable {
        var encodeFrames = -1
        var featureCount = -1
        var closed = false
        private class S : DecoderState
        override fun encode(features: FloatArray, frames: Int): Encoded {
            encodeFrames = frames
            featureCount = features.size
            return Encoded(FloatArray(2), 2, 1) // one encoder frame
        }
        override fun initialDecoderState(): DecoderState = S()
        override fun decoderStep(token: Int, state: DecoderState) = DecoderOutput(FloatArray(1), S())
        // token 4 ("hello"), then (second call) blank with duration index 1
        private var calls = 0
        override fun joint(encoded: Encoded, t: Int, decoder: FloatArray): FloatArray {
            val logits = FloatArray(8198)
            if (calls++ == 0) { logits[4] = 1f; logits[8193 + 0] = 1f } else { logits[8192] = 1f; logits[8193 + 1] = 1f }
            return logits
        }
        override fun close() { closed = true }
    }

    private fun vocabulary() = (0..8192).map { i -> if (i == 4) "▁hello $i" else "x$i $i" }

    @Test fun speechGoesThroughFeaturesSearchAndVocabulary() {
        val model = Recording()
        val engine = TransducerSpeechEngine.parakeetV3(model, vocabulary())
        assertEquals("hello", engine.transcribe(FloatArray(16000) { 0.01f * (it % 7) }, 16000))
        assertEquals(100, model.encodeFrames) // one second: 100 frames of 128 features
        assertEquals(128 * 100, model.featureCount)
    }

    @Test fun onlyTheGivenLengthIsTranscribed() {
        val model = Recording()
        val engine = TransducerSpeechEngine.parakeetV3(model, vocabulary())
        engine.transcribe(FloatArray(32000), 8000)
        assertEquals(50, model.encodeFrames)
    }

    @Test fun lessThanATenthOfASecondIsNotSentToTheModel() {
        val model = Recording()
        val engine = TransducerSpeechEngine.parakeetV3(model, vocabulary())
        assertEquals("", engine.transcribe(FloatArray(1599), 1599))
        assertEquals(-1, model.encodeFrames) // the model was not called
        assertEquals("hello", engine.transcribe(FloatArray(1600), 1600)) // positive control: exactly 0.1 s is transcribed
    }

    @Test fun lengthLargerThanTheBufferIsRejected() {
        val engine = TransducerSpeechEngine.parakeetV3(Recording(), vocabulary())
        assertFailsWith<IllegalArgumentException> { engine.transcribe(FloatArray(10), 11) }
    }

    @Test fun closingTheEngineClosesTheModel() {
        val model = Recording()
        TransducerSpeechEngine.parakeetV3(model, vocabulary()).close()
        assertTrue(model.closed)
    }

    @Test fun featuresEncoderOutputAndScoresAreZeroedAfterTheSearch() { // P20: audio-derived arrays do not outlive the call
        var seenFeatures: FloatArray? = null
        var featureSumDuringEncode = 0.0
        var seenEncoded: Encoded? = null
        val seenLogits = ArrayList<FloatArray>()
        val model = object : TransducerModel {
            override fun encode(features: FloatArray, frames: Int): Encoded {
                featureSumDuringEncode = features.sumOf { Math.abs(it.toDouble()) }
                seenFeatures = features
                return Encoded(FloatArray(2) { 5f }, 2, 1).also { seenEncoded = it }
            }
            override fun initialDecoderState(): DecoderState = object : DecoderState {}
            override fun decoderStep(token: Int, state: DecoderState) = DecoderOutput(FloatArray(1), state)
            override fun joint(encoded: Encoded, t: Int, decoder: FloatArray): FloatArray =
                FloatArray(8198).also { it[4] = 1f; it[8193] = 1f; seenLogits.add(it) } // token 4 with duration 0, then (frame 0 again) the same: ends by the symbol cap
        }
        TransducerSpeechEngine.parakeetV3(model, vocabulary()).transcribe(FloatArray(16000) { 0.01f * (it % 7) }, 16000)
        assertTrue(featureSumDuringEncode > 0.0, "positive control: the encoder was given real features")
        assertTrue(seenFeatures!!.all { it == 0f }, "the feature matrix is cleared")
        assertTrue(seenEncoded!!.data.all { it == 0f }, "the encoder output is cleared")
        assertTrue(seenLogits.size >= 2, "positive control: the joiner was called")
        assertTrue(seenLogits.all { l -> l.all { it == 0f } }, "the joiner scores are cleared")
    }
}
