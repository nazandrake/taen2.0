// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Beyond the table made by Vim: counts, edges, Visual display, the register rules, the password rule, and the fail-closed cases. */
class VimEngineTest {
    private class Rig(text: String, caret: Int, val rules: TestRules = TestRules()) {
        val field = FakeVimField(text, caret)
        val engine = VimEngine(field, rules)
        init { engine.escape() }
        fun keys(k: String) = engine.feed(field, k)
        fun press(k: Char) = engine.key(k.code)
    }

    // ---- counts -------------------------------------------------------------------------------------------------------

    @Test fun countsMultiplyAndShowAsPending() {
        val r = Rig("a b c d e f g h", 1)
        r.keys("2d")
        assertEquals("2d", r.engine.pending)
        r.keys("3")
        assertEquals("2d3", r.engine.pending)
        r.keys("w") // 2 * 3 = 6 words
        assertEquals("g h", r.field.text)
        assertEquals("", r.engine.pending)
    }

    @Test fun aCountIsCappedAt999() {
        val text = "x".repeat(2000)
        val r = Rig(text, 1)
        r.keys("0") // a zero without a count is the motion
        assertEquals(0, r.field.selStart)
        r.keys("99999l")
        assertEquals(999, r.field.selStart)
    }

    @Test fun zeroAfterADigitIsPartOfTheCount() {
        val r = Rig("x".repeat(40), 1)
        r.keys("10l")
        assertEquals(10, r.field.selStart)
    }

    // ---- buffer edges ---------------------------------------------------------------------------------------------------

    @Test fun commandsOnAnEmptyFieldDoNothing() {
        val r = Rig("", 0)
        r.keys("xXDddhlwbe\$0^GggrXdwdbd\$")
        assertEquals("", r.field.text)
        assertEquals(0, r.field.selStart)
        assertEquals(emptyList(), r.field.editCalls)
        assertEquals(VimMode.NORMAL, r.engine.mode)
    }

    @Test fun aSurrogatePairIsOneCharacter() {
        val r = Rig("a😀b", 3) // caret after the emoji: Esc puts the cursor on it
        assertEquals(1, r.field.selStart)
        r.keys("x")
        assertEquals("ab", r.field.text)
        assertEquals("😀", r.engine.register)
    }

    @Test fun rReplacesWithACharacterOutsideTheBasicPlane() {
        val r = Rig("abc", 1)
        r.keys("r😀")
        assertEquals("😀bc", r.field.text)
        assertEquals(0, r.field.selStart) // the cursor is on the new character
    }

    @Test fun anAccentedLetterMovesAsOneCharacter() {
        val r = Rig("café x", 4)
        assertEquals(3, r.field.selStart)
        r.keys("l")
        assertEquals(5, r.field.selStart) // over the accent to the space
        r.keys("h")
        assertEquals(3, r.field.selStart)
    }

    @Test fun aCountedTextObjectDoesNothing() {
        val r = Rig("foo bar baz", 1)
        r.keys("d2iw")
        assertEquals("foo bar baz", r.field.text)
        assertEquals(emptyList(), r.field.editCalls)
    }

    // ---- modes and pending state ------------------------------------------------------------------------------------------

    @Test fun escCancelsAPendingOperator() {
        val r = Rig("foo bar", 1)
        r.keys("d")
        r.engine.escape()
        r.keys("w")
        assertEquals("foo bar", r.field.text)
        assertEquals(4, r.field.selStart)
    }

    @Test fun insertCommandsLeaveVimAndTypingIsNotSeenByTheEngine() {
        for ((k, caret, expected) in listOf(Triple("i", 3, "ab[x]cd"), Triple("a", 3, "abc[x]d"), Triple("I", 3, "[x]abcd"), Triple("A", 3, "abcd[x]"))) {
            val r = Rig("abcd", caret)
            r.keys(k)
            assertEquals(VimMode.INSERT, r.engine.mode, k)
            assertFalse(r.engine.key('x'.code).handled, k) // typing mode: not handled, the typing path takes the key
            r.field.type("[x]")
            assertEquals(expected, r.field.text, k)
        }
    }

    @Test fun oAndOOpenALine() {
        val r = Rig("one\ntwo", 2)
        r.keys("o")
        assertEquals("one\n\ntwo", r.field.text)
        assertEquals(4, r.field.selStart)
        assertEquals(VimMode.INSERT, r.engine.mode)
        val q = Rig("one\ntwo", 6)
        q.keys("O")
        assertEquals("one\n\ntwo", q.field.text)
        assertEquals(4, q.field.selStart)
    }

