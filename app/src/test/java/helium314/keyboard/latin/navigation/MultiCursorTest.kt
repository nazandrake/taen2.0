// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiCursorTest {
    private fun cursor(field: FakeField, clock: FakeClock = FakeClock(), allowed: () -> Boolean = { true }) = MultiCursor(field, clock, allowed)

    private fun MultiCursor.next(field: FakeField) = addNextOccurrence(field.selStart, field.selEnd)

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun nextOccurrenceFromACaretSelectsWholeWordsOneByOne() {
        val field = FakeField("ab ab xab ab", 1)
        val multi = cursor(field)
        assertEquals(MultiCursorResult.Ranges(1, 3), multi.addNextOccurrence(1, 1))
        assertEquals(TextRange(0, 2), TextRange(field.selStart, field.selEnd))
        assertEquals(MultiCursorResult.Ranges(2, 3), multi.next(field))
        assertEquals(TextRange(3, 5), TextRange(field.selStart, field.selEnd))
        assertEquals(MultiCursorResult.Ranges(3, 3), multi.next(field))
        assertEquals(TextRange(10, 12), TextRange(field.selStart, field.selEnd))
        assertEquals(MultiCursorResult.AllSelected(3), multi.next(field))
        assertEquals(listOf(TextRange(0, 2), TextRange(3, 5), TextRange(10, 12)), multi.heldRanges)
    }

    @Test fun typingReplacesEveryRangeInOneBatchEdit() {
        val field = FakeField("ab ab xab ab", 1)
        val multi = cursor(field)
        repeat(3) { multi.next(field) }
        field.calls.clear()
        assertEquals(MultiCursorResult.Written, multi.type("cd"))
        assertEquals("cd cd xab cd", field.text)
        assertEquals(listOf("begin", "setSelection 0,12", "commit cd cd xab cd", "setSelection 12,12", "end"), field.calls)
    }

    @Test fun columnCaretsSitAtTheSameColumnOfEachLine() {
        assertEquals(listOf(1, 4, 7), MultiCursor.columnCarets("a1\nb2\nc3", 1, 3))
        val field = FakeField("a1\nb2\nc3", 1)
        val multi = cursor(field)
        assertEquals(MultiCursorResult.Lines(2), multi.addCaretOnNextLine(1, 1))
        assertEquals(MultiCursorResult.Lines(3), multi.addCaretOnNextLine(field.selStart, field.selEnd))
        assertEquals(MultiCursorResult.Written, multi.type("x"))
        assertEquals("ax1\nbx2\ncx3", field.text)
    }

    @Test fun caretsAddedBelowAfterTypingStayAtTheTypedColumn() {
        // typing moves every caret to the right: the next caret below sits at the column of the first caret now, not at its old one
        val field = FakeField("a1\nb2\nc3", 1)
        val multi = cursor(field)
        assertEquals(MultiCursorResult.Lines(2), multi.addCaretOnNextLine(1, 1))
        assertEquals(MultiCursorResult.Written, multi.type("x"))
        assertEquals("ax1\nbx2\nc3", field.text)
        assertEquals(MultiCursorResult.Lines(3), multi.addCaretOnNextLine(field.selStart, field.selEnd))
        assertEquals(listOf(TextRange(2, 2), TextRange(6, 6), TextRange(10, 10)), multi.heldRanges)
        assertEquals(MultiCursorResult.Written, multi.type("y"))
        assertEquals("axy1\nbxy2\nc3y", field.text)
    }

    @Test fun backspaceMovesTheColumnToo() {
        val field = FakeField("ab1\ncd2\nef3", 2)
        val multi = cursor(field)
        multi.addCaretOnNextLine(2, 2)
        assertEquals(MultiCursorResult.Written, multi.deleteBackward())
        assertEquals("a1\nc2\nef3", field.text)
        assertEquals(MultiCursorResult.Lines(3), multi.addCaretOnNextLine(field.selStart, field.selEnd))
        assertEquals(listOf(TextRange(1, 1), TextRange(4, 4), TextRange(7, 7)), multi.heldRanges)
    }

    @Test fun aLineBreakTypedIntoColumnCaretsEndsTheColumnAndTheNextCaretStartsFromTheCaretNow() {
        val field = FakeField("ab\ncd\nef", 1)
        val multi = cursor(field)
        multi.addCaretOnNextLine(1, 1)
        multi.addCaretOnNextLine(field.selStart, field.selEnd)
        assertEquals(MultiCursorResult.Written, multi.type("1\n2"))
        assertEquals("a1\n2b\nc1\n2d\ne1\n2f", field.text)
        assertTrue(multi.isActive, "the carets stay for typing")
        assertEquals(MultiCursorResult.Refused.NO_MORE_LINES, multi.addCaretOnNextLine(field.selStart, field.selEnd))
        assertFalse(multi.isActive, "the old carets are not kept next to a new column")
    }

    @Test fun backspaceRemovesAWholeGraphemeAtEveryCaret() {
        val graphemes = listOf(
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", // family: three people joined by zero-width joiners
            "\uD83D\uDC4D\uD83C\uDFFD", // thumbs up with a skin tone
            "\uD83C\uDDE7\uD83C\uDDEC", // flag: two regional indicators
            "1\uFE0F\u20E3", // keycap
            "e\u0301", // a letter with a combining accent
        )
        for (g in graphemes) {
            val field = FakeField("x$g\ny$g", 1 + g.length)
            val multi = cursor(field)
            assertEquals(MultiCursorResult.Lines(2), multi.addCaretOnNextLine(field.selStart, field.selEnd))
            assertEquals(MultiCursorResult.Written, multi.deleteBackward())
            assertEquals("x\ny", field.text, g.map { it.code.toString(16) }.toString())
        }
        // a plain letter is still one character
        val plain = FakeField("xa\nya", 2)
        val multi = cursor(plain)
        multi.addCaretOnNextLine(2, 2)
        multi.deleteBackward()
        assertEquals("x\ny", plain.text)
    }

    @Test fun undoIsGoneAsSoonAsAnythingElseChangesTheField() {
        val field = FakeField("ab ab", 1)
        val multi = cursor(field)
        repeat(2) { multi.next(field) }
        multi.type("cd")
        assertEquals("cd cd", field.text)
        field.text += "!" // another edit, outside the region of the cursors
        field.calls.clear()
        assertNull(multi.undoLastEdit())
        assertEquals("cd cd!", field.text)
        assertEquals(emptyList(), field.calls)

        // positive control: without the other edit the same undo goes through
        val clean = FakeField("ab ab", 1)
        val second = cursor(clean)
        repeat(2) { second.next(clean) }
        second.type("cd")
        assertEquals(MultiCursorResult.Undone, second.undoLastEdit())
        assertEquals("ab ab", clean.text)

        // an edit between two typed letters starts a new undo that does not reach back before it
        val letters = FakeField("ab ab", 1)
        val third = cursor(letters)
        repeat(2) { third.next(letters) }
        third.type("c")
        letters.text += "?"
        third.type("d")
        assertEquals("cd cd?", letters.text)
        assertEquals(MultiCursorResult.Undone, third.undoLastEdit())
        assertEquals("c c?", letters.text)
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun aSelectionMatchesSubstringsAndMatchingIsCaseSensitive() {
        val field = FakeField("ab ab xab ab", 0, 2)
        val multi = cursor(field)
        assertEquals(MultiCursorResult.Ranges(1, 4), multi.next(field))
        repeat(3) { multi.next(field) }
        assertEquals(listOf(TextRange(0, 2), TextRange(3, 5), TextRange(7, 9), TextRange(10, 12)), multi.heldRanges)
        assertEquals(MultiCursorResult.AllSelected(4), multi.next(field))

        val cased = FakeField("Ab ab AB ab", 4)
        val second = cursor(cased)
        assertEquals(MultiCursorResult.Ranges(1, 2), second.next(cased))
        assertEquals(MultiCursorResult.Ranges(2, 2), second.next(cased))
        assertEquals(listOf(TextRange(3, 5), TextRange(9, 11)), second.heldRanges)
        assertEquals(MultiCursorResult.AllSelected(2), second.next(cased))
    }

    @Test fun aCaretOutsideAnyWordMatchesNothing() {
        val field = FakeField("ab  ab", 3)
        assertEquals(MultiCursorResult.Refused.NOTHING_TO_MATCH, cursor(field).next(field))
        assertEquals(emptyList(), field.calls)
    }

    @Test fun aFieldWithoutItsWholeTextIsNeverWritten() {
        for ((extracted, refusal) in listOf<Pair<(String) -> FieldText?, MultiCursorResult>>(
            { _: String -> null } to MultiCursorResult.Refused.NO_TEXT,
            { t: String -> FieldText(t, 5) } to MultiCursorResult.Refused.PARTIAL_TEXT,
        )) {
            val field = FakeField("ab ab", 1).also { it.extracted = extracted }
            assertEquals(refusal, cursor(field).next(field))
            assertEquals(emptyList(), field.calls)
        }
        val long = FakeField("ab " + "x".repeat(MultiCursor.MAX_CHARS - 3), 1)
        assertEquals(MultiCursor.MAX_CHARS, long.text.length)
        assertEquals(MultiCursorResult.Ranges(1, 1), cursor(long).next(long)) // positive control: 20 000 characters are fine
        val tooLong = FakeField("ab " + "x".repeat(MultiCursor.MAX_CHARS - 2), 1)
        assertEquals(MultiCursorResult.Refused.TOO_LONG, cursor(tooLong).next(tooLong))
        assertEquals(emptyList(), tooLong.calls)
    }

    @Test fun aRegionThatChangedSinceTheRangesWereMadeIsNotWritten() {
        val field = FakeField("ab ab ab", 1)
        val multi = cursor(field)
        repeat(2) { multi.next(field) }
        field.text = "ab Xb ab" // another app or the user changed the text in between
        field.calls.clear()
        assertEquals(MultiCursorResult.Refused.CHANGED, multi.type("cd"))
        assertEquals("ab Xb ab", field.text)
        assertEquals(emptyList(), field.calls)
        assertFalse(multi.isActive)
    }

    @Test fun theHundredAndFirstRangeIsRefused() {
        val field = FakeField((1..101).joinToString(" ") { "a" }, 0)
        val multi = cursor(field)
        repeat(100) { assertEquals(MultiCursorResult.Ranges(it + 1, 101), multi.next(field)) }
        assertEquals(MultiCursorResult.Refused.TOO_MANY, multi.next(field))
        assertEquals(100, multi.heldRanges.size)
    }

    @Test fun nothingIsReadWhenTheFieldDoesNotAllowIt() {
        val field = FakeField("ab ab", 1)
        var allowed = false
        val multi = cursor(field) { allowed }
        assertEquals(MultiCursorResult.Refused.NOT_ALLOWED, multi.next(field))
        assertEquals(MultiCursorResult.Refused.NOT_ALLOWED, multi.addCaretOnNextLine(1, 1))
        assertEquals(0, field.extractedCalls)
        allowed = true // positive control: an allowed field is read once
        assertEquals(MultiCursorResult.Ranges(1, 2), multi.next(field))
        assertEquals(1, field.extractedCalls)
    }

    @Test fun backspaceDeletesTheRangesThenTheCharacterBeforeEachCaret() {
        val field = FakeField("ab ab xab ab", 1)
        val multi = cursor(field)
        repeat(3) { multi.next(field) }
        assertEquals(MultiCursorResult.Written, multi.type("xyz"))
        assertEquals("xyz xyz xab xyz", field.text)
        assertEquals(MultiCursorResult.Written, multi.deleteBackward())
        assertEquals("xy xy xab xy", field.text)
        assertEquals(TextRange(12, 12), TextRange(field.selStart, field.selEnd))
    }

    @Test fun undoRestoresTheRegionOnlyWhileTheFieldHoldsWhatWasWritten() {
        val clock = FakeClock()
        val field = FakeField("ab ab", 1)
        val multi = cursor(field, clock)
        repeat(2) { multi.next(field) }
        multi.type("cd")
        assertEquals("cd cd", field.text)
        clock.now += MultiCursor.UNDO_MILLIS // exactly two minutes: still allowed
        assertEquals(MultiCursorResult.Undone, multi.undoLastEdit())
        assertEquals("ab ab", field.text)
        assertNull(multi.undoLastEdit()) // one undo only

        val changed = FakeField("ab ab", 1)
        val second = cursor(changed, clock)
        repeat(2) { second.next(changed) }
        second.type("cd")
        changed.text = "cd Xd"
        changed.calls.clear()
        assertNull(second.undoLastEdit())
        assertEquals("cd Xd", changed.text)
        assertEquals(emptyList(), changed.calls)

        val late = FakeField("ab ab", 1)
        val third = cursor(late, clock)
        repeat(2) { third.next(late) }
        third.type("cd")
        clock.now += MultiCursor.UNDO_MILLIS + 1
        assertNull(third.undoLastEdit())
        assertEquals("cd cd", late.text)

        val locked = FakeField("ab ab", 1)
        val fourth = cursor(locked, clock)
        repeat(2) { fourth.next(locked) }
        fourth.type("cd")
        assertTrue(fourth.hasUndo())
        fourth.forgetAll() // screen lock or field change
        assertFalse(fourth.hasUndo())
        assertNull(fourth.undoLastEdit())
        assertEquals("cd cd", locked.text)
    }

    @Test fun lettersTypedOneByOneAreUndoneTogetherAndANewSetOfCursorsStartsANewUndo() {
        val field = FakeField("ab ab xab ab", 1)
        val multi = cursor(field)
        repeat(3) { multi.next(field) }
        multi.type("c")
        multi.type("d")
        multi.deleteBackward()
        multi.type("e")
        assertEquals("ce ce xab ce", field.text)
        assertEquals(MultiCursorResult.Undone, multi.undoLastEdit())
        assertEquals("ab ab xab ab", field.text)

        val second = FakeField("ab ab", 1)
        val other = cursor(second)
        repeat(2) { other.next(second) }
        other.type("x")
        other.clear()
        second.selStart = 1; second.selEnd = 1
        repeat(2) { other.next(second) } // a new set of cursors on "x"
        other.type("y")
        assertEquals("y y", second.text)
        assertEquals(MultiCursorResult.Undone, other.undoLastEdit())
        assertEquals("x x", second.text) // only the edit of the new set is undone
    }

    @Test fun aSelectionTheCursorsDidNotSetDropsThem() {
        val field = FakeField("ab ab", 1)
        val multi = cursor(field)
        repeat(2) { multi.next(field) }
        multi.onSelectionChanged(3, 5) // the visible range: set by the cursors themselves
        assertTrue(multi.isActive)
        multi.onSelectionChanged(4, 4) // the user tapped into the text
        assertFalse(multi.isActive)
    }

    @Test fun aLateReportOfTheSelectionBeforeTheFirstCursorDoesNotDropTheCursors() {
        // seen on the reference phone: the editor reports the old caret after the first range was selected
        val field = FakeField("ab ab xab ab", 12)
        val multi = cursor(field)
        multi.next(field)
        multi.onSelectionChanged(12, 12)
        multi.next(field)
        assertTrue(multi.isActive)
        assertEquals(listOf(TextRange(0, 2), TextRange(10, 12)), multi.heldRanges)
        val column = FakeField("a1\nb2", 1)
        val lines = cursor(column)
        lines.addCaretOnNextLine(1, 1)
        lines.onSelectionChanged(1, 1)
        assertTrue(lines.isActive)
        lines.onSelectionChanged(2, 2) // positive control: a caret the cursors did not set drops them
        assertFalse(lines.isActive)
    }

    @Test fun aLineShorterThanTheColumnIsSkippedAndTheLastLineEndsTheColumn() {
        assertEquals(listOf(2, 8), MultiCursor.columnCarets("ab1\nc\nde2", 2, 3))
        val field = FakeField("ab1\nc\nde2", 2)
        val multi = cursor(field)
        assertEquals(MultiCursorResult.Lines(1), multi.addCaretOnNextLine(2, 2)) // the short line gets no caret
        assertEquals(MultiCursorResult.Lines(2), multi.addCaretOnNextLine(field.selStart, field.selEnd))
        assertEquals(MultiCursorResult.Refused.NO_MORE_LINES, multi.addCaretOnNextLine(field.selStart, field.selEnd))
        assertEquals(MultiCursorResult.Written, multi.type("-"))
        assertEquals("ab-1\nc\nde-2", field.text)
    }
}
