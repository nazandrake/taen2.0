# Troubleshooting

Find the symptom, then read the cause and the fix. Each entry names the setting that matters; the settings pages list what each one does.

## The suggestions disappeared

Most likely the **toolbar** is open. In the default mode the toolbar replaces the suggestion strip, so you see tools instead of words.

- Tap the toolbar key at the left of the strip to close the toolbar.
- Turn on **Auto hide toolbar** (Settings > Toolbar; it is on by default in this version) so that the toolbar closes when suggestions become available.
- If **Toolbar mode** is set to Toolbar keys only or Hidden, there is no strip at all. Choose Toolbar keys and suggestions or Suggestions only.
- If **Show correction suggestions** is off in Text correction, no suggestions are shown.
- Password fields never have a strip. Some apps ask keyboards not to show suggestions: **Always show suggestions** overrides that.

See [Toolbar](page:toolbar) and [Text correction](page:text-correction).

## A Vim key is missing, or Vim mode does nothing

- The **Esc** key is not on the strip: Vim mode is off (turn on **Vim mode** in Settings > Preferences), or the field is a **password field**, or the **device is locked**. Vim mode is never available in those cases.
- Esc is on the strip but a command does nothing and a message shows: "This app does not share its text: only cursor moves work", "This app shares only part of its text: nothing was changed", "The text is too long (over 20 000 characters)", "The text changed: nothing was done" or "Text is selected: press Esc, then the command". The command needs all the text of the field. Some web and custom editors do not share it. Only cursor motions work then, as cursor keys.
- A key that you know from Vim is not on the layouts: long press a key for the capital letter and for the symbols that hang on it ([Vim mode](page:vim)). Screen commands and a few others are not offered at all ([What is not supported](page:vim-limits)).
- A macro or a register is gone: Vim mode forgets them when the screen locks, at a wipe and when it is turned off. In a field where the clipboard history may not keep a copy (password field, incognito), nothing is remembered.
- Undo says it cannot run inside a macro: undo the whole macro afterwards with one `u`.

## Suggestions do not learn my words

Learning is off in password fields, in fields that ask for no personalized learning, in apps on your incognito list, while **Force incognito mode** is on and while the device is locked. Also check that **Personalized suggestions** is on in Text correction. See [Suggestions and autocorrect](page:suggestions).

## Autocorrect keeps changing a word

Press backspace right after the correction to get your word back. Taen then learns your word. If it still happens, move **Auto-correction confidence** toward Modest (a lower value corrects less often), add the word to your personal dictionary ([Dictionaries](page:dictionaries)), or turn off **Auto-correction** ([Text correction](page:text-correction)).

## A clipboard entry is gone

Entries expire after the retention time (one hour by default), and the history keeps at most 50 unpinned entries. A copy marked sensitive, made in a password field, in a field with no personalized learning, in an incognito app or while locked is never stored. **Pin** an entry to keep it. See [Clipboard](page:clipboard).

## There is no paste key in a password field

That is on purpose. Turn on **Clipboard keys in password fields** under Settings > Privacy ([Privacy settings](page:privacy-settings)).

## The voice key is missing or does nothing

- Check that **Voice key** is on in Settings > Voice typing, and that the voice model is installed ([Voice typing](page:voice)). Without the model nothing records.
- The voice key is not offered in password fields or while the device is locked.
- Android asks for the microphone permission the first time. If you refused, allow it in Android's app settings.

## The next-word model is not used

A model is used only on a phone that passes its speed and memory test, and only in fields where learning is allowed. Open Settings > Downloads to see the state of the model and to run the test ([Downloads](page:downloads)).

## I cannot take a screenshot while typing

Taen hides the keyboard window from screenshots and screen recordings by default. Turn off **Hide the keyboard from screenshots and screen recording** in Settings > Privacy ([Privacy settings](page:privacy-settings)).

## A banking app does not start with Taen

Some banking apps start only with a keyboard that came with the phone. Taen cannot change that. Use the Quick Settings tile **Switch keyboard** to change the keyboard for that app, then change back ([About](page:about)).

## I cannot switch language

- Check that more than one language is enabled under Settings > Languages and layouts ([Languages and layouts](page:languages)).
- Check that **Language switch key** is on in Preferences, or use another way: swipe on the space bar if you set that, or long press the comma key ([Getting started](page:getting-started)).
- If a language has no dictionary, you can still type, but there are no suggestions. Add one under Settings > Dictionaries or Downloads ([Dictionaries](page:dictionaries)).

## The keyboard is too tall, too small or in the wrong place

Change the size and the position under Settings > Appearance: **Keyboard height scale**, **Bottom padding scale**, **Side padding scale**, one-handed mode and the split keyboard ([Appearance](page:appearance)). Set the height to 100 percent to return to the plain size.

## The number row, the emoji key or the cursor-keys key is missing

Turn them on in Settings > Preferences: **Number row**, **Emoji key**, **D-pad key** ([Preferences](page:preferences)).

## Gesture typing does nothing

Turn on **Enable gesture typing** in Settings > Gesture typing ([Gesture typing](page:gesture-typing)). Then slide your finger over the letters of a word without lifting it.

## I forgot my PIN

The PIN cannot be read back or reset: Taen stores only a slow hash of it. Clearing the app's data in Android's app settings removes the lock together with everything Taen stored, so use it only if you accept to lose your settings, learned words and personal dictionary.

## A restore failed

A backup is opened only with the passphrase it was made with. A wrong passphrase or a damaged file changes nothing. Try again with the right passphrase. See **Backup and restore** in [Advanced](page:advanced).

## Still stuck

Open the page for the screen that you are using; it lists every setting with its default. If a setting seems to have no effect, check whether a related setting turns it off: for example, the options of the toolbar depend on the toolbar mode.
