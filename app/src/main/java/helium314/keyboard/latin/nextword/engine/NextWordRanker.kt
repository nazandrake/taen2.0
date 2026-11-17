// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

/**
 * Orders next-word candidates with one model step. The candidates are the dictionary's predictions plus the model's most likely next tokens that are
 * a whole word of the active dictionaries ([isWord]): the model never shows a word the dictionaries do not have. Every candidate is scored by the
 * log-probability of its first token after the context (with the leading space the word has in running text); equal scores keep the dictionary's order,
 * then the model's. The same code runs in the keyboard, in the host measurement and in the phone benchmark.
 */
class NextWordRanker(private val tokenizer: BpeTokenizer, private val model: NextTokenModel) : AutoCloseable {
    class Candidate(val word: String, val fromModel: Boolean)

    /** Drops what the model keeps of the last text. */
    fun forget() = model.forget()

    override fun close() = model.close()

    /**
     * [context] is the text before the cursor (trailing spaces are ignored); [dictionary] the dictionary's predictions in their order. Returns all
     * candidates, best first; an empty context gives the dictionary's list unchanged (the model has nothing to read).
     */
    fun rank(context: String, dictionary: List<String>, isWord: (String) -> Boolean): List<Candidate> {
        val tokens = tokenizer.encode(context.trimEnd())
        if (tokens.isEmpty()) return dictionary.distinct().map { Candidate(it, false) }
        val lp = model.logProbs(tokens)
        try {
            val seen = HashSet<String>()
            val scored = ArrayList<Pair<Candidate, Float>>()
            for (w in dictionary) if (seen.add(w)) scored.add(Candidate(w, false) to score(lp, w))
            for (id in topTokens(lp, MODEL_TOKENS)) {
                val piece = tokenizer.piece(id) ?: continue
                if (piece.length < 2 || piece[0] != ' ') continue
                val word = piece.substring(1)
                if (!word.codePoints().allMatch(Character::isLetter) || word in seen || !isWord(word)) continue
                seen.add(word)
                scored.add(Candidate(word, true) to lp[id])
            }
            return scored.sortedByDescending { it.second }.map { it.first } // stable: ties keep the order above
        } finally {
            lp.fill(0f)
            tokens.fill(0)
        }
    }

    private fun score(lp: FloatArray, word: String): Float {
        val first = tokenizer.encode(" $word").firstOrNull() ?: return Float.NEGATIVE_INFINITY
        return lp.getOrElse(first) { Float.NEGATIVE_INFINITY }
    }

    /** The ids of the [k] largest values, largest first. */
    private fun topTokens(lp: FloatArray, k: Int): IntArray {
        val ids = IntArray(minOf(k, lp.size)) { -1 }
        val values = FloatArray(ids.size) { Float.NEGATIVE_INFINITY }
        for (i in lp.indices) {
            val v = lp[i]
            if (v <= values[values.size - 1]) continue
            var j = values.size - 1
            while (j > 0 && values[j - 1] < v) {
                values[j] = values[j - 1]
                ids[j] = ids[j - 1]
                j--
            }
            values[j] = v
            ids[j] = i
        }
        values.fill(0f)
        return ids.filter { it >= 0 }.toIntArray()
    }

    companion object {
        /** How many of the model's most likely next tokens are looked at as extra candidates. */
        const val MODEL_TOKENS = 50
    }
}
