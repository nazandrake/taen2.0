// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * "Install from the one archive" (P20, 1.0.2). The user downloads the official sherpa-onnx archive of the model (one file) and picks it; the app opens it
 * once, streams the tar inside it through the decoder while hashing the compressed bytes (at most [VoiceModelSpec.archive] size of them) and installs the
 * model files through [VoiceModelStore]: each file is checked against its own pinned SHA-256 and staged under a temporary name, and all are renamed into
 * place only when the archive was read to its end (so that the CRC of the compression is checked too) and its size and SHA-256 equal the pinned ones.
 *
 * Only what the pinned archive holds is accepted, although its hash is already pinned (defence in depth, and the reading code is tested with hostile
 * archives): the folder entry and `test_wavs/` as directories; the model files under their exact names, once each, of exactly the pinned size; and
 * `README.md` and `test_wavs/<name>.wav` as extras that are read through and dropped (at most [MAX_EXTRAS], each at most [MAX_EXTRA_BYTES]). Everything else
 * is a [Result.BadArchive]: any other name, a name outside the folder, an absolute or `..` path, a backslash, a link or device or fifo, a pax or GNU
 * extension header, a bad header checksum, a size field that is not plain octal, more than [MAX_ENTRIES] entries, more than [MAX_TRAILING_BYTES] after the end.
 * Nothing is ever written under a name taken from the archive: the file names come from [VoiceModelSpec].
 */
