// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.nextword.engine.NextWordRanker

/**
 * Puts the model's order onto a prediction strip. The dictionary's entries keep their own info (source, score); a word the model added is a
 * prediction of the source [Dictionary.DICTIONARY_NEXTWORD], which is never learned when it is picked (InputLogic). The result never auto-corrects,
 * and every entry of the dictionary's list stays on it (after the ranked ones), so nothing the dictionary offered is lost.
 */
object NextWordMerge {
    fun reorder(words: SuggestedWords, ranked: List<NextWordRanker.Candidate>): SuggestedWords {
        val original = (0 until words.size()).map { words.getInfo(it) }
        val byWord = original.associateBy { it.mWord }
        val list = ArrayList<SuggestedWordInfo>(original.size + ranked.size)
        val placed = HashSet<String>()
        for (c in ranked) {
            if (!placed.add(c.word)) continue
            val info = if (!c.fromModel) byWord[c.word] ?: continue
                else SuggestedWordInfo(c.word, "", MODEL_SCORE, SuggestedWordInfo.KIND_PREDICTION, Dictionary.DICTIONARY_NEXTWORD,
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            list.add(info)
        }
        for (info in original) if (placed.add(info.mWord)) list.add(info)
        return SuggestedWords(list, words.mRawSuggestions, words.mTypedWordInfo, words.mTypedWordValid, false, words.mIsObsoleteSuggestions,
            words.mInputStyle, words.mSequenceNumber)
    }

    /** Whether a picked suggestion came from the model (then it is not learned). */
    fun fromModel(info: SuggestedWordInfo?): Boolean = info?.mSourceDict?.mDictType == Dictionary.TYPE_NEXTWORD

    private const val MODEL_SCORE = 0
}