    @Test fun aCharacterArgumentAsksForTheTypingKeyboard() {
        val r = Rig("a,b,c", 1)
        assertFalse(r.engine.wantsChar)
        r.keys("f")
        assertTrue(r.engine.wantsChar)
        r.keys(",")
        assertFalse(r.engine.wantsChar)
        assertEquals(1, r.field.selStart)
        r.keys("r")
        assertTrue(r.engine.wantsChar)
        r.keys("z")
        assertEquals("a,b,c".replaceRange(1, 2, "z"), r.field.text)
    }

    @Test fun undoAndRedoGoThroughTheKeyboardsOwnMechanism() {
        val r = Rig("abc", 1)
        r.keys("u")
        r.engine.key(VimEngine.CTRL_R)
        assertEquals(listOf("undo", "redo"), r.field.calls.filter { it == "undo" || it == "redo" })
    }

    @Test fun plainJAndKUseTheCursorKeysAndOperatorFormsUseLines() {
        val r = Rig("ab\ncd\nef", 1)
        r.keys("3j")
        assertEquals("nav DOWN 3", r.field.calls.last())
        r.keys("k")
        assertEquals("nav UP 1", r.field.calls.last())
        r.keys("dj") // the operator form works on the text: the line and the one below
        assertEquals("ef", r.field.text)
    }

    // ---- Visual mode --------------------------------------------------------------------------------------------------------

    @Test fun visualShowsTheSelectionAndLeavesWithEsc() {
        val r = Rig("foo bar baz", 1)
        r.keys("vw")
        assertEquals(VimMode.VISUAL, r.engine.mode)
        assertEquals(0 to 5, r.field.selStart to r.field.selEnd) // from the anchor (0) through the character under the cursor (4)
        r.engine.escape()
        assertEquals(VimMode.NORMAL, r.engine.mode)
        assertEquals(4 to 4, r.field.selStart to r.field.selEnd)
    }

    @Test fun visualLineSelectsWholeLinesAndVSwitchesBackAndForth() {
        val r = Rig("ab\ncd\nef", 4)
        r.keys("V")
        assertEquals(VimMode.VISUAL_LINE, r.engine.mode)
        assertEquals(3 to 6, r.field.selStart to r.field.selEnd)
        r.keys("j")
        assertEquals(3 to 8, r.field.selStart to r.field.selEnd)
        r.keys("v")
        assertEquals(VimMode.VISUAL, r.engine.mode)
        r.keys("v")
        assertEquals(VimMode.NORMAL, r.engine.mode)
    }

    @Test fun visualOSwapsTheEnds() {
        val r = Rig("foo bar baz", 1)
        r.keys("vwol") // anchor 4, cursor 0 -> 1: the characters 1 to 4 (Vim: "far baz", register "oo b")
        assertEquals(1 to 5, r.field.selStart to r.field.selEnd)
        r.keys("d")
        assertEquals("far baz", r.field.text)
        assertEquals("oo b", r.engine.register)
        assertEquals(1, r.field.selStart)
    }

    @Test fun aSelectionChangedByTheUserEndsVisualWithoutAnEdit() {
        val r = Rig("foo bar baz", 1)
        r.keys("vl")
        r.field.selStart = 2 // the user moved the selection handle
        r.field.selEnd = 7
        val out = r.engine.key('d'.code)
        assertEquals(VimNotice.CHANGED, out.notice)
        assertEquals("foo bar baz", r.field.text)
        assertEquals(VimMode.NORMAL, r.engine.mode)
        assertEquals(emptyList(), r.field.editCalls)
    }

    @Test fun aVisualMotionAfterTheUserChangedTheSelectionEndsVisualWithoutMoving() {
        val r = Rig("foo bar baz", 1)
        r.keys("vl")
        r.field.selStart = 2 // the user moved the selection handle
        r.field.selEnd = 7
        r.field.calls.clear()
        val out = r.engine.key('l'.code)
        assertEquals(VimNotice.CHANGED, out.notice)
        assertEquals(VimMode.NORMAL, r.engine.mode)
        assertEquals(emptyList(), r.field.calls.filter { it.startsWith("nav") })
    }

