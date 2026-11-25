# Toolbar

Settings > Toolbar controls the row above the keys: whether it shows suggestions, tools or both, which tools it holds, and when it hides. The suggestions are explained in [Suggestions and autocorrect](page:suggestions). If the strip seems to have vanished, see [Troubleshooting](page:troubleshooting).

## The tools

The toolbar keys, from which you choose what is shown, are:

- text: Undo, Redo, Select word, Select all, Copy, Cut, Paste, Clear all text.
- cursor: Left, Right, Up, Down, Word left, Word right, Page up, Page down, Full left, Full right, Page start, Page end. Long press a key of a pair to do the bigger move (for example word instead of character).
- several cursors and selection: Selection mode, Select next occurrence, Cursor on the next line.
- the clipboard: Clipboard, Clear clipboard.
- layouts: Numpad, D-pad, Emoji, GIFs and stickers (the picture panel).
- modes: Voice input, Incognito (a one-tap switch for incognito mode), Auto-correction, One-handed mode, Split keyboard, Floating keyboard.
- Settings and Quick settings.
- Vim Esc (Normal mode): shows with Vim mode ([Vim mode](page:vim)).

In the expandable mode the strip has a key at its left that opens and closes the toolbar. Keys that you **pin** stay on the strip next to the suggestions. Long press a toolbar key to pin it.

## Mode and hiding

### {#toolbar_mode}
What the row above the keys shows. Toolbar keys and suggestions: suggestions, with a key that opens the toolbar. Toolbar keys only: the tools, no suggestions. Suggestions only: the strip, no tools (pinned keys stay). Hidden: neither. Default: Toolbar keys and suggestions. Choose Hidden to gain room, or Toolbar keys only if you do not use suggestions.

### {#toolbar_hiding_global}
When the toolbar mode is Hidden, hides the clipboard and emoji toolbars as well. Shown only when the mode is Hidden. Default: on. Turn it off if you want those toolbars even then.

### {#toolbar_swipe_down_to_hide}
Swipe down on the toolbar to close the keyboard. Shown only when the toolbar is not hidden. Default: off.

## Keys

### {#toolbar_keys}
Chooses which keys the toolbar shows and in what order: turn each on or off, and drag to reorder. Shown only in the modes that have a toolbar. By default the toolbar has Settings, Quick settings, Voice, Clipboard, Incognito, Undo, Redo, Select word, Select all, Clear all, Selection mode, Select next occurrence, Cursor on the next line, Copy, Paste, Left and Right.

### {#pinned_toolbar_keys}
Chooses which keys stay on the strip next to the suggestions. Shown only in the modes that have a strip. By default only the Voice key is pinned. Pin the keys you use most.

### {#clipboard_toolbar_keys}
Chooses the keys of the toolbar inside the clipboard history. Shown unless the toolbar is hidden for good. By default: Clear clipboard, Up, Down, Left, Right, Undo, Cut, Copy, Paste, Select word and the key that closes the history.

### {#toolbar_custom_key_codes}
Lets you change what a toolbar key sends: a key code of your own, for the key and for its long press. Meant for experts; a reset clears all of them. Shown unless the toolbar is hidden for good.

### {#quick_pin_toolbar_keys}
A long press on a toolbar key pins it to the strip. This turns off the other long press actions of the keys that are not pinned. Shown only in the expandable mode. Default: off.

## Behavior

### {#auto_show_toolbar}
Opens the toolbar when you start typing in a field, or when text is selected. Shown only in the expandable mode. Default: off.

### {#auto_hide_toolbar}
Closes the toolbar when suggestions become available, so that the strip comes back while you type. Shown only in the expandable mode. Default: on. Turn it off if you want the toolbar to stay open until you close it.

### {#only_toolbar_with_hw_keyboard}
With a physical keyboard connected, shows only the toolbar and no keys. You still need to allow the virtual keyboard in Android's settings. Shown only when the toolbar is not hidden. Default: off.

### {#var_toolbar_direction}
Reverses the direction of the toolbar when a right-to-left language such as Arabic is selected. Shown only when the toolbar is not hidden. Default: on.
