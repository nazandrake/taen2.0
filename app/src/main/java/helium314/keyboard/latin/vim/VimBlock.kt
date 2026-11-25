// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * Visual block (Ctrl-V) text: the rectangle of characters from a top line to a bottom line and from a left to a right column, as one
 * piece of text per line, and the changes that delete, replace or put such a rectangle. A column is a count of characters (a tab or a wide
 * character is one column here; Android shows only one selection range, so the block is shown by its span and the mode line says so).
 */
internal object VimBlock {
    /** The part of one line inside the block: [start, end) in the text ("" at the line's end when the line does not reach the block). */
    class Seg(val start: Int, val end: Int, val text: String, val line: Int)

    /** A change of the text: replace [start, end) with [replacement], the caret at [caret]. */
    class Edit(val start: Int, val end: Int, val replacement: String, val caret: Int)

    private fun clusters(s: String): Int {
        val b = VimBuf(s)
        var i = 0
        var n = 0
        while (i < s.length) { i = if (s[i] == '\n') i + 1 else b.next(i); n++ }
        return n
    }

    /** One [Seg] per line from [lo] to [hi] (0-based lines), for the columns [left] to [right] ([Int.MAX_VALUE]: to the end of the line). */
    fun segments(b: VimBuf, lo: Int, hi: Int, left: Int, right: Int): List<Seg> {
        val out = ArrayList<Seg>()
        var ls = b.startOfLine(lo)
        for (line in lo..hi) {
            val le = b.lineEnd(ls)
            val start = b.atColumn(ls, left)
            if (start >= le) out.add(Seg(le, le, "", line))
            else {
                val end = if (right == Int.MAX_VALUE) le else maxOf(start, b.atColumn(ls, right + 1))
                out.add(Seg(start, end, b.text.substring(start, end), line))
            }
            if (le >= b.length) break
            ls = le + 1
        }
        return out
    }

    private fun lineRegion(b: VimBuf, segs: List<Seg>): Pair<Int, Int> {
        val first = segs.first().line
        val last = segs.last().line
        return b.startOfLine(first) to b.lineEnd(b.startOfLine(last))
    }

    /** Deleting the block: each line without its part. The caret is the block's top-left (the start of insert mode when [insert]). */
    fun removePlan(b: VimBuf, segs: List<Seg>, topLine: Int, left: Int): Edit {
        val (from, to) = lineRegion(b, segs)
        val sb = StringBuilder()
        var ls = from
        for ((k, seg) in segs.withIndex()) {
            val le = b.lineEnd(ls)
            sb.append(b.text, ls, seg.start).append(b.text, seg.end, le)
            if (k < segs.size - 1) sb.append('\n')
            ls = le + 1
        }
        val first = segs.first()
        val caret = first.start
        return Edit(from, to, sb.toString(), caret)
    }

    /** Replacing the part of each line by [f] of it. Null when no line reaches the block. */
    fun replacePlan(b: VimBuf, segs: List<Seg>, f: (Seg) -> String): Edit? {
        if (segs.all { it.text.isEmpty() }) return null
        val (from, to) = lineRegion(b, segs)
        val sb = StringBuilder()
        var ls = from
        for ((k, seg) in segs.withIndex()) {
            val le = b.lineEnd(ls)
            sb.append(b.text, ls, seg.start).append(if (seg.text.isEmpty()) "" else f(seg)).append(b.text, seg.end, le)
            if (k < segs.size - 1) sb.append('\n')
            ls = le + 1
        }
        var firstWithText = segs[0]
        for (seg in segs) if (seg.text.isNotEmpty()) { firstWithText = seg; break }
        return Edit(from, to, sb.toString(), firstWithText.start)
    }

    /**
     * Putting a block register: each row goes into the line below the one before, at the cursor column (after the cursor character for p),
     * short lines are padded with blanks up to it, and rows are padded to the width of the block when text follows them. Lines that do not
     * exist are added. [n] puts it side by side n times.
     */
    fun put(b: VimBuf, c: Int, reg: Reg, n: Int, before: Boolean): Edit? {
        val rows = reg.text.split('\n')
        val line0 = b.lineNumber(c)
        val col = if (b.isEmptyLine(c)) 0 else b.column(c) + (if (before) 0 else 1)
        val first = b.startOfLine(line0)
        val sb = StringBuilder()
        var ls = first
        var end = first
        var exists = true
        var caret = first
        for ((k, row) in rows.withIndex()) {
            fun pieces(tailFollows: Boolean): String {
                // every copy is padded to the width of the block, the last one only when text follows it
                val padded = if (clusters(row) < reg.width) row + " ".repeat(reg.width - clusters(row)) else row
                return padded.repeat(n - 1) + (if (tailFollows) padded else row)
            }
            if (exists) {
                val le = b.lineEnd(ls)
                val line = b.text.substring(ls, le)
                val len = clusters(line)
                if (len < col) {
                    val pad = " ".repeat(col - len)
                    if (k == 0) caret = ls + line.length + pad.length
                    sb.append(line).append(pad).append(pieces(false))
                } else {
                    val at = VimBuf(line).atColumn(0, col)
                    if (k == 0) caret = ls + at
                    sb.append(line, 0, at).append(pieces(at < line.length)).append(line, at, line.length)
                }
                end = le
                if (k < rows.size - 1) {
                    if (le < b.length) { sb.append('\n'); ls = le + 1 } else exists = false
                }
            } else {
                sb.append('\n').append(" ".repeat(col)).append(pieces(false))
            }
        }
        return Edit(first, end, sb.toString(), caret)
    }
}
