// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The file is not a Taen export, is damaged, or the passphrase is wrong (these cannot be told apart, on purpose). */
class ExportException(message: String) : Exception(message)

/**
 * The encrypted export file (P16): magic `TAENBK1`, the Argon2id parameters (memory in KiB,
 * iterations, lanes; uint32 big endian each), a 16-byte salt, a 12-byte nonce, then AES-256-GCM of the payload and its
 * 128-bit tag. The 47 header bytes are the associated data, so changing any parameter fails the tag. There is no plain
 * option. The payload is whatever the caller packs (a zip of the data to export, see [ExportBundle]).
 * Argon2id is from Bouncy Castle (the platform has none); AES-GCM is the platform's.
 */
object ExportCodec {
    const val MIN_PASSPHRASE_LENGTH = 12
    /** An export is at most this large (bounds the memory the import needs). */
    const val MAX_FILE_BYTES = 64 * 1024 * 1024

    data class Kdf(val memoryKiB: Int, val iterations: Int, val lanes: Int)

    /** 64 MiB, 3 passes, 1 lane: about 2 seconds on the reference phone (checked on the device). */
    val EXPORT_KDF = Kdf(memoryKiB = 65536, iterations = 3, lanes = 1)

    // limits for the parameters read from a file: a crafted file must not exhaust the memory or the time
    private const val MAX_MEMORY_KIB = 262144 // 256 MiB
    private const val MAX_ITERATIONS = 10
    private const val MAX_LANES = 8

    private val MAGIC = "TAENBK1".toByteArray()
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val HEADER_SIZE = 7 + 12 + SALT_SIZE + NONCE_SIZE

    /** Throws [IllegalArgumentException] if the passphrase has fewer than [MIN_PASSPHRASE_LENGTH] characters. */
    fun encrypt(passphrase: CharArray, plain: ByteArray, kdf: Kdf = EXPORT_KDF, random: SecureRandom = SecureRandom()): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "passphrase too short" }
        require(plain.size <= MAX_FILE_BYTES - HEADER_SIZE - TAG_BITS / 8) { "export too large" }
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).putInt(kdf.memoryKiB).putInt(kdf.iterations).putInt(kdf.lanes).put(salt).put(nonce).array()
        val key = deriveKey(passphrase, salt, kdf.memoryKiB, kdf.iterations, kdf.lanes)
        try {
            return header + gcm(Cipher.ENCRYPT_MODE, key, nonce, header, plain, 0, plain.size)
        } finally {
            key.fill(0)
        }
    }

    /** Returns the payload, or throws [ExportException]; nothing else is touched either way. */
    fun decrypt(passphrase: CharArray, file: ByteArray): ByteArray {
        if (file.size > MAX_FILE_BYTES) throw ExportException("file too large")
        if (file.size < HEADER_SIZE + TAG_BITS / 8) throw ExportException("not an export file")
        val buf = ByteBuffer.wrap(file)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw ExportException("not an export file")
        val memory = buf.getInt()
        val iterations = buf.getInt()
        val lanes = buf.getInt()
        // checked before any key derivation (a damaged or crafted file costs nothing)
        if (memory !in 8 * lanes..MAX_MEMORY_KIB || iterations !in 1..MAX_ITERATIONS || lanes !in 1..MAX_LANES)
            throw ExportException("unsupported parameters")
        val salt = ByteArray(SALT_SIZE).also { buf.get(it) }
        val nonce = ByteArray(NONCE_SIZE).also { buf.get(it) }
        val key = deriveKey(passphrase, salt, memory, iterations, lanes)
        try {
            return gcm(Cipher.DECRYPT_MODE, key, nonce, file.copyOf(HEADER_SIZE), file, HEADER_SIZE, file.size - HEADER_SIZE)
        } finally {
            key.fill(0)
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray, offset: Int, length: Int): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(input, offset, length)
        } catch (e: java.security.GeneralSecurityException) { // AEADBadTagException: wrong passphrase or damaged file
            throw ExportException("wrong passphrase or damaged file")
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, memoryKiB: Int, iterations: Int, lanes: Int): ByteArray {
        // the same text typed on another keyboard may be composed differently: NFC
        val bytes = Normalizer.normalize(String(passphrase), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations).withMemoryAsKB(memoryKiB).withParallelism(lanes).withSalt(salt).build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            return ByteArray(32).also { generator.generateBytes(bytes, it) }
        } finally {
            bytes.fill(0)
        }
    }
}
