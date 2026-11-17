// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The one hash-while-copy install of a pinned download, the same steps as the voice model store: a picked file is first hashed without writing
 * anything ([identify]: a private photo is never copied), then copied once into `<id>.part` in [dir] while its size is capped at the pinned size
 * (reading stops at size + 1) and its SHA-256 is computed; only a file that equals the pinned size and hash goes through [accept] and is renamed to its
 * [target] with one atomic move. Every other end (wrong file, cancel, no space, a reader that throws, a failed rename) removes the `.part` file and leaves
 * the target as it was. Installs into one [dir] exclude each other: a second one is refused ([Result.Busy]), it does not wait.
 */
class PinnedInstaller(
    private val dir: File,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val move: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) },
) {
    sealed interface Result {
        /** The file equals the pinned one and is in place at the target. */
        object Installed : Result
        /** The content is not the pinned file (other bytes, shorter, or longer than the pinned size). Nothing was moved. */
        object WrongFile : Result
        /** The file is the pinned one but [accept] refused it (for a dictionary: header, validity or language). Nothing was moved. */
        object Rejected : Result
        object NoSpace : Result
        object Cancelled : Result
        object Failed : Result
        /** Another install runs in the same directory. */
        object Busy : Result
    }

    /** [cancelled] is asked before every read; [onBytes] gets the number of bytes of each read (progress and the stall watch of the caller). */
    class Hooks(val cancelled: () -> Boolean = { false }, val onBytes: (Long) -> Unit = {}) {
        companion object { val NONE = Hooks() }
    }

    /**
     * Which of [entries] the content of [open] is, by size and SHA-256 (one read, nothing written); null when it is none of them, is longer than the largest,
     * cannot be read or the read was cancelled. A reader that throws is the same as an unreadable file.
     */
    fun identify(entries: List<DownloadEntry>, hooks: Hooks = Hooks.NONE, open: () -> InputStream?): DownloadEntry? {
        if (entries.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val limit = entries.maxOf { it.size }
        try {
            (open() ?: return null).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    if (hooks.cancelled()) return null
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) return null
                    digest.update(buffer, 0, n)
                    hooks.onBytes(n.toLong())
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        val sha = hex(digest.digest())
        return entries.firstOrNull { it.sha256 == sha && it.size == total }
    }

    /** Installs the content of [open] as [entry] to [target]. [accept] runs on the verified `.part` file only. */
    fun install(entry: DownloadEntry, target: File, hooks: Hooks = Hooks.NONE, accept: (File) -> Boolean = { true }, open: () -> InputStream?): Result {
        val lock = lockOf(dir)
        if (!lock.tryAcquire()) return Result.Busy
        try {
            return installLocked(entry, target, hooks, accept, open)
        } finally {
            lock.release()
        }
    }

    private fun installLocked(entry: DownloadEntry, target: File, hooks: Hooks, accept: (File) -> Boolean, open: () -> InputStream?): Result {
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Failed
        if (freeSpace(dir) < entry.size + SPACE_MARGIN) return Result.NoSpace
        val part = File(dir, entry.id + PART_SUFFIX)
        var done = false
        try {
            val input = try { open() } catch (_: IOException) { null } ?: return Result.Failed
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { source ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (hooks.cancelled()) return Result.Cancelled
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > entry.size) return Result.WrongFile // one byte more than the pinned size: stop reading
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        hooks.onBytes(n.toLong())
                    }
                    out.flush()
                }
            }
            if (copied != entry.size || hex(digest.digest()) != entry.sha256) return Result.WrongFile
            if (hooks.cancelled()) return Result.Cancelled
            if (!accept(part)) return Result.Rejected
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return Result.Failed }
            move(part, target)
            done = true
            return Result.Installed
        } catch (_: IOException) {
            return if (hooks.cancelled()) Result.Cancelled else Result.Failed
        } catch (_: RuntimeException) {
            return if (hooks.cancelled()) Result.Cancelled else Result.Failed
        } finally {
            if (!done) part.delete()
        }
    }

    /**
     * Removes the leftover `.part` files of an install that did not finish (a crash). Installed files are not touched. It takes the directory's install
     * lock: while an install runs, its `.part` file is not a leftover, and nothing is removed (false).
     */
    fun discardStaged(): Boolean {
        val lock = lockOf(dir)
        if (!lock.tryAcquire()) return false
        try {
            dir.listFiles { f -> f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
            return true
        } finally {
            lock.release()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private val locks = ConcurrentHashMap<String, Semaphore>()
        // one install or discard per staging directory, whichever instance runs it
        private fun lockOf(dir: File): Semaphore = locks.computeIfAbsent(dir.absoluteFile.normalize().path) { Semaphore(1) }
        const val PART_SUFFIX = ".part"
        const val BUFFER = 1 shl 16
        const val SPACE_MARGIN = 64L * 1024 * 1024
    }
}
