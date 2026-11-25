// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * Search in the field text: finding the next or previous match of a pattern line by line (wrapping around the ends), the search offsets
 * (`/foo/e`, `/foo/s+1`, `/foo/+2`), and the pattern of `*` and `#`. Every search runs under a [VimRegex.Budget]: when it is spent the answer
 * is [Found.Limit] and nothing is changed or remembered.
 */
internal object VimSearch {
    sealed interface Found {
        class At(val start: Int, val end: Int, val wrapped: Boolean) : Found
        data object None : Found
        /** The step or time budget ran out. */
        data object Limit : Found
    }

    private fun step(text: String, p: Int): Int = if (p >= text.length) text.length + 1 else p + Character.charCount(text.codePointAt(p))

    /**
     * The [count]th match of [re] after (or, [forward] false, before) the position [from], searching line by line and wrapping at the ends of
     * the text. A match found by the forward search starts after [from]; by the backward search, before it. With [endBased] (a search with an "e"
     * offset) it is the end of the match that has to be after (or before) [from]. Like Vim with the default 'cpoptions', the matches of the
     * cursor line are the ones that follow each other without overlapping, from the start of the line.
     */
    fun find(b: VimBuf, re: VimRegex, from: Int, forward: Boolean, count: Int, budget: VimRegex.Budget, endBased: Boolean = false): Found {
        var at = from
        var wrapped = false
        var result: Found.At? = null
        for (i in 0 until count) {
            val r = if (forward) findForward(b, re, at, budget, endBased) else findBackward(b, re, at, budget, endBased)
            if (r !is Found.At) return r
            wrapped = wrapped || r.wrapped
            result = Found.At(r.start, r.end, wrapped)
            at = if (endBased) (if (r.end > r.start) b.prevAny(r.end) else r.start) else r.start
        }
        return result ?: Found.None
    }

    private fun matchIn(re: VimRegex, line: String, from: Int, budget: VimRegex.Budget): VimRegex.Match? = re.find(line, from, budget)

    /** Where the next match is looked for after [m]: at its end, one character further when it is empty. */
    private fun after(line: String, m: VimRegex.Match): Int = if (m.end > m.start) m.end else step(line, m.end)

    private fun lastChar(line: String, m: VimRegex.Match): Int = if (m.end > m.start) VimBuf(line).prev(m.end) else m.start - 1

    private fun findForward(b: VimBuf, re: VimRegex, from: Int, budget: VimRegex.Budget, endBased: Boolean): Found {
        val text = b.text
        val ls0 = b.lineStart(from)
        val le0 = b.lineEnd(from)
        val line0 = text.substring(ls0, le0)
        val col0 = from - ls0
        var p = 0
        while (p <= line0.length) {
            val m = matchIn(re, line0, p, budget) ?: break
            val ok = if (endBased) lastChar(line0, m) > col0 else (m.start > col0 || (m.start == line0.length && line0.length - 1 > col0 && false))
            if (ok) return Found.At(ls0 + m.start, ls0 + m.end, false)
            p = after(line0, m)
        }
        if (budget.exhausted) return Found.Limit
        var ls = if (le0 < text.length) le0 + 1 else -1
        while (ls >= 0) {
            val le = b.lineEnd(ls)
            val m = matchIn(re, text.substring(ls, le), 0, budget)
            if (m != null) return Found.At(ls + m.start, ls + m.end, false)
            if (budget.exhausted) return Found.Limit
            ls = if (le < text.length) le + 1 else -1
        }
        // wrap: from the top down to the line of the cursor, where a match may start at or before the cursor
        ls = 0
        while (ls <= ls0) {
            val le = b.lineEnd(ls)
            val m = matchIn(re, text.substring(ls, le), 0, budget)
            if (m != null && (ls < ls0 || (if (endBased) lastChar(line0, m) <= col0 else m.start <= col0))) return Found.At(ls + m.start, ls + m.end, true)
            if (budget.exhausted) return Found.Limit
            if (le >= text.length) break
            ls = le + 1
        }
        return Found.None
    }

    /** The last of the matches of [line] that follow each other (no overlap) and that start before [limit] (end before it when [endBased]). */
    private fun lastIn(re: VimRegex, line: String, limit: Int, budget: VimRegex.Budget, endBased: Boolean): VimRegex.Match? {
        var last: VimRegex.Match? = null
        var p = 0
        while (p <= line.length) {
            val m = matchIn(re, line, p, budget) ?: break
            if (if (endBased) lastChar(line, m) >= limit else m.start >= limit) break
            last = m
            p = after(line, m)
        }
        return last
    }

