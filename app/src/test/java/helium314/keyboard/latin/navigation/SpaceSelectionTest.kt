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

    private val clusters = listOf(
        "emoji" to "\uD83D\uDE00",
        "family" to "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
        "skin tone" to "\uD83D\uDC4D\uD83C\uDFFD",
        "flag" to "\uD83C\uDDE7\uD83C\uDDEC",
        "keycap" to "1\uFE0F\u20E3",
        "accent" to "e\u0301",
        "hebrew points" to "\u05E9\u05C1\u05B8",
        "arabic mark" to "\u0645\u064E",
        "bulgarian" to "\u044A", // one char: the control
    )

    @Test fun aStepMovesOverAWholeClusterInBothDirections() {
        for ((name, g) in clusters) {
            val forward = FakeField("a${g}b", 1)
            val selection = SpaceSelection(forward)
            assertTrue(selection.select(1), name)
            assertEquals("setSelection 1,${1 + g.length}", forward.calls.last(), name)
            assertTrue(selection.select(1), name)
            assertEquals("setSelection 1,${2 + g.length}", forward.calls.last(), name)
            assertTrue(selection.select(-2), name) // back over "b" and the cluster
            assertEquals("setSelection 1,1", forward.calls.last(), name)

            val back = FakeField("a${g}b", 1 + g.length + 1)
            val second = SpaceSelection(back)
            assertTrue(second.select(-1), name)
            assertEquals("setSelection ${2 + g.length},${1 + g.length}", back.calls.last(), name)
            assertTrue(second.select(-1), name)
            assertEquals("setSelection ${2 + g.length},1", back.calls.last(), name)
        }
    }

    @Test fun severalStepsAcrossMixedClustersEndBetweenClusters() {
        // x, flag (4), e + accent (2), emoji (2), y
        val text = "x\uD83C\uDDE7\uD83C\uDDEC" + "e\u0301" + "\uD83D\uDE00" + "y"
        val forward = FakeField(text, 0)
        val selection = SpaceSelection(forward)
        assertTrue(selection.select(3))
        assertEquals("setSelection 0,7", forward.calls.last()) // x + flag + e-accent
        assertTrue(selection.select(-1))
        assertEquals("setSelection 0,5", forward.calls.last())

        val back = FakeField(text, text.length)
        val second = SpaceSelection(back)
        assertTrue(second.select(-2))
        assertEquals("setSelection 10,7", back.calls.last()) // y and the emoji
        assertTrue(second.select(2)) // forward again over the emoji and y, from inside the selection
        assertEquals("setSelection 10,10", back.calls.last())
    }

    @Test fun aClusterLongerThanTheReadWindowIsNeverEnteredHalfWay() {
        val long = "e" + "\u0301".repeat(80) // one cluster, 81 chars
        val field = FakeField("a${long}b", 1)
        val selection = SpaceSelection(field)
        assertTrue(selection.select(1))
        assertEquals(emptyList(), field.calls) // a part of it is all that could be read: no move rather than one inside it
        val short = FakeField("a" + "e" + "\u0301".repeat(10) + "b", 1) // positive control: a long cluster that fits is one step
        assertTrue(SpaceSelection(short).select(1))
        assertEquals("setSelection 1,12", short.calls.last())
    }

    private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"

    @Test fun aCaretBetweenTheHalvesOfAnEmojiStartsTheSelectionAtTheEmojiEnd() {
        val field = FakeField("a\uD83D\uDE00b", 2) // the editor reports the caret inside the emoji
        val selection = SpaceSelection(field)
        assertTrue(selection.select(1))
        assertEquals(listOf("setSelection 1,3", "setSelection 1,4"), field.calls) // the whole emoji first, then "b"
        assertEquals(TextRange(1, 4), TextRange(field.selStart, field.selEnd))
    }

    @Test fun aCaretInsideAnyClusterIsSnappedBeforeTheSelectionGrowsInEitherDirection() {
        for ((name, g) in clusters.filter { it.second.length > 1 }) {
            for (inside in 1 until g.length) {
                val forward = FakeField("a${g}b", 1 + inside)
                assertTrue(SpaceSelection(forward).select(1), name)
                assertEquals(listOf("setSelection 1,${1 + g.length}", "setSelection 1,${2 + g.length}"), forward.calls, "$name at $inside")
                val back = FakeField("a${g}b", 1 + inside)
                assertTrue(SpaceSelection(back).select(-1), name)
                assertEquals(listOf("setSelection 1,${1 + g.length}", "setSelection 1,1"), back.calls, "$name at $inside")
            }
            // positive control: a caret on the cluster boundary is not touched
            val onBoundary = FakeField("a${g}b", 1)
            assertTrue(SpaceSelection(onBoundary).select(1), name)
            assertEquals(listOf("setSelection 1,${1 + g.length}"), onBoundary.calls, name)
        }
    }

    @Test fun aSelectionWithBothEndsInsideEmojiIsWidenedToWholeEmoji() {
        val field = FakeField("x\uD83D\uDE00\uD83D\uDE00y", 2, 4)
        assertTrue(SpaceSelection(field).select(1))
        assertEquals(listOf("setSelection 1,5", "setSelection 1,6"), field.calls)
    }

    /** An editor that cuts a large read short and answers small ones in full. */
    private class ShortReads(val field: FakeField, val cap: Int, val fullUpTo: Int) : CursorPort by field {
        override fun textAfter(n: Int): CharSequence = field.textAfter(if (n > fullUpTo) cap else n)
        override fun textBefore(n: Int): CharSequence = field.textBefore(if (n > fullUpTo) cap else n)
    }

    @Test fun aShortReadThatIsNotTheEndOfTheFieldNeverEndsAStepInsideACluster() {
        // the family is 8 chars; the editor answers the big read with 5 chars, the cut-off family start
        val ahead = FakeField("a${family}bc", 1)
        assertTrue(SpaceSelection(ShortReads(ahead, 5, 9)).select(1))
        assertEquals(emptyList(), ahead.calls) // no move rather than one inside the family
        val behind = FakeField("a${family}b", 9)
        assertTrue(SpaceSelection(ShortReads(behind, 5, 9)).select(-1))
        assertEquals(emptyList(), behind.calls)
        // positive controls: a plain character next to the cut is one step, and a read that reaches the field's end is whole
        val plain = FakeField("a${family}b", 10)
        assertTrue(SpaceSelection(ShortReads(plain, 5, 9)).select(-1))
        assertEquals(listOf("setSelection 10,9"), plain.calls)
        val atEnd = FakeField("a$family", 1)
        assertTrue(SpaceSelection(atEnd).select(1)) // a normal editor: 8 chars left, fewer than asked for, and no more after them
        assertEquals(listOf("setSelection 1,9"), atEnd.calls)
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
