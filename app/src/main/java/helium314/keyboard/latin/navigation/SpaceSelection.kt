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
 * Cursor and selection moves of the space bar. Horizontal steps are characters (a surrogate pair is one step); vertical steps
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
            anchor = port.selectionStart
            focus = port.selectionEnd
        }
        val end = port.selectionEnd
        val newFocus = if (steps > 0) {
            val ahead = (if (focus >= end) port.textAfter(steps * 4) else textBetween(focus, end)) ?: return false
            focus + charCount(ahead, steps)
        } else {
            val behind = textBeforeFocus(-steps * 4) ?: return false
            focus - charCountBackwards(behind, -steps)
        }
        if (newFocus == focus) return true
        focus = newFocus
        port.setSelection(anchor, focus)
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

    private fun textBeforeFocus(n: Int): CharSequence? {
        val start = port.selectionStart
        val before = port.textBefore(n) ?: return null
        if (focus <= start) return before.subSequence(0, (before.length - (start - focus)).coerceAtLeast(0))
        val selected = port.selectedText() ?: ""
        val inSelection = selected.subSequence(0, (focus - start).coerceIn(0, selected.length))
        return before.toString() + inSelection
    }

    companion object {
        /** Characters of [steps] code points from the start of [text]. */
        fun charCount(text: CharSequence, steps: Int): Int {
            var i = 0
            var n = 0
            while (n < steps && i < text.length) {
                i += Character.charCount(Character.codePointAt(text, i))
                n++
            }
            return i
        }

        /** Characters of [steps] code points from the end of [text]. */
        fun charCountBackwards(text: CharSequence, steps: Int): Int {
            var i = text.length
            var n = 0
            while (n < steps && i > 0) {
                i -= Character.charCount(Character.codePointBefore(text, i))
                n++
            }
            return text.length - i
        }
    }
}
