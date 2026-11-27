// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.security.SecureRandom

/**
 * The 256-bit data key of one tier (DEK_ce or DEK_de). It is stored wrapped by the [KeyVault] in
 * [keyFile] and unwrapped once into memory. If the vault key is permanently gone, invalidated or does not authenticate the
 * file ([VaultKeyException]), the old data key is unrecoverable: the vault key is deleted, the file is replaced by a new random
 * key, and every store written under the old key then fails its tag check and is deleted on its next read. While the device is
 * locked [key] throws [VaultLockedException], and for a transient failure of the vault or of reading the file it throws
 * [VaultUnavailableException]: in both cases nothing changes and nothing is replaced.
 * [allowed] is the gate of the tier (the screen lock closes it for the credential-encrypted tier): while it returns false no
 * key is handed out, not even the cached one, so no store of the tier can be read or written.
 * [deviceLocked] tells whether the device is locked (the credential-encrypted tier: its vault key needs an unlocked device). While it is true every
 * failure is a [VaultLockedException] and no key is deleted or created, whatever kind of error the platform reports.
 */
class DataKeys(
    private val vault: KeyVault,
    private val tier: Tier,
    /** The wrapped key: also tells which directory this object belongs to (Privacy reuses it only for that directory). */
    val keyFile: File,
    private val files: StoreFiles = DefaultStoreFiles,
    private val random: SecureRandom = SecureRandom(),
    private val allowed: () -> Boolean = { true },
    private val deviceLocked: () -> Boolean = { false },
) {
    private var cached: ByteArray? = null
    private val lock = FileLocks.forFile(keyFile)

    /**
     * A copy of the key that the caller owns and zeroes after use. The cache itself is never handed out: [evict] wipes the
     * cache, and a store that still held the cached array would then decrypt or write under all zeros.
     */
    @Throws(VaultLockedException::class, VaultUnavailableException::class, StoreWriteException::class)
    fun key(): ByteArray = synchronized(lock) {
        if (!allowed()) throw VaultLockedException("storage is locked")
        cached?.let { return it.copyOf() }
        // While the device is locked (asked before and after, so that a lock or an unlock during the call counts too), any failure of the vault is
        // "locked, try later": a platform may report the refused key of a locked device as a lost one, and that must never replace the key.
        val lockedBefore = deviceLocked()
        try {
            val loaded = load()
            if (loaded != null) {
                cached = loaded
                return loaded.copyOf()
            }
            if (lockedBefore || deviceLocked()) throw VaultLockedException("no new data key while the device is locked")
            if (keyFile.exists()) vault.deleteKey(tier) // permanent loss: an invalidated vault key can never wrap again
            val created = create()
            cached = created
            created.copyOf()
        } catch (e: VaultLockedException) {
            throw e
        } catch (e: Exception) {
            if (lockedBefore || deviceLocked()) throw VaultLockedException("the device is locked", e)
            throw e
        }
    }

    /** Forgets the in-memory key (screen lock with "evict keys"); the next [key] unwraps it again. */
    fun evict() = synchronized(lock) {
        cached?.fill(0)
        cached = null
    }

    /** The key, or null when there is none to load or it is lost for good (then a new one may start). */
    private fun load(): ByteArray? {
        if (!keyFile.exists()) return null
        val wrapped = try {
            files.read(keyFile)
        } catch (e: java.io.IOException) {
            throw VaultUnavailableException("the wrapped data key cannot be read now", e) // an I/O error is no reason to replace it
        }
        return try {
            vault.unwrap(tier, wrapped).takeIf { it.size == KEY_SIZE }
        } catch (_: VaultKeyException) {
            null
        }
        // VaultLockedException and VaultUnavailableException propagate: a locked device or a Keystore hiccup must not cause a key reset
    }

    private fun create(): ByteArray {
        val dek = ByteArray(KEY_SIZE).also { random.nextBytes(it) }
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        try {
            keyFile.parentFile?.mkdirs()
            files.writeSynced(tmp, vault.wrap(tier, dek))
            if (!vault.unwrap(tier, files.read(tmp)).contentEquals(dek)) throw StoreWriteException("data key verification failed")
            if (!files.replace(tmp, keyFile)) throw StoreWriteException("could not store the data key")
        } catch (e: VaultLockedException) {
            tmp.delete()
            throw e
        } catch (e: StoreWriteException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw StoreWriteException("could not create the data key", e)
        }
        return dek
    }

    companion object {
        const val KEY_SIZE = 32
    }
}