    @Test fun visualModeNeverFallsBackToCursorKeys() {
        val r = Rig("foo bar baz", 1)
        r.keys("v")
        r.field.readAnswer = { VimRead.Unreadable }
        r.field.calls.clear()
        val out = r.engine.key('l'.code)
        assertEquals(VimNotice.NO_TEXT, out.notice) // the selection could not be kept in step without the text
        assertEquals(emptyList(), r.field.calls.filter { it.startsWith("nav") })
    }

    @Test fun aVisualYankWhereTheRegisterIsOffKeepsNothing() {
        val r = Rig("foo bar baz", 1, TestRules(registerAllowed = false))
        r.keys("vw")
        val out = r.engine.key('y'.code)
        assertEquals(VimNotice.REGISTER_OFF, out.notice)
        assertNull(r.engine.register)
        assertEquals(VimMode.NORMAL, r.engine.mode)
        assertEquals("foo bar baz", r.field.text)
    }

    // ---- the register -----------------------------------------------------------------------------------------------------

    @Test fun theRegisterIsFilledOnlyWhenThePrivacyDecisionAllowsIt() {
        val allowed = Rig("foo bar", 1)
        allowed.keys("dw")
        assertEquals("foo ", allowed.engine.register)
        val denied = Rig("foo bar", 1, TestRules(registerAllowed = false))
        denied.keys("dw")
        assertEquals("bar", denied.field.text) // the edit itself still happens
        assertNull(denied.engine.register)
        val yank = denied.engine.key('y'.code).let { denied.engine.key('w'.code) }
        assertEquals(VimNotice.REGISTER_OFF, yank.notice)
        assertNull(denied.engine.register)
    }

    @Test fun aDeniedFieldClearsWhatWasInTheRegister() {
        val r = Rig("foo bar baz", 1)
        r.keys("dw")
        assertEquals("foo ", r.engine.register)
        r.rules.registerAllowed = false
        r.keys("dw")
        assertNull(r.engine.register)
    }

    @Test fun clearRegisterEmptiesItAndPutThenSaysSo() {
        val r = Rig("foo bar", 1)
        r.keys("yw")
        assertEquals("foo ", r.engine.register)
        r.engine.clearRegister()
        assertNull(r.engine.register)
        val out = r.engine.key('p'.code)
        assertEquals(VimNotice.REGISTER_EMPTY, out.notice)
        assertEquals("foo bar", r.field.text)
    }

    @Test fun putInsertsThroughReplaceOnly() {
        val r = Rig("foo bar", 1)
        r.keys("ywP")
        assertEquals("foo foo bar", r.field.text)
        assertEquals(listOf("replace 0,0"), r.field.editCalls)
    }

    // ---- the password rule ------------------------------------------------------------------------------------------------

    @Test fun nothingWorksWhereVimIsNotAvailable() {
        val rules = TestRules()
        val r = Rig("foo bar", 1, rules)
        r.keys("v")
        rules.available = false // the field became a password field
        val out = r.engine.key('d'.code)
        assertEquals(VimNotice.UNAVAILABLE, out.notice)
        assertEquals(VimMode.INSERT, r.engine.mode)
        assertEquals("foo bar", r.field.text)
        assertEquals(emptyList(), r.field.editCalls)
        val reads = r.field.reads
        assertEquals(VimNotice.UNAVAILABLE, r.engine.escape().notice) // the Esc key does not enter Normal mode either
        assertEquals(VimMode.INSERT, r.engine.mode)
        assertEquals(reads, r.field.reads) // and the field text is not read
    }

    // ---- fail closed ------------------------------------------------------------------------------------------------------

    @Test fun anEditWithUnreadableTextDoesNothingAndSaysWhy() {
        for ((answer, notice) in listOf(VimRead.Unreadable to VimNotice.NO_TEXT, VimRead.Partial to VimNotice.PARTIAL_TEXT, VimRead.TooLong to VimNotice.TOO_LONG)) {
            val r = Rig("foo bar", 1)
            r.field.readAnswer = { answer }
            r.press('d')
            val out = r.press('w')
            assertEquals(notice, out.notice)
            assertEquals("foo bar", r.field.text)
            assertEquals(emptyList(), r.field.editCalls)
            assertEquals(emptyList(), r.field.calls.filter { it.startsWith("nav") }, "no cursor key stands in for the motion of an edit")
            // positive control: the same keys with a readable field edit
            val ok = Rig("foo bar", 1)
            ok.keys("dw")
            assertEquals("bar", ok.field.text)
        }
    }

