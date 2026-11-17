// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A configuration store that survives a wipe that keeps the configuration armed. [name] is the store name (`security`, `selfdestruct`). */
class ArmedFile(val name: String, val bytes: ByteArray)

/**
 * The marker of a wipe that did not finish; it lets the next start complete it. [armed] the configuration is kept armed (saved to be written back),
 * [restored] it was written back, [keysDone] the Keystore keys are gone (so they are never deleted a second time: new ones may exist by then).
 */
data class Pending(val scope: WipeScope, val armed: Boolean, val restored: Boolean, val keysDone: Boolean) {
    fun encode(): ByteArray {
        val bits = listOf(scope.learned, scope.clipboard, scope.settings, scope.config, scope.imported, armed, restored, keysDone)
        return (MAGIC + bits.joinToString("") { if (it) "1" else "0" }).toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private const val MAGIC = "TWP1"
        /**
         * A damaged marker (anyone with file access can write one) means: wipe everything, and it is not known that the keys are gone, so
         * both are destroyed again and both tiers are deleted (fail closed: a key that is not there is no harm, a key left behind is).
         */
        private val EVERYTHING = Pending(WipeScope.ALL, armed = false, restored = false, keysDone = false)

        fun decode(bytes: ByteArray): Pending {
            val text = String(bytes, Charsets.US_ASCII)
            if (bytes.size != MAGIC.length + 8 || !text.startsWith(MAGIC) || text.substring(MAGIC.length).any { it != '0' && it != '1' }) return EVERYTHING
            val b = text.substring(MAGIC.length).map { it == '1' }
            return Pending(WipeScope(b[0], b[1], b[2], b[3], b[4]), b[5], b[6], b[7])
        }
    }
}

/** Everything a wipe touches, so the order and the failure handling can be tested without a phone. */
interface WipePorts {
    /** Step 1: from now on every writer refuses. */
    fun beginWipe()
    /** The configuration stores (plain bytes, in memory only) to write back after a wipe that keeps the configuration armed. */
    fun readArmed(): List<ArmedFile>
    fun writePending(pending: Pending): Boolean
    /** Deletes the Keystore keys of both tiers. False when one could not be deleted. */
    fun deleteKeys(): Boolean
    /** Deletes the files in [scope] except the store files named in [keep]. False when something that should go is still there (credential-encrypted storage locked). */
    fun deleteFiles(scope: WipeScope, keep: Set<String>, ceOnly: Boolean): Boolean
    fun clearClipboard()
    /** Removes the language-only subtype the keyboard registered with the system: it names the language in use. */
    fun clearSystemLanguage()
    fun restoreArmed(files: List<ArmedFile>): Boolean
    fun clearPending()
    /** Last step: the process ends and the system starts the keyboard again. */
    fun restart()
}

/** How a wipe went; the times are from the start of the wipe, on the injected clock. */
class WipeReport(val keysMillis: Long, val totalMillis: Long, val complete: Boolean)

/**
 * The wipe procedure (crypto-shred): stop every writer, save the configuration when it stays armed, write the marker,
 * destroy the keys, delete the files, clear the clipboard and the language subtype registered with the system, write the configuration back,
 * remove the marker, restart. Every step is
 * tried whatever an earlier one did; a failure leaves the marker so the next start finishes the job.
 */
class Wiper(private val ports: WipePorts, private val clock: () -> Long) {
    private fun step(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }

    fun run(scope: WipeScope): WipeReport {
        val start = clock()
        ports.beginWipe()
        // the marker comes first, before anything that can take time or fail (reading the configuration): a process that ends from here on
        // is finished by the next start. The configuration saved below is in memory only, so a wipe that ends early loses it (not kept armed).
        val keepArmed = scope.shreds && !scope.config
        var pending = Pending(scope, keepArmed, restored = false, keysDone = false)
        var ok = step { ports.writePending(pending) }
        val saved = if (keepArmed) try { ports.readArmed() } catch (_: Exception) { emptyList() } else emptyList()
        val armed = saved.isNotEmpty()
        var keysMillis = 0L
        if (scope.shreds) {
            val keys = step { ports.deleteKeys() }
            keysMillis = clock() - start
            ok = ok && keys
            if (keys) {
                pending = pending.copy(keysDone = true)
                ok = step { ports.writePending(pending) } && ok
            }
        }
        ok = step { ports.deleteFiles(scope, setOf(PENDING_NAME), false) } && ok
        if (scope.clipboard) step { ports.clearClipboard(); true }
        step { ports.clearSystemLanguage(); true } // every scope: the system's subtype names the language in use; a failure does not keep the marker
        if (armed) {
            val restored = step { ports.restoreArmed(saved) }
            ok = ok && restored
            if (restored) {
                pending = pending.copy(restored = true)
                ok = step { ports.writePending(pending) } && ok
            }
        }
        if (ok) step { ports.clearPending(); true }
        val total = clock() - start
        ports.restart()
        return WipeReport(keysMillis, total, ok)
    }

    /**
     * Finishes a wipe whose marker was found at the start. Never destroys keys that were already destroyed (new ones may exist), but always
     * deletes the files of BOTH tiers: a process that ended after the key marker and before the file deletion left the device-protected
     * files too. The marker goes only when the keys (when due) and the files of both tiers are done. True when it finished.
     */
    fun resume(pending: Pending): Boolean {
        var ok = true
        if (pending.scope.shreds && !pending.keysDone) ok = step { ports.deleteKeys() }
        val keep = if (pending.armed && pending.restored) ARMED_KEEP + PENDING_NAME else setOf(PENDING_NAME)
        ok = step { ports.deleteFiles(pending.scope, keep, false) } && ok
        if (ok) step { ports.clearPending(); true }
        return ok
    }

