// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

/** Turns one utterance (16 kHz mono, floats in -1..1) into text. Implementations keep no audio after the call returns. */
interface SpeechEngine : AutoCloseable {
    fun transcribe(pcm: FloatArray, length: Int): String
}

/** The log-mel front end, the TDT greedy search and the vocabulary in front of a [TransducerModel]. */
class TransducerSpeechEngine(
    private val model: TransducerModel,
    private val tokens: Tokens,
    private val search: TdtGreedy,
) : SpeechEngine {
    override fun transcribe(pcm: FloatArray, length: Int): String {
        require(length in 0..pcm.size)
        if (length < MIN_SAMPLES) return "" // under a tenth of a second: a tap, not speech
        val frames = Fbank.frameCount(length)
        val features = Fbank.compute(pcm, length)
        try {
            return tokens.text(search.decode(model, features, frames))
        } finally {
            features.fill(0f) // audio-derived (P20); the search clears the encoder output it gets back
        }
    }

    override fun close() {
        (model as? AutoCloseable)?.close()
    }

    companion object {
        const val MIN_SAMPLES = Fbank.SAMPLE_RATE / 10

        /** The speech engine of parakeet-tdt-0.6b-v3: blank id 8192 (vocab_size in the model metadata), durations 0..4. */
        fun parakeetV3(model: TransducerModel, tokenLines: List<String>) =
            TransducerSpeechEngine(model, Tokens(tokenLines), TdtGreedy(blankId = 8192, durations = intArrayOf(0, 1, 2, 3, 4)))
    }
}
