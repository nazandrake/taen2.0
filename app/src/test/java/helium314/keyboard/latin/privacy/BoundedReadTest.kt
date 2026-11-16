// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [readAtMost] replaces InputStream.readNBytes, which does not exist on Android below API 33 (it crashed the import of a
 * backup on the reference phone with NoSuchMethodError). It must only use read(ByteArray, Int, Int) and never read past the limit.
 */
class BoundedReadTest {
    private fun bytes(n: Int) = ByteArray(n) { (it * 7).toByte() }

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun readsEverythingBelowTheLimit() {
        assertContentEquals(bytes(100), ByteArrayInputStream(bytes(100)).readAtMost(1000))
    }

    @Test fun stopsOneByteAfterTheLimit() { // the caller compares the size with the limit to know it was exceeded
        assertEquals(11, ByteArrayInputStream(bytes(500)).readAtMost(10).size)
    }

    // ---- boundaries and awkward streams ------------------------------------------------------------------

    @Test fun exactlyTheLimitIsNotOver() {
        assertEquals(10, ByteArrayInputStream(bytes(10)).readAtMost(10).size)
        assertEquals(11, ByteArrayInputStream(bytes(11)).readAtMost(10).size)
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readAtMost(10).size)
        assertEquals(1, ByteArrayInputStream(bytes(5)).readAtMost(0).size)
    }

    @Test fun streamsThatReturnShortReadsAreReadToTheEnd() {
        val source = bytes(1000)
        val trickle = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < source.size) source[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= source.size) return -1
                val n = minOf(len, 3, source.size - pos) // never more than 3 bytes per call
                System.arraycopy(source, pos, b, off, n)
                pos += n
                return n
            }
        }
        assertContentEquals(source, trickle.readAtMost(5000))
    }

    @Test fun doesNotReadMoreThanNeededFromAnEndlessStream() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertEquals(1001, endless.readAtMost(1000).size)
        assertEquals(true, served <= 1001 + 8192) // at most one buffer beyond the limit was requested
    }
}
