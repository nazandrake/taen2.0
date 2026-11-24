// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * The Vim layer: a parser and state machine for Normal, Visual and Visual Line mode, working on the text of the field through
 * a [VimPort]. The keys of these modes never reach the typing path, so nothing is learned, suggested or remembered by them.
 *
 * Text safety: every edit is computed from the text the port read for this command, and the field is read again just before the
 * edit; if it cannot be read, is a part of the field, is longer than [MAX_CHARS], or changed in between (text or selection), the
 * command does nothing and returns a [VimNotice]. A range is never widened. Pure cursor motions may use cursor keys when the text
 * cannot be read. The one unnamed register holds text in memory only, and only while [VimRules.registerAllowed] says so.
 */
class VimEngine(private val port: VimPort, private val rules: VimRules) {
    var mode: VimMode = VimMode.INSERT
        private set

    private var count1 = 0
    private var count2 = 0
    private var op: Char? = null
    private var prefix: Char? = null
    private var anchor = -1
    private var visualCursor = -1
    private var shownSelection: Pair<Int, Int>? = null
    private var wantCol: Int? = null
    private var expectedCaret = -1
    private var lastFind: Pair<Char, Int>? = null
    private var registerText: String? = null
    private var registerLinewise = false

    /** The unnamed register (memory only), for the tests. */
    internal val register: String? get() = registerText
    internal val registerIsLinewise: Boolean get() = registerLinewise

    /** True while the next key is a character argument (f, F, t, T, r): the typing keyboard shows so that any letter can be typed. */
    val wantsChar: Boolean get() = mode != VimMode.INSERT && prefix in ARG_PREFIXES

    /** The count, operator and prefix typed so far ("2d3"), for the status line. */
    val pending: String
        get() = buildString {
            if (count1 > 0) append(count1)
            op?.let { append(it) }
            if (count2 > 0) append(count2)
            prefix?.let { append(it) }
        }

    /** Forgets the register: at the screen lock, at a wipe, and when Vim mode is turned off. */
    fun clearRegister() {
        registerText = null
        registerLinewise = false
    }

    /** The field ended or changed: the typing keyboard is back and nothing is pending. */
    fun reset() {
        mode = VimMode.INSERT
        clearPending()
        anchor = -1
        visualCursor = -1
        shownSelection = null
        wantCol = null
        expectedCaret = -1
    }

    /** The Esc key of the typing keyboard (and the Esc key of the Vim layouts). */
    fun escape(): VimOutcome {
        if (!rules.available()) {
            reset()
            return VimOutcome(VimNotice.UNAVAILABLE)
        }
        when (mode) {
            VimMode.INSERT -> return enterNormal()
            VimMode.NORMAL -> {
                if (op != null || prefix != null || count1 > 0 || count2 > 0) { clearPending(); return VimOutcome() }
                val s = (port.read() as? VimRead.Text) ?: return VimOutcome()
                if (s.selStart != s.selEnd) {
                    val at = VimBuf(s.text).normal(s.selStart)
                    port.select(at, at)
                    expectedCaret = at
                }
            }
            VimMode.VISUAL, VimMode.VISUAL_LINE -> leaveVisual(null)
        }
        return VimOutcome()
    }

    private fun enterNormal(): VimOutcome {
        mode = VimMode.NORMAL
        clearPending()
        wantCol = null
        when (val r = port.read()) {
            is VimRead.Text -> {
                // Vim leaves insert mode one character to the left, unless at the start of the line
                val b = VimBuf(r.text)
                val at = b.normal(b.prev(maxOf(r.selStart, r.selEnd).coerceIn(0, r.text.length)))
                port.select(at, at)
                expectedCaret = at
            }
            else -> expectedCaret = -1
        }
        return VimOutcome()
    }

    /** A key of Normal or Visual mode: an ASCII character of the Vim layouts, or [CTRL_R]. In [VimMode.INSERT] it is not handled. */
    fun key(code: Int): VimOutcome {
        if (mode == VimMode.INSERT) return VimOutcome(handled = false)
        if (!rules.available()) {
            reset()
            return VimOutcome(VimNotice.UNAVAILABLE)
        }
        if (code == ESC) return escape()
        return process(code)
    }

