// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R

/** What a long press on a toolbar key does where the key sits now (the strip decides, see SuggestionStripView.longPressOf). */
enum class ToolbarHold { NONE, ACTION, VOICE, PIN, UNPIN }

/** The name of a toolbar key: the string named like the key, also its content description and its name in the settings. One owner. */
fun toolbarKeyName(context: Context, key: ToolbarKey): String = key.name.lowercase().getStringResourceOrName("", context)

/** The text of the bubble over a pressed key: its name, and after " · hold: " what a long press does (nothing when it does nothing). */
fun toolbarKeyBubbleText(context: Context, key: ToolbarKey, hold: ToolbarHold): String {
    val name = toolbarKeyName(context, key)
    val action = when (hold) {
        ToolbarHold.NONE -> null
        ToolbarHold.VOICE -> context.getString(R.string.toolbar_hold_talk)
        ToolbarHold.PIN -> context.getString(R.string.toolbar_hold_pin)
        ToolbarHold.UNPIN -> context.getString(R.string.toolbar_hold_unpin)
        ToolbarHold.ACTION -> longPressActionName(context, getCodeForToolbarKeyLongClick(key))
    } ?: return name
    return context.getString(R.string.toolbar_hold_format, name, action)
}

/** The long press sends [code]: the name of the toolbar key whose tap sends it, "Repeat" for a held key, a generic name for another code. */
private fun longPressActionName(context: Context, code: Int): String? = when (code) {
    KeyCode.UNSPECIFIED -> null
    KeyCode.KEY_REPEAT -> context.getString(R.string.toolbar_hold_repeat)
    else -> ToolbarKey.entries.firstOrNull { getCodeForToolbarKey(it) == code }?.let { toolbarKeyName(context, it) }
        ?: context.getString(R.string.toolbar_hold_custom)
}
