// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.privacy.AppLanguageMemory
import helium314.keyboard.latin.privacy.ClipboardLimits
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.EmojiHold
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode

/** The three classes of a preference key for profiles, templates and the quick panel (P21). */
enum class KeyClass {
    /** Stored in profiles and templates, written by the quick panel. */
    INCLUDED,
    /** Stored too, but applying a value can only make it stricter, never looser. */
    PRIVACY,
    /** Never stored and never applied: user data, device state, free text, files, the profiles themselves. Also every key nobody classified. */
    EXCLUDED,
}

/** The type and range of one key: [canonical] returns the typed value (Boolean, Int, Float or String) or null when [raw] is not valid for the key. */
sealed class ValueSpec {
    abstract fun canonical(raw: Any?): Any?

    object Bool : ValueSpec() {
        override fun canonical(raw: Any?): Any? = raw as? Boolean
    }

    /** An integer from [min] to [max], or exactly [also] when it is given (a named value outside the range, such as Forever). */
    class IntRange(private val min: Int, private val max: Int, private val also: Int? = null) : ValueSpec() {
        override fun canonical(raw: Any?): Any? {
            val n = raw as? Number ?: return null
            val d = n.toDouble()
            if (also != null && d == also.toDouble()) return also
            if (d.isNaN() || d != Math.rint(d) || d < min || d > max) return null
            return d.toInt()
        }
    }

    class FloatRange(private val min: Float, private val max: Float) : ValueSpec() {
        override fun canonical(raw: Any?): Any? {
            val n = raw as? Number ?: return null
            val f = n.toFloat()
            if (f.isNaN() || f.isInfinite() || f < min || f > max) return null
            return f
        }
    }

    class Text(private val maxLength: Int, private val check: (String) -> Boolean = { true }) : ValueSpec() {
        override fun canonical(raw: Any?): Any? {
            val s = raw as? String ?: return null
            if (s.length > maxLength || s.any { it.isISOControl() } || !check(s)) return null
            return s
        }
    }
}

/** How a PRIVACY key is made stricter: [strict] is its strictest value, [stricterOf] picks the stricter one of two valid values. */
class PrivacyRule(val default: Any, val strict: Any, val spec: ValueSpec, val stricterOf: (Any, Any) -> Any)

/**
 * The only classification of preference keys (P21). Every PREF_ key of Settings.java is in exactly one of three classes; a key that is in none is
 * EXCLUDED (fail closed), and ProfileKeysTest fails until it is classified on purpose.
 */
