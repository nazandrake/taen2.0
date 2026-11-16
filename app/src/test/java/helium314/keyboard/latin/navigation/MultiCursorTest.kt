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
