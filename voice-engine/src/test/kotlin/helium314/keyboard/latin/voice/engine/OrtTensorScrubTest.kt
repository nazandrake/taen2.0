// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import java.io.File
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The assumption OrtParakeet.useScrubbed makes about ONNX Runtime 1.27.0, checked against the real library: a tensor made from a Java float
 * array keeps its own direct copy of the values (getBufferRef), which Scrub can reach; the Java-side arrays and copies are then zero.
 */
class OrtTensorScrubTest {
    @Test fun aTensorMadeFromAnArrayHoldsACopyThatScrubReaches() {
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "taen-test")
        val values = FloatArray(6) { it + 1f }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 6)).use { tensor ->
            val ref = tensor.bufferRef
            assertTrue(ref.isPresent, "the tensor exposes the buffer that backs it")
            assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f), (0 until 6).map { tensor.floatBuffer.get(it) }) // positive control: the values are in it
            Scrub.zero(ref.get())
            assertEquals(List(6) { 0f }, (0 until 6).map { tensor.floatBuffer.get(it) }, "the tensor's own copy is zero after Scrub")
            assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f), values.toList()) // the caller's array is a separate thing: the caller zeroes it
        }
    }

    private fun peek(buffer: java.nio.Buffer, index: Int): Float = when (buffer) {
        is FloatBuffer -> buffer.get(index)
        is java.nio.ByteBuffer -> buffer.asFloatBuffer().get(index)
        else -> error("unexpected buffer type")
    }

    @Test fun useScrubbedZeroesTheTensorCopyAlsoWhenTheBlockFails() {
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "taen-test")
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(4) { it + 1f }), longArrayOf(1, 4))
        val ref = tensor.bufferRef.get() // the direct copy: a Java-allocated buffer, still readable after close
        assertEquals(listOf(1f, 2f, 3f, 4f), (0 until 4).map { peek(ref, it) }) // positive control
        assertFailsWith<IllegalStateException> { tensor.useScrubbed { error("boom") } }
        assertEquals(List(4) { 0f }, (0 until 4).map { peek(ref, it) })
    }

    @Test fun everyTensorOrtParakeetCreatesIsScrubbedOrAConstant() {
        // production wiring: the decoder state, the decoder vector, the features and the encoder column go through useScrubbed (a plain use() would
        // free the audio-derived copy unzeroed); only the length tensors, which hold no audio-derived value, may use plain use() and say so
        val source = File("../app/src/main/java/helium314/keyboard/latin/voice/engine/OrtParakeet.kt")
        assertTrue(source.isFile, "the source is found from the module directory")
        val creating = source.readLines().filter { "OnnxTensor.createTensor(" in it }
        assertEquals(8, creating.size, "encoder 2, decoder 4, joiner 2: every creation is looked at")
        val loose = creating.filter { "useScrubbed" !in it && "// constant" !in it }
        assertEquals(emptyList(), loose)
        assertEquals(2, creating.count { "// constant" in it }) // the two length tensors
    }

    /** Hand-written ONNX models of identity nodes (no python onnx package needed): enough for OrtParakeet to run its wrapping code for real. */
    private fun identityModel(inputs: List<Triple<String, Int, List<Long>>>, outputs: List<Pair<String, String>>, outputTypes: List<Pair<Int, List<Long>>>): ByteArray {
        fun varint(v: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var n = v
            while (n >= 0x80) { out.write(((n and 0x7F) or 0x80).toInt()); n = n ushr 7 }
            out.write(n.toInt())
            return out.toByteArray()
        }
        fun len(field: Int, bytes: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
        fun num(field: Int, v: Long) = varint(field.toLong() shl 3) + varint(v)
        fun text(field: Int, v: String) = len(field, v.toByteArray())
        fun valueInfo(name: String, type: Int, dims: List<Long>): ByteArray {
            val shape = dims.fold(ByteArray(0)) { acc, d -> acc + len(1, num(1, d)) }
            return text(1, name) + len(2, len(1, num(1, type.toLong()) + len(2, shape)))
        }
        val nodes = outputs.fold(ByteArray(0)) { acc, (out, from) -> acc + len(1, text(1, from) + text(2, out) + text(4, "Identity")) }
        val graph = nodes + text(2, "g") +
            inputs.fold(ByteArray(0)) { acc, (n, t, d) -> acc + len(11, valueInfo(n, t, d)) } +
            outputs.indices.fold(ByteArray(0)) { acc, i -> acc + len(12, valueInfo(outputs[i].first, outputTypes[i].first, outputTypes[i].second)) }
        return num(1, 8) + len(8, num(2, 13)) + len(7, graph)
    }

    private fun parakeetOverIdentityModels(): OrtParakeet {
        val dir = File("build/tmp/ort-parakeet-identity").apply { mkdirs() }
        val float = 1
        val int32 = 6
        val decoder = identityModel(
            listOf(Triple("targets", int32, listOf(1L, 1L)), Triple("target_length", int32, listOf(1L)),
                Triple("states.1", float, listOf(2L, 1L, 640L)), Triple("onnx::Slice_3", float, listOf(2L, 1L, 640L))),
            listOf("o0" to "states.1", "o1" to "target_length", "o2" to "states.1", "o3" to "onnx::Slice_3"),
            listOf(float to listOf(2L, 1L, 640L), int32 to listOf(1L), float to listOf(2L, 1L, 640L), float to listOf(2L, 1L, 640L)),
        )
        val joiner = identityModel(
            listOf(Triple("encoder_outputs", float, listOf(1L, 1024L, 1L)), Triple("decoder_outputs", float, listOf(1L, 640L, 1L))),
            listOf("logits" to "encoder_outputs"),
            listOf(float to listOf(1L, 1024L, 1L)),
        )
        File(dir, "decoder.onnx").writeBytes(decoder)
        File(dir, "joiner.onnx").writeBytes(joiner)
        // the encoder is not run here: any valid model file opens
        return OrtParakeet(File(dir, "decoder.onnx").path, File(dir, "decoder.onnx").path, File(dir, "joiner.onnx").path, 1)
    }

    @Test fun decoderStepAndJointReturnTheRightValuesThroughTheScrubbedTensors() {
        parakeetOverIdentityModels().use { model ->
            val h = FloatArray(1280) { it + 1f }
            val c = FloatArray(1280) { 5000f + it }
            val out = model.decoderStep(7, OrtParakeet.State(h.copyOf(), c.copyOf()))
            // the identity decoder returns the h input as the prediction vector (first 640 values) and as the next h, the c input as the next c
            assertEquals(h.take(640), out.vector.toList())
            val next = out.state as OrtParakeet.State
            assertEquals(h.toList(), next.h.toList())
            assertEquals(c.toList(), next.c.toList())
            // the identity joiner returns the encoder column of frame t
            val encoded = Encoded(FloatArray(1024 * 2) { it * 0.5f }, 1024, 2)
            val decoderVector = FloatArray(640) { 3f }
            val logits = model.joint(encoded, 1, decoderVector)
            assertEquals((0 until 1024).map { (it * 2 + 1) * 0.5f }, logits.toList())
            assertEquals(List(640) { 3f }, decoderVector.toList()) // the caller's vector is the caller's to clear
        }
    }
}
