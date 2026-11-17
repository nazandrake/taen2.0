// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.graphics.Rect
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GlideSuggester between the keyboard and the engine: key geometry from a real Keyboard's keys, touch points from
 * InputPointers, the word list from Dictionary.forEachWord, results as SuggestedWordInfo. The keyboard and its
 * keys are mocks built from the layout geometry of the glide tests (the parser needs the native library).
 */
@RunWith(RobolectricTestRunner::class)
class GlideSuggesterTest {
    private class FakeDictionary(private val words: List<Pair<String, Int>>, private val offensive: Set<String> = emptySet()) :
        Dictionary(TYPE_MAIN, Locale.ENGLISH) {
        var reads = 0
        /** false: the read hands over the words but says it was cut short (lock timeout, damaged dictionary) */
        var complete = true
        override fun forEachWord(listener: WordListener): Boolean { reads++; words.forEach { listener.onWord(it.first, it.second) }; return complete }
        override fun isPossiblyOffensive(word: String) = word in offensive
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = words.any { it.first == word }
    }

    private fun key(code: Int, x: Float, y: Float, w: Float, h: Float): Key {
        val key = Mockito.mock(Key::class.java)
        Mockito.`when`(key.code).thenReturn(code)
        // the hit box is the key plus its gaps: 2 px more on each side
        Mockito.`when`(key.hitBox).thenReturn(Rect((x - w / 2 - 2).toInt(), (y - h / 2 - 2).toInt(), (x + w / 2 + 2).toInt(), (y + h / 2 + 2).toInt()))
        return key
    }

    private fun keyboard(layout: String, extra: List<Key> = emptyList()): Keyboard {
        val keys = GlideTestKit.keys(layout).map { key(it.code, it.x, it.y, it.width, it.height) } + extra
        val kb = Mockito.mock(Keyboard::class.java)
        Mockito.`when`(kb.sortedKeys).thenReturn(keys)
        return kb
    }

    private fun pointers(g: GlideTestKit.Gesture): InputPointers {
        val p = InputPointers(g.count)
        for (i in 0 until g.count) p.addPointer(g.xs[i].toInt(), g.ys[i].toInt(), 0, i * 10)
        return p
    }

    @Before @After fun forgetUserWords() = GlideSuggester.forgetUserWords()

    private val sync = GlideSuggester(background = { it() })
    private val dict = FakeDictionary(listOf("hello" to 200, "hullo" to 200, "hallo" to 100, "world" to 200, "word" to 200))
    private val qwertyKeys = GlideTestKit.keys("qwerty")

    @Test
    fun theFirstGestureStartsReadingTheWordListAndTheNextOneUsesIt() {
        val kb = keyboard("qwerty")
        val g = pointers(GlideTestKit.idealGesture("world", qwertyKeys))
        // a list that is read in the background (not yet there): nothing, and the read is started once
        val queue = ArrayList<() -> Unit>()
        val lazy = GlideSuggester(background = { queue.add(it) })
        assertTrue(lazy.suggest(dict, kb, g, 1f, false).isEmpty())
        assertTrue(lazy.suggest(dict, kb, g, 1f, false).isEmpty())
        assertEquals(1, queue.size)
        queue[0]()
        assertEquals("world", lazy.suggest(dict, kb, g, 1f, false).first().word)
        assertEquals(1, dict.reads)
    }

    @Test
    fun suggestionsAreCorrectionsOfTheDictionaryBestFirstWithTheLocaleWeight() {
        sync.warm(dict)
        val kb = keyboard("qwerty")
        val g = pointers(GlideTestKit.idealGesture("hello", qwertyKeys))
        val full = sync.suggest(dict, kb, g, 1f, false)
        val half = sync.suggest(dict, kb, g, 0.5f, false)
        assertEquals("hello", full.first().word)
        // "hullo" is a much shorter path than the finger drew: left out by the path length band
        assertEquals(listOf("hello", "hallo"), full.map { it.word })
        assertTrue(full.zipWithNext().all { (a, b) -> a.mScore >= b.mScore })
        assertEquals(SuggestedWordInfo.KIND_CORRECTION, full.first().kind)
        assertEquals(dict, full.first().mSourceDict)
        assertEquals(full.first().mScore / 2.0, half.first().mScore.toDouble(), 1.0)
        assertTrue(full.size <= GlideSuggester.MAX_SUGGESTIONS)
    }

