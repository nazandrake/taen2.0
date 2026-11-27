// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.gesture.GlideFacilitatorKit
import helium314.keyboard.latin.gesture.GlideSuggester
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the learned words and the personal dictionary are a source of suggestions and of
 * glide candidates only where PrivacyPolicy allows it (not in an incognito field) and never while the tier is locked. The
 * real DictionaryFacilitatorImpl runs over fake user dictionaries that, like the native ones, keep their words until they
 * are recreated.
 */
@RunWith(RobolectricTestRunner::class)
class PersonalSourcesPrivacyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun reset() { Privacy.unlock(ctx); GlideSuggester.forgetUserWords() }

    @Test fun anIncognitoFieldGetsNoLearnedOrPersonalWordFromGlide() {
        val kit = GlideFacilitatorKit(ctx)
        kit.warmUp()
        // positive control: the same gesture in a normal field finds the learned and the personal word
        assertEquals("zork", kit.glide("zork", personalized = true).first())
        assertEquals("quibble", kit.glide("quibble", personalized = true).first())
        // incognito (the decision of the field): neither of them, the main dictionary still works
        assertFalse("zork" in kit.glide("zork", personalized = false))
        assertFalse("quibble" in kit.glide("quibble", personalized = false))
        assertEquals("world", kit.glide("world", personalized = false).first())
    }

    @Test fun glideUsesNoUserWordsWhileTheTierIsLockedEvenWhenTheFieldWasLoadedBeforeTheLock() {
        val kit = GlideFacilitatorKit(ctx)
        kit.warmUp()
        assertEquals("zork", kit.glide("zork", personalized = true).first()) // positive control: before the lock
        assertEquals(2, GlideSuggester.userWordCount()) // positive control: the memory copies of both user dictionaries exist
        Privacy.lock(ctx)
        assertEquals(0, GlideSuggester.userWordCount())
        assertFalse("zork" in kit.glide("zork", personalized = true)) // the stale settings of the field still say yes
        assertFalse("quibble" in kit.glide("quibble", personalized = true))
        assertEquals(0, GlideSuggester.userWordCount()) // and nothing was read again
        assertEquals("world", kit.glide("world", personalized = true).first())
        Privacy.unlock(ctx)
        assertEquals("zork", kit.glide("zork", personalized = true).first()) // positive control: back after the unlock
    }

    @Test fun anIncognitoFieldAsksNoUserDictionaryForSuggestionsWhileANormalFieldDoes() {
        val kit = GlideFacilitatorKit(ctx)
        assertTrue("zork" in kit.tap("zor", personalized = true)) // positive control: both user dictionaries are asked
        assertEquals(1, kit.history.suggestionCalls)
        assertEquals(1, kit.personal.suggestionCalls)
        assertFalse("zork" in kit.tap("zor", personalized = false))
        assertEquals(1, kit.history.suggestionCalls) // not asked again
        assertEquals(1, kit.personal.suggestionCalls)
        Privacy.lock(ctx)
        assertFalse("zork" in kit.tap("zor", personalized = true)) // a lock after the field was loaded
        assertEquals(1, kit.history.suggestionCalls)
    }

    // ---- the lock arrives after the facilitator authorized the user words (deterministic interleaving) ----

    @Test fun aLockBetweenTheAuthorizationAndTheScoringOfGlideReturnsNoUserWord() {
        val kit = GlideFacilitatorKit(ctx)
        kit.warmUp()
        assertEquals("zork", kit.glide("zork", personalized = true).first()) // positive control: the copies are cached and used
        assertEquals("quibble", kit.glide("quibble", personalized = true).first())
        var locked = false
        val lock = { if (!locked) { locked = true; Privacy.lock(ctx) } } // once, when glide has the authorized user dictionaries in hand
        kit.history.onContentVersion = lock
        kit.personal.onContentVersion = lock
        val words = kit.glide("zork", personalized = true)
        assertTrue(locked, "the interleaving happened")
        assertFalse("zork" in words, words.toString())
        assertFalse("quibble" in kit.glide("quibble", personalized = true)) // and the next gesture is refused at the facilitator
        assertEquals("world", kit.glide("world", personalized = true).first()) // the main dictionary still answers
    }

    // ---- is-this-word-valid never consults a learned or personal word list that must stay out ----

    @Test fun theValidityCheckConsultsUserWordsOnlyWhileTheTierIsOpen() {
        val kit = GlideFacilitatorKit(ctx)
        assertTrue(kit.facilitator.isValidSpellingWord("zork")) // positive control: a learned word is valid while unlocked
        assertTrue(kit.facilitator.isValidSuggestionWord("quibble"))
        assertTrue(kit.facilitator.isValidSpellingWord("world"))
        Privacy.lock(ctx) // the native dictionaries keep their words after a lock: only the check keeps them out
        assertFalse(kit.facilitator.isValidSpellingWord("zork"))
        assertFalse(kit.facilitator.isValidSuggestionWord("quibble"))
        assertTrue(kit.facilitator.isValidSpellingWord("world")) // the main dictionary still answers
    }

    // ---- voice text (VoiceWords) gets the user words through the same decision ----

    private fun voice(kit: GlideFacilitatorKit, personalized: Boolean) =
        kit.facilitator.userWordsForVoice(SettingsValuesForSuggestion(false, false, personalized))?.sorted()

    @Test fun voiceTextGetsTheLearnedAndPersonalWordsOnlyWhereTheFieldAllowsThem() {
        val kit = GlideFacilitatorKit(ctx)
        assertEquals(listOf("quibble", "zork"), voice(kit, personalized = true)) // positive control: both user dictionaries
        assertNull(voice(kit, personalized = false)) // an incognito field (password, no-learning flag, incognito mode or app)
        Privacy.lock(ctx)
        assertNull(voice(kit, personalized = true)) // locked, whatever the field was loaded with
        Privacy.unlock(ctx)
        assertEquals(listOf("quibble", "zork"), voice(kit, personalized = true))
        assertNull(GlideFacilitatorKit(ctx, forSpellChecking = true).facilitator.userWordsForVoice(SettingsValuesForSuggestion(false, false, true)))
    }

    @Test fun voiceTextGetsNoUserWordsWhenADictionaryCannotHandOverAllOfThem() {
        val kit = GlideFacilitatorKit(ctx)
        kit.history.complete = false
        assertNull(voice(kit, personalized = true)) // a part of the list could make an ambiguous match look like the only one
        kit.history.complete = true
        assertEquals(listOf("quibble", "zork"), voice(kit, personalized = true))
    }

    @Test fun aLockDuringTheReadOfTheUserWordsGivesNone() {
        val kit = GlideFacilitatorKit(ctx)
        assertEquals(listOf("quibble", "zork"), voice(kit, personalized = true)) // positive control: without the lock
        var read = false
        val locking = object : GlideFacilitatorKit.FakeUser(ctx, Dictionary.TYPE_USER_HISTORY, listOf("zork" to 120)) {
            override fun forEachWord(listener: WordListener): Boolean { read = true; Privacy.lock(ctx); return super.forEachWord(listener) }
        }
        kit.facilitator.installDictionariesForTest(java.util.Locale.ENGLISH, kit.main, mapOf(Dictionary.TYPE_USER_HISTORY to locking, Dictionary.TYPE_USER to kit.personal))
        assertNull(voice(kit, personalized = true))
        assertTrue(read, "the interleaving happened")
    }

    @Test fun onlyAWordOfALoadedMainDictionaryCountsAsADictionaryWordForVoiceText() {
        val kit = GlideFacilitatorKit(ctx)
        assertTrue(kit.facilitator.isMainDictionaryWord("world"))
        assertFalse(kit.facilitator.isMainDictionaryWord("zork")) // a learned word is not a main dictionary word
        assertFalse(kit.facilitator.isMainDictionaryWord("quibble"))
        // nothing loaded: unknown counts as a word, so voice text is left as it is
        assertTrue(DictionaryFacilitatorImpl(GlideSuggester(background = { it() })).isMainDictionaryWord("zork"))
    }

    @Test fun theSpellCheckersFacilitatorNeverConsultsOrSuggestsUserWords() {
        val kit = GlideFacilitatorKit(ctx, forSpellChecking = true) // the unlocked tier: only the spell checker role keeps them out
        assertTrue(kit.facilitator.isValidSpellingWord("world")) // positive control: it works for the main dictionary
        assertFalse(kit.facilitator.isValidSpellingWord("zork"))
        assertFalse(kit.facilitator.isValidSuggestionWord("quibble"))
        assertFalse("zork" in kit.tap("zor", personalized = true).also { assertEquals(0, kit.history.suggestionCalls) })
        assertEquals(0, kit.personal.suggestionCalls)
        // positive control for the two checks above: the same fake dictionaries do answer in the keyboard's own facilitator
        assertTrue(GlideFacilitatorKit(ctx).facilitator.isValidSpellingWord("zork"))
    }
}
