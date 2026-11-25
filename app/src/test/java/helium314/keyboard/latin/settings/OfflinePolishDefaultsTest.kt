// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The defaults of the offline features. Each line is a decision, not an accident: a change of one
 * of them is a change of the product and must change this test with its reason.
 */
@RunWith(RobolectricTestRunner::class)
class OfflinePolishDefaultsTest {
    @Test fun typingHelpersAreOnByDefault() {
        assertTrue(Defaults.PREF_AUTO_CAP) // auto-capitals
        assertTrue(Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD) // double space gives a period
        assertTrue(Defaults.PREF_SMART_QUOTES) // Bulgarian „ “
        assertTrue(Defaults.PREF_AUTOSPACE_AFTER_SUGGESTION) // a space after a picked suggestion
        assertTrue(Defaults.PREF_SUGGEST_EMOJIS) // emoji suggestions from the CLDR keywords
        assertTrue(Defaults.PREF_GESTURE_INPUT) // glide typing (see GlideQualityGateTest)
        assertTrue(Defaults.PREF_AUTO_CORRECTION)
    }

    @Test fun theToolbarClosesWhenSuggestionsAppear() {
        // the toolbar (chevron key) replaces the suggestion strip; without this it stays open after a toolbar key and no word shows until the chevron is tapped again
        assertTrue(Defaults.PREF_AUTO_HIDE_TOOLBAR)
        assertFalse(Defaults.PREF_AUTO_SHOW_TOOLBAR) // control: it does not open by itself
    }

    @Test fun autospaceAfterTypedPunctuationStaysOptIn() {
        // "example.net" typed in a normal field would become "example. net": the option exists, the default stays off
        assertFalse(Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION)
    }

    @Test fun keySoundIsOffAndHapticsAreOn() {
        assertFalse(Defaults.PREF_SOUND_ON) // sound would tell people nearby what is typed
        assertTrue(Defaults.PREF_VIBRATE_ON) // haptics, with the adjustable strength
        assertEquals(-1, Defaults.PREF_VIBRATION_DURATION_SETTINGS) // the system default strength until the user changes it
    }

    @Test fun theIncognitoKeyIsOnTheToolbarOfANewInstall() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = getEnabledToolbarKeys(context.prefs())
        assertTrue(ToolbarKey.INCOGNITO in fresh, "incognito key: $fresh")
        assertTrue(ToolbarKey.CLIPBOARD in fresh && ToolbarKey.UNDO in fresh) // control: the other defaults are still there
        assertFalse(ToolbarKey.CLEAR_CLIPBOARD in fresh)
    }

    // A language key on the main layout, English and Bulgarian (phonetic) enabled on a new install.
    @Test fun languageKeyIsShownAndEnglishAndBulgarianAreEnabled() {
        assertTrue(Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY)
        assertEquals("internal", Defaults.PREF_LANGUAGE_SWITCH_KEY) // the key switches between the keyboard's own languages
        assertTrue(Defaults.PREF_SPACE_TO_CHANGE_LANG) // the space-bar gesture stays
        val parsed = SubtypeSettings.createSettingsSubtypes(Defaults.PREF_ENABLED_SUBTYPES)
        assertEquals(listOf("en-US", "bg"), parsed.map { it.locale.toLanguageTag() })
        assertEquals(listOf("qwerty", "bulgarian"), parsed.map { it.mainLayoutName() })
    }

    @Test fun aNewInstallResolvesToTwoEnabledSubtypes() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.prefs().edit().clear().commit() // a new install: no enabled_subtypes stored
        SubtypeSettings.init(context)
        SubtypeSettings.reloadEnabledSubtypes(context) // init adds to the list; the reload clears it first (other tests in the JVM may have called init)
        val enabled = SubtypeSettings.getEnabledSubtypes()
        assertEquals(listOf("en_US", "bg"), enabled.map { it.locale.toString() })
    }

    // The emoji key is on the bottom row of a new install. Upstream HeliBoard hides it by default
    // (Defaults.kt PREF_SHOW_EMOJI_KEY = false, so the key was only in the comma long-press popup, TextKeyData.getCommaPopupKeys).
    @Test fun theEmojiKeyIsOnTheBottomRowOfANewInstall() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.prefs().edit().clear().commit()
        assertTrue(Defaults.PREF_SHOW_EMOJI_KEY)
        assertTrue(context.prefs().getBoolean(Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY))
        val layout = java.io.File("src/main/assets/layouts/functional/functional_keys.json").readText()
        assertTrue("\"emojiKeyEnabled\"" in layout && "\"label\": \"emoji\"" in layout, "the bottom row shows the emoji key when the setting is on")
        assertTrue(Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY) // control: both keys are on, as before
    }
}
