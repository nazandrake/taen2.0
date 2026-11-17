// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Which part of a context the model reads, and how much of the kept state (the attention cache of the last window) can be reused. The model always
 * sees one window of the context, `context[windowStart until end]`. When the last window ends a few tokens before the end of the new context (the user
 * typed on), only those tokens are fed. Otherwise (another field, a cursor move, an edit, or the window would grow past [MAX]) the model starts again on
 * the last [KEEP] tokens.
 */
object KvWindow {
    const val KEEP = 48
    const val MAX = 128
    const val MAX_STEP = 48

    /** Feed `context[feedFrom until end]`; [reuse] says whether the kept state stays (else it is dropped first). */
    class Plan(val reuse: Boolean, val feedFrom: Int, val windowStart: Int)

    fun plan(kept: IntArray?, context: IntArray): Plan {
        if (kept != null && kept.isNotEmpty()) {
            val most = min(MAX_STEP, context.size - kept.size)
            for (added in 0..most) {
                if (kept.size + added > MAX) break
                val start = context.size - added - kept.size
                if (regionEquals(context, start, kept)) return Plan(true, context.size - added, start)
            }
        }
        val start = max(0, context.size - KEEP)
        return Plan(false, start, start)
    }

    private fun regionEquals(context: IntArray, start: Int, kept: IntArray): Boolean {
        for (i in kept.indices) if (context[start + i] != kept[i]) return false
        return true
    }
}
