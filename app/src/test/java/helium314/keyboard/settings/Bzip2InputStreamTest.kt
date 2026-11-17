// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The bzip2 decoder of the voice model archive. Fixtures are made by tools/voice/make_bzip2_fixtures.py with Python's bz2 (libbz2); the expected
 * length and SHA-256 in fixtures.txt come from hashlib, not from this code.
 */
class Bzip2InputStreamTest {
    private fun fixture(name: String): ByteArray = File("src/test/resources/bzip2/$name.bz2").readBytes()
    private fun manifest(): Map<String, Pair<Long, String>> = File("src/test/resources/bzip2/fixtures.txt").readLines().filter { it.isNotBlank() }
        .associate { line -> line.split('\t').let { it[0] to (it[1].toLong() to it[2]) } }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decode(bytes: ByteArray): ByteArray = Bzip2InputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun aShortTextIsDecodedToTheExactBytes() {
        assertEquals("hello hello hello bzip2 world\n", String(decode(fixture("short-text")), Charsets.UTF_8))
    }

    @Test fun aRunLengthHeavyFileIsDecodedToTheReferenceHash() {
        val (length, hash) = manifest().getValue("runs")
        val plain = decode(fixture("runs"))
        assertEquals(length, plain.size.toLong())
        assertEquals(hash, sha256(plain))
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun everyFixtureIsDecodedToTheReferenceLengthAndHash() { // empty, one byte, runs of 4 to 70000, noise (all symbols), several blocks at level 1, every block size
        val expected = manifest().filterKeys { !it.endsWith(".tar.bz2") }
        assertEquals(8, expected.size) // positive control: the manifest is read
        for ((name, lengthAndHash) in expected) {
            val plain = decode(fixture(name))
            assertEquals(lengthAndHash.first, plain.size.toLong(), name)
            assertEquals(lengthAndHash.second, sha256(plain), name)
        }
    }

    @Test fun readingInSmallPiecesAndByteByByteGivesTheSameBytes() {
        val (_, hash) = manifest().getValue("mixed")
        val viaBuffer = ByteArrayInputStream(fixture("mixed")).let { Bzip2InputStream(it) }.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val piece = ByteArray(7)
            while (true) { val n = s.read(piece, 0, piece.size); if (n < 0) break; out.write(piece, 0, n) }
            out.toByteArray()
        }
        assertEquals(hash, sha256(viaBuffer))
        val viaSingle = Bzip2InputStream(ByteArrayInputStream(fixture("short-text"))).use { s -> generateSequence { s.read().takeIf { it >= 0 } }.map { it.toByte() }.toList().toByteArray() }
        assertEquals("hello hello hello bzip2 world\n", String(viaSingle))
        val s = Bzip2InputStream(ByteArrayInputStream(fixture("one-byte")))
        assertEquals('x'.code, s.read())
        assertEquals(-1, s.read()) // the end, and again
        assertEquals(-1, s.read())
        assertEquals(-1, s.read(ByteArray(4), 0, 4))
    }

    @Test fun aWrongBlockCrcIsAnErrorNotWrongData() { // the CRC of the first block is the four bytes after "BZh9" and the 48-bit block magic
        val bytes = fixture("short-text")
        bytes[10] = (bytes[10].toInt() xor 0x01).toByte()
        assertFailsWith<IOException> { decode(bytes) }
    }

    @Test fun aWrongStreamCrcIsAnErrorNotWrongData() { // the combined CRC ends the stream: flip a bit two bytes before the end (inside the CRC, the padding is under 8 bits)
        val bytes = fixture("text-multi-block")
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0x01).toByte()
        assertFailsWith<IOException> { decode(bytes) }
    }

    @Test fun aFlippedBitInTheCompressedDataIsAnErrorForEveryPosition() { // 40 positions over the data of a multi-block file: none may give output without an error
        val original = fixture("mixed")
        for (k in 1..40) {
            val bytes = original.copyOf()
            val at = 20 + (original.size - 40) * k / 41
            bytes[at] = (bytes[at].toInt() xor 0x10).toByte()
            assertFailsWith<IOException>("bit flipped at $at") { decode(bytes) }
        }
    }

    @Test fun aTruncatedStreamIsAnError() {
        for (cut in listOf(0, 3, 4, 10, 40)) assertFailsWith<IOException>("cut $cut") { decode(fixture("text-multi-block").copyOf(cut)) }
        assertFailsWith<IOException> { decode(fixture("text-multi-block").let { it.copyOf(it.size - 8) }) }
        // positive control: the whole file decodes
        assertEquals(490043, decode(fixture("text-multi-block")).size)
    }

    @Test fun notABzip2StreamAndUnsupportedVariantsAreRefused() {
        assertFailsWith<IOException> { decode("not bzip2 at all".toByteArray()) }
        assertFailsWith<IOException> { decode(fixture("short-text").also { it[3] = '0'.code.toByte() }) } // block size 0
        assertFailsWith<IOException> { decode(fixture("short-text").also { it[4] = 0 }) } // block magic
        // the old randomised block: the flag is the bit after the 32-bit CRC, i.e. the top bit of byte 14
        assertFailsWith<IOException> { decode(fixture("short-text").also { it[14] = (it[14].toInt() or 0x80).toByte() }) }
    }

    @Test fun aDamagedHeaderNeverEndsInAnOtherExceptionType() { // an index out of range or a negative size would crash the settings: every damage is an IOException
        val original = fixture("all-byte-values")
        for (at in 4 until 120) {
            for (mask in listOf(0x01, 0x80, 0xff)) {
                val bytes = original.copyOf()
                bytes[at] = (bytes[at].toInt() xor mask).toByte()
                try { decode(bytes) } catch (_: IOException) { /* the expected answer */ }
            }
        }
    }

    @Test fun badArgumentsOfReadAreRefused() {
        val s = Bzip2InputStream(ByteArrayInputStream(fixture("short-text")))
        assertFailsWith<IndexOutOfBoundsException> { s.read(ByteArray(4), 3, 5) }
        assertEquals(0, s.read(ByteArray(4), 0, 0))
    }
}