object ProfileKeys {
    private val bool = ValueSpec.Bool
    private fun scale(min: Float = 0.1f, max: Float = 5f) = ValueSpec.FloatRange(min, max)
    private fun name(max: Int = 64) = ValueSpec.Text(max) { s -> s.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ' ' } }
    private val noCustomFile: (String) -> Boolean = { !it.contains("custom.") } // a custom layout is a file, and files are not in profiles
    private val toolbarList = ValueSpec.Text(2048) { s ->
        s.split(Separators.ENTRY).all { e ->
            val (k, v) = e.split(Separators.KV).let { it.getOrNull(0) to it.getOrNull(1) }
            k != null && (v == "true" || v == "false") && ToolbarKey.entries.any { it.name == k }
        }
    }
    private val oneOf = { values: Collection<String> -> ValueSpec.Text(64) { it in values } }

    /** Fixed INCLUDED keys with their value specs. */
    private val included: Map<String, ValueSpec> = LinkedHashMap<String, ValueSpec>().apply {
        for (k in listOf(
            Settings.PREF_ACCENT_SHIFTED_ICON, Settings.PREF_THEME_KEY_BORDERS, Settings.PREF_THEME_DAY_NIGHT, Settings.PREF_AUTO_CAP,
            Settings.PREF_VIBRATE_ON, Settings.PREF_VIBRATE_IN_DND_MODE, Settings.PREF_SOUND_ON, Settings.PREF_SUGGEST_EMOJIS,
            Settings.PREF_INLINE_EMOJI_SEARCH, Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, Settings.PREF_POPUP_ON, Settings.PREF_AUTO_CORRECTION,
            Settings.PREF_MORE_AUTO_CORRECTION, Settings.PREF_AUTOCORRECT_SHORTCUTS, Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT,
            Settings.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION, Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER, Settings.PREF_SHOW_SUGGESTIONS,
            Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT, Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD,
            Settings.PREF_SMART_QUOTES, Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, Settings.PREF_SHOW_EMOJI_KEY,
            Settings.PREF_SHOW_DPAD_KEY, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION, Settings.PREF_ENABLE_SPLIT_KEYBOARD,
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE,
            Settings.PREF_EMOJI_KEY_FIT, Settings.PREF_DELETE_SWIPE, Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, Settings.PREF_AUTOSPACE_AFTER_SUGGESTION,
            Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING, Settings.PREF_SHIFT_REMOVES_AUTOSPACE,
            Settings.PREF_BIGRAM_PREDICTIONS, Settings.PREF_SUGGEST_PUNCTUATION, Settings.PREF_GESTURE_INPUT, Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
            Settings.PREF_ENABLE_HARDWARE_KEYBOARD, Settings.PREF_GESTURE_PREVIEW_TRAIL, Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
            Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC, Settings.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM, Settings.PREF_GESTURE_SPACE_AWARE,
            Settings.PREF_SHOW_SETUP_WIZARD_ICON, Settings.PREF_SHOW_NUMBER_ROW, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, Settings.PREF_LOCALIZED_NUMBER_ROW,
            Settings.PREF_SHOW_NUMBER_ROW_HINTS, Settings.PREF_SHOW_HINTS, Settings.PREF_SHOW_POPUP_HINTS, Settings.PREF_SHOW_TLD_POPUP_KEYS,
            Settings.PREF_SPACE_TO_CHANGE_LANG, Settings.PREF_TOUCHPAD_EDGE_SCROLL, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, Settings.PREF_NAVBAR_COLOR,
            Settings.PREF_URL_DETECTION, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, Settings.PREF_AUTO_SHOW_TOOLBAR, Settings.PREF_AUTO_HIDE_TOOLBAR,
            Settings.PREF_ABC_AFTER_EMOJI, Settings.PREF_ABC_AFTER_CLIP, Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
            Settings.PREF_REMOVE_REDUNDANT_POPUPS, Settings.PREF_TOOLBAR_HIDING_GLOBAL, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, Settings.PREF_SPELLCHECK_SUGGEST,
            Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD,
        )) put(k, bool)
        put(Settings.PREF_AUTO_CORRECT_CONFIDENCE, scale(0f, 1f))
        put(Settings.PREF_KEYPRESS_SOUND_VOLUME, scale(-0.01f, 1f))
        for (k in listOf(Settings.PREF_FONT_SCALE, Settings.PREF_HINT_FONT_SCALE, Settings.PREF_EMOJI_FONT_SCALE)) put(k, scale(0.5f, 2.5f))
        put(Settings.PREF_VIBRATION_DURATION_SETTINGS, ValueSpec.IntRange(-1, 100))
        put(Settings.PREF_KEY_LONGPRESS_TIMEOUT, ValueSpec.IntRange(100, 700))
        put(Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, ValueSpec.IntRange(0, 500))
        put(Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, ValueSpec.IntRange(100, 1900))
        put(Settings.PREF_LANGUAGE_SWIPE_DISTANCE, ValueSpec.IntRange(2, 18))
        put(Settings.PREF_TOUCHPAD_SENSITIVITY, ValueSpec.IntRange(0, 100))
        for (k in listOf(Settings.PREF_THEME_STYLE, Settings.PREF_ICON_STYLE, Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT)) put(k, name())
        put(Settings.PREF_CUSTOM_ICON_NAMES, ValueSpec.Text(2048))
        put(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, ValueSpec.Text(2048))
        put(Settings.PREF_LANGUAGE_SWITCH_KEY, oneOf(listOf("internal", "input_method", "both")))
        put(Settings.PREF_EMOJI_HOLD, oneOf(EmojiHold.entries.map { it.pref })) // which key opens the emoji panel when held
        put(Settings.PREF_EMOJI_SKIN_TONE, ValueSpec.Text(16))
        for (k in listOf(Settings.PREF_SPACE_HORIZONTAL_SWIPE, Settings.PREF_SPACE_VERTICAL_SWIPE))
            put(k, oneOf(KeyboardActionListener.SwipeAction.entries.map { it.name }))
        put(Settings.PREF_POPUP_KEYS_ORDER, ValueSpec.Text(512))
        put(Settings.PREF_POPUP_KEYS_HINT_ORDER, ValueSpec.Text(512))
        put(Settings.PREF_MORE_POPUP_KEYS, name(32))
        put(Settings.PREF_PUNCTUATION_SUGGESTIONS, ValueSpec.Text(128))
        put(Settings.PREF_ADDITIONAL_SUBTYPES, ValueSpec.Text(4096, noCustomFile))
        put(Settings.PREF_ENABLED_SUBTYPES, ValueSpec.Text(2048, noCustomFile))
        for (k in listOf(Settings.PREF_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS)) put(k, toolbarList)
        put(Settings.PREF_TOOLBAR_MODE, oneOf(ToolbarMode.entries.map { it.name }))
    }

    private fun stricterBool(strict: Boolean) = { current: Any, proposed: Any -> if (current == strict || proposed == strict) strict else current }
    private fun lowerInt() = { current: Any, proposed: Any -> minOf(current as Int, proposed as Int) }
    private val overlayRank = listOf("off", "warn", "block")

    /** The PRIVACY keys (P21): a value can only move towards [PrivacyRule.strict]. */
    private val privacy: Map<String, PrivacyRule> = linkedMapOf(
        Settings.PREF_ALWAYS_INCOGNITO_MODE to PrivacyRule(Defaults.PREF_ALWAYS_INCOGNITO_MODE, true, bool, stricterBool(true)),
        Settings.PREF_SECURE_KEYBOARD_WINDOW to PrivacyRule(Defaults.PREF_SECURE_KEYBOARD_WINDOW, true, bool, stricterBool(true)),
        Settings.PREF_A11Y_WARNING to PrivacyRule(Defaults.PREF_A11Y_WARNING, true, bool, stricterBool(true)),
        Settings.PREF_OVERLAY_MODE to PrivacyRule(Defaults.PREF_OVERLAY_MODE, "block", oneOf(overlayRank)) { c, p ->
            if (overlayRank.indexOf(p as String) > overlayRank.indexOf(c as String)) p else c
        },
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY to PrivacyRule(Defaults.PREF_ENABLE_CLIPBOARD_HISTORY, false, bool, stricterBool(false)),
        Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME to PrivacyRule(
            Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, ClipboardLimits.MIN_RETENTION_MINUTES,
            // Forever is the largest value, so "lower is stricter" still holds
            ValueSpec.IntRange(ClipboardLimits.MIN_RETENTION_MINUTES, ClipboardLimits.MAX_RETENTION_MINUTES, ClipboardLimits.FOREVER_MINUTES), lowerInt()),
        Settings.PREF_CLIPBOARD_HISTORY_MAX_ITEMS to PrivacyRule(
            Defaults.PREF_CLIPBOARD_HISTORY_MAX_ITEMS, 1, ValueSpec.IntRange(1, ClipboardLimits.MAX_ITEMS), lowerInt()),
        Settings.PREF_SUGGEST_CLIPBOARD_CONTENT to PrivacyRule(Defaults.PREF_SUGGEST_CLIPBOARD_CONTENT, false, bool, stricterBool(false)),
        Settings.PREF_KEY_USE_PERSONALIZED_DICTS to PrivacyRule(Defaults.PREF_KEY_USE_PERSONALIZED_DICTS, false, bool, stricterBool(false)),
        Settings.PREF_ADD_TO_PERSONAL_DICTIONARY to PrivacyRule(Defaults.PREF_ADD_TO_PERSONAL_DICTIONARY, false, bool, stricterBool(false)),
        Settings.PREF_SAVE_SUBTYPE_PER_APP to PrivacyRule(Defaults.PREF_SAVE_SUBTYPE_PER_APP, false, bool, stricterBool(false)),
        Settings.PREF_VOICE_TYPING to PrivacyRule(Defaults.PREF_VOICE_TYPING, false, bool, stricterBool(false)),
        Settings.PREF_NEXTWORD_MODEL to PrivacyRule(Defaults.PREF_NEXTWORD_MODEL, false, bool, stricterBool(false)),
    )

    /** Keys that are never stored or applied, named on purpose (the rest of the unclassified ones are EXCLUDED too, but ProfileKeysTest fails for them). */
    private val excluded: Set<String> = setOf(
        INCOGNITO_APPS, Settings.PREF_SPACE_BAR_TEXT, Settings.PREF_VERSION_CODE, Settings.PREF_SETUP_GUIDE_DONE, Settings.PREF_EMOJI_MAX_SDK,
        Settings.PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG, Settings.PREF_RECENT_EMOJIS, Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID,
        Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID, Settings.PREF_SELECTED_SUBTYPE, Settings.PREF_CUSTOM_CURRENCY_KEY, Settings.PREF_TIMESTAMP_FORMAT,
        AppLanguageMemory.KEY, ProfileStore.KEY, Settings.PREF_MEDIA_STARTER_PACK,
    )

    private class PrefixRule(val prefix: String, val cls: KeyClass, val spec: ValueSpec?, val matches: (String) -> Boolean)

    private fun combos(n: Int): Set<String> = (0 until (1 shl n)).map { i -> (0 until n).joinToString("_") { ((i shr it) % 2 == 1).toString() } }.toSet()
    private fun comboRule(prefix: String, n: Int, spec: ValueSpec): PrefixRule {
        val valid = combos(n)
        return PrefixRule(prefix, KeyClass.INCLUDED, spec) { it.startsWith(prefix + "_") && it.removePrefix(prefix + "_") in valid }
    }
    private fun excludedPrefix(prefix: String) = PrefixRule(prefix, KeyClass.EXCLUDED, null) { it.startsWith(prefix) }

    private val prefixRules: List<PrefixRule> = listOf(
        comboRule(Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, 2, scale()),
        comboRule(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, 2, scale(HEIGHT_MIN, HEIGHT_MAX)),
        comboRule(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, 2, scale()),
        comboRule(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, 2, scale(0f, 5f)),
        comboRule(Settings.PREF_SIDE_PADDING_SCALE_PREFIX, 3, scale(0f, 5f)),
        comboRule(Settings.PREF_KEY_GAP_SCALE_PREFIX, 2, scale(0f, 5f)),
        comboRule(Settings.PREF_ONE_HANDED_MODE_PREFIX, 3, bool),
        comboRule(Settings.PREF_ONE_HANDED_GRAVITY_PREFIX, 3, ValueSpec.IntRange(1, 0xFFFF)),
        comboRule(Settings.PREF_ONE_HANDED_SCALE_PREFIX, 3, scale(0.5f, 2.5f)),
        // floating keyboard: the switch is per screen width (the key ends in the width in pixels); size and position are device geometry
        PrefixRule(Settings.PREF_FLOATING_ENABLED_PREFIX, KeyClass.INCLUDED, bool) { k -> k.removePrefix(Settings.PREF_FLOATING_ENABLED_PREFIX).let { it.length in 1..5 && it.all(Char::isDigit) } },
        PrefixRule(Settings.PREF_LAYOUT_PREFIX, KeyClass.INCLUDED, ValueSpec.Text(64, noCustomFile)) { k ->
            k.removePrefix(Settings.PREF_LAYOUT_PREFIX).let { t -> LayoutType.entries.any { it.name == t } }
        },
        excludedPrefix(Settings.PREF_USER_COLORS_PREFIX), excludedPrefix(Settings.PREF_USER_ALL_COLORS_PREFIX), excludedPrefix(Settings.PREF_USER_MORE_COLORS_PREFIX),
        excludedPrefix(Settings.PREF_FLOATING_HEIGHT_PREFIX), excludedPrefix(Settings.PREF_FLOATING_WIDTH_PREFIX),
        excludedPrefix(Settings.PREF_FLOATING_POS_X_PREFIX), excludedPrefix(Settings.PREF_FLOATING_POS_Y_PREFIX),
        excludedPrefix(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX),
    )

    // The per-app incognito list key, named by its text: the P9 rule lets only the privacy policy and its screen name the constant; ProfileKeysTest compares the two
    private const val INCOGNITO_APPS = "incognito_apps"

    /** Keyboard height scale limits of the settings slider and of the quick panel. */
    const val HEIGHT_MIN = 0.3f
    const val HEIGHT_MAX = 1.5f

    private fun matchPrefix(key: String): PrefixRule? = prefixRules.firstOrNull { it.matches(key) }

    fun classOf(key: String): KeyClass = when {
        key in included -> KeyClass.INCLUDED
        key in privacy -> KeyClass.PRIVACY
        key in excluded -> KeyClass.EXCLUDED
        else -> matchPrefix(key)?.cls ?: KeyClass.EXCLUDED
    }

    /** True when [key] was decided on purpose: a listed key, a valid key of a listed prefix, or a prefix itself. */
    fun isClassified(key: String): Boolean =
        key in included || key in privacy || key in excluded || prefixRules.any { it.prefix == key } || matchPrefix(key) != null

    /** The keys of [keys] nobody classified (the test of I3 uses it with the real Settings keys and with a planted one). */
    fun unclassified(keys: Collection<String>): List<String> = keys.filterNot(::isClassified)

    private fun specOf(key: String): ValueSpec? = included[key] ?: privacy[key]?.spec ?: matchPrefix(key)?.takeIf { it.cls == KeyClass.INCLUDED }?.spec

    /** The typed value of [raw] for [key], or null when the key is EXCLUDED or the value is not valid for it. */
    fun validate(key: String, raw: Any?): Any? = specOf(key)?.canonical(raw)

    val privacyKeys: List<String> = privacy.keys.toList()
    fun privacyRule(key: String): PrivacyRule? = privacy[key]
    fun privacyDefault(key: String): Any = privacy.getValue(key).default
    fun strictValue(key: String): Any = privacy.getValue(key).strict

    /** The stricter of [current] and [proposed] for a PRIVACY key; a value that is not valid for the key counts as not stricter ([current] stays). */
    fun stricter(key: String, current: Any, proposed: Any): Any {
        val rule = privacy.getValue(key)
        val c = rule.spec.canonical(current) ?: return rule.strict
        val p = rule.spec.canonical(proposed) ?: return c
        return rule.stricterOf(c, p)
    }

    /**
     * The INCLUDED keys a profile always records, so that applying it also resets a key that was at its default when it was saved: every fixed key
     * and every key of the prefixes that are indexed by booleans. Not in it: keys that only exist per screen width (the floating switch is added by
     * the caller) and the layout keys of the layout types (listed below).
     */
    val savedKeys: List<String> by lazy {
        val keys = LinkedHashSet<String>(included.keys)
        for (p in listOf(
            Settings.PREF_SPLIT_SPACER_SCALE_PREFIX to 2, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX to 2, Settings.PREF_BOTTOM_ROW_SCALE_PREFIX to 2,
            Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX to 2, Settings.PREF_SIDE_PADDING_SCALE_PREFIX to 3, Settings.PREF_KEY_GAP_SCALE_PREFIX to 2,
            Settings.PREF_ONE_HANDED_MODE_PREFIX to 3, Settings.PREF_ONE_HANDED_GRAVITY_PREFIX to 3, Settings.PREF_ONE_HANDED_SCALE_PREFIX to 3,
        )) combos(p.second).forEach { keys.add(p.first + "_" + it) }
        LayoutType.entries.forEach { keys.add(Settings.PREF_LAYOUT_PREFIX + it.name) }
        keys.toList()
    }

    /** The key of the keyboard height scale for the given screen state (Settings.readHeightScale: index bits landscape, folded). */
    fun heightKey(landscape: Boolean = false, folded: Boolean = false): String = Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX + "_$landscape" + "_$folded"

    /** The key of one-handed mode for portrait, not split, not folded (what the template uses). */
    fun oneHandedPortraitKey(): String = Settings.PREF_ONE_HANDED_MODE_PREFIX + "_false_false_false"

    /** The per-width floating switch key for a screen [widthPixels] wide. */
    fun floatingKey(widthPixels: Int): String = Settings.PREF_FLOATING_ENABLED_PREFIX + widthPixels
}
