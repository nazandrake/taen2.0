/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import helium314.keyboard.compat.ConfigurationCompatKt;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.internal.PopupKeySpec;
import helium314.keyboard.latin.AudioAndHapticFeedbackManager;
import helium314.keyboard.latin.InputAttributes;
import helium314.keyboard.latin.PunctuationSuggestions;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.common.StringUtils;
import helium314.keyboard.latin.privacy.AppLanguageMemory;
import helium314.keyboard.latin.privacy.Privacy;
import helium314.keyboard.latin.privacy.PrivacyPolicy;
import helium314.keyboard.compat.IsLockedCompatKt;
import helium314.keyboard.latin.utils.DeviceProtectedUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LayoutType;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.RunInLocaleKt;
import helium314.keyboard.latin.utils.StatsUtils;
import helium314.keyboard.latin.utils.SubtypeSettings;
import helium314.keyboard.latin.utils.ToolbarKey;
import helium314.keyboard.latin.utils.ToolbarUtilsKt;
import helium314.keyboard.latin.utils.ToolbarMode;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class Settings implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = Settings.class.getSimpleName();

    // theme-related stuff
    public static final String PREF_THEME_STYLE = "theme_style";
    public static final String PREF_ICON_STYLE = "icon_style";
    public static final String PREF_ACCENT_SHIFTED_ICON = "accent_shifted_icon";
    public static final String PREF_THEME_COLORS = "theme_colors";
    public static final String PREF_THEME_COLORS_NIGHT = "theme_colors_night";
    public static final String PREF_THEME_KEY_BORDERS = "theme_key_borders";
    public static final String PREF_THEME_DAY_NIGHT = "theme_auto_day_night";
    public static final String PREF_USER_COLORS_PREFIX = "user_colors_";
    public static final String PREF_USER_ALL_COLORS_PREFIX = "user_all_colors_";
    public static final String PREF_USER_MORE_COLORS_PREFIX = "user_more_colors_";

    public static final String PREF_CUSTOM_ICON_NAMES = "custom_icon_names";
    public static final String PREF_TOOLBAR_CUSTOM_KEY_CODES = "toolbar_custom_key_codes";
    public static final String PREF_LAYOUT_PREFIX = "layout_";

    public static final String PREF_AUTO_CAP = "auto_cap";
    public static final String PREF_VIBRATE_ON = "vibrate_on";
    public static final String PREF_VIBRATE_IN_DND_MODE = "vibrate_in_dnd_mode";
    public static final String PREF_SOUND_ON = "sound_on";
    public static final String PREF_SUGGEST_EMOJIS = "suggest_emojis";
    public static final String PREF_INLINE_EMOJI_SEARCH = "inline_emoji_search";
    public static final String PREF_SHOW_EMOJI_DESCRIPTIONS = "show_emoji_descriptions";
    public static final String PREF_POPUP_ON = "popup_on";
    public static final String PREF_AUTO_CORRECTION = "auto_correction";
    public static final String PREF_MORE_AUTO_CORRECTION = "more_auto_correction";
    public static final String PREF_AUTO_CORRECT_CONFIDENCE = "auto_correct_confidence";
    public static final String PREF_AUTOCORRECT_SHORTCUTS = "autocorrect_shortcuts";
    public static final String PREF_BACKSPACE_REVERTS_AUTOCORRECT = "backspace_reverts_autocorrect";
    public static final String PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION = "autocorrect_capitalized_suggestion";
    public static final String PREF_CENTER_SUGGESTION_TEXT_TO_ENTER = "center_suggestion_text_to_enter";
    public static final String PREF_SHOW_SUGGESTIONS = "show_suggestions";
    public static final String PREF_ALWAYS_SHOW_SUGGESTIONS = "always_show_suggestions";
    public static final String PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT = "always_show_suggestions_except_web_text";
    public static final String PREF_KEY_USE_PERSONALIZED_DICTS = "use_personalized_dicts";
    public static final String PREF_KEY_USE_DOUBLE_SPACE_PERIOD = "use_double_space_period";
    public static final String PREF_SMART_QUOTES = "smart_quotes";
    public static final String PREF_VIM_MODE = "vim_mode"; // Vim Normal and Visual mode on the keyboard (off until turned on)
    public static final String PREF_VIM_INDENT_TAB = "vim_indent_tab"; // Vim > and < indent with a tab (else four spaces)
    public static final String PREF_EMOJI_HOLD = "emoji_hold"; // the key that opens the emoji panel when held (comma, period, space, language, none)
    public static final String PREF_MEDIA_STARTER_PACK = "media_starter_pack"; // show the starter stickers of the APK in the picture panel (off until turned on)
    public static final String PREF_NEXTWORD_MODEL = "nextword_model"; // the downloaded next-word model orders the prediction strip (when one is installed and ready)
    public static final String PREF_VOICE_TYPING = "voice_typing"; // the voice key (off: the key is hidden and nothing records)
    public static final String PREF_BLOCK_POTENTIALLY_OFFENSIVE = "block_potentially_offensive";
    public static final String PREF_SHOW_LANGUAGE_SWITCH_KEY = "show_language_switch_key";
    public static final String PREF_LANGUAGE_SWITCH_KEY = "language_switch_key";
    public static final String PREF_SHOW_EMOJI_KEY = "show_emoji_key";
    public static final String PREF_SHOW_DPAD_KEY = "show_dpad_key";
   public static final String PREF_VARIABLE_TOOLBAR_DIRECTION = "var_toolbar_direction";
    public static final String PREF_ADDITIONAL_SUBTYPES = "additional_subtypes";
    public static final String PREF_ENABLE_SPLIT_KEYBOARD = "split_keyboard";
    public static final String PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE = "split_keyboard_landscape";
    public static final String PREF_ENABLE_SPLIT_KEYBOARD_FOLDED = "split_keyboard_folded";
    public static final String PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE = "split_keyboard_folded_landscape";
    public static final String PREF_SPLIT_SPACER_SCALE_PREFIX = "split_spacer_scale";
    public static final String PREF_KEYBOARD_HEIGHT_SCALE_PREFIX = "keyboard_height_scale";
    public static final String PREF_BOTTOM_ROW_SCALE_PREFIX = "bottom_row_scale";
    public static final String PREF_BOTTOM_PADDING_SCALE_PREFIX = "bottom_padding_scale";
    public static final String PREF_SIDE_PADDING_SCALE_PREFIX = "side_padding_scale";
    public static final String PREF_KEY_GAP_SCALE_PREFIX = "key_gap_scale";
    public static final String PREF_FLOATING_HEIGHT_PREFIX = "floating_height";
    public static final String PREF_FLOATING_WIDTH_PREFIX = "floating_width";
    public static final String PREF_FLOATING_ENABLED_PREFIX = "floating_enabled";
    public static final String PREF_FLOATING_POS_X_PREFIX = "floating_pos_x";
    public static final String PREF_FLOATING_POS_Y_PREFIX = "floating_pos_y";
    public static final String PREF_FONT_SCALE = "font_scale";
    public static final String PREF_HINT_FONT_SCALE = "hint_font_scale";
    public static final String PREF_EMOJI_FONT_SCALE = "emoji_font_scale";
    public static final String PREF_EMOJI_KEY_FIT = "emoji_key_fit";
    public static final String PREF_EMOJI_SKIN_TONE = "emoji_skin_tone";
    public static final String PREF_SPACE_HORIZONTAL_SWIPE = "horizontal_space_swipe";
    public static final String PREF_SPACE_VERTICAL_SWIPE = "vertical_space_swipe";
    public static final String PREF_DELETE_SWIPE = "delete_swipe";
    public static final String PREF_AUTOSPACE_AFTER_PUNCTUATION = "autospace_after_punctuation";
    public static final String PREF_AUTOSPACE_AFTER_SUGGESTION = "autospace_after_suggestion";
    public static final String PREF_AUTOSPACE_AFTER_GESTURE_TYPING = "autospace_after_gesture_typing";
    public static final String PREF_AUTOSPACE_BEFORE_GESTURE_TYPING = "autospace_before_gesture_typing";
    public static final String PREF_SHIFT_REMOVES_AUTOSPACE = "shift_removes_autospace";
    public static final String PREF_ALWAYS_INCOGNITO_MODE = "always_incognito_mode";
    // Anti-snooping settings (see PrivacyPolicy, OverlayGuard, A11yWatch, SecureWindow); the app list is kept in the credential-encrypted preferences
    public static final String PREF_INCOGNITO_APPS = "incognito_apps";
    public static final String PREF_SECURE_KEYBOARD_WINDOW = "secure_keyboard_window";
    public static final String PREF_A11Y_WARNING = "accessibility_warning";
    public static final String PREF_OVERLAY_MODE = "overlay_mode";
    public static final String PREF_BIGRAM_PREDICTIONS = "next_word_prediction";
    public static final String PREF_SUGGEST_PUNCTUATION = "suggest_punctuation";
    public static final String PREF_PUNCTUATION_SUGGESTIONS = "punctuation_suggestions";
    public static final String PREF_SUGGEST_CLIPBOARD_CONTENT = "suggest_clipboard_content";
    public static final String PREF_CLIPBOARD_IN_SECURE_FIELDS = "clipboard_in_secure_fields";
    public static final String PREF_GESTURE_INPUT = "gesture_input";
    public static final String PREF_VIBRATION_DURATION_SETTINGS = "vibration_duration_settings";
    public static final String PREF_KEYPRESS_SOUND_VOLUME = "keypress_sound_volume";
    public static final String PREF_KEY_LONGPRESS_TIMEOUT = "key_longpress_timeout";
    public static final String PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY = "enable_emoji_alt_physical_key";
    public static final String PREF_ENABLE_HARDWARE_KEYBOARD = "enable_hardware_keyboard";
    public static final String PREF_GESTURE_PREVIEW_TRAIL = "gesture_preview_trail";
    public static final String PREF_GESTURE_FLOATING_PREVIEW_TEXT = "gesture_floating_preview_text";
    public static final String PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC = "gesture_floating_preview_dynamic";
    public static final String PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = "gesture_dynamic_preview_follow_system";
    public static final String PREF_GESTURE_SPACE_AWARE = "gesture_space_aware";
    public static final String PREF_GESTURE_FAST_TYPING_COOLDOWN = "gesture_fast_typing_cooldown";
    public static final String PREF_GESTURE_TRAIL_FADEOUT_DURATION = "gesture_trail_fadeout_duration";
    public static final String PREF_SHOW_SETUP_WIZARD_ICON = "show_setup_wizard_icon";
    public static final String PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD = "long_press_symbols_for_numpad";

    public static final String PREF_ONE_HANDED_MODE_PREFIX = "one_handed_mode_enabled";
    public static final String PREF_ONE_HANDED_GRAVITY_PREFIX = "one_handed_mode_gravity";
    public static final String PREF_ONE_HANDED_SCALE_PREFIX = "one_handed_mode_scale";

    public static final String PREF_SHOW_NUMBER_ROW = "show_number_row";
    public static final String PREF_SHOW_NUMBER_ROW_IN_SYMBOLS = "show_number_row_in_symbols";
    public static final String PREF_LOCALIZED_NUMBER_ROW = "localized_number_row";
    public static final String PREF_SHOW_NUMBER_ROW_HINTS = "show_number_row_hints";
    public static final String PREF_CUSTOM_CURRENCY_KEY = "custom_currency_key";

    public static final String PREF_SHOW_HINTS = "show_hints";
    public static final String PREF_POPUP_KEYS_ORDER = "popup_keys_order";
    public static final String PREF_POPUP_KEYS_HINT_ORDER = "popup_keys_labels_order";
    public static final String PREF_SHOW_POPUP_HINTS = "show_popup_hints";
    public static final String PREF_MORE_POPUP_KEYS = "more_popup_keys";
    public static final String PREF_SHOW_TLD_POPUP_KEYS = "show_tld_popup_keys";

    public static final String PREF_SPACE_TO_CHANGE_LANG = "prefs_long_press_keyboard_to_change_lang";
    public static final String PREF_LANGUAGE_SWIPE_DISTANCE = "language_swipe_distance";
    public static final String PREF_TOUCHPAD_SENSITIVITY = "touchpad_sensitivity";
    public static final String PREF_TOUCHPAD_EDGE_SCROLL = "touchpad_edge_scroll";

    public static final String PREF_ENABLE_CLIPBOARD_HISTORY = "enable_clipboard_history";
    public static final String PREF_CLIPBOARD_HISTORY_RETENTION_TIME = "clipboard_history_retention_time";
    public static final String PREF_CLIPBOARD_HISTORY_PINNED_FIRST = "clipboard_history_pinned_first";
    public static final String PREF_CLIPBOARD_HISTORY_MAX_ITEMS = "clipboard_history_max_items";

    public static final String PREF_ADD_TO_PERSONAL_DICTIONARY = "add_to_personal_dictionary";
    public static final String PREF_NAVBAR_COLOR = "navbar_color";
    public static final String PREF_ENABLED_SUBTYPES = "enabled_subtypes";
    public static final String PREF_SELECTED_SUBTYPE = "selected_subtype";
    public static final String PREF_URL_DETECTION = "url_detection";
    public static final String PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG = "dont_show_missing_dict_dialog";
    public static final String PREF_QUICK_PIN_TOOLBAR_KEYS = "quick_pin_toolbar_keys";
    public static final String PREF_PINNED_TOOLBAR_KEYS = "pinned_toolbar_keys";
    public static final String PREF_TOOLBAR_KEYS = "toolbar_keys";
    public static final String PREF_AUTO_SHOW_TOOLBAR = "auto_show_toolbar";
    public static final String PREF_AUTO_HIDE_TOOLBAR = "auto_hide_toolbar";
    public static final String PREF_CLIPBOARD_TOOLBAR_KEYS = "clipboard_toolbar_keys";
    public static final String PREF_ABC_AFTER_EMOJI = "abc_after_emoji";
    public static final String PREF_ABC_AFTER_CLIP = "abc_after_clip";
    public static final String PREF_ABC_AFTER_SYMBOL_SPACE = "abc_after_symbol_space";
    public static final String PREF_ABC_AFTER_NUMPAD_SPACE = "abc_after_numpad_space";
    public static final String PREF_REMOVE_REDUNDANT_POPUPS = "remove_redundant_popups";
    public static final String PREF_SPACE_BAR_TEXT = "space_bar_text";
    public static final String PREF_TIMESTAMP_FORMAT = "timestamp_format";
    public static final String PREF_TOOLBAR_MODE = "toolbar_mode";
    public static final String PREF_TOOLBAR_HIDING_GLOBAL = "toolbar_hiding_global";
    public static final String PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE = "toolbar_swipe_down_to_hide";
    public static final String PREF_SPELLCHECK_SUGGEST = "spellcheck_suggest";
    public static final String PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD = "only_toolbar_with_hw_keyboard";

    // Emoji
    public static final String PREF_EMOJI_MAX_SDK = "emoji_max_sdk";
    public static final String PREF_RECENT_EMOJIS = "recent_emojis";
    public static final String PREF_LAST_SHOWN_EMOJI_CATEGORY_ID = "last_shown_emoji_category_id";
    public static final String PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID = "last_shown_emoji_category_page_id";

    public static final String PREF_VERSION_CODE = "version_code";
    public static final String PREF_SETUP_GUIDE_DONE = "setup_guide_done"; // 1.1.0: the first-run guide was shown (skipped or finished); a wipe of the settings brings it back
    public static final String PREF_WHATS_NEW_SHOWN = "whats_new_shown"; // 2.0.3: the version code of the "What's new" box that was shown last (one int; a wipe of the settings shows it again after an update)
    public static final String PREF_SAVE_SUBTYPE_PER_APP ="save_subtype_per_app";
    public static final String PREF_SAVED_APP_SUBTYPE_PREFIX = "saved_app_subtype_";

    private Context mContext;
    private SharedPreferences mPrefs;
    private SettingsValues mSettingsValues;
    private final ReentrantLock mSettingsValuesLock = new ReentrantLock();

    /** A layer that must notice a reload of the settings (the privacy decision of the field is part of them). */
    public interface ReloadHook {
        /** [before] is null at the first load. Called on the thread that reloaded, after the new values are in place. */
        void onReload(@Nullable SettingsValues before, @NonNull SettingsValues after);
    }

    private final ConcurrentHashMap<String, ReloadHook> mReloadHooks = new ConcurrentHashMap<>();

    /** Registers [hook] (replacing an earlier one with the same [name]) to run after every reload of the settings. */
    public void onReload(@NonNull final String name, @NonNull final ReloadHook hook) {
        mReloadHooks.put(name, hook);
    }

    /** Removes the hook registered under [name]: a layer of a service that is destroyed must not be kept alive by the settings, which outlive it. */
    public void removeReloadHook(@NonNull final String name) {
        mReloadHooks.remove(name);
    }

    // static cache for background images to avoid potentially slow reload on every settings reload
    private final static Drawable[] sCachedBackgroundImages = new Drawable[4];

    private static final Settings sInstance = new Settings();

    // preferences that are not used in SettingsValues and thus should not trigger reload when changed
    private static boolean reloadOnChanged(String key) {
        return switch (key) {
            case PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID, PREF_LAST_SHOWN_EMOJI_CATEGORY_ID, PREF_RECENT_EMOJIS,
                 PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG, PREF_SELECTED_SUBTYPE -> false;
            default -> !key.startsWith(PREF_SAVED_APP_SUBTYPE_PREFIX) && !key.startsWith("floating_pos");
        };
    }

    public static Settings getInstance() {
        return sInstance;
    }

    public static SettingsValues getValues() {
        return sInstance.mSettingsValues;
    }

    public static Context getCurrentContext() {
        return sInstance.mContext;
    }

    public static void init(final Context context) {
        sInstance.onCreate(context);
    }

    private Settings() {
        // Intentional empty constructor for singleton.
    }

    private void onCreate(final Context context) {
        mContext = context;
        mPrefs = KtxKt.prefs(context);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    public void onDestroy() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (PREF_SAVE_SUBTYPE_PER_APP.equals(key) && !isSubtypePerApp()) clearAppLanguages(mContext); // off: the list is forgotten
        if (key != null && !reloadOnChanged(key))
            return;
        mSettingsValuesLock.lock();
        try {
            if (mSettingsValues == null) {
                Log.w(TAG, "onSharedPreferenceChanged called before loadSettings.");
                return;
            }
            ToolbarUtilsKt.clearCustomToolbarKeyCodes();
            loadSettings(mContext, mSettingsValues.mLocale, mSettingsValues.mInputAttributes);
            StatsUtils.onLoadSettings(mSettingsValues);
        } finally {
            mSettingsValuesLock.unlock();
        }
        if (PREF_ADDITIONAL_SUBTYPES.equals(key)) {
            SubtypeSettings.INSTANCE.reloadEnabledSubtypes(mContext);
        }
    }

    /** convenience function for the rare situations where we need to load settings but may not have a keyboard */
    public void loadSettings(final Context context) {
        if (mSettingsValues != null) return;
        final Locale locale = ConfigurationCompatKt.locale(context.getResources().getConfiguration());
        final InputAttributes inputAttributes = new InputAttributes(new EditorInfo(), false, context.getPackageName());
        loadSettings(context, locale, inputAttributes);
    }

    public void loadSettings(final Context context, final Locale locale,
                             @NonNull final InputAttributes inputAttributes) {
        final SettingsValues before;
        final SettingsValues after;
        mSettingsValuesLock.lock();
        mContext = context;
        try {
            before = mSettingsValues;
            final SharedPreferences prefs = mPrefs;
            Log.i(TAG, "loadSettings");
            mSettingsValues = RunInLocaleKt.runInLocale(context, locale,
                    ctx -> new SettingsValues(ctx, prefs, ctx.getResources(), inputAttributes));
            after = mSettingsValues;
        } finally {
            mSettingsValuesLock.unlock();
        }
        for (final ReloadHook hook : mReloadHooks.values()) {
            try {
                hook.onReload(before, after);
            } catch (final RuntimeException e) {
                // a layer that fails to notice must not break the settings; each hook guards its own work
            }
        }
    }

    public void stopListener() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    public void startListener() {
        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    public SettingsValues getCurrent() {
        return mSettingsValues;
    }

    public static int readScreenMetrics(final Resources res) {
        return res.getInteger(R.integer.config_screen_metrics);
    }

    public static boolean readVibrationEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)
                && AudioAndHapticFeedbackManager.getInstance().hasVibrator();
    }

    public void toggleAutoCorrect() {
        final boolean oldValue = mPrefs.getBoolean(PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION);
        mPrefs.edit().putBoolean(Settings.PREF_AUTO_CORRECTION, !oldValue).apply();
    }

    public static boolean readGestureDynamicPreviewEnabled(final SharedPreferences prefs) {
        final boolean followSystem = prefs.getBoolean(PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM, Defaults.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM);
        final boolean defValue = Defaults.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM;
        final boolean curValue = prefs.getBoolean(Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC, defValue);
        return followSystem ? defValue : curValue;
    }

    public static boolean readGestureDynamicPreviewDefault(final Context context) {
        // if transitions are disabled for the system (reduced motion), moving preview should be disabled
        return SettingsKt.getTransitionAnimationScale(context) != 0.0f;
    }

    public static int readDefaultGestureFastTypingCooldown(final Resources res) {
        return res.getInteger(R.integer.config_gesture_static_time_threshold_after_fast_typing);
    }

    public void toggleAlwaysIncognitoMode() {
        final boolean oldValue = mPrefs.getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE);
        mPrefs.edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, !oldValue).apply();
    }

    public static ToolbarMode readToolbarMode(final SharedPreferences prefs) {
        return ToolbarMode.valueOf(prefs.getString(PREF_TOOLBAR_MODE, Defaults.PREF_TOOLBAR_MODE));
    }

    public static KeyboardActionListener.SwipeAction readHorizontalSpaceSwipe(SharedPreferences prefs) {
        try {
            String value = prefs.getString(PREF_SPACE_HORIZONTAL_SWIPE, Defaults.PREF_SPACE_HORIZONTAL_SWIPE);
            return KeyboardActionListener.SwipeAction.valueOf(value);
        } catch (IllegalArgumentException e) {
            return KeyboardActionListener.SwipeAction.NONE;
        }
    }

    public static KeyboardActionListener.SwipeAction readVerticalSpaceSwipe(SharedPreferences prefs) {
        try {
            String value = prefs.getString(PREF_SPACE_VERTICAL_SWIPE, Defaults.PREF_SPACE_VERTICAL_SWIPE);
            return KeyboardActionListener.SwipeAction.valueOf(value);
        } catch (IllegalArgumentException e) {
            return KeyboardActionListener.SwipeAction.NONE;
        }
    }

    public static boolean readFullscreenModeAllowed(final Resources res) {
        return res.getBoolean(R.bool.config_fullscreen_mode_allowed);
    }

    public static boolean readShowSetupWizardIcon(final SharedPreferences prefs,
                                                  final Context context) {
        if (!prefs.contains(PREF_SHOW_SETUP_WIZARD_ICON)) {
            final ApplicationInfo appInfo = context.getApplicationInfo();
            final boolean isApplicationInSystemImage =
                    (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // Default value
            return !isApplicationInSystemImage;
        }
        return prefs.getBoolean(PREF_SHOW_SETUP_WIZARD_ICON, Defaults.PREF_SHOW_SETUP_WIZARD_ICON);
    }

    public static boolean readOneHandedModeEnabled(SharedPreferences prefs, boolean landscape, boolean split, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, split, folded);
        String key = SettingsKt.createPrefKeyForBooleanSettings(PREF_ONE_HANDED_MODE_PREFIX, index, 3);
        return prefs.getBoolean(key, Defaults.PREF_ONE_HANDED_MODE);
    }

    public void writeOneHandedModeEnabled(final boolean enabled) {
        final boolean landscape = mSettingsValues.mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE;
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, mSettingsValues.mIsSplitKeyboardEnabled, FoldableUtils.INSTANCE.isFolded());
        String key = SettingsKt.createPrefKeyForBooleanSettings(PREF_ONE_HANDED_MODE_PREFIX, index, 3);
        mPrefs.edit().putBoolean(key, enabled).apply();
    }

    public static float readOneHandedModeScale(SharedPreferences prefs, boolean landscape, boolean split, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, split, folded);
        String key = SettingsKt.createPrefKeyForBooleanSettings(PREF_ONE_HANDED_SCALE_PREFIX, index, 3);
        return prefs.getFloat(key, Defaults.PREF_ONE_HANDED_SCALE);
    }

    public void writeOneHandedModeScale(final Float scale) {
        final boolean landscape = mSettingsValues.mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE;
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, mSettingsValues.mIsSplitKeyboardEnabled, FoldableUtils.INSTANCE.isFolded());
        String key = SettingsKt.createPrefKeyForBooleanSettings(PREF_ONE_HANDED_SCALE_PREFIX, index, 3);
        mPrefs.edit().putFloat(key, scale).apply();
    }

    public static int readOneHandedModeGravity(SharedPreferences prefs, boolean landscape, boolean split, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, split, folded);
        String key = SettingsKt.createPrefKeyForBooleanSettings(PREF_ONE_HANDED_GRAVITY_PREFIX, index, 3);
        return prefs.getInt(key, Defaults.PREF_ONE_HANDED_GRAVITY);
    }

    public void writeOneHandedModeGravity(final int gravity) {
        final boolean landscape = mSettingsValues.mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE;
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, mSettingsValues.mIsSplitKeyboardEnabled, FoldableUtils.INSTANCE.isFolded());
        String key = SettingsKt.createPrefKeyForBooleanSettings(PREF_ONE_HANDED_GRAVITY_PREFIX, index, 3);
        mPrefs.edit().putInt(key, gravity).apply();
    }

    public void writeSplitKeyboardEnabled(boolean enabled, boolean isLandscape, boolean isFolded) {
        String pref = isLandscape
                        ? (isFolded ? PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE : PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE)
                        : (isFolded ? PREF_ENABLE_SPLIT_KEYBOARD_FOLDED : PREF_ENABLE_SPLIT_KEYBOARD);
        mPrefs.edit().putBoolean(pref, enabled).apply();
    }

    public static boolean readSplitKeyboardEnabled(SharedPreferences prefs, boolean isLandscape, boolean isFolded) {
        String pref = isLandscape
                      ? (isFolded ? PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE : PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE)
                      : (isFolded ? PREF_ENABLE_SPLIT_KEYBOARD_FOLDED : PREF_ENABLE_SPLIT_KEYBOARD);
        return prefs.getBoolean(pref, Defaults.PREF_ENABLE_SPLIT_KEYBOARD);
    }

    public static float readSplitSpacerScale(SharedPreferences prefs, boolean landscape, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, folded);
        final Float[] defaults = Defaults.PREF_SPLIT_SPACER_SCALE;
        final float defaultValue = defaults[index];
        return prefs.getFloat(SettingsKt.createPrefKeyForBooleanSettings(PREF_SPLIT_SPACER_SCALE_PREFIX, index, 2), defaultValue);
    }

    public static float readBottomPaddingScale(SharedPreferences prefs, boolean landscape, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, folded);
        final Float[] defaults = Defaults.PREF_BOTTOM_PADDING_SCALE;
        final float defaultValue = defaults[index];
        return prefs.getFloat(SettingsKt.createPrefKeyForBooleanSettings(PREF_BOTTOM_PADDING_SCALE_PREFIX, index, 2), defaultValue);
    }

    public static float readSidePaddingScale(SharedPreferences prefs, boolean landscape, boolean split, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, split, folded);
        final Float[] defaults = Defaults.PREF_SIDE_PADDING_SCALE;
        final float defaultValue = defaults[index];
        return prefs.getFloat(SettingsKt.createPrefKeyForBooleanSettings(PREF_SIDE_PADDING_SCALE_PREFIX, index, 3), defaultValue);
    }

    public static float readHeightScale(SharedPreferences prefs, boolean landscape, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, folded);
        final Float[] defaults = Defaults.PREF_KEYBOARD_HEIGHT_SCALE;
        final float defaultValue = defaults[index];
        return prefs.getFloat(SettingsKt.createPrefKeyForBooleanSettings(PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, index, 2), defaultValue);
    }

    public static float readBottomRowScale(SharedPreferences prefs, boolean landscape, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, folded);
        final Float[] defaults = Defaults.PREF_BOTTOM_ROW_SCALE;
        final float defaultValue = defaults[index];
        return prefs.getFloat(SettingsKt.createPrefKeyForBooleanSettings(PREF_BOTTOM_ROW_SCALE_PREFIX, index, 2), defaultValue);
    }

    public static float readKeyGapScale(SharedPreferences prefs, boolean landscape, boolean folded) {
        int index = SettingsKt.findIndexOfDefaultSetting(landscape, folded);
        float defaultValue = Defaults.PREF_KEY_GAP_SCALE[index];
        return prefs.getFloat(SettingsKt.createPrefKeyForBooleanSettings(PREF_KEY_GAP_SCALE_PREFIX, index, 2), defaultValue);
    }

    public static boolean readHasHardwareKeyboard(final Configuration conf) {
        // The standard way of finding out whether we have a hardware keyboard. This code is taken
        // from InputMethodService#onEvaluateInputShown, which canonically determines this.
        // In a nutshell, we have a keyboard if the configuration says the type of hardware keyboard
        // is NOKEYS and if it's not hidden (e.g. folded inside the device).
        return conf.keyboard != Configuration.KEYBOARD_NOKEYS
                && conf.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
    }

    public boolean readShowToolbarOnly() {
        return mSettingsValues.mHasHardwareKeyboard
            && mPrefs.getBoolean(PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD, Defaults.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD);
    }

    @Nullable public static Drawable readUserBackgroundImage(final Context context, final boolean night) {
        final boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        final int index = (night ? 1 : 0) + (landscape ? 2 : 0);
        if (sCachedBackgroundImages[index] != null) return sCachedBackgroundImages[index];

        // the image is only stored encrypted, see Privacy.readBackground
        byte[] image = Privacy.INSTANCE.readBackground(context, night, landscape);
        if (image == null && landscape)
            image = Privacy.INSTANCE.readBackground(context, night, false); // fall back to portrait image for historic reasons
        if (image == null) return null;
        try {
            sCachedBackgroundImages[index] = new BitmapDrawable(context.getResources(), BitmapFactory.decodeByteArray(image, 0, image.length));
            return sCachedBackgroundImages[index];
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearCachedBackgroundImages() {
        Arrays.fill(sCachedBackgroundImages, null);
    }

    public static Context getDayNightContext(final Context context, final boolean wantNight) {
        final boolean isNight = ResourceUtils.isNight(context.getResources());
        if (isNight == wantNight)
            return context;
        final Configuration config = new Configuration(context.getResources().getConfiguration());
        final int night = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        final int uiModeWithNightBitsZero = config.uiMode - night;
        config.uiMode = uiModeWithNightBitsZero + (wantNight ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        final ContextThemeWrapper wrapper = new ContextThemeWrapper(context, R.style.platformActivityTheme);
        wrapper.applyOverrideConfiguration(config);
        return wrapper;
    }

    public boolean isTablet() {
        return mContext.getResources().getInteger(R.integer.config_screen_metrics) >= 3;
    }

    @SuppressLint("DiscouragedApi")
    public int getStringResIdByName(final String name) {
        return mContext.getResources().getIdentifier(name, "string", mContext.getPackageName());
    }

    public String getInLocale(@StringRes final int resId, final Locale locale) {
        return RunInLocaleKt.runInLocale(mContext, locale, (ctx) -> ctx.getString(resId));
    }

    public String readCustomCurrencyKey() {
        return mPrefs.getString(PREF_CUSTOM_CURRENCY_KEY, Defaults.PREF_CUSTOM_CURRENCY_KEY);
    }

    public Integer getCustomToolbarKeyCode(ToolbarKey key) {
        return ToolbarUtilsKt.getCustomKeyCode(key, mPrefs);
    }

    public Integer getCustomToolbarLongpressCode(ToolbarKey key) {
        return ToolbarUtilsKt.getCustomLongpressKeyCode(key, mPrefs);
    }

    public static File getCustomFontFile(final Context context) {
        return new File(DeviceProtectedUtils.getFilesDir(context), "custom_font");
    }

    public static File getCustomEmojiFontFile(final Context context) {
        return new File(DeviceProtectedUtils.getFilesDir(context), "custom_emoji_font");
    }

    // "default" layout as in this is used if nothing else is specified in the subtype
    public static String readDefaultLayoutName(final LayoutType type, final SharedPreferences prefs) {
        return prefs.getString(PREF_LAYOUT_PREFIX + type.name(), Defaults.INSTANCE.getDefault(type));
    }

    public static void writeDefaultLayoutName(@Nullable final String name, final LayoutType type, final SharedPreferences prefs) {
        if (name == null) prefs.edit().remove(PREF_LAYOUT_PREFIX + type.name()).apply();
        else prefs.edit().putString(PREF_LAYOUT_PREFIX + type.name(), name).apply();
    }

    /**
     * "Remember language per app" (P7, P9): remembers [subtype] as the language of the app of [editorInfo]. The map is one value of the credential-encrypted
     * preferences (AppLanguageMemory, newest 200 apps). Nothing is recorded in a password field, in a field that asks for no personalized learning, for an app on
     * the incognito list or while the device or the screen is locked: the decision is PrivacyPolicy's (SettingsValues.decidePrivacy), restoring needs no decision.
     */
    public void saveSubtypeForApp(RichInputMethodSubtype subtype, @Nullable EditorInfo editorInfo) {
        final String packageName = editorInfo == null ? null : editorInfo.packageName;
        if (!isSubtypePerApp() || StringUtils.isEmpty(packageName) || !mayRememberLanguage(editorInfo)) return;
        AppLanguageMemory.INSTANCE.remember(KtxKt.protectedPrefs(mContext), packageName,
                SettingsSubtype.Companion.toSettingsSubtype(subtype.getRawSubtype()).toPref());
    }

    /** The first time an app is seen it gets the language in use now, so that a later change elsewhere does not carry over to it. */
    public void rememberSubtypeForAppIfNew(RichInputMethodSubtype subtype, @Nullable EditorInfo editorInfo) {
        final String packageName = editorInfo == null ? null : editorInfo.packageName;
        if (!isSubtypePerApp() || StringUtils.isEmpty(packageName) || !mayRememberLanguage(editorInfo)) return;
        final SharedPreferences ce = KtxKt.protectedPrefs(mContext);
        if (AppLanguageMemory.INSTANCE.recall(ce, packageName) != null) return;
        AppLanguageMemory.INSTANCE.remember(ce, packageName, SettingsSubtype.Companion.toSettingsSubtype(subtype.getRawSubtype()).toPref());
    }

    private boolean mayRememberLanguage(EditorInfo editorInfo) {
        final boolean locked = IsLockedCompatKt.isDeviceLocked(mContext);
        return !SettingsValues.decidePrivacy(mContext, mPrefs, new InputAttributes(editorInfo, false, mContext.getPackageName()), locked).getIncognito();
    }

    /** Forgets the per-app language list and the entries of the earlier format (the setting turned off, the "Clear app language list" action). */
    public static void clearAppLanguages(final Context context) {
        AppLanguageMemory.INSTANCE.clear(KtxKt.protectedPrefs(context));
        AppLanguageMemory.INSTANCE.clearLegacy(KtxKt.prefs(context));
    }

    /** Stores the per-app incognito list and forgets the languages of the apps on it (the one writer of the list). */
    public static void setIncognitoApps(final Context context, final Set<String> apps) {
        final SharedPreferences ce = KtxKt.protectedPrefs(context);
        ce.edit().putString(PREF_INCOGNITO_APPS, PrivacyPolicy.INSTANCE.formatApps(apps)).apply();
        AppLanguageMemory.INSTANCE.removeApps(ce, apps);
    }

    /**
     * At the start of the app and at every unlock (Privacy.openAtStart, Privacy.unlock), with the credential-encrypted tier open: the entries of the earlier
     * per-app format are deleted and moved only when the setting is on and the app is not on the incognito list; with the setting off the list is cleared
     * too (a clear that could not be written when it was turned off), and the apps on the incognito list are removed from it.
     */
    public static void settleAppLanguages(final Context context) {
        final SharedPreferences de = KtxKt.prefs(context);
        final SharedPreferences ce = KtxKt.protectedPrefs(context);
        final Set<String> incognito = PrivacyPolicy.INSTANCE.parseApps(ce.getString(PREF_INCOGNITO_APPS, Defaults.PREF_INCOGNITO_APPS));
        if (!de.getBoolean(PREF_SAVE_SUBTYPE_PER_APP, Defaults.PREF_SAVE_SUBTYPE_PER_APP)) {
            clearAppLanguages(context);
            return;
        }
        AppLanguageMemory.INSTANCE.settleLegacy(de, ce, true, incognito);
        AppLanguageMemory.INSTANCE.removeApps(ce, incognito);
    }

    public RichInputMethodSubtype getSubtypeForApp(String packageName) {
        if (! isSubtypePerApp() || StringUtils.isEmpty(packageName)) return null;
        final SharedPreferences ce = KtxKt.protectedPrefs(mContext); // empty while locked: nothing is restored then
        var subtypePref = AppLanguageMemory.INSTANCE.recall(ce, packageName);
        if (subtypePref == null) return null;
        var settingsSubtype = SettingsSubtype.Companion.toSettingsSubtype(subtypePref);
        var subtype = settingsSubtype.toEnabledSubtype();
        if (subtype == null) subtype = RichInputMethodManager.getInstance().findSubtypeForHintLocale(settingsSubtype.getLocale());
        return subtype != null? RichInputMethodSubtype.Companion.get(subtype) : null;
    }

    private boolean isSubtypePerApp() {
        return mPrefs.getBoolean(PREF_SAVE_SUBTYPE_PER_APP, Defaults.PREF_SAVE_SUBTYPE_PER_APP);
    }

    public static PunctuationSuggestions readPunctuationSuggestions(Context context) {
        SharedPreferences prefs = KtxKt.prefs(context);
        String[] suggestPuncsSpec = prefs.contains(PREF_PUNCTUATION_SUGGESTIONS)
            ? prefs.getString(PREF_PUNCTUATION_SUGGESTIONS, "").split("\\s+")
            : PopupKeySpec.splitKeySpecs(context.getString(R.string.suggested_punctuations));
        return PunctuationSuggestions.newPunctuationSuggestions(suggestPuncsSpec);
    }
}
