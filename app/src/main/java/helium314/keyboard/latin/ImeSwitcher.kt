// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * Whether the system asked the keyboard to show its own IME switcher (Android 16 and later, InputMethodService.onCustomImeSwitcherButtonRequestedVisible).
 * While it is requested the language key is on the keyboard even when the setting hides it: it is the switcher (a tap cycles Taen's languages, a long
 * press opens the system picker).
 */
object ImeSwitcher {
    @Volatile
    var requested = false
}
