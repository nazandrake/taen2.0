// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.NextWordRanker
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

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
        submit {
            if (mine != generation || clock() - start > DEADLINE_MILLIS) return@submit
            val ranker = holder.get() ?: return@submit
            val ranked = try {
                ranker.rank(context, dictionary, isWord)
            } catch (_: Exception) {
                ranker.forget()
                return@submit
            }
            if (clock() - start > DEADLINE_MILLIS) return@submit
            // checked again where it is applied: the main thread can be busy for a while after the step ended
            main.execute { if (mine == generation && clock() - start <= DEADLINE_MILLIS) onResult(ranked) }
        }
    }

    /** Loads the model ahead of the first request (the load takes seconds; a request would be late). */
    fun prepare(holder: NextWordEngineHolder) = submit { holder.get() }

    /** The field changed or the screen locked: a running request is dropped, and the model forgets the text. Called on the main thread. */
    fun cancel(holders: Collection<NextWordEngineHolder>) {
        generation++
        submit { holders.forEach { it.forget() } }
    }

    // The model thread is shut down when the keyboard service ends, but the framework's finish-input callbacks and the screen lock hook, which
    // stays registered for the whole process, still arrive after that. A step that has no thread left to run on is dropped: there is no model state left to forget.
    private fun submit(step: Runnable) {
        try {
            worker.execute(step)
        } catch (_: RejectedExecutionException) {
        }
    }

    companion object {
        const val DEADLINE_MILLIS = 150L
    }
}
