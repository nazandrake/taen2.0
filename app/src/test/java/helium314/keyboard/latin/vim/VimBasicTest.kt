// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals

/** The core of Normal mode (Esc, h, l, w, dw, i). Expected values are what Vim does (see VimGoldenTest for the full table made by Vim itself). */
class VimBasicTest {
    private fun setup(text: String, caret: Int): Pair<VimEngine, FakeVimField> {
        val field = FakeVimField(text, caret)
        return VimEngine(field, TestRules()) to field
    }

    @Test fun escFromTypingEntersNormalModeOneCharacterLeft() {
        val (e, f) = setup("hello world", 5)
        e.escape()
        assertEquals(VimMode.NORMAL, e.mode)
        assertEquals(4, f.selStart)
        assertEquals(4, f.selEnd)
    }

    @Test fun hAndLMoveByOneCharacter() {
        val (e, f) = setup("hello", 2)
        e.escape() // caret 1
        e.key('l'.code)
        assertEquals(2, f.selStart)
        e.key('h'.code)
        e.key('h'.code)
        assertEquals(0, f.selStart)
        e.key('h'.code) // at the start of the line: stays
        assertEquals(0, f.selStart)
    }

    @Test fun wMovesToTheStartOfTheNextWord() {
        val (e, f) = setup("foo bar baz", 1)
        e.escape() // caret 0
        e.key('w'.code)
        assertEquals(4, f.selStart)
        e.key('w'.code)
        assertEquals(8, f.selStart)
    }

    @Test fun dwDeletesToTheNextWordAndKeepsItInTheRegister() {
        val (e, f) = setup("foo bar baz", 1)
        e.escape()
        e.feed(f, "dw")
        assertEquals("bar baz", f.text)
        assertEquals(0, f.selStart)
        assertEquals("foo ", e.register)
    }

    @Test fun iReturnsToTypingAtTheCaret() {
        val (e, f) = setup("abc", 2)
        e.escape() // caret 1
        e.key('i'.code)
        assertEquals(VimMode.INSERT, e.mode)
        assertEquals(1, f.selStart)
        e.feed(f, "X")
        assertEquals("aXbc", f.text)
    }
}
