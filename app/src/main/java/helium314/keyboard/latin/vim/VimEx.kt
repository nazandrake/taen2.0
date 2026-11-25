// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** The result of one ex command line: the whole new [text] and the cursor, or a [notice]; [undo] is 1 for :undo, 2 for :redo. */
internal class ExResult(
    val text: String,
    val cursor: Int,
    val notice: VimNotice? = null,
    val message: String? = null,
    val undo: Int = 0,
    val newSearch: String? = null,
    val changed: Boolean = false,
)

/**
 * The ex commands of the keyboard's command line, run on the text of the field as lines: ranges (. $ % n 'x /pat/ ?pat? +n -n, with , and ;),
 * :s with & and \1 to \9, :&&, :d, :y, :pu, :m, :t and :co, :j, :> and :<, :normal, :g and :v with these commands, :reg, :marks, :undo and
 * :redo, and a notice for what a keyboard cannot do (:w, :q, :x, :wq). The whole line is run on a copy; the engine applies the result as one
 * text change, or nothing (an error or a spent budget changes nothing). Nothing here reads or writes the field.
 *
 * Fail closed: a command line is parsed to its end, and what no part of the syntax takes (trailing characters, an unknown flag or argument, an
 * expression register, a range where none is allowed) is an error, never ignored. Bounds: every command, line and iteration is a step of the
 * one budget of the command ([Host.charge]), and the text never gets longer than [Host.maxChars]: the size is checked before a larger string is built.
 */
internal class VimEx(private val host: Host) {
    interface Host {
        /** The memory of the layer (registers, marks, the last substitute): every write in it asks the capture rule, so nothing here does. */
        val memory: VimMemory
        val budget: VimRegex.Budget
        /** Counts [steps] steps of this command line against its budget; false when the budget is spent (the line stops). */
        fun charge(steps: Int): Boolean
        /** Counts [chars] characters of a text copy that is about to be built; false when the copies of this command are at their limit (the line stops, nothing is built). */
        fun copy(chars: Long): Boolean
        /** The most characters the text may have; a command that would make it longer stops. */
        val maxChars: Int
        /** The 0-based line of mark [name] (a to z, < > ' [ ]), or null. */
        fun markLine(name: Char): Int?
        fun setMark(name: Char, line: Int)
        /** Runs Normal mode [keys] on [text] with the cursor at [caret]; the new text and cursor, or null when it cannot (a failure or a limit). */
        fun normal(text: String, caret: Int, keys: String): Pair<String, Int>?
        /** The register's content for :put; "+ and "* read the system clipboard. */
        fun register(name: Char): Reg?
        fun registerListing(): String
        fun markListing(): String
        val indentWithTab: Boolean
    }

    private class Line(var s: String, var tag: Boolean = false)

    /** What stops one ex command line (an invalid range, a pattern that does not match...): it carries a notice (an enum), never text, and is caught in [run]. */
    private class Stop(val notice: VimNotice) : Exception()

    private var lines = ArrayList<Line>()
    private var cur = 0
    private var curCol = -1 // -1: first non-blank
    private var changed = false
    private var undo = 0
    private var message: String? = null
    private var newSearch: String? = null
    private var inGlobal = false
    private var cursorKept = false

    /**
     * The characters the lines hold, counted with one line break per line (so one more than the text). Every change of [lines] goes through
     * [setLine], [addLine], [removeLines] or [replaceAll], which keep this number, so the ceiling is checked without building the text.
     */
    private var size = 0L

    private fun spend(steps: Int = 1) { if (!host.charge(steps)) throw Stop(VimNotice.PLAY_LIMIT) }

    /** A text copy of [chars] characters is about to be built (a join, a split): it counts against the copies of the command, and the command stops before it. */
    private fun copying(chars: Int) { if (!host.copy(chars.toLong())) throw Stop(VimNotice.PLAY_LIMIT) }

    /** A change that adds [chars] characters to the text must still fit: checked before the larger string is built. */
    private fun guardGrow(chars: Long) { if (size - 1 + chars > host.maxChars) throw Stop(VimNotice.PLAY_LIMIT) }

    private fun setLine(l: Int, s: String) {
        size += s.length - lines[l].s.length
        lines[l].s = s
        if (size - 1 > host.maxChars) throw Stop(VimNotice.PLAY_LIMIT)
    }

    private fun addLine(at: Int, line: Line) {
        lines.add(at, line)
        size += line.s.length + 1
        if (size - 1 > host.maxChars) throw Stop(VimNotice.PLAY_LIMIT)
    }

    private fun removeLines(from: Int, count: Int) {
        val gone = lines.subList(from, from + count)
        for (l in gone) size -= l.s.length + 1
        gone.clear()
    }

    /** A command that fails after it moved the cursor to [line] (0-based, the column stays): the failure keeps that move, as in Vim. */
    private var stopLine = -1
    private var stopCol0 = false
    private var stopClamp = false