    private fun findBackward(b: VimBuf, re: VimRegex, from: Int, budget: VimRegex.Budget, endBased: Boolean): Found {
        val text = b.text
        val ls0 = b.lineStart(from)
        val line0 = text.substring(ls0, b.lineEnd(from))
        val col0 = from - ls0
        val m0 = lastIn(re, line0, col0, budget, endBased)
        if (m0 != null) return Found.At(ls0 + m0.start, ls0 + m0.end, false)
        if (budget.exhausted) return Found.Limit
        var ls = ls0
        while (ls > 0) {
            val prevStart = b.lineStart(ls - 1)
            val line = text.substring(prevStart, ls - 1)
            val m = lastIn(re, line, Int.MAX_VALUE, budget, false)
            if (m != null) return Found.At(prevStart + m.start, prevStart + m.end, false)
            if (budget.exhausted) return Found.Limit
            ls = prevStart
        }
        // wrap: from the last line up to the line of the cursor
        ls = b.lineStart(text.length)
        while (true) {
            val le = b.lineEnd(ls)
            val line = text.substring(ls, le)
            val m = lastIn(re, line, Int.MAX_VALUE, budget, false)
            if (m != null && (ls > ls0 || (if (endBased) lastChar(line, m) >= col0 else m.start >= col0))) return Found.At(ls + m.start, ls + m.end, true)
            if (budget.exhausted) return Found.Limit
            if (ls <= ls0) break
            ls = b.lineStart(ls - 1)
        }
        return Found.None
    }

    /** The first line at or after [lineFrom] (a line start offset, forward) or at/before it whose text matches. -1: none. For ex ranges. */
    fun lineMatches(re: VimRegex, line: String, budget: VimRegex.Budget): Boolean = re.find(line, 0, budget) != null

    /** A search offset: what the search command does after the match. */
    class Offset(val kind: Char, val amount: Int) { // kind: 'l' lines, 's' start, 'e' end, ' ' none

        companion object {
            val NONE = Offset(' ', 0)
        }
    }

    /** Parses the part after the closing delimiter ("e", "e+1", "s-2", "+3", "b"). Null when it is not an offset. */
    fun parseOffset(s: String): Offset? {
        if (s.isEmpty()) return Offset.NONE
        var i = 0
        var kind = 'l'
        if (s[0] == 'e' || s[0] == 's' || s[0] == 'b') { kind = if (s[0] == 'b') 's' else s[0]; i = 1 }
        if (i == s.length) return Offset(kind, 0)
        var sign = 1
        if (s[i] == '+' || s[i] == '-') { if (s[i] == '-') sign = -1; i++ }
        var n = 0
        var any = false
        while (i < s.length && s[i] in '0'..'9') { n = minOf(n * 10 + (s[i] - '0'), 100000); any = true; i++ }
        if (i != s.length) return null
        if (!any) n = if (i > 0 && (s.last() == '+' || s.last() == '-')) 1 else 0
        return Offset(kind, sign * n)
    }

    /**
     * Splits a typed search ("foo/e+1", "foo") at the first delimiter that is not escaped; [delim] is '/' or '?'. Returns the pattern, and the
     * rest after the delimiter (null when there is no delimiter).
     */
    fun splitPattern(s: String, delim: Char): Pair<String, String?> {
        var i = 0
        var inClass = false
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { i += 2; continue }
            if (c == '[' && !inClass) { if (s.indexOf(']', i + 1) > 0) inClass = true }
            else if (c == ']' && inClass) inClass = false
            else if (c == delim && !inClass) return s.substring(0, i) to s.substring(i + 1)
            i++
        }
        return s to null
    }

    /** A backslash before the delimiter of the search is dropped ("?fo\?o" looks for "fo?o"), as Vim stores the pattern. */
    fun unescapeDelimiter(p: String, delim: Char): String {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) {
                if (p[i + 1] == delim) sb.append(delim) else sb.append(c).append(p[i + 1])
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** The pattern for `*` / `#` at [cursor]: the keyword under or after the cursor in its line (else the non-blank run), with \< \> when [whole]. Null: none. */
    fun wordPattern(b: VimBuf, cursor: Int, whole: Boolean): Pair<String, Int>? {
        val ls = b.lineStart(cursor)
        val le = b.lineEnd(cursor)
        var i = cursor
        fun isWordAt(p: Int) = VimRegex.isWord(b.codePointAt(p))
        // the keyword at the cursor, or the first one after it
        var p = i
        while (p < le && !isWordAt(p)) p = b.next(p)
        var keyword = true
        if (p >= le) { // no keyword after the cursor: the run of other non-blank characters
            keyword = false
            p = i
            while (p < le && b.isBlankAt(p)) p = b.next(p)
            if (p >= le) return null
        }
        i = p
        // the start of the run that contains p
        val cls = { q: Int -> if (keyword) isWordAt(q) else !b.isBlankAt(q) && !isWordAt(q) }
        var s = i
        while (s > ls && cls(b.prev(s))) s = b.prev(s)
        var e = i
        while (e < le && cls(e)) e = b.next(e)
        val word = b.text.substring(s, e)
        val sb = StringBuilder()
        if (whole && keyword) sb.append("\\<")
        for (ch in word) {
            if (ch == '\\' || ch == '/' || ch == '.' || ch == '*' || ch == '$' || ch == '^' || ch == '~' || ch == '[' || ch == ']') sb.append('\\')
            sb.append(ch)
        }
        if (whole && keyword) sb.append("\\>")
        return sb.toString() to s
    }
}
