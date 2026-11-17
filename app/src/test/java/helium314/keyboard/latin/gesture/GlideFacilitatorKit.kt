// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import android.graphics.Rect
import com.android.inputmethod.keyboard.ProximityInfo
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.mockito.Mockito
import java.util.Locale

/**
 * The real DictionaryFacilitatorImpl (suggestion and glide wiring) over fake dictionaries: the real ones need the native
 * library. Used by the privacy wiring tests: what reaches the suggestions of a field comes out of getSuggestionResults.
 */
class GlideFacilitatorKit(context: Context, forSpellChecking: Boolean = false) {
    class FakeMain(private val words: List<Pair<String, Int>>) : Dictionary(TYPE_MAIN, Locale.ENGLISH) {
        override fun forEachWord(listener: WordListener): Boolean { words.forEach { listener.onWord(it.first, it.second) }; return true }
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = words.any { it.first == word }
    }

    /**
     * A learned-word or personal dictionary. Like the native one it keeps what it was given until it is recreated: the
     * words stay after a screen lock (that is the data the privacy rules must keep out of glide).
     */
    class FakeUser(context: Context, type: String, var words: List<Pair<String, Int>>) :
        ExpandableBinaryDictionary(context, "fake-$type", Locale.ENGLISH, type, null) {
        var suggestionCalls = 0
        var version = 1L
        /** Test seam: runs when glide asks for the content version, i.e. after the facilitator authorized the user words and before they are scored. */
        var onContentVersion: (() -> Unit)? = null
        override fun loadInitialContentsLocked() {}
        override fun contentVersion(): Long { onContentVersion?.invoke(); return version }
        override fun forEachWord(listener: WordListener): Boolean { words.forEach { listener.onWord(it.first, it.second) }; return true }
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? {
            suggestionCalls++
            return ArrayList(words.map {
                SuggestedWordInfo(it.first, "", 1_000, SuggestedWordInfo.KIND_TYPED, this, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            })
        }
        override fun isInDictionary(word: String) = words.any { it.first == word }
    }

    val main = FakeMain(listOf("hello" to 200, "world" to 200, "word" to 200))
    val history = FakeUser(context, Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
    val personal = FakeUser(context, Dictionary.TYPE_USER, listOf("quibble" to 200))
    val facilitator = DictionaryFacilitatorImpl(GlideSuggester(background = { it() }), forSpellChecking).also {
        it.installDictionariesForTest(Locale.ENGLISH, main, mapOf(Dictionary.TYPE_USER_HISTORY to history, Dictionary.TYPE_USER to personal))
    }

    private val qwertyKeys = GlideTestKit.keys("qwerty")

    private fun key(code: Int, x: Float, y: Float, w: Float, h: Float): Key {
        val key = Mockito.mock(Key::class.java)
        Mockito.`when`(key.code).thenReturn(code)
        Mockito.`when`(key.hitBox).thenReturn(Rect((x - w / 2 - 2).toInt(), (y - h / 2 - 2).toInt(), (x + w / 2 + 2).toInt(), (y + h / 2 + 2).toInt()))
        return key
    }

    private val keyboard: Keyboard = run {
        val keys = qwertyKeys.map { k -> key(k.code, k.x, k.y, k.width, k.height) } // mocks are made before the stubbing starts
        val proximity = Mockito.mock(ProximityInfo::class.java)
        Mockito.mock(Keyboard::class.java).also {
            Mockito.`when`(it.sortedKeys).thenReturn(keys)
            Mockito.`when`(it.proximityInfo).thenReturn(proximity)
        }
    }

    private fun pointers(word: String): InputPointers {
        val g = GlideTestKit.idealGesture(word, qwertyKeys)
        return InputPointers(g.count).also { p -> for (i in 0 until g.count) p.addPointer(g.xs[i].toInt(), g.ys[i].toInt(), 0, i * 10) }
    }

    /** The words of a glide over [word] in a field that [personalized] says may or may not use the user words. */
    fun glide(word: String, personalized: Boolean): List<String> = facilitator.getSuggestionResults(
        ComposedData(pointers(word), true, ""), NgramContext.getEmptyPrevWordsContext(0), keyboard,
        SettingsValuesForSuggestion(false, false, personalized), 0, SuggestedWords.INPUT_STYLE_TAIL_BATCH
    ).map { it.word }

    /** The words of typing [typed] (the tap path through every dictionary). */
    fun tap(typed: String, personalized: Boolean): List<String> = facilitator.getSuggestionResults(
        ComposedData.createForWord(typed), NgramContext.getEmptyPrevWordsContext(0), keyboard,
        SettingsValuesForSuggestion(false, false, personalized), 0, SuggestedWords.INPUT_STYLE_TYPING
    ).map { it.word }

    /** The first glide of a main dictionary only starts reading its word list: do it before the test looks at results. */
    fun warmUp() { glide("world", true); glide("world", true) }
}
