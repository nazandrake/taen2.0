// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The only owner of the voice model files in the app-private directory [dir] (no_backup/taen-voice of the device-protected storage, given by the
 * privacy storage owner). The model is public data (not user data), so the files are plain: the app-private directory is the protection,
 * and a 650 MB read-only encoder cannot be decrypted into the Java heap for ONNX Runtime, which maps the file itself.
 *
 * Import takes a picked file in two passes over the stream: pass 1 only hashes (a wrong file, for example a private photo, is never
 * written anywhere), pass 2 copies the files that match an entry of [VoiceModelSpec] and checks the hash of the copy before the rename.
 * A file read out of the one archive goes through [stage] (one pass, the entry is known) and [commitStaged]. Each file is installed on its own,
 * so any subset can be present between two imports ([status] says which); the model is [isComplete] when all are. Nothing but the model files
 * and the temporary `<name>.part` files of a running import ever exists in [dir]. [verify] hashes every file again; it runs before each
 * engine load, so a file changed after the import is never loaded.
 *
 * The temporary names are shared by every install into [dir], so installs and [deleteAll] are mutually exclusive per directory ([exclusive]: a second one
 * does not wait, it reports that it was refused). A commit of several files ([commitStaged]) removes again the files it newly created when a later rename fails.
 * A crash between two renames can leave a subset installed; that is a valid state of the store (every file is pinned by its own hash, any subset can be
 * present, [status] says which) and the next install starts with [discardStaged].
 */
