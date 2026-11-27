// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.media.MediaVault
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import java.io.File

/**
 * Closed while an import replaces the data: the learned-word storage (see [Privacy.learnedStorage]) then reports "unavailable",
 * so a running dictionary can neither reload the old words nor flush them over the imported ones. Dictionaries notice the
 * storage is available again and load the imported words.
 */
object ImportGuard {
    @Volatile var active = false
        private set

    fun <T> run(block: () -> T): T {
        active = true
        try {
            return block()
        } finally {
            active = false
        }
    }
}

/**
 * Collects what an export holds from the live data, and applies an imported [ExportParts] to it. The import is all or
 * nothing as far as possible: everything is read and verified before [apply] is called, and if a step of [apply] fails
 * the earlier steps are put back from the snapshot taken at the start.
 */
object ExportService {
    private fun SharedPreferences.typed(): Map<String, Any> =
        all.entries.mapNotNull { (k, v) -> if (v == null) null else k to v }.toMap()

    /** [accept] tells which relative file paths belong in an export (custom layouts, fonts, user dictionaries). */
    fun collect(context: Context, accept: (String) -> Boolean): ExportParts = collect(context, accept, withMedia = true, mediaCap = MEDIA_EXPORT_BYTES)

    /** [collect] with the pictures switched off ([withMedia] false: the snapshot that an import puts back) or with another cap ([mediaCap], a test seam; the real limit is [MEDIA_EXPORT_BYTES]). */
    fun collect(context: Context, accept: (String) -> Boolean, withMedia: Boolean, mediaCap: Long): ExportParts {
        val app = context.applicationContext ?: context
        val ce = Privacy.protectedPrefs(app)
        check((ce as? EncryptedPrefs)?.isAvailable() == true) { "the device is locked" }
        val base = ExportParts(
            prefsDe = Privacy.prefs(app).typed(),
            prefsCe = ce.typed(),
            personal = Privacy.personalDictionary(app)?.all(),
            learned = Privacy.readAllLearned(app),
            files = FileSet.read(app.filesDir, accept),
            protectedFiles = FileSet.read(DeviceProtectedUtils.getFilesDir(app), accept),
            removedWords = checkNotNull(Privacy.readAllRemovedWords(app)) { "the removed words cannot be read" },
        )
        if (!withMedia) return base
        // the pictures go in only as a whole, when they total MEDIA_EXPORT_BYTES or less and the payload stays within the bundle limit; never a part of them
        val room = ExportBundle.MAX_TOTAL_BYTES - ExportBundle.contentBytes(base)
        return when (val media = Privacy.mediaVault(app).exportAll(minOf(mediaCap, room))) {
            is MediaVault.Export.Included -> ExportParts(base.prefsDe, base.prefsCe, base.personal, base.learned, base.files, base.protectedFiles, media.snapshot.takeIf { it.items.isNotEmpty() },
                removedWords = base.removedWords)
            MediaVault.Export.TooBig -> ExportParts(base.prefsDe, base.prefsCe, base.personal, base.learned, base.files, base.protectedFiles, null, mediaExcluded = true,
                removedWords = base.removedWords)
            MediaVault.Export.Unavailable -> base
        }
    }

    /** The most that an export carries of the pictures: 64 MiB together. */
    const val MEDIA_EXPORT_BYTES = 64L * 1024 * 1024

    /**
     * An import as the settings screen runs it: the learned-word storage is closed ([ImportGuard]) while [discardLearned] makes
     * the running dictionaries drop their unsaved state, [apply] replaces the data, and [discardLearned] runs again, so that no
     * dictionary that was created or reloaded meanwhile keeps old words. Throws like [apply]; the guard is always released.
     */
    fun applyImport(context: Context, parts: ExportParts, accept: (String) -> Boolean, discardLearned: () -> Unit) {
        ImportGuard.run {
            discardLearned()
            try {
                apply(context, parts, accept)
                // an import does not go through Settings.setIncognitoApps: an app that is on the imported incognito list must not keep an imported language
                Settings.settleAppLanguages(context.applicationContext ?: context)
            } finally {
                discardLearned()
            }
        }
    }