    /**
     * [col0] puts the cursor in column 0 (the lines of :global); [clamp] cuts the column to the last character (:put); else the column stays when
     * the line has it, and is 0 when the line is shorter (:join).
     */
    private fun stopAt(line: Int, col0: Boolean = false, clamp: Boolean = false) {
        stopLine = line.coerceIn(0, lines.size - 1)
        stopCol0 = col0
        stopClamp = clamp
    }

    /** Runs the command line [cmdLine] on [text] with the cursor at offset [cursor]. */
    fun run(text: String, cursor: Int, cmdLine: String): ExResult {
        if (!host.copy(text.length.toLong())) return ExResult(text, cursor, VimNotice.PLAY_LIMIT) // the line list below is a copy of the text
        lines = ArrayList()
        for (s in text.split('\n')) lines.add(Line(s))
        size = text.length + 1L
        cur = lineIndexOf(text, cursor)
        curCol = cursor - lineStartOf(text, cur)
        val origLine = cur
        val origCol = curCol
        changed = false
        undo = 0
        message = null
        newSearch = null
        inGlobal = false
        cursorKept = true
        stopLine = -1
        try {
            execLine(cmdLine)
        } catch (s: Stop) {
            val at = if (stopLine >= 0 && !changed) {
                val start = lineStartOf(text, stopLine)
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                if (stopCol0 || origCol < 0) start else if (start + origCol >= end) (if (stopClamp) maxOf(start, end - 1) else start) else start + origCol
            } else cursor
            return ExResult(text, at, s.notice, newSearch = newSearch)
        }
        if (undo != 0) return ExResult(text, cursor, undo = undo)
        val out = join()
        if (!changed && out == text) {
            return ExResult(text, if (cursorKept && cur == origLine && curCol == origCol) cursor else offsetOf(cur), message = message, newSearch = newSearch)
        }
        return ExResult(out, offsetOf(cur), message = message, newSearch = newSearch, changed = true)
    }

    private fun join(): String {
        val sb = StringBuilder()
        for ((i, l) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(l.s)
        }
        return sb.toString()
    }

    private fun lineIndexOf(text: String, offset: Int): Int = VimLines.lineOf(text, offset)
    private fun lineStartOf(text: String, line: Int): Int = VimLines.lineStartOf(text, line)

    /** The text offset of the cursor: line [cur], and the column [curCol] (first non-blank when -1). */
    private fun offsetOf(line: Int): Int {
        val i = line.coerceIn(0, lines.size - 1)
        var start = 0
        for (k in 0 until i) start += lines[k].s.length + 1
        val s = lines[i].s
        val col = if (curCol < 0) VimOps.indentLength(s).let { if (it >= s.length && s.isNotEmpty()) s.length - 1 else it } else minOf(curCol, maxOf(0, s.length - 1))
        return start + col
    }

    private fun moved(line: Int, col: Int = -1) {
        cur = line.coerceIn(0, lines.size - 1)
        curCol = col
        cursorKept = false
    }

    // ---- parsing --------------------------------------------------------------------------------------------------------------

    private class P(val s: String) {
        var i = 0
        fun more() = i < s.length
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun skipBlanks() { while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++ }
        fun rest(): String = s.substring(i)
    }

    private fun execLine(cmdLine: String) {
        val p = P(cmdLine)
        while (p.more() && (p.peek() == ':' || p.peek() == ' ' || p.peek() == '\t')) p.i++
        execCommand(p)
    }

    private class Range(val line1: Int, val line2: Int, val count: Int) // 1-based lines; count: how many addresses were given

    private fun parseNumber(p: P): Int {
        var n = 0
        while (p.peek() in '0'..'9') { n = minOf(n * 10 + (p.peek() - '0'), 10_000_000); p.i++ }
        return n
    }

    /** One address (1-based line; 0 means before the first line). Null when there is none. [from] is the line a relative address counts from. */
    private fun parseAddress(p: P, from: Int): Int? {
        p.skipBlanks()
        var line: Int? = null
        val c = p.peek()
        val patternBase = c == '/' || c == '?'
        when {
            c in '0'..'9' -> line = parseNumber(p)
            c == '.' -> { p.i++; line = from }
            c == '$' -> { p.i++; line = lines.size }
            c == '\'' -> {
                p.i++
                val name = p.peek()
                if (!p.more()) throw Stop(VimNotice.EX_ERROR)
                p.i++
                line = (host.markLine(name) ?: throw Stop(VimNotice.EX_ERROR)) + 1
            }
            c == '/' || c == '?' -> {
                p.i++
                val sp = VimSearch.splitPattern(p.rest(), c)
                p.i += sp.first.length + (if (sp.second != null) 1 else 0)
                val pat = if (sp.first.isEmpty()) (currentPattern() ?: throw Stop(VimNotice.NO_PATTERN)) else sp.first
                newSearch = pat
                line = findLine(pat, from, c == '/')
            }
        }
        // offsets: +n -n (a lone sign is 1), also when there was no base (relative to [from])
        while (true) {
            p.skipBlanks()
            val s = p.peek()
            if (s != '+' && s != '-' && !(s in '0'..'9' && line != null)) break
            val sign = if (s == '-') -1 else 1
            if (s == '+' || s == '-') p.i++
            val hasDigits = p.peek() in '0'..'9'
            val n = if (hasDigits) parseNumber(p) else 1
            if (line == null) line = from
            line += sign * n
        }
        // an address that starts with a pattern is cut to the lines there are (a number or a relative one is an error out of range)
        if (patternBase && line != null) line = line.coerceIn(1, lines.size)
        return line
    }

