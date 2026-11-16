// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.NextWordRanker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** LM2 timing: the model's order reaches the strip only within 150 ms of the request and only for the newest request of the same field. */
class NextWordAssistTest {
    private val worker = QueueExecutor()
    private val main = QueueExecutor()
    private var now = 1_000L
    private val assist = NextWordAssist(worker, main) { now }
    private val model = FakeModel(mapOf(2 to -0.5f, 1 to -1f, 3 to -2f))
    private val holder = NextWordEngineHolder({ true }) { fakeRanker(model) }
    private var shown: List<String>? = null
    private val show: (List<NextWordRanker.Candidate>) -> Unit = { shown = it.map { c -> c.word } }

    private fun request(dictionary: List<String> = listOf("cat", "fox")) = assist.request("the", dictionary, { it == "dog" }, holder, show)

    @Test fun aResultWithinTheDeadlineReordersTheStrip() {
        request()
        now += 150 // exactly at the deadline: still in time
        worker.runAll()
        main.runAll()
        assertEquals(listOf("dog", "cat", "fox"), shown, "the model's order, with its own dictionary word dog added")
        assertEquals(1, model.calls)
    }

    @Test fun aResultLaterThan150MsIsDroppedAndTheDictionaryListStays() {
        request()
        worker.runAll() // the step itself takes no fake time here: the request waited too long before it started
        now += 151
        main.runAll()
        assertEquals(listOf("dog", "cat", "fox"), shown, "positive control: in time when the clock has not moved past the deadline before the end of the step")
        shown = null
        request()
        now += 151
        worker.runAll()
        main.runAll()
        assertNull(shown, "started after the deadline: dropped")
        assertEquals(1, model.calls, "a request that is already late does not run the model")
    }

    @Test fun aStepThatEndsAfterTheDeadlineIsDropped() {
        val slow = FakeModel(mapOf(2 to -0.5f))
        val slowHolder = NextWordEngineHolder({ true }) { fakeRanker(object : helium314.keyboard.latin.nextword.engine.NextTokenModel by slow {
            override fun logProbs(context: IntArray): FloatArray { now += 151; return slow.logProbs(context) }
        }) }
        assist.request("the", listOf("cat"), { true }, slowHolder, show)
        worker.runAll()
        main.runAll()
        assertNull(shown)
        assertEquals(1, slow.calls, "positive control: the model did run")
    }

    @Test fun aNewerRequestOrAFieldChangeDropsTheOlderOne() {
        request(listOf("cat"))
        request(listOf("fox"))
        worker.runAll()
        main.runAll()
        assertEquals(listOf("dog", "fox"), shown, "only the newest request is shown")
        assertEquals(1, model.calls, "the older one never ran")
        shown = null
        request()
        assist.cancel(listOf(holder))
        worker.runAll()
        main.runAll()
        assertNull(shown)
        assertEquals(1, model.forgets, "the model forgot the text of the old field")
    }

    @Test fun aDamagedModelOrAFailingStepShowsNothing() {
        val damaged = NextWordEngineHolder({ false }) { fakeRanker(model) }
        assist.request("the", listOf("cat"), { true }, damaged, show)
        worker.runAll()
        main.runAll()
        assertNull(shown)
        assertEquals(0, model.calls)
        model.fail = true
        request()
        worker.runAll()
        main.runAll()
        assertNull(shown)
        assertEquals(1, model.forgets, "a failed step drops the kept state")
    }
}
