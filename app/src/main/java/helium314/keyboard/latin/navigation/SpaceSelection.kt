// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import kotlin.math.abs

/** What [SpaceSelection] needs from the text field. */
interface CursorPort {
    val selectionStart: Int
    val selectionEnd: Int
    /** Up to [n] characters before the selection start. */
    fun textBefore(n: Int): CharSequence?
    fun selectedText(): CharSequence?
    /** Up to [n] characters after the selection end. */
    fun textAfter(n: Int): CharSequence?
    /** Selects from [anchor] to [focus]; the focus may lie before the anchor. */
    fun setSelection(anchor: Int, focus: Int)
    /** One arrow key, extending the selection when [select] is set. The editor decides what a line is. */
    fun arrowKey(arrow: Arrow, select: Boolean)
}

enum class Arrow { LEFT, RIGHT, UP, DOWN }

/**
 * Cursor and selection moves of the space bar. Horizontal steps are grapheme clusters (an emoji sequence, a flag or a letter with its accents is one step), so the focus never lands inside one; vertical steps
 * are lines, sent as one arrow key each and only in multi-line fields: in a single-line field an up or down key can move the
 * focus to another view. While [selecting], horizontal steps move the focus of a selection whose anchor stays where the
 * selection started; after a vertical step the editor owns the selection, so later horizontal steps are arrow keys too.
 */
class SpaceSelection(private val port: CursorPort) {
    /** Selection mode switched on by the toolbar key; it stays on until it is switched off or the field changes. */
    var sticky = false
        set(value) {
            field = value
            if (!value) reset()
        }

    private var anchor = -1
    private var focus = -1
    private var editorOwnsSelection = false

    fun isSelecting(shiftHeld: Boolean) = shiftHeld || sticky

    /** Moves the cursor [steps] lines; returns true when the swipe is consumed. */
    fun moveLines(steps: Int, multiLine: Boolean, select: Boolean): Boolean {
        if (steps == 0) return false
        if (!multiLine) return true
        repeat(abs(steps)) { port.arrowKey(if (steps < 0) Arrow.UP else Arrow.DOWN, select) }
        if (select) editorOwnsSelection = true
        return true
    }

    /** Moves the focus of the selection [steps] characters; returns false when nothing could be read from the field. */
    fun select(steps: Int): Boolean {
        if (steps == 0) return false
        if (editorOwnsSelection) {
            repeat(abs(steps)) { port.arrowKey(if (steps < 0) Arrow.LEFT else Arrow.RIGHT, true) }
            return true
        }
        if (anchor < 0) {
            if (!snapSelectionToClusters()) return false
            anchor = port.selectionStart
            focus = port.selectionEnd
        }
        val end = port.selectionEnd
        val newFocus = if (steps > 0) {
            val window = steps * Graphemes.STEP_WINDOW
            val fromField = focus >= end
            val ahead = (if (fromField) port.textAfter(window) else textBetween(focus, end)) ?: return false
            val cut = fromField && Graphemes.mayBeCut(ahead, window) { port.textAfter(it) }
            focus + Graphemes.forward(ahead, steps, cut)
        } else {
            val window = -steps * Graphemes.STEP_WINDOW
            val (behind, maybeCut) = textBeforeFocus(window) ?: return false
            focus - Graphemes.backward(behind, -steps, maybeCut)
        }
        if (newFocus == focus) return true
        focus = newFocus
        port.setSelection(anchor, focus)
        return true
    }

    /**
     * The editor may report a caret or selection end inside a cluster (between the halves of an emoji): the start moves to the start
     * of its cluster and the end to the end of its cluster before a selection grows from it. False when the text cannot be read.
     */
    private fun snapSelectionToClusters(): Boolean {
        val start = port.selectionStart
        val end = port.selectionEnd
        val window = Graphemes.STEP_WINDOW
        val before = port.textBefore(window) ?: return false
        val after = port.textAfter(window) ?: return false
        val selected = port.selectedText()
        val head = before.toString() + (selected ?: after).take(window)
        val newStart = start - (before.length - Graphemes.snapDown(head, before.length))
        val tail = (selected ?: before).takeLast(window).toString()
        val newEnd = end + (Graphemes.snapUp(tail + after, tail.length) - tail.length)
        if (newStart != start || newEnd != end) port.setSelection(newStart, newEnd)
        return true
    }

    /** Forgets the anchor; the selection stays in the field. */
    fun reset() {
        anchor = -1
        focus = -1
        editorOwnsSelection = false
    }

    private fun textBetween(from: Int, to: Int): CharSequence? {
        val selected = port.selectedText() ?: return null
        val start = port.selectionStart
        return selected.subSequence((from - start).coerceIn(0, selected.length), (to - start).coerceIn(0, selected.length))
    }

    /** The text before the focus (at most [n] chars before the selection start, plus the selection part) and whether the field has more before it. */
    private fun textBeforeFocus(n: Int): Pair<CharSequence, Boolean>? {
        val start = port.selectionStart
        val back = maxOf(0, start - focus)
        val before = port.textBefore(n + back) ?: return null
        val maybeCut = Graphemes.mayBeCut(before, n + back) { port.textBefore(it) }
        if (focus <= start) return before.subSequence(0, (before.length - back).coerceAtLeast(0)) to maybeCut
        val selected = port.selectedText() ?: ""
        val inSelection = selected.subSequence(0, (focus - start).coerceIn(0, selected.length))
        return (before.toString() + inSelection) to maybeCut
    }
}
