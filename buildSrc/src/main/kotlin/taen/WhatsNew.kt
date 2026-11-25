package taen

import java.io.File

/**
 * The notes of the "What's new" box. One owner: the entry `## <versionName>` of CHANGELOG.md, built into the asset [ASSET] of every variant (the build
 * reads nothing but that file and the version, so two builds of one commit give the same bytes).
 *
 * Extraction rule (BUILDING.md): the lines of the entry from the first non-blank line up to the first blank line (the summary: 3 to 6 lines; the
 * details of an entry follow after a blank line and are not shown). A bullet (`- ` or `* `) starts a paragraph; any other line continues the paragraph
 * before it (a bullet wrapped over lines is one paragraph); a `#` heading line is a paragraph of its own without the marker. Paragraphs are joined by a
 * blank line, a bullet is shown as a dot. Markdown markers are removed: `**bold**`, `__bold__`, `` `code` ``, `[text](link)`. Nothing else is touched.
 */
object WhatsNew {
    const val ASSET = "whats_new.txt"

    private val bullet = Regex("""^[-*] +""")
    private val heading = Regex("""^#{1,6} +""")
    private val strong = Regex("""\*\*(.+?)\*\*|(?<![\w])__(.+?)__(?![\w])""")
    private val code = Regex("""`([^`]+)`""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")

    /** The text for [versionName], or null when the file has no such entry or its summary is empty. */
    fun notes(changelog: String, versionName: String): String? {
        val lines = changelog.lines().map { it.trimEnd() }
        val start = lines.indexOfFirst { it == "## $versionName" }
        if (start < 0) return null
        val summary = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() }
        val paragraphs = ArrayList<String>()
        for (line in summary) {
            val text = line.trim()
            when {
                bullet.containsMatchIn(text) -> paragraphs.add("• " + text.replace(bullet, ""))
                heading.containsMatchIn(text) -> paragraphs.add(text.replace(heading, ""))
                paragraphs.isEmpty() -> paragraphs.add(text)
                else -> paragraphs[paragraphs.lastIndex] = paragraphs.last() + " " + text
            }
        }
        val result = paragraphs.joinToString("\n\n") { plain(it) }
        return result.takeIf { it.isNotBlank() }
    }

    private fun plain(text: String): String = text
        .replace(link) { it.groupValues[1] }
        .replace(strong) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .replace(code) { it.groupValues[1] }

    /**
     * Writes [ASSET] into [outDir]. A build that [required] the notes (release) fails when [changelog] has no entry for [versionName]; the others write an
     * empty file, which the app reads as "no box".
     */
    fun write(changelog: File, versionName: String, required: Boolean, outDir: File) {
        val text = notes(changelog.readText(), versionName)
        check(text != null || !required) { "CHANGELOG.md has no entry (or an empty one) for version $versionName: add '## $versionName' with a summary before the first blank line" }
        outDir.mkdirs()
        File(outDir, ASSET).writeText(text ?: "")
    }
}
