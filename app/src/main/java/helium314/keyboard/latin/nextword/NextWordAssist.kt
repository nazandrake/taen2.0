// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.NextWordRanker
import java.util.concurrent.Executor

/**
 * Runs one model step for the prediction strip off the main thread with a deadline. The dictionary's list is already on the strip; the model's order
 * replaces it only when it is there within [DEADLINE_MILLIS] of the request and no newer request or field change came in between. A late result is
 * dropped (the dictionary's list stays). [worker] is the one model thread, [main] the main thread; [clock] is a monotonic clock in milliseconds.
 */
class NextWordAssist(private val worker: Executor, private val main: Executor, private val clock: () -> Long) {
    @Volatile private var generation = 0

    /** Called on the main thread. [onResult] runs on the main thread, at most once. */
    fun request(context: String, dictionary: List<String>, isWord: (String) -> Boolean, holder: NextWordEngineHolder, onResult: (List<NextWordRanker.Candidate>) -> Unit) {
        val mine = ++generation
        val start = clock()
        worker.execute {
            if (mine != generation || clock() - start > DEADLINE_MILLIS) return@execute
            val ranker = holder.get() ?: return@execute
            val ranked = try {
                ranker.rank(context, dictionary, isWord)
            } catch (_: Exception) {
                ranker.forget()
                return@execute
            }
            if (clock() - start > DEADLINE_MILLIS) return@execute
            // checked again where it is applied: the main thread can be busy for a while after the step ended
            main.execute { if (mine == generation && clock() - start <= DEADLINE_MILLIS) onResult(ranked) }
        }
    }

    /** Loads the model ahead of the first request (the load takes seconds; a request would be late). */
    fun prepare(holder: NextWordEngineHolder) = worker.execute { holder.get() }

    /** The field changed or the screen locked: a running request is dropped, and the model forgets the text. Called on the main thread. */
    fun cancel(holders: Collection<NextWordEngineHolder>) {
        generation++
        worker.execute { holders.forEach { it.forget() } }
    }

    companion object {
        const val DEADLINE_MILLIS = 150L
    }
}
