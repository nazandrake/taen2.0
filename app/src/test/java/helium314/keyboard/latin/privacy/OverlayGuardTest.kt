// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.view.MotionEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** P14: touches that another window obscures (partly or fully) are warned about or dropped, as the setting says. */
class OverlayGuardTest {
    private val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED
    private val partly = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
    private val pass = OverlayGuard.Verdict.PASS
    private val warn = OverlayGuard.Verdict.WARN
    private val drop = OverlayGuard.Verdict.DROP
    private val cancel = OverlayGuard.Verdict.CANCEL

    // ---- the core behaviour -----------------------------------------------------------------------------------

    @Test fun anObscuredTouchIsDroppedInBlockModeAndPassedWhenNothingCoversTheKeyboard() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(OverlayGuard.Verdict.DROP, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.BLOCK))
        assertEquals(OverlayGuard.Verdict.DROP, gate.filter(MotionEvent.ACTION_UP, obscured, OverlayMode.BLOCK))
        assertEquals(OverlayGuard.Verdict.PASS, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK)) // positive control
    }

    @Test fun anObscuredTouchIsPassedWithAWarningInWarnMode() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(OverlayGuard.Verdict.WARN, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.WARN))
    }

    // ---- the whole contract -----------------------------------------------------------------------------------

    @Test fun bothObscuredFlagsCountAndOtherFlagsDoNot() {
        for ((name, flags) in mapOf("obscured" to obscured, "partly" to partly, "both" to (obscured or partly), "with others" to (partly or 0x40))) {
            assertEquals(drop, OverlayGuard.Gate { 0L }.filter(MotionEvent.ACTION_DOWN, flags, OverlayMode.BLOCK), name)
            assertEquals(warn, OverlayGuard.Gate { 0L }.filter(MotionEvent.ACTION_DOWN, flags, OverlayMode.WARN), name)
        }
        for (flags in listOf(0, 0x4, 0x40, 0x7ffffffc)) { // anything except the two bits
            assertEquals(pass, OverlayGuard.Gate { 0L }.filter(MotionEvent.ACTION_DOWN, flags, OverlayMode.BLOCK), "$flags")
            assertEquals(pass, OverlayGuard.Gate { 0L }.filter(MotionEvent.ACTION_DOWN, flags, OverlayMode.WARN), "$flags")
        }
    }

    @Test fun offModePassesEverything() {
        val gate = OverlayGuard.Gate { 0L }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP))
            assertEquals(pass, gate.filter(action, obscured or partly, OverlayMode.OFF), "$action")
    }

    @Test fun aGestureThatStartsCoveredIsDroppedToItsEndEvenIfTheCoverGoesAway() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(drop, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_MOVE, 0, OverlayMode.BLOCK)) // cover gone, but the key was never pressed
        assertEquals(drop, gate.filter(MotionEvent.ACTION_POINTER_DOWN, 0, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_UP, 0, OverlayMode.BLOCK))
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK)) // the next gesture is fine
        assertEquals(pass, gate.filter(MotionEvent.ACTION_UP, 0, OverlayMode.BLOCK))
    }

    // (block mode after a window transition): a covered gesture whose end never arrives must not drop the next, uncovered gesture
    @Test fun aNewGestureAfterACoveredOneThatNeverEndedIsNotDropped() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(drop, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_MOVE, obscured, OverlayMode.BLOCK)) // the window changes: no UP or CANCEL ever comes
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK)) // a fresh, uncovered gesture
        assertEquals(pass, gate.filter(MotionEvent.ACTION_MOVE, 0, OverlayMode.BLOCK))
        assertEquals(pass, gate.filter(MotionEvent.ACTION_UP, 0, OverlayMode.BLOCK))
        // a covered fresh gesture is still dropped (the reset only applies to an uncovered start)
        assertEquals(drop, gate.filter(MotionEvent.ACTION_DOWN, partly, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_MOVE, 0, OverlayMode.BLOCK)) // and the rest of that gesture is dropped even when the cover goes
    }

    @Test fun aCoverThatAppearsMidGestureCancelsTheGestureOnceAndDropsTheRest() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK))
        assertEquals(pass, gate.filter(MotionEvent.ACTION_MOVE, 0, OverlayMode.BLOCK))
        assertEquals(cancel, gate.filter(MotionEvent.ACTION_MOVE, partly, OverlayMode.BLOCK)) // the key tracker gets a cancel
        assertEquals(drop, gate.filter(MotionEvent.ACTION_MOVE, partly, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_UP, partly, OverlayMode.BLOCK))
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK))
    }

    @Test fun aGestureThatEndsCoveredIsCancelledSoNoKeyStaysPressed() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK))
        assertEquals(cancel, gate.filter(MotionEvent.ACTION_UP, obscured, OverlayMode.BLOCK)) // the UP never reaches the tracker
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK))
    }

    @Test fun aCancelEndsAGestureCleanly() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(drop, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_CANCEL, 0, OverlayMode.BLOCK))
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.BLOCK))
        // a finished gesture does not need a cancel when the cover shows up between gestures
        assertEquals(pass, gate.filter(MotionEvent.ACTION_UP, 0, OverlayMode.BLOCK))
        assertEquals(drop, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.BLOCK))
    }

    @Test fun anIdleMoveWithoutADownIsNotMistakenForAGesture() { // hover or a stray event must not produce a cancel
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(drop, gate.filter(MotionEvent.ACTION_MOVE, obscured, OverlayMode.BLOCK))
    }

    @Test fun warningsAreRateLimitedAndTheTouchAlwaysPasses() {
        var now = 100_000L
        val gate = OverlayGuard.Gate { now }
        assertEquals(warn, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.WARN))
        now += OverlayGuard.WARN_INTERVAL_MILLIS - 1
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.WARN)) // just inside the interval
        now += 1
        assertEquals(warn, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.WARN)) // the interval is over
        // a touch that nothing covers neither warns nor resets the interval
        now += 5
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.WARN))
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, obscured, OverlayMode.WARN))
    }

    @Test fun warnModeNeverDropsOrCancels() {
        val gate = OverlayGuard.Gate { 0L }
        assertEquals(pass, gate.filter(MotionEvent.ACTION_DOWN, 0, OverlayMode.WARN))
        val verdict = gate.filter(MotionEvent.ACTION_MOVE, obscured, OverlayMode.WARN)
        assertEquals(warn, verdict)
        assertEquals(pass, gate.filter(MotionEvent.ACTION_UP, obscured, OverlayMode.WARN))
    }

    @Test fun theModeIsReadFromTheSettingValueAndAnUnknownValueBlocks() { // fail closed
        assertEquals(OverlayMode.OFF, OverlayMode.parse("off"))
        assertEquals(OverlayMode.WARN, OverlayMode.parse("warn"))
        assertEquals(OverlayMode.BLOCK, OverlayMode.parse("block"))
        assertEquals(OverlayMode.WARN, OverlayMode.parse(null)) // not set: the default
        assertEquals(OverlayMode.BLOCK, OverlayMode.parse("nonsense"))
    }
}
