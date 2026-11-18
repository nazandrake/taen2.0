// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.screens.createAboutSettings
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.createAppearanceSettings
import helium314.keyboard.settings.screens.createCorrectionSettings
import helium314.keyboard.settings.screens.createGestureTypingSettings
import helium314.keyboard.settings.screens.createLayoutSettings
import helium314.keyboard.settings.screens.createPreferencesSettings
import helium314.keyboard.settings.screens.createToolbarSettings

class SettingsContainer(context: Context) {
    private val list = createSettings(context)
    private val map: Map<String, Setting> = HashMap<String, Setting>(list.size).apply {
        list.forEach {
            if (put(it.key, it) != null)
                throw IllegalArgumentException("key $it added twice")
        }
    }

    operator fun get(key: Any): Setting? = map[key]

    // filtering could be more elaborate, but should be good enough for a start
    // always have all settings in search, because:
    //  don't show disabled settings -> users confused
    //  show as disabled (i.e. no interaction possible) -> users confused
    //  show, but change will not do anything because another setting needs to be enabled first -> probably best
    fun filter(searchTerm: String): List<Setting> {
        val term = searchTerm.lowercase()
        val results = mutableSetOf<Setting>()
        list.forEach { setting -> if (setting.title.lowercase().startsWith(term)) results.add(setting) }
        list.forEach { setting -> if (setting.title.lowercase().split(' ').any { it.startsWith(term) }) results.add(setting) }
        list.forEach { setting ->
            if (setting.description?.lowercase()?.split(' ')?.any { it.startsWith(term) } == true)
                results.add(setting)
        }
        return results.toList()
    }
}

@Immutable
class Setting(
    context: Context,
    val key: String,
    @StringRes titleId: Int,
    @StringRes descriptionId: Int? = null,
    private val content: @Composable (Setting) -> Unit
) {
    val title = context.getString(titleId)
    val description = descriptionId?.let { context.getString(it) }

    @Composable
    fun Preference() {
        content(this)
    }
}

// intentionally not putting individual debug settings in here so user knows the context
private fun createSettings(context: Context) = createTaenSettings(context) + createAboutSettings(context) + createAppearanceSettings(context) +
        createCorrectionSettings(context) + createPreferencesSettings(context) + createToolbarSettings(context) +
        createLayoutSettings(context) + createAdvancedSettings(context) +
        if (JniUtils.sHaveGestureLib) createGestureTypingSettings(context) else emptyList()

/**
 * Entries that only exist for the search: they open a Taen screen that has no setting of its own in this list, so that Profiles, Self-destruct,
 * Voice typing and App lock are found by their names, in English and Bulgarian. Static strings; the search text is not stored.
 */
private fun createTaenSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.PROFILES, R.string.profiles_title, R.string.profiles_summary) {
        Preference(name = it.title, description = it.description, onClick = { SettingsDestination.navigateTo(SettingsDestination.Profiles) }) { NextScreenIcon() }
    },
    Setting(context, SettingsWithoutKey.SELF_DESTRUCT, R.string.self_destruct_title, R.string.self_destruct_intro_short) {
        Preference(name = it.title, description = it.description, onClick = { SettingsDestination.navigateTo(SettingsDestination.SelfDestruct) }) { NextScreenIcon() }
    },
    Setting(context, SettingsWithoutKey.VOICE_TYPING, R.string.settings_screen_voice, R.string.voice_key_summary) {
        Preference(name = it.title, description = it.description, onClick = { SettingsDestination.navigateTo(SettingsDestination.Voice) }) { NextScreenIcon() }
    },
    Setting(context, SettingsWithoutKey.APP_LOCK, R.string.app_lock_title, R.string.app_lock_summary) {
        Preference(name = it.title, description = it.description, onClick = { SettingsDestination.navigateTo(SettingsDestination.Privacy) }) { NextScreenIcon() }
    },
)

object SettingsWithoutKey {
    const val PROFILES = "profiles"
    const val SELF_DESTRUCT = "self_destruct"
    const val VOICE_TYPING = "voice_typing_screen"
    const val APP_LOCK = "app_lock"
    const val EDIT_PERSONAL_DICTIONARY = "edit_personal_dictionary"
    const val CLEAR_APP_LANGUAGES = "clear_app_languages"
    const val APP = "app"
    const val VERSION = "version"
    const val LICENSE = "license"
    const val HIDDEN_FEATURES = "hidden_features"
    const val BANKING_APPS = "banking_apps"
    const val GITHUB = "github"
    const val COMMUNITY_LINKS = "community_links"
    const val BACKUP_RESTORE = "backup_restore"
    const val DEBUG_SETTINGS = "screen_debug"
    const val BACKGROUND_IMAGE = "background_image"
    const val BACKGROUND_IMAGE_LANDSCAPE = "background_image_landscape"
    const val CUSTOM_FONT = "custom_font"
    const val CUSTOM_EMOJI_FONT = "custom_emoji_font"
}