    // ---- parsing ------------------------------------------------------------------------------------------------------

    private fun clearPending() {
        count1 = 0
        count2 = 0
        op = null
        prefix = null
    }

    private fun cancel(): VimOutcome {
        clearPending()
        return VimOutcome()
    }

    /** A command that does nothing because it cannot be done (Vim beeps and drops the keys typed ahead). */
    private fun fail(notice: VimNotice? = null) = VimOutcome(notice, failed = true)

    private fun countGiven(): Int? = if (count1 == 0 && count2 == 0) null else (maxOf(count1, 1) * maxOf(count2, 1)).coerceAtMost(MAX_COUNT)

    private fun process(key: Int): VimOutcome {
        val pre = prefix
        if (pre != null) {
            prefix = null
            return afterPrefix(pre, key)
        }
        val visual = mode != VimMode.NORMAL
        if (key in '1'.code..'9'.code || (key == '0'.code && (if (op == null) count1 else count2) > 0)) {
            val digit = key - '0'.code
            if (op == null) count1 = (count1 * 10 + digit).coerceAtMost(MAX_COUNT) else count2 = (count2 * 10 + digit).coerceAtMost(MAX_COUNT)
            return VimOutcome()
        }
        return when (key) {
            'h'.code, 'l'.code, 'j'.code, 'k'.code, 'w'.code, 'W'.code, 'b'.code, 'B'.code, 'e'.code, 'E'.code,
            '0'.code, '^'.code, '$'.code, 'G'.code -> motionKey(key)
            ';'.code, ','.code -> repeatFind(key == ','.code)
            'g'.code, 'f'.code, 'F'.code, 't'.code, 'T'.code -> { prefix = key.toChar(); VimOutcome() }
            'd'.code, 'c'.code, 'y'.code -> operatorKey(key.toChar())
            'x'.code -> shorthand('d', 'l'.code)
            'X'.code -> shorthand('d', 'h'.code)
            's'.code -> shorthand('c', 'l'.code)
            'D'.code -> shorthand('d', '$'.code)
            'C'.code -> shorthand('c', '$'.code)
            'S'.code -> shorthand('c', 'c'.code)
            'Y'.code -> shorthand('y', 'y'.code)
            'r'.code -> if (visual || op != null) cancel() else { prefix = 'r'; VimOutcome() }
            'p'.code, 'P'.code -> if (visual || op != null) cancel() else paste(before = key == 'P'.code)
            'i'.code, 'a'.code ->
                if (op != null || visual) { prefix = key.toChar(); VimOutcome() } else insertCommand(key.toChar())
            'I'.code, 'A'.code, 'o'.code, 'O'.code ->
                if (key == 'o'.code && visual) swapVisualEnds() else if (visual || op != null) cancel() else insertCommand(key.toChar())
            'v'.code -> if (op != null) cancel() else toggleVisual(VimMode.VISUAL)
            'V'.code -> if (op != null) cancel() else toggleVisual(VimMode.VISUAL_LINE)
            'u'.code -> if (visual || op != null) cancel() else { clearPending(); port.history(false); VimOutcome() }
            CTRL_R -> if (visual || op != null) cancel() else { clearPending(); port.history(true); VimOutcome() }
            else -> cancel()
        }
    }

    private fun afterPrefix(pre: Char, key: Int): VimOutcome = when (pre) {
        'g' -> if (key == 'g'.code) motionKey(GG) else cancel()
        'f', 'F', 't', 'T' ->
            if (key <= 0 || key == ESC || key == '\n'.code) cancel() else { lastFind = pre to key; findMotion(pre, key, isRepeat = false) }
        'r' -> if (key <= 0 || key == ESC || key == '\n'.code) cancel() else replaceChars(key)
        'i', 'a' -> textObject(pre, key)
        else -> cancel()
    }

