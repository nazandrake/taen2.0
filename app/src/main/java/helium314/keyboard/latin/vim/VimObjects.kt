// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * Text objects of an operator: iw, aw, iW, aW (over line breaks, with counts), the quote objects (i" a" i' a' i` a`) on the line of the
 * cursor, and the bracket objects (i( a( ib, i[, i{ iB, i<) over several lines, with counts. The word and block objects are ports of Vim's
 * current_word and current_block, so that the edge cases (indent, a closing bracket alone on its line) come out as Vim's do.
 */
internal object VimObjects {
    private val pairs = mapOf(
        '(' to ('(' to ')'), ')' to ('(' to ')'), 'b' to ('(' to ')'),
        '[' to ('[' to ']'), ']' to ('[' to ']'),
        '{' to ('{' to '}'), '}' to ('{' to '}'), 'B' to ('{' to '}'),
        '<' to ('<' to '>'), '>' to ('<' to '>'),
    )

    /**
     * The range [start, end) of the object [key] around [c], or null; [end] is exclusive and the caller adjusts it as for an exclusive motion.
     * [inclusiveWord] tells whether Vim takes the object as an inclusive motion (the word objects do): an empty one then still changes registers with c.
     */
    class Obj(val start: Int, val end: Int, val inclusive: Boolean, val failAt: Int = -1, val linewise: Boolean = false)

    fun find(b: VimBuf, c: Int, around: Boolean, key: Int, count: Int): Obj? {
        if (key <= 0 || key > 0xFFFF) return null
        val ch = key.toChar()
        if (ch == 'w' || ch == 'W') return word(b, c, count, around, big = ch == 'W')
        if (ch == 'p') return paragraph(b, c, around, count)
        val r = when {
            ch == '"' || ch == '\'' || ch == '`' -> quote(b, c, around, ch, count)
            pairs.containsKey(ch) -> block(b, c, around, pairs.getValue(ch), count)
            else -> null
        } ?: return null
        return Obj(r.first, r.second, false)
    }

    /**
     * Vim's current_word: [include] is the "a" form. A failure (the text ends before the count is used) leaves the cursor where the walk ended,
     * as Vim does: the result then has [Obj.failAt].
     */
    private fun word(b: VimBuf, from: Int, count0: Int, include: Boolean, big: Boolean): Obj {
        val c = VimMotions.Cur(b, from, big)
        var count = count0
        var inclusive = true
        var includeWhite = false
        c.backInLine()
        var start = c.pos
        if ((c.cls() == 0) == include) {
            if (!VimMotions.endWord(c, 1, true, true)) return Obj(0, 0, true, failAt = c.pos)
        } else {
            VimMotions.fwdWord(c, 1, true)
            if (c.pos == b.lineStart(c.pos)) c.decl() else c.oneLeft()
            if (include) includeWhite = true
        }
        count--
        while (count > 0) {
            inclusive = true
            if (c.incl() == -1) return Obj(0, 0, true, failAt = c.pos)
            if (include != (c.cls() == 0)) {
                if (!VimMotions.fwdWord(c, 1, true) && count > 1) return Obj(0, 0, true, failAt = c.pos)
                if (!c.oneLeft()) inclusive = false
            } else if (!VimMotions.endWord(c, 1, true, true)) return Obj(0, 0, true, failAt = c.pos)
            count--
        }
        if (includeWhite && (c.cls() != 0 || (c.pos == b.lineStart(c.pos) && !inclusive))) {
            // no white space at the end: take some before the word, but not the indent of the line
            val save = c.pos
            c.pos = start
            if (c.oneLeft()) {
                c.backInLine()
                if (c.cls() == 0 && c.pos > b.lineStart(c.pos)) start = c.pos
            }
            c.pos = save
        }
        if (c.pos < start) { // the end went back before the start (an empty last line, a word that ends its line): the ends are swapped, as Vim does
            return Obj(c.pos, if (inclusive && start < b.lineEnd(start)) b.next(start) else start, inclusive)
        }
        val end = if (inclusive && c.pos < b.lineEnd(c.pos)) b.next(c.pos) else c.pos
        return Obj(start, end, inclusive)
    }

    /**
     * Vim's current_word in Visual mode with a selection of more than one character: [cursor] goes forward by [count] objects. Returns the new
     * cursor and whether all of them were found (the cursor has moved as far as it got either way).
     */
    fun extendWord(b: VimBuf, cursor: Int, count0: Int, include: Boolean, big: Boolean): Pair<Int, Boolean> {
        val c = VimMotions.Cur(b, cursor, big)
        var count = count0
        while (count > 0) {
            if (c.incl() == -1) return c.pos to false
            if (include != (c.cls() == 0)) {
                if (!VimMotions.fwdWord(c, 1, true) && count > 1) return c.pos to false
                c.oneLeft()
            } else if (!VimMotions.endWord(c, 1, true, true)) return c.pos to false
            count--
        }
        return c.pos to true
    }

    private fun lineWhite(b: VimBuf, ls: Int): Boolean {
        var i = ls
        val e = b.lineEnd(ls)
        while (i < e && (b.text[i] == ' ' || b.text[i] == '\t')) i++
        return i >= e
    }

    /** Vim's current_par: the paragraph (lines of text) or the blank lines around the cursor, with the blank lines after (or before) it for "ap". Whole lines. */
    private fun paragraph(b: VimBuf, from: Int, include: Boolean, count: Int): Obj? {
        val last = b.lineCount() - 1
        fun start(n: Int) = b.startOfLine(n)
        var startLine = b.lineNumber(from)
        val whiteInFront = lineWhite(b, start(startLine))
        while (startLine > 0) {
            if (whiteInFront) { if (!lineWhite(b, start(startLine - 1))) break }
            else if (lineWhite(b, start(startLine - 1)) || b.lineEnd(start(startLine)) == start(startLine)) break
            startLine--
        }
        var endLine = startLine
        while (endLine <= last && lineWhite(b, start(endLine))) endLine++
        endLine--
        var i = count
        if (!include && whiteInFront) i--
        var doWhite = false
        while (i-- > 0) {
            if (endLine == last) return null // Vim fails here without moving the cursor
            if (!include) doWhite = lineWhite(b, start(endLine + 1))
            if (include || !doWhite) {
                endLine++
                while (endLine < last && !lineWhite(b, start(endLine + 1)) && b.lineEnd(start(endLine + 1)) != start(endLine + 1)) endLine++
            }
            if (i == 0 && whiteInFront && include) break
            if (include || doWhite) while (endLine < last && lineWhite(b, start(endLine + 1))) endLine++
        }
        if (!whiteInFront && !lineWhite(b, start(endLine)) && include) while (startLine > 0 && lineWhite(b, start(startLine - 1))) startLine--
        return Obj(start(startLine), b.lineEnd(start(endLine)), false, linewise = true)
    }

    private fun quote(b: VimBuf, c: Int, around: Boolean, q: Char, count: Int): Pair<Int, Int>? {
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
                if (!around) return if (count > 1) open to (close + 1) else (open + 1) to close // 2i" includes the quotes, not the white space
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

    private fun inIndent(b: VimBuf, p: Int): Boolean {
        var n = 0
        val ls = b.lineStart(p)
        while (ls + n < b.length && (b.text[ls + n] == ' ' || b.text[ls + n] == '\t')) n++
        return n >= (p - ls) + 1
    }

    /** Vim's current_block: the innermost [count]th pair around the cursor; without an enclosing pair, the next pair that opens after it. */
    private fun block(b: VimBuf, from: Int, include: Boolean, p: Pair<Char, Char>, count0: Int): Pair<Int, Int>? {
        val open = p.first
        val close = p.second
        var cur = from
        if (open == '{') {
            val c = VimMotions.Cur(b, cur, false)
            while (inIndent(b, c.pos)) if (c.inc() != 0) break
            cur = c.pos
        }
        if (cur < b.length && cur < b.lineEnd(cur) && b.text[cur] == open) cur = b.next(cur) // on the opening bracket: just after it
        var start = -1
        var count = count0
        var probe = cur
        var found = false
        var missing = false
        while (count > 0) {
            val m = VimMotions.findMatching(b, probe, open, close, fwd = false, wantEscaped = false)
            if (m < 0) { missing = found; break } // fewer enclosing pairs than the count: nothing (Vim fails)
            start = m
            probe = m
            found = true
            count--
        }
        if (missing) return null
        if (!found) {
            // not inside a pair: Vim looks forward for an opening bracket, skipping the pairs that close before it (a ")" counts one against the next "(")
            count = count0
            var at = from
            var ahead = -1
            while (count > 0) {
                var depth = 0
                var i = at
                ahead = -1
                while (true) {
                    i++
                    if (i >= b.length) break
                    val ch = b.text[i]
                    if (isEscaped(b, i)) continue
                    if (ch == close) depth++
                    else if (ch == open) { if (depth == 0) { ahead = i; break } else depth-- }
                }
                if (ahead < 0) return null
                at = ahead
                count--
            }
            start = ahead
        }
        val end = VimMotions.findMatching(b, start, open, close, fwd = true, wantEscaped = false)
        if (end < 0) return null
        if (include) return start to (end + 1)
        // inner: just after the opening bracket, up to before the closing one; a closing bracket that has only indent before it is not
        // part of it, and the line break before that indent is
        val s = VimMotions.Cur(b, start, false).also { it.incl() }.pos
        val e = VimMotions.Cur(b, end, false)
        var sol = end == b.lineStart(end)
        e.decl()
        while (inIndent(b, e.pos)) {
            sol = true
            if (e.decl() != 0) break
        }
        val ep = e.pos
        return when {
            sol -> s to VimMotions.Cur(b, ep, false).also { it.incl() }.pos
            s <= ep -> s to (if (ep < b.lineEnd(ep)) b.next(ep) else ep)
            else -> s to s // nothing between the brackets
        }
    }

    private fun isEscaped(b: VimBuf, i: Int): Boolean {
        var n = 0
        var k = i - 1
        while (k >= 0 && b.text[k] == '\\') { n++; k-- }
        return n % 2 == 1
    }
}
