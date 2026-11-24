// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.manual

import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.SelfDestruct
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.settings.SettingsWithoutKey

/** The title of a settings entry of the manual: the app's own text for the setting, and where several settings share a title, the line that tells them apart. */
class ManualTitle(@StringRes val title: Int, @StringRes val subtitle: Int? = null)

/**
 * The title of every settings entry of the manual, by the key of the setting (the `{#key}` of its heading). The text is the app's own string, so the
 * manual shows what the screen shows, in the language of the screen. ManualCoverageTest compares each of these with the title that the settings
 * screens (SettingsContainer) give the same key, and the keys with the keys that the screens show.
 */
object ManualTitles {
    private val fixed: Map<String, ManualTitle> = mapOf(
        SettingsWithoutKey.VERSION to ManualTitle(R.string.version),
        SettingsWithoutKey.LICENSE to ManualTitle(R.string.license),
        SettingsWithoutKey.HIDDEN_FEATURES to ManualTitle(R.string.hidden_features_title),
        SettingsWithoutKey.BANKING_APPS to ManualTitle(R.string.banking_apps_title),
        SettingsWithoutKey.COMMUNITY_LINKS to ManualTitle(R.string.about_community_links),
        SettingsWithoutKey.GITHUB to ManualTitle(R.string.about_github_link),
        Settings.PREF_ALWAYS_INCOGNITO_MODE to ManualTitle(R.string.incognito),
        Settings.PREF_KEY_LONGPRESS_TIMEOUT to ManualTitle(R.string.prefs_key_longpress_timeout_settings),
        Settings.PREF_SPACE_HORIZONTAL_SWIPE to ManualTitle(R.string.show_horizontal_space_swipe),
        Settings.PREF_SPACE_VERTICAL_SWIPE to ManualTitle(R.string.show_vertical_space_swipe),
        Settings.PREF_LANGUAGE_SWIPE_DISTANCE to ManualTitle(R.string.prefs_language_swipe_distance),
        Settings.PREF_TOUCHPAD_SENSITIVITY to ManualTitle(R.string.touchpad_sensitivity),
        Settings.PREF_TOUCHPAD_EDGE_SCROLL to ManualTitle(R.string.touchpad_edge_scroll),
        Settings.PREF_DELETE_SWIPE to ManualTitle(R.string.delete_swipe),
        Settings.PREF_SPACE_TO_CHANGE_LANG to ManualTitle(R.string.prefs_long_press_keyboard_to_change_lang),
        Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD to ManualTitle(R.string.prefs_long_press_symbol_for_numpad),
        Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY to ManualTitle(R.string.prefs_enable_emoji_alt_physical_key),
        Settings.PREF_ABC_AFTER_SYMBOL_SPACE to ManualTitle(R.string.switch_keyboard_after, R.string.after_symbol_and_space),
        Settings.PREF_ABC_AFTER_NUMPAD_SPACE to ManualTitle(R.string.switch_keyboard_after, R.string.after_numpad_and_space),
        Settings.PREF_ABC_AFTER_EMOJI to ManualTitle(R.string.switch_keyboard_after, R.string.after_emoji),
        Settings.PREF_ABC_AFTER_CLIP to ManualTitle(R.string.switch_keyboard_after, R.string.after_clip),
        Settings.PREF_CUSTOM_CURRENCY_KEY to ManualTitle(R.string.customize_currencies),
        Settings.PREF_MORE_POPUP_KEYS to ManualTitle(R.string.show_popup_keys_title),
        SettingsWithoutKey.BACKUP_RESTORE to ManualTitle(R.string.backup_restore_title),
        Settings.PREF_TIMESTAMP_FORMAT to ManualTitle(R.string.timestamp_format_title),
        Settings.PREF_EMOJI_MAX_SDK to ManualTitle(R.string.prefs_key_emoji_max_sdk),
        Settings.PREF_URL_DETECTION to ManualTitle(R.string.url_detection_title),
        Settings.PREF_ENABLE_HARDWARE_KEYBOARD to ManualTitle(R.string.prefs_enable_hardware_keyboard),
        Settings.PREF_THEME_STYLE to ManualTitle(R.string.theme_style),
        Settings.PREF_ICON_STYLE to ManualTitle(R.string.icon_style),
        Settings.PREF_ACCENT_SHIFTED_ICON to ManualTitle(R.string.accent_shifted_icon),
        Settings.PREF_CUSTOM_ICON_NAMES to ManualTitle(R.string.customize_icons),
        Settings.PREF_THEME_COLORS to ManualTitle(R.string.theme_colors),
        Settings.PREF_THEME_COLORS_NIGHT to ManualTitle(R.string.theme_colors_night),
        Settings.PREF_THEME_KEY_BORDERS to ManualTitle(R.string.key_borders),
        Settings.PREF_THEME_DAY_NIGHT to ManualTitle(R.string.day_night_mode),
        Settings.PREF_NAVBAR_COLOR to ManualTitle(R.string.theme_navbar),
        SettingsWithoutKey.BACKGROUND_IMAGE to ManualTitle(R.string.customize_background_image),
        SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE to ManualTitle(R.string.customize_background_image_landscape),
        Settings.PREF_ENABLE_SPLIT_KEYBOARD to ManualTitle(R.string.enable_split_keyboard),
        Settings.PREF_SPLIT_SPACER_SCALE_PREFIX to ManualTitle(R.string.split_spacer_scale),
        Settings.PREF_KEY_GAP_SCALE_PREFIX to ManualTitle(R.string.prefs_key_gap_scale),
        Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX to ManualTitle(R.string.prefs_keyboard_height_scale),
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX to ManualTitle(R.string.prefs_bottom_row_scale),
        Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX to ManualTitle(R.string.prefs_bottom_padding_scale),
        Settings.PREF_SIDE_PADDING_SCALE_PREFIX to ManualTitle(R.string.prefs_side_padding_scale),
        Settings.PREF_SPACE_BAR_TEXT to ManualTitle(R.string.prefs_space_bar_text),
        SettingsWithoutKey.CUSTOM_FONT to ManualTitle(R.string.custom_font),
        Settings.PREF_FONT_SCALE to ManualTitle(R.string.prefs_font_scale),
        Settings.PREF_HINT_FONT_SCALE to ManualTitle(R.string.prefs_hint_font_scale),
        SettingsWithoutKey.CUSTOM_EMOJI_FONT to ManualTitle(R.string.custom_emoji_font),
        Settings.PREF_EMOJI_FONT_SCALE to ManualTitle(R.string.prefs_emoji_font_scale),
        Settings.PREF_EMOJI_KEY_FIT to ManualTitle(R.string.prefs_emoji_key_fit),
        Settings.PREF_EMOJI_SKIN_TONE to ManualTitle(R.string.prefs_emoji_skin_tone),
        Settings.PREF_GESTURE_INPUT to ManualTitle(R.string.gesture_input),
        Settings.PREF_GESTURE_PREVIEW_TRAIL to ManualTitle(R.string.gesture_preview_trail),
        Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT to ManualTitle(R.string.gesture_floating_preview_static),
        Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC to ManualTitle(R.string.gesture_floating_preview_text),
        Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN to ManualTitle(R.string.gesture_fast_typing_cooldown),
        Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION to ManualTitle(R.string.gesture_trail_fadeout_duration),
        Settings.PREF_SAVE_SUBTYPE_PER_APP to ManualTitle(R.string.save_subtype_per_app),
        SettingsWithoutKey.CLEAR_APP_LANGUAGES to ManualTitle(R.string.clear_app_languages),
        Settings.PREF_SHOW_HINTS to ManualTitle(R.string.show_hints),
        Settings.PREF_POPUP_KEYS_HINT_ORDER to ManualTitle(R.string.hint_source),
        Settings.PREF_POPUP_KEYS_ORDER to ManualTitle(R.string.popup_order),
        Settings.PREF_SHOW_TLD_POPUP_KEYS to ManualTitle(R.string.show_tld_popup_keys),
        Settings.PREF_SHOW_POPUP_HINTS to ManualTitle(R.string.show_popup_hints),
        Settings.PREF_POPUP_ON to ManualTitle(R.string.popup_on_keypress),
        Settings.PREF_VIBRATE_ON to ManualTitle(R.string.vibrate_on_keypress),
        Settings.PREF_VIBRATE_IN_DND_MODE to ManualTitle(R.string.vibrate_in_dnd_mode),
        Settings.PREF_SOUND_ON to ManualTitle(R.string.sound_on_keypress),
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS to ManualTitle(R.string.show_emoji_descriptions),
        Settings.PREF_SHOW_NUMBER_ROW to ManualTitle(R.string.number_row),
        Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS to ManualTitle(R.string.number_row_in_symbols),
        Settings.PREF_LOCALIZED_NUMBER_ROW to ManualTitle(R.string.localized_number_row),
        Settings.PREF_SHOW_NUMBER_ROW_HINTS to ManualTitle(R.string.number_row_hints),
        Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY to ManualTitle(R.string.show_language_switch_key),
        Settings.PREF_LANGUAGE_SWITCH_KEY to ManualTitle(R.string.language_switch_key_behavior),
        Settings.PREF_SHOW_EMOJI_KEY to ManualTitle(R.string.show_emoji_key),
        Settings.PREF_EMOJI_HOLD to ManualTitle(R.string.emoji_hold_title),
        Settings.PREF_SHOW_DPAD_KEY to ManualTitle(R.string.show_dpad_key),
        Settings.PREF_VIM_MODE to ManualTitle(R.string.vim_mode),
        Settings.PREF_VIM_INDENT_TAB to ManualTitle(R.string.vim_indent_tab),
        Settings.PREF_REMOVE_REDUNDANT_POPUPS to ManualTitle(R.string.remove_redundant_popups),
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY to ManualTitle(R.string.enable_clipboard_history),
        Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME to ManualTitle(R.string.clipboard_history_retention_time),
        Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST to ManualTitle(R.string.clipboard_history_pinned_first),
        Settings.PREF_CLIPBOARD_HISTORY_MAX_ITEMS to ManualTitle(R.string.clipboard_history_max_items),
        Settings.PREF_VIBRATION_DURATION_SETTINGS to ManualTitle(R.string.prefs_keypress_vibration_duration_settings),
        Settings.PREF_KEYPRESS_SOUND_VOLUME to ManualTitle(R.string.prefs_keypress_sound_volume_settings),
        SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY to ManualTitle(R.string.edit_personal_dictionary),
        Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE to ManualTitle(R.string.prefs_block_potentially_offensive_title),
        Settings.PREF_AUTO_CORRECTION to ManualTitle(R.string.autocorrect),
        Settings.PREF_MORE_AUTO_CORRECTION to ManualTitle(R.string.more_autocorrect),
        Settings.PREF_AUTOCORRECT_SHORTCUTS to ManualTitle(R.string.auto_correct_shortcuts),
        Settings.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION to ManualTitle(R.string.auto_correct_capitalized_suggestions),
        Settings.PREF_AUTO_CORRECT_CONFIDENCE to ManualTitle(R.string.auto_correction_confidence),
        Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT to ManualTitle(R.string.backspace_reverts_autocorrect),
        Settings.PREF_AUTO_CAP to ManualTitle(R.string.auto_cap),
        Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD to ManualTitle(R.string.use_double_space_period),
        Settings.PREF_SMART_QUOTES to ManualTitle(R.string.smart_quotes),
        Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION to ManualTitle(R.string.autospace_after_punctuation),
        Settings.PREF_AUTOSPACE_AFTER_SUGGESTION to ManualTitle(R.string.autospace_after_suggestion),
        Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING to ManualTitle(R.string.autospace_after_gesture_typing),
        Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING to ManualTitle(R.string.autospace_before_gesture_typing),
        Settings.PREF_SHIFT_REMOVES_AUTOSPACE to ManualTitle(R.string.shift_removes_autospace),
        Settings.PREF_SHOW_SUGGESTIONS to ManualTitle(R.string.prefs_show_suggestions),
        Settings.PREF_ALWAYS_SHOW_SUGGESTIONS to ManualTitle(R.string.prefs_always_show_suggestions),
        Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT to ManualTitle(R.string.prefs_always_show_suggestions_except_web_text),
        Settings.PREF_KEY_USE_PERSONALIZED_DICTS to ManualTitle(R.string.use_personalized_dicts),
        Settings.PREF_BIGRAM_PREDICTIONS to ManualTitle(R.string.bigram_prediction),
        Settings.PREF_SUGGEST_PUNCTUATION to ManualTitle(R.string.suggest_punctuation),
        Settings.PREF_PUNCTUATION_SUGGESTIONS to ManualTitle(R.string.custom_punctuation_suggestions),
        Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER to ManualTitle(R.string.center_suggestion_text_to_enter),
        Settings.PREF_SUGGEST_CLIPBOARD_CONTENT to ManualTitle(R.string.suggest_clipboard_content),
        Settings.PREF_SUGGEST_EMOJIS to ManualTitle(R.string.suggest_emojis),
        Settings.PREF_INLINE_EMOJI_SEARCH to ManualTitle(R.string.inline_emoji_search),
        Settings.PREF_ADD_TO_PERSONAL_DICTIONARY to ManualTitle(R.string.add_to_personal_dictionary),
        Settings.PREF_SPELLCHECK_SUGGEST to ManualTitle(R.string.spell_check_suggestions),
        Settings.PREF_TOOLBAR_MODE to ManualTitle(R.string.toolbar_mode),
        Settings.PREF_TOOLBAR_HIDING_GLOBAL to ManualTitle(R.string.toolbar_hiding_global),
        Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE to ManualTitle(R.string.toolbar_swipe_down_to_hide),
        Settings.PREF_TOOLBAR_KEYS to ManualTitle(R.string.toolbar_keys),
        Settings.PREF_PINNED_TOOLBAR_KEYS to ManualTitle(R.string.pinned_toolbar_keys),
        Settings.PREF_CLIPBOARD_TOOLBAR_KEYS to ManualTitle(R.string.clipboard_toolbar_keys),
        Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES to ManualTitle(R.string.customize_toolbar_key_codes),
        Settings.PREF_QUICK_PIN_TOOLBAR_KEYS to ManualTitle(R.string.quick_pin_toolbar_keys),
        Settings.PREF_AUTO_SHOW_TOOLBAR to ManualTitle(R.string.auto_show_toolbar),
        Settings.PREF_AUTO_HIDE_TOOLBAR to ManualTitle(R.string.auto_hide_toolbar),
        Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD to ManualTitle(R.string.toolbar_only_with_hw_keyboard),
        Settings.PREF_VARIABLE_TOOLBAR_DIRECTION to ManualTitle(R.string.var_toolbar_direction),
        // the Taen screens: their switches are drawn by the screen itself, not registered in SettingsContainer
        "incognito_apps" to ManualTitle(R.string.incognito_apps_title), // named by text: the P9 rule lets only the privacy code mention the constant (ManualCoverageTest keeps the two equal)
        Settings.PREF_CLIPBOARD_IN_SECURE_FIELDS to ManualTitle(R.string.clipboard_in_secure_fields_title),
        Settings.PREF_SECURE_KEYBOARD_WINDOW to ManualTitle(R.string.secure_keyboard_window_title),
        Settings.PREF_A11Y_WARNING to ManualTitle(R.string.a11y_warning_title),
        Settings.PREF_OVERLAY_MODE to ManualTitle(R.string.overlay_mode_title),
        Settings.PREF_VOICE_TYPING to ManualTitle(R.string.voice_key_title),
        Settings.PREF_MEDIA_STARTER_PACK to ManualTitle(R.string.media_starter_title),
        Settings.PREF_NEXTWORD_MODEL to ManualTitle(R.string.nextword_switch),
        SelfDestruct.PREF_PANIC_SHORTCUT to ManualTitle(R.string.self_destruct_panic_shortcut),
    )

    /** The secondary layouts (SecondaryLayoutScreen): one entry for each layout type but the main one, with the key the screen gives it. */
    private val layouts: Map<String, ManualTitle> = LayoutType.entries.filter { it != LayoutType.MAIN }
        .associate { (Settings.PREF_LAYOUT_PREFIX + it.name) to ManualTitle(it.displayNameId) }

    private val all: Map<String, ManualTitle> = fixed + layouts

    /** The keys that have a title. */
    val keys: Set<String> get() = all.keys

    operator fun get(key: String): ManualTitle? = all[key]
}
