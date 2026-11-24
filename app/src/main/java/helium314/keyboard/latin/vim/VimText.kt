// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import java.text.BreakIterator
import java.util.Locale

/**
 * The field text as Vim sees a buffer: lines separated by '\n', a position is an offset in the text, and the offset of a line's
 * '\n' (or the text length for the last line) is the "end of line" position that Vim calls the NUL. A character is one grapheme
 * cluster, so a caret is never placed inside a surrogate pair, a combined emoji or a letter with its accents.
 */
internal class VimBuf(val text: String) {
    val length = text.length

    // a fresh iterator for each question: one iterator that answers following() and preceding() in turn gives wrong answers at the end of the text
    private fun iter(): BreakIterator = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(text) }

    private fun plain(i: Int) = i < 0 || i >= length || text[i] < '\u0300' // no combining, joining or surrogate: every char is a cluster

    /** The first cluster boundary after [p] (p < length). */
    private fun following(p: Int): Int =
        if (plain(p) && plain(p + 1) && !(text[p] == '\r' && p + 1 < length && text[p + 1] == '\n')) p + 1 else iter().following(p)

    /** The last cluster boundary before [p] (p > 0). */
    private fun preceding(p: Int): Int =
        if (plain(p - 1) && plain(p - 2) && plain(p) && !(text[p - 1] == '\r' && p < length && text[p] == '\n')) p - 1 else iter().preceding(p)

    fun lineStart(p: Int): Int = text.lastIndexOf('\n', p - 1) + 1

    fun lineEnd(p: Int): Int = text.indexOf('\n', p).let { if (it < 0) length else it }

    fun isLastLine(p: Int) = lineEnd(p) >= length

    fun isFirstLine(p: Int) = lineStart(p) == 0

    /** True when line [p] is on has no characters. */
    fun isEmptyLine(p: Int) = lineStart(p) == lineEnd(p)

    /** The start of the cluster after the one that starts at [p], at most [lineEnd]. */
    fun next(p: Int): Int {
        val end = lineEnd(p)
        if (p >= end) return end
        val n = following(p)
        return if (n == BreakIterator.DONE || n > end) end else n
    }

    /** The start of the cluster before [p], at least the start of its line (a cluster never crosses '\n'). */
    fun prev(p: Int): Int {
        val start = lineStart(p)
        if (p <= start) return start
        val n = preceding(p)
        return if (n == BreakIterator.DONE || n < start) start else n
    }

    /** [p] moved left to the start of the cluster that contains it. */
    fun snap(p: Int): Int {
        val at = p.coerceIn(0, length)
        return if (at == length || iter().isBoundary(at)) at else iter().preceding(at)
    }

    /** The start of the last cluster of the line of [p], or the line's start when it is empty. */
    fun lastChar(p: Int): Int {
        val end = lineEnd(p)
        return if (end == lineStart(p)) end else prev(end)
    }

    /** A cursor of Normal mode: on a character, never on the end of a non-empty line. */
    fun normal(p: Int): Int {
        val at = snap(p)
        val end = lineEnd(at)
        return if (at >= end) lastChar(at) else at
    }

    /** The first non-blank of the line of [p] (the last character of an all-blank line). */
    fun firstNonBlank(p: Int): Int {
        val end = lineEnd(p)
        var i = lineStart(p)
        while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
        return if (i >= end && end > lineStart(p)) prev(end) else i
    }

    /** Clusters of the line before the cluster at [p]. */
    fun column(p: Int): Int {
        var i = lineStart(p)
        var n = 0
        while (i < p) { i = next(i); n++ }
        return n
    }

    /** The offset [col] clusters into the line of [lineAt]; the end of line when the line is shorter. */
    fun atColumn(lineAt: Int, col: Int): Int {
        var i = lineStart(lineAt)
        val end = lineEnd(lineAt)
        var n = 0
        while (i < end && n < col) { i = next(i); n++ }
        return i
    }

    fun lineNumber(p: Int): Int {
        var n = 0
        var i = text.indexOf('\n')
        while (i in 0 until p) { n++; i = text.indexOf('\n', i + 1) }
        return n
    }

    fun lineCount(): Int = text.count { it == '\n' } + 1

    /** The start of line number [n] (0-based), clamped to the last line. */
    fun startOfLine(n: Int): Int {
        var start = 0
        repeat(n) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return start
            start = nl + 1
        }
        return start
    }

    fun codePointAt(p: Int): Int = text.codePointAt(p)

    fun isBlankAt(p: Int) = p < length && (text[p] == ' ' || text[p] == '\t')

    companion object {
        /** Vim's character classes: 0 blank, 1 punctuation, 2 word character, 3 emoji. [big] collapses everything but blanks (W, B, E). */
        fun classOf(cp: Int, big: Boolean): Int {
            val c = utfClass(cp)
            return if (c != 0 && big) 1 else c
        }

        private fun isWordLatin(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code || cp == '_'.code || cp in 192..255

        private class Range(val from: Int, val to: Int, val cls: Int)

        // the punctuation, space and symbol ranges of Vim's utf_class (above U+00FF; everything else is a word character)
        private val ranges = listOf(
            Range(0x037e, 0x037e, 1), Range(0x0387, 0x0387, 1), Range(0x055a, 0x055f, 1), Range(0x0589, 0x0589, 1),
            Range(0x05be, 0x05be, 1), Range(0x05c0, 0x05c0, 1), Range(0x05c3, 0x05c3, 1), Range(0x05f3, 0x05f4, 1),
            Range(0x060c, 0x060c, 1), Range(0x061b, 0x061b, 1), Range(0x061f, 0x061f, 1), Range(0x066a, 0x066d, 1),
            Range(0x06d4, 0x06d4, 1), Range(0x0700, 0x070d, 1), Range(0x0964, 0x0965, 1), Range(0x0970, 0x0970, 1),
            Range(0x0df4, 0x0df4, 1), Range(0x0e4f, 0x0e4f, 1), Range(0x0e5a, 0x0e5b, 1), Range(0x0f04, 0x0f12, 1),
            Range(0x0f3a, 0x0f3d, 1), Range(0x0f85, 0x0f85, 1), Range(0x104a, 0x104f, 1), Range(0x10fb, 0x10fb, 1),
            Range(0x1361, 0x1368, 1), Range(0x166d, 0x166e, 1), Range(0x1680, 0x1680, 0), Range(0x169b, 0x169c, 1),
            Range(0x16eb, 0x16ed, 1), Range(0x1735, 0x1736, 1), Range(0x17d4, 0x17dc, 1), Range(0x1800, 0x180a, 1),
            Range(0x2000, 0x200b, 0), Range(0x200c, 0x2027, 1), Range(0x2028, 0x2029, 0), Range(0x202a, 0x202e, 1),
            Range(0x202f, 0x202f, 0), Range(0x2030, 0x205e, 1), Range(0x205f, 0x205f, 0), Range(0x2060, 0x206f, 1),
            Range(0x20a0, 0x27ff, 1), Range(0x2900, 0x2998, 1), Range(0x29d8, 0x29db, 1), Range(0x29fc, 0x29fd, 1),
            Range(0x2e00, 0x2e7f, 1), Range(0x3000, 0x3000, 0), Range(0x3001, 0x3020, 1), Range(0x3030, 0x3030, 1),
            Range(0x303d, 0x303d, 1), Range(0xfd3e, 0xfd3f, 1), Range(0xfe30, 0xfe6b, 1), Range(0xff00, 0xff0f, 1),
            Range(0xff1a, 0xff20, 1), Range(0xff3b, 0xff40, 1), Range(0xff5b, 0xff65, 1), Range(0x1d000, 0x1d24f, 1),
            Range(0x1d400, 0x1d7ff, 1), Range(0x1f000, 0x1f2ff, 1), Range(0x1f300, 0x1f9ff, 1),
        )

        private fun utfClass(cp: Int): Int {
            if (cp < 0x100) {
                if (cp == ' '.code || cp == '\t'.code || cp == 0 || cp == 0xa0) return 0
                return if (isWordLatin(cp)) 2 else 1
            }
            for (r in ranges) if (cp in r.from..r.to) return r.cls
            return 2
        }
    }
}
