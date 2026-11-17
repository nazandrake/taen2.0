// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.gesture.GlideFacilitatorKit
import helium314.keyboard.latin.gesture.GlideSuggester
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
