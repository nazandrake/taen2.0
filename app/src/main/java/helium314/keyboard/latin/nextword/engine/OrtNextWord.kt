// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * A decoder-only language model in the export layout of Transformers.js (inputs input_ids, attention_mask, position_ids and
 * past_key_values.<layer>.key/value; outputs logits and present.<layer>.key/value), on ONNX Runtime 1.27.0 (MIT, CPU provider only), from one
 * .onnx file given by path. Besides OrtParakeet this is the only class that touches ONNX Runtime; nothing is written (no optimised-model file, no profile)
 * and ONNX Runtime's own logging is set to fatal.
 *
 * The attention cache of the last window ([KvWindow]) stays in ONNX Runtime's native memory between calls, so a context that grew by one word costs one
 * or two tokens. The Java copies of the text-derived values (token ids, logits) are zeroed after use; the native cache is dropped by [forget] and [close].
 */
class OrtNextWord(path: String, threads: Int) : NextTokenModel {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "taen")
    private val session: OrtSession
    private val shape: Shape
    private var kept: IntArray? = null
    private var cache: OrtSession.Result? = null
    private var last: FloatArray? = null
    private var logitsBuffer: ByteBuffer? = null

    /** What the model file declares: layers and the cache layout, the vocabulary size and which optional inputs it takes. */
    private class Shape(session: OrtSession) {
        val layers: Int
        val kvHeads: Long
        val headDim: Long
        val vocab: Int
        val hasMask: Boolean
        val hasPositions: Boolean
        val presentNames: Set<String>

        init {
            val inputs = session.inputInfo
            layers = inputs.keys.count { it.startsWith(PAST) && it.endsWith(".key") }
            val kv = (inputs["${PAST}0.key"]?.info as? TensorInfo)?.shape ?: longArrayOf(0, 0, 0, 0) // [batch, kv heads, past, head dim]
            kvHeads = kv[1]
            headDim = kv[3]
            vocab = ((session.outputInfo["logits"]?.info as? TensorInfo)?.shape?.getOrNull(2) ?: 0L).toInt()
            hasMask = "attention_mask" in inputs
            hasPositions = "position_ids" in inputs
            presentNames = (0 until layers).flatMap { layer -> KINDS.map { "$PRESENT$layer.$it" } }.toSet()
            require(layers > 0 && kvHeads > 0 && headDim > 0 && vocab > 0 && "input_ids" in inputs) { "not a decoder model with a cache" }
        }
    }

    init {
        env.setTelemetry(false)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            // no memory arena: measured on SmolLM2 with the Python runtime of the same version, the arena keeps about 230 MB more after a few steps
            options.setCPUArenaAllocator(false)
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            env.createSession(path, options)
        }
        shape = try {
            Shape(session)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    val vocabularySize: Int get() = shape.vocab

    override fun logProbs(context: IntArray): FloatArray {
        require(context.isNotEmpty())
        val plan = KvWindow.plan(kept, context)
        if (!plan.reuse) forget()
        val cached = last
        if (plan.reuse && plan.feedFrom == context.size && cached != null) return cached.copyOf()
        var past = if (plan.reuse) kept!!.size else 0
        val feed = context.copyOfRange(plan.feedFrom, context.size)
        try {
            // in chunks of at most CHUNK tokens: the logits of a run hold one row per fed token (151936 floats each for Qwen3), only the last row is used
            var logits: FloatArray? = null
            var from = 0
            while (from < feed.size) {
                val to = minOf(from + CHUNK, feed.size)
                logits = run(feed, from, to, past, to == feed.size)
                past += to - from
                from = to
            }
            last?.fill(0f)
            last = logits
            kept = context.copyOfRange(plan.windowStart, context.size)
            return logits!!.copyOf()
        } catch (e: Throwable) {
            forget()
            throw e
        } finally {
            feed.fill(0)
        }
    }

    /** Feeds `feed[from until to]` after [past] kept tokens; returns the log-probabilities after the last one when [wanted], else null. */
    private fun run(feed: IntArray, from: Int, to: Int, past: Int, wanted: Boolean): FloatArray? {
        val n = to - from
        val total = past + n
        // direct buffers: ONNX Runtime reads them in place, so the token ids have no second copy that could not be zeroed
        val ids = longs(n).also { b -> for (i in from until to) b.put(feed[i].toLong()); b.rewind() }
        val positions = longs(n).also { b -> for (i in 0 until n) b.put((past + i).toLong()); b.rewind() }
        val mask = longs(total).also { b -> for (i in 0 until total) b.put(1L); b.rewind() }
        val owned = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input_ids"] = OnnxTensor.createTensor(env, ids, longArrayOf(1, n.toLong())).also(owned::add)
            if (shape.hasMask) inputs["attention_mask"] = OnnxTensor.createTensor(env, mask, longArrayOf(1, total.toLong())).also(owned::add)
            if (shape.hasPositions) inputs["position_ids"] = OnnxTensor.createTensor(env, positions, longArrayOf(1, n.toLong())).also(owned::add)
            val previous = cache
            for (layer in 0 until shape.layers) {
                for (kind in KINDS) {
                    inputs["$PAST$layer.$kind"] = if (previous != null) previous.get("$PRESENT$layer.$kind").get() as OnnxTensor
                    else OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, shape.kvHeads, 0, shape.headDim)).also(owned::add)
                }
            }
            val needed = CHUNK.toLong() * shape.vocab * 4
            val buffer = logitsBuffer ?: ByteBuffer.allocateDirect(needed.toInt()).order(ByteOrder.nativeOrder()).also { logitsBuffer = it }
            val floats = buffer.asFloatBuffer().also { it.limit(n * shape.vocab) }
            val logitsTensor = OnnxTensor.createTensor(env, floats, longArrayOf(1, n.toLong(), shape.vocab.toLong())).also(owned::add)
            val result = session.run(inputs, shape.presentNames, mapOf("logits" to logitsTensor))
            cache = result
            previous?.close() // after the run: its tensors were the inputs
            return try {
                if (wanted) logSoftmax(floats, (n - 1) * shape.vocab) else null
            } finally {
                zero(floats, n * shape.vocab)
            }
        } finally {
            owned.forEach { runCatching { it.close() } }
            zero(ids)
        }
    }

    private fun logSoftmax(floats: FloatBuffer, from: Int): FloatArray {
        val out = FloatArray(shape.vocab)
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until shape.vocab) {
            val v = floats.get(from + i)
            out[i] = v
            if (v > max) max = v
        }
        var sum = 0.0
        for (v in out) sum += exp((v - max).toDouble())
        val norm = (max + ln(sum)).toFloat()
        for (i in out.indices) out[i] -= norm
        return out
    }

    override fun forget() {
        cache?.close()
        cache = null
        kept?.fill(0)
        kept = null
        last?.fill(0f)
        last = null
    }

    override fun close() {
        forget()
        logitsBuffer = null
        runCatching { session.close() }
    }

    private fun longs(n: Int): LongBuffer = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asLongBuffer()

    private fun zero(buffer: FloatBuffer, count: Int) { for (i in 0 until count) buffer.put(i, 0f) }

    private fun zero(buffer: LongBuffer) { for (i in 0 until buffer.capacity()) buffer.put(i, 0L) }

    private companion object {
        const val PAST = "past_key_values."
        const val PRESENT = "present."
        val KINDS = arrayOf("key", "value")
        const val CHUNK = 8
    }
}
