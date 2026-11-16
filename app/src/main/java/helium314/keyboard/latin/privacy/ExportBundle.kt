// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import helium314.keyboard.latin.media.MediaVault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What an export holds, in memory. [files] are relative to the app's files directory, [protectedFiles] to the
 * device-protected one; [learned] maps a language tag to the serialized learned-word dictionary.
 */
class ExportParts(
    val prefsDe: Map<String, Any>?,
    val prefsCe: Map<String, Any>?,
    val personal: List<PersonalWord>?,
    val learned: Map<String, ByteArray>,
    val files: Map<String, ByteArray>,
    val protectedFiles: Map<String, ByteArray>,
    /** The imported pictures, or null when the export holds none: the live pictures are then left alone by an import. */
    val media: MediaVault.Snapshot? = null,
    /** Set by the export: the pictures were left out because they are larger than [ExportService.MEDIA_EXPORT_BYTES] (the result says so). */
    val mediaExcluded: Boolean = false,
)

/**
 * The payload that [ExportCodec] encrypts: a zip with fixed entry names. It is only ever held in memory, never written
 * as a plain file. [unpack] reads everything before the caller changes anything and rejects the whole payload on an
 * unsafe name, a duplicate, a damaged record, or content larger than the limits (a zip bomb).
 */
object ExportBundle {
    private const val VERSION_ENTRY = "taen-export"
    private const val VERSION = "1"
    private const val PREFS_DE = "prefs_de.bin"
    private const val PREFS_CE = "prefs_ce.bin"
    private const val PERSONAL = "personal.bin"
    private const val LEARNED = "learned/"
    private const val FILES = "files/"
    private const val PROTECTED = "protected/"
    private const val MEDIA = "media/"
    private const val MEDIA_INDEX = "media/index"
    private val MEDIA_ID = Regex("[0-9a-f]{16}")
    const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    const val MAX_ENTRIES = 4096
    private val TAG = Regex("[A-Za-z0-9_-]{1,64}")

    fun pack(parts: ExportParts): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(VERSION_ENTRY, VERSION.toByteArray())
            parts.prefsDe?.let { put(PREFS_DE, PrefsCodec.encode(it)) }
            parts.prefsCe?.let { put(PREFS_CE, PrefsCodec.encode(it)) }
            parts.personal?.let { put(PERSONAL, PersonalDictionaryCodec.encode(it)) }
            parts.learned.forEach { (tag, bytes) -> put("$LEARNED$tag.bin", bytes) }
            parts.files.forEach { (path, bytes) -> put(FILES + path, bytes) }
            parts.protectedFiles.forEach { (path, bytes) -> put(PROTECTED + path, bytes) }
            parts.media?.let { m ->
                put(MEDIA_INDEX, m.index)
                m.items.forEach { (id, bytes) -> put("$MEDIA$id.bin", bytes) }
            }
        }
        return out.toByteArray()
    }

    /** The bytes the payload holds when unpacked (what [unpack] counts against its limit), without building it. */
    fun contentBytes(parts: ExportParts): Long =
        (parts.prefsDe?.let { PrefsCodec.encode(it).size } ?: 0).toLong() + (parts.prefsCe?.let { PrefsCodec.encode(it).size } ?: 0) +
            (parts.personal?.let { PersonalDictionaryCodec.encode(it).size } ?: 0) +
            parts.learned.values.sumOf { it.size.toLong() } + parts.files.values.sumOf { it.size.toLong() } + parts.protectedFiles.values.sumOf { it.size.toLong() } +
            (parts.media?.let { m -> m.index.size + m.items.values.sumOf { it.size.toLong() } } ?: 0L)

    fun unpack(bytes: ByteArray, maxTotalBytes: Long = MAX_TOTAL_BYTES, maxEntries: Int = MAX_ENTRIES): ExportParts {
        var prefsDe: Map<String, Any>? = null
        var prefsCe: Map<String, Any>? = null
        var personal: List<PersonalWord>? = null
        val learned = HashMap<String, ByteArray>()
        val files = HashMap<String, ByteArray>()
        val protectedFiles = HashMap<String, ByteArray>()
        var mediaIndex: ByteArray? = null
        val mediaItems = HashMap<String, ByteArray>()
        val seen = HashSet<String>()
        var total = 0L
        var sawVersion = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (seen.size >= maxEntries) throw ExportException("too many entries")
                    if (!seen.add(entry.name)) throw ExportException("duplicate entry")
                    val data = readLimited(zip, maxTotalBytes - total)
                    total += data.size
                    val name = entry.name
                    try {
                        when {
                            name == VERSION_ENTRY -> {
                                if (String(data) != VERSION) throw ExportException("unsupported export version")
                                sawVersion = true
                            }
                            name == PREFS_DE -> prefsDe = PrefsCodec.decode(data)
                            name == PREFS_CE -> prefsCe = PrefsCodec.decode(data)
                            name == PERSONAL -> personal = PersonalDictionaryCodec.decode(data)
                            name.startsWith(LEARNED) -> {
                                val tag = name.removePrefix(LEARNED).removeSuffix(".bin")
                                if (!name.endsWith(".bin") || !TAG.matches(tag)) throw ExportException("bad entry name")
                                learned[tag] = data
                            }
                            name.startsWith(FILES) -> files[safePath(name.removePrefix(FILES))] = data
                            name.startsWith(PROTECTED) -> protectedFiles[safePath(name.removePrefix(PROTECTED))] = data
                            name == MEDIA_INDEX -> mediaIndex = data
                            name.startsWith(MEDIA) -> {
                                val id = name.removePrefix(MEDIA).removeSuffix(".bin")
                                if (!name.endsWith(".bin") || !MEDIA_ID.matches(id)) throw ExportException("bad entry name")
                                mediaItems[id] = data
                            }
                            // anything else is ignored (a newer export may carry more)
                        }
                    } catch (e: CorruptStoreException) {
                        throw ExportException("damaged record in $name")
                    }
                }
            }
        } catch (e: ZipException) {
            throw ExportException("not a valid export payload")
        } catch (e: IOException) {
            throw ExportException("not a valid export payload")
        }
        if (!sawVersion) throw ExportException("not an export payload")
        if (mediaIndex == null && mediaItems.isNotEmpty()) throw ExportException("pictures without a list")
        val media = mediaIndex?.let { MediaVault.Snapshot(it, mediaItems) }
        if (media != null && !MediaVault.validate(media)) throw ExportException("damaged pictures")
        return ExportParts(prefsDe, prefsCe, personal, learned, files, protectedFiles, media)
    }

    private fun readLimited(zip: ZipInputStream, remaining: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            count += n
            if (count > remaining) throw ExportException("content too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** A relative path of normal segments only (no "..", ".", empty segment, backslash, NUL or oversized name). */
    private fun safePath(path: String): String {
        if (path.isEmpty() || path.length > 1024) throw ExportException("bad path")
        for (segment in path.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == ".." || segment.length > 255) throw ExportException("bad path")
            if (segment.any { it == '\\' || it == '\u0000' }) throw ExportException("bad path")
        }
        return path
    }
}
