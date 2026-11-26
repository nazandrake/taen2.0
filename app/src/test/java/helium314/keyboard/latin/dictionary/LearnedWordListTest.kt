// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The learned-words list of the settings reads every word of a learned-word dictionary with its use count, and a word removed there is
 * gone from the dictionary and saved at once. The native dictionary is a mock that hands out its words through the native word walk, so
 * what comes out is the decision of the dictionary class.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedWordListTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class Probe(ctx: Context, private val storageBacked: Boolean = false) : ExpandableBinaryDictionary(ctx, "probe", Locale.ENGLISH, Dictionary.TYPE_USER_HISTORY, null) {
        var storageAvailable = true
        val saved = ArrayList<ByteArray>()
        override fun loadInitialContentsLocked() {}
        override fun usesStorage() = storageBacked
        override fun isStorageAvailable() = storageAvailable
        override fun writeStorage(bytes: ByteArray): Boolean { saved.add(bytes); return true }
        fun pendRecreate() = setNeedsToRecreate()
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = false
    }

    private fun property(word: String, count: Int) = WordProperty(word, ProbabilityInfo(100, 0, 1, count), null, null, false, false)

    /** A probe whose native dictionary (a mock) holds [words] in this order. */
    private fun loaded(vararg words: Pair<String, Int>, storage: Boolean = false): Pair<Probe, BinaryDictionary> {
        val probe = Probe(ctx, storage)
        val native = Mockito.mock(BinaryDictionary::class.java)
        Mockito.doAnswer { call ->
            val token = call.getArgument<Int>(0)
            val (word, count) = words[token]
            BinaryDictionary.GetNextWordPropertyResult(property(word, count), if (token + 1 < words.size) token + 1 else 0)
        }.`when`(native).getNextWordProperty(Mockito.anyInt())
        val field = ExpandableBinaryDictionary::class.java.getDeclaredField("mBinaryDictionary")
        field.isAccessible = true
        field.set(probe, native)
        if (storage) ExpandableBinaryDictionary::class.java.getDeclaredField("mStorageReady").apply { isAccessible = true }.set(probe, true) // loaded from the store
        return probe to native
    }

    @Test fun everyWordComesWithItsCount() {
        val (probe, _) = loaded("zork" to 3, "quibble" to 1)
        assertEquals(listOf(ListedWord("zork", 3), ListedWord("quibble", 1)), probe.listWords(2_000))
    }

    // ---- pass 2 ----

    @Test fun aClosedStorageListsNothingAndDoesNotAskTheNativeDictionary() {
        val (probe, native) = loaded("zork" to 3, storage = true)
        assertEquals(listOf(ListedWord("zork", 3)), probe.listWords(2_000)) // positive control: open
        probe.storageAvailable = false // the screen lock or an import
        assertNull(probe.listWords(500))
        Mockito.verify(native, Mockito.times(1)).getNextWordProperty(0) // only the open read above reached the native walk
    }

    @Test fun aDictionaryWaitingToBeRecreatedListsNothing() {
        val (probe, _) = loaded("zork" to 3)
        probe.pendRecreate()
        assertNull(probe.listWords(300))
    }

    @Test fun theSentenceStartAndEmptyEntriesAreNoWords() {
        val probe = Probe(ctx)
        val native = Mockito.mock(BinaryDictionary::class.java)
        val bos = WordProperty(intArrayOf('x'.code), false, false, false, false, true, intArrayOf(0, 0, 0, 0), ArrayList(), ArrayList(), ArrayList(), ArrayList(), ArrayList(), ArrayList())
        Mockito.doAnswer { call ->
            when (call.getArgument<Int>(0)) {
                0 -> BinaryDictionary.GetNextWordPropertyResult(bos, 1)
                1 -> BinaryDictionary.GetNextWordPropertyResult(property("", 0), 2)
                else -> BinaryDictionary.GetNextWordPropertyResult(property("quibble", 2), 0)
            }
        }.`when`(native).getNextWordProperty(Mockito.anyInt())
        ExpandableBinaryDictionary::class.java.getDeclaredField("mBinaryDictionary").apply { isAccessible = true }.set(probe, native)
        assertEquals(listOf(ListedWord("quibble", 2)), probe.listWords(2_000))
    }

    @Test fun aWordWalkThatDoesNotEndIsNotTrusted() {
        val probe = Probe(ctx)
        val native = Mockito.mock(BinaryDictionary::class.java)
        Mockito.doAnswer { BinaryDictionary.GetNextWordPropertyResult(property("loop", 1), 1) }.`when`(native).getNextWordProperty(Mockito.anyInt())
        ExpandableBinaryDictionary::class.java.getDeclaredField("mBinaryDictionary").apply { isAccessible = true }.set(probe, native)
        assertNull(probe.listWords(5_000))
    }

    @Test fun aRemovedWordIsTakenOutOfTheDictionaryAndAFailedSaveIsReported() {
        val (probe, native) = loaded("zork" to 3, "quibble" to 1, storage = true)
        assertFalse(probe.removeWordAndSave("zork", 2_000)) // the mock cannot serialize: the save failed and says so
        Mockito.verify(native).removeUnigramEntry("zork")
        Mockito.verify(native, Mockito.never()).removeUnigramEntry("quibble")
        assertEquals(0, probe.saved.size)
    }

    @Test fun nothingIsRemovedWhileTheStorageIsClosed() {
        val (probe, native) = loaded("zork" to 3, storage = true)
        probe.storageAvailable = false
        assertFalse(probe.removeWordAndSave("zork", 300))
        Mockito.verify(native, Mockito.never()).removeUnigramEntry(Mockito.anyString())
        probe.storageAvailable = true // positive control: the same call reaches the native dictionary
        probe.removeWordAndSave("zork", 2_000)
        Mockito.verify(native).removeUnigramEntry("zork")
    }
}