    private fun findLine(pat: String, from: Int, forward: Boolean): Int {
        val re = compile(pat, false)
        val n = lines.size
        for (k in 1..n) {
            spend()
            val idx = if (forward) (from - 1 + k).mod(n) else (from - 1 - k).mod(n)
            val found = re.find(lines[idx].s, 0, host.budget)
            if (host.budget.exhausted) throw Stop(VimNotice.SEARCH_LIMIT)
            if (found != null) return idx + 1
        }
        throw Stop(VimNotice.NOT_FOUND)
    }

    /** The pattern that an empty one stands for: the last one this line used (Vim's last search pattern moves with an address, :s and :g), else the last search. */
    private fun currentPattern(): String? = newSearch ?: host.memory.lastSearch

    private fun compile(pat: String, ignoreCase: Boolean): VimRegex =when (val c = VimRegex.compile(pat, ignoreCase)) {
        is VimRegex.Compiled.Ok -> c.regex
        VimRegex.Compiled.TooBig -> throw Stop(VimNotice.SEARCH_LIMIT)
        VimRegex.Compiled.Unsupported -> throw Stop(VimNotice.PATTERN_UNSUPPORTED)
    }

    /** The range in front of a command (Vim's parse_cmd_address): addresses separated by "," or ";", "%" for all lines. */
    private fun parseRange(p: P): Range {
        var line1: Int
        var line2 = cur + 1
        var count = 0
        var empty: Boolean
        while (true) {
            line1 = line2
            line2 = cur + 1
            p.skipBlanks()
            val a = parseAddress(p, cur + 1)
            empty = false
            if (a == null) {
                if (p.peek() == '%') { p.i++; line1 = 1; line2 = lines.size; count++ } else empty = true
            } else line2 = a
            count++
            p.skipBlanks()
            if (p.peek() == ';') {
                p.i++
                cur = line2.coerceIn(1, lines.size) - 1
                cursorKept = false
                stopAt(cur) // the cursor stays on that line if what follows fails
                continue
            }
            if (p.peek() == ',') { p.i++; continue }
            break
        }
        if (count == 1) {
            line1 = line2
            if (empty) count = 0
        }
        return Range(line1, line2, count)
    }

    /** The range checked and, where line 0 is not a place of its own, line 0 taken as line 1 (as Vim does for most commands). */
    private fun checkRange(r: Range, allowZero: Boolean = false): Range {
        val line1 = if (r.line1 == 0 && !allowZero) 1 else r.line1
        val line2 = if (r.line2 == 0 && !allowZero) 1 else r.line2
        if (line1 > line2) throw Stop(VimNotice.EX_ERROR)
        if (line2 > lines.size || line1 < 0) throw Stop(VimNotice.EX_ERROR)
        return Range(line1, line2, r.count)
    }

    private class Cmd(val name: String, val min: Int)

    private val table = listOf(
        Cmd("substitute", 1), Cmd("delete", 1), Cmd("yank", 1), Cmd("put", 2), Cmd("move", 1), Cmd("t", 1), Cmd("copy", 2), Cmd("join", 1),
        Cmd("normal", 4), Cmd("global", 1), Cmd("vglobal", 1), Cmd("registers", 3), Cmd("display", 2), Cmd("marks", 5), Cmd("undo", 1),
        Cmd("redo", 3), Cmd("write", 1), Cmd("wq", 2), Cmd("xit", 1), Cmd("quit", 1), Cmd("qall", 2), Cmd("wall", 2), Cmd("xall", 2),
        Cmd("wqall", 3), Cmd("nohlsearch", 3), Cmd("k", 1), Cmd("mark", 2), Cmd("number", 2), Cmd("exit", 3), Cmd("update", 2),
        Cmd("close", 3), Cmd("only", 2), Cmd("list", 1), Cmd("sno", 3),
    )