    @Test fun aPureCursorMotionFallsBackToCursorKeysWhenTheTextCannotBeRead() {
        val expected = mapOf('h' to "nav LEFT 1", 'l' to "nav RIGHT 1", 'w' to "nav WORD_RIGHT 1", 'b' to "nav WORD_LEFT 1", '0' to "nav LINE_START 1", '$' to "nav LINE_END 1",
            'G' to "nav TEXT_END 1")
        for ((k, nav) in expected) {
            val r = Rig("foo bar", 1)
            r.field.readAnswer = { VimRead.Unreadable }
            r.press(k)
            assertEquals(nav, r.field.calls.last(), k.toString())
            assertEquals(emptyList(), r.field.editCalls)
        }
        val r = Rig("foo bar", 1)
        r.field.readAnswer = { VimRead.Partial }
        r.keys("3l")
        assertEquals("nav RIGHT 3", r.field.calls.last())
    }

    @Test fun aFieldThatChangesBetweenTheReadAndTheEditIsNotEdited() {
        val r = Rig("foo bar", 1)
        r.press('d')
        val before = r.field.reads
        r.field.onRead = { n -> if (n == before + 2) r.field.text = "xxx bar" } // the second read of the command (just before the edit) sees other text
        val out = r.press('w')
        assertEquals(VimNotice.CHANGED, out.notice)
        assertEquals("xxx bar", r.field.text)
        assertEquals(emptyList(), r.field.editCalls)
    }

    @Test fun aSelectionThatChangesBetweenTheReadAndTheEditIsNotEdited() {
        val r = Rig("foo bar", 1)
        val before = r.field.reads
        r.field.onRead = { n -> if (n == before + 2) { r.field.selStart = 3; r.field.selEnd = 3 } }
        val out = r.press('x')
        assertEquals(VimNotice.CHANGED, out.notice)
        assertEquals("foo bar", r.field.text)
        assertEquals(emptyList(), r.field.editCalls)
    }

    @Test fun anUnchangedFieldIsEditedWithExactlyTheComputedRange() {
        val r = Rig("foo bar baz", 5)
        r.keys("dw")
        assertEquals(listOf("replace 4,8"), r.field.editCalls)
        assertEquals("foo baz", r.field.text)
        val q = Rig("foo bar baz", 1)
        q.keys("D")
        assertEquals(listOf("replace 0,11"), q.field.editCalls)
    }

    @Test fun textThatIsSelectedIsNotEditedUntilEscCollapsesIt() {
        val r = Rig("foo bar", 1)
        r.field.selStart = 0
        r.field.selEnd = 3
        assertEquals(VimNotice.SELECTED, r.press('x').notice)
        assertEquals("foo bar", r.field.text)
        r.engine.escape()
        assertEquals(0 to 0, r.field.selStart to r.field.selEnd)
        r.press('x')
        assertEquals("oo bar", r.field.text)
    }

    @Test fun textTheFieldDoesNotTakeIsNotInserted() {
        val single = Rig("one two", 1, TestRules(multiLine = false))
        assertEquals(VimNotice.NOT_ACCEPTED, single.press('o').notice) // a line break in a single-line field
        assertEquals("one two", single.field.text)
        assertEquals(VimMode.NORMAL, single.engine.mode)
        single.keys("yy")
        assertEquals(VimNotice.NOT_ACCEPTED, single.press('P').notice) // a yanked line pastes with a line break
        assertEquals("one two", single.field.text)
        val multi = Rig("one two", 1)
        multi.press('o')
        assertEquals("one two\n", multi.field.text)
    }

    @Test fun aNormalCursorOnTheEndOfTheLineActsOnTheLastCharacter() {
        val r = Rig("abc\ndef", 1)
        r.field.selStart = 3 // the user taps after the "c"
        r.field.selEnd = 3
        r.keys("x")
        assertEquals("ab\ndef", r.field.text)
    }

    @Test fun resetReturnsToTypingAndForgetsWhatIsPending() {
        val r = Rig("abc", 2)
        assertEquals(VimMode.NORMAL, r.engine.mode)
        r.keys("2d")
        assertEquals("2d", r.engine.pending)
        r.engine.reset()
        assertEquals(VimMode.INSERT, r.engine.mode)
        assertEquals("", r.engine.pending)
    }
}