    @Test
    fun possiblyOffensiveWordsAreDroppedOnlyWhenTheSettingAsksForIt() {
        val rude = FakeDictionary(listOf("hello" to 250, "hullo" to 100, "hallo" to 100), offensive = setOf("hello"))
        sync.warm(rude)
        val g = pointers(GlideTestKit.idealGesture("hello", qwertyKeys))
        assertEquals("hello", sync.suggest(rude, keyboard("qwerty"), g, 1f, false).first().word) // positive control
        val blocked = sync.suggest(rude, keyboard("qwerty"), g, 1f, true).map { it.word }
        assertTrue("hello" !in blocked && blocked.isNotEmpty(), blocked.toString())
    }

    @Test
    fun onlyLetterKeysAreUsedAndABoardWithoutLettersGivesNothing() {
        sync.warm(dict)
        val g = pointers(GlideTestKit.idealGesture("world", qwertyKeys))
        // shift, delete, a digit and the space bar are on every real keyboard: they are no letter keys
        val functional = listOf(key(-1, 50f, 400f, 100f, 100f), key(-7, 1000f, 400f, 100f, 100f), key('1'.code, 500f, 560f, 100f, 100f), key(' '.code, 500f, 700f, 400f, 100f))
        assertEquals("world", sync.suggest(dict, keyboard("qwerty", functional), g, 1f, false).first().word)
        // three non-letter keys sit on the start of the gesture, closer to it than the w key (20 px off): they must not take its place
        val w = qwertyKeys.first { it.code == 'w'.code }
        val shifted = GlideTestKit.idealGesture("world", qwertyKeys).let { GlideTestKit.Gesture(FloatArray(it.count) { i -> it.xs[i] + 20f }, it.ys) }
        val onStart = listOf(key('1'.code, w.x + 20f, w.y + 1f, 100f, 100f), key(-1, w.x + 21f, w.y, 100f, 100f), key(' '.code, w.x + 19f, w.y, 100f, 100f))
        assertEquals("world", sync.suggest(dict, keyboard("qwerty", onStart), pointers(shifted), 1f, false).first().word)
        val digits = Mockito.mock(Keyboard::class.java)
        val digitKeys = listOf(key('1'.code, 50f, 50f, 100f, 100f), key('2'.code, 150f, 50f, 100f, 100f))
        Mockito.`when`(digits.sortedKeys).thenReturn(digitKeys)
        assertTrue(sync.suggest(dict, digits, g, 1f, false).isEmpty())
    }

    @Test
    fun aBulgarianBoardGlidesBulgarianWords() {
        val bg = FakeDictionary(listOf("вода" to 200, "вили" to 200, "светло" to 200))
        sync.warm(bg)
        val keys = GlideTestKit.keys("bulgarian")
        val kb = keyboard("bulgarian")
        assertEquals("вода", sync.suggest(bg, kb, pointers(GlideTestKit.idealGesture("вода", keys)), 1f, false).first().word)
        assertEquals("светло", sync.suggest(bg, kb, pointers(GlideTestKit.idealGesture("светло", keys)), 1f, false).first().word)
    }

    @Test
    fun aTapGivesNoSuggestions() {
        sync.warm(dict)
        val tap = InputPointers(2).apply { addPointer(500, 70, 0, 0); addPointer(502, 71, 0, 20) }
        assertTrue(sync.suggest(dict, keyboard("qwerty"), tap, 1f, false).isEmpty())
    }

    // ---- learned words and the personal dictionary join the glide word list (memory only) -----------------------

    /** The user history or personal dictionary: a content version that changes with every edit, like ExpandableBinaryDictionary. */
    private class FakeUserDictionary(type: String, var words: List<Pair<String, Int>>) : Dictionary(type, Locale.ENGLISH) {
        var reads = 0
        var version = 1L
        var complete = true
        override fun contentVersion() = version
        override fun forEachWord(listener: WordListener): Boolean { reads++; words.forEach { listener.onWord(it.first, it.second) }; return complete }
        override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
        override fun isInDictionary(word: String) = words.any { it.first == word }
    }

    private fun glide(suggester: GlideSuggester, word: String, users: List<Dictionary>, main: Dictionary = dict, weight: Float = 1f, block: Boolean = false) =
        suggester.suggest(main, keyboard("qwerty"), pointers(GlideTestKit.idealGesture(word, qwertyKeys)), weight, block, users).map { it.word }

