// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import android.content.SharedPreferences
import helium314.keyboard.latin.profiles.ProfileKeys
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.EmojiHold
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import kotlin.math.roundToInt

/** The nine controls of the quick settings panel (toolbar key QUICK_SETTINGS). */
enum class QuickControl { NUMBER_ROW, ONE_HANDED, SPLIT, FLOATING, HAPTICS, SOUND, GLIDE, EMOJI_KEY, HEIGHT }

/** The screen state that picks the preference key of a control (orientation, split, folded, width in pixels of this screen). */
class ScreenState(val landscape: Boolean, val split: Boolean, val folded: Boolean, val widthPixels: Int)

/**
 * The quick settings panel (P21): the options changed most often, reachable from the keyboard without opening the settings. Each control writes exactly
 * ONE preference key, and every one of them is an INCLUDED device-protected key (ProfileKeys): the panel never touches a PRIVACY key or the
 * credential-encrypted preferences, so it is safe on the lock screen. The three layout toggles (one-handed, split, floating) are carried out by the keyboard
 * through its existing key codes, which write the same key ([keyOf] names it); the others are written here ([toggle], [stepHeight]).
 */
object QuickSettings {
    const val HEIGHT_STEP = 0.05f

    /** The only preference key that [control] writes on a screen in state [s]. */
    fun keyOf(control: QuickControl, s: ScreenState): String = when (control) {
        QuickControl.NUMBER_ROW -> Settings.PREF_SHOW_NUMBER_ROW
        QuickControl.ONE_HANDED -> createPrefKeyForBooleanSettings(Settings.PREF_ONE_HANDED_MODE_PREFIX, findIndexOfDefaultSetting(s.landscape, s.split, s.folded), 3)
        QuickControl.SPLIT -> when {
            s.landscape && s.folded -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE
            s.landscape -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE
            s.folded -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED
            else -> Settings.PREF_ENABLE_SPLIT_KEYBOARD
        }
        QuickControl.FLOATING -> ProfileKeys.floatingKey(s.widthPixels)
        QuickControl.HAPTICS -> Settings.PREF_VIBRATE_ON
        QuickControl.SOUND -> Settings.PREF_SOUND_ON
        QuickControl.GLIDE -> Settings.PREF_GESTURE_INPUT
        QuickControl.EMOJI_KEY -> Settings.PREF_SHOW_EMOJI_KEY
        QuickControl.HEIGHT -> createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, findIndexOfDefaultSetting(s.landscape, s.folded), 2)
    }

    private fun defaultOf(control: QuickControl): Boolean = when (control) {
        QuickControl.NUMBER_ROW -> Defaults.PREF_SHOW_NUMBER_ROW
        QuickControl.ONE_HANDED -> Defaults.PREF_ONE_HANDED_MODE
        QuickControl.SPLIT -> Defaults.PREF_ENABLE_SPLIT_KEYBOARD
        QuickControl.FLOATING -> false
        QuickControl.HAPTICS -> Defaults.PREF_VIBRATE_ON
        QuickControl.SOUND -> Defaults.PREF_SOUND_ON
        QuickControl.GLIDE -> Defaults.PREF_GESTURE_INPUT
        QuickControl.EMOJI_KEY -> Defaults.PREF_SHOW_EMOJI_KEY
        QuickControl.HEIGHT -> error("the height is not a switch")
    }

    /** The state of a switch control. */
    fun isOn(prefs: SharedPreferences, control: QuickControl, s: ScreenState): Boolean = prefs.getBoolean(keyOf(control, s), defaultOf(control))

    /** Flips a switch control (every one but HEIGHT) in one commit and returns the new state; false is returned too when the commit failed (the key is unchanged then). */
    fun toggle(prefs: SharedPreferences, control: QuickControl, s: ScreenState): Boolean {
        val now = !isOn(prefs, control, s)
        return if (prefs.edit().putBoolean(keyOf(control, s), now).commit()) now else !now
    }

    /**
     * The key that opens the emoji panel when held. It is a choice of five, so it is not one of the [QuickControl] switches: the panel shows it as its own button
     * that steps through comma, period, space, language key and none, and it writes the one INCLUDED key [Settings.PREF_EMOJI_HOLD].
     */
    fun emojiHold(prefs: SharedPreferences): EmojiHold = EmojiHold.fromPref(prefs.getString(Settings.PREF_EMOJI_HOLD, Defaults.PREF_EMOJI_HOLD))

    /** Steps [emojiHold] to the next value in one commit and returns the value now (the old one when the commit failed). */
    fun cycleEmojiHold(prefs: SharedPreferences): EmojiHold {
        val current = emojiHold(prefs)
        val next = current.next()
        return if (prefs.edit().putString(Settings.PREF_EMOJI_HOLD, next.pref).commit()) next else current
    }

    /** The keyboard height scale, read by the same function the keyboard uses (it owns the default of each screen state). */
    fun height(prefs: SharedPreferences, s: ScreenState): Float = Settings.readHeightScale(prefs, s.landscape, s.folded)

    /** Moves the height by one step up or down inside [ProfileKeys.HEIGHT_MIN]..[ProfileKeys.HEIGHT_MAX] (steps of 0.05) and returns the height now. */
    fun stepHeight(prefs: SharedPreferences, s: ScreenState, up: Boolean): Float {
        val current = height(prefs, s)
        val next = ((current + if (up) HEIGHT_STEP else -HEIGHT_STEP) * 100f).roundToInt() / 100f
        val clamped = next.coerceIn(ProfileKeys.HEIGHT_MIN, ProfileKeys.HEIGHT_MAX)
        if (clamped == current) return current
        return if (prefs.edit().putFloat(keyOf(QuickControl.HEIGHT, s), clamped).commit()) clamped else current
    }
}
