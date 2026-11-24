// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** How an operator takes the range of a motion: up to the target, through the character at the target, or whole lines. */
internal enum class MType { EXCL, INCL, LINE }

/**
 * The result of a motion from a cursor: the new cursor [pos], how an operator reads the range, and [cancels] when an operator
 * pending with this motion does nothing at all (Vim beeps). A pure cursor motion that [cancels] still moves to [pos] (Vim moves
 * as far as it can, "5w" with two words left goes to the last one). [jump] marks a motion that sets the context mark (the position
 * before the jump, for `` and ''), [big] a motion whose delete goes to register 1 even inside one line (Vim's use_reg_one).
 */
internal class Mv(val pos: Int, val type: MType, val cancels: Boolean = false, val jump: Boolean = false, val big: Boolean = false, val notice: VimNotice? = null)

/**
 * The motions of the Vim layer, ported from Vim's own motion code (cursor_up/down, nv_left/right, fwd_word, bck_word, end_word,
 * searchc) on [VimBuf]. The expected results of the tests are Vim's (tools/gen-vim-golden.py).
 */
internal object VimMotions {
    /** A cursor that steps like Vim's inc/dec: it can stand on the end of a line and it crosses line breaks. */
    internal class Cur(val b: VimBuf, var pos: Int, val big: Boolean) {
        /** 0 moved within the line, 2 moved onto the end of the line, 1 moved to the next line, -1 at the end of the text. */
        fun inc(): Int {
            val end = b.lineEnd(pos)
            if (pos < end) {
                pos = b.next(pos)
                return if (pos < end) 0 else 2
            }
            if (end < b.length) {
                pos = end + 1
                return 1
            }
            return -1
        }

        /** 0 moved within the line, 1 moved to the end of the previous line, -1 at the start of the text. */
        fun dec(): Int {
            val start = b.lineStart(pos)
            if (pos > start) {
                pos = b.prev(pos)
                return 0
            }
            if (start > 0) {
                pos = start - 1
                return 1
            }
            return -1
        }

        /** inc() that does not stand on the end of a line: -1 at the end of the text. */
        fun incl(): Int {
            var r = inc()
            if (r >= 1 && pos != b.lineStart(pos)) r = inc()
            return r
        }

        /** dec() that does not stand on the end of a line (an empty line is a place). */
        fun decl(): Int {
            var r = dec()
            if (r == 1 && pos != b.lineStart(pos)) r = dec()
            return r
        }

        fun oneLeft(): Boolean {
            if (pos <= b.lineStart(pos)) return false
            pos = b.prev(pos)
            return true
        }

        /** Back to the start of the word or of the white space the cursor is in, within the line. */
        fun backInLine() {
            val sclass = cls()
            while (pos > b.lineStart(pos)) {
                pos = b.prev(pos)
                if (cls() != sclass) { pos = b.next(pos); break }
            }
        }

        fun cls(): Int = if (pos >= b.lineEnd(pos)) 0 else VimBuf.classOf(b.codePointAt(pos), big)

        fun onEmptyLine() = pos == b.lineStart(pos) && b.lineEnd(pos) == pos

        /** True when the walk hit the end of the text. */
        fun skip(cls: Int, forward: Boolean): Boolean {
            while (cls() == cls) if ((if (forward) inc() else dec()) == -1) return true
            return false
        }
    }

    internal fun fwdWord(c: Cur, count: Int, eol: Boolean): Boolean {
        var left = count
        while (--left >= 0) {
            val sclass = c.cls()
            val lastLine = c.b.isLastLine(c.pos)
            var i = c.inc()
            if (i == -1 || (i >= 1 && lastLine)) return false
            if (i >= 1 && eol && left == 0) return true
            if (sclass != 0) {
                while (c.cls() == sclass) {
                    i = c.inc()
                    if (i == -1 || (i >= 1 && eol && left == 0)) return true
                }
            }
            while (c.cls() == 0) {
                if (c.onEmptyLine()) break
                i = c.inc()
                if (i == -1 || (i >= 1 && eol && left == 0)) return true
            }
        }
        return true
    }