    /** The commands that take a "!": :put! (above), :join! (no blank), :global! (the lines that do not match), :normal! and the ones that only answer that a keyboard cannot save or quit. */
    private val bangCommands = setOf("put", "join", "global", "normal", "write", "wq", "xit", "quit", "qall", "wall", "xall", "wqall", "exit", "update", "close", "only")

    private fun lookup(word: String): String? {
        if (word.isEmpty()) return null
        if (word == "p" || word == "pr" || word == "pri" || word == "prin") return "show" // the command that only shows lines: it does nothing here (the full name is not offered)
        for (c in table) if (word.length >= c.min && c.name.startsWith(word)) return c.name
        return null
    }

    /** The command is parsed to its end: anything left that no part of the syntax took (trailing characters, an unknown argument) is an error, never ignored. */
    private fun finish(p: P) {
        p.skipBlanks()
        if (p.more()) throw Stop(VimNotice.EX_ERROR)
    }

    /** The commands that take no range (:reg, :marks, :undo...): a range in front is an error, as in Vim, not something to skip. */
    private fun noRange(range: Range) { if (range.count != 0) throw Stop(VimNotice.EX_ERROR) }

    private fun execCommand(p: P) {
        spend()
        val range = parseRange(p)
        p.skipBlanks()
        if (!p.more()) { // a line number alone: go there
            if (range.count == 0) return
            if (range.line2 > lines.size || range.line2 < 0) throw Stop(VimNotice.EX_ERROR) // a line that is not there: Vim does not move
            moved(maxOf(range.line2, 1) - 1)
            return
        }
        val c = p.peek()
        var name: String
        when {
            c == '&' -> { p.i++; name = "&" }
            c == '<' || c == '>' -> name = c.toString()
            c == '~' || c == '!' || c == '=' || c == '@' || c == '#' -> throw Stop(VimNotice.EX_ERROR)
            c.isLetter() -> {
                val start = p.i
                // letters make the name, except where a command takes its argument right after it (:s/, :g#, :ka)
                while (p.peek().isLetter()) p.i++
                val word = p.s.substring(start, p.i)
                var resolved = lookup(word)
                if (resolved == null && word.length > 1) { // "sg": s with flags; "dx": d with a register: a name that is not known is its first letters
                    for (len in word.length - 1 downTo 1) {
                        val r = lookup(word.substring(0, len))
                        if (r != null && (r == "substitute" || r == "k" || r == "delete" || r == "yank" || r == "put")) { p.i = start + len; resolved = r; break }
                    }
                }
                name = resolved ?: throw Stop(VimNotice.EX_ERROR)
            }
            else -> throw Stop(VimNotice.EX_ERROR)
        }
        if (name == "<" || name == ">") {
            var n = 0
            while (p.peek() == name[0]) { n++; p.i++ }
            p.skipBlanks()
            val count = if (p.peek() in '0'..'9') parseNumber(p) else 0
            finish(p)
            val r = checkRange(if (count > 0) Range(range.line2, minOf(range.line2 + count - 1, lines.size), 2) else range)
            spend(r.line2 - r.line1 + 1)
            // each shifted line gets this much longer at most: checked before any line is built
            if (name == ">") guardGrow((r.line2 - r.line1 + 1L) * n * (if (host.indentWithTab) 1 else VimOps.SHIFT_WIDTH))
            for (l in r.line1..r.line2) {
                val ln = lines[l - 1]
                if (ln.s.isNotEmpty()) setLine(l - 1, VimOps.shiftLine(ln.s, name == "<", n, host.indentWithTab))
            }
            changed = true
            moved(r.line2 - 1)
            return
        }
        val bang = p.peek() == '!'
        if (bang && name != "&") p.i++
        // a "!" after a command that has no use for it is an error (the commands below read it as their own flag)
        if (bang && name != "&" && name !in bangCommands) throw Stop(VimNotice.EX_ERROR)
        when (name) {
            "substitute", "&" -> substitute(p, range, name == "&", bang)
            "sno" -> throw Stop(VimNotice.EX_ERROR)
            "delete" -> deleteLines(p, range)
            "yank" -> yankLines(p, range)
            "put" -> putLines(p, range, bang)
            "move" -> moveLines(p, range, copy = false)
            "t", "copy" -> moveLines(p, range, copy = true)
            "join" -> joinLines(p, range, bang)
            "normal" -> normal(p, range)
            "global" -> global(p, range, invert = bang)
            "vglobal" -> global(p, range, invert = true)
            // a register or mark name, or a count, after these is not offered (Vim shows only those registers or marks): an error, not the whole listing
            "registers", "display" -> { noRange(range); finish(p); message = host.registerListing() }
            "marks" -> { noRange(range); finish(p); message = host.markListing() }
            "undo" -> { noRange(range); finish(p); undo = 1 }
            "redo" -> { noRange(range); finish(p); undo = 2 }
            "write", "wq", "xit", "quit", "qall", "wall", "xall", "wqall", "exit", "update", "close", "only" -> throw Stop(VimNotice.CANNOT_SAVE)
            "nohlsearch" -> { noRange(range); finish(p); throw Stop(VimNotice.NO_HIGHLIGHT) }
            "k", "mark" -> {
                p.skipBlanks()
                val n = p.peek()
                if (n !in 'a'..'z') throw Stop(VimNotice.EX_ERROR)
                p.i++
                finish(p)
                val l = if (range.count == 0) cur else range.line2 - 1
                if (l !in 0 until lines.size) throw Stop(VimNotice.EX_ERROR)
                host.setMark(n, l)
            }
            "number", "list", "show" -> { finish(p); checkRange(range); if (range.count > 0) moved(range.line2 - 1) }
            else -> throw Stop(VimNotice.EX_ERROR)
        }
    }

