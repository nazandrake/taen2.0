// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/** The plain native dictionary directory of an earlier version. */
interface LegacyLearned {
    fun exists(): Boolean
    /** The old dictionary in the serialized format, or null when it cannot be read or is in an unsupported format. */
    fun toSerialized(): ByteArray?
    fun delete()
}

/** [available] is false while the storage cannot be read (device locked). [bytes] is null when nothing is stored. */
class LearnedLoad(val available: Boolean, val bytes: ByteArray?)

/**
 * Where the serialized learned-word dictionary of one language lives: one AES-GCM store in the credential-encrypted
 * tier. The owner of the rules for the learned data (P7):
 * - while the store cannot be read (device locked) [load] reports it unavailable and [save] writes nothing, so an empty
 *   dictionary that was created in that state can never replace the stored words;
 * - a damaged store is deleted by [SecureStore] and the words are lost (fail closed, no fallback);
 * - the plain dictionary directory of an earlier version is moved into the store and deleted only after the
 *   encrypted copy is stored; an unreadable one is deleted.
 */
class LearnedStorage(private val store: () -> SecureStore?, private val legacy: LegacyLearned?) {
    fun isAvailable(): Boolean = store() != null

    fun load(): LearnedLoad {
        val s = store() ?: return UNAVAILABLE
        return when (val content = s.read()) {
            is StoreContent.Unavailable -> UNAVAILABLE
            is StoreContent.Data -> {
                if (legacy?.exists() == true) legacy.delete() // leftover of an interrupted move: the stored copy is newer
                LearnedLoad(true, content.bytes)
            }
            is StoreContent.Missing, is StoreContent.Reset -> moveLegacyIn(s)
        }
    }

    private fun moveLegacyIn(s: SecureStore): LearnedLoad {
        val old = legacy
        if (old == null || !old.exists()) return LearnedLoad(true, null)
        val bytes = try { old.toSerialized() } catch (_: Exception) { null }
        if (bytes == null) {
            old.delete() // nothing usable, and a plain file must not linger
            return LearnedLoad(true, null)
        }
        val stored = try {
            s.write(bytes)
            true
        } catch (_: StoreWriteException) {
            false
        } catch (_: VaultLockedException) {
            false
        }
        if (stored) old.delete() // only after the encrypted copy is verified and in place
        return LearnedLoad(true, bytes)
    }

    /** Stores [bytes]; false (nothing changed) when the store is unavailable or the write failed. */
    fun save(bytes: ByteArray): Boolean {
        val s = store() ?: return false
        return try {
            s.write(bytes)
            true
        } catch (_: StoreWriteException) {
            false
        } catch (_: VaultLockedException) {
            false
        }
    }

    fun delete() {
        store()?.delete()
        legacy?.delete()
    }

    private companion object {
        val UNAVAILABLE = LearnedLoad(false, null)
    }
}
