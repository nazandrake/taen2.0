# Advanced

Settings > Advanced holds the long press delay, the gestures of the space bar and the delete key, what the bottom-row keys do, formats, backup and restore, and a few experimental switches. Force incognito mode is also on this screen; it is described under [Privacy settings](page:privacy-settings). Entries that appear only when another setting is on say so.

## Keys and gestures

### {#key_longpress_timeout}
How long you hold a key before its long press popup opens, from 100 to 700 milliseconds. Default: 300 ms. Raise it if popups open when you do not mean it; lower it if they open too slowly.

### {#horizontal_space_swipe}
What a swipe left or right on the space bar does: move the cursor, switch language, toggle the numpad, toggle the D-pad, or nothing. Default: move the cursor. Choose switch language if you change language with a swipe.

### {#vertical_space_swipe}
What a swipe up or down on the space bar does: move the cursor, switch language, toggle the numpad, toggle the D-pad, hide the keyboard, touchpad mode, or nothing. Default: move the cursor. Touchpad mode turns the keyboard into a touchpad for the cursor.

### {#language_swipe_distance}
How far you have to swipe on the space bar to switch language. Shown only when a space bar swipe is set to switch language. Default: 5.

### {#touchpad_sensitivity}
How fast the cursor moves in touchpad mode. Shown only when the vertical swipe is touchpad mode. Default: 50.

### {#touchpad_edge_scroll}
Keeps scrolling when you slide to the edge of the keyboard in touchpad mode. Shown only when the vertical swipe is touchpad mode. Default: on.

### {#delete_swipe}
Swipe from the delete key to select and remove bigger portions of text at once. Default: on. Turn it off if it deletes text when you do not mean it.

### {#prefs_long_press_keyboard_to_change_lang}
A long press on the space bar opens Android's keyboard picker. Default: on. Turn it off if you hold the space bar by accident.

### {#long_press_symbols_for_numpad}
A long press on the symbols key opens the numpad. Default: off.

### {#enable_emoji_alt_physical_key}
With a physical keyboard, the Alt key opens the emoji panel. Default: on.

## Bottom-row behavior

The four entries below share the title **Switch to main keyboard after…** and differ in the line under it. Each one says after which action the keyboard goes back to the letters.

### {#abc_after_symbol_space}
After pressing enter or space in the symbols page. Default: on.

### {#abc_after_numpad_space}
After pressing enter or space in the numpad. Default: off.

### {#abc_after_emoji}
After selecting an emoji in the emoji panel. Default: off. Turn it on if you send one emoji at a time.

### {#abc_after_clip}
After selecting an entry of the clipboard history. Default: off.

## Text and formats

### {#custom_currency_key}
Sets the currency key: one main symbol and up to six more, separated by spaces. Default: the usual symbols of your language.

### {#more_popup_keys}
How many letters with diacritics the popups of the letter keys offer: only the variants defined for the keyboard languages, very common variants, common variants or all variants. Default: very common variants. Choose more if you need rare accents.

### {#timestamp_format}
The pattern of the timestamp that the timestamp key types. It uses the letters of Java's date and time patterns, for example `yyyy-MM-dd HH:mm:ss`. A pattern that is not valid is refused. Default: `yyyy-MM-dd HH:mm:ss`.

## Backup and restore

### {#backup_restore}
Saves or loads one encrypted file with your settings, learned words, personal dictionary, snippets, imported dictionaries and, if they fit, your pictures. You choose a passphrase of at least 12 characters. It is asked for each time and never stored, so without it the backup cannot be opened. There is no unencrypted option. A restore is checked completely before anything is replaced; a wrong passphrase or a damaged file changes nothing. A restore overwrites what is there now. The settings are behind the app lock, so the PIN protects this too. Pictures are left out of a backup that would be larger than 64 MiB, and the screen says so.

## Experimental

### {#emoji_max_sdk}
Lets you override the emoji version that Taen shows. Use it if new emoji appear as empty boxes on your phone. Default: the version of your phone.

### {#url_detection}
Tries to detect web addresses and similar text as a single word. Default: off.

### {#enable_hardware_keyboard}
Support for a physical keyboard. It still has known problems with some layouts and apps. Default: off. Turn it on only if you use a physical keyboard.
