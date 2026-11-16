// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.security.SecureRandom
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P16: the export file is Argon2id + AES-256-GCM. Known-answer vector made with independent implementations (OpenSSL 3
 * `openssl kdf ... ARGON2ID` for the key, python-cryptography AES-GCM for the encryption):
 *   passphrase "p4ssphrase-Жщ-Ünï", salt 00..0f, nonce a0..ab, Argon2id m=64 KiB t=3 p=1, plaintext below,
 *   AAD = the 47 header bytes. Wrong passphrase, any damaged byte, truncation and oversized parameters change nothing.
 */
class ExportCodecTest {
    private val passphrase = "p4ssphrase-Жщ-Ünï".toCharArray()
    private val plain = "taen export test vector: Жщ 123".toByteArray()
    private val kat = hex(
        "5441454e424b31000000400000000300000001000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a7a8a9aaab" +
            "d66d82cefbeb60a914bd96ae5fb4ec7829e36f1a8a71b45d86b8b9fa7dc5ab278f2d3939e5553f3826a1522e43fab7171e"
    )
    private val fast = ExportCodec.Kdf(memoryKiB = 64, iterations = 3, lanes = 1) // the test vector's parameters

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class SequenceRandom(vararg val chunks: ByteArray) : SecureRandom() {
        private var i = 0
        override fun nextBytes(bytes: ByteArray) {
            val c = chunks[i++]
            require(c.size == bytes.size) { "unexpected request of ${bytes.size} bytes" }
            c.copyInto(bytes)
        }
    }

    private fun katRandom() = SequenceRandom(ByteArray(16) { it.toByte() }, ByteArray(12) { (0xA0 + it).toByte() })

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun roundTrip() {
        val file = ExportCodec.encrypt(passphrase, plain, fast)
        assertContentEquals(plain, ExportCodec.decrypt(passphrase, file))
    }

    @Test fun knownAnswerVectorDecrypts() {
        assertContentEquals(plain, ExportCodec.decrypt(passphrase, kat))
    }

    // ---- format, damage, limits ------------------------------------------------------------------------

    @Test fun encryptingWithTheVectorsSaltAndNonceGivesTheVectorsBytes() {
        assertContentEquals(kat, ExportCodec.encrypt(passphrase, plain, fast, katRandom()))
    }