    private fun operatorKey(o: Char): VimOutcome {
        if (mode != VimMode.NORMAL) return visualOperator(o)
        val current = op
        if (current == null) {
            op = o
            return VimOutcome()
        }
        if (current != o) return cancel()
        return doubled(o)
    }

    /** x, X, s, D, C, S, Y: an operator with its motion (a count given before it applies to the motion). */
    private fun shorthand(o: Char, motion: Int): VimOutcome {
        if (mode != VimMode.NORMAL) {
            // in Visual mode x is d, s and others are not supported
            return if (o == 'd' && motion == 'l'.code) visualOperator('d') else cancel()
        }
        if (op != null) return cancel()
        op = o
        return if (motion == 'c'.code || motion == 'y'.code) doubled(o) else motionKey(motion)
    }

    // ---- reading ------------------------------------------------------------------------------------------------------

    private class Snap(val b: VimBuf, val selStart: Int, val selEnd: Int) {
        val text get() = b.text
    }

    private var failure: VimNotice? = null

    private fun snapshot(): Snap? {
        failure = null
        return when (val r = port.read()) {
            is VimRead.Text -> Snap(VimBuf(r.text), r.selStart, r.selEnd)
            VimRead.Unreadable -> { failure = VimNotice.NO_TEXT; null }
            VimRead.Partial -> { failure = VimNotice.PARTIAL_TEXT; null }
            VimRead.TooLong -> { failure = VimNotice.TOO_LONG; null }
        }
    }

    /** The cursor of this command and whether the state is consistent with the field; Visual mode is left when the selection is not the one it set. */
    private fun cursorOf(s: Snap): Int? {
        if (mode == VimMode.NORMAL) {
            if (s.selStart != s.selEnd) {
                clearPending()
                return null
            }
            if (s.selStart != expectedCaret) wantCol = null // the user moved the cursor
            return s.b.normal(s.selStart)
        }
        if (shownSelection != s.selStart to s.selEnd) {
            leaveVisual(null)
            return null
        }
        return visualCursor
    }

    // ---- motions ------------------------------------------------------------------------------------------------------

    private fun fallbackNav(key: Int): VimNav? = when (key) {
        'h'.code -> VimNav.LEFT
        'l'.code -> VimNav.RIGHT
        'j'.code -> VimNav.DOWN
        'k'.code -> VimNav.UP
        'w'.code, 'W'.code -> VimNav.WORD_RIGHT
        'b'.code, 'B'.code -> VimNav.WORD_LEFT
        '0'.code, '^'.code -> VimNav.LINE_START
        '$'.code -> VimNav.LINE_END
        GG -> VimNav.TEXT_START
        'G'.code -> VimNav.TEXT_END
        else -> null
    }

    private fun motionKey(key: Int): VimOutcome {
        val n = countGiven()
        val pure = op == null && mode == VimMode.NORMAL
        if (pure && (key == 'j'.code || key == 'k'.code)) { // by visual line: the editor knows where the wrapped lines are
            clearPending()
            wantCol = null
            port.navigate(if (key == 'j'.code) VimNav.DOWN else VimNav.UP, n ?: 1)
            return VimOutcome()
        }
        return runMotion(key, n) { b, c, visual ->
            val hasOp = op != null
            val count = n ?: 1
            when (key) {
                'h'.code -> VimMotions.left(b, c, count, hasOp)
                'l'.code -> VimMotions.right(b, c, count, hasOp, visual)
                'j'.code, 'k'.code -> VimMotions.vertical(b, c, count, key == 'j'.code, wantCol, visual)
                'w'.code, 'W'.code -> VimMotions.word(b, c, count, key == 'W'.code, end = false, op = hasOp, change = op == 'c', visual = visual)
                'e'.code, 'E'.code -> VimMotions.word(b, c, count, key == 'E'.code, end = true, op = hasOp, change = false, visual = visual)
                'b'.code, 'B'.code -> VimMotions.back(b, c, count, key == 'B'.code)
                '0'.code -> VimMotions.lineStart(b, c)
                '^'.code -> VimMotions.firstNonBlank(b, c)
                '$'.code -> VimMotions.lineEnd(b, c, count, visual)
                'G'.code -> VimMotions.gotoLine(b, if (n == null) Int.MAX_VALUE else n - 1)
                GG -> VimMotions.gotoLine(b, if (n == null) 0 else n - 1)
                else -> Mv(c, MType.EXCL, cancels = true)
            }
        }
    }

