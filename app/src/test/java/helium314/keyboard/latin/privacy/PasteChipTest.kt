// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Paste chip rule (P10): offered after a copy of text, in every field kind, not while locked; gone when used, after 60 s, and when the field
 * changes after it was shown. Time is injected; expected values come from the rule, not from the code.
 */
class PasteChipTest {
    private var now = 1_000_000L
    private val chip = PasteChip { now }

    private val text = InputType.TYPE_CLASS_TEXT
    private val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private fun decide(inputType: Int = text, imeOptions: Int = 0, force: Boolean = false, apps: Set<String> = emptySet(), locked: Boolean = false) =
        PrivacyPolicy.decide(PrivacyPolicy.Input(inputType, imeOptions, "org.example.app", force, apps, locked))

    private val plain = decide()

    private var fieldCount = 0
    private var field = FieldId("org.example.app", 0, text, 0)
    /** Input starts in a field that is not the one before. */
    private fun startOtherField() {
        field = FieldId("org.example.app", ++fieldCount, text, 0)
        chip.onFieldStart(field)
    }

    private fun copy(decision: PrivacyPolicy.Decision = plain, enabled: Boolean = true, hasText: Boolean = true) = chip.onCopy(enabled, decision, hasText)
    /** The strip of a field that has the keyboard asks and, when the chip is there, says that it is shown. */
    private fun visible(decision: PrivacyPolicy.Decision = plain, enabled: Boolean = true) =
        chip.visible(enabled, decision).also { if (it) chip.markShown(field) }
    /** Asking only: a layout of a strip that is not on screen. */
    private fun peek(decision: PrivacyPolicy.Decision = plain, enabled: Boolean = true) = chip.visible(enabled, decision)

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

    @Test fun aFieldChangeRemovesAChipThatWasShownButNotOneThatWasNot() {
        copy()
        startOtherField() // copied while no field was shown: the first field that opens still gets it
        assertTrue(visible(), "the first field shows it")
        startOtherField()
        assertFalse(visible(), "the next field does not")
        copy()
        assertTrue(visible())
        startOtherField()
        assertFalse(visible())
    }

    @Test fun askingIsNotShowing() {
        copy()
        repeat(3) { assertTrue(peek(), "asked while the strip is not on screen") }
        startOtherField()
        assertTrue(visible(), "a chip that was only asked about is not taken away by the next field")
        chip.markShown(field)
        startOtherField()
        assertFalse(peek(), "marked shown, then the field changed")
        chip.markShown(field) // nothing is armed: marking changes nothing
        assertFalse(peek())
    }

    @Test fun theSameFieldStartingAgainKeepsTheChip() {
        copy()
        assertTrue(visible(), "shown in the field")
        repeat(3) { chip.onFieldStart(field) } // the system starts input in a field more than once
        assertTrue(peek(), "the same field is no other field")
        for (other in listOf(field.copy(packageName = "org.other"), field.copy(fieldId = 99), field.copy(inputType = password), field.copy(imeOptions = 5))) {
            copy()
            assertTrue(visible())
            chip.onFieldStart(other)
            assertFalse(peek(), "$other is another field")
        }
    }

    @Test fun aNewCopyIsAFreshChipThatHasNotBeenShownYet() {
        copy()
        assertTrue(visible(), "shown for the first copy")
        copy() // the second copy is made while no field shows the strip
        startOtherField()
        assertTrue(visible(), "the first field that opens after the second copy still gets its chip")
    }

    @Test fun aChipThatCouldNotBeShownHereStaysForTheNextField() {
        val locked = decide(locked = true)
        copy()
        assertFalse(visible(locked), "not shown in the locked field")
        startOtherField()
        assertTrue(visible(plain), "it was never shown, so the field change does not remove it")
    }
}