    private fun bckWord(c: Cur, count: Int): Boolean {
        var left = count
        var stop = false
        while (--left >= 0) {
            val sclass = c.cls()
            if (c.dec() == -1) return false
            var finished = false
            if (!stop || sclass == c.cls() || sclass == 0) {
                while (c.cls() == 0) {
                    if (c.onEmptyLine()) { finished = true; break }
                    if (c.dec() == -1) return true
                }
                if (!finished && c.skip(c.cls(), forward = false)) return true
            }
            if (!finished) c.inc()
            stop = false
        }
        return true
    }

    internal fun endWord(c: Cur, count: Int, stopFirst: Boolean, empty: Boolean): Boolean {
        var left = count
        var stop = stopFirst
        while (--left >= 0) {
            val sclass = c.cls()
            if (c.inc() == -1) return false
            var finished = false
            if (c.cls() == sclass && sclass != 0) {
                if (c.skip(sclass, forward = true)) return false
            } else if (!stop || sclass == 0) {
                while (c.cls() == 0) {
                    if (empty && c.onEmptyLine()) { finished = true; break }
                    if (c.inc() == -1) return false
                }
                if (!finished && c.skip(c.cls(), forward = true)) return false
            }
            if (!finished) c.dec()
            stop = false
        }
        return true
    }

    /**
     * w, W, e, E, and the c operator ("cw" is "ce" on a word character). [op] is true when an operator is pending, [visual] in
     * Visual mode (the cursor may then stay on the end of the line).
     */
    fun word(b: VimBuf, from: Int, count: Int, big: Boolean, end: Boolean, op: Boolean, change: Boolean, visual: Boolean): Mv {
        var wordEnd = end
        var inclusive = end
        var flag = false
        if (!end && change) {
            val atEol = from >= b.lineEnd(from)
            if (!atEol && !b.isBlankAt(from)) { // "cw" on a word acts like "ce"; on a blank it changes the blanks like "dw"
                inclusive = true
                wordEnd = true
                flag = true
            }
        }
        val c = Cur(b, from, big)
        val ok = if (wordEnd) endWord(c, count, flag, empty = false) else fwdWord(c, count, eol = op)
        var pos = c.pos
        if (pos > from) {
            // the cursor does not stay on the end of a line (adjust_cursor); an operator then takes the last character
            if (pos > 0 && pos >= b.lineEnd(pos) && pos > b.lineStart(pos) && !visual) {
                pos = b.prev(pos)
                if (op) inclusive = true
            }
        }
        return Mv(pos, if (inclusive) MType.INCL else MType.EXCL, cancels = !ok && !op)
    }

    fun back(b: VimBuf, from: Int, count: Int, big: Boolean): Mv {
        val c = Cur(b, from, big)
        val ok = bckWord(c, count)
        return Mv(c.pos, MType.EXCL, cancels = !ok)
    }

    /** h: stops at the start of the line; it only fails (a beep) when it cannot move at all and no operator is pending. */
    fun left(b: VimBuf, from: Int, count: Int, op: Boolean): Mv {
        var pos = from
        for (i in 0 until count) {
            if (pos <= b.lineStart(pos)) break
            pos = b.prev(pos)
        }
        return Mv(pos, MType.EXCL, cancels = pos == from && !op)
    }

    /** l: in Normal mode it stops on the last character; an operator pending then takes that character too. In Visual it may reach the end of the line. */
    fun right(b: VimBuf, from: Int, count: Int, op: Boolean, visual: Boolean): Mv {
        var pos = from
        var failed = false
        var moved = false
        for (i in 0 until count) {
            val end = b.lineEnd(pos)
            val blocked = if (visual) pos >= end else (pos >= end || b.next(pos) >= end)
            if (blocked) { failed = true; break }
            pos = b.next(pos)
            moved = true
        }
        if (failed) {
            if (!op && !moved) return Mv(pos, MType.EXCL, cancels = true)
            if (op && !b.isEmptyLine(pos)) return Mv(pos, MType.INCL)
        }
        return Mv(pos, MType.EXCL)
    }

    /** j and k as lines: [want] is the wanted column (null: the cursor's), [Int.MAX_VALUE] after "$". */
    fun vertical(b: VimBuf, from: Int, count: Int, down: Boolean, want: Int?, visual: Boolean): Mv {
        val line = b.lineNumber(from)
        val last = b.lineCount() - 1
        if (if (down) line >= last else line <= 0) return Mv(from, MType.LINE, cancels = true)
        val target = if (down) minOf(line + count, last) else maxOf(line - count, 0)
        return Mv(columnPos(b, b.startOfLine(target), want ?: b.column(from), visual), MType.LINE)
    }

