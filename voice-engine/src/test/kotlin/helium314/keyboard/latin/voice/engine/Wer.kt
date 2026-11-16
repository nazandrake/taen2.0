// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

/** Word error rate with the normalisation of tools/voice/wer.py: lower case, anything that is not a letter or digit is a separator. */
object Wer {
    fun words(text: String): List<String> =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }

    /** Word-level edit distance (substitution, insertion and deletion each cost 1). */
    fun edits(ref: List<String>, hyp: List<String>): Int {
        var prev = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val cur = IntArray(hyp.size + 1)
            cur[0] = i
            for (j in 1..hyp.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[hyp.size]
    }
}
