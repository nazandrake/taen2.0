// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone speed test: one step per typed word, the nearest-rank 95th percentile of the step times, the most memory added, and fail-closed memory. */
class NextWordSpeedTestTest {
    @Test fun thePercentileIsTheNearestRank() {
        assertEquals(0L, NextWordSpeedTest.p95(emptyList()))
        assertEquals(7L, NextWordSpeedTest.p95(listOf(7L)))
        assertEquals(95L, NextWordSpeedTest.p95((1L..100L).toList().shuffled(java.util.Random(1))))
        assertEquals(1L, NextWordSpeedTest.p95(List(19) { 1L } + 500L), "1 slow step of 20 (5 %): the 95th percentile is the 19th value")
        assertEquals(500L, NextWordSpeedTest.p95(List(18) { 1L } + 500L + 500L), "2 slow steps of 20 (10 %)")
    }

    @Test fun eachWordAfterTheFirstIsOneStep() {
        assertEquals(listOf("a", "a b"), NextWordSpeedTest.prefixes(" a  b c "))
        assertEquals(emptyList(), NextWordSpeedTest.prefixes("one"))
        for ((language, sentences) in NextWordSpeedTest.SAMPLES) assertTrue(sentences.sumOf { NextWordSpeedTest.prefixes(it).size } in 40..60, language)
    }

    private class Clock { var nanos = 0L }

    @Test fun theResultIsThe95thPercentileAndTheMostMemoryAdded() {
        val clock = Clock()
        var rss = 100_000L // KiB before the load
        val model = object : helium314.keyboard.latin.nextword.engine.NextTokenModel {
            var step = 0
            override fun logProbs(context: IntArray): FloatArray {
                step++
                clock.nanos += if (step % 10 == 0) 400_000_000 else 100_000_000 // every tenth step takes 400 ms, the others 100 ms
                rss = 100_000L + 200 * 1024 + step // 200 MiB more while it runs
                return FloatArray(32) { -1f }
            }
            override fun forget() {}
            override fun close() {}
        }
        var progressed = 0
        var free = 3000L
        val speed = NextWordSpeedTest({ clock.nanos }, { rss }, { free.also { free = 10 } }).run({ helium314.keyboard.latin.nextword.engine.NextWordRanker(tinyTokenizer(), model) },
            listOf("the cat the dog the fox the owl the cat the dog the fox", "the cat the dog the fox the owl the cat the dog the fox"), needMb = 100,
            progress = { done, _ -> progressed = done })!!
        assertEquals(26, progressed, "13 steps per sentence")
        assertEquals(400L, speed.p95Ms, "2 of 26 steps take 400 ms: more than 5 %, so the 95th percentile is 400")
        assertEquals(200L, speed.extraMb)
        assertEquals(3000L, speed.freeMb, "the free memory is read once, before the load")
    }

    @Test fun memoryThatCannotBeReadFailsTheTestAndCancelGivesNoResult() {
        val model = FakeModel(emptyMap())
        val unknown = NextWordSpeedTest({ 0L }, { -1L }, { 4000L }).run({ fakeRanker(model) }, listOf("the cat dog"), needMb = 0)!!
        assertEquals(NextWordSpeedTest.UNKNOWN_MB, unknown.extraMb)
        assertTrue(!unknown.passes, "unmeasured memory never passes")
        val known = NextWordSpeedTest({ 0L }, { 1000L }, { 4000L }).run({ fakeRanker(model) }, listOf("the cat dog"), needMb = 0)!!
        assertTrue(known.passes, "positive control: the same run with readable memory passes")
        var asked = 0
        assertNull(NextWordSpeedTest({ 0L }, { 1000L }, { 4000L }).run({ fakeRanker(model) }, listOf("the cat dog"), needMb = 0, cancelled = { ++asked > 1 }))
        assertTrue(model.closed, "the model is closed after the test")
    }

    @Test fun withLessFreeThanTheModelPlusTheMarginTheModelIsNotLoaded() {
        val model = FakeModel(emptyMap())
        var loads = 0
        val load = { loads++; fakeRanker(model) }
        val short = NextWordSpeedTest({ 0L }, { 1000L }, { 618L + 1023 }).run(load, listOf("the cat dog"), needMb = 618)!!
        assertEquals(0, loads, "not loaded: it could push the keyboard out of memory")
        assertEquals(Triple(0L, 618L, 1641L), Triple(short.p95Ms, short.extraMb, short.freeMb))
        assertEquals(true to false, short.fastEnough to short.enoughMemory, "the result says not enough memory, not too slow")
        val unreadable = NextWordSpeedTest({ 0L }, { 1000L }, { -1L }).run(load, listOf("the cat dog"), needMb = 618)!!
        assertEquals(0, loads, "free memory that cannot be read: not loaded either")
        assertEquals(0L, unreadable.freeMb)
        assertTrue(!unreadable.passes)
        val enough = NextWordSpeedTest({ 0L }, { 1000L }, { 618L + 1024 }).run(load, listOf("the cat dog"), needMb = 618)!!
        assertEquals(1, loads, "positive control: exactly the model plus the margin free, it is loaded and tested")
        assertEquals(1642L, enough.freeMb)
    }
}
