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
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
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
}
