// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import android.content.res.AssetManager
import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/** A page of the manual: its id is the file name (assets/manual/<language>/<id>.md); the index shows [title] and [summary] (the app's own strings, in both languages). */
class ManualPage(val id: String, @StringRes val title: Int, @StringRes val summary: Int)

/**
 * The pages of the manual and where they are read from. The index of the Help screen is this list; ManualNavigationTest checks it against the page
 * files of every language, and every `[text](page:id)` link of every page against it. The text of a page is read from the assets of the app only.
 */
object ManualPages {
    const val ASSET_DIR = "manual"
    /** The language that every page exists in, and the one that is shown when the language of the phone has no manual. */
    const val FALLBACK_LANGUAGE = "en"

    val all: List<ManualPage> = listOf(
        ManualPage("getting-started", R.string.help_page_getting_started, R.string.help_page_getting_started_summary),
        ManualPage("languages", R.string.help_page_languages, R.string.help_page_languages_summary),
        ManualPage("preferences", R.string.help_page_preferences, R.string.help_page_preferences_summary),
        ManualPage("appearance", R.string.help_page_appearance, R.string.help_page_appearance_summary),
        ManualPage("toolbar", R.string.help_page_toolbar, R.string.help_page_toolbar_summary),
        ManualPage("gesture-typing", R.string.help_page_gesture_typing, R.string.help_page_gesture_typing_summary),
        ManualPage("text-correction", R.string.help_page_text_correction, R.string.help_page_text_correction_summary),
        ManualPage("layouts", R.string.help_page_layouts, R.string.help_page_layouts_summary),
        ManualPage("dictionaries", R.string.help_page_dictionaries, R.string.help_page_dictionaries_summary),
        ManualPage("voice", R.string.help_page_voice, R.string.help_page_voice_summary),
        ManualPage("downloads", R.string.help_page_downloads, R.string.help_page_downloads_summary),
        ManualPage("media", R.string.help_page_media, R.string.help_page_media_summary),
        ManualPage("privacy-settings", R.string.help_page_privacy_settings, R.string.help_page_privacy_settings_summary),
        ManualPage("self-destruct", R.string.help_page_self_destruct, R.string.help_page_self_destruct_summary),
        ManualPage("profiles", R.string.help_page_profiles, R.string.help_page_profiles_summary),
        ManualPage("advanced", R.string.help_page_advanced, R.string.help_page_advanced_summary),
        ManualPage("about", R.string.help_page_about, R.string.help_page_about_summary),
        ManualPage("suggestions", R.string.help_page_suggestions, R.string.help_page_suggestions_summary),
        ManualPage("clipboard", R.string.help_page_clipboard, R.string.help_page_clipboard_summary),
        ManualPage("privacy", R.string.help_page_privacy, R.string.help_page_privacy_summary),
        ManualPage("vim", R.string.help_page_vim, R.string.help_page_vim_summary),
        ManualPage("vim-keys", R.string.help_page_vim_keys, R.string.help_page_vim_keys_summary),
        ManualPage("vim-power", R.string.help_page_vim_power, R.string.help_page_vim_power_summary),
        ManualPage("vim-limits", R.string.help_page_vim_limits, R.string.help_page_vim_limits_summary),
        ManualPage("vim-lessons", R.string.help_page_vim_lessons, R.string.help_page_vim_lessons_summary),
        ManualPage("troubleshooting", R.string.help_page_troubleshooting, R.string.help_page_troubleshooting_summary),
    )

    fun byId(id: String): ManualPage? = all.firstOrNull { it.id == id }

    /** The languages that have a manual: the folders of assets/manual (the first part of a listed path, so that a listing that goes down into the folders gives the same answer). */
    fun languages(assets: AssetManager): List<String> = assets.list(ASSET_DIR).orEmpty().map { it.substringBefore('/') }.distinct().sorted()

    /** The language of the manual to show: the language of the phone when it has a manual, else English. */
    fun languageFor(phoneLanguage: String, available: Collection<String>): String =
        if (phoneLanguage in available) phoneLanguage else FALLBACK_LANGUAGE

    /** The text of a page, or null when the file is missing or cannot be read. */
    fun read(assets: AssetManager, language: String, id: String): String? =
        if (byId(id) == null) null
        else try {
            assets.open("$ASSET_DIR/$language/$id.md").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: java.io.IOException) {
            null
        }
}
