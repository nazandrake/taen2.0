// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** A text field for the Vim tests: it applies and records every call; [read] can be overridden to model an editor that cannot give text. */
class FakeVimField(var text: String, var selStart: Int = 0, var selEnd: Int = selStart) : VimPort {
    val calls = ArrayList<String>()
    var reads = 0
    /** Replaces the answer of a read (null: the field's own text). */
    var readAnswer: ((Int) -> VimRead?)? = null
    /** Runs on every read before it answers, with the number of the read: a test changes the field "meanwhile" here. */
    var onRead: ((Int) -> Unit)? = null

    override fun read(): VimRead {
        reads++
        onRead?.invoke(reads)
        return readAnswer?.invoke(reads) ?: VimRead.Text(text, selStart, selEnd)
    }

    /** The column an editor keeps while the cursor goes up and down (reset by anything else). */
    private var wantColumn: Int? = null
    /** Set when the cursor could not go up or down (an editor does nothing there; Vim beeps and drops the typed-ahead keys, which [feed] copies). */
    var navFailed = false

    override fun select(start: Int, end: Int) {
        calls.add("select $start,$end")
        selStart = start
        selEnd = end
        wantColumn = null
    }

    override fun replace(start: Int, end: Int, text: String, caret: Int) {
        calls.add("replace $start,$end")
        this.text = this.text.substring(0, start) + text + this.text.substring(end)
        selStart = caret
        selEnd = caret
        wantColumn = null
    }

    override fun navigate(nav: VimNav, times: Int) {
        calls.add("nav $nav $times")
        // an editor moves by lines of the text here (nothing wraps in the tests)
        if (nav != VimNav.UP && nav != VimNav.DOWN) return
        repeat(times) {
            val b = VimBuf(text)
            val c = selStart
            val line = b.lineNumber(c)
            val target = if (nav == VimNav.DOWN) line + 1 else line - 1
            if (target < 0 || target >= b.lineCount()) { navFailed = true; return }
            val col = wantColumn ?: b.column(c)
            val at = b.atColumn(b.startOfLine(target), col)
            val pos = if (at >= b.lineEnd(at) && at > b.lineStart(at)) b.lastChar(at) else at
            selStart = pos
            selEnd = pos
            wantColumn = col
        }
    }

    override fun history(redo: Boolean) {
        calls.add(if (redo) "redo" else "undo")
    }

    /** The system clipboard of the fake: what the clipboard owners would hold. */
    var clipboard: String? = null
    /** True makes the clipboard owner refuse a write. */
    var clipboardRefuses = false

    override fun clipboardRead(): String? {
        calls.add("clipboardRead")
        return clipboard
    }

    override fun clipboardWrite(text: String): Boolean {
        calls.add("clipboardWrite")
        if (clipboardRefuses) return false
        clipboard = text
        return true
    }

    /** Backspace of the typing keyboard: the character before the caret goes. */
    fun backspace() {
        if (selStart != selEnd) { type(""); return }
        if (selStart == 0) return
        val from = if (selStart >= 2 && Character.isLowSurrogate(text[selStart - 1]) && Character.isHighSurrogate(text[selStart - 2])) selStart - 2 else selStart - 1
        text = text.substring(0, from) + text.substring(selStart)
        selStart = from
        selEnd = from
    }

    /** Types [typed] at the caret, as the typing keyboard does while the engine is in insert mode. */
    fun type(typed: String) {
        text = text.substring(0, minOf(selStart, selEnd)) + typed + text.substring(maxOf(selStart, selEnd))
        selStart = minOf(selStart, selEnd) + typed.length
        selEnd = selStart
    }

    val editCalls get() = calls.filter { it.startsWith("replace") }
}

class TestRules(var available: Boolean = true, var registerAllowed: Boolean = true, var multiLine: Boolean = true, var tabs: Boolean = false) : VimRules {
    /** The clock of the rules: a test moves it to spend a time limit. */
    var clock = 0L
    /** Added to the clock at every reading, so that a limit in time is reached by reading it often enough. */
    var tick = 0L
    override fun available() = available
    override fun registerAllowed() = registerAllowed
    override fun accepts(text: String) = multiLine || '\n' !in text
    override fun nowMillis(): Long { clock += tick; return clock }
    override fun indentWithTab() = tabs
}

/**
 * Feeds [keys] to the engine the way the keyboard does: each char is a key; while the engine is in insert mode the char is typed instead.
 * With [stopOnFail] a command that fails ends the sequence, as a beep makes ":normal!" drop the keys typed ahead; without it the keys go on,
 * as typed keys do (Vim keeps typed keys after a beep; only a macro or a repeat is stopped).
 */
fun VimEngine.feed(field: FakeVimField, keys: String, stopOnFail: Boolean = true) {
    var i = 0
    field.navFailed = false
    while (i < keys.length) {
        val token = TOKENS.entries.firstOrNull { keys.startsWith(it.key, i) }
        val typing = mode == VimMode.INSERT && !wantsChar
        if (token != null) {
            i += token.key.length
            if (typing && token.key == "<CR>") { field.type("\n"); continue }
            if (typing && token.key == "<BS>") { field.backspace(); continue }
            val out = if (token.value == VimEngine.ESC) escape() else key(token.value)
            if (out.failed && stopOnFail) return
            continue
        }
        val cp = keys.codePointAt(i)
        i += Character.charCount(cp)
        if (typing) field.type(String(Character.toChars(cp))) else if (key(cp).failed && stopOnFail) return
        if (field.navFailed && stopOnFail) return // the motion that could not move: Vim drops the keys typed ahead
        field.navFailed = false
    }
}

/** The control keys that a key string can name, with the code the keyboard gives the engine for each. */
val TOKENS: Map<String, Int> = linkedMapOf(
    "<Esc>" to VimEngine.ESC, "<CR>" to VimEngine.ENTER, "<BS>" to VimEngine.BACKSPACE, "<C-r>" to VimEngine.CTRL_R, "<C-a>" to VimEngine.CTRL_A,
    "<C-x>" to VimEngine.CTRL_X, "<C-v>" to VimEngine.CTRL_V, "<C-w>" to VimEngine.CTRL_W, "<C-u>" to VimEngine.CTRL_U, "<C-h>" to VimEngine.CTRL_H,
    "<C-o>" to VimEngine.CTRL_O, "<Up>" to VimEngine.HISTORY_PREVIOUS, "<Down>" to VimEngine.HISTORY_NEXT,
)
