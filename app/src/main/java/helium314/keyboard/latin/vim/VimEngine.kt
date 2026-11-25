// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * The Vim layer: a parser and state machine for Normal, Visual (character, line and block) and command-line input, working on the text of
 * the field through a [VimPort]. The keys of these modes never reach the typing path, so nothing is learned, suggested or remembered by them.
 *
 * Text safety: every edit is computed from the text the port read for this command, and the field is read again just before the
 * edit; if it cannot be read, is a part of the field, is longer than [MAX_CHARS], or changed in between (text or selection), the
 * command does nothing and returns a [VimNotice]. A range is never widened. Pure cursor motions may use cursor keys when the text
 * cannot be read. One command is one text change: a macro, a repeat with ".", a command line with :g or :normal run on a copy of the text
 * ([MemPort]) and only the difference to the field is written, once. A Visual block insert that must pad a short line computes the padding
 * and the insert together and writes them once, when the insert ends.
 *
 * Bounds: one budget ([Run]) covers every command that runs several steps (a macro, a repeat, a command line with :g or :normal): keys, ex
 * steps, time. No text that a command computes may be longer than [MAX_CHARS], and the size is checked before a larger string is built. At
 * a limit the command stops with a [VimNotice] and the field is as it was (a play or an ex line is computed on a copy and not written). Executions
 * nested in one another (a macro in a macro, :normal, @:, @@, "." in a macro, :g with :normal) are at most [MAX_NEST] levels deep, and the text copies
 * that executions two or more levels deep build add up to at most [MAX_RUN_CHARS] characters in one command: no input makes the layer throw an
 * OutOfMemoryError or a StackOverflowError (VimStressTest runs every nested form under a small heap and stack).
 *
 * Memory: everything the layer keeps between commands is in [VimMemory] (registers, macros, the "." record, marks, the search and the
 * substitute state, the histories), which asks [VimRules.registerAllowed] again at every write; [clearMemory] forgets all of it (screen
 * lock, wipe, the setting turned off). The text typed in insert mode does not pass through the engine, so an insert session carries one flag, "capture
 * allowed throughout" (see [VimMemory]), which [captureChanged] clears for good; a session whose flag is cleared keeps nothing. No other class of the
 * package keeps field text or typed text across commands. The system clipboard
 * (the + and * registers) is reached only through [VimPort.clipboardRead] and [VimPort.clipboardWrite], the keyboard's own paste and copy owners.
 */
class VimEngine(private val port: VimPort, private val rules: VimRules) {
    var mode: VimMode = VimMode.INSERT
        private set

    // the I/O of the commands: the field, or a copy of its text while a play, a repeat or :normal runs
    private var io: VimPort = port

    // ---- the parser state (transient: dropped by reset) ---------------------------------------------------------------------
    private var count1 = 0
    private var count2 = 0
    private var op: Char? = null
    private var prefix: Char? = null
    private var regName: Char? = null
    private var cmdCount: Int? = null
    private var cmdReg: Char? = null
    private var anchor = -1
    private var visualCursor = -1
    private var blockDollar = false
    /** The right column of a block that a repeat set up; it can be beyond the end of a short line, where the cursor cannot be (null: from the cursor). */
    private var blockRight: Int? = null
    private var shownSelection: Pair<Int, Int>? = null
    private var wantCol: Int? = null
    private var expectedCaret = -1
    private var cmd: CmdLine? = null
    private var ins: InsertSession? = null
    private var insertPrefix: Char? = null
    private var returnToInsert = false
    private var returnAtEol = false
    private var exVisualLines: Pair<Int, Int>? = null
    private val cmdKeys = ArrayList<Int>()
    private var cmdDropped = false
    private var cmdChanged = false
    private var visualDot: VisualInfo? = null

    // ---- memory: everything that is kept between commands is in [memory], which asks the capture rule at every write ------------
    private val memory = VimMemory(rules)
    /** The marks were forgotten because the text changed behind the engine's back (the notice then says "invalid", not "not set"). */
    private var marksLost = false
    /** The text became empty because its last line was deleted (Vim then has an empty buffer, where "dd" has nothing to delete). */
    private var bufferEmptied = false

    // ---- plays ---------------------------------------------------------------------------------------------------------------------
    private var replaying = false
    private var inTx = false
    /** How many executions are open inside one another: a play is one, every :normal run is one more. At most [MAX_NEST]; 0 for a plain command. */
    private var nest = 0
    private val playQueue = ArrayDeque<Int>()
    /** The budget of the command that is running (a play or an ex line), null for a plain command. */
    private var run: Run? = null
    private var playStopped: VimNotice? = null // an undo inside a play: the play stops and what it did so far is written
    private var playFailure: VimNotice? = null // the notice of the command that stopped a play

    /** The unnamed register (memory only), for the tests. */
    internal val register: String? get() = memory.unnamed?.let { if (it.type == RegType.LINE) it.text + "\n" else it.text }
    internal val registerIsLinewise: Boolean get() = memory.unnamed?.type == RegType.LINE
    internal val registers: VimMemory get() = memory

    /** True while the next key is a character argument (f F t T r, a register or mark name, q, @), a register after Ctrl-R, or a command line is typed. */
    val wantsChar: Boolean
        get() = (mode != VimMode.INSERT && prefix in ARG_PREFIXES) || insertPrefix != null || cmd != null

    /** The count, register, operator and prefix typed so far ("2d3"), for the status line. */
    val pending: String
        get() = buildString {
            if (count1 > 0) append(count1)
            regName?.let { append('"').append(it) }
            op?.let { append(it) }
            if (count2 > 0) append(count2)
            prefix?.let { append(it) }
        }

    /** The command line being typed, with its ":" "/" or "?", or null. */
    val cmdline: String? get() = cmd?.let { it.kind + it.text.toString() }

    /** The register a macro is being recorded into, or null. */
    val recordingRegister: Char? get() = memory.recording

    /** Forgets everything the layer remembers: registers, macros, the "." record, marks, the search and substitute state and the histories. */
    fun clearMemory() {
        memory.clear()
        marksLost = false
        lastVisualMode = VimMode.VISUAL
        lastVisualCursorAtStart = false
        lastVisualDollar = false
    }

    /**
     * The adapter tells the engine that the capture rule may have turned to "no" or that the field or the session changed (the incognito switch, a settings
     * reload, a field that starts or finishes). While the user types in insert mode the engine is not called, so this is the only way it learns of a "no"
     * in between: the insert session that is open keeps nothing, the recording and the "." record that wait for its text are dropped.
     */
    fun captureChanged() {
        memory.taint()
    }

    /** The field ended or changed: the typing keyboard is back and nothing is pending. Marks belong to a field and go with it. */
    fun reset() {
        io = port // a play that was cut short (an exception went to the guard) leaves nothing behind
        inTx = false
        replaying = false
        nest = 0
        run = null
        playQueue.clear()
        playStopped = null
        mode = VimMode.INSERT
        resetParser()
        wantCol = null
        expectedCaret = -1
        memory.marksClear()
        marksLost = false
        bufferEmptied = false
    }

    private fun captureOk() = memory.mayKeep()

    // ---- entry points --------------------------------------------------------------------------------------------------------------

    /** The Esc key of the typing keyboard (and the Esc key of the Vim layouts). */
    fun escape(): VimOutcome {
        syncMemory()
        val was = memory.recording
        val out = escapeInner()
        return finishKey(was, ESC, out)
    }

    /**
     * Before every key: where the capture rule now says no, a recording stops and the keys typed so far for the command that is being built
     * (they could hold typed text) are dropped.
     */
    private fun syncMemory() {
        memory.sync()
        if (!captureOk()) {
            if (cmdKeys.isNotEmpty()) cmdDropped = true // the rest of this command is no record, even if the decision turns back
            cmdKeys.clear()
        }
    }

    /** After a key: it joins the macro that is being recorded, or the recording is dropped (the user is told once). */
    private fun finishKey(was: Char?, code: Int, out: VimOutcome): VimOutcome {
        if (was != null && memory.recording != null && !replaying && !memory.recordKey(code)) return VimOutcome(VimNotice.RECORDING_DROPPED)
        if (memory.takeDropped()) return VimOutcome(VimNotice.RECORDING_DROPPED)
        return out
    }

    /**
     * A key of Normal, Visual or command-line mode: an ASCII character of the Vim layouts, a control code ([CTRL_R]...), [ENTER], [BACKSPACE],
     * [HISTORY_PREVIOUS] or [HISTORY_NEXT] in the command line. In [VimMode.INSERT] only the control keys of insert mode are handled.
     */
    fun key(rawCode: Int): VimOutcome {
        // the Ctrl keys that sit in popups of the layouts have codes of their own (a popup key has no control code)
        val code = when (rawCode) { KEY_CTRL_A -> CTRL_A; KEY_CTRL_X -> CTRL_X; KEY_CTRL_V -> CTRL_V; else -> rawCode }
        syncMemory()
        if (mode == VimMode.INSERT) return insertKey(code)
        if (!rules.available()) {
            reset()
            return VimOutcome(VimNotice.UNAVAILABLE)
        }
        val was = memory.recording
        val out = dispatch(code)
        return finishKey(was, code, out)
    }

    private fun dispatch(code: Int): VimOutcome {
        val line = cmd
        if (line != null) {
            val out = cmdKey(line, code)
            afterCommand()
            return out
        }
        if (code == ESC) return escapeInner()
        val out = process(code)
        afterCommand()
        return out
    }

    /** Called after every key: the command is complete when nothing is pending; then a change becomes the "." record. */
    private fun afterCommand() {
        if (!idle()) return
        if (cmdChanged && !replaying && !cmdDropped) memory.dotSet(cmdReg, cmdCount, cmdKeys, visualDot, openForInsert = mode == VimMode.INSERT)
        cmdKeys.clear()
        cmdDropped = false
        cmdChanged = false
        visualDot = null
        if (returnToInsert && mode == VimMode.NORMAL) { // Ctrl-O: one command, then typing goes on
            returnToInsert = false
            val s = snapshot()
            if (s != null && s.selStart == s.selEnd) {
                var at = s.selStart
                // a cursor on the last character that was after it before the command, or that "$" sent there, goes after it again
                if ((returnAtEol || wantCol == Int.MAX_VALUE) && at == s.b.lastChar(at) && at < s.b.lineEnd(at)) at = s.b.lineEnd(at)
                io.select(at, at)
                beginInsert(s.text, at, 1, 'i')
                mode = VimMode.INSERT
            }
        }
    }

    /** Where the cursor lands: on a character, or, while a Ctrl-O command runs (insert mode goes on after it), anywhere in the line including its end. */
    private fun landing(b: VimBuf, p: Int) = if (returnToInsert) b.snap(p) else b.normal(p)

    private fun idle() = op == null && prefix == null && count1 == 0 && count2 == 0 && regName == null && cmd == null && insertPrefix == null

    // ---- recording --------------------------------------------------------------------------------------------------------------------

    private fun startRecording(name: Char): VimOutcome {
        if (!memory.recordStart(name)) return VimOutcome(VimNotice.REGISTER_OFF)
        return VimOutcome()
    }

    private fun stopRecording(): VimOutcome = when (memory.recordStop()) {
        Recorded.DROPPED -> VimOutcome(VimNotice.RECORDING_DROPPED)
        Recorded.FULL -> VimOutcome(VimNotice.REGISTER_FULL)
        else -> VimOutcome()
    }

    // ---- Esc and the way out of insert mode ----------------------------------------------------------------------------------------

    private fun escapeInner(): VimOutcome {
        if (!rules.available()) {
            reset()
            return VimOutcome(VimNotice.UNAVAILABLE)
        }
        if (cmd != null) {
            cmd = null
            clearPending()
            return VimOutcome()
        }
        insertPrefix = null
        returnToInsert = false
        when (mode) {
            VimMode.INSERT -> return leaveInsert()
            VimMode.NORMAL -> {
                if (!idle()) { clearPending(); return VimOutcome() }
                val s = (io.read() as? VimRead.Text) ?: return VimOutcome()
                if (s.selStart != s.selEnd) {
                    val at = VimBuf(s.text).normal(s.selStart)
                    io.select(at, at)
                    expectedCaret = at
                }
            }
            VimMode.VISUAL, VimMode.VISUAL_LINE, VimMode.VISUAL_BLOCK -> leaveVisual(null)
        }
        clearPending()
        return VimOutcome()
    }

    /** One typing session after an insert command: where it started and what the text was then, so that what was typed can be told afterwards. */
    private class InsertSession(val baseText: String, val start: Int, val count: Int, val kind: Char, val block: BlockInsert?)

    /**
     * A block insert (I, A, c in Visual block): the text typed on the first line goes to the other lines at the same column. [pad] is the number of
     * blanks that a first line shorter than the column gets in front of what is typed: it is written together with the other lines when the insert
     * ends, never before (a padding committed ahead would leave a text that is neither the old one nor the result).
     */
    private class BlockInsert(val firstLine: Int, val lastLine: Int, val col: Int, val append: Boolean, val dollar: Boolean, val left: Int = col, val pad: Int = 0)

    private fun beginInsert(text: String, start: Int, count: Int, kind: Char, block: BlockInsert? = null) {
        ins = InsertSession(text, start, count, kind, block)
        memory.insertBegin() // what is typed from here on is kept only if capture is allowed now and stays allowed until the session ends
    }

    /** What was typed since [session] began, or null when the text is not "the old text with something inserted at the start point". */
    private fun typedSince(session: InsertSession, text: String, caret: Int): String? {
        val old = session.baseText
        val start = session.start
        if (caret < start || start > old.length) return null
        if (text.length - old.length != caret - start) return null
        if (!text.regionMatches(0, old, 0, start)) return null
        if (!text.regionMatches(caret, old, start, old.length - start)) return null
        return text.substring(start, caret)
    }

