// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

/** A piece of running text of the manual. */
sealed interface Span {
    data class Text(val text: String) : Span
    data class Bold(val children: List<Span>) : Span
    data class Code(val text: String) : Span
    /** A link to another page of the manual: [page] is the page id, [text] what the link says. */
    data class Link(val text: String, val page: String) : Span
}

/** A block of a page. */
sealed interface Block {
    /** A heading; [key] is the hidden `{#key}` of a settings entry (the title shown is then the app's own text for that setting, [spans] is empty). */
    data class Heading(val level: Int, val spans: List<Span>, val key: String?) : Block
    data class Paragraph(val spans: List<Span>) : Block
    data class Bullets(val items: List<List<Span>>) : Block
    data class Numbered(val items: List<List<Span>>) : Block
    data class Code(val text: String) : Block
    /** A Vim example (fence `example`, see [ManualExample]): the text before and after with the cursor marked by `|`, and the keys. */
    data class Example(val before: String, val keys: String, val after: String) : Block
}

/**
 * The renderer of the manual: a small, pure parser for a fixed subset of Markdown: headings `#` to `###` (a hidden `{#key}` at the end names the
 * setting that a settings entry describes), paragraphs, `-` and `1.` lists (an indented line continues the item), `**bold**`, `code`, fenced code
 * blocks, `[text](page:id)` links to other pages and the `example` fence. Anything else is kept as plain text and never dropped: a table, a quote,
 * `*italic*`, an outside link and an unclosed marker show as they were written.
 */
object ManualMarkdown {
    private val heading = Regex("^(#{1,3}) (.*)$")
    private val keyMarker = Regex("\\s*\\{#([A-Za-z0-9_]+)\\}\\s*$")
    private val bullet = Regex("^- (.*)$")
    private val numbered = Regex("^\\d+\\. (.*)$")
    /** Lines of a syntax that this renderer does not know and that depends on its line breaks (tables, quotes, star lists, rules, html). */
    private val verbatimStart = Regex("^(\\||>|\\* |\\+ |<|-{3,}|\\*{3,}|_{3,}|!\\[)")
    private val link = Regex("\\[([^\\]\\n]+)]\\(page:([a-z0-9-]+)\\)")

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val h = heading.matchEntire(line)
            when {
                line.isBlank() -> i++
                line.startsWith("```") -> {
                    val info = line.substring(3).trim()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != "```") body.add(lines[i++])
                    i++ // the closing fence (past the end when the fence is not closed: the rest of the page is code)
                    val code = body.joinToString("\n")
                    blocks.add(if (info == "example") ManualExample.parse(code) ?: Block.Code(code) else Block.Code(code))
                }
                h != null -> {
                    val marker = keyMarker.find(h.groupValues[2])
                    val title = if (marker != null) h.groupValues[2].substring(0, marker.range.first) else h.groupValues[2]
                    blocks.add(Block.Heading(h.groupValues[1].length, if (title.isBlank()) emptyList() else inline(title.trim()), marker?.groupValues?.get(1)))
                    i++
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    val item = if (bullet.matches(line)) bullet else numbered
                    val items = ArrayList<List<Span>>()
                    while (i < lines.size && item.matches(lines[i])) {
                        val parts = arrayListOf(item.matchEntire(lines[i])!!.groupValues[1].trim())
                        i++
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank()) parts.add(lines[i++].trim())
                        items.add(inline(parts.joinToString(" ")))
                    }
                    blocks.add(if (item === bullet) Block.Bullets(items) else Block.Numbered(items))
                }
                else -> {
                    val verbatim = verbatimStart.containsMatchIn(line)
                    val para = ArrayList<String>()
                    while (i < lines.size && !startsBlock(lines[i])) para.add(lines[i++].trim())
                    blocks.add(Block.Paragraph(inline(para.joinToString(if (verbatim) "\n" else " "))))
                }
            }
        }
        return blocks
    }

    private fun startsBlock(line: String) =
        line.isBlank() || line.startsWith("```") || heading.matches(line) || bullet.matches(line) || numbered.matches(line)

    private fun inline(s: String): List<Span> {
        val out = ArrayList<Span>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) out.add(Span.Text(text.toString()))
            text.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out.add(Span.Code(s.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            } else if (c == '*' && s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    out.add(Span.Bold(inline(s.substring(i + 2, end))))
                    i = end + 2
                    continue
                }
            } else if (c == '[' && (i == 0 || s[i - 1] != '!')) {
                val m = link.matchAt(s, i)
                if (m != null) {
                    flush()
                    out.add(Span.Link(m.groupValues[1], m.groupValues[2]))
                    i += m.value.length
                    continue
                }
            }
            text.append(c)
            i++
        }
        flush()
        return out
    }
}
