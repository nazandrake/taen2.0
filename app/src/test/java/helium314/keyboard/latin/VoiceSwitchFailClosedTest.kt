// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.ShadowAndroidKeyVault
import helium314.keyboard.latin.privacy.VaultUnavailableException
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The voice typing switch fails closed in the running service: while the settings cannot be read (a Keystore or I/O error of the
 * device-protected store) the default of the switch (on) is not taken, voice typing is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class VoiceSwitchFailClosedTest {
    private fun withKeyboard(test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        try {
            test(ime)
        } finally {
            ShadowAndroidKeyVault.deUnwrapError = null
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ShadowInputMethodService.reset()
        }
    }

    @Test fun voiceTypingIsOffWhileTheSettingsCannotBeReadAndTheSwitchDecidesAgainAfterwards() = withKeyboard { ime ->
        assertTrue(ime.mVoiceTyping.voiceEnabled()) // positive control: the default is on
        assertTrue(ime.prefs().edit().putBoolean(Settings.PREF_VOICE_TYPING, true).commit())
        Privacy.forgetCachedState() // a new process: the data key is unwrapped again
        ShadowAndroidKeyVault.deUnwrapError = VaultUnavailableException("the keystore is busy")
        assertFalse(ime.mVoiceTyping.voiceEnabled())
        ShadowAndroidKeyVault.deUnwrapError = null // the error passes: the stored value (on) decides again
        assertTrue(ime.mVoiceTyping.voiceEnabled())
        assertTrue(ime.prefs().edit().putBoolean(Settings.PREF_VOICE_TYPING, false).commit())
        assertFalse(ime.mVoiceTyping.voiceEnabled()) // and an off switch stays off
    }
}