    private fun leaveInsert(): VimOutcome {
        val session = ins
        ins = null
        mode = VimMode.NORMAL
        clearPending()
        wantCol = null
        val r = io.read()
        if (r !is VimRead.Text) {
            memory.dotCloseInsert()
            expectedCaret = -1
            return VimOutcome()
        }
        var text = r.text
        var caret = maxOf(r.selStart, r.selEnd).coerceIn(0, text.length)
        var failedNotice: VimNotice? = null
        if (session != null) {
            val typed = typedSince(session, text, caret)
            memory.marksAfterInsert(session.baseText, session.start, typed, text)
            if (typed == null) {
                if (!replaying) { memory.dotBroken(); memory.recordBroken() } else memory.dotCloseInsert()
            } else {
                // a session that capture was ever not allowed in (the incognito switch while typing) keeps none of these, whatever the rule says now
                memory.setLastTyped(typed)
                if (!replaying) { memory.dotTyped(typed); memory.recordTyped(typed) } else memory.dotCloseInsert()
                memory.insertMarks(text, session.start, caret)
                followRefused = false
                val follow = followUp(session, typed, text, caret)
                if (followRefused) { // the count's repeats were too long: they are not written, and a play that did this stops
                    followRefused = false
                    failedNotice = if (inTx) VimNotice.PLAY_LIMIT else VimNotice.TOO_LONG
                    if (inTx) abortRun(VimNotice.PLAY_LIMIT)
                }
                if (follow != null) {
                    if (text.length.toLong() - (follow.end - follow.start) + follow.replacement.length > MAX_CHARS) {
                        failedNotice = if (inTx) VimNotice.PLAY_LIMIT else VimNotice.TOO_LONG
                        if (inTx) abortRun(VimNotice.PLAY_LIMIT)
                    } else if (follow.replacement.isNotEmpty() && !rules.accepts(follow.replacement)) {
                        failedNotice = VimNotice.NOT_ACCEPTED
                    } else {
                        val again = io.read()
                        if (again is VimRead.Text && again.text == text) {
                            val newText = text.substring(0, follow.start) + follow.replacement + text.substring(follow.end)
                            io.replace(follow.start, follow.end, follow.replacement, follow.caret)
                            if (memory.marksBase == text) memory.marksRemapEdit(text, follow.start, follow.end, follow.replacement)
                            text = newText
                            caret = follow.caret
                        } else failedNotice = VimNotice.CHANGED
                    }
                }
            }
        } else memory.dotCloseInsert()
        // Vim leaves insert mode one character to the left, unless at the start of the line; after a block insert the cursor is at the top left of the block
        val b = VimBuf(text)
        val blockCols = session?.block?.takeIf { session.kind != 'c' }
        val at = if (blockCols != null) b.normal(b.atColumn(b.startOfLine(blockCols.firstLine), blockCols.left)) else b.normal(b.prev(caret))
        io.select(at, at)
        expectedCaret = at
        return VimOutcome(failedNotice)
    }

    private class Follow(val start: Int, val end: Int, val replacement: String, val caret: Int)

    /** The one change that finishes an insert: the count's repeats, the characters that replace mode overwrites, the other lines of a block. */
    private fun followUp(session: InsertSession, typed: String, text: String, caret: Int): Follow? {
        if (typed.isEmpty() && session.block == null) return null
        val b = VimBuf(text)
        val block = session.block
        if (block != null) return blockFollow(session, block, typed, text)
        if (session.kind == 'R') {
            // replace mode: the characters the typing pushed on are taken away, as many as were typed (and the repeats), to the end of the line
            val n = clusters(typed.replace("\n", "")) * session.count // a line break replaces nothing
            var end = caret
            var k = 0
            val limit = b.lineEnd(caret)
            while (k < n && end < limit) { end = b.next(end); k++ }
            if (typed.length.toLong() * (session.count - 1) + text.length > MAX_CHARS) { followRefused = true; return null } // before the repeats are built
            val extra = typed.repeat(session.count - 1)
            if (end == caret && extra.isEmpty()) return null
            return Follow(caret, end, extra, caret + extra.length)
        }
        if (session.count > 1) {
            val one = if (session.kind == 'o' || session.kind == 'O') "\n$typed" else typed
            // the size is checked before the repeats are built: a count of 999 times a long text must not be made first
            if (one.length.toLong() * (session.count - 1) + text.length > MAX_CHARS) { followRefused = true; return null }
            val extra = one.repeat(session.count - 1)
            return Follow(caret, caret, extra, caret + extra.length)
        }
        return null
    }

    /** The repeats of an insert would make the text longer than the window: they were not built. */
    private var followRefused = false

    private fun clusters(s: String): Int {
        val b = VimBuf(s)
        var i = 0
        var n = 0
        while (i < s.length) { i = if (s[i] == '\n') i + 1 else b.next(i); n++ } // a line break is one character, and VimBuf stops at it
        return n
    }

    // ---- insert-mode keys (Ctrl-R, Ctrl-W, Ctrl-U, Ctrl-H, Ctrl-O) ---------------------------------------------------------------------

    private fun insertKey(code: Int): VimOutcome {
        val pending = insertPrefix
        if (pending != null) {
            insertPrefix = null
            return insertRegister(code)
        }
        if (code != CTRL_R && code != CTRL_W && code != CTRL_U && code != CTRL_H && code != CTRL_O) return VimOutcome(handled = false)
        if (!rules.available()) {
            reset()
            return VimOutcome(VimNotice.UNAVAILABLE)
        }
        return when (code) {
            CTRL_R -> { insertPrefix = 'r'; VimOutcome() }
            CTRL_O -> ctrlO()
            else -> insertDelete(code)
        }
    }

    private fun insertRegister(code: Int): VimOutcome {
        if (code <= 0 || code > 0xFFFF) return VimOutcome()
        val name = code.toChar()
        val reg: Reg? = if (name == '+' || name == '*') io.clipboardRead()?.let { clipboardReg(it) } else memory.get(name)
        if (reg == null) return VimOutcome(if (name == '+' || name == '*') VimNotice.CLIPBOARD_EMPTY else VimNotice.REGISTER_EMPTY)
        val s = snapshot() ?: return VimOutcome(failure)
        val caret = s.selStart
        if (s.selStart != s.selEnd) return VimOutcome(VimNotice.SELECTED)
        val text = if (reg.type == RegType.LINE) reg.text + "\n" else reg.text
        return insertText(s, caret, caret, text)
    }

    /** Inserts [text] in place of [start, end) while typing goes on (one change; the caret goes after the text). */
    private fun insertText(s: Snap, start: Int, end: Int, text: String): VimOutcome {
        if (text.isNotEmpty() && !rules.accepts(text)) return VimOutcome(VimNotice.NOT_ACCEPTED)
        if (s.text.length.toLong() - (end - start) + text.length > MAX_CHARS) return tooBig()
        val again = io.read()
        if (again !is VimRead.Text || again.text != s.text || again.selStart != s.selStart || again.selEnd != s.selEnd) return VimOutcome(VimNotice.CHANGED)
        io.replace(start, end, text, start + text.length)
        return VimOutcome()
    }

    private fun insertDelete(code: Int): VimOutcome {
        val s = snapshot() ?: return VimOutcome(failure)
        if (s.selStart != s.selEnd) return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        val caret = s.selStart
        val session = ins
        val ls = b.lineStart(caret)
        // typed text is taken away first: back to the point where this insert started, if it is before the caret in the same line
        val stop = if (session != null && session.start in ls until caret) session.start else ls
        var from: Int
        when (code) {
            CTRL_H -> from = if (caret > ls) b.prev(caret) else if (caret > 0) caret - 1 else caret
            CTRL_U -> from = if (caret > ls) stop else if (caret > 0) caret - 1 else caret
            else -> { // CTRL_W
                if (caret <= ls) from = if (caret > 0) caret - 1 else caret
                else {
                    var i = caret
                    // white space, then the run of characters of one class
                    while (i > stop && b.isBlankAt(b.prev(i))) i = b.prev(i)
                    if (i > stop) {
                        val k = VimBuf.classOf(b.codePointAt(b.prev(i)), false)
                        while (i > stop && VimBuf.classOf(b.codePointAt(b.prev(i)), false) == k && !b.isBlankAt(b.prev(i))) i = b.prev(i)
                    }
                    from = i
                }
            }
        }
        if (from >= caret) return VimOutcome()
        return insertText(s, from, caret, "")
    }

    private fun ctrlO(): VimOutcome {
        val s = snapshot() ?: return VimOutcome(failure)
        if (s.selStart != s.selEnd) return VimOutcome(VimNotice.SELECTED)
        finishInsertForOneCommand(s)
        return VimOutcome()
    }

    /** Ctrl-O: the insert so far is a command of its own; one Normal command follows, then insert mode again. */
    private fun finishInsertForOneCommand(s: Snap) {
        val session = ins
        ins = null
        if (session != null) memory.marksAfterInsert(session.baseText, session.start, null, s.text)
        memory.dotCloseInsert()
        mode = VimMode.NORMAL
        clearPending()
        val b = s.b
        returnAtEol = s.selStart >= b.lineEnd(s.selStart) && b.lineEnd(s.selStart) > b.lineStart(s.selStart)
        returnToInsert = true
        val at = b.normal(s.selStart)
        io.select(at, at)
        expectedCaret = at
    }

    // ---- parsing ------------------------------------------------------------------------------------------------------------------

    private fun clearPending() {
        cmdCount = countGiven()
        cmdReg = regName
        dropPending()
    }