    /** Replaces the live data with [parts]. Throws (after putting the earlier steps back) if a step fails. */
    fun apply(context: Context, parts: ExportParts, accept: (String) -> Boolean) {
        val app = context.applicationContext ?: context
        val de = Privacy.prefs(app)
        val ce = Privacy.protectedPrefs(app)
        check((ce as? EncryptedPrefs)?.isAvailable() == true) { "the device is locked" }
        val personal = Privacy.personalDictionary(app)
        val filesDir = app.filesDir
        val protectedDir = DeviceProtectedUtils.getFilesDir(app)
        val before = collect(app, accept, withMedia = false, mediaCap = 0L)
        try {
            parts.prefsDe?.let { replacePrefs(de, it) }
            parts.prefsCe?.let { replacePrefs(ce, it) }
            parts.personal?.let { check(personal?.replaceAll(it) == true) { "could not store the personal dictionary" } }
            check(Privacy.replaceAllLearned(app, parts.learned)) { "could not store the learned words" }
            check(Privacy.replaceAllRemovedWords(app, parts.removedWords)) { "could not store the removed words" }
            FileSet.replace(filesDir, accept, parts.files)
            FileSet.replace(protectedDir, accept, parts.protectedFiles)
            // the last step: the vault keeps the old pictures until the new ones are complete (MediaVault.replaceAll), so a failure here leaves them as they were
            // and no copy of them is needed for the way back
            parts.media?.let { check(Privacy.mediaVault(app).replaceAll(it)) { "could not store the pictures" } }
        } catch (t: Throwable) {
            // best effort, each step on its own so that one failure does not stop the others
            runCatching { replacePrefs(de, before.prefsDe!!) }
            runCatching { replacePrefs(ce, before.prefsCe!!) }
            runCatching { personal?.replaceAll(before.personal ?: emptyList()) }
            runCatching { Privacy.replaceAllLearned(app, before.learned) }
            runCatching { Privacy.replaceAllRemovedWords(app, before.removedWords) }
            runCatching { FileSet.replace(filesDir, accept, before.files) }
            runCatching { FileSet.replace(protectedDir, accept, before.protectedFiles) }
            throw t
        }
    }

    /** All preferences are replaced by [values]. Throws if they could not be stored. */
    fun replacePrefs(prefs: SharedPreferences, values: Map<String, Any>) {
        val editor = prefs.edit().clear()
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toMutableSet())
                else -> throw IllegalArgumentException("unsupported preference type")
            }
        }
        check(editor.commit()) { "could not store the preferences" }
    }
}

/** The files below one directory that an export covers, as relative paths with '/' separators. */
internal object FileSet {
    fun read(dir: File, accept: (String) -> Boolean): Map<String, ByteArray> {
        if (!dir.isDirectory) return emptyMap()
        val base = dir.path + File.separator
        val result = HashMap<String, ByteArray>()
        dir.walk().forEach { f ->
            val path = f.path.removePrefix(base).replace(File.separatorChar, '/')
            if (f.isFile && accept(path)) result[path] = f.readBytes()
        }
        return result
    }

    /**
     * Deletes the accepted files of [dir] and writes [newFiles] (only the accepted names, only inside [dir]).
     * If a write fails, the files written so far are removed and the old ones come back before the exception is thrown.
     */
    fun replace(dir: File, accept: (String) -> Boolean, newFiles: Map<String, ByteArray>, writer: (File, ByteArray) -> Unit = ::write) {
        val old = read(dir, accept)
        val canonicalBase = dir.canonicalFile
        val written = ArrayList<File>()
        try {
            old.keys.forEach { File(dir, it).delete() }
            for ((path, bytes) in newFiles) {
                if (!accept(path)) continue
                val target = File(dir, path)
                val canonical = target.canonicalFile
                if (!canonical.path.startsWith(canonicalBase.path + File.separator)) throw IllegalArgumentException("path outside the directory")
                written.add(target) // before the write: a failed write may leave a partial file
                writer(target, bytes)
            }
        } catch (t: Throwable) {
            written.forEach { it.delete() }
            for ((path, bytes) in old) runCatching { write(File(dir, path), bytes) }
            throw t
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}
