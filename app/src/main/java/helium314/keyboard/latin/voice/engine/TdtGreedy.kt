// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

/** The encoder output of one utterance: [frames] frames of [dim] floats, laid out dim-major like the encoder returns it (`d * frames + t`). */
class Encoded(val data: FloatArray, val dim: Int, val frames: Int)

/** Prediction-network state that [TransducerModel.decoderStep] returns and takes again; opaque to the search. */
interface DecoderState {
    /** Overwrites the state with zeros; the search calls it when the state is no longer needed (P20: it is derived from the audio). */
    fun clear() {}
}

class DecoderOutput(val vector: FloatArray, val state: DecoderState) {
    /** Zeros the vector and the state, which are audio-derived (P20). */
    fun clear() {
        vector.fill(0f)
        state.clear()
    }
}

/** The three networks of an exported transducer (encoder, prediction network, joiner), behind an interface so the search is tested with a scripted fake. */
interface TransducerModel {
    fun encode(features: FloatArray, frames: Int): Encoded

    /** The state before the first token. */
    fun initialDecoderState(): DecoderState

    /** One prediction-network step after emitting [token] (the blank id for the first step). */
    fun decoderStep(token: Int, state: DecoderState): DecoderOutput

    /** Joiner logits for encoder frame [t] and the prediction vector: first the token logits (blank last), then the duration logits. */
    fun joint(encoded: Encoded, t: Int, decoder: FloatArray): FloatArray
}

/**
 * Greedy search of a token-and-duration transducer (NeMo TDT, as sherpa-onnx's offline NeMo transducer decoder): the joiner scores the
 * [blankId] + 1 tokens and then the durations; a non-blank token is emitted and the prediction network steps; the chosen duration says
 * how many encoder frames to skip. Blank with duration 0 advances by one frame, and so does a frame that already emitted
 * [maxSymbolsPerFrame] tokens, so the loop ends. Oracle: tools/voice/parakeet_ref.py.
 */
class TdtGreedy(
    private val blankId: Int,
    private val durations: IntArray,
    private val maxSymbolsPerFrame: Int = 10,
) {
    init {
        require(durations.isNotEmpty() && durations.all { it >= 0 } && maxSymbolsPerFrame >= 1)
    }

    fun decode(model: TransducerModel, features: FloatArray, featureFrames: Int): IntArray {
        val encoded = model.encode(features, featureFrames)
        try {
            return search(model, encoded)
        } finally {
            encoded.data.fill(0f) // the encoder output is audio-derived (P20)
        }
    }

    private fun search(model: TransducerModel, encoded: Encoded): IntArray {
        // the decoder vectors and states are audio-derived (P20): each is zeroed when the next step has replaced it, the last one when the search ends
        val initial = model.initialDecoderState()
        var decoder: DecoderOutput? = null
        try {
            decoder = model.decoderStep(blankId, initial)
            if (decoder.state !== initial) initial.clear()
            val tokens = ArrayList<Int>()
            var t = 0
            var symbolsThisFrame = 0
            while (t < encoded.frames) {
                val logits = model.joint(encoded, t, decoder!!.vector)
                if (logits.size != blankId + 1 + durations.size) {
                    logits.fill(0f)
                    throw IllegalArgumentException("unexpected joiner output size")
                }
                val token = argmax(logits, 0, blankId + 1)
                var skip = durations[argmax(logits, blankId + 1, durations.size)]
                logits.fill(0f) // the scores depend on the audio (P20)
                if (token != blankId) {
                    tokens.add(token)
                    val next = model.decoderStep(token, decoder.state)
                    decoder.vector.fill(0f)
                    if (next.state !== decoder.state) decoder.state.clear()
                    decoder = next
                    symbolsThisFrame++
                }
                if (token == blankId && skip == 0) skip = 1
                if (skip > 0) {
                    t += skip
                    symbolsThisFrame = 0
                } else if (symbolsThisFrame >= maxSymbolsPerFrame) {
                    t++
                    symbolsThisFrame = 0
                }
            }
            return tokens.toIntArray()
        } finally {
            if (decoder == null) initial.clear() else decoder.clear()
        }
    }

    private fun argmax(values: FloatArray, from: Int, count: Int): Int {
        var best = 0
        for (i in 1 until count) if (values[from + i] > values[from + best]) best = i
        return best
    }
}