    // ---- :d :y :pu :m :t :j ---------------------------------------------------------------------------------------------------

    /**
     * A register name after :d, :y or :put, or null. A yank or delete writes a to z, A to Z, ", - , _ , + and *; :put also reads a digit, ".", ":" and "/".
     * For :d and :y a digit is the count. Any other character is left where it is, and the command then ends in an error (finish), so an expression
     * register ("=") or a name that is not a register never falls back to the unnamed one.
     */
    private fun regArg(p: P, read: Boolean = false): Char? {
        p.skipBlanks()
        val c = p.peek()
        val ok = c in 'a'..'z' || c in 'A'..'Z' || c == '"' || c == '-' || c == '_' || c == '+' || c == '*' ||
            (read && (c in '0'..'9' || c == '.' || c == ':' || c == '/'))
        if (!ok) return null
        p.i++
        return c
    }

    private fun countArg(p: P): Int? {
        p.skipBlanks()
        return if (p.peek() in '0'..'9') parseNumber(p) else null
    }

    private fun withCount(r: Range, count: Int?): Range {
        if (count == null) return r
        if (count <= 0) throw Stop(VimNotice.EX_ERROR)
        return Range(r.line2, minOf(r.line2 + count - 1, lines.size), 2)
    }

    private fun linesText(r: Range): String {
        val sb = StringBuilder()
        for (l in r.line1..r.line2) {
            if (l > r.line1) sb.append('\n')
            sb.append(lines[l - 1].s)
        }
        return sb.toString()
    }

    private fun deleteLines(p: P, range: Range) {
        val reg = regArg(p)
        val count = countArg(p)
        finish(p)
        val r = checkRange(withCount(range, count))
        spend(r.line2 - r.line1 + 1)
        val text = linesText(r)
        // where nothing may be kept the memory empties the registers this delete would have replaced and keeps nothing
        if (host.memory.delete(reg, Reg(text, RegType.LINE), multiLine = true, useRegOne = false) == Kept.FULL) throw Stop(VimNotice.REGISTER_FULL)
        removeLines(r.line1 - 1, r.line2 - r.line1 + 1)
        if (lines.isEmpty()) addLine(0, Line(""))
        changed = true
        moved(minOf(r.line1 - 1, lines.size - 1))
    }

    private fun yankLines(p: P, range: Range) {
        val reg = regArg(p)
        val count = countArg(p)
        finish(p)
        val r = checkRange(withCount(range, count))
        spend(r.line2 - r.line1 + 1)
        when (host.memory.yank(reg, Reg(linesText(r), RegType.LINE))) {
            Kept.OFF -> throw Stop(VimNotice.REGISTER_OFF)
            Kept.FULL -> throw Stop(VimNotice.REGISTER_FULL)
            Kept.OK -> {}
        }
    }

    private fun putLines(p: P, range: Range, above0: Boolean) {
        val reg = regArg(p, read = true) ?: '"'
        finish(p) // ":put =expr" and anything else after the register name is not offered: an error, never the unnamed register
        val rr = checkRange(range, allowZero = true)
        var above = above0
        var line2 = rr.line2
        if (line2 == 0) { line2 = 1; above = true } // ":0put" is ":1put!"
        stopAt(line2 - 1, clamp = true) // the cursor goes to the line before the put is tried
        val r = host.register(reg)?.takeIf { it.text.isNotEmpty() || it.type == RegType.LINE } ?: throw Stop(VimNotice.REGISTER_EMPTY)
        guardGrow(r.text.length + 1L) // the lines of the register are added as they are: its text and one more line break
        val put = r.text.split('\n')
        spend(put.size)
        var at = line2 // insert after this 1-based line
        if (above) at = maxOf(0, at - 1)
        for ((k, s) in put.withIndex()) addLine(at + k, Line(s))
        changed = true
        moved(at + put.size - 1)
    }

    private fun address(p: P): Int {
        val a = parseAddress(p, cur + 1) ?: throw Stop(VimNotice.EX_ERROR)
        if (a < 0 || a > lines.size) throw Stop(VimNotice.EX_ERROR)
        return a
    }

