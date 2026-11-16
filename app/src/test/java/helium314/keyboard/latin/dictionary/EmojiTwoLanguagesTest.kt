// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.inputmethod.keyboard.ProximityInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.emoji.EmojiSearchIndex
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * With English and Bulgarian both enabled the strip still shows the emoji of an English word: the language weight (0.85 for
 * each of two languages) does not scale the emoji score of 2e9 below the words. The real
 * DictionaryFacilitatorImpl with the real emoji dictionaries (CLDR keywords of the bundled assets) next to fake main
 * dictionaries that answer like the native ones (scores of about 1 to 1.5 million): the emoji of an English word stays above
 * every word, with one language and with two, typed in lower case and with a capital.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiTwoLanguagesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val en = Locale.US
    private val bg = Locale("bg")

    /** A main dictionary that answers every word with the same corrections, like the native dictionary for "dog". */
    private class Main(locale: Locale, private val words: List<Pair<String, Int>>) : Dictionary(TYPE_MAIN, locale) {
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? =
            if (words.isEmpty()) null else ArrayList(words.map {
                SuggestedWordInfo(it.first, "", (it.second * weightForLocale).toInt(), SuggestedWordInfo.KIND_CORRECTION, this,
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            })
        override fun isInDictionary(word: String) = words.any { it.first == word }
    }

    private fun group(locale: Locale, words: List<Pair<String, Int>>): Triple<Locale, Dictionary, Map<String, ExpandableBinaryDictionary>> {
        val dicts = DictionaryFactory.withEmojiKeywords(ctx, locale, listOf(Main(locale, words)), true)
        return Triple(locale, DictionaryCollection(Dictionary.TYPE_MAIN, locale, dicts, FloatArray(dicts.size) { 1f }), emptyMap())
    }

    private val keyboard: Keyboard = run {
        val proximity = Mockito.mock(ProximityInfo::class.java)
        Mockito.mock(Keyboard::class.java).also { Mockito.`when`(it.proximityInfo).thenReturn(proximity) }
    }

    private fun typing(typed: String, vararg groups: Triple<Locale, Dictionary, Map<String, ExpandableBinaryDictionary>>): List<SuggestedWordInfo> {
        val facilitator = DictionaryFacilitatorImpl()
        facilitator.installGroupsForTest(groups.toList())
        return facilitator.getSuggestionResults(
            ComposedData.createForWord(typed), NgramContext.getEmptyPrevWordsContext(0), keyboard,
            SettingsValuesForSuggestion(false, false, true), 0, SuggestedWords.INPUT_STYLE_TYPING
        ).toList()
    }

    private val englishWords = listOf("dog" to 1_500_000, "fog" to 1_300_000, "dot" to 1_250_000, "dig" to 1_200_000)

    @Test fun theEmojiOfAnEnglishWordLeadsTheResultsAloneAndWithBulgarianEnabled() {
        val best = assertNotNull(EmojiSearchIndex.load(ctx, "en")).exact("dog").first() // the CLDR keyword index decides which emoji is best
        for (typed in listOf("dog", "Dog")) {
            val alone = typing(typed, group(en, englishWords))
            assertEquals(best, alone.first().word, "English alone, $typed")
            val both = typing(typed, group(en, englishWords), group(bg, emptyList()))
            assertEquals(best, both.first().word, "English and Bulgarian, $typed")
            // the premise of the hypothesis holds: two languages weigh the emoji score down (2e9 * 0.85 = 1.7e9, hand calculation)...
            assertTrue(both.first().mScore in 1_650_000_000..1_750_000_000, "$typed score ${both.first().mScore}")
            // ...and the emoji still sits above every word, whatever the weight of the language, so that the strip puts it in its third slot
            assertTrue(both.first().mScore > both.drop(1).maxOf { it.mScore }, "$typed ${both.map { it.word to it.mScore }}")
        }
    }
}