class VoiceModelStore(
    private val dir: File,
    private val entries: List<VoiceModelSpec.Entry> = VoiceModelSpec.entries,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    sealed interface ImportResult {
        /** The file is installed. */
        class Imported(val name: String) : ImportResult
        /** [stage] only: the content is verified and waits in a temporary file for [commitStaged]. */
        class Staged(val name: String) : ImportResult
        /** The content is none of the model files (or is larger than the largest of them). Nothing was written. */
        object NotAModelFile : ImportResult
        /** [stage] only: the content is not the entry it was given for (wrong size or hash). The temporary file is gone. */
        object Mismatch : ImportResult
        object NoSpace : ImportResult
        object Failed : ImportResult
        /** The [Hooks] asked to stop. The temporary file is gone. */
        object Cancelled : ImportResult
    }

    /** [cancelled] is asked before every read; [onBytes] gets the number of bytes of each read (progress and the stall watch of the caller). */
    class Hooks(val cancelled: () -> Boolean = { false }, val onBytes: (Long) -> Unit = {}) {
        companion object { val NONE = Hooks() }
    }

    /** One row of the voice typing page: the file, its pinned size, and whether a complete copy is installed. */
    class Status(val entry: VoiceModelSpec.Entry, val installed: Boolean)

    fun file(name: String): File = File(dir, name)

    /**
     * Runs [block] when no other install or delete is running on [dir] (any store object of the same directory), otherwise returns [busy] without running it.
     * Not reentrant: [block] must not call another exclusive function of a store of this directory.
     */
    fun <T> exclusive(busy: T, block: () -> T): T {
        val lock = locks.computeIfAbsent(dir.absoluteFile.normalize().path) { Semaphore(1) }
        if (!lock.tryAcquire()) return busy
        try {
            return block()
        } finally {
            lock.release()
        }
    }

    private fun isInstalled(entry: VoiceModelSpec.Entry): Boolean { val f = file(entry.name); return f.isFile && f.length() == entry.size }

    /** Entries whose file is absent or has the wrong size (a cheap check, no hashing). */
    fun missing(): List<VoiceModelSpec.Entry> = entries.filter { !isInstalled(it) }

    fun status(): List<Status> = entries.map { Status(it, isInstalled(it)) }

    fun isComplete(): Boolean = missing().isEmpty()

    /** True when [bytes] and the safety margin fit on the storage of the model directory (the directory is made first when it does not exist). */
    fun hasSpaceFor(bytes: Long): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return freeSpace(dir) >= bytes + SPACE_MARGIN
    }

    /** Hashes every file against [VoiceModelSpec]. False when one is absent, has the wrong size or another content. */
    fun verify(): Boolean = entries.all { entry ->
        val f = file(entry.name)
        f.isFile && f.length() == entry.size && runCatching { f.inputStream().use { hash(it, entry.size, Hooks.NONE)?.takeIf { (_, n) -> n == entry.size }?.first } }.getOrNull() == entry.sha256
    }

    /** The lines of tokens.txt. Only meaningful after [verify]. */
    fun tokenLines(): List<String> = file(VoiceModelSpec.TOKENS).readLines(Charsets.UTF_8)

    /** Imports the file that [open] streams; [open] is called twice. */
    fun importFile(hooks: Hooks = Hooks.NONE, open: () -> InputStream?): ImportResult = exclusive<ImportResult>(ImportResult.Failed) { importFileLocked(hooks, open) }

    private fun importFileLocked(hooks: Hooks, open: () -> InputStream?): ImportResult {
        val stream = try {
            open()
        } catch (_: IOException) {
            null
        } ?: return ImportResult.Failed // the picked file cannot be opened
        val first = try {
            stream.use { hash(it, entries.maxOf { it.size }, hooks) }
        } catch (_: IOException) {
            return ImportResult.Failed
        } ?: return if (hooks.cancelled()) ImportResult.Cancelled else ImportResult.NotAModelFile // more bytes than the largest model file
        if (hooks.cancelled()) return ImportResult.Cancelled
        val entry = entries.firstOrNull { it.sha256 == first.first }?.takeIf { it.size == first.second } ?: return ImportResult.NotAModelFile
        val input = try {
            open()
        } catch (_: IOException) {
            null
        } ?: return ImportResult.Failed
        return when (val staged = stage(entry, input, hooks)) {
            is ImportResult.Staged -> if (commitStaged(listOf(entry.name))) ImportResult.Imported(entry.name) else ImportResult.Failed.also { discardStaged() }
            ImportResult.Mismatch -> ImportResult.Failed // the file changed between the two passes
            else -> staged
        }
    }

    /**
     * Copies [input] (closed here) to the temporary file of [entry] and checks size and SHA-256 on the way: [ImportResult.Staged] when both equal the
     * entry (the file waits for [commitStaged]), otherwise nothing stays. One pass: the caller knows which entry the stream must be.
     */
    fun stage(entry: VoiceModelSpec.Entry, input: InputStream, hooks: Hooks = Hooks.NONE): ImportResult {
        if (!hasSpaceFor(entry.size)) { input.close(); return ImportResult.NoSpace }
        val part = partFile(entry)
        var staged = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { source ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (hooks.cancelled()) return ImportResult.Cancelled
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > entry.size) return ImportResult.Mismatch // more bytes than the entry has
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        hooks.onBytes(n.toLong())
                    }
                    out.flush()
                }
            }
            if (copied != entry.size || hex(digest.digest()) != entry.sha256) return ImportResult.Mismatch
            staged = true
            return ImportResult.Staged(entry.name)
        } catch (_: IOException) {
            return ImportResult.Failed
        } finally {
            if (!staged) part.delete()
        }
    }

    /**
     * Moves the staged files [names] to their final names, one atomic rename each, all or none: nothing is moved unless every name is a known entry with a
     * staged file, and when a rename fails the files this call newly created are removed again (a file that was installed before was replaced by the same
     * pinned content). False then; call [discardStaged] for what is left staged.
     */
    fun commitStaged(names: List<String>): Boolean {
        val parts = names.map { name -> entries.firstOrNull { it.name == name } ?: return false }
        if (!parts.all { partFile(it).isFile }) return false
        val created = ArrayList<VoiceModelSpec.Entry>()
        try {
            for (entry in parts) {
                val existed = file(entry.name).exists()
                Files.move(partFile(entry).toPath(), file(entry.name).toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (!existed) created.add(entry)
            }
            return true
        } catch (_: IOException) {
            created.forEach { file(it.name).delete() }
            return false
        }
    }

    /** Removes every temporary file (an import that did not finish, a stopped one). Installed files stay. */
    fun discardStaged() {
        entries.forEach { partFile(it).delete() }
        File(dir, PART).delete()
    }

    /** Removes the model (the settings action "delete model"). False, and nothing removed, while an install runs on [dir]. */
    fun deleteAll(): Boolean = exclusive(false) {
        entries.forEach { file(it.name).delete() }
        discardStaged()
        true
    }

    private fun partFile(entry: VoiceModelSpec.Entry) = File(dir, entry.name + PART_SUFFIX)

    /** SHA-256 and byte count of [input], or null when it holds more than [limit] bytes (or [hooks] asked to stop). */
    private fun hash(input: InputStream, limit: Long, hooks: Hooks): Pair<String, Long>? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            if (hooks.cancelled()) return null
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            digest.update(buffer, 0, n)
            hooks.onBytes(n.toLong())
        }
        return hex(digest.digest()) to total
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private val locks = ConcurrentHashMap<String, Semaphore>()

        /** The temporary file of the single-pass import of older versions; still removed when found. */
        const val PART = "import.part"
        const val PART_SUFFIX = ".part"
        const val BUFFER = 1 shl 20
        const val SPACE_MARGIN = 64L * 1024 * 1024
    }
}
