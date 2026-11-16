// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.content.SharedPreferences
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.profiles.SettingsApply
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/** How the user changes the language (step 3 of the guide). */
enum class LanguageSwitch { KEY, SPACE_SWIPE, LONG_PRESS_SPACE }

/**
 * The first-run guide (P21): shown once at the first start after an install or after a wipe of the settings. The "done" flag is ONE device-protected boolean
 * ([Settings.PREF_SETUP_GUIDE_DONE]); Skip and Done both set it. Every choice of the guide is applied through [SettingsApply] to INCLUDED keys only. The texts
 * are static strings; nothing the user does in the guide is recorded.
 */
object SetupGuide {
    fun shouldShow(de: SharedPreferences): Boolean = !de.getBoolean(Settings.PREF_SETUP_GUIDE_DONE, false)

    /** Marks the guide as shown (Skip or Done). False when it could not be stored: the guide then shows again next time. */
    fun markDone(de: SharedPreferences): Boolean = de.edit().putBoolean(Settings.PREF_SETUP_GUIDE_DONE, true).commit()

    /** Step 2: English (QWERTY) and Bulgarian (phonetic) enabled. */
    fun enableEnglishAndBulgarian(de: SharedPreferences): Boolean =
        SettingsApply.commit(de, SettingsApply.plan(de, mapOf(Settings.PREF_ENABLED_SUBTYPES to Defaults.PREF_ENABLED_SUBTYPES)))

    /** The settings of one way to switch language. The other ways are left as they are. */
    fun languageSwitchValues(how: LanguageSwitch): Map<String, Any> = when (how) {
        LanguageSwitch.KEY -> mapOf(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY to true)
        LanguageSwitch.SPACE_SWIPE -> mapOf(Settings.PREF_SPACE_HORIZONTAL_SWIPE to KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE.name)
        LanguageSwitch.LONG_PRESS_SPACE -> mapOf(Settings.PREF_SPACE_TO_CHANGE_LANG to true)
    }

    /** Step 3: turns the chosen way on. */
    fun chooseLanguageSwitch(de: SharedPreferences, how: LanguageSwitch): Boolean =
        SettingsApply.commit(de, SettingsApply.plan(de, languageSwitchValues(how)))
}