    private fun findMotion(type: Char, ch: Int, isRepeat: Boolean): VimOutcome {
        val n = countGiven() ?: 1
        return runMotion(null, n) { b, c, _ -> VimMotions.find(b, c, ch, type, n, isRepeat) }
    }

    private fun repeatFind(reverse: Boolean): VimOutcome {
        val last = lastFind ?: return cancel()
        val type = if (reverse) last.first.let { if (it.isLowerCase()) it.uppercaseChar() else it.lowercaseChar() } else last.first
        return findMotion(type, last.second, isRepeat = true)
    }

    private fun runMotion(key: Int?, n: Int?, compute: (VimBuf, Int, Boolean) -> Mv): VimOutcome {
        val pending = op
        val visual = mode != VimMode.NORMAL
        val s = snapshot()
        if (s == null) {
            val notice = failure
            val nav = if (key != null && pending == null && !visual) fallbackNav(key) else null
            clearPending()
            if (nav == null) return VimOutcome(notice)
            port.navigate(nav, n ?: 1) // a pure cursor motion with the cursor keys when the text cannot be read
            return VimOutcome()
        }
        val c = cursorOf(s) ?: return VimOutcome(if (visual) VimNotice.CHANGED else VimNotice.SELECTED)
        val mv = compute(s.b, c, visual)
        val vertical = key == 'j'.code || key == 'k'.code
        val newWant: Int? = when {
            vertical -> wantCol ?: s.b.column(c)
            key == '$'.code -> Int.MAX_VALUE
            else -> null
        }
        if (pending != null) {
            val o = pending
            clearPending()
            if (mv.cancels) return fail()
            val rng = rangeOf(s.b, c, mv, o) ?: return fail()
            return operate(s, o, rng, mv, c)
        }
        clearPending()
        wantCol = newWant
        if (visual) {
            visualCursor = mv.pos
            showVisual(s.b)
        } else {
            port.select(mv.pos, mv.pos)
            expectedCaret = mv.pos
        }
        return if (mv.cancels) fail() else VimOutcome()
    }

    // ---- ranges and operators -----------------------------------------------------------------------------------------

    /** A range of the text: [start, end), or whole lines (start of the first line, end of the last, without its line break) when [linewise]. */
    private class Rng(val start: Int, val end: Int, val linewise: Boolean)

    private fun rangeOf(b: VimBuf, c: Int, mv: Mv, o: Char): Rng? {
        val s = minOf(c, mv.pos)
        var e = maxOf(c, mv.pos)
        if (mv.type == MType.LINE) return Rng(b.lineStart(s), b.lineEnd(e), true)
        if (mv.type == MType.INCL) {
            if (e < b.lineEnd(e)) e = b.next(e)
            return charRange(b, s, e, o)
        }
        // an exclusive motion that ends in column 0 of a later line ends at the end of the line before; if the start is at or before the
        // first non-blank of its line, the motion is linewise (Vim's "o_v" rules)
        if (e > s && e == b.lineStart(e) && b.lineStart(e) > b.lineStart(s)) {
            val lastLineEnd = e - 1
            val inIndent = (b.lineStart(s) until s).all { b.text[it] == ' ' || b.text[it] == '\t' }
            if (inIndent) return Rng(b.lineStart(s), b.lineEnd(lastLineEnd), true)
            e = lastLineEnd
        }
        return charRange(b, s, e, o)
    }

