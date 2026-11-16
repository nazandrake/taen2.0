// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

/**
 * Which key opens the emoji panel when it is held (setting "Open emoji by holding"). The hold uses the keyboard's own mechanism for a key that does one thing on a
 * long press (a single popup key behind the `!noPanelAutoPopupKey!` marker, like the caps-lock hold on the shift key), so it works whether the emoji key on the bottom
 * row is shown or not. [NONE] leaves every key as it was (the comma key keeps its menu). A value that is not one of the five is read as the default.
 */
enum class EmojiHold(val pref: String) {
    COMMA("comma"), PERIOD("period"), SPACE("space"), LANGUAGE("language"), NONE("none");

    /** The next value of the cycle comma, period, space, language, none (the quick panel). */
    fun next(): EmojiHold = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = COMMA
        fun fromPref(value: String?): EmojiHold = entries.firstOrNull { it.pref == value } ?: DEFAULT
    }
}
