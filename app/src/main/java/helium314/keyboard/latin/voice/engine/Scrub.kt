// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

/**
 * P20: everything derived from the audio (signal, spectrum, features, encoder output, joiner input and output) is overwritten with zeros
 * as soon as the next stage has used it. Pure Kotlin, no Android or ONNX Runtime type. What cannot be reached from here (ONNX Runtime's own
 * native memory: arena, activations, output tensors) is listed in PRIVACY.md P20.
 */
internal object Scrub {
    /** Zeros the whole buffer (heap or direct, any element type, up to its capacity); position and limit are as they were afterwards. */
    fun zero(buffer: Buffer) {
        val position = buffer.position()
        val limit = buffer.limit()
        buffer.clear() // absolute writes need the limit at the capacity
        try {
            zeroAll(buffer)
        } finally {
            buffer.limit(limit) // separate statements: Buffer.limit returns Buffer on older Android libraries
            buffer.position(position)
        }
    }

    private fun zeroAll(buffer: Buffer) {
        when (buffer) {
            is FloatBuffer -> for (i in 0 until buffer.capacity()) buffer.put(i, 0f)
            is ByteBuffer -> for (i in 0 until buffer.capacity()) buffer.put(i, 0)
            is IntBuffer -> for (i in 0 until buffer.capacity()) buffer.put(i, 0)
            is LongBuffer -> for (i in 0 until buffer.capacity()) buffer.put(i, 0L)
            is ShortBuffer -> for (i in 0 until buffer.capacity()) buffer.put(i, 0)
            is DoubleBuffer -> for (i in 0 until buffer.capacity()) buffer.put(i, 0.0)
            else -> throw IllegalArgumentException("unsupported buffer type")
        }
    }
}
