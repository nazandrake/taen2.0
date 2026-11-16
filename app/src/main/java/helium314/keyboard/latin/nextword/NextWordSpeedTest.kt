// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.nextword.engine.NextWordRanker
import kotlin.math.ceil
import kotlin.math.max

/**
 * The one-time speed test of a model on this phone: the model is loaded, then fixed sample sentences are typed word by word, and every word is one
 * suggestion step (one [NextWordRanker.rank] call, as in the keyboard; the first word of each sentence starts without kept state). The result is the
 * 95th percentile of the step times, the most resident memory the model added ([residentKb] before the load against the highest value seen) and the
 * memory the phone reported free before the load ([freeMb]). [clockNanos], [residentKb] and [freeMb] are parameters so tests use fakes; the app reads
 * /proc/self/status and ActivityManager.MemoryInfo (a negative value: it could not be read).
 */
class NextWordSpeedTest(private val clockNanos: () -> Long, private val residentKb: () -> Long, private val freeMb: () -> Long) {
    /**
     * Null when [cancelled] became true. [load] throws when the model cannot be loaded. [needMb] is the least the model needs (the size of its file): when
     * the phone reports less free than that plus [NextWordGate.PHONE_MARGIN_MB], or the free memory cannot be read, the model is not loaded at all (it
     * could push the keyboard out of memory) and the result fails on memory.
     */
    fun run(load: () -> NextWordRanker, sentences: List<String>, needMb: Long, cancelled: () -> Boolean = { false },
            progress: (Int, Int) -> Unit = { _, _ -> }): NextWordGate.Speed? {
        val free = max(0L, freeMb())
        if (free - needMb < NextWordGate.PHONE_MARGIN_MB) return NextWordGate.Speed(0, needMb, free)
        val before = residentKb()
        var peak = before
        var unknown = before < 0
        val steps = sentences.sumOf { prefixes(it).size }
        val times = ArrayList<Long>(steps)
        load().use { ranker ->
            residentKb().let { if (it < 0) unknown = true else peak = max(peak, it) }
            for (sentence in sentences) {
                ranker.forget()
                for (prefix in prefixes(sentence)) {
                    if (cancelled()) return null
                    val start = clockNanos()
                    ranker.rank(prefix, emptyList()) { false }
                    times.add((clockNanos() - start) / 1_000_000)
                    residentKb().let { if (it < 0) unknown = true else peak = max(peak, it) }
                    progress(times.size, steps)
                }
            }
        }
        // memory that could not be read counts as too much: the test fails rather than passing unmeasured
        return NextWordGate.Speed(p95(times), if (unknown) UNKNOWN_MB else max(0L, peak - before) / 1024, free)
    }

    companion object {
        const val UNKNOWN_MB = 999_999L

        /** The text before each word after the first: "a b c" gives "a" and "a b". */
        fun prefixes(sentence: String): List<String> {
            val words = sentence.trim().split(' ').filter { it.isNotEmpty() }
            return (1 until words.size).map { words.subList(0, it).joinToString(" ") }
        }

        /** The nearest-rank 95th percentile (the smallest value that at least 95 % of [times] do not exceed); 0 for none. */
        fun p95(times: List<Long>): Long {
            if (times.isEmpty()) return 0
            val sorted = times.sorted()
            return sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        }

        /** Public sample sentences (written for this test), about 40 steps per language. */
        val SAMPLES: Map<String, List<String>> = mapOf(
            "en" to listOf(
                "I will be home a little later than usual tonight because the meeting started late",
                "Could you please send me the photos from the weekend when you have a moment",
                "The weather is supposed to be much better tomorrow so we could go for a walk",
                "Thank you so much for the lovely dinner and the great company yesterday",
            ),
            "bg" to listOf(
                "Ще се прибера малко по-късно от обикновено тази вечер защото срещата започна късно",
                "Можеш ли да ми пратиш снимките от събота и неделя когато имаш малко време",
                "Утре времето трябва да е много по-хубаво така че може да излезем на разходка",
                "Благодаря ти много за хубавата вечеря и приятната компания вчера",
            ),
        )
    }
}