    @Test fun argon2idMatchesRfc9106() {
        // RFC 9106 section 5.3 Argon2id test vector (with secret and associated data), through the library the codec uses
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3).withMemoryAsKB(32).withParallelism(4)
            .withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 }).withAdditional(ByteArray(12) { 4 }).build()
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(params)
        val tag = ByteArray(32)
        generator.generateBytes(ByteArray(32) { 1 }, tag)
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", tag.joinToString("") { "%02x".format(it) })
    }

    @Test fun wrongPassphraseIsRejected() {
        val file = ExportCodec.encrypt(passphrase, plain, fast)
        assertFailsWith<ExportException> { ExportCodec.decrypt("p4ssphrase-Жщ-Ünî".toCharArray(), file) }
        assertFailsWith<ExportException> { ExportCodec.decrypt("".toCharArray(), file) }
        assertContentEquals(plain, ExportCodec.decrypt(passphrase, file)) // positive control
    }

    @Test fun everyDamagedByteIsRejected() {
        for (i in kat.indices) {
            val damaged = kat.copyOf()
            damaged[i] = (damaged[i].toInt() xor 0x01).toByte()
            assertFailsWith<ExportException>("byte $i") { ExportCodec.decrypt(passphrase, damaged) }
        }
        assertContentEquals(plain, ExportCodec.decrypt(passphrase, kat)) // the unmodified vector still opens
    }

    @Test fun truncationAndTrailingBytesAreRejected() {
        for (len in 0 until kat.size) assertFailsWith<ExportException>("length $len") { ExportCodec.decrypt(passphrase, kat.copyOf(len)) }
        assertFailsWith<ExportException> { ExportCodec.decrypt(passphrase, kat + byteArrayOf(0)) }
    }

    @Test fun oversizedKdfParametersAreRejectedBeforeAnyWork() {
        for ((mem, iter, lanes) in listOf(Triple(Int.MAX_VALUE, 3, 1), Triple(1_048_576, 3, 1), Triple(64, 0, 1), Triple(64, 100, 1), Triple(64, 3, 0), Triple(64, 3, 99), Triple(0, 3, 1), Triple(-1, 3, 1),
            // just over each limit, with the other two at their maximum: accepting any of them would cost seconds
            Triple(262144, 11, 1), Triple(262145, 3, 1), Triple(262144, 3, 9))) {
            val header = kat.copyOf()
            java.nio.ByteBuffer.wrap(header).putInt(7, mem).putInt(11, iter).putInt(15, lanes)
            val start = System.nanoTime()
            assertFailsWith<ExportException>("$mem/$iter/$lanes") { ExportCodec.decrypt(passphrase, header) }
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "rejected quickly")
        }
    }

    @Test fun shortPassphraseCannotBeUsedToExport() {
        assertFailsWith<IllegalArgumentException> { ExportCodec.encrypt("12345678901".toCharArray(), plain, fast) }
        ExportCodec.encrypt("123456789012".toCharArray(), plain, fast) // 12 characters is the minimum
    }

    @Test fun eachExportUsesNewSaltAndNonce() {
        val a = ExportCodec.encrypt(passphrase, plain, fast)
        val b = ExportCodec.encrypt(passphrase, plain, fast)
        assertTrue(!a.contentEquals(b))
        assertTrue(!a.copyOfRange(19, 35).contentEquals(b.copyOfRange(19, 35)), "salt differs")
        assertTrue(!a.copyOfRange(35, 47).contentEquals(b.copyOfRange(35, 47)), "nonce differs")
    }

    @Test fun exportFileHoldsNoPlaintext() {
        val canary = "TAENcanary-Qx93"
        val cyrillic = "ТАЕНканарче-Жщ93"
        val file = ExportCodec.encrypt(passphrase, ("$canary $cyrillic" + " x".repeat(200)).toByteArray(), fast)
        for (c in listOf(canary, cyrillic)) for (e in encodings(c)) assertEquals(-1, file.indexOfSub(e), c)
        val control = ("$canary").toByteArray() + ByteArray(5) // positive control for the scanner
        assertTrue(control.indexOfSub(canary.toByteArray()) >= 0)
    }

    @Test fun passphraseIsNormalised() {
        val composed = Normalizer.normalize("Ünïcode-passphrase-é", Normalizer.Form.NFC).toCharArray()
        val decomposed = Normalizer.normalize("Ünïcode-passphrase-é", Normalizer.Form.NFD).toCharArray()
        assertTrue(!composed.contentEquals(decomposed))
        val file = ExportCodec.encrypt(composed, plain, fast)
        assertContentEquals(plain, ExportCodec.decrypt(decomposed, file))
    }

    @Test fun emptyAndLargePayloadsRoundTrip() {
        assertContentEquals(ByteArray(0), ExportCodec.decrypt(passphrase, ExportCodec.encrypt(passphrase, ByteArray(0), fast)))
        val big = ByteArray(3_000_000) { (it * 31).toByte() }
        assertContentEquals(big, ExportCodec.decrypt(passphrase, ExportCodec.encrypt(passphrase, big, fast)))
    }

    @Test fun unknownMagicIsRejected() {
        val other = kat.copyOf()
        other[6] = '2'.code.toByte() // TAENBK2
        assertFailsWith<ExportException> { ExportCodec.decrypt(passphrase, other) }
        assertFailsWith<ExportException> { ExportCodec.decrypt(passphrase, "PK\u0003\u0004 a plain zip".toByteArray()) }
    }

    @Test fun inputOverTheSizeLimitIsRejected() {
        assertFailsWith<ExportException> { ExportCodec.decrypt(passphrase, ByteArray(ExportCodec.MAX_FILE_BYTES + 1)) }
    }
}
