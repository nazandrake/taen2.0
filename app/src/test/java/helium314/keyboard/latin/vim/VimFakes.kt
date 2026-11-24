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

    override fun select(start: Int, end: Int) {
        calls.add("select $start,$end")
        selStart = start
        selEnd = end
    }

    override fun replace(start: Int, end: Int, text: String, caret: Int) {
        calls.add("replace $start,$end")
        this.text = this.text.substring(0, start) + text + this.text.substring(end)
        selStart = caret
        selEnd = caret
    }

    override fun navigate(nav: VimNav, times: Int) {
        calls.add("nav $nav $times")
    }

    override fun history(redo: Boolean) {
        calls.add(if (redo) "redo" else "undo")
    }

    /** Types [typed] at the caret, as the typing keyboard does while the engine is in insert mode. */
    fun type(typed: String) {
        text = text.substring(0, minOf(selStart, selEnd)) + typed + text.substring(maxOf(selStart, selEnd))
        selStart = minOf(selStart, selEnd) + typed.length
        selEnd = selStart
    }

    val editCalls get() = calls.filter { it.startsWith("replace") }
}

class TestRules(var available: Boolean = true, var registerAllowed: Boolean = true, var multiLine: Boolean = true) : VimRules {
    override fun available() = available
    override fun registerAllowed() = registerAllowed
    override fun accepts(text: String) = multiLine || '\n' !in text
}

/**
 * Feeds [keys] to the engine the way the keyboard does: each char is a key; while the engine is in insert mode the char is typed instead.
 * A command that fails ends the sequence, as a beep makes Vim drop the keys typed ahead.
 */
fun VimEngine.feed(field: FakeVimField, keys: String) {
    var i = 0
    while (i < keys.length) {
        if (keys.startsWith("<Esc>", i)) {
            if (escape().failed) return
            i += 5
            continue
        }
        val cp = keys.codePointAt(i)
        i += Character.charCount(cp)
        if (mode == VimMode.INSERT) field.type(String(Character.toChars(cp))) else if (key(cp).failed) return
    }
}
