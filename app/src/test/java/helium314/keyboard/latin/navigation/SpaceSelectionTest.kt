// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceSelectionTest {
    private val text = "0123456789abcdefghij"

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun aLineStepInAMultiLineFieldIsOneArrowKey() {
        val field = FakeField(text, 10)
        assertTrue(SpaceSelection(field).moveLines(1, multiLine = true, select = false))
        assertEquals(listOf("key DOWN"), field.calls)
    }

    @Test fun selectingMovesTheFocusAndKeepsTheAnchor() {
        val field = FakeField(text, 10)
        val selection = SpaceSelection(field)
        assertTrue(selection.select(3))
        assertTrue(selection.select(-5))
        assertEquals(listOf("setSelection 10,13", "setSelection 10,8"), field.calls)
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun aSingleLineFieldNeverGetsAnUpOrDownKey() {
        val field = FakeField(text, 10)
        val selection = SpaceSelection(field)
        assertTrue(selection.moveLines(1, multiLine = false, select = false)) // the swipe is consumed
        assertTrue(selection.moveLines(-2, multiLine = false, select = true))
        assertEquals(emptyList(), field.calls)
        assertTrue(selection.moveLines(-2, multiLine = true, select = false)) // positive control: a multi-line field gets them
        assertEquals(listOf("key UP", "key UP"), field.calls)
        assertFalse(selection.moveLines(0, multiLine = true, select = false))
    }

    @Test fun aVerticalStepWhileSelectingIsShiftAndArrowAndTheEditorKeepsTheSelection() {
        val field = FakeField(text, 10)
        val selection = SpaceSelection(field)
        assertTrue(selection.select(2))
        assertTrue(selection.moveLines(1, multiLine = true, select = true))
        assertTrue(selection.select(-2)) // the editor owns the selection now: arrows with shift, not setSelection
        assertEquals(listOf("setSelection 10,12", "key DOWN shift", "key LEFT shift", "key LEFT shift"), field.calls)
        selection.reset()
        field.calls.clear()
        assertTrue(selection.select(1)) // after a reset the anchor is read again from the field
        assertEquals(listOf("setSelection 10,13"), field.calls)
    }

    @Test fun aSurrogatePairIsOneStepAndTheEndsOfTheTextStopTheFocus() {
        val emoji = "😀"
        val field = FakeField("a${emoji}b", 1)
        val selection = SpaceSelection(field)
        assertTrue(selection.select(1))
        assertEquals("setSelection 1,3", field.calls.last())
        assertTrue(selection.select(5)) // only "b" is left
        assertEquals("setSelection 1,4", field.calls.last())
        assertTrue(selection.select(-9)) // back over the emoji and the "a" to the start
        assertEquals("setSelection 1,0", field.calls.last())
        val before = field.calls.size
        assertTrue(selection.select(-1)) // at the start: nothing to do
        assertEquals(before, field.calls.size)
    }

    @Test fun releasingShiftKeepsTheSelectionAndTheStickyModeEndsOnlyWhenSwitchedOff() {
        val field = FakeField(text, 10)
        val selection = SpaceSelection(field)
        assertFalse(selection.isSelecting(shiftHeld = false))
        assertTrue(selection.isSelecting(shiftHeld = true))
        selection.select(3)
        selection.reset() // shift released at the end of the swipe
        assertEquals(TextRange(10, 13), TextRange(field.selectionStart, field.selectionEnd))
        selection.sticky = true
        assertTrue(selection.isSelecting(shiftHeld = false))
        selection.sticky = false
        assertFalse(selection.isSelecting(shiftHeld = false))
    }
}
