// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScrubTest {
    @Test fun heapFloatBufferIsZeroedWithoutMovingPositionOrLimit() {
        val array = FloatArray(8) { it + 1f }
        val buffer = FloatBuffer.wrap(array)
        buffer.position(3); buffer.limit(6)
        Scrub.zero(buffer)
        assertTrue(array.all { it == 0f }) // the whole backing array, not only the window
        assertEquals(3, buffer.position()); assertEquals(6, buffer.limit())
    }

    @Test fun directBuffersOfEveryTypeAreZeroed() {
        val bytes = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).also { b -> for (i in 0 until 16) b.put(i, (i + 1).toByte()) }
        val floats = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer().also { f -> for (i in 0 until 4) f.put(i, i + 1f) }
        val ints = IntBuffer.wrap(IntArray(4) { it + 1 })
        val longs = LongBuffer.wrap(LongArray(4) { it + 1L })
        listOf(bytes, floats, ints, longs).forEach { Scrub.zero(it) }
        assertTrue((0 until 16).all { bytes.get(it) == 0.toByte() })
        assertTrue((0 until 4).all { floats.get(it) == 0f && ints.get(it) == 0 && longs.get(it) == 0L })
    }

    @Test fun aReadOnlyBufferIsReportedNotSilentlyLeftFilled() {
        assertFailsWith<java.nio.ReadOnlyBufferException> { Scrub.zero(FloatBuffer.wrap(FloatArray(2) { 1f }).asReadOnlyBuffer()) }
    }
}