    private fun charRange(b: VimBuf, s: Int, e: Int, o: Char): Rng {
        // a delete over several lines from the indent to a blank rest of the end line is linewise
        if (o == 'd' && b.lineStart(e) > b.lineStart(s)) {
            val endLineEnd = b.lineEnd(e)
            var rest = e
            while (rest < endLineEnd && (b.text[rest] == ' ' || b.text[rest] == '\t')) rest++
            val inIndent = (b.lineStart(s) until s).all { b.text[it] == ' ' || b.text[it] == '\t' }
            if (rest >= endLineEnd && inIndent) return Rng(b.lineStart(s), endLineEnd, true)
        }
        return Rng(s, e, false)
    }

    private fun linesText(b: VimBuf, r: Rng) = b.text.substring(r.start, r.end) + "\n"

    private fun operate(s: Snap, o: Char, rng: Rng, mv: Mv?, cursor: Int): VimOutcome {
        wantCol = null
        val b = s.b
        if (o == 'y') {
            if (!rules.registerAllowed()) return VimOutcome(VimNotice.REGISTER_OFF)
            val yanked = if (rng.linewise) linesText(b, rng) else b.text.substring(rng.start, rng.end)
            registerText = yanked
            registerLinewise = rng.linewise
            val at = b.normal(if (rng.linewise) minOf(cursor, mv?.pos ?: cursor) else rng.start)
            port.select(at, at)
            expectedCaret = at
            return VimOutcome()
        }
        val change = o == 'c'
        if (!rng.linewise && rng.start == rng.end) { // nothing to take: "d" does nothing, "c" starts typing where it stands
            if (change) {
                port.select(rng.start, rng.start)
                mode = VimMode.INSERT
            }
            return VimOutcome()
        }
        val removed = if (rng.linewise) linesText(b, rng) else b.text.substring(rng.start, rng.end)
        val plan: Plan = if (!rng.linewise) {
            val newText = b.text.substring(0, rng.start) + b.text.substring(rng.end)
            Plan(rng.start, rng.end, "", if (change) rng.start else VimBuf(newText).normal(rng.start), removed, false, change)
        } else if (change) {
            Plan(rng.start, rng.end, "", rng.start, removed, true, true)
        } else {
            val ls = rng.start
            val le = rng.end
            val start: Int
            val end: Int
            val landing: Int
            if (le < b.length) { start = ls; end = le + 1; landing = ls }
            else if (ls > 0) { start = ls - 1; end = le; landing = b.lineStart(ls - 1) }
            else { start = 0; end = le; landing = 0 }
            val newText = b.text.substring(0, start) + b.text.substring(end)
            Plan(start, end, "", VimBuf(newText).firstNonBlank(landing), removed, true, false)
        }
        return apply(s, plan)
    }

    private class Plan(val start: Int, val end: Int, val replacement: String, val caret: Int, val removed: String?, val removedLinewise: Boolean, val insert: Boolean)

    /** Re-reads the field, and edits only if it is exactly what the plan was computed from. */
    private fun apply(s: Snap, p: Plan): VimOutcome {
        if (p.replacement.isNotEmpty() && !rules.accepts(p.replacement)) return VimOutcome(VimNotice.NOT_ACCEPTED)
        if (p.start < 0 || p.end > s.b.length || p.start > p.end) return VimOutcome(VimNotice.CHANGED)
        val again = port.read()
        if (again !is VimRead.Text || again.text != s.text || again.selStart != s.selStart || again.selEnd != s.selEnd)
            return VimOutcome(VimNotice.CHANGED)
        if (p.start != p.end || p.replacement.isNotEmpty()) port.replace(p.start, p.end, p.replacement, p.caret) else port.select(p.caret, p.caret) // nothing to change (dd on an empty field)
        if (p.removed != null) {
            if (rules.registerAllowed()) { registerText = p.removed; registerLinewise = p.removedLinewise } else clearRegister()
        }
        mode = if (p.insert) VimMode.INSERT else VimMode.NORMAL
        anchor = -1
        shownSelection = null
        expectedCaret = p.caret
        return VimOutcome()
    }