    /** Forgets the pending count, register, operator and prefix without keeping them for the command that is being run. */
    private fun dropPending() {
        count1 = 0
        count2 = 0
        op = null
        prefix = null
        regName = null
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
            if (pre != '"') cmdKeys.add(key)
            return afterPrefix(pre, key)
        }
        val visual = mode != VimMode.NORMAL
        if (key in '1'.code..'9'.code || (key == '0'.code && (if (op == null) count1 else count2) > 0)) {
            val digit = key - '0'.code
            if (op == null) count1 = (count1 * 10 + digit).coerceAtMost(MAX_COUNT) else count2 = (count2 * 10 + digit).coerceAtMost(MAX_COUNT)
            return VimOutcome()
        }
        if (key != '"'.code) cmdKeys.add(key)
        return when (key) {
            'h'.code, 'l'.code, 'j'.code, 'k'.code, 'w'.code, 'W'.code, 'b'.code, 'B'.code, 'e'.code, 'E'.code,
            '0'.code, '^'.code, 'G'.code -> motionKey(key)
            '$'.code -> { if (visual && mode == VimMode.VISUAL_BLOCK) blockDollar = true; motionKey(key) }
            '%'.code -> if (countGiven() != null && op == null) percentLine() else motionKey(key)
            '{'.code, '}'.code, '('.code, ')'.code -> motionKey(key)
            ';'.code, ','.code -> repeatFind(key == ','.code)
            'g'.code, 'f'.code, 'F'.code, 't'.code, 'T'.code, 'z'.code -> { prefix = key.toChar(); VimOutcome() }
            '"'.code -> { prefix = '"'; VimOutcome() }
            '\''.code, '`'.code -> { prefix = key.toChar(); VimOutcome() }
            'd'.code, 'c'.code, 'y'.code, '>'.code, '<'.code -> operatorKey(key.toChar())
            'x'.code -> shorthand('d', 'l'.code)
            'X'.code -> shorthand('d', 'h'.code)
            's'.code -> shorthand('c', 'l'.code)
            'D'.code -> shorthand('d', '$'.code)
            'C'.code -> shorthand('c', '$'.code)
            'S'.code -> shorthand('c', 'c'.code)
            'Y'.code -> shorthand('y', 'y'.code)
            'r'.code -> if (op != null) cancel() else { prefix = 'r'; VimOutcome() }
            'p'.code, 'P'.code -> if (op != null) cancel() else if (visual) visualPut(key == 'P'.code) else paste(before = key == 'P'.code)
            'i'.code, 'a'.code ->
                if (op != null || visual) { prefix = key.toChar(); VimOutcome() } else insertCommand(key.toChar())
            'I'.code, 'A'.code ->
                if (visual) { if (mode == VimMode.VISUAL_BLOCK) blockInsert(key == 'A'.code) else cancel() } else if (op != null) cancel() else insertCommand(key.toChar())
            'o'.code, 'O'.code ->
                if (visual) swapVisualEnds(key == 'O'.code) else if (op != null) cancel() else insertCommand(key.toChar())
            'R'.code -> if (visual || op != null) cancel() else insertCommand('R')
            'v'.code -> if (op != null) cancel() else toggleVisual(VimMode.VISUAL)
            'V'.code -> if (op != null) cancel() else toggleVisual(VimMode.VISUAL_LINE)
            CTRL_V -> if (op != null) cancel() else toggleVisual(VimMode.VISUAL_BLOCK)
            'u'.code -> when {
                op == 'u' -> doubled('u')
                visual -> visualOperator('u')
                op != null -> cancel()
                else -> undoRedo(false)
            }
            'U'.code -> when {
                op == 'U' -> doubled('U')
                visual -> visualOperator('U')
                else -> cancel()
            }
            '~'.code -> when {
                op == '~' -> doubled('~')
                visual -> visualOperator('~')
                op != null -> cancel()
                else -> tildeChars()
            }
            CTRL_R -> if (visual || op != null) cancel() else undoRedo(true)
            'J'.code -> if (op != null) cancel() else if (visual) visualJoin(true) else joinKey(true)
            CTRL_A, CTRL_X -> if (op != null) cancel() else if (visual) visualIncrement(key == CTRL_A, false) else increment(key == CTRL_A)
            '.'.code -> if (visual || op != null) cancel() else repeatDot()
            'q'.code -> if (visual || op != null) cancel() else if (memory.recording != null) { clearPending(); stopRecording() } else { prefix = 'q'; VimOutcome() }
            '@'.code -> if (visual || op != null) cancel() else { prefix = '@'; VimOutcome() }
            'm'.code -> if (visual || op != null) cancel() else { prefix = 'm'; VimOutcome() }
            'n'.code, 'N'.code -> searchRepeat(key == 'N'.code)
            '*'.code, '#'.code -> searchWord(forward = key == '*'.code, whole = true)
            '/'.code, '?'.code -> startCmdline(key.toChar())
            ':'.code -> if (op != null) cancel() else startCmdline(':')
            'H'.code, 'M'.code, 'L'.code, CTRL_D, CTRL_U, CTRL_F, CTRL_B, CTRL_E, CTRL_Y -> { clearPending(); VimOutcome(VimNotice.SCREEN_COMMAND) }
            else -> cancel()
        }
    }

    private fun afterPrefix(pre: Char, key: Int): VimOutcome = when (pre) {
        'g' -> afterG(key)
        'z' -> { clearPending(); VimOutcome(VimNotice.SCREEN_COMMAND) }
        'f', 'F', 't', 'T' ->
            if (key <= 0 || key == ESC || key == '\n'.code) cancel() else { memory.setLastFind(pre, key); findMotion(pre, key, isRepeat = false) }
        'r' -> if (key <= 0 || key == ESC || key == '\n'.code) cancel() else if (mode != VimMode.NORMAL) visualReplace(key) else replaceChars(key)
        'i', 'a' -> textObject(pre, key)
        '"' -> if (key > 0 && key <= 0xFFFF && registerNameOk(key.toChar())) { regName = key.toChar(); VimOutcome() } else cancel()
        'q' -> if (key in 'a'.code..'z'.code || key in 'A'.code..'Z'.code || key in '0'.code..'9'.code) { clearPending(); startRecording(key.toChar()) } else cancel()
        '@' -> playRegister(key)
        'm' -> setMarkKey(key)
        '\'', '`' -> markMotion(key, pre == '\'')
        '\u0001', '\u0002' -> markMotion(key, pre == '\u0001', noJump = true)
        else -> cancel()
    }

    private fun registerNameOk(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '"' || c == '-' || c == '_' || c == '+' || c == '*' || c == '.' || c == ':' || c == '/'

    private fun afterG(key: Int): VimOutcome = when (key) {
        'g'.code -> motionKey(GG)
        '*'.code, '#'.code -> searchWord(forward = key == '*'.code, whole = false)
        'J'.code -> if (op != null) cancel() else if (mode != VimMode.NORMAL) visualJoin(false) else joinKey(false)
        'v'.code -> if (op != null || mode != VimMode.NORMAL) cancel() else reselectVisual()
        'i'.code -> if (op != null || mode != VimMode.NORMAL) cancel() else insertAtLastInsert()
        '\''.code -> { prefix = '\u0001'; VimOutcome() } // g' and g` jump to a mark without setting the context mark
        '`'.code -> { prefix = '\u0002'; VimOutcome() }
        '~'.code, 'u'.code, 'U'.code -> {
            val k = key.toChar()
            when {
                mode != VimMode.NORMAL -> visualOperator(k)
                op == k -> doubled(k)
                op == null -> { op = k; VimOutcome() }
                else -> cancel()
            }
        }
        CTRL_A, CTRL_X -> if (mode != VimMode.NORMAL && op == null) visualIncrement(key == CTRL_A, true) else cancel()
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
            // in Visual mode x is d, X and D are linewise, s is c, S and C change the lines, Y yanks them
            return when (o to motion) {
                ('d' to 'l'.code) -> visualOperator('d')
                ('c' to 'l'.code) -> visualOperator('c')
                ('d' to 'h'.code) -> { if (mode != VimMode.VISUAL_BLOCK) visualLines(); visualOperator('d') } // in a block X is d
                ('d' to '$'.code) -> { visualLines(); visualOperator('d') }
                ('c' to 'c'.code), ('c' to '$'.code) -> { visualLines(); visualOperator('c') }
                ('y' to 'y'.code) -> { visualLines(); visualOperator('y') }
                else -> cancel()
            }
        }
        if (op != null) return cancel()
        op = o
        return if (motion == 'c'.code || motion == 'y'.code) doubled(o) else motionKey(motion)
    }

    /** X, D, Y, S, C in Visual mode act on whole lines (in a block, D goes to the end of each line). */
    private fun visualLines() {
        if (mode == VimMode.VISUAL_BLOCK) blockDollar = true else mode = VimMode.VISUAL_LINE
    }

    // ---- reading ------------------------------------------------------------------------------------------------------------------

    private class Snap(val b: VimBuf, val selStart: Int, val selEnd: Int) {
        val text get() = b.text
    }

    private var failure: VimNotice? = null

    private fun snapshot(): Snap? {
        failure = null
        return when (val r = io.read()) {
            is VimRead.Text -> {
                if (memory.marksForgetIfChanged(r.text)) marksLost = true
                Snap(VimBuf(r.text), r.selStart, r.selEnd)
            }
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

    // ---- motions ------------------------------------------------------------------------------------------------------------------

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
        if (pure && !inTx && (key == 'j'.code || key == 'k'.code)) { // by visual line: the editor knows where the wrapped lines are
            clearPending()
            wantCol = null
            io.navigate(if (key == 'j'.code) VimNav.DOWN else VimNav.UP, n ?: 1)
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
                'G'.code -> VimMotions.gotoLine(b, if (n == null) Int.MAX_VALUE else n - 1).let { Mv(it.pos, it.type, jump = true) }
                GG -> VimMotions.gotoLine(b, if (n == null) 0 else n - 1).let { Mv(it.pos, it.type, jump = true) }
                '%'.code -> VimMotions.matchPair(b, c)
                '{'.code -> VimMotions.paragraph(b, c, count, forward = false)
                '}'.code -> VimMotions.paragraph(b, c, count, forward = true)
                '('.code -> VimMotions.sentence(b, c, count, forward = false)
                ')'.code -> VimMotions.sentence(b, c, count, forward = true)
                else -> Mv(c, MType.EXCL, cancels = true)
            }
        }
    }

    /** N% : a line a part of the way into the text. */
    private fun percentLine(): VimOutcome {
        val n = countGiven() ?: return cancel()
        return runMotion('%'.code, n) { b, _, _ ->
            val lines = b.lineCount()
            if (n > 100) Mv(0, MType.LINE, cancels = true)
            else Mv(b.firstNonBlank(b.startOfLine(((n.toLong() * lines + 99) / 100 - 1).toInt().coerceIn(0, lines - 1))), MType.LINE, jump = true)
        }
    }

    private fun findMotion(type: Char, ch: Int, isRepeat: Boolean): VimOutcome {
        val n = countGiven() ?: 1
        return runMotion(null, n) { b, c, _ -> VimMotions.find(b, c, ch, type, n, isRepeat) }
    }

    private fun repeatFind(reverse: Boolean): VimOutcome {
        val last = memory.lastFind ?: return cancel()
        val type = if (reverse) last.first.let { if (it.isLowerCase()) it.uppercaseChar() else it.lowercaseChar() } else last.first
        return findMotion(type, last.second, isRepeat = true)
    }

    private fun runMotion(key: Int?, n: Int?, compute: (VimBuf, Int, Boolean) -> Mv): VimOutcome {
        val pending = op
        val visual = mode != VimMode.NORMAL
        val s = snapshot()
        if (s == null) {
            val notice = failure
            val nav = if (key != null && pending == null && !visual && !inTx) fallbackNav(key) else null
            clearPending()
            if (nav == null) return VimOutcome(notice)
            io.navigate(nav, n ?: 1) // a pure cursor motion with the cursor keys when the text cannot be read
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
        // the context mark is set by a jump, but only if it went somewhere (Vim's checkpcmark: a jump that ends where it began leaves the old mark)
        if (mv.jump && !mv.cancels && s.b.normal(mv.pos) != c) memory.markSet('\'', s.text, c)
        if (pending != null) {
            val o = pending
            clearPending()
            if (mv.cancels) return fail(mv.notice)
            val rng = rangeOf(s.b, c, mv, o) ?: return fail()
            return operate(s, o, rng, mv, c, big = mv.big)
        }
        clearPending()
        wantCol = newWant
        if (visual) {
            visualCursor = mv.pos
            blockRight = null
            showVisual(s.b)
        } else {
            val at = s.b.normal(mv.pos) // a motion that ends beyond the last character puts the cursor on it
            io.select(at, at)
            expectedCaret = at
        }
        return if (mv.cancels) fail(mv.notice) else VimOutcome()
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
        return exclusiveRange(b, s, e, o)
    }

    /** The range of an exclusive motion from [s] to [e] with Vim's rules ("o_v"): an end in column 0 of a later line ends at the end of the line before. */
    private fun exclusiveRange(b: VimBuf, s: Int, e0: Int, o: Char): Rng {
        var e = e0
        // an exclusive motion that ends in column 0 of a later line ends at the end of the line before; if the start is at or before the
        // first non-blank of its line, the motion is linewise
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

    private fun regOf(b: VimBuf, r: Rng): Reg = if (r.linewise) Reg(b.text.substring(r.start, r.end), RegType.LINE) else Reg(b.text.substring(r.start, r.end), RegType.CHAR)

    private fun operate(s: Snap, o: Char, rng: Rng, mv: Mv?, cursor: Int, big: Boolean = false, inclusive: Boolean = mv?.type == MType.INCL, startPos: Int? = null, target: Int? = null): VimOutcome {
        wantCol = null
        val b = s.b
        val rn = cmdReg
        if ((o == 'y' || o == 'd' || o == 'c') && unwritable(rn)) return fail() // ". : / are read-only: Vim beeps and does nothing
        when (o) {
            'y' -> return yankRange(s, rng, if (startPos != null) b.normal(startPos) else if (rng.linewise) b.normal(minOf(cursor, mv?.pos ?: cursor)) else b.normal(rng.start), rn)
            '~', 'u', 'U' -> return caseRange(s, o, rng, cursor, mv, startPos, target)
            '>', '<' -> return shiftRange(s, o == '<', rng, 1)
        }
        val change = o == 'c'
        if (!rng.linewise && rng.start == rng.end) { // nothing to take: "d" does nothing, "c" starts typing where it stands
            if (change) {
                // an inclusive motion over nothing (iw or $ on an empty line) still yanks: the registers are emptied; an exclusive one leaves them
                if (inclusive) deleteInto(rn, Reg("", RegType.CHAR), false, big)
                io.select(rng.start, rng.start)
                beginInsert(s.text, rng.start, 1, 'c')
                mode = VimMode.INSERT
                cmdChanged = true
            }
            return VimOutcome()
        }
        if (rng.linewise && b.length == 0 && !change && bufferEmptied) return fail() // the last line was deleted before: Vim has nothing to delete and keeps its registers
        val removed = regOf(b, rng)
        val plan: Plan = if (!rng.linewise) {
            val newText = b.text.substring(0, rng.start) + b.text.substring(rng.end)
            Plan(rng.start, rng.end, "", if (change) rng.start else landing(VimBuf(newText), rng.start), insert = change, insertKind = 'c')
        } else if (change) {
            Plan(rng.start, rng.end, "", rng.start, insert = true, insertKind = 'c')
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
            Plan(start, end, "", VimBuf(newText).firstNonBlank(landing), insert = false)
        }
        val multi = rng.linewise || '\n' in removed.text
        val wholeText = rng.linewise && !change && rng.start == 0 && rng.end >= b.length
        return apply(s, plan, markStart = rng.start, markLine = rng.linewise && !change) { _ ->
            if (wholeText) bufferEmptied = true
            deleteInto(rn, removed, multi, big)
        }
    }

    /** A register name that a yank or delete cannot write (the read-only ones and anything that is no register). */
    private fun unwritable(name: Char?) = name != null && name != '+' && name != '*' && name != '_' && !memory.writable(name)

    /** Puts the deleted text into the registers the command names (or clears them where nothing may be kept). */
    private fun deleteInto(name: Char?, reg: Reg, multi: Boolean, useRegOne: Boolean): VimNotice? {
        if (name == '+' || name == '*') {
            if (!io.clipboardWrite(if (reg.type == RegType.LINE) reg.text + "\n" else reg.text)) return VimNotice.CLIPBOARD_REFUSED
            memory.delete(null, reg, multi, useRegOne)
            return null
        }
        return if (memory.delete(name, reg, multi, useRegOne) == Kept.FULL) VimNotice.REGISTER_FULL else null
    }

    private fun yankRange(s: Snap, rng: Rng, caretAfter: Int, rn: Char?): VimOutcome {
        val b = s.b
        val reg = regOf(b, rng)
        if (unwritable(rn)) return fail()
        if (rn == '+' || rn == '*') {
            if (!io.clipboardWrite(if (reg.type == RegType.LINE) reg.text + "\n" else reg.text)) return VimOutcome(VimNotice.CLIPBOARD_REFUSED)
            memory.point(reg)
        } else {
            when (memory.yank(rn, reg)) {
                Kept.OFF -> return VimOutcome(VimNotice.REGISTER_OFF)
                Kept.FULL -> return VimOutcome(VimNotice.REGISTER_FULL)
                Kept.OK -> {}
            }
        }
        memory.markSet('[', s.text, rng.start)
        memory.markSet(']', s.text, if (rng.linewise) b.lastChar(rng.end) else b.prev(rng.end))
        io.select(caretAfter, caretAfter)
        expectedCaret = caretAfter
        return VimOutcome()
    }

    private class Plan(
        val start: Int,
        val end: Int,
        val replacement: String,
        val caret: Int,
        val insert: Boolean,
        val insertKind: Char = 'i',
        val insertCount: Int = 1,
        val block: BlockInsert? = null,
        val selection: Pair<Int, Int>? = null,
    )

    /**
     * Re-reads the field, and edits only if it is exactly what the plan was computed from: one text change. [onDone] runs after the change
     * (the registers); a notice it returns is shown. [markStart] is where the change starts ('[ and ']).
     */
    private fun apply(s: Snap, p: Plan, markStart: Int = p.start, markLine: Boolean = false, onDone: ((String) -> VimNotice?)? = null): VimOutcome {
        if (p.replacement.isNotEmpty() && !rules.accepts(p.replacement)) return VimOutcome(VimNotice.NOT_ACCEPTED)
        if (p.start < 0 || p.end > s.b.length || p.start > p.end) return VimOutcome(VimNotice.CHANGED)
        // no text longer than the window is ever written (the size is known before the new text is built)
        if (s.text.length.toLong() - (p.end - p.start) + p.replacement.length > MAX_CHARS) return tooBig()
        val again = io.read()
        if (again !is VimRead.Text || again.text != s.text || again.selStart != s.selStart || again.selEnd != s.selEnd)
            return VimOutcome(VimNotice.CHANGED)
        val newText = s.text.substring(0, p.start) + p.replacement + s.text.substring(p.end)
        bufferEmptied = false
        if (p.insert) cmdChanged = true // an insert command is a change even where it removes nothing
        if (p.start != p.end || p.replacement.isNotEmpty()) {
            io.replace(p.start, p.end, p.replacement, p.caret) // nothing to change (dd on an empty field): the caret only
            cmdChanged = true
        } else io.select(p.caret, p.caret)
        if (p.selection != null) io.select(p.selection.first, p.selection.second)
        if (memory.marksBase == s.text) memory.marksRemapEdit(s.text, p.start, p.end, p.replacement)
        if (captureOk() && (p.start != p.end || p.replacement.isNotEmpty())) { // (the build of the line table is skipped where the marks would refuse)
            val cut = newText.length
            val nb = VimBuf(newText)
            fun at(x: Int) = if (markLine) nb.lineStart(minOf(x, cut)) else minOf(x, cut) // whole lines deleted: the marks are at the start of the line that took their place
            memory.markSet('[', newText, at(markStart))
            memory.markSet(']', newText, at(maxOf(markStart, if (p.replacement.isNotEmpty()) p.start + p.replacement.length - 1 else markStart)))
        }
        val notice = onDone?.let { done -> done(newText) }
        mode = if (p.insert) VimMode.INSERT else VimMode.NORMAL
        if (p.insert) beginInsert(newText, p.caret, p.insertCount, p.insertKind, p.block)
        anchor = -1
        shownSelection = null
        expectedCaret = p.caret
        return VimOutcome(notice)
    }

    /** dd, cc, yy, g~~, >>, ...: [count] lines from the cursor line. */
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
        val lastStart = s.b.startOfLine(endLine)
        return operate(s, o, Rng(s.b.lineStart(c), s.b.lineEnd(lastStart), true), null, c, target = s.b.firstNonBlank(lastStart))
    }

    // ---- case and shift -----------------------------------------------------------------------------------------------------------

    private fun caseRange(s: Snap, kind: Char, rng: Rng, cursor: Int, mv: Mv?, startPos: Int? = null, target: Int? = null): VimOutcome {
        val b = s.b
        var rng = rng
        // g~0 and gU0 at the start of a line change the character there: Vim takes an empty exclusive range as one character here
        if (!rng.linewise && rng.start == rng.end && rng.start < b.lineEnd(rng.start) && mv != null && mv.type == MType.EXCL) rng = Rng(rng.start, b.next(rng.start), false)
        val old = b.text.substring(rng.start, rng.end)
        val changed = VimOps.changeCase(old, kind)
        // a linewise motion leaves the cursor at the earlier of where it was and where the motion ended
        val end = target ?: mv?.pos ?: cursor
        val landing = if (startPos != null) b.normal(startPos) else if (rng.linewise) b.normal(minOf(cursor, end)) else b.normal(rng.start)
        if (changed == old) { io.select(landing, landing); expectedCaret = landing; cmdChanged = true; return VimOutcome() }
        return apply(s, Plan(rng.start, rng.end, changed, landing, insert = false))
    }

    /** Shifts the lines of [rng] by [times] shift widths (the setting says whether the indent is made with a tab). */
    private fun shiftRange(s: Snap, left: Boolean, range: Rng, times: Int): VimOutcome {
        val b = s.b
        // the lines the range touches (a range of characters shifts the lines it is in)
        val rng = if (range.linewise) range else Rng(b.lineStart(range.start), b.lineEnd(if (range.end > range.start) range.end - 1 else range.start), true)
        val tabs = rules.indentWithTab()
        val first = b.text.substring(rng.start, rng.end)
        val lines = first.split('\n')
        // each shifted line gets at most this much longer: checked before the new lines are built
        if (!left && lines.size.toLong() * times * (if (tabs) 1 else VimOps.SHIFT_WIDTH) + b.length > MAX_CHARS) return tooBig()
        val out = lines.joinToString("\n") { VimOps.shiftLine(it, left, times, tabs) }
        val newText = b.text.substring(0, rng.start) + out + b.text.substring(rng.end)
        val landing = VimBuf(newText).firstNonBlank(rng.start)
        if (out == first) { io.select(landing, landing); expectedCaret = landing; cmdChanged = true; return VimOutcome() }
        return apply(s, Plan(rng.start, rng.end, out, landing, insert = false))
    }

    // ---- insert commands, replace, paste -----------------------------------------------------------------------------------------------

    private fun insertCommand(k: Char): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        wantCol = null
        val s = snapshot()
        if (s == null) {
            // no text: i is where the caret stands; a, A, I use the cursor keys
            when (k) {
                'i' -> {}
                'a' -> io.navigate(VimNav.RIGHT, 1)
                'A' -> io.navigate(VimNav.LINE_END, 1)
                'I' -> io.navigate(VimNav.LINE_START, 1)
                else -> return VimOutcome(failure)
            }
            mode = VimMode.INSERT
            cmdChanged = true
            return VimOutcome()
        }
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        when (k) {
            'i', 'R' -> return insertAt(s, c, n, k)
            'a' -> return insertAt(s, if (c < b.lineEnd(c)) b.next(c) else c, n, k)
            'A' -> return insertAt(s, b.lineEnd(c), n, k)
            'I' -> {
                var i = b.lineStart(c)
                while (b.isBlankAt(i)) i++
                return insertAt(s, i, n, k)
            }
        }
        // o and O add a line
        val at = if (k == 'o') b.lineEnd(c) else b.lineStart(c)
        return apply(s, Plan(at, at, "\n", if (k == 'o') at + 1 else at, insert = true, insertKind = k, insertCount = n))
    }

    private fun insertAt(s: Snap, at: Int, count: Int, kind: Char): VimOutcome {
        io.select(at, at)
        beginInsert(s.text, at, count, kind)
        mode = VimMode.INSERT
        cmdChanged = true
        return VimOutcome()
    }

    /** gi: insert where the last insert stopped (the ^ mark), else at the cursor. */
    private fun insertAtLastInsert(): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val at = memory.markGet('^', s.text).let { if (it >= 0) it else c }
        return insertAt(s, at, 1, 'i')
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
        memory.setLastInsert(String(Character.toChars(ch))) // Vim keeps the replacing character as the last inserted text
        return apply(s, Plan(c, end, text, c + text.length - Character.charCount(ch), insert = false))
    }

    /** ~ in Normal mode: toggles the case of [count] characters and moves right. */
    private fun tildeChars(): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        if (b.isEmptyLine(c)) return fail()
        var end = c
        for (i in 0 until n) {
            if (end >= b.lineEnd(end)) break
            end = b.next(end)
        }
        val old = b.text.substring(c, end)
        val changed = VimOps.changeCase(old, '~')
        val after = if (end >= b.lineEnd(c)) b.lastChar(c) else end
        if (changed == old) { io.select(after, after); expectedCaret = after; cmdChanged = true; return VimOutcome() }
        return apply(s, Plan(c, end, changed, after, insert = false))
    }

    private fun clipboardReg(text: String): Reg? {
        if (text.isEmpty() || text.length > VimMemory.MAX_CHARS) return null
        return if (text.endsWith("\n")) Reg(text.removeSuffix("\n"), RegType.LINE) else Reg(text, RegType.CHAR)
    }

    /** The register a put or play reads: the clipboard ones through the paste owner, else memory. */
    private fun readRegister(name: Char?): Pair<Reg?, VimNotice?> {
        val n = name ?: '"'
        if (n == '+' || n == '*') {
            val text = io.clipboardRead() ?: return null to VimNotice.CLIPBOARD_EMPTY
            return (clipboardReg(text) ?: return null to VimNotice.CLIPBOARD_EMPTY) to null
        }
        if (n == '_') return null to VimNotice.REGISTER_EMPTY
        return memory.get(n) to null
    }

    private fun paste(before: Boolean): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        // Putting the register is allowed in every field where Vim is available, also a no-learning or per-app incognito field, as pasting
        // from the clipboard history is; only filling the register follows registerAllowed (nothing is kept from such a field).
        val rn = cmdReg
        val (reg, why) = readRegister(rn)
        if (reg == null || (reg.text.isEmpty() && reg.type != RegType.LINE)) return fail(why ?: if (captureOk()) VimNotice.REGISTER_EMPTY else VimNotice.REGISTER_OFF)
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        wantCol = null
        if (s.b.length + putGrowth(s.b, c, reg, n) > MAX_CHARS) return tooBig() // before the repeats are built
        val plan = putPlan(s, c, reg, n, before) ?: return fail()
        // the lines put after the last line start after the line break that joins them
        return apply(s, plan, markStart = if (reg.type == RegType.LINE && plan.replacement.startsWith("\n")) plan.start + 1 else plan.start)
    }

    /** An upper bound of how many characters putting [reg] [n] times adds to [b] at [c], computed from sizes only (nothing is built). */
    private fun putGrowth(b: VimBuf, c: Int, reg: Reg, n: Int): Long = when (reg.type) {
        RegType.LINE -> (reg.text.length + 1L) * n
        RegType.CHAR -> reg.text.length.toLong() * n
        RegType.BLOCK -> {
            val rows = reg.text.split('\n')
            var width = reg.width
            for (row in rows) width = maxOf(width, row.length)
            rows.size.toLong() * (width.toLong() * n + b.column(c) + 2) // each row: its copies, the padding up to the column, a line break
        }
    }

    /** The change that puts [reg] [n] times after (or [before]) the cursor [c]. Null when it cannot be done (a block into a single-line field...). */
    private fun putPlan(s: Snap, c: Int, reg: Reg, n: Int, before: Boolean): Plan? {
        val b = s.b
        when (reg.type) {
            RegType.LINE -> {
                val block = (1..n).joinToString("\n") { reg.text }
                return if (before) {
                    val at = b.lineStart(c)
                    val newText = b.text.substring(0, at) + block + "\n" + b.text.substring(at)
                    Plan(at, at, block + "\n", VimBuf(newText).firstNonBlank(at), insert = false)
                } else if (b.lineEnd(c) < b.length) {
                    val at = b.lineEnd(c) + 1
                    val newText = b.text.substring(0, at) + block + "\n" + b.text.substring(at)
                    Plan(at, at, block + "\n", VimBuf(newText).firstNonBlank(at), insert = false)
                } else {
                    val at = b.length
                    val newText = b.text + "\n" + block
                    Plan(at, at, "\n" + block, VimBuf(newText).firstNonBlank(at + 1), insert = false)
                }
            }
            RegType.CHAR -> {
                val at = if (before || c >= b.lineEnd(c)) c else b.next(c)
                val block = reg.text.repeat(n)
                val newText = b.text.substring(0, at) + block + b.text.substring(at)
                val nb = VimBuf(newText)
                val caret = if ('\n' in block) at else nb.prev(at + block.length)
                return Plan(at, at, block, nb.normal(caret), insert = false)
            }
            RegType.BLOCK -> return VimBlock.put(b, c, reg, n, before)?.let { Plan(it.start, it.end, it.replacement, it.caret, insert = false) }
        }
    }

    // ---- Visual mode --------------------------------------------------------------------------------------------------------------

    private fun toggleVisual(target: VimMode): VimOutcome {
        val count = countGiven()
        clearPending()
        if (mode == target) return leaveVisual(null).let { VimOutcome() }
        if (mode != VimMode.NORMAL) { // v, V and Ctrl-V switch between the three
            mode = target
            val s = snapshot() ?: return VimOutcome(failure)
            if (shownSelection != s.selStart to s.selEnd) return leaveVisual(null).let { VimOutcome(VimNotice.CHANGED) }
            if (target != VimMode.VISUAL_BLOCK) blockDollar = false
            showVisual(s.b)
            return VimOutcome()
        }
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val dollar = target == VimMode.VISUAL_BLOCK && wantCol == Int.MAX_VALUE // "$" before Ctrl-V: the block goes to the end of each line
        anchor = c
        visualCursor = c
        // a count with V selects that many lines
        if (target == VimMode.VISUAL_LINE && count != null && count > 1) {
            val line = s.b.lineNumber(c)
            val endLine = minOf(line + count - 1, s.b.lineCount() - 1)
            visualCursor = s.b.atColumn(s.b.startOfLine(endLine), s.b.column(c)).let { if (it >= s.b.lineEnd(it)) s.b.lastChar(it) else it }
        }
        mode = target
        wantCol = null
        blockDollar = dollar
        showVisual(s.b)
        return VimOutcome()
    }

    private fun swapVisualEnds(otherCorner: Boolean): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        if (cursorOf(s) == null) return VimOutcome(VimNotice.CHANGED)
        if (otherCorner && mode == VimMode.VISUAL_BLOCK) { // O: the other corner on the same line
            val b = s.b
            val aCol = b.column(anchor)
            val cCol = b.column(visualCursor)
            val newAnchor = b.atColumn(anchor, cCol)
            val newCursor = b.atColumn(visualCursor, aCol)
            anchor = if (newAnchor >= b.lineEnd(anchor)) b.lastChar(anchor) else newAnchor
            visualCursor = if (newCursor >= b.lineEnd(visualCursor)) b.lastChar(visualCursor) else newCursor
        } else {
            val t = anchor
            anchor = visualCursor
            visualCursor = t
        }
        wantCol = null
        showVisual(s.b)
        return VimOutcome()
    }

    /** The geometry of a block selection: lines [lo, hi] (0-based) and columns [left, right] in characters ([Int.MAX_VALUE]: to the end of each line). */
    private class Block(val loLine: Int, val hiLine: Int, val left: Int, val right: Int)

    private fun blockOf(b: VimBuf): Block {
        val aLine = b.lineNumber(anchor)
        val cLine = b.lineNumber(visualCursor)
        val aCol = b.column(anchor)
        val cCol = b.column(visualCursor)
        return Block(minOf(aLine, cLine), maxOf(aLine, cLine), minOf(aCol, cCol), if (blockDollar) Int.MAX_VALUE else maxOf(blockRight ?: -1, maxOf(aCol, cCol)))
    }

    /** The field selection that shows the Visual selection: the characters from the anchor to the cursor, both included (for a block, its span). */
    private fun displayOf(b: VimBuf): Pair<Int, Int> {
        val lo = minOf(anchor, visualCursor)
        val hi = maxOf(anchor, visualCursor)
        if (mode == VimMode.VISUAL_LINE) return b.lineStart(lo) to minOf(b.lineEnd(hi) + 1, b.length)
        if (mode == VimMode.VISUAL_BLOCK) {
            val bl = blockOf(b)
            val first = b.startOfLine(bl.loLine)
            val last = b.startOfLine(bl.hiLine)
            val start = b.atColumn(first, bl.left)
            val end = if (bl.right == Int.MAX_VALUE) b.lineEnd(last) else b.atColumn(last, bl.right + 1)
            return start to maxOf(end, start)
        }
        val end = if (hi < b.lineEnd(hi)) b.next(hi) else minOf(hi + 1, b.length)
        return lo to end
    }

    private fun showVisual(b: VimBuf) {
        val d = displayOf(b)
        io.select(d.first, d.second)
        shownSelection = d
    }

    /** Leaves Visual mode: the caret goes where the cursor was, unless [select] is false (an edit of the selection follows, and checks it is still there). */
    private fun leaveVisual(s: Snap?, select: Boolean = true): VimOutcome {
        val cursor = visualCursor
        val was = mode
        mode = VimMode.NORMAL
        shownSelection = null
        dropPending()
        val text = (s?.text ?: (io.read() as? VimRead.Text)?.text)
        if (text != null && cursor >= 0) {
            if (select) {
                val at = VimBuf(text).normal(cursor.coerceIn(0, text.length))
                io.select(at, at)
                expectedCaret = at
            }
            if (anchor >= 0 && captureOk()) rememberVisual(text, was)
        }
        anchor = -1
        visualCursor = -1
        blockDollar = false
        blockRight = null
        return VimOutcome()
    }

    private var lastVisualMode: VimMode = VimMode.VISUAL
    private var lastVisualCursorAtStart = false
    private var lastVisualDollar = false

    private fun rememberVisual(text: String, was: VimMode) {
        val lo = minOf(anchor, visualCursor).coerceIn(0, text.length)
        val hi = maxOf(anchor, visualCursor).coerceIn(0, text.length)
        memory.markSet('<', text, lo)
        memory.markSet('>', text, hi)
        lastVisualMode = was
        lastVisualCursorAtStart = visualCursor < anchor
        lastVisualDollar = blockDollar
    }

    private fun reselectVisual(): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val lo = memory.markGet('<', s.text)
        val hi = memory.markGet('>', s.text)
        if (lo < 0 || hi < 0) return VimOutcome(if (marksLost) VimNotice.MARK_INVALID else VimNotice.MARK_NOT_SET)
        val b = s.b
        anchor = if (lastVisualCursorAtStart) b.snap(hi) else b.snap(lo)
        visualCursor = if (lastVisualCursorAtStart) b.snap(lo) else b.snap(hi) // the end may be the end of a line: the selection then has its line break
        mode = lastVisualMode
        blockDollar = lastVisualDollar
        wantCol = null
        showVisual(b)
        return VimOutcome()
    }

    private fun visualJoin(spaces: Boolean): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val lo = b.lineNumber(minOf(anchor, visualCursor))
        val hi = b.lineNumber(maxOf(anchor, visualCursor))
        val last = b.lineCount() - 1
        val to = if (hi == lo) minOf(lo + 1, last) else hi
        if (to == lo) {
            val lineWise = mode == VimMode.VISUAL_LINE
            val start = minOf(anchor, visualCursor)
            leaveVisual(s)
            val at = if (lineWise) b.lineStart(start) else b.normal(start) // the cursor is at the start of the selection
            io.select(at, at)
            expectedCaret = at
            return fail()
        }
        leaveVisual(s, false)
        return joinLines(s, lo, to, spaces)
    }

    private fun joinKey(spaces: Boolean): VimOutcome {
        var n = countGiven() ?: 2
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val line = s.b.lineNumber(c)
        val last = s.b.lineCount() - 1
        if (line + n - 1 > last) {
            if (n <= 2) return fail() // can't join on the last line
            n = last - line + 1
            cmdCount = n // "." repeats the count that was used (Vim stores it after cutting it)
        }
        return joinLines(s, line, line + n - 1, spaces)
    }

    private fun joinLines(s: Snap, firstLine: Int, lastLine: Int, spaces: Boolean): VimOutcome {
        val b = s.b
        val start = b.startOfLine(firstLine)
        val end = b.lineEnd(b.startOfLine(lastLine))
        val parts = b.text.substring(start, end).split('\n')
        val joined = VimOps.join(parts, spaces)
        val newText = b.text.substring(0, start) + joined.line + b.text.substring(end)
        val nb = VimBuf(newText)
        val caret = nb.normal(start + minOf(joined.cursor, maxOf(0, joined.line.length - 1)))
        return apply(s, Plan(start, end, joined.line, caret, insert = false))
    }

    private fun visualIncrement(plus: Boolean, progressive: Boolean): VimOutcome {
        val n = (countGiven() ?: 1).toLong()
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val modeNow = mode
        val anchorAtStart = anchor
        val visualCursorAtStart = visualCursor
        val lo = b.lineNumber(minOf(anchor, visualCursor))
        val hi = b.lineNumber(maxOf(anchor, visualCursor))
        val blk = if (modeNow == VimMode.VISUAL_BLOCK) blockOf(b) else null
        val first = b.startOfLine(lo)
        val lastEnd = b.lineEnd(b.startOfLine(hi))
        val parts = b.text.substring(first, lastEnd).split('\n').toMutableList()
        var step = 0L
        var changedAny = false
        for ((k, line) in parts.withIndex()) {
            val startCol: Int
            val endCol: Int
            when {
                blk != null -> {
                    val lb = VimBuf(line)
                    if (lb.length == 0 || lb.atColumn(0, blk.left) >= lb.length) continue
                    startCol = lb.atColumn(0, blk.left)
                    endCol = if (blk.right == Int.MAX_VALUE) lb.length else lb.atColumn(0, blk.right + 1)
                }
                modeNow == VimMode.VISUAL_LINE -> { startCol = 0; endCol = line.length }
                else -> {
                    val lsOff = b.startOfLine(lo + k)
                    val dStart = minOf(anchor, visualCursor)
                    val dEnd = displayOf(b).second
                    startCol = if (k == 0) dStart - lsOff else 0
                    endCol = if (lo + k == hi) (dEnd - lsOff).coerceAtMost(line.length) else line.length
                }
            }
            step += 1
            val delta = (if (plus) 1 else -1) * n * (if (progressive) step else 1L)
            val r = VimOps.addSub(line, startCol, delta, true, startCol, endCol) ?: continue
            parts[k] = r.line
            changedAny = true
        }
        leaveVisual(s, false)
        cmdChanged = true
        if (!changedAny) { // no number in the selection: Visual mode ends, the cursor at the start of it
            val at = b.normal(if (modeNow == VimMode.VISUAL_LINE) first else if (modeNow == VimMode.VISUAL_BLOCK) b.atColumn(first, blk?.left ?: 0) else minOf(anchorAtStart, visualCursorAtStart))
            io.select(at, at)
            expectedCaret = at
            return fail()
        }
        val out = parts.joinToString("\n")
        val newText = b.text.substring(0, first) + out + b.text.substring(lastEnd)
        // the cursor goes to the start of the selection (the top left of a block); whole lines: their start
        val landing = VimBuf(newText).normal(when (modeNow) {
            VimMode.VISUAL_LINE -> first
            VimMode.VISUAL_BLOCK -> b.atColumn(first, blk?.left ?: 0)
            else -> minOf(anchorAtStart, visualCursorAtStart)
        })
        return apply(s, Plan(first, lastEnd, out, landing, insert = false))
    }

    private fun increment(plus: Boolean): VimOutcome {
        val n = (countGiven() ?: 1).toLong()
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val b = s.b
        val ls = b.lineStart(c)
        val line = b.text.substring(ls, b.lineEnd(c))
        val r = VimOps.addSub(line, c - ls, if (plus) n else -n, false) ?: return fail()
        val newText = b.text.substring(0, ls) + r.line + b.text.substring(ls + line.length)
        return apply(s, Plan(ls, ls + line.length, r.line, VimBuf(newText).normal(ls + r.cursor), insert = false))
    }

    private fun visualReplace(ch: Int): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val c = String(Character.toChars(ch))
        val was = mode
        val blk = if (was == VimMode.VISUAL_BLOCK) blockOf(b) else null
        val lo = minOf(anchor, visualCursor)
        val range = if (was == VimMode.VISUAL_LINE) b.lineStart(lo) to b.lineEnd(maxOf(anchor, visualCursor)) else shown
        leaveVisual(s, false)
        cmdChanged = true
        if (blk != null) {
            val segs = VimBlock.segments(b, blk.loLine, blk.hiLine, blk.left, blk.right)
            val e = VimBlock.replacePlan(b, segs) { seg -> c.repeat(clusters(seg.text)) } ?: return fail()
            return apply(s, Plan(e.start, e.end, e.replacement, e.caret, insert = false))
        }
        val old = b.text.substring(range.first, range.second)
        val sb = StringBuilder()
        var i = 0
        val ob = VimBuf(old)
        while (i < old.length) {
            if (old[i] == '\n') { sb.append('\n'); i++ } else { sb.append(c); i = ob.next(i) }
        }
        val newText = b.text.substring(0, range.first) + sb + b.text.substring(range.second)
        val landing = VimBuf(newText).normal(range.first)
        return apply(s, Plan(range.first, range.second, sb.toString(), landing, insert = false))
    }

    private fun visualPut(keepRegister: Boolean): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        val rn = cmdReg
        val (found, why) = readRegister(rn)
        if (found == null && why != null && why != VimNotice.REGISTER_EMPTY) return VimOutcome(why)
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        if (mode == VimMode.VISUAL_BLOCK) {
            val blk = blockOf(b)
            val dollar = blockDollar
            leaveVisual(s, false)
            if (found != null && !(found.text.isEmpty() && found.type != RegType.LINE)) return fail() // a block replaced by text is not offered
            cmdChanged = true
            cmdReg = if (keepRegister) '_' else null
            return blockOperator(s, 'd', blk, dollar, cmdReg)
        }
        val linewise = mode == VimMode.VISUAL_LINE
        val lo = minOf(anchor, visualCursor)
        val hi = maxOf(anchor, visualCursor)
        val sel = if (linewise) Rng(b.lineStart(lo), b.lineEnd(hi), true) else Rng(shown.first, shown.second, false)
        val removed = regOf(b, sel)
        val before = visualCursor
        leaveVisual(s, false)
        cmdChanged = true
        if (found == null || (found.text.isEmpty() && found.type != RegType.LINE)) {
            // an empty register puts nothing: the selection is taken away, like d (P keeps the registers as they are)
            cmdReg = if (keepRegister) '_' else null
            return operate(s, 'd', sel, null, lo.coerceAtMost(before))
        }
        val reg = found
        // the size of the put text is known before it is built: [n] copies of the register, with a line break between the lines of a line register
        val bodyLength = if (reg.type == RegType.CHAR) reg.text.length.toLong() * n else (reg.text.length + 1L) * n
        if (b.length - (sel.end - sel.start) + bodyLength > MAX_CHARS) return tooBig()
        val body = when (reg.type) { RegType.CHAR -> reg.text.repeat(n); else -> (1..n).joinToString("\n") { reg.text } }
        val replacement: String
        val landing: Int
        val start = sel.start
        val end = sel.end
        if (linewise) {
            replacement = body
            landing = sel.start
        } else if (reg.type == RegType.LINE) { // the lines go between the two halves of the line
            replacement = "\n" + body + "\n"
            landing = sel.start + 1
        } else {
            replacement = body
            landing = sel.start + body.length - 1
        }
        val newText = b.text.substring(0, start) + replacement + b.text.substring(end)
        val caret = if (reg.type == RegType.LINE || linewise) VimBuf(newText).firstNonBlank(landing) else VimBuf(newText).normal(maxOf(start, landing))
        return apply(s, Plan(start, end, replacement, caret, insert = false)) { text ->
            // the put text is the last Visual area (gv selects it)
            if (captureOk()) {
                memory.markSet('<', text, start)
                memory.markSet('>', text, maxOf(start, start + replacement.length - 1))
                lastVisualMode = if (linewise || reg.type == RegType.LINE) VimMode.VISUAL_LINE else VimMode.VISUAL
                lastVisualCursorAtStart = false
                lastVisualDollar = false
            }
            if (keepRegister) null else deleteInto(null, removed, linewise || '\n' in removed.text, false)
        }
    }

    private fun visualOperator(o: Char): VimOutcome {
        val count = countGiven() ?: 1
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val lo = minOf(anchor, visualCursor)
        val hi = maxOf(anchor, visualCursor)
        val was = mode
        val linewise = was == VimMode.VISUAL_LINE
        val blk = if (was == VimMode.VISUAL_BLOCK) blockOf(b) else null
        val rn = cmdReg
        val info = visualInfo(b, was, blk)
        if (blk != null) {
            val dollar = blockDollar
            leaveVisual(s, false)
            visualDot = info
            return blockOperator(s, o, blk, dollar, rn)
        }
        val rng = if (linewise) Rng(b.lineStart(lo), b.lineEnd(hi), true) else Rng(shown.first, shown.second, false)
        // Vim: after a linewise yank the cursor stays where it is when it moved to an earlier line, else it goes to column 0 of the first line
        val yankAt = if (linewise && b.lineNumber(visualCursor) < b.lineNumber(anchor)) visualCursor else if (linewise) b.lineStart(lo) else rng.start
        val cursorBefore = visualCursor
        leaveVisual(s, false)
        visualDot = info
        if (o != 'y') cmdChanged = true
        return when (o) {
            'y' -> yankRange(s, rng, b.normal(yankAt), rn)
            '~', 'u', 'U' -> caseRange(s, o, rng, rng.start, null)
            '>', '<' -> shiftRange(s, o == '<', rng, count)
            else -> operate(s, o, rng, null, lo.coerceAtMost(cursorBefore)).also { if (o == 'c') cmdChanged = true }
        }
    }

    // ---- Visual block commands ----------------------------------------------------------------------------------------------------

    private fun blockOperator(s: Snap, o: Char, blk: Block, dollar: Boolean, rn: Char?): VimOutcome {
        val b = s.b
        val segs = VimBlock.segments(b, blk.loLine, blk.hiLine, blk.left, blk.right)
        val topLeft = b.atColumn(b.startOfLine(blk.loLine), blk.left).let { if (it >= b.lineEnd(it) && it > b.lineStart(it)) b.lastChar(it) else it }
        var widest = 0
        for (seg in segs) widest = maxOf(widest, clusters(seg.text))
        val width = if (dollar) widest else blk.right - blk.left + 1
        val reg = Reg(segs.joinToString("\n") { it.text }, RegType.BLOCK, width)
        when (o) {
            'y' -> {
                if (unwritable(rn)) return fail()
                if (rn == '+' || rn == '*') { if (!io.clipboardWrite(reg.text)) return VimOutcome(VimNotice.CLIPBOARD_REFUSED); memory.point(reg) }
                else when (memory.yank(rn, reg)) {
                    Kept.OFF -> return VimOutcome(VimNotice.REGISTER_OFF)
                    Kept.FULL -> return VimOutcome(VimNotice.REGISTER_FULL)
                    Kept.OK -> {}
                }
                io.select(topLeft, topLeft)
                expectedCaret = topLeft
                return VimOutcome()
            }
            'd', 'c' -> {
                if (unwritable(rn)) return fail()
                if (segs.isEmpty()) return fail()
                val insert = o == 'c'
                val plan = VimBlock.removePlan(b, segs, blk.loLine, blk.left)
                val after = s.text.substring(0, plan.start) + plan.replacement + s.text.substring(plan.end)
                val caret = if (insert) plan.caret else VimBuf(after).normal(plan.caret) // Normal mode: on a character
                cmdChanged = true
                return apply(s, Plan(plan.start, plan.end, plan.replacement, caret, insert = insert, insertKind = 'c',
                    block = if (insert) BlockInsert(blk.loLine, blk.hiLine, blk.left, false, false) else null)) { _ ->
                    deleteInto(rn, reg, segs.size > 1, false) // one line is a small delete
                }
            }
            '~', 'u', 'U' -> {
                cmdChanged = true
                val e = VimBlock.replacePlan(b, segs) { seg -> VimOps.changeCase(seg.text, o) } ?: return fail()
                return apply(s, Plan(e.start, e.end, e.replacement, e.caret, insert = false))
            }
            '>', '<' -> {
                // the text from the left column of the block on is shifted: blanks are put there, or taken away from there
                cmdChanged = true
                val tabs = rules.indentWithTab()
                val unit = if (tabs) "\t" else " ".repeat(VimOps.SHIFT_WIDTH)
                if (o == '>' && (blk.hiLine - blk.loLine + 1L) * unit.length + b.length > MAX_CHARS) return tooBig()
                val first = b.startOfLine(blk.loLine)
                val end = b.lineEnd(b.startOfLine(blk.hiLine))
                val lines = b.text.substring(first, end).split('\n')
                val out = lines.joinToString("\n") { line ->
                    val lb = VimBuf(line)
                    if (clusters(line) < blk.left) line // the line does not reach the block
                    else {
                        val at = lb.atColumn(0, blk.left)
                        if (o == '>') line.substring(0, at) + unit + line.substring(at)
                        else {
                            var e = at
                            var w = 0
                            while (e < line.length && w < VimOps.SHIFT_WIDTH && (line[e] == ' ' || line[e] == '\t')) { w += if (line[e] == '\t') VimOps.TAB - w % VimOps.TAB else 1; e++ }
                            line.substring(0, at) + line.substring(e)
                        }
                    }
                }
                val topLeft = b.atColumn(first, blk.left)
                if (out == b.text.substring(first, end)) { io.select(topLeft, topLeft); expectedCaret = topLeft; return VimOutcome() }
                return apply(s, Plan(first, end, out, VimBuf(s.text.substring(0, first) + out + s.text.substring(end)).normal(topLeft), insert = false))
            }
            else -> return cancel()
        }
    }

    private fun blockInsert(append: Boolean): VimOutcome {
        clearPending()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val blk = blockOf(b)
        val dollar = blockDollar
        val info = visualInfo(b, VimMode.VISUAL_BLOCK, blk)
        leaveVisual(s, false)
        visualDot = info
        cmdChanged = true
        val firstStart = b.startOfLine(blk.loLine)
        val firstLen = clusters(b.text.substring(firstStart, b.lineEnd(firstStart)))
        val col = if (append) (if (dollar) firstLen else blk.right + 1) else blk.left
        val at: Int
        var pad = 0
        if (col <= firstLen) at = b.atColumn(firstStart, col)
        else { // A on a short first line: it gets blanks up to the column, written with the insert when it ends (not now: one text change)
            at = b.lineEnd(firstStart)
            pad = col - firstLen
        }
        val session = BlockInsert(blk.loLine, blk.hiLine, col, append, dollar, left = blk.left, pad = pad)
        io.select(at, at)
        beginInsert(s.text, at, 1, 'i', session)
        mode = VimMode.INSERT
        return VimOutcome()
    }

    /**
     * The one change that ends a block insert: the first line's padding (when it was shorter than the column) in front of what was typed there,
     * and [typed] on the other lines at the same column. The padding and the other lines are computed together and written once.
     */
    private fun blockFollow(session: InsertSession, block: BlockInsert, typed: String, text: String): Follow? {
        val fill = if (typed.isEmpty() || '\n' in typed) null else blockFill(block, typed, text)
        if (followRefused) return null // the other lines were refused: the padding is part of the same change, so nothing at all is written
        if (block.pad == 0) return fill
        val pad = " ".repeat(block.pad)
        val start = session.start // the typed text starts here, at the end of the first line, and the first line ends where it ends
        if (fill == null) return Follow(start, start, pad, start + pad.length + typed.length)
        if (fill.start != start + typed.length + 1) return null // the text between is not what was typed: nothing is written
        return Follow(start, fill.end, pad + typed + "\n" + fill.replacement, start + pad.length + typed.length)
    }

    /** The other lines of a block insert get [typed] at the same column, in one change (lines too short for it are left, except by A). */
    private fun blockFill(block: BlockInsert, typed: String, text: String): Follow? {
        val b = VimBuf(text)
        val firstStart = b.startOfLine(block.firstLine)
        val from = b.lineEnd(firstStart) + 1
        if (block.lastLine <= block.firstLine || from > text.length) return null
        val endOff = b.lineEnd(b.startOfLine(block.lastLine))
        val kept = text.length.toLong() - (endOff - from) // what stays outside the lines that are rebuilt
        val sb = StringBuilder()
        var ls = from
        for (line in block.firstLine + 1..block.lastLine) {
            val le = b.lineEnd(ls)
            val len = clusters(text.substring(ls, le))
            val seg = text.substring(ls, le)
            // every line gets the typed text and perhaps blanks up to the column: a line that would take the text over the window is not built
            if (kept + sb.length + seg.length + typed.length + (if (block.append && len < block.col) block.col - len else 0) > MAX_CHARS) { followRefused = true; return null }
            if (block.dollar) sb.append(seg).append(typed)
            else if (len >= block.col) {
                val at = VimBuf(seg).atColumn(0, block.col)
                sb.append(seg, 0, at).append(typed).append(seg, at, seg.length)
            } else if (block.append) sb.append(seg).append(" ".repeat(block.col - len)).append(typed)
            else sb.append(seg)
            if (line < block.lastLine) sb.append('\n')
            if (le >= text.length) break
            ls = le + 1
        }
        val caretAt = b.startOfLine(block.firstLine).let { b.atColumn(it, block.col) }
        return Follow(from, endOff, sb.toString(), minOf(caretAt + typed.length, from))
    }

    private fun visualInfo(b: VimBuf, was: VimMode, blk: Block?): VisualInfo {
        val lo = minOf(anchor, visualCursor)
        val hi = maxOf(anchor, visualCursor)
        val lines = b.lineNumber(hi) - b.lineNumber(lo) + 1
        return when {
            blk != null -> VisualInfo(was, lines, if (blk.right == Int.MAX_VALUE) Int.MAX_VALUE else blk.right - blk.left + 1)
            was == VimMode.VISUAL_LINE -> VisualInfo(was, lines, 0)
            lines == 1 -> VisualInfo(was, 1, b.column(hi) - b.column(lo) + 1)
            else -> VisualInfo(was, lines, b.column(hi) + 1)
        }
    }

    // ---- text objects -------------------------------------------------------------------------------------------------------------

    private fun textObject(kind: Char, key: Int): VimOutcome {
        val o = op
        val n = countGiven() ?: 1
        clearPending()
        if (o == null) return selectObject(kind, key, n)
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val range = VimObjects.find(s.b, c, kind == 'a', key, n) ?: return fail()
        if (range.failAt >= 0) { // the walk through the text ended before the count: nothing is done, and the cursor is where the walk ended
            val at = s.b.normal(range.failAt)
            io.select(at, at)
            expectedCaret = at
            return fail()
        }
        val rng = if (range.linewise) Rng(range.start, range.end, true) else if (range.inclusive) charRange(s.b, range.start, range.end, o) else exclusiveRange(s.b, range.start, range.end, o) // o_v applies to exclusive ones only
        return operate(s, o, rng, null, c, inclusive = range.inclusive, startPos = range.start)
    }

    /** A text object in Visual mode: the selection becomes the object, or (a selection of several characters, word objects) grows by one. */
    private fun selectObject(kind: Char, key: Int, n: Int): VimOutcome {
        if (mode == VimMode.NORMAL) return VimOutcome()
        val s = snapshot() ?: return VimOutcome(failure)
        val shown = shownSelection
        if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
        val b = s.b
        val word = key == 'w'.code || key == 'W'.code
        if (word && anchor != visualCursor && visualCursor > anchor) {
            val (at, ok) = VimObjects.extendWord(b, visualCursor, n, kind == 'a', key == 'W'.code)
            visualCursor = at
            if (mode == VimMode.VISUAL_LINE) mode = VimMode.VISUAL
            showVisual(b)
            return if (ok) VimOutcome() else fail()
        }
        val range = VimObjects.find(b, visualCursor, kind == 'a', key, n) ?: return fail()
        if (range.failAt >= 0) {
            if (word) { // the cursor goes where the walk got to; the selection starts at the word that was found first
                val first = VimObjects.find(b, visualCursor, kind == 'a', key, 1)
                if (first != null && first.failAt < 0 && first.end > first.start) { anchor = first.start; visualCursor = maxOf(b.prev(first.end), range.failAt.coerceAtMost(b.length)) }
                else visualCursor = range.failAt
                showVisual(b)
            }
            return fail()
        }
        if (range.end <= range.start) return fail()
        anchor = range.start
        visualCursor = b.prev(range.end)
        if (mode == VimMode.VISUAL_LINE && word) mode = VimMode.VISUAL
        showVisual(b)
        return VimOutcome()
    }

    // ---- marks ----------------------------------------------------------------------------------------------------------------------

    private fun setMarkKey(key: Int): VimOutcome {
        clearPending()
        if (key > 0xFFFF) return VimOutcome()
        val name = key.toChar()
        if (name !in 'a'..'z' && name != '\'' && name != '`' && name != '<' && name != '>' && name != '[' && name != ']') return VimOutcome()
        if (!captureOk()) return VimOutcome(VimNotice.REGISTER_OFF)
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        memory.markSet(if (name == '`') '\'' else name, s.text, c)
        marksLost = false
        return VimOutcome()
    }

    private fun markMotion(key: Int, lineWise: Boolean, noJump: Boolean = false): VimOutcome {
        if (key <= 0 || key > 0xFFFF) { clearPending(); return VimOutcome() }
        var name = key.toChar()
        if (name == '`') name = '\''
        if (name !in 'a'..'z' && name != '\'' && name != '<' && name != '>' && name != '[' && name != ']' && name != '^') { clearPending(); return VimOutcome() }
        return runMotion(null, null) { b, c, _ ->
            // before any jump the context mark is the start of the text, as in a new Vim buffer
            val at = if (name == '\'' && !memory.markIsSet('\'')) 0 else memory.markGet(name, b.text)
            if (at < 0) {
                Mv(c, MType.EXCL, cancels = true, notice = if (marksLost && !memory.markIsSet(name)) VimNotice.MARK_INVALID else VimNotice.MARK_NOT_SET)
            } else if (lineWise) Mv(b.firstNonBlank(at), MType.LINE, jump = noJump.not())
            else Mv(b.normalOrEnd(at), MType.EXCL, jump = noJump.not(), big = true) // a backtick motion always fills register 1
        }
    }

    // ---- search -------------------------------------------------------------------------------------------------------------------------

    /** The step and time budget of one search or pattern; inside a play or an ex line it also ends at the deadline of that command. */
    private fun newBudget(): VimRegex.Budget {
        val now = rules.nowMillis()
        val own = now + SEARCH_MILLIS
        val limit = run?.let { minOf(own, it.deadline) } ?: own
        return VimRegex.Budget(SEARCH_STEPS, limit) { rules.nowMillis() }
    }

    // ---- the budget of a command that runs several steps ---------------------------------------------------------------------------

    /** What one play or ex line may spend: keys (a macro, a repeat, :normal), ex steps and time are counted in this one object, and the first limit stops all of it. */
    private class Run(val deadline: Long) {
        var keys = 0
        var steps = 0L
        /** The characters of the text copies built so far (the line list of an ex run, the text a :normal call is given and the one it gives back). */
        var chars = 0L
        var abort: VimNotice? = null
    }

    /** The command that runs stops at its limit: nothing it computed is written (the field stays as it was) and the notice says why. */
    private fun abortRun(notice: VimNotice) {
        val r = run ?: Run(0L).also { run = it }
        if (r.abort == null) r.abort = notice
        playQueue.clear()
    }

    /** Counts [n] ex steps (a line, an iteration, a command) against the budget; false when it is spent (the run is then aborted). */
    private fun chargeSteps(n: Int): Boolean {
        val r = run ?: return true
        if (r.abort != null) return false
        r.steps += n
        if (r.steps > MAX_EX_STEPS || (r.steps / 64 != (r.steps - n) / 64 && rules.nowMillis() > r.deadline)) { abortRun(VimNotice.PLAY_LIMIT); return false }
        return true
    }

    /**
     * Counts [chars] characters of a text copy that is about to be built against the budget of the command; false when it would pass [MAX_RUN_CHARS] (the
     * command is then aborted and nothing is built). Only the copies built while two or more executions are open inside one another count: those are
     * the ones that nest (every level holds its own, and the iterations of :g and :normal inside a level build new ones). The copies of the first level
     * (a command line with :normal over a range, one macro) are freed one iteration after the other: the key budget, the step budget and the time limit
     * bound them, and a command line over thousands of lines of a long text stays possible. The keys of one play edit its own copy, which nothing keeps
     * longer than one key.
     */
    private fun copyBudget(chars: Long): Boolean {
        val r = run ?: return true // a plain command builds no nested copy
        if (r.abort != null) return false
        if (nest < 2) return true
        r.chars += chars
        if (r.chars > MAX_RUN_CHARS) { abortRun(VimNotice.PLAY_LIMIT); return false }
        return true
    }

    /** A command would compute a text longer than the window: in a play the whole play stops (nothing is written), else the command only says so. */
    private fun tooBig(): VimOutcome {
        if (inTx) { abortRun(VimNotice.PLAY_LIMIT); return fail(VimNotice.PLAY_LIMIT) }
        return fail(VimNotice.TOO_LONG)
    }

    private fun regexOf(pattern: String): VimRegex? = (VimRegex.compile(pattern, false) as? VimRegex.Compiled.Ok)?.regex

    private fun searchNotice(pattern: String): VimNotice = when (VimRegex.compile(pattern, false)) {
        VimRegex.Compiled.TooBig -> VimNotice.SEARCH_LIMIT
        else -> VimNotice.PATTERN_UNSUPPORTED
    }

    /** A search from the cursor as a motion: [forward], [offset], [n] matches away. */
    private fun searchMotion(pattern: String, forward: Boolean, offset: VimSearch.Offset, n: Int): VimOutcome {
        val re = regexOf(pattern)
        if (re == null) {
            clearPending()
            return fail(searchNotice(pattern))
        }
        return runMotion(null, n) { b, c, _ ->
            val budget = newBudget()
            // a character offset is taken off the start of the search, so that "/pat/e+1" does not find the same match again
            var from = c
            if ((offset.kind == 'e' || offset.kind == 's') && offset.amount != 0) {
                repeat(kotlin.math.abs(offset.amount)) { from = if (offset.amount > 0) (if (from > 0) b.prevSkipEol(from) else from) else (if (from < b.length) b.nextSkipEol(from) else from) }
            }
            when (val r = VimSearch.find(b, re, from, forward, n, budget, endBased = offset.kind == 'e')) {
                VimSearch.Found.None -> Mv(c, MType.EXCL, cancels = true, notice = VimNotice.NOT_FOUND)
                VimSearch.Found.Limit -> Mv(c, MType.EXCL, cancels = true, notice = VimNotice.SEARCH_LIMIT)
                is VimSearch.Found.At -> searchTarget(b, r, offset)
            }
        }
    }

    private fun searchTarget(b: VimBuf, r: VimSearch.Found.At, offset: VimSearch.Offset): Mv {
        return when (offset.kind) {
            'l' -> {
                val line = (b.lineNumber(r.start) + offset.amount).coerceIn(0, b.lineCount() - 1)
                Mv(b.firstNonBlank(b.startOfLine(line)), MType.LINE, jump = true, big = true)
            }
            'e' -> {
                var p = if (r.end > r.start) b.prev(r.end) else r.start
                repeat(kotlin.math.abs(offset.amount)) { p = if (offset.amount > 0) (if (p < b.length) b.nextSkipEol(p) else p) else (if (p > 0) b.prevSkipEol(p) else p) }
                Mv(b.normalOrEnd(p), MType.INCL, jump = true, big = true)
            }
            's' -> {
                var p = r.start
                repeat(kotlin.math.abs(offset.amount)) { p = if (offset.amount > 0) (if (p < b.length) b.nextSkipEol(p) else p) else (if (p > 0) b.prevSkipEol(p) else p) }
                Mv(b.normalOrEnd(p), MType.EXCL, jump = true, big = true)
            }
            else -> Mv(b.normalOrEnd(r.start), MType.EXCL, jump = true, big = true)
        }
    }

    private fun searchRepeat(reverse: Boolean): VimOutcome {
        val pattern = memory.lastSearch ?: run { clearPending(); return VimOutcome(VimNotice.NO_PATTERN) }
        return searchMotion(pattern, memory.searchForward != reverse, memory.searchOffset, countGiven() ?: 1)
    }

    private fun searchWord(forward: Boolean, whole: Boolean): VimOutcome {
        val n = countGiven() ?: 1
        val s = snapshot()
        if (s == null) { clearPending(); return VimOutcome(failure) }
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val found = VimSearch.wordPattern(s.b, c, whole) ?: run { clearPending(); return fail(VimNotice.NOT_FOUND) }
        memory.setSearch(found.first, forward, VimSearch.Offset.NONE)
        memory.addHistory(commandLine = false, found.first)
        val re = regexOf(found.first) ?: run { clearPending(); return fail(searchNotice(found.first)) }
        return runMotion(null, n) { b, cur, _ ->
            val budget = newBudget()
            // from the start of the word, so that the word under the cursor is not found again
            when (val r = VimSearch.find(b, re, found.second.coerceAtMost(cur).let { if (forward) found.second else found.second }, forward, n, budget)) {
                VimSearch.Found.None -> Mv(cur, MType.EXCL, cancels = true, notice = VimNotice.NOT_FOUND)
                VimSearch.Found.Limit -> Mv(cur, MType.EXCL, cancels = true, notice = VimNotice.SEARCH_LIMIT)
                is VimSearch.Found.At -> Mv(b.normalOrEnd(r.start), MType.EXCL, jump = true, big = true)
            }
        }
    }

    // ---- the command line ----------------------------------------------------------------------------------------------------------

    private class CmdLine(val kind: Char, val text: StringBuilder = StringBuilder(), var hist: Int = -1, var typed: String = "")

    private fun startCmdline(kind: Char): VimOutcome {
        val line = CmdLine(kind)
        if (kind == ':') {
            if (mode != VimMode.NORMAL) { // from Visual mode: the range of the selection, as Vim puts '<,'> in front
                val s = snapshot() ?: return VimOutcome(failure)
                val shown = shownSelection
                if (shown == null || shown != s.selStart to s.selEnd) { leaveVisual(s); return VimOutcome(VimNotice.CHANGED) }
                val b = s.b
                val lo = minOf(anchor, visualCursor)
                val lineWise = mode == VimMode.VISUAL_LINE
                exVisualLines = b.lineNumber(lo) to b.lineNumber(maxOf(anchor, visualCursor))
                leaveVisual(s)
                val at = if (lineWise) b.lineStart(lo) else b.normal(lo) // Vim leaves the cursor at the start of the selection (column 0 for whole lines)
                io.select(at, at)
                expectedCaret = at
                line.text.append("'<,'>")
            } else exVisualLines = null
        }
        cmd = line
        return VimOutcome()
    }

    private fun cmdKey(line: CmdLine, code: Int): VimOutcome {
        when (code) {
            ESC -> { cmd = null; clearPending(); return VimOutcome() }
            ENTER, 13 -> {
                cmd = null
                if (line.kind != ':') cmdKeys.add(ENTER)
                return runCmdline(line)
            }
            BACKSPACE, 127 -> {
                if (line.text.isEmpty()) { cmd = null; clearPending(); return VimOutcome() }
                val s = line.text.toString()
                line.text.setLength(VimBuf(s).prev(s.length))
                return VimOutcome()
            }
            CTRL_U -> { line.text.setLength(0); return VimOutcome() }
            CTRL_W -> {
                val s = line.text.toString()
                var i = s.length
                while (i > 0 && (s[i - 1] == ' ' || s[i - 1] == '\t')) i--
                if (i > 0) { val k = VimBuf.classOf(s.codePointBefore(i), false); while (i > 0 && VimBuf.classOf(s.codePointBefore(i), false) == k && s[i - 1] != ' ') i-- }
                line.text.setLength(i)
                return VimOutcome()
            }
            HISTORY_PREVIOUS, HISTORY_NEXT -> { history(line, code == HISTORY_PREVIOUS); return VimOutcome() }
        }
        if (code < 32 || code > 0x10FFFF) return VimOutcome()
        if (line.text.length + 2 > MAX_CMDLINE) return VimOutcome()
        if (line.kind != ':') cmdKeys.add(code) // a search that an operator waits for is part of the change
        line.text.appendCodePoint(code)
        line.hist = -1
        return VimOutcome()
    }

    private fun history(line: CmdLine, previous: Boolean) {
        val list = memory.history(commandLine = line.kind == ':')
        if (list.isEmpty()) return
        if (line.hist == -1) { if (!previous) return; line.typed = line.text.toString(); line.hist = list.size }
        val next = if (previous) line.hist - 1 else line.hist + 1
        if (next < 0) return
        if (next >= list.size) { line.hist = -1; line.text.setLength(0); line.text.append(line.typed); return }
        line.hist = next
        line.text.setLength(0)
        line.text.append(list[next])
    }

    private fun runCmdline(line: CmdLine): VimOutcome {
        val text = line.text.toString()
        return if (line.kind == ':') runEx(text) else runSearchLine(line.kind, text)
    }

    private fun runSearchLine(kind: Char, text: String): VimOutcome {
        val (rawPat, rest) = VimSearch.splitPattern(text, kind)
        val pat = VimSearch.unescapeDelimiter(rawPat, kind)
        val offset = if (rest == null) VimSearch.Offset.NONE else (VimSearch.parseOffset(rest) ?: run { clearPending(); return fail(VimNotice.PATTERN_UNSUPPORTED) })
        val pattern = if (pat.isEmpty()) (memory.lastSearch ?: run { clearPending(); return VimOutcome(VimNotice.NO_PATTERN) }) else pat
        val forward = kind == '/'
        val usedOffset = if (pat.isEmpty() && rest == null) memory.searchOffset else offset // a bare "/" keeps the offset of the search before
        memory.setSearch(pattern, forward, usedOffset) // kept only where the capture rule allows it; this search uses its own values either way
        memory.addHistory(commandLine = false, text)
        return searchMotion(pattern, forward, usedOffset, countGiven() ?: 1)
    }

    // ---- ex commands ------------------------------------------------------------------------------------------------------------------

    private inner class ExHostImpl(val text: String) : VimEx.Host {
        override val memory: VimMemory get() = this@VimEngine.memory
        override val budget: VimRegex.Budget = newBudget()
        override val indentWithTab: Boolean get() = rules.indentWithTab()
        override val maxChars: Int get() = MAX_CHARS

        override fun charge(steps: Int): Boolean = chargeSteps(steps)

        override fun copy(chars: Long): Boolean = copyBudget(chars)

        override fun markLine(name: Char): Int? {
            val v = exVisualLines
            if (v != null && name == '<') return v.first
            if (v != null && name == '>') return v.second
            val at = this@VimEngine.memory.markGet(name, text)
            return if (at < 0) null else VimLines.lineOf(text, at)
        }

        override fun setMark(name: Char, line: Int) {
            this@VimEngine.memory.markSet(name, text, VimLines.lineStartOf(text, line))
        }

        override fun normal(text: String, caret: Int, keys: String): Pair<String, Int>? = runNormal(text, caret, keys)

        override fun register(name: Char): Reg? = readRegister(name).first

        /** What :reg shows: the register names and a short start of each text. It goes to the keyboard's own view, never to a Toast or the field. */
        override fun registerListing(): String {
            val out = ArrayList<String>()
            val m = this@VimEngine.memory
            for (n in m.names()) {
                val r = m.get(n) ?: continue
                out.add("\"$n  " + shorten(r.text))
                if (out.size >= LISTING_LINES) break
            }
            m.lastInsert?.let { if (out.size < LISTING_LINES) out.add("\".  " + shorten(it)) }
            m.lastCommand?.let { if (out.size < LISTING_LINES) out.add("\":  " + shorten(it)) }
            m.lastSearch?.let { if (out.size < LISTING_LINES) out.add("\"/  " + shorten(it)) }
            return out.joinToString("\n")
        }

        /** What :marks shows: the mark names and their line and column (no text). */
        override fun markListing(): String {
            val out = ArrayList<String>()
            val m = this@VimEngine.memory
            for (n in m.markNames()) {
                val at = m.markGet(n, text)
                if (at < 0) continue
                val line = VimLines.lineOf(text, at)
                out.add("$n  ${line + 1}:${at - VimLines.lineStartOf(text, line)}")
                if (out.size >= LISTING_LINES) break
            }
            return out.joinToString("\n")
        }

        private fun shorten(t: String): String {
            val one = t.replace("\n", "^J")
            return if (one.length > 36) one.substring(0, 35) + "…" else one
        }
    }

    private fun runEx(text: String): VimOutcome {
        if (text.isBlank()) { clearPending(); return VimOutcome() }
        val s = snapshot() ?: run { clearPending(); return VimOutcome(failure) }
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        memory.addHistory(commandLine = true, text)
        memory.setLastCommand(text)
        val topLevel = !inTx // a command line inside a play spends the budget of the play
        if (topLevel) run = Run(rules.nowMillis() + PLAY_MILLIS)
        val result = VimEx(ExHostImpl(s.text)).run(s.text, c, text)
        if (inTx && (result.notice == VimNotice.PLAY_LIMIT || result.notice == VimNotice.SEARCH_LIMIT)) abortRun(result.notice) // a play stops there, as a whole
        if (topLevel) run = null
        clearPending()
        exVisualLines = null
        result.newSearch?.let { memory.setSearchPattern(it) }
        if (result.notice != null) {
            if (result.cursor != c) { // a command that moved the cursor before it failed
                val at = VimBuf(s.text).normal(result.cursor.coerceIn(0, s.text.length))
                io.select(at, at)
                expectedCaret = at
            }
            return fail(result.notice)
        }
        if (result.undo != 0) {
            if (inTx) return fail(VimNotice.UNDO_IN_PLAY)
            io.history(result.undo == 2)
            return VimOutcome()
        }
        val newText = result.text
        val landing = VimBuf(newText).normal(result.cursor.coerceIn(0, newText.length))
        wantCol = null
        if (newText == s.text) {
            io.select(landing, landing)
            expectedCaret = landing
            mode = VimMode.NORMAL
            return VimOutcome(message = result.message)
        }
        val d = diff(s.text, newText)
        val out = apply(s, Plan(d.first, d.second, newText.substring(d.first, newText.length - (s.text.length - d.second)), landing, insert = false))
        cmdChanged = false // an ex command is not repeated by "."
        return if (out.notice != null) out else VimOutcome(message = result.message)
    }

    /** The smallest range [start, end) of [old] that becomes the rest of [new] when everything before and after it is kept. */
    private fun diff(old: String, new: String): Pair<Int, Int> {
        var p = 0
        val limit = minOf(old.length, new.length)
        while (p < limit && old[p] == new[p]) p++
        if (p > 0 && p < limit + 1 && Character.isHighSurrogate(old[p - 1])) p--
        var q = 0
        while (q < limit - p && old[old.length - 1 - q] == new[new.length - 1 - q]) q++
        if (q > 0 && Character.isLowSurrogate(old[old.length - q])) q--
        return p to old.length - q
    }

    // ---- plays: ".", macros and :normal -------------------------------------------------------------------------------------------------

    /** The text of a play, in memory: what the keys of the play read and write, until the difference is written to the field once. */
    private class MemPort(var text: String, var selStart: Int, var selEnd: Int, val real: VimPort) : VimPort {
        var blocked = false
        /** A change would have made the text longer than the window: it was not made (the engine stops the play). */
        var overflow = false
        override fun read(): VimRead = VimRead.Text(text, selStart, selEnd)
        override fun select(start: Int, end: Int) { selStart = start; selEnd = end }
        override fun replace(start: Int, end: Int, text: String, caret: Int) {
            if (this.text.length.toLong() - (end - start) + text.length > MAX_CHARS) { overflow = true; return } // the new text is not built
            this.text = this.text.substring(0, start) + text + this.text.substring(end)
            selStart = caret
            selEnd = caret
        }
        override fun navigate(nav: VimNav, times: Int) { blocked = true }
        override fun history(redo: Boolean) { blocked = true }
        override fun clipboardRead(): String? = real.clipboardRead()
        override fun clipboardWrite(text: String): Boolean = real.clipboardWrite(text)
    }

    /**
     * Queues [keys] [times] times. A [nested] play (a macro, "@:", "@@" or "." run by the keys of another play) is one level deeper: the queue gets a
     * [PLAY_END] after its keys, which gives the level back when the play ends, and one that would be deeper than [MAX_NEST] stops the whole command.
     */
    private fun enqueue(keys: String, times: Int, nested: Boolean = false): VimOutcome {
        val one = ArrayList<Int>()
        var i = 0
        while (i < keys.length) { val cp = keys.codePointAt(i); one.add(cp); i += Character.charCount(cp) }
        if (one.isEmpty()) return VimOutcome()
        if (nested && nest + 1 > MAX_NEST) {
            abortRun(VimNotice.PLAY_LIMIT) // too deep: nothing is queued and nothing is written
            return fail(VimNotice.PLAY_LIMIT)
        }
        if (playQueue.size.toLong() + one.size.toLong() * times + (if (nested) 1 else 0) > MAX_PLAY_KEYS) {
            abortRun(VimNotice.PLAY_LIMIT) // more keys than a play may run: none are queued and nothing is written
            return fail(VimNotice.PLAY_LIMIT)
        }
        // what a nested play adds goes in front of what the outer one still has to run
        if (nested) { playQueue.addFirst(PLAY_END); nest++ }
        for (t in 0 until times) for (k in one.indices.reversed()) playQueue.addFirst(one[k])
        return VimOutcome()
    }

    /** One key of a play: Esc, a typed character in insert mode, or a command key. */
    private fun feedOne(k: Int, mem: MemPort): VimOutcome {
        if (k == ESC) return escapeInner()
        if (mode == VimMode.INSERT && insertPrefix == null) {
            if (k < 32 && k != '\n'.code && k != '\t'.code && k != 13) return VimOutcome()
            val r = mem.read() as? VimRead.Text ?: return fail()
            val ch = if (k == 13) "\n" else String(Character.toChars(k))
            if (!rules.accepts(ch)) return fail(VimNotice.NOT_ACCEPTED)
            mem.replace(r.selStart, r.selStart, ch, r.selStart + ch.length)
            return VimOutcome()
        }
        if (mode == VimMode.INSERT) return insertKey(k)
        return dispatch(k)
    }

    private fun finishPlay() {
        if (cmd != null) cmd = null
        if (mode == VimMode.INSERT) leaveInsert()
        insertPrefix = null
        clearPending()
    }

    private fun play(keys: String, times: Int, visual: VisualInfo? = null): VimOutcome {
        if (inTx) return enqueue(keys, times, nested = true) // inside a play: the keys join the queue (no recursion), one level deeper
        val s = snapshot() ?: return VimOutcome(failure)
        val c = cursorOf(s) ?: return VimOutcome(VimNotice.SELECTED)
        val mem = MemPort(s.text, c, c, port)
        io = mem
        inTx = true
        replaying = true
        nest = 1
        playQueue.clear()
        playStopped = null
        playFailure = null
        val budget = Run(rules.nowMillis() + PLAY_MILLIS)
        run = budget
        expectedCaret = c
        var notice: VimNotice? = null
        // no try/finally: whatever the play throws goes to the guard, whose reset puts io, inTx, run and replaying back
        if (visual != null) setupVisual(visual, s.b, c)
        val first = enqueue(keys, times)
        if (first.failed) notice = first.notice
        drain(mem)
        playQueue.clear()
        if (playStopped == null && budget.abort == null) finishPlay() else { cmd = null; ins = null; insertPrefix = null; if (mode == VimMode.INSERT) mode = VimMode.NORMAL; clearPending() }
        notice = budget.abort ?: playStopped ?: playFailure ?: notice
        io = port
        inTx = false
        replaying = false
        nest = 0
        run = null
        playQueue.clear()
        if (budget.abort != null) { // a limit (keys, steps, time, size): nothing the play computed is written, and the field is as it was
            mode = VimMode.NORMAL
            resetParser()
            wantCol = null
            bufferEmptied = false
            expectedCaret = c
            return fail(budget.abort)
        }
        return writePlay(s, mem, notice)
    }

    /** Writes what a play did to the field: one text change for the whole difference (or only the selection), after checking the field is as it was. */
    private fun writePlay(s: Snap, mem: MemPort, notice: VimNotice?): VimOutcome {
        val after = mem.text
        val again = port.read()
        if (again !is VimRead.Text || again.text != s.text || again.selStart != s.selStart || again.selEnd != s.selEnd) {
            reset()
            return VimOutcome(VimNotice.CHANGED)
        }
        if (after != s.text) {
            val d = diff(s.text, after)
            val repl = after.substring(d.first, after.length - (s.text.length - d.second))
            if (repl.isNotEmpty() && !rules.accepts(repl)) { reset(); return VimOutcome(VimNotice.NOT_ACCEPTED) }
            port.replace(d.first, d.second, repl, mem.selStart)
            if (memory.marksBase == s.text) memory.marksRemapEdit(s.text, d.first, d.second, repl)
        }
        if (mem.selStart != mem.selEnd || after == s.text) port.select(mem.selStart, mem.selEnd)
        expectedCaret = if (mem.selStart == mem.selEnd) mem.selStart else -1
        if (mode != VimMode.NORMAL && mode != VimMode.INSERT) shownSelection = mem.selStart to mem.selEnd
        return VimOutcome(notice, failed = false)
    }

    /** :normal on a copy of [text]: the keys run as Normal mode commands from [caret]; an incomplete command ends as with Esc. Null: a limit was hit. */
    private fun runNormal(text: String, caret: Int, keys: String): Pair<String, Int>? {
        val wasNest = nest
        if (wasNest + 1 > MAX_NEST) { abortRun(VimNotice.PLAY_LIMIT); return null } // too deep: nothing is built (every level holds a copy of the text)
        if (!copyBudget(playQueue.size.toLong())) return null // the copy of the keys the outer play still has to run, built below
        val mem = MemPort(text, caret, caret, port)
        val savedIo = io
        val wasTx = inTx
        val wasReplay = replaying
        val outerQueue = ArrayList(playQueue) // a play that is running goes on after this one
        val outerStopped = playStopped
        playQueue.clear()
        nest = wasNest + 1
        io = mem
        inTx = true
        replaying = true
        mode = VimMode.NORMAL
        resetParser()
        expectedCaret = caret
        wantCol = null
        enqueueRaw(keys)
        val limit = drain(mem)
        if (!limit) finishPlay()
        io = savedIo
        inTx = wasTx
        replaying = wasReplay
        nest = wasNest
        playQueue.clear()
        playQueue.addAll(outerQueue)
        playStopped = outerStopped
        mode = VimMode.NORMAL
        resetParser()
        return if (limit) null else mem.text to mem.selStart
    }

    private fun enqueueRaw(keys: String) {
        var i = 0
        while (i < keys.length) { val cp = keys.codePointAt(i); playQueue.addLast(cp); i += Character.charCount(cp) }
    }

    /** Runs the queued keys until the queue is empty or a command fails; true when a limit (keys, time, undo) stopped it. */
    private fun drain(mem: MemPort): Boolean {
        val budget = run ?: Run(rules.nowMillis() + PLAY_MILLIS).also { run = it } // a play always has its budget; this only closes the case where it has none
        while (playQueue.isNotEmpty()) {
            val next = playQueue.removeFirst()
            if (next == PLAY_END) { nest--; continue } // a nested play ended: its level is free again (not a key)
            budget.keys++
            if (budget.keys > MAX_PLAY_KEYS || ((budget.keys and 63) == 0 && rules.nowMillis() > budget.deadline)) { abortRun(VimNotice.PLAY_LIMIT); return true }
            val out = feedOne(next, mem)
            if (mem.overflow) { abortRun(VimNotice.PLAY_LIMIT); return true } // a change would make the text longer than the window
            if (budget.abort != null) { playQueue.clear(); return true }
            if (mem.blocked) { playQueue.clear(); playStopped = VimNotice.UNDO_IN_PLAY; return true }
            if (out.failed) {
                playQueue.clear()
                playFailure = out.notice
                if (out.notice == VimNotice.SEARCH_LIMIT) { abortRun(VimNotice.SEARCH_LIMIT); return true } // a search that ran out of steps or time: the play is spent too
                return false
            }
        }
        return false
    }

    private fun playRegister(key: Int): VimOutcome {
        val n = countGiven() ?: 1
        clearPending()
        if (key <= 0 || key > 0xFFFF) return VimOutcome()
        var name = key.toChar()
        if (name == ':') {
            val last = memory.lastCommand ?: return VimOutcome(VimNotice.REGISTER_EMPTY)
            return play(":" + last + "\n", n)
        }
        if (name == '@') name = memory.lastMacro ?: return VimOutcome(VimNotice.REGISTER_EMPTY)
        if (!(name in 'a'..'z' || name in 'A'..'Z' || name in '0'..'9' || name == '"' || name == '-' || name == '.' || name == '/')) return VimOutcome()
        val reg = memory.get(name) ?: return VimOutcome(VimNotice.REGISTER_EMPTY)
        memory.setLastMacro(name)
        val text = if (reg.type == RegType.LINE) reg.text + "\n" else reg.text
        return play(text, n)
    }

    private fun repeatDot(): VimOutcome {
        val newCount = countGiven()
        clearPending()
        val d = when (val r = memory.dotRepeat(newCount)) {
            DotRepeat.None -> return VimOutcome(VimNotice.NOTHING_TO_REPEAT)
            DotRepeat.Broken -> return VimOutcome(VimNotice.NOT_REPEATABLE)
            is DotRepeat.Ready -> r.play
        }
        val sb = StringBuilder()
        d.reg?.let { sb.append('"').append(it) }
        d.count?.let { sb.append(it) }
        for (k in d.keys) sb.appendCodePoint(k)
        return play(sb.toString(), 1, d.visual)
    }

    private fun undoRedo(redo: Boolean): VimOutcome {
        clearPending()
        if (inTx) return fail(VimNotice.UNDO_IN_PLAY)
        io.history(redo)
        return VimOutcome()
    }

    /** The Visual selection a repeated Visual command works on: the size of the one it was made with, from the cursor. */
    private fun setupVisual(info: VisualInfo, b: VimBuf, c: Int) {
        mode = info.mode
        anchor = c
        val line = b.lineNumber(c)
        val endLine = minOf(line + info.lines - 1, b.lineCount() - 1)
        val endStart = b.startOfLine(endLine)
        blockDollar = false
        visualCursor = when {
            info.mode == VimMode.VISUAL_LINE -> b.atColumn(endStart, b.column(c)).let { if (it >= b.lineEnd(it)) b.lastChar(it) else it }
            info.mode == VimMode.VISUAL_BLOCK -> {
                val width = info.width
                if (width == Int.MAX_VALUE) blockDollar = true else blockRight = b.column(c) + width - 1
                b.atColumn(endStart, if (width == Int.MAX_VALUE) 0 else b.column(c) + width - 1).let { if (it >= b.lineEnd(it)) b.lastChar(it) else it }
            }
            info.lines == 1 -> b.atColumn(endStart, b.column(c) + info.width - 1) // may be the end of the line: the selection then takes the line break
            else -> b.atColumn(endStart, info.width - 1)
        }
        showVisual(b)
    }

    private fun resetParser() {
        clearPending()
        cmd = null
        ins = null
        insertPrefix = null
        returnToInsert = false
        anchor = -1
        visualCursor = -1
        blockDollar = false
        blockRight = null
        shownSelection = null
        exVisualLines = null
        cmdKeys.clear()
        cmdDropped = false
        cmdChanged = false
        visualDot = null
        memory.dotCloseInsert()
    }

    companion object {
        /** The most characters read from a field: a longer field is not edited (about 20 pages of text). */
        const val MAX_CHARS = 20_000
        const val MAX_COUNT = 999
        const val ESC = 27
        /** Ctrl-R: redo in Normal mode, insert a register in insert mode and in the command line. */
        const val CTRL_R = 18
        const val CTRL_A = 1
        const val CTRL_B = 2
        const val CTRL_D = 4
        const val CTRL_E = 5
        const val CTRL_F = 6
        const val BACKSPACE = 8
        const val ENTER = 10
        const val CTRL_O = 15
        const val CTRL_U = 21
        const val CTRL_V = 22
        const val CTRL_W = 23
        const val CTRL_X = 24
        const val CTRL_Y = 25
        const val CTRL_H = 8
        /** The codes of the popup keys ^A, ^X and ^V of the Vim layouts (private use characters, so that they can never be typed text). */
        const val KEY_CTRL_A = 0xE001
        const val KEY_CTRL_X = 0xE002
        const val KEY_CTRL_V = 0xE003
        /** Keys of the command line: the history up and down. */
        const val HISTORY_PREVIOUS = -101
        const val HISTORY_NEXT = -102
        /** A macro holds at most this many keys (the memory owns the number); one play runs at most [MAX_PLAY_KEYS] keys, nested plays included. */
        const val MAX_MACRO_KEYS = VimMemory.MAX_MACRO_KEYS
        const val MAX_PLAY_KEYS = 20_000
        /** Executions open inside one another (a macro in a macro, :normal in :normal, @: in :normal, a repeat): at most this many levels, a deeper one stops the command. */
        const val MAX_NEST = 10
        /** The steps of the ex commands of one command line (a line, an iteration of :g, a :normal run, a command), counted in the same budget as the keys. */
        const val MAX_EX_STEPS = 100_000
        const val PLAY_MILLIS = 2_500L
        const val SEARCH_STEPS = 3_000_000
        const val SEARCH_MILLIS = 400L
        const val MAX_HISTORY = VimMemory.MAX_HISTORY
        const val MAX_CMDLINE = 1_000
        /** The text copies that one command (a play, an ex line) may build, in characters: the line list of every ex run, the text of every :normal call and of its result. */
        const val MAX_RUN_CHARS = 2_000_000L
        /** In the queue of a play: the end of a nested play (a level is given back). Not a key; no key code is this value. */
        private const val PLAY_END = Int.MIN_VALUE
        private const val LISTING_LINES = 12
        private const val GG = -2
        private val ARG_PREFIXES = setOf('f', 'F', 't', 'T', 'r', '"', 'q', '@', 'm', '\'', '`', '\u0001', '\u0002')
    }
}
