// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/** One link of the "Everyday" section at the top of the settings: a title, an optional summary and the screen it opens. Navigation only, nothing is stored. */
class EverydayLink(@StringRes val title: Int, @StringRes val summary: Int?, val destination: String)

/** The eight links of the "Everyday" section: the options that are changed most often, one tap from the settings home. */
object EverydayLinks {
    val all: List<EverydayLink> = listOf(
        EverydayLink(R.string.prefs_keyboard_height_scale, null, SettingsDestination.Appearance),
        EverydayLink(R.string.number_row, null, SettingsDestination.Preferences),
        EverydayLink(R.string.everyday_one_handed, R.string.everyday_one_handed_summary, SettingsDestination.Toolbar),
        EverydayLink(R.string.vibrate_on_keypress, null, SettingsDestination.Preferences),
        EverydayLink(R.string.everyday_language_switch, R.string.everyday_language_switch_summary, SettingsDestination.Preferences),
        EverydayLink(R.string.everyday_snippets, null, SettingsDestination.PersonalDictionaries),
        EverydayLink(R.string.clipboard_history_retention_time, null, SettingsDestination.Preferences),
        EverydayLink(R.string.profiles_title, R.string.profiles_summary, SettingsDestination.Profiles),
    )
}
