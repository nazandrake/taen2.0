// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.content.Context
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import helium314.keyboard.latin.utils.SubtypeSettings
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The system's request for a keyboard-drawn IME switcher (Android 16 and later) reaches the language key through the running service: while it is
 * requested the language key is on even when the setting hides it, and it goes again when the request ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class ImeSwitcherWiringTest {
    @AfterTest fun after() {
        service?.let { // the enabled languages are static state shared with the other tests of the JVM
            it.prefs().edit { remove(Settings.PREF_ENABLED_SUBTYPES) }
            SubtypeSettings.reloadEnabledSubtypes(it)
        }
        service = null
        ImeSwitcher.requested = false
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
    }

    @Test fun theRequestShowsTheLanguageKeyAndItsEndHidesItAgain() {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        ime.prefs().edit { putBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true) }
        Settings.getInstance().loadSettings(ime, Settings.getValues().mLocale, Settings.getValues().mInputAttributes)
        assertTrue(Settings.getValues().isLanguageSwitchKeyEnabled, "positive control: the key is on with the setting and two languages")
        ime.prefs().edit { putBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, false) }
        Settings.getInstance().loadSettings(ime, Settings.getValues().mLocale, Settings.getValues().mInputAttributes)
        assertFalse(Settings.getValues().isLanguageSwitchKeyEnabled, "the setting hides it")
        ime.onCustomImeSwitcherButtonRequestedVisible(true)
        assertTrue(Settings.getValues().isLanguageSwitchKeyEnabled, "the system asks for a switcher: the language key is it")
        ime.onCustomImeSwitcherButtonRequestedVisible(false)
        assertFalse(Settings.getValues().isLanguageSwitchKeyEnabled)
    }

    private fun service(): LatinIME {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        service = ime
        return ime
    }

    private var service: LatinIME? = null

    /** Taen with one language and a second keyboard enabled on the phone. */
    private fun oneLanguageAndAnotherKeyboard(ime: LatinIME) {
        val one = SubtypeSettings.getEnabledSubtypes(true).first().toSettingsSubtype()
        ime.prefs().edit { putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(one))) }
        SubtypeSettings.reloadEnabledSubtypes(ime)
        assertEquals(1, SubtypeSettings.getEnabledSubtypes(true).size, "positive control: one language is enabled")
        val imm = ime.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val own = imm.inputMethodList.first()
        shadowOf(imm).setEnabledInputMethodInfoList(listOf(own, InputMethodInfo("other.keyboard", "Other", "Other keyboard", null)))
    }

    @Test fun withOneLanguageAndAnotherKeyboardTheForcedLanguageKeyStillSwitchesAndTheRequestStaysOnlyWhileThereIsSomethingToSwitchTo() {
        val ime = service()
        ime.prefs().edit { putBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, false) }
        oneLanguageAndAnotherKeyboard(ime)
        Settings.getInstance().loadSettings(ime, Settings.getValues().mLocale, Settings.getValues().mInputAttributes)
        assertFalse(Settings.getValues().isLanguageSwitchKeyEnabled, "positive control: hidden by the setting while nothing is requested")
        ime.onCustomImeSwitcherButtonRequestedVisible(true)
        assertTrue(Settings.getValues().isLanguageSwitchKeyEnabled, "the system bar is replaced: the key is there although Taen has one language")
        ime.switchToNextSubtype()
        assertEquals(1, ShadowInputMethodService.switchedToNextKeyboard, "a tap goes to the next keyboard when Taen has one language")
    }

    @Test fun withSeveralLanguagesATapCyclesTaenLanguagesAndDoesNotLeaveTaen() {
        val ime = service()
        assertTrue(SubtypeSettings.getEnabledSubtypes(true).size > 1, "positive control: more than one language")
        ime.onCustomImeSwitcherButtonRequestedVisible(true)
        ime.switchToNextSubtype()
        assertEquals(0, ShadowInputMethodService.switchedToNextKeyboard, "a tap with several languages stays in Taen")
        oneLanguageAndAnotherKeyboard(ime) // positive control: the same tap leaves Taen once there is one language and another keyboard
        ime.switchToNextSubtype()
        assertEquals(1, ShadowInputMethodService.switchedToNextKeyboard)
    }

    @Test fun theSystemBarIsReplacedOnlyWhenThereIsSomethingToSwitchTo() {
        val ime = service()
        assertTrue(ime.wantsOwnImeSwitcher(), "several languages: the key switches between them")
        oneLanguageAndAnotherKeyboard(ime)
        assertTrue(ime.wantsOwnImeSwitcher(), "one language and another keyboard: the key goes to that keyboard")
        val imm = ime.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        shadowOf(imm).setEnabledInputMethodInfoList(listOf(imm.inputMethodList.first()))
        assertFalse(ime.wantsOwnImeSwitcher(), "one language and no other keyboard: the system's bar stays")
    }
}