    /** dd, cc, yy: [count] lines from the cursor line. */
    private fun doubled(o: Char): VimOutcome {
        val n = countGiven()
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val count = n ?: 1
        val line = s.b.lineNumber(c)
        val last = s.b.lineCount() - 1
        if (count > 1 && line >= last) return fail()
        val endLine = minOf(line + count - 1, last)
        return operate(s, o, Rng(s.b.lineStart(c), s.b.lineEnd(s.b.startOfLine(endLine)), true), null, c)
    }

    // ---- insert commands, replace, paste ------------------------------------------------------------------------------

    private fun insertCommand(k: Char): VimOutcome {
        clearPending()
        wantCol = null
        val s = snapshot()
        if (s == null) {
            // no text: i is where the caret stands; a, A, I use the cursor keys
            when (k) {
                'i' -> {}
                'a' -> port.navigate(VimNav.RIGHT, 1)
                'A' -> port.navigate(VimNav.LINE_END, 1)
                'I' -> port.navigate(VimNav.LINE_START, 1)
                else -> return VimOutcome(failure)
            }
            mode = VimMode.INSERT
            return VimOutcome()
        }
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        when (k) {
            'i' -> return insertAt(c)
            'a' -> return insertAt(if (c < b.lineEnd(c)) b.next(c) else c)
            'A' -> return insertAt(b.lineEnd(c))
            'I' -> {
                var i = b.lineStart(c)
                while (b.isBlankAt(i)) i++
                return insertAt(i)
            }
        }
        // o and O add a line
        val at = if (k == 'o') b.lineEnd(c) else b.lineStart(c)
        return apply(s, Plan(at, at, "\n", if (k == 'o') at + 1 else at, null, false, true))
    }

    private fun insertAt(at: Int): VimOutcome {
        port.select(at, at)
        mode = VimMode.INSERT
        return VimOutcome()
    }

