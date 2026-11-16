// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which part of the context is fed: only the new tokens when the text grew, a fresh window of the last 48 tokens otherwise. */
class KvWindowTest {
    private fun ids(n: Int, from: Int = 0) = IntArray(n) { from + it }

    private fun plan(kept: IntArray?, context: IntArray) = KvWindow.plan(kept, context).let { Triple(it.reuse, it.feedFrom, it.windowStart) }

    @Test fun nothingKeptStartsOnTheLastKeepTokens() {
        assertEquals(Triple(false, 0, 0), plan(null, ids(10)))
        assertEquals(Triple(false, 0, 0), plan(null, ids(KvWindow.KEEP)))
        assertEquals(Triple(false, 1, 1), plan(null, ids(KvWindow.KEEP + 1)))
        assertEquals(Triple(false, 52, 52), plan(IntArray(0), ids(100)))
    }

    @Test fun aGrownTextFeedsOnlyTheNewTokens() {
        assertEquals("two new tokens", Triple(true, 10, 0), plan(ids(10), ids(12)))
        assertEquals("the same text: nothing to feed, the kept answer is used", Triple(true, 10, 0), plan(ids(10), ids(10)))
        assertEquals("at most MAX_STEP new tokens (fed from index 10)", Triple(true, 10, 0), plan(ids(10), ids(10 + KvWindow.MAX_STEP)))
        assertEquals("one more: a fresh window", Triple(false, 10 + KvWindow.MAX_STEP + 1 - KvWindow.KEEP, 10 + KvWindow.MAX_STEP + 1 - KvWindow.KEEP), plan(ids(10), ids(10 + KvWindow.MAX_STEP + 1)))
        // the kept window can sit later in the context (the text before it is not in the window)
        assertEquals(Triple(true, 30, 20), plan(ids(10, 20), ids(32)))
    }

    @Test fun anEditOrACursorMoveStartsAFreshWindow() {
        val edited = ids(12).also { it[5] = 999 }
        assertEquals(Triple(false, 0, 0), plan(ids(10), edited))
        assertEquals("shorter text (deleted words)", Triple(false, 0, 0), plan(ids(10), ids(8)))
    }

    @Test fun theWindowNeverGrowsPastMax() {
        assertEquals("exactly MAX tokens in the window", Triple(true, KvWindow.MAX - 1, 0), plan(ids(KvWindow.MAX - 1), ids(KvWindow.MAX)))
        val c = ids(KvWindow.MAX + 1)
        assertEquals("one more than MAX: a fresh window", Triple(false, c.size - KvWindow.KEEP, c.size - KvWindow.KEEP), plan(ids(KvWindow.MAX), c))
    }
}