    private fun moveLines(p: P, range0: Range, copy: Boolean) {
        val range = checkRange(range0)
        val dest = address(p)
        finish(p)
        val n = range.line2 - range.line1 + 1
        spend(n)
        if (!copy && dest >= range.line1 && dest < range.line2) throw Stop(VimNotice.EX_ERROR) // moving lines into themselves
        if (!copy && (dest == range.line2 || dest == range.line1 - 1)) { moved(range.line2 - 1); return }
        if (copy) { // the copies add these lines: the size is known before one is made (":g/^/%t$" doubles the text at every line)
            var chars = 0L
            for (l in range.line1..range.line2) chars += lines[l - 1].s.length + 1
            guardGrow(chars)
        }
        val picked = ArrayList<Line>()
        for (l in range.line1..range.line2) picked.add(if (copy) Line(lines[l - 1].s) else lines[l - 1])
        if (copy) {
            for ((k, ln) in picked.withIndex()) addLine(dest + k, ln)
            moved(dest + n - 1)
        } else {
            removeLines(range.line1 - 1, n)
            val at = if (dest > range.line2) dest - n else dest
            for ((k, ln) in picked.withIndex()) addLine(at + k, ln)
            moved(at + n - 1)
        }
        changed = true
    }

    private fun joinLines(p: P, range: Range, bang: Boolean) {
        val count = countArg(p)
        finish(p)
        var r = Range(maxOf(range.line1, 1), maxOf(range.line2, 1), range.count)
        if (count != null) r = Range(range.line2, range.line2 + count - 1, 2)
        else if (r.line1 == r.line2) {
            if (range.count >= 2) { stopAt(r.line1 - 1); moved(r.line1 - 1, curCol); return } // :2,2join does nothing
            r = Range(r.line1, r.line1 + 1, 2)
        }
        if (r.line2 > lines.size) r = Range(r.line1, lines.size, 2)
        stopAt(r.line1 - 1) // the cursor goes to the first line before the join is tried
        if (r.line2 <= r.line1) throw Stop(VimNotice.EX_ERROR) // a beep on the last line
        r = checkRange(r)
        val parts = ArrayList<String>()
        for (l in r.line1..r.line2) parts.add(lines[l - 1].s)
        spend(parts.size)
        val joined = VimOps.join(parts, insertSpace = !bang)
        setLine(r.line1 - 1, joined.line)
        removeLines(r.line1, r.line2 - r.line1)
        changed = true
        moved(r.line1 - 1)
    }

    // ---- :s -----------------------------------------------------------------------------------------------------------------------

    private fun substitute(p: P, range: Range, ampersand: Boolean, bang: Boolean) {
        val before = host.memory.substitute // the last substitute, where the layer may keep one (null in a field that keeps nothing)
        var pattern: String
        var replacement: String
        var flags: String
        val first = p.peek()
        val fresh = !ampersand && p.more() && first != ' ' && !first.isLetterOrDigit() && first != '\\' && first != '"' && first != '|' && first != '&'
        if (fresh) {
            val delim = first
            p.i++
            val rest = p.rest()
            val (pat, afterPat) = splitAt(rest, delim)
            pattern = pat
            if (afterPat == null) { replacement = ""; flags = "" ; p.i = p.s.length }
            else {
                val (rep, afterRep) = splitAt(afterPat, delim, forReplacement = true)
                replacement = rep
                flags = afterRep ?: ""
                p.i = p.s.length
            }
            if (pattern.isEmpty()) pattern = currentPattern() ?: throw Stop(VimNotice.NO_PATTERN)
        } else {
            pattern = before?.pattern ?: throw Stop(VimNotice.NO_PATTERN)
            replacement = before.replacement
            flags = p.rest().trim()
            p.i = p.s.length
        }
        // flags and a count: g i I, the display flags (p # l: the keyboard shows no output, so they change nothing) and a count; every other flag,
        // and anything after the count, is an error (e, r, c and n are not offered: ignoring them would give another result than Vim's)
        var fl = flags.trimStart()
        var global = false
        var ic = false
        var count: Int? = null
        var i = 0
        if (fl.startsWith("&")) fl = (before?.flags ?: "") + fl.substring(1) // a leading "&" keeps the flags of the last substitute
        while (i < fl.length) {
            when (fl[i]) {
                'g' -> global = !global
                'i' -> ic = true
                'I' -> ic = false
                'p', '#', 'l' -> {}
                ' ', '\t' -> {}
                in '0'..'9' -> {
                    count = parseCount(fl.substring(i))
                    var j = i
                    while (j < fl.length && fl[j] in '0'..'9') j++
                    if (fl.substring(j).isNotBlank()) throw Stop(VimNotice.EX_ERROR)
                    i = fl.length
                }
                else -> throw Stop(VimNotice.EX_ERROR)
            }
            i++
        }
        val r = withCount(range, count)
        checkRange(r)
        val re = compile(pattern, ic)
        host.memory.setSubstitute(pattern, replacement, (if (global) "g" else "") + (if (ic) "i" else ""))
        newSearch = pattern
        var any = false
        var lastLine = -1
        var l = r.line1 - 1
        var end = r.line2 - 1
        while (l <= end) {
            spend()
            val line = lines[l].s
            val parts = substituteLine(line, re, replacement, global, host.maxChars.toLong() - (size - 1) + line.length)
            if (host.budget.exhausted) throw Stop(VimNotice.SEARCH_LIMIT)
            if (parts != null) {
                any = true
                setLine(l, parts[0])
                for (k in 1 until parts.size) addLine(l + k, Line(parts[k]))
                l += parts.size - 1
                end += parts.size - 1
                lastLine = l
            }
            l++
        }
        if (!any) {
            if (inGlobal) return
            throw Stop(VimNotice.NOT_FOUND)
        }
        changed = true
        moved(lastLine)
    }

