// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import com.android.inputmethod.keyboard.ProximityInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The warm-up list of D15: which languages are loaded, what is handed over, what is closed, with fake dictionaries that count their closes. */
class WarmDictionariesTest {
    private val en = Locale.US
    private val bg = Locale("bg")
    private val de = Locale.GERMAN
    private val fr = Locale.FRENCH

    private class Fake(locale: Locale) : Dictionary(TYPE_MAIN, locale) {
        var closed = 0
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWords.SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = false
        override fun close() { closed++ }
    }

    private val loaded = ArrayList<Locale>()
    private val made = HashMap<Locale, Fake>()
    private fun load(locale: Locale): Dictionary { loaded.add(locale); return Fake(locale).also { made[locale] = it } }

    @Test fun warmsOnlyTheLanguagesThatAreNotInUse() {
        val warm = WarmDictionaries()
        warm.warm(listOf(en, bg), inUse = listOf(en), load = ::load)
        assertEquals(listOf(bg), loaded)
        assertEquals(listOf(bg), warm.locales())
    }

    @Test fun takeHandsOverOwnershipOnceAndNothingIsClosed() {
        val warm = WarmDictionaries()
        warm.warm(listOf(bg), inUse = listOf(en), load = ::load)
        val taken = warm.take(bg)
        assertSame(made[bg], taken)
        assertNull(warm.take(bg)) // gone: it belongs to the caller now
        warm.clear()
        assertEquals(0, made[bg]!!.closed)
    }

    @Test fun clearClosesWhatWasNotTaken() {
        val warm = WarmDictionaries()
        warm.warm(listOf(bg, de), inUse = listOf(en), load = ::load)
        warm.clear()
        assertEquals(listOf(1, 1), listOf(bg, de).map { made[it]!!.closed })
        assertEquals(emptyList(), warm.locales())
    }

    @Test fun aLanguageThatIsWarmAlreadyIsNotLoadedAgain() {
        val warm = WarmDictionaries()
        warm.warm(listOf(bg), inUse = listOf(en), load = ::load)
        warm.warm(listOf(bg), inUse = listOf(en), load = ::load)
        assertEquals(listOf(bg), loaded)
    }

    @Test fun theNumberOfWarmDictionariesIsBounded() {
        val warm = WarmDictionaries(maxEntries = 2)
        warm.warm(listOf(bg, de, fr), inUse = listOf(en), load = ::load)
        assertEquals(listOf(bg, de), warm.locales())
        assertEquals(listOf(bg, de), loaded) // the third was never loaded
        warm.take(bg)
        warm.warm(listOf(fr), inUse = listOf(en), load = ::load) // room again
        assertEquals(listOf(de, fr), warm.locales())
    }

    @Test fun aDictionaryThatArrivesAfterAClearIsClosedNotKept() {
        val warm = WarmDictionaries()
        warm.warm(listOf(bg), inUse = listOf(en), load = { locale ->
            warm.clear() // the files changed while the slow load was running
            load(locale)
        })
        assertEquals(emptyList(), warm.locales())
        assertEquals(1, made[bg]!!.closed)
    }

    @Test fun twoLoadsOfTheSameLanguageKeepOneAndCloseTheOther() {
        val warm = WarmDictionaries()
        warm.warm(listOf(bg), inUse = listOf(en), load = { locale ->
            warm.warm(listOf(bg), inUse = listOf(en), load = ::load) // another thread warmed it meanwhile
            Fake(locale).also { made[Locale("bg-second")] = it }
        })
        assertEquals(listOf(bg), warm.locales())
        assertEquals(1, made[Locale("bg-second")]!!.closed) // the late one lost
        assertEquals(0, made[bg]!!.closed)
    }

    @Test fun readyCallbackSeesEachKeptDictionaryOnly() {
        val warm = WarmDictionaries(maxEntries = 1)
        val seen = ArrayList<Locale>()
        warm.warm(listOf(bg, de), inUse = listOf(en), load = ::load, onReady = { seen.add(it.mLocale) })
        assertEquals(listOf(bg), seen)
        assertTrue(warm.locales().contains(bg))
    }
}
