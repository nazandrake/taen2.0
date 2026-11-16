// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * The exported parakeet-tdt-0.6b-v3 transducer (sherpa-onnx int8 export: encoder, decoder, joiner) on ONNX Runtime 1.27.0 (MIT, CPU
 * provider only). The only class that touches ONNX Runtime (privacyCheck). Input and output names and shapes were read from the
 * model files (tools/voice/parakeet_ref.py is the oracle). The model files are given by path: ONNX Runtime maps them itself; nothing is
 * written (no optimised-model file, no profile: privacyCheck forbids both calls) and ONNX Runtime's own logging is set to fatal.
 */
class OrtParakeet(encoderPath: String, decoderPath: String, joinerPath: String, threads: Int) : TransducerModel, AutoCloseable {
    internal class State(val h: FloatArray, val c: FloatArray) : DecoderState {
        override fun clear() {
            h.fill(0f)
            c.fill(0f)
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "taen")
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val joiner: OrtSession

    init {
        env.setTelemetry(false)
        fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT) // the full graph optimiser needs seconds and memory more on a 650 MB int8 encoder
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
        }
        val opened = ArrayList<OrtSession>()
        try {
            options().use { encoder = env.createSession(encoderPath, it).also(opened::add) }
            options().use { decoder = env.createSession(decoderPath, it).also(opened::add) }
            options().use { joiner = env.createSession(joinerPath, it).also(opened::add) }
        } catch (e: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
    }

    override fun encode(features: FloatArray, frames: Int): Encoded {
        require(features.size == Fbank.N_MELS * frames)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, Fbank.N_MELS.toLong(), frames.toLong())).useScrubbed { signal ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { length -> // constant: the length only
                encoder.run(mapOf("audio_signal" to signal, "length" to length)).use { result ->
                    val out = result.get(0) as OnnxTensor // [1, 1024, frames']
                    val shape = out.info.shape
                    val dim = shape[1].toInt()
                    val lengths = (result.get(1) as OnnxTensor).longBuffer // a copy in Java memory: zeroed right after the length is read
                    val outFrames = try {
                        lengths.get(0).toInt().coerceIn(0, shape[2].toInt())
                    } finally {
                        Scrub.zero(lengths)
                    }
                    val buffer = out.floatBuffer // a copy in Java memory: zeroed below, after the part that is kept has been taken
                    try {
                        val total = shape[2].toInt()
                        val data = FloatArray(dim * outFrames)
                        for (d in 0 until dim) {
                            buffer.position(d * total) // separate statements: Buffer.position returns Buffer on older Android libraries
                            buffer.get(data, d * outFrames, outFrames)
                        }
                        return Encoded(data, dim, outFrames)
                    } finally {
                        Scrub.zero(buffer)
                    }
                }
            }
        }
    }

    override fun initialDecoderState(): DecoderState = State(FloatArray(2 * HIDDEN), FloatArray(2 * HIDDEN))

    override fun decoderStep(token: Int, state: DecoderState): DecoderOutput {
        state as State
        OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(token)), longArrayOf(1, 1)).useScrubbed { targets ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1)).use { length -> // constant: the length only
                OnnxTensor.createTensor(env, FloatBuffer.wrap(state.h), longArrayOf(2, 1, HIDDEN.toLong())).useScrubbed { h ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(state.c), longArrayOf(2, 1, HIDDEN.toLong())).useScrubbed { c ->
                        decoder.run(mapOf("targets" to targets, "target_length" to length, "states.1" to h, "onnx::Slice_3" to c)).use { result ->
                            val vector = FloatArray(HIDDEN)
                            (result.get(0) as OnnxTensor).copyFloatsTo(vector) // [1, 640, 1]
                            val nextH = FloatArray(2 * HIDDEN)
                            val nextC = FloatArray(2 * HIDDEN)
                            (result.get(2) as OnnxTensor).copyFloatsTo(nextH)
                            (result.get(3) as OnnxTensor).copyFloatsTo(nextC)
                            return DecoderOutput(vector, State(nextH, nextC))
                        }
                    }
                }
            }
        }
    }

    override fun joint(encoded: Encoded, t: Int, decoder: FloatArray): FloatArray {
        val column = FloatArray(encoded.dim) { encoded.data[it * encoded.frames + t] }
        try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(column), longArrayOf(1, encoded.dim.toLong(), 1)).useScrubbed { enc ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(decoder), longArrayOf(1, HIDDEN.toLong(), 1)).useScrubbed { dec ->
                    joiner.run(mapOf("encoder_outputs" to enc, "decoder_outputs" to dec)).use { result ->
                        val out = result.get(0) as OnnxTensor // [1, 1, 1, 8198]
                        val copy = out.floatBuffer // a copy; the search zeroes the logits it gets back
                        val logits = FloatArray(copy.remaining())
                        copy.get(logits)
                        Scrub.zero(copy)
                        return logits
                    }
                }
            }
        } finally {
            column.fill(0f)
        }
    }

    /** Copies the first `into.size` values of an output tensor into [into]; the Java copy ONNX Runtime makes of the output is zeroed afterwards. */
    private fun OnnxTensor.copyFloatsTo(into: FloatArray) {
        val copy = floatBuffer
        try {
            copy.get(into)
        } finally {
            Scrub.zero(copy)
        }
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        runCatching { joiner.close() }
    }

    private companion object {
        const val HIDDEN = 640 // pred_hidden of the model metadata
    }
}

/**
 * Closes the tensor after overwriting the memory behind it that Java can reach: a tensor made from a Java float or int array owns a direct copy of
 * it (getBufferRef), which would otherwise be freed with the audio-derived values in it. Native memory ONNX Runtime allocates itself is not reachable.
 */
internal inline fun <R> OnnxTensor.useScrubbed(block: (OnnxTensor) -> R): R = try {
    block(this)
} finally {
    runCatching { bufferRef.ifPresent { Scrub.zero(it) } }
    close()
}
