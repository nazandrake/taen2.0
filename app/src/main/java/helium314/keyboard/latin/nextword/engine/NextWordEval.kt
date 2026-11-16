// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import java.util.Locale

/**
 * The measurement of a model on the reference set (tools/nextword/contexts.py: 1000 held-out contexts, the same as tools/dict-eval.sh), shared by the host
 * run (:nextword-engine:nextWordEval) and the phone benchmark, so both measure the same thing. Each context is typed word by word as in the keyboard (one
 * [NextWordRanker.rank] call per word, the first one without kept state); the last call ranks the dictionary's predictions for the next word. A hit is the
 * expected word among the first three, in lower case: for the dictionary alone its first three predictions, with the model the first three of the ranking.
 */
class NextWordEval(private val clockNanos: () -> Long) {
    class Case(val expected: String, val prefix: String, val dictionary: List<String>)

    class Result(val cases: Int, val dictionaryHits: Int, val modelHits: Int, val stepMillis: List<Long>) {
        fun percentile(p: Double): Long {
            if (stepMillis.isEmpty()) return 0
            val sorted = stepMillis.sorted()
            return sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        }
    }

    fun run(ranker: NextWordRanker, cases: List<Case>, isWord: (String) -> Boolean): Result {
        var dictionaryHits = 0
        var modelHits = 0
        val times = ArrayList<Long>()
        for (case in cases) {
            ranker.forget()
            val words = case.prefix.split(' ').filter { it.isNotEmpty() }
            for (k in 1..words.size) {
                val last = k == words.size
                val start = clockNanos()
                val ranked = ranker.rank(words.subList(0, k).joinToString(" "), if (last) case.dictionary else emptyList(), isWord)
                times.add((clockNanos() - start) / 1_000_000)
                if (last && hit(case.expected, ranked.take(3).map { it.word })) modelHits++
            }
            if (hit(case.expected, case.dictionary.take(3))) dictionaryHits++
        }
        return Result(cases.size, dictionaryHits, modelHits, times)
    }

    private fun hit(expected: String, words: List<String>) = words.any { it.lowercase(Locale.ROOT) == expected }

    companion object {
        /** One line of contexts.tsv: the expected word, the text before it, the dictionary's predictions separated by '|'. */
        fun parse(line: String): Case {
            val c = line.split('\t')
            require(c.size == 3) { "a context line has 3 columns" }
            return Case(c[0], c[1], c[2].split('|').filter { it.isNotEmpty() })
        }
    }
}
