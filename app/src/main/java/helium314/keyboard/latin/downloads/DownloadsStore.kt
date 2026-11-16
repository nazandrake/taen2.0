// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Where each catalogue row lives ([targetOf]) and what is installed there. A row is "verified" only when its file has the pinned size and SHA-256;
 * a file of the same name that is another file (for example a dictionary the user imported by hand) is never touched by Remove: it is not this row's file.
 * The page of Settings > Downloads is built from [rows] (a pure function of the files, the running installs and the last refusals), so the page and the
 * tests read the same state.
 */
class DownloadsStore(
    private val entries: List<DownloadEntry>,
    private val targetOf: (DownloadEntry) -> File,
    private val freeSpace: () -> Long,
) {
    enum class State { NOT_INSTALLED, INSTALLING, VERIFIED, WRONG_FILE, DAMAGED }

    /** [percent] is only meaningful for INSTALLING; [bytesOnDisk] is the length of the row's file (0 when none). */
    class Row(val entry: DownloadEntry, val state: State, val percent: Int, val bytesOnDisk: Long)

    class Overview(val rows: List<Row>, val totalBytes: Long, val freeBytes: Long)

    /** The state of the file of [entry] on disk: absent, the pinned file, a file of the pinned size with other content (damaged), or another file (not this row's). */
    fun diskState(entry: DownloadEntry): State {
        val f = targetOf(entry)
        if (!f.isFile) return State.NOT_INSTALLED
        if (f.length() != entry.size) return State.NOT_INSTALLED
        return if (hashOf(f) == entry.sha256) State.VERIFIED else State.DAMAGED
    }

    /**
     * One row per catalogue entry. [running] maps an entry id to the percent of its running install, [refused] holds the ids whose last install attempt
     * was a wrong file. A running install shows as INSTALLING, then a damaged file, then a refusal, then what is on disk.
     */
    fun overview(running: Map<String, Int> = emptyMap(), refused: Set<String> = emptySet()): Overview {
        val rows = entries.map { e ->
            val disk = diskState(e)
            val state = when {
                e.id in running -> State.INSTALLING
                disk == State.DAMAGED -> State.DAMAGED
                e.id in refused && disk != State.VERIFIED -> State.WRONG_FILE
                else -> disk
            }
            val f = targetOf(e)
            Row(e, state, running[e.id] ?: 0, if (f.isFile) f.length() else 0L)
        }
        return Overview(rows, rows.sumOf { it.bytesOnDisk }, freeSpace())
    }

    /** Deletes the file of [entry] when it is this row's (verified or damaged). True when it is gone afterwards. */
    fun remove(entry: DownloadEntry): Boolean {
        val f = targetOf(entry)
        if (!f.exists()) return true
        return when (diskState(entry)) {
            State.VERIFIED, State.DAMAGED -> f.delete()
            else -> false // another file under this name: not ours to delete
        }
    }

    /** Deletes the files of every row (Remove all). Returns the entries whose file could not be removed. */
    fun removeAll(): List<DownloadEntry> = entries.filter { !remove(it) && targetOf(it).exists() && diskState(it) != State.NOT_INSTALLED }

    // a model file is 130-620 MB: its hash is kept for as long as the file is the same one: the same inode (an install renames a new file in), length and
    // time of change in nanoseconds. A file system without an inode key is hashed every time.
    private val hashes = HashMap<String, Pair<String, String>>()

    private fun identity(file: File): String? = try {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        a.fileKey()?.let { "$it ${a.size()} ${a.lastModifiedTime().to(TimeUnit.NANOSECONDS)}" }
    } catch (_: IOException) {
        null
    }

    private fun hashOf(file: File): String? {
        val key = file.absolutePath
        val before = identity(file)
        if (before != null) synchronized(hashes) { hashes[key]?.let { (id, h) -> if (id == before) return h } }
        val hash = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { digestOf(it, digest) }
        } catch (_: IOException) {
            return null
        }
        // kept only when the file did not change while it was read
        if (before != null && identity(file) == before) synchronized(hashes) { hashes[key] = before to hash }
        return hash
    }

    private fun digestOf(input: InputStream, digest: MessageDigest): String {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
