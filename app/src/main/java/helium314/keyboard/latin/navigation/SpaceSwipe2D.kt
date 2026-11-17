// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import helium314.keyboard.keyboard.KeyboardActionListener.SwipeAction

/**
 * One space-bar swipe split into horizontal character steps and vertical line steps, both measured from where the finger went
 * down: a horizontal step is [stepWidth] pixels, a vertical step is one key row ([rowHeight] pixels), so a slightly slanted
 * horizontal swipe does not change the line.
 */
class SpaceSwipe2D(private val stepWidth: Int, private val rowHeight: Int) {
    init {
        require(stepWidth > 0 && rowHeight > 0) { "step sizes must be positive" }
    }

    private var usedX = 0
    private var usedY = 0

    /** The steps not taken yet for the finger at ([dx], [dy]) pixels from the start of the swipe. */
    fun onMove(dx: Int, dy: Int): Steps {
        val x = (dx - usedX) / stepWidth
        val y = (dy - usedY) / rowHeight
        usedX += x * stepWidth
        usedY += y * rowHeight
        return Steps(x, y)
    }

    data class Steps(val x: Int, val y: Int)

    companion object {
        /** The space bar moves the cursor in both directions when both swipe directions are set to move the cursor. */
        @JvmStatic
        fun isActive(horizontal: SwipeAction, vertical: SwipeAction) =
            horizontal == SwipeAction.MOVE_CURSOR && vertical == SwipeAction.MOVE_CURSOR
    }
}
