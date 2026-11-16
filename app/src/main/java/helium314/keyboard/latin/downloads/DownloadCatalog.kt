// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import android.content.Context

/** One optional download: a public file that the user fetches with the browser and installs from the picker, pinned by size and SHA-256. */
class DownloadEntry(
    val id: String,
    val kind: String,
    val locale: String,
    val size: Long,
    val sha256: String,
    val url: String,
    val licence: String,
    val source: String,
) {
    companion object {
        const val DICTIONARY = "dictionary"
        /** A next-word model (one .onnx file; its tokenizer is in the APK). */
        const val MODEL = "model"
    }
}

/**
 * The in-APK catalogue of the optional downloads, assets/catalog/downloads.tsv (tab separated: id, kind, locale, size, SHA-256, URL, licence, source;
 * lines that start with '#' are comments). tools/downloads.lock is the same file for the build PC (DownloadCatalogTest keeps them equal).
 * The keyboard never connects to a URL: [DownloadEntry.url] is only handed to the browser (DownloadAccess). Every URL is https, on one of the two
 * allowed hosts and pinned to a commit, because a branch name or `latest` can change the file under the pinned hash ([problems]).
 */
object DownloadCatalog {
    const val ASSET = "catalog/downloads.tsv"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val PINNED_URL = Regex("""https://(?:codeberg\.org/[^/\s]+/[^/\s]+/raw/commit/[0-9a-f]{40}/|huggingface\.co/[^/\s]+/[^/\s]+/resolve/[0-9a-f]{40}/)[^\s?#]+(?:\?download=true)?""")
    private val KINDS = setOf(DownloadEntry.DICTIONARY, DownloadEntry.MODEL)

    /** Parses [text]; throws IllegalArgumentException for a row with the wrong number of columns or a size that is not a number. */
    fun parse(text: String): List<DownloadEntry> = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
        val c = line.split('\t')
        require(c.size == 8) { "a catalogue row has 8 columns" }
        DownloadEntry(c[0], c[1], c[2], requireNotNull(c[3].toLongOrNull()) { "size is not a number" }, c[4], c[5], c[6], c[7])
    }.toList()

    /** What is wrong with [entries]; empty when the catalogue is acceptable. */
    fun problems(entries: List<DownloadEntry>): List<String> {
        val found = ArrayList<String>()
        if (entries.map { it.id }.toSet().size != entries.size) found.add("duplicate id")
        for (e in entries) {
            if (e.id.isBlank() || e.licence.isBlank() || e.source.isBlank()) found.add("${e.id}: id, licence and source must be given")
            if (e.kind !in KINDS) found.add("${e.id}: unknown kind")
            if (e.size <= 0) found.add("${e.id}: size must be positive")
            if (!SHA256.matches(e.sha256)) found.add("${e.id}: SHA-256 must be 64 lower-case hex characters")
            if (!PINNED_URL.matches(e.url) || e.url.contains("/branch/") || e.url.contains("latest")) found.add("${e.id}: URL must be https on codeberg.org or huggingface.co and pinned to a commit")
        }
        return found
    }

    /** The catalogue of the APK. Throws when the asset is unreadable or not acceptable: a damaged catalogue offers nothing. */
    fun load(context: Context): List<DownloadEntry> {
        val text = context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val entries = parse(text)
        check(problems(entries).isEmpty()) { "the download catalogue is not valid" }
        return entries
    }
}