    /** The position on the line of [lineAt] for the wanted column. */
    fun columnPos(b: VimBuf, lineAt: Int, col: Int, visual: Boolean): Int {
        val end = b.lineEnd(lineAt)
        if (col == Int.MAX_VALUE) return if (visual) end else b.lastChar(lineAt)
        val p = b.atColumn(lineAt, col)
        return if (p >= end) (if (visual) end else b.lastChar(lineAt)) else p
    }

    fun lineEnd(b: VimBuf, from: Int, count: Int, visual: Boolean): Mv {
        val line = b.lineNumber(from)
        val last = b.lineCount() - 1
        if (count > 1 && line >= last) return Mv(from, MType.INCL, cancels = true)
        val at = b.startOfLine(minOf(line + count - 1, last))
        return Mv(if (visual) b.lineEnd(at) else b.lastChar(at), MType.INCL)
    }

    fun lineStart(b: VimBuf, from: Int) = Mv(b.lineStart(from), MType.EXCL)

    fun firstNonBlank(b: VimBuf, from: Int) = Mv(b.firstNonBlank(from), MType.EXCL)

    /** G and gg: [line] is 0-based and clamped; the cursor goes to the first non-blank of the line. */
    fun gotoLine(b: VimBuf, line: Int): Mv = Mv(b.firstNonBlank(b.startOfLine(line.coerceIn(0, b.lineCount() - 1))), MType.LINE)

    /** f, F, t, T ([type] is the command character). [isRepeat] is a ";" or ",": a "t" that stands before its character goes on to the next one. */
    fun find(b: VimBuf, from: Int, ch: Int, type: Char, count: Int, isRepeat: Boolean): Mv {
        val forward = type == 'f' || type == 't'
        val till = type == 't' || type == 'T'
        val end = b.lineEnd(from)
        var pos = from
        var stop = !(isRepeat && count == 1 && till)
        for (i in 0 until count) {
            while (true) {
                val n = if (forward) b.next(pos) else b.prev(pos)
                if (n == pos || (forward && n >= end)) return Mv(from, MType.EXCL, cancels = true)
                pos = n
                if (b.codePointAt(pos) == ch && stop) break
                stop = true
            }
        }
        if (till) pos = if (forward) b.prev(pos) else b.next(pos)
        return Mv(pos, if (forward) MType.INCL else MType.EXCL)
    }

    // ---- jumps: %, paragraphs, sentences -------------------------------------------------------------------------------------

    private const val BRACKETS = "(){}[]"

    private fun escaped(b: VimBuf, i: Int): Boolean {
        var n = 0
        var k = i - 1
        while (k >= 0 && b.text[k] == '\\') { n++; k-- }
        return n % 2 == 1
    }

    /**
     * The bracket that matches the one at [open] ([fwd]: it is an opening one), by nesting; a bracket with a backslash before it only matches
     * another that has one (the start bracket decides), as Vim does without "M" in 'cpoptions'. Quotes are not looked at. -1: none.
     */
    fun findMatching(b: VimBuf, start: Int, open: Char, close: Char, fwd: Boolean, wantEscaped: Boolean): Int {
        var depth = 0
        var i = start
        while (true) {
            i = if (fwd) i + 1 else i - 1
            if (i < 0 || i >= b.length) return -1
            val ch = b.text[i]
            if (ch != open && ch != close) continue
            if (escaped(b, i) != wantEscaped) continue
            if (ch == (if (fwd) open else close)) depth++
            else if (depth == 0) return i
            else depth--
        }
    }

    /** `%`: the first of ( ) { } [ ] at or after the cursor in its line, and its match. */
    fun matchPair(b: VimBuf, from: Int): Mv {
        val end = b.lineEnd(from)
        var i = from
        while (i < end && BRACKETS.indexOf(b.text[i]) < 0) i++
        if (i >= end) return Mv(from, MType.INCL, cancels = true, jump = true, big = true)
        val ch = b.text[i]
        val k = BRACKETS.indexOf(ch)
        val opening = k % 2 == 0
        val other = BRACKETS[if (opening) k + 1 else k - 1]
        val at = findMatching(b, i, if (opening) ch else other, if (opening) other else ch, opening, escaped(b, i))
        return if (at < 0) Mv(from, MType.INCL, cancels = true, jump = true, big = true) else Mv(at, MType.INCL, jump = true, big = true)
    }

