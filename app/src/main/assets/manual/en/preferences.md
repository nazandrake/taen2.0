# Preferences

Settings > Preferences holds what happens when you press keys: hints and popups, feedback, extra keys, Vim mode and the clipboard history. Some entries appear only when another setting makes them useful; the entry says so. The clipboard history is explained in [Clipboard](page:clipboard), Vim mode in [Vim mode](page:vim).

## Languages

### {#save_subtype_per_app}
Each app keeps the language you last used in it, so that the language you set in one app does not carry over to another. The list is stored encrypted, holds the newest 200 apps, and is never recorded in password fields, for apps on your incognito list or while the device is locked. Turning the setting off clears the list. Default: on. Turn it off if you want one language everywhere.

### {#clear_app_languages}
Forgets the language that was remembered for each app. The setting above stays on and starts remembering again. Use it after you changed your languages, or to start from a clean list.

## Input

### {#show_hints}
Shows a small hint on each key for what a long press gives (for example a digit or an accent). Default: on. Turn it off for a cleaner look.

### {#popup_keys_labels_order}
Chooses where the hints on the keys come from (the language, the layout, the symbols, the number row) and in which order. Shown only when key hints are on. Change it if the hint on a key is not the one you want to see.

### {#popup_keys_order}
Chooses the order of the keys in the popup that opens on a long press: the keys of the language, of the layout, of the symbols and the number row. Change it if the popup shows the key you need last.

### {#show_tld_popup_keys}
In a field for web addresses or e-mail addresses, the long press of the period key offers top-level domains such as .com or .org. Default: on. Turn it off if you prefer the usual punctuation popup there.

### {#show_popup_hints}
Shows a hint on a function key when its long press opens a popup (for example the comma key). Default: off. Turn it on if you want to see which keys hide more.

### {#popup_on}
Shows a magnified preview of the key above your finger while you press it. Default: off on phones, on on tablets. Turn it on if you want to see which key you hit; turn it off if the preview gets in your way. A preview is never shown in a password field.

### {#vibrate_on}
Vibrates a little at every key press. The strength can be set below. Default: on. Turn it off to save a little battery, or if you do not want to feel the keys.

### {#vibration_duration_settings}
How long the key vibration lasts, from the system value to 100 milliseconds. A step plays the vibration so that you can feel it. Shown only when vibration is on. Default: the system value. Make it longer if you do not feel it, shorter if it feels harsh.

### {#vibrate_in_dnd_mode}
Keeps the key vibration when Android's Do Not Disturb mode is on. Shown only when vibration is on. Default: off. Turn it on if you want feedback even at night.

### {#sound_on}
Plays a click at every key press. Default: off, because a click tells people near you what you type. Turn it on if you want the sound.

### {#keypress_sound_volume}
How loud the click is, from the system value to 100. Shown only when the key sound is on. Default: the system value.

### {#show_emoji_descriptions}
Shows the name of an emoji when you long press it in the emoji panel. A note appears if the emoji dictionary of the language is missing. Default: on. Turn it off if the labels get in the way.

## Additional keys

### {#show_number_row}
Shows a row of digits above the letters at all times. Default: off. Turn it on if you type numbers often and do not want to open the symbols page.

### {#localized_number_row}
For languages that have their own digits, such as Arabic, Hindi or Thai, shows those digits on the number row instead of the Latin ones. Shown only when one of your languages has its own digits. Default: on.

### {#show_number_row_hints}
Shows hints on the keys of the number row too. Shown only when key hints and the number row are on. Default: off.

### {#show_number_row_in_symbols}
Shows the number row on the symbols page too. Shown only when the always-on number row is off. Default: on.

### {#show_language_switch_key}
Shows the language switch key (the globe) on the bottom row. Default: on. Turn it off if you switch language another way, for example with a swipe on the space bar, and want the room for the space bar.

### {#language_switch_key}
What a tap on the language switch key does: switch language, switch to other keyboards (Android's picker), or both. Default: switch language. Choose both if you also use other keyboards.

### {#show_emoji_key}
Shows the emoji key on the bottom row. Default: on. Turn it off if you open emoji another way (see below).

### {#emoji_hold}
Chooses a key that opens the emoji panel when you hold it: the comma, the period, the space bar or the language key. It works whether the emoji key is shown or not. The key then has no popup menu of its own. Default: none, so popup menus stay.

### {#show_dpad_key}
Shows the key that opens the cursor keys (up, down, left, right, and more) on the keyboard. Default: on. Turn it off if you move the cursor with the space bar or the toolbar.

### {#vim_mode}
Turns on Vim mode: an Esc key appears on the strip and opens the Normal layout with Vim's motions, operators, registers, macros, search and command line. Not available in password fields. Default: off. See [Vim mode](page:vim).

### {#vim_indent_tab}
The Vim commands `>` and `<` indent with a tab instead of four spaces. Default: off. Turn it on if the text you edit uses tabs.

### {#remove_redundant_popups}
Removes from the long press popup the keys that are already on the keyboard. Default: off. Turn it on for shorter popups.

## Clipboard history

### {#enable_clipboard_history}
Keeps a short history of the text you copy, which you reach with the clipboard key. Default: on. If you turn it off, the clipboard key pastes the current clipboard content, and the unpinned entries are removed. See [Clipboard](page:clipboard).

### {#clipboard_history_retention_time}
How long an entry stays: 5 minutes up to 7 days, or Forever. Shown only when the history is on. Default: 1 hour. Shorten it for more privacy; choose longer or Forever if you reuse clips.

### {#clipboard_history_pinned_first}
Shows pinned entries above the others. Shown only when the history is on. Default: on.

### {#clipboard_history_max_items}
The most entries that the history keeps (pinned entries are not counted), from 1 to 500. Shown only when the history is on. Default: 50 items.
