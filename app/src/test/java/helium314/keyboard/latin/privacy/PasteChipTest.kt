// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Paste chip rule (P10): offered after a copy of text, in every field that asks within 60 s and every field kind, not while locked; gone when
 * used, after 60 s, and when cleared (screen lock). A field change does not end it (copy in one app, paste in another). Time is injected; expected
 * values come from the rule, not from the code.
 */
class PasteChipTest {
    private var now = 1_000_000L
    private var lockedNow = false
    private val chip = PasteChip({ now }, { lockedNow })

    private val text = InputType.TYPE_CLASS_TEXT
    private val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private fun decide(inputType: Int = text, imeOptions: Int = 0, force: Boolean = false, apps: Set<String> = emptySet(), locked: Boolean = false) =
        PrivacyPolicy.decide(PrivacyPolicy.Input(inputType, imeOptions, "org.example.app", force, apps, locked))

    private val plain = decide()

    private var clipCount = 0L
    /** A copy: every call is a new clip (its own time stamp) unless [clipTimestamp] says it is the clip that is already there. */
    private fun copy(decision: PrivacyPolicy.Decision = plain, enabled: Boolean = true, hasText: Boolean = true, clipTimestamp: Long = ++clipCount) =
        chip.onCopy(enabled, decision, hasText, clipTimestamp)
    /** The strip of whatever field is current asks. */
    private fun visible(decision: PrivacyPolicy.Decision = plain, enabled: Boolean = true) = chip.visible(enabled, decision)

    // ---- pass 1: the core ---------------------------------------------------------------------------------------------

    @Test fun theChipIsOfferedAfterACopyAndNotBefore() {
        assertFalse(visible(), "nothing was copied")
        copy()
        assertTrue(visible())
    }

    // ---- pass 2: the whole rule ---------------------------------------------------------------------------------------

