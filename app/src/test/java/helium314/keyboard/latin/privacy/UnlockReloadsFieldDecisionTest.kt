// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A field that starts input after the keyguard went away but before the user-present broadcast keeps the decision it was loaded with: the
 * screen-off flag of Privacy is still set then, so the field is incognito (no learning, no learned or personal word as a suggestion). The
 * broadcast that ends the lock must make the keyboard read that decision again for the field that is open, and while the lock lasts the
 * restrictive decision must stay. Drives the real LatinIME receiver, the real settings loader and the real lock flags (the keyguard is a fake).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class UnlockReloadsFieldDecisionTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        val keyguard = shadowOf(ime.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(false)
        keyguard.setIsDeviceLocked(false)
        try {
            test(ime, ime.applicationContext)
        } finally {
            keyguard.setKeyguardLocked(false)
            keyguard.setIsDeviceLocked(false)
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ShadowInputMethodService.reset()
        }
    }

    private fun load(ime: LatinIME, inputType: Int = text, packageName: String = "org.example.app") {
        val editor = EditorInfo().apply { this.inputType = inputType; this.packageName = packageName }
        Settings.getInstance().loadSettings(ime, Locale.ENGLISH, InputAttributes(editor, false, ime.packageName))
    }

    private fun broadcast(ctx: Context, action: String) {
        ctx.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- pass 1 ---------------------------------------------------------------------------------------------------------

    @Test fun aFieldThatStartedBetweenTheKeyguardAndTheUserPresentBroadcastIsReadAgainWhenTheLockEnds() = withKeyboard { ime, ctx ->
        load(ime)
        assertFalse(Settings.getValues().mIncognitoModeEnabled, "positive control: an unlocked plain field learns")
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        assertTrue(Privacy.isLocked)
        // the keyguard is gone (isDeviceLocked is false) and the field starts, but USER_PRESENT has not arrived: Privacy is still locked
        load(ime)
        assertTrue(Settings.getValues().mIncognitoModeEnabled, "the lock flag makes the field incognito")
        assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
        broadcast(ctx, Intent.ACTION_USER_PRESENT)
        assertFalse(Privacy.isLocked)
        assertFalse(Settings.getValues().mIncognitoModeEnabled, "the same field, now unlocked, is read again")
        assertTrue(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
    }

    // ---- pass 2 ---------------------------------------------------------------------------------------------------------

    private fun keyguard(ime: LatinIME, up: Boolean) = shadowOf(ime.getSystemService(KeyguardManager::class.java)).apply {
        setKeyguardLocked(up)
        setIsDeviceLocked(up)
    }

    @Test fun whileTheLockLastsEveryReloadStaysRestrictive() = withKeyboard { ime, ctx ->
        load(ime)
        assertFalse(Settings.getValues().mIncognitoModeEnabled, "positive control: unlocked")
        assertFalse(Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN)
        keyguard(ime, true)
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        load(ime)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        assertEquals(ToolbarMode.HIDDEN, Settings.getValues().mToolbarMode)
        broadcast(ctx, Intent.ACTION_SCREEN_ON) // the keyguard is still up: nothing unlocks, nothing is read as unlocked
        assertTrue(Privacy.isLocked)
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
        assertEquals(ToolbarMode.HIDDEN, Settings.getValues().mToolbarMode)
        ctx.prefs().edit().putBoolean(Settings.PREF_AUTO_HIDE_TOOLBAR, false).commit() // the existing reload on a changed preference, while locked
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
        assertEquals(ToolbarMode.HIDDEN, Settings.getValues().mToolbarMode)
        broadcast(ctx, Intent.ACTION_SCREEN_OFF) // a second screen-off does not unlock
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun theEndOfTheLockAlsoReadsTheToolbarAndTheSameFieldAgain() = withKeyboard { ime, ctx ->
        keyguard(ime, true)
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        load(ime, text or InputType.TYPE_TEXT_VARIATION_URI, "org.example.other")
        val before = Settings.getValues()
        assertEquals(ToolbarMode.HIDDEN, before.mToolbarMode)
        keyguard(ime, false)
        broadcast(ctx, Intent.ACTION_USER_PRESENT)
        val after = Settings.getValues()
        assertTrue(after !== before, "positive control: the values were rebuilt")
        assertFalse(after.mToolbarMode == ToolbarMode.HIDDEN)
        assertFalse(after.mIncognitoModeEnabled)
        assertEquals("org.example.other", after.mInputAttributes.mTargetApplicationPackageName, "the field that is open is the one read again")
        assertEquals(before.mInputAttributes.mInputType, after.mInputAttributes.mInputType)
    }

    @Test fun aScreenOnWithoutKeyguardEndsTheLockAndReadsTheFieldAgain() = withKeyboard { ime, ctx ->
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        load(ime)
        assertTrue(Settings.getValues().mIncognitoModeEnabled, "positive control: locked")
        broadcast(ctx, Intent.ACTION_SCREEN_ON) // no keyguard (the fake says it is down)
        assertFalse(Privacy.isLocked)
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun anUnlockBeforeAnyFieldWasLoadedOnlyOpensTheTier() = withKeyboard { ime, ctx ->
        load(ime)
        val field = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
        val loaded = field.get(Settings.getInstance())
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        assertTrue(Privacy.isLocked, "positive control: locked")
        try {
            field.set(Settings.getInstance(), null) // a process that has not started any input yet
            broadcast(ctx, Intent.ACTION_USER_PRESENT) // must not throw into the receiver
            assertFalse(Privacy.isLocked)
            assertEquals(null, field.get(Settings.getInstance()), "nothing was invented to read")
        } finally {
            field.set(Settings.getInstance(), loaded)
        }
    }

    @Test fun theEndOfTheLockNeverLoosensTheOtherRules()= withKeyboard { ime, ctx ->
        ctx.protectedPrefs().edit().putString(Settings.PREF_INCOGNITO_APPS, "org.bank.app").commit()
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        for ((type, app) in listOf(text or InputType.TYPE_TEXT_VARIATION_PASSWORD to "org.example.app", text to "org.bank.app")) {
            load(ime, type, app)
            assertTrue(Settings.getValues().mIncognitoModeEnabled, "locked: $type $app")
            broadcast(ctx, Intent.ACTION_USER_PRESENT)
            assertTrue(Settings.getValues().mIncognitoModeEnabled, "unlocked, still a password field or an app of the list: $type $app")
            assertFalse(Settings.getValues().mSettingsValuesForSuggestion.mPersonalized)
            broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        }
        load(ime) // positive control: an ordinary field of another app becomes personal after the same sequence
        broadcast(ctx, Intent.ACTION_USER_PRESENT)
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
        ctx.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit()
        broadcast(ctx, Intent.ACTION_SCREEN_OFF)
        load(ime)
        broadcast(ctx, Intent.ACTION_USER_PRESENT)
        assertTrue(Settings.getValues().mIncognitoModeEnabled, "force incognito survives the unlock")
    }
}