    private fun replaceChars(ch: Int): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        var end = c
        for (i in 0 until n) {
            if (end >= b.lineEnd(end)) return fail() // fewer characters than the count: Vim does nothing
            end = b.next(end)
        }
        val text = String(Character.toChars(ch)).repeat(n)
        return apply(s, Plan(c, end, text, c + text.length - Character.charCount(ch), null, false, false))
    }

    private fun paste(before: Boolean): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        val reg = registerText
        if (reg == null) return VimOutcome(if (rules.registerAllowed()) VimNotice.REGISTER_EMPTY else VimNotice.REGISTER_OFF)
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        wantCol = null
        if (registerLinewise) {
            val body = reg.removeSuffix("\n")
            val block = (1..n).joinToString("\n") { body }
            val plan: Plan
            if (before) {
                val at = b.lineStart(c)
                val newText = b.text.substring(0, at) + block + "\n" + b.text.substring(at)
                plan = Plan(at, at, block + "\n", VimBuf(newText).firstNonBlank(at), null, false, false)
            } else if (b.lineEnd(c) < b.length) {
                val at = b.lineEnd(c) + 1
                val newText = b.text.substring(0, at) + block + "\n" + b.text.substring(at)
                plan = Plan(at, at, block + "\n", VimBuf(newText).firstNonBlank(at), null, false, false)
            } else {
                val at = b.length
                val newText = b.text + "\n" + block
                plan = Plan(at, at, "\n" + block, VimBuf(newText).firstNonBlank(at + 1), null, false, false)
            }
            return apply(s, plan)
        }
        val at = if (before || c >= b.lineEnd(c)) c else b.next(c)
        val block = reg.repeat(n)
        val newText = b.text.substring(0, at) + block + b.text.substring(at)
        val nb = VimBuf(newText)
        val caret = if ('\n' in block) at else nb.prev(at + block.length)
        return apply(s, Plan(at, at, block, nb.normal(caret), null, false, false))
    }

    // ---- Visual mode --------------------------------------------------------------------------------------------------

    private fun toggleVisual(target: VimMode): VimOutcome {
        clearPending()
        if (mode == target) return leaveVisual(null).let { VimOutcome() }
        if (mode != VimMode.NORMAL) { // v and V switch between the two
            mode = target
            val s = snapshot() ?: return VimOutcome(failure)
            if (shownSelection != s.selStart to s.selEnd) return leaveVisual(null).let { VimOutcome(VimNotice.CHANGED) }
            showVisual(s.b)
            return VimOutcome()
        }
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        anchor = c
        visualCursor = c
        mode = target
        wantCol = null
        showVisual(s.b)
        return VimOutcome()
    }

    private fun swapVisualEnds(): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        if (cursorOf(s) == null) return VimOutcome(VimNotice.CHANGED)
        val t = anchor
        anchor = visualCursor
        visualCursor = t
        wantCol = null
        showVisual(s.b)
        return VimOutcome()
    }

    /** The field selection that shows the Visual selection: the characters from the anchor to the cursor, both included. */
    private fun displayOf(b: VimBuf): Pair<Int, Int> {
        val lo = minOf(anchor, visualCursor)
        val hi = maxOf(anchor, visualCursor)
        if (mode == VimMode.VISUAL_LINE) return b.lineStart(lo) to minOf(b.lineEnd(hi) + 1, b.length)
        val end = if (hi < b.lineEnd(hi)) b.next(hi) else minOf(hi + 1, b.length)
        return lo to end
    }

    private fun showVisual(b: VimBuf) {
        val d = displayOf(b)
        port.select(d.first, d.second)
        shownSelection = d
    }

    private fun leaveVisual(s: Snap?): VimOutcome {
        val cursor = visualCursor
        mode = VimMode.NORMAL
        shownSelection = null
        clearPending()
        val text = (s?.text ?: (port.read() as? VimRead.Text)?.text)
        if (text != null && cursor >= 0) {
            val at = VimBuf(text).normal(cursor.coerceIn(0, text.length))
            port.select(at, at)
            expectedCaret = at
        }
        anchor = -1
        visualCursor = -1
        return VimOutcome()
    }

    private fun visualOperator(o: Char): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val lo = minOf(anchor, visualCursor)
        val hi = maxOf(anchor, visualCursor)
        val linewise = mode == VimMode.VISUAL_LINE
        val rng = if (linewise) Rng(b.lineStart(lo), b.lineEnd(hi), true) else Rng(shown.first, shown.second, false)
        // Vim: after a linewise yank the cursor stays where it is when it moved to an earlier line, else it goes to column 0 of the first line
        val yankAt = if (linewise && b.lineNumber(visualCursor) < b.lineNumber(anchor)) visualCursor else if (linewise) b.lineStart(lo) else rng.start
        mode = VimMode.NORMAL
        shownSelection = null
        anchor = -1
        visualCursor = -1
        if (o == 'y') {
            if (!rules.registerAllowed()) { port.select(shown.first, shown.first); expectedCaret = shown.first; return VimOutcome(VimNotice.REGISTER_OFF) }
            registerText = if (linewise) linesText(b, rng) else b.text.substring(rng.start, rng.end)
            registerLinewise = linewise
            val at = b.normal(yankAt)
            port.select(at, at)
            expectedCaret = at
            return VimOutcome()
        }
        // the selection is the range: leave Visual mode first so that the edit is checked against it
        return operate(s, o, rng, null, lo)
    }

    // ---- text objects -------------------------------------------------------------------------------------------------

    private fun textObject(kind: Char, key: Int): VimOutcome {
        val o = op
        val n = countGiven() ?: 1
        clearPending()
        if (o == null) return VimOutcome()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        if (n > 1) return fail() // a count on a text object (it goes over several words and lines in Vim) is not in the first version
        val range = VimObjects.find(s.b, c, kind == 'a', key, n) ?: return fail()
        return operate(s, o, Rng(range.first, range.second, false), null, c)
    }

    companion object {
        /** The most characters read from a field: a longer field is not edited (about 20 pages of text). */
        const val MAX_CHARS = 20_000
        const val MAX_COUNT = 999
        const val ESC = 27
        /** Ctrl-R, redo. */
        const val CTRL_R = 18
        private const val GG = -2
        private val ARG_PREFIXES = setOf('f', 'F', 't', 'T', 'r')
    }
}
