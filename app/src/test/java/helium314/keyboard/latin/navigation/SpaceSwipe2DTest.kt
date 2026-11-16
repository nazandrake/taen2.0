// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.navigation

import helium314.keyboard.keyboard.KeyboardActionListener.SwipeAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceSwipe2DTest {
    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun oneStepRightThenOneRowDownGivesOneOfEach() {
        val swipe = SpaceSwipe2D(stepWidth = 10, rowHeight = 50)
        assertEquals(SpaceSwipe2D.Steps(1, 0), swipe.onMove(10, 0))
        assertEquals(SpaceSwipe2D.Steps(0, 1), swipe.onMove(10, 50))
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun aRowHeightLessOnePixelIsNoLineAndARowHeightIsOneInBothDirections() {
        for ((dy, expected) in listOf(49 to 0, 50 to 1, -49 to 0, -50 to -1, 99 to 1, 100 to 2, -100 to -2)) {
            assertEquals(expected, SpaceSwipe2D(10, 50).onMove(0, dy).y, "dy=$dy")
        }
        for ((dx, expected) in listOf(9 to 0, 10 to 1, -9 to 0, -10 to -1, 35 to 3)) {
            assertEquals(expected, SpaceSwipe2D(10, 50).onMove(dx, 0).x, "dx=$dx")
        }
    }

    @Test fun stepsAreTakenOnceAndASlantedSwipeStaysOnItsLine() {
        val swipe = SpaceSwipe2D(10, 50)
        assertEquals(SpaceSwipe2D.Steps(2, 0), swipe.onMove(25, 20)) // 20 px off the line: no line change
        assertEquals(SpaceSwipe2D.Steps(0, 0), swipe.onMove(29, 49))
        assertEquals(SpaceSwipe2D.Steps(1, 1), swipe.onMove(30, 50))
        assertEquals(SpaceSwipe2D.Steps(-3, -1), swipe.onMove(0, 0)) // back to the start: everything undone
    }

    @Test fun bothDirectionsMustMoveTheCursor() {
        assertTrue(SpaceSwipe2D.isActive(SwipeAction.MOVE_CURSOR, SwipeAction.MOVE_CURSOR))
        assertFalse(SpaceSwipe2D.isActive(SwipeAction.MOVE_CURSOR, SwipeAction.NONE))
        assertFalse(SpaceSwipe2D.isActive(SwipeAction.SWITCH_LANGUAGE, SwipeAction.MOVE_CURSOR))
    }

    @Test fun stepSizesMustBePositive() {
        assertFailsWith<IllegalArgumentException> { SpaceSwipe2D(0, 50) }
        assertFailsWith<IllegalArgumentException> { SpaceSwipe2D(10, 0) }
    }
}
