// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * Whether the system asked the keyboard to show its own IME switcher (Android 16 and later, InputMethodService.onCustomImeSwitcherButtonRequestedVisible).
 * While it is requested the language key is on the keyboard even when the setting or the number of languages would hide it: it is the switcher
 * (a tap cycles Taen's languages, or goes to the next keyboard when Taen has one language; a long press opens the system picker).
 *
 * Taen replaces the system's bar only when there is something to switch to, so the key is never the only way to a switcher that has nothing to offer.
 */
object ImeSwitcher {
    @Volatile
    var requested = false

    enum class Tap { NEXT_LANGUAGE, NEXT_KEYBOARD, NOTHING }

    /** [somethingToSwitchTo]: the picker has more than one entry (another Taen language or another keyboard). */
    fun shouldRequest(somethingToSwitchTo: Boolean) = somethingToSwitchTo

    /** What a tap on the language key does while it is the system's switcher. */
    fun tap(otherLanguages: Boolean, somethingToSwitchTo: Boolean): Tap = when {
        otherLanguages -> Tap.NEXT_LANGUAGE
        somethingToSwitchTo -> Tap.NEXT_KEYBOARD
        else -> Tap.NOTHING
    }
}
