# Text correction

Settings > Text correction holds autocorrect, capital letters, spacing, suggestions and emoji suggestions. How suggestions are made, learned and taken back is explained in [Suggestions and autocorrect](page:suggestions). Entries that appear only when another setting is on say so.

## Dictionary

### {#edit_personal_dictionary}
Opens your personal dictionary and snippets: words and short texts that you add, with an optional shortcut. See [Dictionaries](page:dictionaries).

## Correction

### {#block_potentially_offensive}
Does not suggest words that may be offensive. Default: on. Turn it off if you want every word of the dictionary.

### {#auto_correction}
When you press the space bar or a punctuation mark, a mistyped word is replaced by the word most likely meant. Default: on. Turn it off if you want to decide every word yourself. Backspace right after a correction takes it back.

### {#more_auto_correction}
Also corrects in fields that did not ask for autocorrect. Shown only when autocorrect is on. Default: off.

### {#autocorrect_shortcuts}
Lets autocorrect expand the shortcuts of your personal dictionary. Shown only when autocorrect is on. Default: on.

### {#autocorrect_capitalized_suggestion}
When you press shift to capitalize a suggestion, autocorrect still applies. Shown only when autocorrect is on. Default: on.

### {#auto_correct_confidence}
How sure Taen has to be before it corrects. The value goes from 0 to 1 and is shown with a name: modest up to 0.4, aggressive up to 0.8, very aggressive above. A lower value corrects less often. Shown only when autocorrect is on. Default: 0.24 (modest).

### {#backspace_reverts_autocorrect}
Backspace right after a correction brings back the word you typed and teaches it to the dictionary of learned words, when learning is allowed. Shown only when autocorrect is on. Default: on. See [Suggestions and autocorrect](page:suggestions).

### {#auto_cap}
Starts the first word of each sentence with a capital letter. Default: on.

## Spacing and punctuation

### {#use_double_space_period}
Two quick taps on the space bar type a full stop and a space. Default: on. Turn it off if you type two spaces on purpose.

### {#smart_quotes}
In Bulgarian, the quote key types „ to open and “ to close, and a plain quote after a digit. It works in ordinary text fields only. Default: on.

### {#autospace_after_punctuation}
Adds a space automatically after punctuation when you start the next word. Default: off.

### {#autospace_after_suggestion}
Adds a space after you pick a suggestion. Default: on.

### {#autospace_before_gesture_typing}
Adds a space before a word that you type by gesture, when the text before it needs one. Shown only when gesture typing is on. Default: on.

### {#autospace_after_gesture_typing}
Adds a space after a word that you type by gesture. Shown only when gesture typing is on. Default: on.

### {#shift_removes_autospace}
Pressing shift removes the space that was added automatically. Default: off.

## Suggestions

### {#show_suggestions}
Shows suggested words while you type. Shown only when the toolbar mode has a strip. Default: on. If it is off, there is no suggestion strip at all. See [Troubleshooting](page:troubleshooting).

### {#always_show_suggestions}
Shows suggestions even when an app asks keyboards not to show them. Shown only when suggestions are on. Default: off. It may cause problems in some apps.

### {#always_show_suggestions_except_web_text}
Does not force suggestions in web edit fields, which are a common cause of problems with the setting above. Shown only when **Always show suggestions** is on. Default: on.

### {#center_suggestion_text_to_enter}
The space bar and punctuation always use the middle suggestion. Shown only when suggestions are on. Default: off.

### {#suggest_emojis}
Adds emoji to the suggestions, from the emoji dictionary. Shown when suggestions or autocorrect are on. Default: on. An emoji is never used as an autocorrection.

### {#inline_emoji_search}
Type a colon and a word to see only emoji. Shown when suggestions or autocorrect are on. Default: on.

### {#use_personalized_dicts}
Learns the words you type and uses them for suggestions. Default: on. Turning it off asks for a confirmation. Nothing is learned in password fields, incognito and while locked, whatever this setting says. See [Suggestions and autocorrect](page:suggestions).

### {#next_word_prediction}
After a word, suggests the words that usually follow it. Default: on. An optional model can improve the order ([Downloads](page:downloads)).

### {#suggest_punctuation}
Shows punctuation marks as suggestions when there is no other suggestion. Default: off.

### {#punctuation_suggestions}
The list of marks that are suggested, separated by spaces. Shown only when punctuation suggestions are on. Default: the standard set.

### {#suggest_clipboard_content}
Offers a recently copied text as a suggestion. A clip marked sensitive is never offered. Default: off. See [Clipboard](page:clipboard).

### {#suggest_paste}
After you copy text, a Paste button appears first on the strip, next to the word suggestions. It shows no text. Tap it to paste, like the paste key. It goes away when you use it, after a minute, or when the screen locks; it stays when you open a field in another app, so you can copy in one app and paste in another. It also appears in password fields, because it shows nothing and the clipboard is read only when you tap it; it never appears while the device is locked. Default: on. See [Clipboard](page:clipboard).

### {#add_to_personal_dictionary}
Lets words that you type often move into your personal dictionary, where they stay. Shown only when personalized suggestions are on. Default: off.

### {#spellcheck_suggest}
Shows suggestions when you select a word with a red underline. It needs Taen to be Android's default spell checker. Shown only on Android 12 and newer. Default: on.
