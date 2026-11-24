// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * Text objects of an operator: iw, aw, the quote objects (i" a" i' a' i` a`) and the bracket objects (i( a( ib, i[, i{ iB, i<) on the
 * line of the cursor. A pair that is not whole on that line gives nothing (several lines are not in the first version).
 */
internal object VimObjects {
    private val pairs = mapOf(
        '(' to ('(' to ')'), ')' to ('(' to ')'), 'b' to ('(' to ')'),
        '[' to ('[' to ']'), ']' to ('[' to ']'),
        '{' to ('{' to '}'), '}' to ('{' to '}'), 'B' to ('{' to '}'),
        '<' to ('<' to '>'), '>' to ('<' to '>'),
    )

    /** The range [start, end) of the object [key] around [c], or null. [around] is the "a" form, [count] counts words. */
    fun find(b: VimBuf, c: Int, around: Boolean, key: Int, count: Int): Pair<Int, Int>? {
        if (key <= 0 || key > 0xFFFF) return null
        val ch = key.toChar()
        return when {
            ch == 'w' -> word(b, c, around, count, big = false)
            ch == 'W' -> word(b, c, around, count, big = true)
            ch == '"' || ch == '\'' || ch == '`' -> quote(b, c, around, ch)
            pairs.containsKey(ch) -> bracket(b, c, around, pairs.getValue(ch))
            else -> null
        }
    }

    private fun cls(b: VimBuf, p: Int, big: Boolean) = VimBuf.classOf(b.codePointAt(p), big)

    private fun runStart(b: VimBuf, p: Int, big: Boolean): Int {
        val k = cls(b, p, big)
        var i = p
        val ls = b.lineStart(p)
        while (i > ls) {
            val q = b.prev(i)
            if (cls(b, q, big) != k) break
            i = q
        }
        return i
    }

    private fun runEnd(b: VimBuf, p: Int, big: Boolean): Int {
        val k = cls(b, p, big)
        val le = b.lineEnd(p)
        var i = p
        while (i < le && cls(b, i, big) == k) i = b.next(i)
        return i
    }

    private fun word(b: VimBuf, c: Int, around: Boolean, count: Int, big: Boolean): Pair<Int, Int>? {
        val le = b.lineEnd(c)
        if (c >= le) return null
        val start0 = runStart(b, c, big)
        var start = start0
        var end = runEnd(b, c, big)
        if (!around) {
            for (i in 2..count) {
                if (end >= le) break
                end = runEnd(b, end, big)
            }
            return start to end
        }
        val onBlank = cls(b, c, big) == 0
        if (onBlank) {
            if (end >= le) return null // blanks to the end of the line: Vim goes on to the word on the next line, which is not in the first version
            end = runEnd(b, end, big) // the blanks and the word after them
        } else if (end < le && cls(b, end, big) == 0) {
            end = runEnd(b, end, big) // the word and the blanks after it
        } else if (start > b.lineStart(c) && cls(b, b.prev(start), big) == 0) {
            start = runStart(b, b.prev(start), big) // no blanks after: the blanks before it
        }
        for (i in 2..count) {
            if (end >= le) break
            end = runEnd(b, end, big)
            if (end < le && cls(b, end, big) == 0) end = runEnd(b, end, big)
        }
        return start to end
    }

    private fun quote(b: VimBuf, c: Int, around: Boolean, q: Char): Pair<Int, Int>? {
        val ls = b.lineStart(c)
        val le = b.lineEnd(c)
        val quotes = ArrayList<Int>()
        var i = ls
        while (i < le) {
            val ch = b.text[i]
            if (ch == '\\') { i = minOf(i + 2, le); continue }
            if (ch == q) quotes.add(i)
            i++
        }
        var k = 0
        while (k + 1 < quotes.size) {
            val open = quotes[k]
            val close = quotes[k + 1]
            if (c <= close) { // the pair that holds the cursor, or the first one after it
                if (!around) return (open + 1) to close
                var end = close + 1
                var start = open
                if (end < le && b.isBlankAt(end)) { while (end < le && b.isBlankAt(end)) end++ }
                else while (start > ls && b.isBlankAt(start - 1)) start--
                return start to end
            }
            k += 2
        }
        return null
    }

    private fun bracket(b: VimBuf, c: Int, around: Boolean, p: Pair<Char, Char>): Pair<Int, Int>? {
        val ls = b.lineStart(c)
        val le = b.lineEnd(c)
        val stack = ArrayList<Int>()
        var best: Pair<Int, Int>? = null // the innermost pair around the cursor
        var ahead: Pair<Int, Int>? = null // else the first pair that opens after it on the line
        for (i in ls until le) {
            val ch = b.text[i]
            if (ch == p.first) stack.add(i)
            else if (ch == p.second && stack.isNotEmpty()) {
                val open = stack.removeAt(stack.size - 1)
                if (open <= c && c <= i) { if (best == null || open > best.first) best = open to i }
                else if (open > c && (ahead == null || open < ahead.first)) ahead = open to i
            }
        }
        val pair = best ?: ahead ?: return null
        return if (around) pair.first to (pair.second + 1) else (pair.first + 1) to pair.second
    }
}