    private fun startsParagraph(b: VimBuf, ls: Int): Boolean = b.lineEnd(ls) == ls || b.text[ls] == '\u000c'

    /** `{` and `}`: to the next (or previous) empty line, or the end of the text. */
    fun paragraph(b: VimBuf, from: Int, count: Int, forward: Boolean): Mv {
        var ls = b.lineStart(from)
        var left = count
        while (left > 0) {
            left--
            var didSkip = false
            var first = true
            while (true) {
                if (b.lineEnd(ls) > ls) didSkip = true
                if (!first && didSkip && startsParagraph(b, ls)) break
                val nl = if (forward) (if (b.lineEnd(ls) >= b.length) -1 else b.lineEnd(ls) + 1) else (if (ls == 0) -1 else b.lineStart(ls - 1))
                if (nl < 0) {
                    if (left > 0) return Mv(from, MType.EXCL, cancels = true, jump = true, big = true)
                    break
                }
                ls = nl
                first = false
            }
        }
        if (forward && b.lineEnd(ls) >= b.length) {
            val end = b.lineEnd(ls)
            if (end != ls) return Mv(b.prev(end), MType.INCL, jump = true, big = true)
        }
        return Mv(ls, MType.EXCL, jump = true, big = true)
    }

    /** `(` and `)`: Vim's findsent. */
    fun sentence(b: VimBuf, from: Int, count: Int, forward: Boolean): Mv {
        val c = Cur(b, from, false)
        fun ch(p: Int): Char = if (p >= b.length || p >= b.lineEnd(p)) '\u0000' else b.text[p]
        fun step(): Int = if (forward) c.incl() else c.decl()
        fun white(x: Char) = x == ' ' || x == '\t'
        var left = count
        while (left > 0) {
            left--
            var noskip = false
            var found = false
            if (ch(c.pos) == '\u0000') {
                do { if (step() == -1) break } while (ch(c.pos) == '\u0000')
                if (forward) found = true
            } else if (forward && c.pos == b.lineStart(c.pos) && startsParagraph(b, c.pos)) {
                if (b.isLastLine(c.pos)) return Mv(from, MType.EXCL, cancels = true, jump = true, big = true)
                c.pos = b.lineEnd(c.pos) + 1
                found = true
            } else if (!forward) c.decl()
            if (!found) {
                var foundDot = false
                while (true) {
                    val x = ch(c.pos)
                    if (!(white(x) || ".!?)]\"'".indexOf(x) >= 0 && x != '\u0000')) break
                    val t = Cur(b, c.pos, false)
                    if (t.decl() == -1 || (b.isEmptyLine(t.pos) && forward)) break
                    if (foundDot) break
                    if (".!?".indexOf(x) >= 0) foundDot = true
                    if (")]\"'".indexOf(x) >= 0 && ".!?)]\"'".indexOf(ch(t.pos)).let { it < 0 || ch(t.pos) == '\u0000' }) break
                    c.decl()
                }
                val startLine = b.lineStart(c.pos)
                while (true) {
                    val x = ch(c.pos)
                    if (x == '\u0000' || (c.pos == b.lineStart(c.pos) && startsParagraph(b, c.pos))) {
                        if (!forward && b.lineStart(c.pos) != startLine) c.pos = b.lineEnd(c.pos) + 1
                        break
                    }
                    if (x == '.' || x == '!' || x == '?') {
                        val t = Cur(b, c.pos, false)
                        var y: Char
                        var r: Int
                        do {
                            r = t.inc()
                            y = ch(t.pos)
                        } while (r != -1 && ")]\"'".indexOf(y) >= 0 && y != '\u0000')
                        if (r == -1 || white(y) || y == '\u0000') {
                            c.pos = t.pos
                            if (ch(c.pos) == '\u0000') c.inc()
                            break
                        }
                    }
                    if (step() == -1) {
                        if (left > 0) return Mv(from, MType.EXCL, cancels = true, jump = true, big = true)
                        noskip = true
                        break
                    }
                }
            }
            while (!noskip && white(ch(c.pos))) if (c.incl() == -1) break
        }
        return Mv(c.pos, MType.EXCL, jump = true, big = true)
    }
}