    @Test
    fun learnedAndPersonalWordsAreSuggestedThoughTheyAreNotInTheMainList() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        val personal = FakeUserDictionary(Dictionary.TYPE_USER, listOf("quibble" to 200))
        assertTrue("zork" !in glide(sync, "zork", emptyList()), "positive control: not in the main list") // nothing to find
        assertEquals("zork", glide(sync, "zork", listOf(history, personal)).first())
        assertEquals("quibble", glide(sync, "quibble", listOf(history, personal)).first())
        assertEquals("world", glide(sync, "world", listOf(history, personal)).first()) // the main list still works next to them
    }

    @Test
    fun aWordInTheMainListAndInTheUserListIsSuggestedOnce() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("world" to 255, "hello" to 10))
        val words = glide(sync, "world", listOf(history))
        assertEquals(1, words.count { it == "world" }, words.toString())
        assertEquals("world", words.first())
    }

    @Test
    fun theUserListIsReadAgainOnlyWhenTheDictionaryChanged() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        glide(sync, "zork", listOf(history))
        glide(sync, "zork", listOf(history)) // many suggestion calls per gesture: no new read
        assertEquals(1, history.reads)
        history.words = listOf("zork" to 120, "plonk" to 120)
        assertTrue("plonk" !in glide(sync, "plonk", listOf(history)), "not read before the dictionary says it changed")
        history.version++
        assertEquals("plonk", glide(sync, "plonk", listOf(history)).first())
        assertEquals(2, history.reads)
        // a word the user deleted (the dictionary changed again) is gone from the list
        history.words = listOf("plonk" to 120)
        history.version++
        assertTrue("zork" !in glide(sync, "zork", listOf(history)))
    }

    @Test
    fun forgettingTheUserWordsEmptiesTheCopyInMemory() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120, "plonk" to 90))
        glide(sync, "zork", listOf(history))
        assertEquals(2, GlideSuggester.userWordCount())
        GlideSuggester.forgetUserWords() // the screen lock hook
        assertEquals(0, GlideSuggester.userWordCount())
        // the dictionary is the owner: once it is empty (evicted at the lock), nothing comes back
        history.words = emptyList()
        history.version++
        assertTrue("zork" !in glide(sync, "zork", listOf(history)))
        assertEquals(0, GlideSuggester.userWordCount())
    }

    @Test
    fun userWordsCarryTheLocaleWeightAndTheOffensiveSetting() {
        val rude = FakeDictionary(listOf("world" to 200), offensive = setOf("zork"))
        sync.warm(rude)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 200))
        val full = suggestionsOf("zork", listOf(history), rude, 1f)
        val half = suggestionsOf("zork", listOf(history), rude, 0.5f)
        assertEquals("zork", full.first().word)
        assertEquals(full.first().mScore / 2.0, half.first().mScore.toDouble(), 1.0)
        assertTrue(glide(sync, "zork", listOf(history), main = rude, block = true).none { it == "zork" })
    }

    private fun suggestionsOf(word: String, users: List<Dictionary>, main: Dictionary, weight: Float) =
        sync.suggest(main, keyboard("qwerty"), pointers(GlideTestKit.idealGesture(word, qwertyKeys)), weight, false, users)

    @Test
    fun anUnavailableUserDictionaryIsSkippedNotFatal() {
        sync.warm(dict)
        val broken = object : Dictionary(TYPE_USER, Locale.ENGLISH) {
            override fun forEachWord(listener: WordListener): Boolean { throw IllegalStateException("closed") }
            override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
            override fun isInDictionary(word: String) = false
        }
        assertEquals("world", glide(sync, "world", listOf(broken)).first())
    }

    @Test
    fun theScreenLockEmptiesTheCopiesOfLearnedAndPersonalWordsThroughTheFacilitatorsHook() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // class initialisation of the facilitator registers the lock hook (production wiring: no test registration)
        Class.forName("helium314.keyboard.latin.DictionaryFacilitatorImpl", true, javaClass.classLoader)
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        Privacy.unlock(ctx)
        try {
            glide(sync, "zork", listOf(history))
            assertEquals(1, GlideSuggester.userWordCount()) // positive control: the copy exists before the lock
            Privacy.lock(ctx)
            assertEquals(0, GlideSuggester.userWordCount())
        } finally {
            Privacy.unlock(ctx)
        }
    }

    @Test
    fun aLockDuringTheReadOfAUserDictionaryLeavesNoCopyBehind() {
        sync.warm(dict)
        val racing = object : Dictionary(TYPE_USER, Locale.ENGLISH) {
            override fun contentVersion() = 1L
            override fun forEachWord(listener: WordListener): Boolean {
                listener.onWord("zork", 120)
                GlideSuggester.forgetUserWords() // the screen lock arrives while the list is being read
                return true
            }
            override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
            override fun isInDictionary(word: String) = true
        }
        glide(sync, "zork", listOf(racing))
        assertEquals(0, GlideSuggester.userWordCount()) // the read that began before the lock must not put its copy back
        // positive control: without a lock during the read the copy is kept
        val calm = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        glide(sync, "zork", listOf(calm))
        assertEquals(1, GlideSuggester.userWordCount())
    }

    // ---- a lock between the authorization of the user words and their scoring --------------------------

    private fun glideAllowing(word: String, users: List<Dictionary>, allowed: () -> Boolean) =
        sync.suggest(dict, keyboard("qwerty"), pointers(GlideTestKit.idealGesture(word, qwertyKeys)), 1f, false, users, allowed).map { it.word }

    @Test
    fun userWordsAreDroppedWhenTheLockStateChangesBetweenTheAuthorizationAndTheScoring() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        assertEquals("zork", glideAllowing("zork", listOf(history)) { true }.first()) // positive control: the copy is cached, nothing changes
        var asked = 0
        // the facilitator said yes once (the authorization); the screen locks while the cached copy is scored: the next look says no
        val words = glideAllowing("zork", listOf(history)) { ++asked == 1 }
        assertTrue("zork" !in words, words.toString())
        assertTrue(asked >= 2, "the state is looked at again before the result is returned")
        assertEquals("world", glideAllowing("world", listOf(history)) { ++asked == 1 }.first()) // the main list is not touched by the drop
    }

    @Test
    fun userWordsAreDroppedWhenTheLockClearedTheCopiesWhileACachedListWasInUse() {
        sync.warm(dict)
        var lockInBetween = false
        val history = object : Dictionary(TYPE_USER_HISTORY, Locale.ENGLISH) {
            override fun contentVersion(): Long {
                if (lockInBetween) GlideSuggester.forgetUserWords() // lock hook: after the call began, before the lists are scored
                return 1L
            }
            override fun forEachWord(listener: WordListener): Boolean { listener.onWord("zork", 120); return true }
            override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo>? = null
            override fun isInDictionary(word: String) = true
        }
        assertEquals("zork", glide(sync, "zork", listOf(history)).first()) // positive control: read and cached
        lockInBetween = true
        val words = glide(sync, "zork", listOf(history))
        assertTrue("zork" !in words, words.toString())
        lockInBetween = false
        GlideSuggester.forgetUserWords()
        assertEquals("zork", glide(sync, "zork", listOf(history)).first()) // positive control: later calls are not affected
    }

    @Test
    fun userWordsAreNotUsedWhenTheStateIsAlreadyLockedAtTheStart() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        val words = glideAllowing("zork", listOf(history)) { false }
        assertTrue("zork" !in words, words.toString())
        assertEquals(0, history.reads) // not even read
        assertEquals(0, GlideSuggester.userWordCount())
    }

    // ---- a read that was cut short is never kept as the current word list -------------------------------

    @Test
    fun aCutShortReadOfAUserDictionaryIsNeitherUsedNorKept() {
        sync.warm(dict)
        val history = FakeUserDictionary(Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120))
        history.complete = false // e.g. the lock timed out: the dictionary handed over what it had and said so
        assertTrue("zork" !in glide(sync, "zork", listOf(history)), "a list that may be short is not used")
        assertEquals(0, GlideSuggester.userWordCount())
        history.complete = true // same version: only the missing copy makes the next call read again
        assertEquals("zork", glide(sync, "zork", listOf(history)).first())
        assertEquals(2, history.reads)
        assertEquals(1, GlideSuggester.userWordCount())
    }

    @Test
    fun aCutShortReadOfTheMainDictionaryIsReadAgainByTheNextGesture() {
        val flaky = FakeDictionary(listOf("hello" to 200, "world" to 200, "word" to 200))
        flaky.complete = false
        val g = pointers(GlideTestKit.idealGesture("world", qwertyKeys))
        assertTrue(sync.suggest(flaky, keyboard("qwerty"), g, 1f, false).isEmpty()) // first gesture: the read starts (and fails)
        assertTrue(sync.suggest(flaky, keyboard("qwerty"), g, 1f, false).isEmpty()) // nothing cached: reads again, still failing
        assertEquals(2, flaky.reads)
        flaky.complete = true
        assertTrue(sync.suggest(flaky, keyboard("qwerty"), g, 1f, false).isEmpty()) // this call starts the read that now works
        assertEquals("world", sync.suggest(flaky, keyboard("qwerty"), g, 1f, false).first().word)
        assertEquals(3, flaky.reads)
    }
}
