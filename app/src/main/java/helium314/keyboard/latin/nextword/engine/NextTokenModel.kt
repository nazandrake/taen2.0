// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

/** A causal language model seen from the ranking: the distribution of the token after a context. Tests use a fake. */
interface NextTokenModel : AutoCloseable {
    /**
     * The log-probabilities of every token of the vocabulary as the next token after [context] (token ids, oldest first). The array is the caller's: it
     * zeroes it after use. An implementation may keep state of the last context to make the next, longer one cheaper; [forget] drops it.
     */
    fun logProbs(context: IntArray): FloatArray

    /** Drops what the model keeps of the last context (the field changed, the screen locked). */
    fun forget()
}
