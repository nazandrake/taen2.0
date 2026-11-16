// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.nextword.engine.NextWordRanker.Candidate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** LM2: the model reorders the prediction strip, adds only what the ranking found in the dictionaries, keeps every dictionary entry and never auto-corrects. */
@RunWith(RobolectricTestRunner::class)
class NextWordMergeTest {
    private val main = Dictionary.PhonyDictionary(Dictionary.TYPE_MAIN)

    private fun info(word: String, score: Int) = SuggestedWordInfo(word, "", score, SuggestedWordInfo.KIND_PREDICTION, main, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private fun predictions(vararg words: String): SuggestedWords {
        val list = ArrayList(words.mapIndexed { i, w -> info(w, 1000 - i) })
        return SuggestedWords(list, null, null, false, false, false, SuggestedWords.INPUT_STYLE_PREDICTION, 7)
    }

    private fun words(s: SuggestedWords) = (0 until s.size()).map { s.getInfo(it).mWord }

    @Test fun theStripTakesTheModelsOrderAndKeepsEveryDictionaryEntry() {
        val before = predictions("the", "a", "this", "it")
        val after = NextWordMerge.reorder(before, listOf(Candidate("this", false), Candidate("weather", true), Candidate("the", false)))
        assertEquals(listOf("this", "weather", "the", "a", "it"), words(after), "ranked first, then the rest of the dictionary's list in its order")
        assertSame(before.getInfo(2), after.getInfo(0), "a dictionary entry keeps its own info")
        assertEquals(Dictionary.TYPE_NEXTWORD, after.getInfo(1).mSourceDict.mDictType, "a word the model added has the model's source")
        assertTrue(NextWordMerge.fromModel(after.getInfo(1)))
        assertFalse(NextWordMerge.fromModel(after.getInfo(0)), "positive control: a dictionary word is not the model's")
        assertFalse(NextWordMerge.fromModel(null))
    }

    @Test fun theResultIsAPredictionThatNeverAutoCorrects() {
        val before = SuggestedWords(ArrayList(listOf(info("the", 5))), null, null, false, true, false, SuggestedWords.INPUT_STYLE_PREDICTION, 3)
        val after = NextWordMerge.reorder(before, listOf(Candidate("the", false)))
        assertFalse(after.mWillAutoCorrect)
        assertTrue(after.isPrediction)
        assertEquals(3, after.mSequenceNumber)
    }

    @Test fun aRankedDictionaryWordThatIsNotOnTheStripIsNotInvented() {
        val after = NextWordMerge.reorder(predictions("the"), listOf(Candidate("ghost", false), Candidate("the", false), Candidate("the", true)))
        assertEquals(listOf("the"), words(after), "only words of the strip or the model's own (from the dictionaries) appear, each once")
    }
}