    private fun parseCount(s: String): Int {
        var n = 0
        for (ch in s) { if (ch !in '0'..'9') break; n = minOf(n * 10 + (ch - '0'), 10_000_000) }
        return n
    }

    /** Splits at the first [delim] that is not escaped (in a pattern, brackets of a class are not looked into). Null second part: no delimiter. */
    private fun splitAt(s: String, delim: Char, forReplacement: Boolean = false): Pair<String, String?> {
        var i = 0
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                if (s[i + 1] == delim && delim != '\\') { sb.append(if (forReplacement) "\\" else "").append(delim); i += 2; continue }
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == delim) return sb.toString() to s.substring(i + 1)
            sb.append(c)
            i++
        }
        return sb.toString() to null
    }

    /** The new text of [line] after replacing the first (or every) match, as one line or several when the replacement has a "\r"; null: no match. */
    private fun substituteLine(line: String, re: VimRegex, rep: String, global: Boolean, room: Long): List<String>? {
        var pos = 0
        var copied = 0
        val out = StringBuilder()
        var any = false
        var lastEnd = -1
        while (pos <= line.length) {
            val m = re.find(line, pos, host.budget) ?: break
            // an empty match right after the previous match is not replaced
            if (m.end == m.start && m.start == lastEnd) {
                if (m.start >= line.length) break
                pos = m.start + Character.charCount(line.codePointAt(m.start))
                if (pos >= line.length) break // stepped over the last character: Vim does not try again at the end
                continue
            }
            any = true
            // [room] is the longest the new line may be: the text before this match, the replacement and what is left of the line are checked
            // before they are appended (a replacement of "&" a thousand times on a long line would be made first otherwise)
            if (out.length.toLong() + (m.start - copied) + (line.length - m.end) > room) throw Stop(VimNotice.PLAY_LIMIT)
            out.append(line, copied, m.start)
            appendReplacement(out, rep, m, line, room - (line.length - m.end))
            copied = m.end
            lastEnd = m.end
            if (!global) break
            pos = if (m.end == m.start) {
                if (m.end >= line.length) { break } else {
                    out.append(line, m.end, m.end + Character.charCount(line.codePointAt(m.end)))
                    copied = m.end + Character.charCount(line.codePointAt(m.end))
                    if (copied >= line.length) break // having stepped over a character to the end of the line, Vim does not try again there
                    copied
                }
            } else m.end
            if (pos > line.length) break
        }
        if (host.budget.exhausted || !any) return null
        out.append(line, minOf(copied, line.length), line.length)
        return out.toString().split('\n')
    }

    private fun appendReplacement(out: StringBuilder, rep: String, m: VimRegex.Match, line: String, limit: Long) {
        var i = 0
        var mode = ' ' // pending one-shot case change: 'u' or 'l'; ongoing: 'U' or 'L'
        var oneShot = ' '
        fun put(s: String) {
            if (out.length.toLong() + s.length > limit) throw Stop(VimNotice.PLAY_LIMIT) // the new line would be too long: it is not built
            for (ch in s) {
                var c = ch
                if (oneShot == 'u') { c = c.uppercaseChar(); oneShot = ' ' }
                else if (oneShot == 'l') { c = c.lowercaseChar(); oneShot = ' ' }
                else if (mode == 'U') c = c.uppercaseChar()
                else if (mode == 'L') c = c.lowercaseChar()
                out.append(c)
            }
        }
        while (i < rep.length) {
            val c = rep[i]
            if (c == '&') { put(m.group(0, line)); i++; continue }
            if (c == '\\' && i + 1 < rep.length) {
                val e = rep[i + 1]
                i += 2
                when (e) {
                    in '0'..'9' -> put(m.group(e - '0', line))
                    'n' -> out.append('\n') // Vim puts a NUL here; in a field it is a line break, as \r
                    'r' -> out.append('\n') // a line break (the lines of the field never hold one)
                    't' -> put("\t")
                    'u', 'l' -> oneShot = e
                    'U', 'L' -> mode = e
                    'e', 'E' -> { mode = ' '; oneShot = ' ' }
                    else -> put(e.toString())
                }
                continue
            }
            if (c == '\r') { out.append('\n'); i++; continue }
            put(c.toString())
            i++
        }
    }

    // ---- :normal and :global ----------------------------------------------------------------------------------------------------------

    private fun normal(p: P, range: Range) {
        p.skipBlanks()
        val keys = p.rest()
        if (keys.isEmpty()) throw Stop(VimNotice.EX_ERROR)
        if (range.count == 0) {
            copying(size.toInt() - 1) // the join below
            val text = join()
            val caret = offsetOf(cur)
            val out = host.normal(text, caret, keys) ?: throw Stop(VimNotice.PLAY_LIMIT)
            replaceAll(out.first, out.second, text)
            return
        }
        checkRange(range)
        var ln = range.line1
        while (ln <= range.line2) {
            spend()
            // as in Vim a line number that the buffer lost meanwhile is the last line
            copying(size.toInt() - 1)
            val text = join()
            var start = 0
            for (k in 0 until minOf(ln, lines.size) - 1) start += lines[k].s.length + 1
            val out = host.normal(text, start, keys) ?: throw Stop(VimNotice.PLAY_LIMIT)
            replaceAll(out.first, out.second, text)
            ln++
        }
    }

    /**
     * Takes the text a :normal run left ([old] is the text it was given, which the lines still hold): the lines that are the same stay the same objects
     * (so that :g marks follow them).
     */
    private fun replaceAll(newText: String, caret: Int, old: String) {
        if (old == newText) {
            val line = VimLines.lineOf(newText, caret)
            moved(line, caret - VimLines.lineStartOf(newText, line))
            return
        }
        copying(newText.length) // the split below
        val newLines = newText.split('\n')
        val map = VimLines.lineMap(lines.map { it.s }, newLines) // the lines hold [old]: their strings are the old lines, no second split
        val updated = ArrayList<Line?>(newLines.size)
        for (k in newLines.indices) updated.add(null)
        for ((k, ln) in lines.withIndex()) if (k < map.size && map[k] >= 0) { ln.s = newLines[map[k]]; updated[map[k]] = ln }
        val result = ArrayList<Line>()
        for ((k, ln) in updated.withIndex()) result.add(ln ?: Line(newLines[k]))
        lines = result
        size = newText.length + 1L // the text of a :normal run is as long as the copy it ran on may be (the copy refuses a longer text)
        if (size - 1 > host.maxChars) throw Stop(VimNotice.PLAY_LIMIT)
        changed = true
        val line = VimLines.lineOf(newText, caret)
        moved(line, caret - VimLines.lineStartOf(newText, line))
    }

    private fun global(p: P, range: Range, invert: Boolean) {
        if (inGlobal) throw Stop(VimNotice.EX_ERROR)
        val delim = p.peek()
        if (!p.more() || delim.isLetterOrDigit() || delim == '\\' || delim == '"' || delim == '|' || delim == ' ') throw Stop(VimNotice.EX_ERROR)
        p.i++
        val (pat, after) = splitAt(p.rest(), delim)
        p.i = p.s.length
        val pattern = if (pat.isEmpty()) (currentPattern() ?: throw Stop(VimNotice.NO_PATTERN)) else pat
        val sub = (after ?: "").ifEmpty { "p" }
        val r = if (range.count == 0) Range(1, lines.size, 2) else range
        checkRange(r)
        val re = compile(pattern, false)
        newSearch = pattern
        var tagged = 0
        for (l in r.line1..r.line2) {
            spend()
            val hit = re.find(lines[l - 1].s, 0, host.budget) != null
            if (host.budget.exhausted) throw Stop(VimNotice.SEARCH_LIMIT)
            lines[l - 1].tag = hit != invert
            if (hit != invert) tagged++
        }
        if (tagged == 0) throw Stop(VimNotice.NOT_FOUND)
        inGlobal = true
        while (true) {
            var idx = -1
            for ((k, ln) in lines.withIndex()) if (ln.tag) { idx = k; break }
            if (idx < 0) break
            spend() // one step for every line that the command is run on (the command itself is a step too)
            if (host.budget.exhausted) throw Stop(VimNotice.SEARCH_LIMIT)
            lines[idx].tag = false
            moved(idx, 0)
            stopAt(idx, col0 = true) // a command that fails leaves the cursor on this line
            execLine(sub)
        }
        inGlobal = false
        for (ln in lines) ln.tag = false
    }
}
