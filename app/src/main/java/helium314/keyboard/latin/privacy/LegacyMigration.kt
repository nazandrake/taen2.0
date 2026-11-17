// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.SharedPreferences

/** The plain (unencrypted) SharedPreferences file of an earlier version. */
interface PlainPrefsSource {
    fun all(): Map<String, *>
    /** Deletes the plain file. */
    fun delete(): Boolean
}

/**
 * One-time moves of plaintext data of earlier versions into the encrypted stores. Every move writes the encrypted
 * copy, reads it back and compares, and only then deletes the plain original. If any step fails the original stays and
 * the move is tried again at the next start.
 */
object LegacyMigration {
    /**
     * Returns true when no plain preferences remain (nothing to do, or moved and verified). If the encrypted copy already
     * holds data, an interrupted earlier run left the plain file behind and the encrypted data is authoritative: the plain
     * file is just deleted. With [mergeMissing] the plain keys that the encrypted copy lacks are added first (for a store
     * that was started empty by a version that did not read the plain file yet); existing keys are never overwritten.
     */
    fun migratePrefs(target: EncryptedPrefs, source: PlainPrefsSource, mergeMissing: Boolean = false): Boolean {
        if (!target.isAvailable()) return false // device locked: the plain file is not touched
        val plain = source.all()
        if (plain.isEmpty()) return true
        val existing = target.all
        if (existing.isNotEmpty() && !mergeMissing) // an earlier run was interrupted after the encrypted copy was verified
            return source.delete()
        val wanted = plain.filterKeys { it !in existing }
        val editor = target.edit()
        for ((key, value) in wanted) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toMutableSet())
                else -> return false // unknown type: keep the plain file, do not guess
            }
        }
        if (!editor.commit() || target.all != existing + wanted) {
            // undo: restore exactly what was there before (nothing, or the data the earlier version stored)
            val restore = target.edit().clear()
            for ((key, value) in existing) {
                when (value) {
                    is String -> restore.putString(key, value)
                    is Int -> restore.putInt(key, value)
                    is Long -> restore.putLong(key, value)
                    is Float -> restore.putFloat(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    is Set<*> -> restore.putStringSet(key, value.map { it as String }.toMutableSet())
                }
            }
            restore.commit()
            return false
        }
        return source.delete()
    }

    /** A clipboard row of the old SQLite table (text rows only; file clips are dropped). */
    class LegacyClip(val timestamp: Long, val pinned: Boolean, val text: String)

    /**
     * Writes the old clipboard rows into [store], verifies them and then calls [deleteLegacy]. Returns the entries that
     * are now stored, or null when the encrypted copy could not be verified (the legacy data is then kept).
     */
    fun migrateClipboard(store: SecureStore, rows: List<LegacyClip>, deleteLegacy: () -> Unit): List<ClipboardEntry>? {
        val entries = rows.asSequence()
            .filter { it.text.isNotEmpty() && it.text.length <= ClipboardCapture.MAX_TEXT_LENGTH }
            .distinctBy { it.text }
            .mapIndexed { i, r -> ClipboardEntry(i + 1L, r.timestamp, r.pinned, r.text) }
            .toList()
        try {
            val bytes = ClipboardCodec.encode(entries)
            store.write(bytes)
            val back = (store.read() as? StoreContent.Data)?.bytes ?: return null
            if (!back.contentEquals(bytes)) return null
        } catch (_: StoreWriteException) {
            return null
        } catch (_: VaultLockedException) {
            return null
        }
        deleteLegacy()
        return entries
    }

    /** Moves a plain file's bytes into [store]. Returns true when the encrypted copy is verified and the plain file is gone. */
    fun migrateFile(plain: java.io.File, store: SecureStore): Boolean {
        if (!plain.isFile) return true
        return try {
            val bytes = plain.readBytes()
            store.write(bytes)
            val back = (store.read() as? StoreContent.Data)?.bytes
            if (back == null || !back.contentEquals(bytes)) return false
            plain.delete()
        } catch (_: java.io.IOException) {
            false
        } catch (_: StoreWriteException) {
            false
        } catch (_: VaultLockedException) {
            false
        }
    }
}

/** Plain SharedPreferences of the platform, adapted for [LegacyMigration]. */
class PlatformPlainPrefs(private val prefs: () -> SharedPreferences, private val deleteFile: () -> Boolean) : PlainPrefsSource {
    // opened only when the migration reads it: the credential-encrypted file cannot be opened while the user is locked
    override fun all(): Map<String, *> = prefs().all
    override fun delete(): Boolean = deleteFile()
}
