// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A PIN or the trigger word becomes bytes for the hash without a String, and nothing of it is left behind in an array. */
class SecretBytesTest {
    private val samples = listOf(
        "", "482915", "Blue#Horse-4711", "é", "é", "日本語1", "😀x", "€ab", "a\u0000b", "\uD800a", "a\uDC00", // empty, ASCII, 2/3/4-byte, NUL, lone surrogates
        "x".repeat(64),
    )

    @Test fun theBytesAreTheUtf8BytesOfTheCharactersAsBefore() {
        for (s in samples) {
            val chars = s.toCharArray()
            val got = SecretBytes.withUtf8(chars) { it.copyOf() }
            assertContentEquals(s.toByteArray(Charsets.UTF_8), got, "sample of ${s.length} chars")
            assertContentEquals(s.toCharArray(), chars) // the characters are not changed
        }
    }

    @Test fun theArrayIsZeroedWhenTheBlockEndsAndWhenItThrows() {
        val chars = "482915".toCharArray()
        lateinit var seen: ByteArray
        assertContentEquals("482915".toByteArray(), SecretBytes.withUtf8(chars) { seen = it; it.copyOf() })
        assertEquals(6, seen.size)
        assertTrue(seen.all { it == 0.toByte() })
        lateinit var seenOnError: ByteArray
        assertFailsWith<IllegalStateException> { SecretBytes.withUtf8(chars) { seenOnError = it; throw IllegalStateException("x") } }
        assertTrue(seenOnError.size == 6 && seenOnError.all { it == 0.toByte() })
    }

    @Test fun theScratchSpaceTheEncodingIsMadeInIsZeroedToo() {
        for (s in samples) {
            val scratch = ByteArray(s.length * 3) { 0x55 } // not zero at the start: the encoding must overwrite or clear all of it
            var inside: ByteArray? = null
            SecretBytes.withUtf8(s.toCharArray(), scratch) { inside = it.copyOf() }
            assertTrue(scratch.all { it == 0.toByte() }, "scratch for ${s.length} chars")
            assertContentEquals(s.toByteArray(Charsets.UTF_8), inside)
        }
        assertFailsWith<IllegalArgumentException> { SecretBytes.withUtf8("abcd".toCharArray(), ByteArray(11)) { } } // too small
    }

    @Test fun theHashIsTheSameAsTheOldStringBasedOne() {
        val kdf = ExportCodec.Kdf(memoryKiB = 64, iterations = 1, lanes = 1)
        val salt = ByteArray(16) { (it * 7 + 1).toByte() }
        for (s in samples.filter { it.isNotEmpty() }) {
            val reference = Argon2BytesGenerator().also {
                it.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(kdf.iterations).withMemoryAsKB(kdf.memoryKiB).withParallelism(kdf.lanes).withSalt(salt).build())
            }.let { g -> ByteArray(32).also { out -> g.generateBytes(String(s.toCharArray()).toByteArray(Charsets.UTF_8), out) } }
            assertContentEquals(reference, PinHasher(kdf).hash(s.toCharArray(), salt), "hash of a sample of ${s.length} chars")
        }
    }
}