    @Test fun itIsOfferedInPasswordAndIncognitoFieldsToo() {
        val kinds = mapOf(
            "password" to decide(inputType = password),
            "number password" to decide(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "web password" to decide(inputType = text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "no learning flag" to decide(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            "force incognito" to decide(force = true),
            "listed app" to decide(apps = setOf("org.example.app")),
        )
        for ((name, decision) in kinds) {
            assertTrue(decision.incognito || decision.passwordField, "$name is incognito (positive control: the kind is the one meant)")
            copy(decision)
            assertTrue(visible(decision), "$name: the copy was made in it and the chip is shown in it")
            copy(plain)
            assertTrue(visible(decision), "$name: a copy from a plain field is offered in it")
        }
    }

    @Test fun itIsNeverOfferedWhileLocked() {
        val locked = decide(locked = true)
        assertTrue(locked.incognito, "positive control: a locked device is incognito")
        copy(locked)
        assertFalse(visible(locked), "copied while locked")
        assertFalse(visible(plain), "a copy made while locked is not offered later either")
        copy(plain)
        assertTrue(visible(plain), "positive control: the same chip is offered after an unlocked copy")
        assertFalse(visible(locked), "shown in a locked field")
        assertTrue(visible(plain), "and it is back where it is allowed")
        copy(decide(inputType = password, locked = true))
        assertFalse(visible(decide(inputType = password, locked = true)), "a locked password field")
    }

    /** The lock is asked live (the same flag the word suggestions use), not only through the decision a field was started with. */
    @Test fun aLiveLockHidesTheChipWhateverTheFieldDecisionSays() {
        copy()
        assertTrue(plain.pasteSuggestion, "positive control: the field's decision allows it")
        assertTrue(visible(), "positive control: shown while unlocked")
        lockedNow = true
        assertFalse(visible(plain), "locked now, though the decision of the field says unlocked")
        lockedNow = false
        assertTrue(visible(plain), "unlocked again within the minute: back")
    }

    @Test fun aCopyMadeWhileLiveLockedIsNeverOffered() {
        lockedNow = true
        copy() // the field's decision is stale (unlocked) but the lock is on
        lockedNow = false
        assertFalse(visible(), "not offered after the unlock either")
        assertEquals(0L, chip.remainingMillis())
        copy()
        assertTrue(visible(), "positive control: an unlocked copy is offered")
    }

    @Test fun theAllowedRuleTakesTheLiveLock() {
        assertTrue(PasteChip.allowed(true, plain, true, lockedNow = false), "positive control")
        assertFalse(PasteChip.allowed(true, plain, true, lockedNow = true))
    }

    @Test fun theSettingOffOrNoTextMeansNoChip() {
        copy(enabled = false)
        assertFalse(visible())
        copy()
        assertTrue(visible(), "positive control")
        assertFalse(visible(enabled = false), "turned off after the copy")
        assertTrue(visible(), "and on again")
        copy(hasText = false)
        assertFalse(visible(), "an image or a cleared clipboard replaces the clip: no chip")
    }

    @Test fun aLaterCopyOfSomethingNotOfferedRemovesTheChipOfTheEarlierOne() {
        copy()
        assertTrue(visible())
        copy(enabled = false)
        assertFalse(visible())
    }

    @Test fun itExpiresAfterSixtySecondsExactly() {
        assertEquals(60_000L, PasteChip.LIFETIME_MILLIS)
        copy()
        assertEquals(60_000L, chip.remainingMillis())
        now += 59_999
        assertTrue(visible(), "1 ms before")
        assertEquals(1L, chip.remainingMillis())
        now += 1
        assertFalse(visible(), "at 60 s")
        now -= 1
        assertFalse(visible(), "it does not come back")
        assertEquals(0L, chip.remainingMillis())
    }

    @Test fun aNewCopyStartsTheMinuteAgain() {
        copy()
        now += 50_000
        copy()
        now += 50_000
        assertTrue(visible(), "100 s after the first copy, 50 s after the second")
        now += 10_000
        assertFalse(visible())
    }

    @Test fun usingItRemovesIt() {
        copy()
        assertTrue(visible())
        chip.onUsed()
        assertFalse(visible())
        copy()
        assertTrue(visible(), "a new copy brings it back")
    }

    /** Copy in one app, paste in another: the chip is the same in every field that asks within the minute, whatever the field kind. */
    @Test fun itStaysForEveryFieldThatAsksWithinTheMinute() {
        copy()
        val fields = listOf(plain, decide(inputType = password), decide(force = true), decide(inputType = InputType.TYPE_CLASS_NUMBER), plain)
        var elapsed = 0L
        for ((i, decision) in fields.withIndex()) {
            assertTrue(visible(decision), "field $i, ${elapsed / 1000} s after the copy")
            now += 10_000
            elapsed += 10_000
        }
        now += 9_999 // 59 999 ms after the copy
        assertTrue(visible(), "1 ms before the minute is over, in one more field")
        now += 1
        assertFalse(visible(), "the minute is over: no field has it")
    }

    @Test fun aDecisionOfOneFieldDoesNotUseUpTheChipForTheNext() {
        val locked = decide(locked = true)
        copy()
        assertFalse(visible(locked), "not offered in the locked field")
        assertTrue(visible(plain), "positive control: the next field has it")
        assertFalse(visible(locked), "locked again")
        assertTrue(visible(plain), "and unlocked again")
    }

    /** Measured on the reference phone: the system tells the listeners about the same clip again exactly 60 s after the copy (same time stamp). */
    @Test fun theSameClipAnnouncedAgainIsNotAFreshCopy() {
        copy(clipTimestamp = 777)
        now += 59_999
        assertTrue(visible())
        now += 1
        assertFalse(visible(), "the minute is over")
        copy(clipTimestamp = 777) // the system's second notice of the clip of the same copy
        assertFalse(visible(), "it does not bring the chip back")
        assertEquals(0L, chip.remainingMillis())
        copy(clipTimestamp = 778)
        assertTrue(visible(), "positive control: a clip with another time stamp is a fresh copy")
    }

    @Test fun theSameClipAnnouncedAgainDoesNotRestartTheMinute() {
        copy(clipTimestamp = 5)
        now += 50_000
        copy(clipTimestamp = 5)
        assertEquals(10_000L, chip.remainingMillis(), "still counted from the first notice")
        now += 10_000
        assertFalse(visible())
        chip.clear()
        copy(clipTimestamp = 6)
        now += 50_000
        copy(clipTimestamp = 6)
        copy(clipTimestamp = 7)
        assertEquals(60_000L, chip.remainingMillis(), "positive control: another clip restarts it")
    }

    @Test fun aSecondNoticeOfAClipThatWasNotOfferedStaysNotOffered() {
        copy(enabled = false, clipTimestamp = 9)
        copy(enabled = true, clipTimestamp = 9)
        assertFalse(visible(), "the clip was seen once, with the chip off: its second notice is not a copy")
        copy(enabled = true, clipTimestamp = 10)
        assertTrue(visible(), "positive control")
    }

    @Test fun aClipWithNoTimeStampIsAlwaysFresh() {
        copy(clipTimestamp = 0)
        now += 30_000
        copy(clipTimestamp = 0)
        assertEquals(60_000L, chip.remainingMillis())
    }

    @Test fun clearingEndsItForGood() {
        copy()
        assertTrue(visible())
        chip.clear() // the screen locked
        assertFalse(visible(), "gone after the lock")
        assertEquals(0L, chip.remainingMillis())
        copy()
        assertTrue(visible(), "a copy after the lock brings it back")
    }
}
