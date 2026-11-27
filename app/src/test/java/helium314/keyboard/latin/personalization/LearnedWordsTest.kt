// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.dictionary.ListedWord
import helium314.keyboard.latin.privacy.PersonalWord
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.filesContaining
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings > Privacy > Learned words and Forget learned data, through the production accessors of the encrypted stores: forgetting removes
 * the learned words and the removed-word list of every language and nothing else; a word removed from the list goes to the removed words,
 * from where it can be restored.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedWordsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val storeDir get() = File(ctx.noBackupFilesDir, "taen")

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState(); LearnedWords.historyForTest = { history } }
    @After fun after() { LearnedWords.historyForTest = null; Privacy.unlock(ctx); Privacy.forgetCachedState() }

    /** The learned-word dictionary of a language as the screen sees it (the native engine is not in the unit tests). */
    private class FakeHistory(ctx: Context) : ExpandableBinaryDictionary(ctx, "fake", Locale.ENGLISH, Dictionary.TYPE_USER_HISTORY, null) {
        var words: List<ListedWord>? = listOf(ListedWord("zork", 3), ListedWord("Apple", 1), ListedWord("apple", 2))
        var saves = true
        val removed = ArrayList<String>()
        override fun loadInitialContentsLocked() {}
        override fun listWords(timeoutMillis: Long) = words
        override fun removeWordAndSave(word: String, timeoutMillis: Long): Boolean { removed.add(word); return saves }
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = false
    }

    private val history = FakeHistory(ctx)

    private fun learnedFiles() = storeDir.list().orEmpty().filter { it.startsWith("learned_") || it.startsWith("blacklist_") }.sorted()

    @Test fun forgettingRemovesTheLearnedAndRemovedWordsOfEveryLanguageAndKeepsThePersonalDictionary() {
        assertTrue(Privacy.learnedStorage(ctx, "en-US", null).save(ByteArray(40) { 7 }))
        assertTrue(Privacy.learnedStorage(ctx, "bg", null).save(ByteArray(40) { 8 }))
        assertTrue(Privacy.removedWords(ctx, "en-US").add("zork"))
        assertTrue(assertNotNull(Privacy.personalDictionary(ctx)).add(PersonalWord("Taen", null, 250, "en")))
        // positive control: the stores exist before
        assertEquals(listOf("blacklist_en-US.enc", "learned_bg.enc", "learned_en-US.enc"), learnedFiles())

        assertTrue(LearnedWords.forgetAll(ctx))
        assertEquals(emptyList(), learnedFiles())
        assertEquals(emptySet(), Privacy.removedWords(ctx, "en-US").all())
        assertEquals(listOf("Taen"), assertNotNull(Privacy.personalDictionary(ctx)).all().map { it.word })
    }

    // ---- pass 2 ----

    private fun legacyBlacklist(tag: String, vararg words: String) =
        File(ctx.filesDir, "blacklists/$tag.txt").apply { parentFile!!.mkdirs(); writeText(words.joinToString("\n") + "\n") }

    @Test fun whileLockedNothingIsListedRemovedRestoredOrDeleted() {
        assertTrue(Privacy.learnedStorage(ctx, "en-US", null).save(ByteArray(40) { 7 }))
        assertTrue(Privacy.removedWords(ctx, "en-US").add("zork"))
        Privacy.lock(ctx)
        assertNull(LearnedWords.languages(ctx))
        assertNull(LearnedWords.learned(ctx, "en-US"))
        assertNull(LearnedWords.removed(ctx, "en-US"))
        assertFalse(LearnedWords.remove(ctx, "en-US", "zork"))
        assertFalse(LearnedWords.restore(ctx, "en-US", "zork"))
        assertFalse(LearnedWords.forgetAll(ctx))
        assertEquals(emptyList(), history.removed)
        Privacy.unlock(ctx)
        assertEquals(listOf("blacklist_en-US.enc", "learned_en-US.enc"), learnedFiles()) // nothing was deleted
        assertEquals(listOf("zork"), LearnedWords.removed(ctx, "en-US")) // positive control: the same calls work after the unlock
        assertEquals(listOf("en-US"), LearnedWords.languages(ctx))
    }

    @Test fun theLanguagesAreThoseWithLearnedOrRemovedWords() {
        assertTrue(Privacy.learnedStorage(ctx, "en-US", null).save(ByteArray(40) { 7 }))
        assertTrue(Privacy.removedWords(ctx, "bg").add("Жщ"))
        legacyBlacklist("de", "Quatsch")
        assertEquals(listOf("bg", "de", "en-US"), LearnedWords.languages(ctx))
    }

    @Test fun theLearnedWordsAreSortedAndNothingWhenTheDictionaryCannotBeRead() {
        assertEquals(listOf("Apple", "apple", "zork"), LearnedWords.learned(ctx, "en-US")!!.map { it.word })
        history.words = null
        assertNull(LearnedWords.learned(ctx, "en-US"))
    }

    @Test fun aRemovedWordGoesToTheRemovedWordsAndOutOfTheLearnedWordsAndCanBeRestored() {
        assertTrue(LearnedWords.remove(ctx, "en-US", "zork"))
        assertEquals(listOf("zork"), history.removed)
        assertEquals(listOf("zork"), LearnedWords.removed(ctx, "en-US"))
        assertTrue(Privacy.removedWords(ctx, "en-US").contains("zork")) // not suggested any more
        assertTrue(LearnedWords.restore(ctx, "en-US", "zork"))
        assertEquals(emptyList(), LearnedWords.removed(ctx, "en-US"))
        assertFalse(Privacy.removedWords(ctx, "en-US").contains("zork"))
    }

    @Test fun aRemovalWhoseSaveFailsIsReported() {
        history.saves = false
        assertFalse(LearnedWords.remove(ctx, "en-US", "zork"))
        assertEquals(listOf("zork"), history.removed) // it was tried
        assertEquals(listOf("zork"), LearnedWords.removed(ctx, "en-US")) // and stays hidden from the suggestions (the restrictive side)
    }

    @Test fun forgettingAlsoDeletesThePlainFilesOfEarlierVersions() {
        val blacklist = legacyBlacklist("en-US", "zork")
        val history = File(ctx.filesDir, "UserHistoryDictionary.en-US.dict").apply { mkdirs(); File(this, "x.body").writeText("zork") }
        assertTrue(LearnedWords.forgetAll(ctx))
        assertFalse(blacklist.exists())
        assertFalse(history.exists())
        assertEquals(emptyList(), learnedFiles())
    }

    @Test fun thePlainRemovedWordsOfAnEarlierVersionAreMovedInAtTheUnlockAndNotBefore() {
        val canary = "TAENcanary-Qx93"
        val plain = legacyBlacklist("en-US", canary, "Жщ")
        Privacy.lock(ctx)
        assertNull(Privacy.removedWords(ctx, "en-US").all())
        assertTrue(plain.isFile) // untouched while locked
        assertEquals(listOf("en-US.txt"), plain.parentFile!!.list()!!.toList())
        Privacy.unlock(ctx)
        assertEquals(setOf(canary, "Жщ"), Privacy.removedWords(ctx, "en-US").all())
        assertFalse(plain.exists())
        assertEquals(emptyList(), filesContaining(ctx.dataDir, canary)) // only ciphertext is left
        assertEquals(listOf("blacklist_en-US.enc"), learnedFiles())
    }
}