class VoiceArchiveInstaller(
    private val store: VoiceModelStore,
    private val archive: VoiceModelSpec.Archive = VoiceModelSpec.archive,
    private val entries: List<VoiceModelSpec.Entry> = VoiceModelSpec.entries,
    private val decompress: (InputStream) -> InputStream = { Bzip2InputStream(it) },
) {
    sealed interface Result {
        class Installed(val names: List<String>) : Result
        /** The picked document is not the pinned archive (size or SHA-256 differ). Nothing is installed. */
        object NotThisArchive : Result
        /** The archive has the right hash but its content is not what was expected (see the class comment). Nothing is installed. */
        class BadArchive(val reason: String) : Result
        /** A model file inside has another size or content than pinned. Nothing is installed. */
        class EntryRejected(val name: String) : Result
        object NoSpace : Result
        object Failed : Result
        object Cancelled : Result
    }

    /** One install at a time per model directory ([VoiceModelStore.exclusive]); a second one gets [Result.Failed] and touches nothing. */
    fun install(open: () -> InputStream?, control: InstallControl = InstallControl()): Result =
        store.exclusive(Result.Failed) { installLocked(open, control) }

    /**
     * The document is opened ONCE. Its compressed bytes are hashed (and counted against the pinned size) as the decoder reads them, so the decoder and the tar
     * reader see exactly the bytes that are hashed, whatever the provider does on a later open (it is never opened again). The model files are staged under
     * temporary names, each checked against its own SHA-256; the stream is then read to its end, and the files are renamed into place only when the size and
     * SHA-256 of the whole archive equal the pinned ones too. A document that is not the pinned archive is [Result.NotThisArchive] whatever the decoder made of
     * it, and installs nothing.
     */
    private fun installLocked(open: () -> InputStream?, control: InstallControl): Result {
        store.discardStaged()
        var committed = false
        var hashing: HashingInputStream? = null
        try {
            if (control.cancelled) return Result.Cancelled
            if (!store.hasSpaceFor(entries.sumOf { it.size })) return Result.NoSpace
            val source = open() ?: return Result.Failed
            val hashed = HashingInputStream(source, archive.size) { control.hooks.onBytes(it) }
            hashing = hashed
            control.reading(hashed)
            control.step(STEP_INSTALLING, archive.size)
            val staged = ArrayList<String>()
            var outcome: Result? = null // null: the tar was read to its end and is as expected
            var decoderError: IOException? = null
            try {
                // the decoder must not close the stream: what is left of it is read below, for the hash
                decompress(NonClosingInputStream(hashed)).use { tar -> outcome = readTar(tar, staged, control) }
            } catch (e: IOException) {
                decoderError = e
            }
            if (control.cancelled) return Result.Cancelled
            if (hashed.exceeded) return Result.NotThisArchive
            if (decoderError == null) when (outcome) {
                Result.Cancelled, Result.NoSpace, Result.Failed -> return outcome!!
                else -> Unit
            }
            try {
                hashed.readToEnd(control)
            } catch (_: IOException) {
                return if (control.cancelled) Result.Cancelled else if (hashed.exceeded) Result.NotThisArchive else Result.Failed
            }
            if (control.cancelled) return Result.Cancelled
            // the hash of everything that was read decides first: another file is "not this archive" however the decoder or the tar reader judged it
            if (hashed.total != archive.size || hashed.sha256() != archive.sha256) return Result.NotThisArchive
            if (decoderError != null) throw decoderError
            outcome?.let { return it }
            if (staged.sorted() != entries.map { it.name }.sorted()) return Result.BadArchive("a model file is missing")
            if (!store.commitStaged(staged)) return Result.Failed
            committed = true
            return Result.Installed(staged.toList())
        } catch (_: IOException) {
            return if (control.cancelled) Result.Cancelled else Result.Failed
        } catch (_: RuntimeException) {
            // a decoder (or the tar reader) that fails on corrupt data with an unchecked exception (IllegalStateException, IndexOutOfBounds):
            // a failed install, never a crash; the finally block still removes every staged file
            return if (control.cancelled) Result.Cancelled else Result.Failed
        } finally {
            control.reading(null)
            try { hashing?.close() } catch (_: IOException) { /* given up anyway */ }
            if (!committed) store.discardStaged() // every way out but the install: nothing of this run stays; files installed before are untouched
        }
    }

    /** Reads the tar stream, staging the model files into [staged]. An error result, or null when the archive was read to its end and is as expected. */
    private fun readTar(tar: InputStream, staged: MutableList<String>, control: InstallControl): Result? {
        val header = ByteArray(BLOCK)
        var count = 0
        var extras = 0
        while (true) {
            if (control.cancelled) return Result.Cancelled
            if (!readFully(tar, header)) break // no end blocks at all: the end of the data is the end of the archive
            if (header.all { it == 0.toByte() }) break // the end of the archive
            if (++count > MAX_ENTRIES) return bad("too many entries")
            if (!checksumOk(header)) return bad("damaged header")
            val type = header[156].toInt().toChar()
            if (type != '0' && type != '\u0000' && type != '5') return bad("an entry that is not a file or a folder")
            val size = octal(header) ?: return bad("unsupported size field")
            val name = entryName(header, directory = type == '5') ?: return bad("unsafe name")
            if (type == '5') {
                if (size != 0L || (name != archive.folder && name != archive.folder + "/" + EXTRAS_FOLDER)) return bad("unexpected folder")
                continue
            }
            val modelEntry = entries.firstOrNull { name == archive.folder + "/" + it.name }
            if (modelEntry != null) {
                if (modelEntry.name in staged) return bad("a model file twice")
                if (size != modelEntry.size) return Result.EntryRejected(modelEntry.name)
                // progress counts the compressed bytes of the archive (HashingInputStream), not the files written
                val stage = store.stage(modelEntry, LimitedInputStream(tar, size), VoiceModelStore.Hooks(cancelled = { control.cancelled }))
                when (stage) {
                    is VoiceModelStore.ImportResult.Staged -> staged.add(modelEntry.name)
                    VoiceModelStore.ImportResult.Mismatch -> return Result.EntryRejected(modelEntry.name)
                    VoiceModelStore.ImportResult.NoSpace -> return Result.NoSpace
                    VoiceModelStore.ImportResult.Cancelled -> return Result.Cancelled
                    else -> return Result.Failed
                }
                skip(tar, padding(size))
            } else if (isExtra(name)) {
                if (++extras > MAX_EXTRAS) return bad("too many extra files")
                if (size > MAX_EXTRA_BYTES) return bad("an extra file is too large")
                skip(tar, size + padding(size))
            } else {
                return bad("an entry that is not expected")
            }
        }
        // the rest of the decompressed stream after the end blocks: bytes are not interpreted (GNU tar pads with zeros to its record size) but at most
        // [MAX_TRAILING_BYTES] are accepted; reading to the end makes the decoder check the compression trailer (the CRC of everything)
        val buffer = ByteArray(BUFFER)
        var trailing = 0L
        while (true) {
            if (control.cancelled) return Result.Cancelled
            val n = tar.read(buffer)
            if (n < 0) break
            trailing += n
            if (trailing > MAX_TRAILING_BYTES) return bad("data after the end of the archive")
        }
        return null
    }

    private fun bad(reason: String) = Result.BadArchive(reason)

    private fun isExtra(name: String): Boolean {
        if (name == archive.folder + "/" + EXTRA_README) return true
        val prefix = archive.folder + "/" + EXTRAS_FOLDER + "/"
        if (!name.startsWith(prefix)) return false
        val file = name.substring(prefix.length)
        return file.length > EXTRA_SUFFIX.length && file.endsWith(EXTRA_SUFFIX) && '/' !in file
    }

    /** The checksum of the header: the sum of its bytes with the checksum field read as spaces. */
    private fun checksumOk(h: ByteArray): Boolean {
        val stored = octalField(h, 148, 8) ?: return false
        var sum = 0L
        for (i in h.indices) sum += if (i in 148 until 156) 0x20 else (h[i].toInt() and 0xff)
        return sum == stored
    }

    /** The size field: plain octal digits (the base-256 form for huge sizes is refused), or null. */
    private fun octal(h: ByteArray): Long? = octalField(h, 124, 12)

    private fun octalField(h: ByteArray, at: Int, width: Int): Long? {
        var value = 0L
        var digits = 0
        for (i in at until at + width) {
            val c = h[i].toInt() and 0xff
            if (c == 0 || c == 0x20) { if (digits > 0) break else continue }
            if (c !in '0'.code..'7'.code) return null
            value = value * 8 + (c - '0'.code)
            if (++digits > 11) return null
        }
        return value
    }

    /** The path of the entry (the prefix field of POSIX ustar headers, which is read only when the magic is "ustar\0": in a GNU header those bytes are times, and GNU long names are refused as an 'L' entry type; then the name), without a trailing slash of a folder; null when it is not a plain relative path. */
    private fun entryName(h: ByteArray, directory: Boolean): String? {
        val name = cString(h, 0, 100)
        val posix = cString(h, 257, 6) == "ustar" && h[262] == 0.toByte() // "ustar\0"; the GNU magic is "ustar " (and its bytes at 345 are times)
        val prefix = if (posix) cString(h, 345, 155) else ""
        var path = if (prefix.isEmpty()) name else "$prefix/$name"
        if (directory && path.endsWith("/")) path = path.dropLast(1)
        if (path.isEmpty() || path.length > MAX_NAME || path.startsWith("/") || '\\' in path || path.any { it.code < 0x20 }) return null
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return path
    }

    private fun padding(size: Long) = (BLOCK - size % BLOCK) % BLOCK

    private fun skip(s: InputStream, count: Long) {
        val buffer = ByteArray(BUFFER)
        var left = count
        while (left > 0) {
            val n = s.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("the archive ends early")
            left -= n
        }
    }

    private fun readFully(s: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = s.read(b, n, b.size - n)
            if (r < 0) return if (n == 0) false else throw IOException("the archive ends early")
            n += r
        }
        return true
    }

    private fun cString(b: ByteArray, at: Int, width: Int): String {
        var end = at
        while (end < at + width && b[end] != 0.toByte()) end++
        return String(b, at, end - at, Charsets.UTF_8)
    }

    /** At most [limit] bytes of [source]; closing it leaves [source] open (the tar goes on). */
    private class LimitedInputStream(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val c = source.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() {}
    }

    /** Hands the reads on and ignores [close] (the owner closes the real stream when it has read to the end). */
    private class NonClosingInputStream(source: InputStream) : java.io.FilterInputStream(source) {
        override fun close() {}
    }

    /**
     * The compressed archive as it is read: counts and hashes every byte, reports progress, and fails with an IOException (and [exceeded]) at the first byte
     * beyond [limit], so that a provider cannot make the install read more than the pinned size.
     */
    private class HashingInputStream(private val source: InputStream, private val limit: Long, private val onBytes: (Long) -> Unit) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
            private set
        var exceeded = false
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            while (true) { val n = read(one, 0, 1); if (n > 0) return one[0].toInt() and 0xff; if (n < 0) return -1 }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (exceeded) throw IOException("more than the pinned size") // never read on after the limit (the clamp below would ask for no bytes)
            if (len == 0) return 0
            val n = source.read(b, off, minOf(len.toLong(), limit - total + 1).toInt()) // one byte past the limit is enough to know
            if (n <= 0) return n
            total += n
            if (total > limit) { exceeded = true; throw IOException("more than the pinned size") }
            digest.update(b, off, n)
            onBytes(n.toLong())
            return n
        }

        /** Reads and hashes what is left (after a decoder that stops early), up to the end of the stream or [limit]. */
        fun readToEnd(control: InstallControl) {
            val buffer = ByteArray(BUFFER)
            while (!control.cancelled) if (read(buffer, 0, buffer.size) < 0) return
        }

        fun sha256(): String = hex(digest.digest())

        override fun close() = source.close()
    }

    companion object {
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        const val BLOCK = 512
        const val BUFFER = 1 shl 16
        const val STEP_INSTALLING = "installing"
        const val MAX_ENTRIES = 64
        const val MAX_EXTRAS = 32
        const val MAX_EXTRA_BYTES = 1L shl 20
        const val MAX_TRAILING_BYTES = 4L shl 20
        const val MAX_NAME = 255
        private const val EXTRA_README = "README.md"
        private const val EXTRAS_FOLDER = "test_wavs"
        private const val EXTRA_SUFFIX = ".wav"
    }
}
