// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.emoji.EmojiSearchIndex
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The emoji suggestions of the keyboard: a dictionary of type "emoji" over the CLDR keyword index. As a suggestion
 * (the strip) it gives the emoji of a whole typed keyword; in search mode (inline search with ":") the words and the start
 * of words. It never makes an emoji or a keyword a valid word, and it hands no words to the glide word list.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiKeywordDictionaryTest {
    private val enIndex = EmojiSearchIndex.parse(
        listOf(
            "😀\tgrinning face\tgrin|smile|face",
            "😃\tgrinning face with big eyes\tsmile|happy|face",
            "❤️\tred heart\theart|love|red heart",
            "❤️‍🔥\theart on fire\theart|fire|love",
            "🔥\tfire\tfire|flame",
            "🐶\tdog face\tdog|face",
            "🐱\tcat face\tcat|face",
            "🅰️\tA button (blood type)\ta|blood type",
        ).joinToString("\n"), Locale.ENGLISH
    )
    private val bgIndex = EmojiSearchIndex.parse("❤️\tЧервено сърце\tсърце|любов|love\n🐶\tМуцуна на куче\tкуче", Locale("bg"))
    private val strip = EmojiKeywordDictionary(Locale.ENGLISH, listOf(enIndex), searchMode = false)
    private val search = EmojiKeywordDictionary(Locale.ENGLISH, listOf(enIndex), searchMode = true)

    private fun ask(dictionary: Dictionary, typed: String): List<SuggestedWordInfo>? = dictionary.getSuggestions(
        ComposedData.createForWord(typed), NgramContext.getEmptyPrevWordsContext(0), 0L, SettingsValuesForSuggestion(false, false, true),
        0, 1f, floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL)
    )

    @Test fun aTypedKeywordSuggestsItsEmojiAsCompletions() {
        val result = assertNotNull(ask(strip, "heart"))
        assertEquals(listOf("❤️", "❤️‍🔥"), result.map { it.word })
        result.forEach {
            assertEquals(SuggestedWordInfo.KIND_COMPLETION, it.kind)
            assertSame(strip, it.mSourceDict)
            assertEquals(Dictionary.TYPE_EMOJI, it.mSourceDict.mDictType)
            // far above the scores of words (about 1.5 million for an exact match), so that the best emoji takes the third slot of
            // the strip (Suggest keeps the first two non-emoji); below the maximum of the typed word and the whitelist
            assertTrue(it.mScore in 1_900_000_000 until SuggestedWordInfo.MAX_SCORE, "score ${it.mScore}")
        }
        assertTrue(result[0].mScore > result[1].mScore) // the order of the list is the order of the scores
    }

    @Test fun inTheStripOnlyAWholeKeywordCountsAndNotMoreThanThreeEmojiAreSuggested() {
        assertNull(ask(strip, "hear")) // the start of a keyword while typing: nothing
        assertNull(ask(strip, "h"))
        assertNull(ask(strip, "a")) // one letter is never suggested, though "a" is a keyword of 🅰️ (positive control below)
        assertEquals(listOf("🅰️"), enIndex.exact("a"))
        assertNull(ask(strip, ""))
        assertNull(ask(strip, "zzz"))
        // 4 emoji have "face"; the shortest names first ("dog face" and "cat face" 8 letters, "grinning face" 13, the long one 27;
        // equal length: palette order): 3 are suggested
        assertEquals(listOf("🐶", "🐱", "😀"), assertNotNull(ask(strip, "face")).map { it.word })
        assertEquals(listOf("😀", "😃"), assertNotNull(ask(strip, "Smile")).map { it.word })
    }

    @Test fun theLocaleWeightScalesTheScores() {
        fun scores(weight: Float) = assertNotNull(strip.getSuggestions(
            ComposedData.createForWord("heart"), NgramContext.getEmptyPrevWordsContext(0), 0L, SettingsValuesForSuggestion(false, false, true),
            0, weight, floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL))).map { it.mScore }
        val full = scores(1f)
        val half = scores(0.5f)
        assertEquals(full.size, half.size)
        full.zip(half).forEach { (f, h) -> assertEquals(f / 2.0, h.toDouble(), 1.0) }
        assertTrue(scores(0f).all { it == 1 }) // a score is never below 1
    }

    @Test fun searchModeAlsoFindsWordsAndStartsOfWordsAndAllTheMatches() {
        assertEquals(listOf("❤️", "❤️‍🔥"), assertNotNull(ask(search, "hea")).map { it.word })
        assertEquals(listOf("🐶", "🐱", "😀", "😃"), assertNotNull(ask(search, "face")).map { it.word }) // all four: no cut at three
        assertNull(ask(search, "x"))
    }

    @Test fun aSearchInTheMainLanguageIsExtendedByEnglishAndNothingElseIsMixedIn() {
        val bgSearch = EmojiKeywordDictionary(Locale("bg"), listOf(bgIndex, enIndex), searchMode = true)
        assertEquals(listOf("❤️"), assertNotNull(ask(bgSearch, "сърце")).map { it.word })
        assertEquals(listOf("❤️", "❤️‍🔥"), assertNotNull(ask(bgSearch, "heart")).map { it.word }) // English keywords still work
        val bgStrip = EmojiKeywordDictionary(Locale("bg"), listOf(bgIndex), searchMode = false)
        assertNull(ask(bgStrip, "heart")) // the suggestion strip is of the typing language only
        assertEquals(listOf("🐶"), assertNotNull(ask(bgStrip, "КУЧЕ")).map { it.word })
        // an emoji of both lists is suggested once (❤️ has the keyword "love" in both fixtures)
        assertEquals(listOf("❤️", "❤️‍🔥"), assertNotNull(ask(bgSearch, "love")).map { it.word })
    }

    @Test fun anEmojiOrAKeywordIsNeverAValidWordAndTheDictionaryHoldsNoWordsForGlideTyping() {
        assertTrue(!strip.isInDictionary("heart") && !strip.isValidWord("heart") && !strip.isInDictionary("❤️"))
        assertEquals(Dictionary.NOT_A_PROBABILITY, strip.getFrequency("heart"))
        assertEquals(Dictionary.NOT_A_PROBABILITY, strip.getMaxFrequencyOfExactMatches("heart"))
        assertTrue(!strip.isPossiblyOffensive("heart"))
        // in the main dictionary collection next to a word list: the glide word list reads the word list only
        val main = object : Dictionary(TYPE_MAIN, Locale.ENGLISH) {
            override fun forEachWord(listener: WordListener): Boolean { listener.onWord("hello", 200); return true }
            override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
            override fun isInDictionary(word: String) = word == "hello"
        }
        val collection = DictionaryCollection(Dictionary.TYPE_MAIN, Locale.ENGLISH, listOf(main, strip), floatArrayOf(1f, 1f))
        val words = ArrayList<String>()
        collection.forEachWord { word, _ -> words.add(word) }
        assertEquals(listOf("hello"), words)
        assertTrue(!collection.isInDictionary("heart"))
        // the collection still answers suggestions with the emoji (the dictionary is part of the typing path)
        assertEquals(listOf("❤️", "❤️‍🔥"), assertNotNull(ask(collection, "heart")).map { it.word })
    }

    // ---- the bundled data as the factory hands it out ---------------------------------------------------------------

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun theSearchDictionaryHasTheLanguageAndEnglishAndDescribesEmoji() {
        val bg = assertNotNull(DictionaryFactory.emojiSearchDictionary(context, Locale("bg")))
        assertEquals(Locale("bg"), bg.mLocale) // SingleDictionaryFacilitator.isForLocale compares it
        assertTrue("❤️" in assertNotNull(ask(bg, "сърце")).map { it.word })
        assertTrue("❤️" in assertNotNull(ask(bg, "heart")).map { it.word }) // English keywords next to Bulgarian ones
        assertEquals("Червено сърце", assertNotNull(bg.getWordProperty("❤️", false)).mShortcutTargets[0].mWord)
        assertEquals("Червено сърце", assertNotNull(bg.getWordProperty("❤", false)).mShortcutTargets[0].mWord) // without the selector
        val en = assertNotNull(DictionaryFactory.emojiSearchDictionary(context, Locale.US))
        assertNull(ask(en, "сърце")) // English has no Bulgarian keywords
        assertEquals("red heart", assertNotNull(en.getWordProperty("❤️", false)).mShortcutTargets[0].mWord)
        assertNull(en.getWordProperty("not an emoji", false))
        assertNull(DictionaryFactory.emojiSearchDictionary(context, Locale.FRENCH)) // no data for French: no search
    }

    @Test fun theFactoryAddsTheBundledEmojiDictionaryOfTheLanguageOnlyWhenEmojiSuggestionsAreOn() {
        val bg = DictionaryFactory.withEmojiKeywords(context, Locale("bg", "BG"), emptyList(), true)
        assertEquals(listOf(Dictionary.TYPE_EMOJI), bg.map { it.mDictType })
        // CLDR bg: many hearts have the keyword "сърце"; three are suggested, each with its CLDR name as description
        val hearts = assertNotNull(ask(bg.single(), "сърце"))
        assertEquals(3, hearts.size)
        assertEquals(3, hearts.map { it.word }.toSet().size)
        hearts.forEach { assertTrue(assertNotNull(bg.single().getWordProperty(it.word, false)).mShortcutTargets[0].mWord.isNotEmpty(), it.word) }
        assertEquals(emptyList(), DictionaryFactory.withEmojiKeywords(context, Locale("bg"), emptyList(), false)) // switched off
        assertEquals(emptyList(), DictionaryFactory.withEmojiKeywords(context, Locale.FRENCH, emptyList(), true)) // no data for French
        assertEquals(emptyList(), DictionaryFactory.withEmojiKeywords(context, Locale("bg"), emptyList(), true).filter { it.mDictType != Dictionary.TYPE_EMOJI })
        val en = DictionaryFactory.withEmojiKeywords(context, Locale.US, emptyList(), true).single()
        assertEquals("❤️", assertNotNull(ask(en, "heart")).first().word) // "red heart" is the plainest of the hearts
        // a dictionary list that already has an emoji dictionary (a user-supplied one) is left as it is
        val own = EmojiKeywordDictionary(Locale.US, listOf(enIndex), false)
        assertEquals(listOf<Dictionary>(own), DictionaryFactory.withEmojiKeywords(context, Locale.US, listOf(own), true))
    }
}
