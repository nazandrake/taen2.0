// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cancel, progress and stall watch of a voice model install (audit follow-up of 1.0.1: the install had no timeout, cancel or progress). */
class InstallControlTest {
    private var now = 1_000L
    private val control = InstallControl { now }

    private class Probe : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun cancelSetsTheFlagAndTheHooksSeeIt() {
        assertFalse(control.cancelled)
        assertFalse(control.hooks.cancelled())
        control.cancel()
        assertTrue(control.cancelled)
        assertTrue(control.hooks.cancelled())
        assertFalse(control.stalled) // a cancel by the user is not a stall
    }

    @Test fun cancelClosesTheStreamThatIsBeingReadOnce() {
        val probe = Probe()
        control.reading(probe)
        assertEquals(0, probe.closes)
        control.cancel()
        control.cancel()
        assertEquals(1, probe.closes)
        // a stream registered after the cancel is closed at once (the read that would block never starts)
        val late = Probe()
        control.reading(late)
        assertEquals(1, late.closes)
    }

    @Test fun aStreamThatWasReleasedIsNotClosedByTheCancel() {
        val probe = Probe()
        control.reading(probe)
        control.reading(null)
        control.cancel()
        assertEquals(0, probe.closes)
    }

    @Test fun aStreamThatFailsToCloseDoesNotStopTheCancel() {
        control.reading(object : Closeable { override fun close() = throw java.io.IOException("already gone") })
        control.cancel()
        assertTrue(control.cancelled)
    }

    @Test fun theStallWatchCountsFromTheLastByteAndAStallIsACancel() {
        control.step("x", 100)
        now += 59_999
        assertEquals(59_999L, control.stalledFor())
        assertFalse(control.stalledFor() > InstallControl.STALL_MILLIS)
        control.hooks.onBytes(10) // a byte arrives: the watch starts again
        assertEquals(0L, control.stalledFor())
        now += InstallControl.STALL_MILLIS + 1
        assertTrue(control.stalledFor() > InstallControl.STALL_MILLIS)
        val probe = Probe()
        control.reading(probe)
        control.stall()
        assertTrue(control.stalled && control.cancelled)
        assertEquals(1, probe.closes)
    }

    @Test fun progressCountsTheBytesOfTheStepAndStartsOverWithTheNextStep() {
        control.step("first", 1000)
        control.hooks.onBytes(300)
        control.hooks.onBytes(200)
        assertEquals(500L, control.progress.done)
        assertEquals(1000L, control.progress.total)
        assertEquals("first", control.progress.label)
        control.step("second", 0)
        assertEquals(0L, control.progress.done)
        assertEquals("second", control.progress.label)
    }
}