    companion object {
        const val PENDING_NAME = "wipe.pending"
        /** Target from the trigger to the end of the key deletion, and to the end of the wipe, on the reference phone. */
        const val KEY_TARGET_MILLIS = 200L
        const val TOTAL_TARGET_MILLIS = 2000L
        /** The files of a configuration that was written back and must survive the replay of an unfinished wipe. */
        val ARMED_KEEP = setOf("security.enc", "selfdestruct.enc", "dek_de.bin")
    }
}

/**
 * Deletes the files of a wipe from the credential-encrypted ([ce], null while it is locked) and the device-protected ([de]) data
 * directories. A shred (every user store in scope) removes everything below the data folders (files, cache, databases, shared_prefs,
 * no_backup) except the named store files; a partial scope removes the files that belong to it. The cache, the encrypted activity
 * time and empty folders (their names can be data) always go. Symbolic links are removed, never followed.
 */
class FileWiper(private val ce: File?, private val de: File?) {
    private enum class Category { LEARNED, CLIPBOARD, SETTINGS, CONFIG, IMPORTED, ALWAYS, KEYS, NEVER }

    /** False when something in scope could not be removed, or when the credential-encrypted directory is locked ([ce] null). */
    fun delete(scope: WipeScope, keep: Set<String>, ceOnly: Boolean): Boolean {
        var ok = true
        if (ce == null) ok = false else ok = wipeRoot(ce, scope, keep) && ok
        if (!ceOnly && de != null) ok = wipeRoot(de, scope, keep) && ok
        return ok
    }

    private fun wipeRoot(root: File, scope: WipeScope, keep: Set<String>): Boolean {
        var ok = true
        for (dir in DATA_FOLDERS) {
            val top = File(root, dir).toPath()
            if (!Files.exists(top, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(top)) { // a data folder that is a link: remove the link only
                ok = runCatching { Files.delete(top) }.isSuccess && ok
                continue
            }
            ok = walk(root.toPath(), top, scope, keep) && ok
        }
        return ok
    }

    private fun walk(root: Path, top: Path, scope: WipeScope, keep: Set<String>): Boolean {
        var ok = true
        Files.walkFileTree(top, emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).joinToString("/")
                if (wanted(relative, scope, keep)) {
                    try { Files.delete(file) } catch (_: IOException) { ok = false }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                ok = false
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (dir != top) { // an emptied folder: its name can be data (a dictionary language), so it goes too
                    try { Files.delete(dir) } catch (_: IOException) { /* not empty: something was kept */ }
                }
                return FileVisitResult.CONTINUE
            }
        })
        return ok
    }

    private fun wanted(relative: String, scope: WipeScope, keep: Set<String>): Boolean {
        val name = relative.substringAfterLast('/')
        val inStoreDir = relative.startsWith("no_backup/taen/")
        if (inStoreDir && name in keep) return false
        if (scope.shreds) return true
        return when (category(relative, name)) {
            Category.ALWAYS -> true
            Category.LEARNED -> scope.learned
            Category.CLIPBOARD -> scope.clipboard
            Category.SETTINGS -> scope.settings
            Category.CONFIG -> scope.config
            Category.IMPORTED -> scope.imported
            Category.KEYS -> false // only a shred destroys the keys and their files
            Category.NEVER -> false
        }
    }

    private fun category(relative: String, name: String): Category = when {
        relative.startsWith("cache/") -> Category.ALWAYS
        relative.startsWith("no_backup/taen/") -> storeCategory(name)
        // the voice model is public data, not user data: a partial wipe keeps it (a shred removes everything, see [wanted])
        relative.startsWith("no_backup/${Privacy.VOICE_DIR}/") -> Category.NEVER
        relative.startsWith("files/dicts/") -> Category.IMPORTED
        // a downloaded dictionary is in files/dicts (above); an install that was cut off leaves its .part file here
        relative.startsWith("no_backup/${Privacy.DOWNLOADS_DIR}/") -> Category.IMPORTED
        // the downloaded next-word models and their speed results show which languages are in use (unlike the voice model, which is one for all)
        relative.startsWith("no_backup/${Privacy.MODELS_DIR}/") -> Category.IMPORTED
        relative.startsWith("files/blacklists/") || name.startsWith("UserHistoryDictionary") -> Category.LEARNED
        relative.startsWith("files/clipboard/") || relative == "files/clipboard" || relative == "files/temp_clip" -> Category.CLIPBOARD
        relative.startsWith("databases/") -> Category.CLIPBOARD
        else -> Category.SETTINGS // shared_prefs, layouts, fonts, themes, anything unknown: the safe side is to remove it with the settings
    }

    private fun storeCategory(name: String): Category = when {
        name == Wiper.PENDING_NAME -> Category.NEVER
        name.startsWith("dek_") -> Category.KEYS
        name.startsWith("learned_") || name.startsWith("snippets.enc") -> Category.LEARNED
        name.startsWith("clipboard.enc") -> Category.CLIPBOARD
        name.startsWith("security.enc") || name.startsWith("selfdestruct.enc") -> Category.CONFIG
        // the imported pictures (media_<id>.enc and media_index.enc). Listed on its own, not left to the else-branch: a new store name falls to SETTINGS.
        name.startsWith("media_") -> Category.IMPORTED
        name.startsWith("activity.enc") -> Category.ALWAYS
        else -> Category.SETTINGS
    }

    companion object {
        private val DATA_FOLDERS = listOf("files", "cache", "databases", "shared_prefs", "no_backup")
    }
}
