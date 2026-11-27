// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The two file operations that decide whether a write is atomic. A test replaces them to simulate a failing file system. */
interface StoreFiles {
    /** Writes [bytes] to [file] and forces them to disk. */
    fun writeSynced(file: File, bytes: ByteArray)
    /** Replaces [to] with [from]; returns false when that failed (then [to] must be untouched). */
    fun replace(from: File, to: File): Boolean
    /** Reads the whole [file]. An I/O error is an [java.io.IOException]: a transient error never counts as a damaged file. */
    fun read(file: File): ByteArray = file.readBytes()
}

object DefaultStoreFiles : StoreFiles {
    override fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    override fun replace(from: File, to: File): Boolean = from.renameTo(to) // atomic on the same file system

    /** Writes [bytes] to a temporary file next to [file], syncs it and renames it over [file]. For small plain markers that hold no user data (the wipe marker). */
    fun atomicWrite(file: File, bytes: ByteArray): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            writeSynced(tmp, bytes)
            replace(tmp, file)
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }
}

/** One lock per file path, shared by every object that writes that file (the SecureStore objects and DataKeys of a path). */
internal object FileLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    fun forFile(file: File): Any = locks.getOrPut(file.absolutePath) { Any() }
}

class StoreWriteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Result of [SecureStore.read]. */
sealed interface StoreContent {
    /** No store file: nothing was saved yet. */
    object Missing : StoreContent
    class Data(val bytes: ByteArray) : StoreContent
    /** The file was damaged (tag mismatch, broken format, unknown version, key lost for good) and has been deleted (unless the caller asked to keep it to replace it). Callers start empty, or with their reset values. */
    object Reset : StoreContent
    /** The key is unavailable (device locked, screen locked, key file unreadable) or a transient error stopped the read. The file is untouched. */
    object Unavailable : StoreContent
}

/**
 * One AES-256-GCM encrypted file `<name>.enc`. The store name and [schemaVersion] are the AAD, so a
 * file cannot be moved to another store or schema. Writes go to a temporary file that is synced and decrypted again
 * before it replaces the old file, so the old generation survives every failed write. A file that fails its integrity check
 * (authentication tag, magic, schema, length) is deleted (or, with `deleteDamaged = false`, left for the caller's replacement) and reported as [StoreContent.Reset]; there is no fallback to old or
 * plain data. Any other failure (I/O error, an unusable key) is [StoreContent.Unavailable] and deletes nothing.
 */
class SecureStore(
    private val dir: File,
    val name: String,
    private val schemaVersion: Int,
    /** Returns a copy of the key that the store owns and zeroes after each operation (never a shared array). */
    private val dataKey: () -> ByteArray,
    private val files: StoreFiles = DefaultStoreFiles,
    private val random: SecureRandom = SecureRandom(),
) {
    val file = File(dir, "$name.enc")
    private val tmp = File(dir, "$name.enc.tmp")
    // every SecureStore object of the same file shares one lock, so two objects can never write the temporary file at once
    private val lock = FileLocks.forFile(file)

    fun exists() = file.exists()

    /**
     * [deleteDamaged] false: a damaged file is reported as [StoreContent.Reset] but stays on disk, for a caller that replaces it at once with [write]
     * (write-new-then-atomic-rename). The file is then never missing while the replacement is not in place: a failed or interrupted write leaves the
     * damaged file, and the next start is a reset again. The settings store uses this (EncryptedPrefs); a caller that does not replace the file
     * deletes it, so a store of that kind never stays damaged.
     */
    fun read(deleteDamaged: Boolean = true): StoreContent = synchronized(lock) {
        if (!file.exists()) return@synchronized StoreContent.Missing
        val key = try { dataKey() } catch (_: Exception) { return@synchronized StoreContent.Unavailable }
        try {
            if (!usable(key)) return@synchronized StoreContent.Unavailable // a broken caller, not damaged data
            val blob = try { files.read(file) } catch (_: IOException) { return@synchronized StoreContent.Unavailable }
            val plain = try {
                decrypt(key, blob)
            } catch (_: AEADBadTagException) {
                null // the data does not authenticate under this key: damaged or written under a lost key
            } catch (_: IllegalArgumentException) {
                null // broken format: too short, wrong magic, unknown schema version
            } catch (_: Exception) {
                return@synchronized StoreContent.Unavailable // cipher or provider failure: may pass, delete nothing
            }
            if (plain == null) {
                if (deleteDamaged) delete()
                return@synchronized StoreContent.Reset
            }
            StoreContent.Data(plain)
        } finally {
            key.fill(0) // the supplier hands out a copy that is ours
        }
    }

    /** Throws [StoreWriteException] (old file untouched) or [VaultLockedException] (device locked, nothing written). */
    fun write(plain: ByteArray): Unit = synchronized(lock) {
        try {
            val key = dataKey()
            try {
                if (!usable(key)) throw StoreWriteException("no usable data key for $name")
                dir.mkdirs()
                val encrypted = encrypt(key, plain)
                files.writeSynced(tmp, encrypted)
                val check = decrypt(key, files.read(tmp))
                if (!check.contentEquals(plain)) throw StoreWriteException("verification of $name failed")
                if (!files.replace(tmp, file)) throw StoreWriteException("could not replace $name")
            } finally {
                key.fill(0) // the supplier hands out a copy that is ours
            }
        } catch (e: VaultLockedException) {
            throw e
        } catch (e: StoreWriteException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw StoreWriteException("could not write $name", e)
        }
    }

    fun delete() = synchronized(lock) {
        file.delete()
        tmp.delete()
    }

    /** A 256-bit key that is not all zeros: a wiped key (never a real one) must neither decrypt nor encrypt, so it can never delete or write a store. */
    private fun usable(key: ByteArray) = key.size == KEY_SIZE && key.any { it.toInt() != 0 }

    private fun aad() = "taen-store:$name:$schemaVersion".toByteArray()

    private fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad())
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(MAGIC.size + 4 + NONCE_SIZE + body.size)
            .put(MAGIC).putInt(schemaVersion).put(nonce).put(body).array()
    }

    private fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        val header = MAGIC.size + 4 + NONCE_SIZE
        require(blob.size >= header + TAG_BITS / 8) { "store too short" }
        val buf = ByteBuffer.wrap(blob)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "bad magic" }
        require(buf.getInt() == schemaVersion) { "unknown schema version" }
        val nonce = ByteArray(NONCE_SIZE).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad())
        return cipher.doFinal(blob, header, blob.size - header)
    }

    companion object {
        private val MAGIC = "TAEN".toByteArray()
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
    }
}
