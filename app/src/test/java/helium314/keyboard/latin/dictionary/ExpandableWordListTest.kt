// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * the word list that glide typing reads from a learned-word or personal dictionary must
 * be empty while the dictionary is not the truth: pending recreation (the personal dictionary was wiped at the screen lock but the
 * native copy still holds the words), storage closed (locked), not loaded. The native dictionary is a mock that hands out words
 * whenever it is asked, so that a word that comes out is the dictionary's decision.
 */
@RunWith(RobolectricTestRunner::class)
class ExpandableWordListTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class Probe(ctx: Context, type: String, private val storageBacked: Boolean = false) :
        ExpandableBinaryDictionary(ctx, "probe-$type", Locale.ENGLISH, type, null) {
        var storageAvailable = true
        override fun loadInitialContentsLocked() {}
        override fun usesStorage() = storageBacked
        override fun isStorageAvailable() = storageAvailable
        fun pendRecreate() = setNeedsToRecreate()
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = false
    }

    private var nativeReads = 0

    /** A probe whose native dictionary (a mock) holds "zork" and "quibble". */
    private fun loaded(type: String, usesStorage: Boolean = false): Probe {
        val probe = Probe(ctx, type, usesStorage)
        val native = Mockito.mock(BinaryDictionary::class.java)
        Mockito.doAnswer { call ->
            nativeReads++
            val listener = call.getArgument<Dictionary.WordListener>(0)
            listener.onWord("zork", 120)
            listener.onWord("quibble", 200)
            true
        }.`when`(native).forEachWord(Mockito.any())
        val field = ExpandableBinaryDictionary::class.java.getDeclaredField("mBinaryDictionary")
        field.isAccessible = true
        field.set(probe, native)
        return probe
    }

    private fun words(dictionary: Dictionary): Pair<Boolean, List<String>> {
        val out = ArrayList<String>()
        val complete = dictionary.forEachWord { word, _ -> out.add(word) }
        return complete to out
    }

    @Test fun aLoadedDictionaryHandsOverItsWordsAndSaysItIsComplete() {
        assertEquals(true to listOf("zork", "quibble"), words(loaded(Dictionary.TYPE_USER)))
    }

    @Test fun aDictionaryThatIsNotLoadedGivesNothingAndIsIncomplete() {
        assertEquals(false to emptyList(), words(Probe(ctx, Dictionary.TYPE_USER)))
    }

    @Test fun aDictionaryWaitingToBeRecreatedGivesNothingEvenThoughTheNativeCopyStillHoldsWords() {
        val personal = loaded(Dictionary.TYPE_USER)
        assertEquals(2, words(personal).second.size) // positive control: the native copy has words
        val before = nativeReads
        personal.pendRecreate() // what the screen lock does to the personal dictionary (PersonalDictionary.evict -> listener)
        assertEquals(false to emptyList(), words(personal))
        assertEquals(before, nativeReads) // the native copy was not even asked
    }

    @Test fun markingADictionaryForRecreationChangesItsContentVersion() {
        // a copy made earlier (glide) must be read again, not served from the old version while the recreation is pending
        val personal = loaded(Dictionary.TYPE_USER)
        val version = personal.contentVersion()
        personal.pendRecreate()
        assertNotEquals(version, personal.contentVersion())
    }

    @Test fun aDictionaryWhoseStorageIsClosedGivesNothing() {
        val history = loaded(Dictionary.TYPE_USER_HISTORY, usesStorage = true)
        assertEquals(true to listOf("zork", "quibble"), words(history)) // positive control: storage open
        history.storageAvailable = false // the screen lock: nothing is loaded, saved or learned, and nothing is listed
        assertEquals(false to emptyList(), words(history))
        history.storageAvailable = true
        assertEquals(true to listOf("zork", "quibble"), words(history))
    }
}
