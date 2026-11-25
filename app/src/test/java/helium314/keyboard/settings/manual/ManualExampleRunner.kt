// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import helium314.keyboard.latin.vim.FakeVimField
import helium314.keyboard.latin.vim.TestRules
import helium314.keyboard.latin.vim.VimBuf
import helium314.keyboard.latin.vim.VimEngine
import helium314.keyboard.latin.vim.VimMode
import helium314.keyboard.latin.vim.feed

/** Runs a Vim example of the manual through the real engine the way the golden table of the Vim tests runs a case. */
object ManualExampleRunner {
    /** What differs between the engine's result and the "After" of the example (null when they are equal). */
    fun run(e: Block.Example): String? {
        val before = ManualExample.place(e.before) ?: return "the text before has no single cursor marker"
        val after = ManualExample.place(e.after) ?: return "the text after has no single cursor marker"
        val text = before.text
        val buf = VimBuf(text)
        val caret = before.cursor
        // Normal mode stands on a character, or on an empty line
        if (caret < text.length && text[caret] == '\n' && !(caret == 0 || text[caret - 1] == '\n')) return "the cursor is not on a character"
        if (caret == text.length && !(text.isEmpty() || text.endsWith("\n"))) return "the cursor is after the last character"
        // the typing caret that leaves the cursor on this character after Esc: just after it (an empty line has only one place)
        val insertCaret = if (caret < buf.lineEnd(caret)) buf.next(caret) else caret
        val field = FakeVimField(text, insertCaret)
        val engine = VimEngine(field, TestRules())
        engine.escape()
        if (field.selStart != caret || field.selEnd != caret) return "Esc left the caret at ${field.selStart}, expected $caret"
        engine.feed(field, e.keys.replace("<Space>", " "), stopOnFail = false)
        val problems = ArrayList<String>()
        if (engine.mode != VimMode.NORMAL) problems.add("the mode is ${engine.mode}, expected NORMAL (end the keys with <Esc>)")
        if (field.text != after.text) problems.add("text '${field.text}' expected '${after.text}'")
        if (field.selStart != field.selEnd || field.selStart != after.cursor) problems.add("cursor ${field.selStart}-${field.selEnd} expected ${after.cursor}")
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }
}
