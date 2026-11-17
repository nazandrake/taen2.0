// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With the opt-in "add to personal dictionary", a word whose learned frequency is above 120 is promoted permanently
 * into the personal dictionary the next time it is added (DictionaryFacilitatorImpl.addToPersonalDictionaryIfInvalidButInHistory). The undo of an
 * autocorrection adds the typed word twice (InputLogic.revertCommit), so two undos of one word reach that frequency without the user typing it
 * again. The undo passes mayPromoteToPersonalDictionary = false: it only teaches the decaying user history. The real facilitator runs over fake
 * dictionaries (the native ones are not available in unit tests); the history fake reports the frequency of a word that was added three times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class])
class RevertDoesNotPromoteTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun reset() { Privacy.unlock(ctx); Privacy.forgetCachedState() }

    private class Main : Dictionary(TYPE_MAIN, Locale.ENGLISH) {
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWords.SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = false
    }

    /** Like the native user history: the word is reported invalid, only its frequency tells how often it was added. */
    private class History(context: Context, private val frequency: Int) : ExpandableBinaryDictionary(context, "fake-history", Locale.ENGLISH, TYPE_USER_HISTORY, null) {
        override fun loadInitialContentsLocked() {}
        override fun getFrequency(word: String) = frequency
        override fun isInDictionary(word: String) = false
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWords.SuggestedWordInfo>? = null
    }

    private class User(context: Context) : ExpandableBinaryDictionary(context, "fake-user", Locale.ENGLISH, TYPE_USER, null) {
        override fun loadInitialContentsLocked() {}
        override fun isInDictionary(word: String) = false
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWords.SuggestedWordInfo>? = null
    }

    private fun facilitator(frequency: Int): DictionaryFacilitatorImpl {
        Settings.init(ctx)
        ctx.prefs().edit { putBoolean(Settings.PREF_ADD_TO_PERSONAL_DICTIONARY, true) }
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT }
        Settings.getInstance().loadSettings(ctx, Locale.ENGLISH, InputAttributes(editor, false, ctx.packageName)) // a field that autocorrects, as the opt-in requires
        assertTrue(Settings.getValues().mAddToPersonalDictionary, "the opt-in is on")
        return DictionaryFacilitatorImpl().also {
            it.installDictionariesForTest(Locale.ENGLISH, Main(), mapOf(Dictionary.TYPE_USER_HISTORY to History(ctx, frequency), Dictionary.TYPE_USER to User(ctx)))
        }
    }

    private fun add(f: DictionaryFacilitatorImpl, word: String, mayPromote: Boolean) =
        f.addToUserHistory(word, false, NgramContext.getEmptyPrevWordsContext(2), 1_000L, false, mayPromote)

    private fun personalWords(): List<String> = Privacy.personalDictionary(ctx)!!.all().map { it.word }

    /** The promotion runs on a background scope: wait for a word to arrive, or for a short time when none is expected. */
    private fun waitFor(expected: Boolean) {
        val end = System.nanoTime() + (if (expected) 5_000L else 500L) * 1_000_000
        while (System.nanoTime() < end && (personalWords().isEmpty() == expected)) Thread.sleep(10)
    }

    @Test fun aWordAddedAgainAboveTheFrequencyIsPromoted() { // positive control: the path exists and is reached
        val f = facilitator(frequency = 150)
        add(f, "zorbling", mayPromote = true)
        waitFor(expected = true)
        assertEquals(listOf("zorbling"), personalWords())
    }

    @Test fun theSameWordFromAnUndoneCorrectionIsNeverPromoted() {
        val f = facilitator(frequency = 150) // the frequency after repeated undos of the same word
        add(f, "zorbling", mayPromote = false)
        add(f, "zorbling", mayPromote = false)
        waitFor(expected = false)
        assertEquals(emptyList(), personalWords())
        // the same facilitator promotes the word when it is typed normally: only the undo is excluded
        add(f, "zorbling", mayPromote = true)
        waitFor(expected = true)
        assertEquals(listOf("zorbling"), personalWords())
    }

    @Test fun aWordBelowTheFrequencyIsNotPromotedEvenWhenAllowed() { // keeps the existing threshold
        val f = facilitator(frequency = 111)
        add(f, "zorbling", mayPromote = true)
        waitFor(expected = false)
        assertEquals(emptyList(), personalWords())
    }
}
