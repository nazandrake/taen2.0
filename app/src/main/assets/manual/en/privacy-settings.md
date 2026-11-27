# Privacy settings

Settings > Privacy gathers the switches that decide what Taen remembers, what other software can see, and who can open the settings. Why each one exists, and what it cannot do, is in [Privacy explained](page:privacy).

## Learning

### {#use_personalized_dicts}
Learns the words you type and the words that follow them, and suggests them. Default: on. Turning it off asks for a confirmation and deletes the learned words of every language (the removed words and your personal dictionary stay). Nothing is learned in password fields, in fields that ask for no personalized learning, in incognito and while locked, whatever this setting says. See [Suggestions and autocorrect](page:suggestions).

### {#learned_words}
Shows, for each language, the words Taen has learned from your typing, and the words removed from the suggestions. **Remove** takes a learned word out of the learned words at once and puts it on the removed list, so it is not suggested any more; **Restore** takes a word off that list (typing the word again does the same). The list is part of the settings, so the app lock guards it and it cannot be captured on screen. While the phone is locked it shows nothing.

### {#forget_learned_data}
Deletes now, after a confirmation, the learned words and the removed words of every language, from memory and from their encrypted files. Your personal dictionary is kept: to empty it, use **Clear personal dictionary**. Learning goes on afterwards if **Learn from my typing** is on. Not possible while the phone is locked.

### {#clear_personal_dictionary}
Deletes, after a confirmation, every word and shortcut of your personal dictionary, in every language. To delete single words, use **Personal dictionary** in [Text correction](page:text-correction). Not possible while the phone is locked.

## Incognito

### {#always_incognito_mode}
Turns on incognito for every field: nothing is learned, no clipboard entry or recent emoji is kept, and your learned words are not suggested. The same switch is also found under Advanced. Default: off. Turn it on when you want to type something without leaving a trace, and off again after. The incognito key on the toolbar switches it with one tap ([Toolbar](page:toolbar)).

### {#incognito_apps}
A list of apps in which Taen is always incognito. Open it and type the package names, one per line (for example `org.example.bank`), at most as many as the dialog says. Entries that are not valid are dropped. The list is stored encrypted. Putting an app on the list also forgets the language that was remembered for it. Default: empty. Use it for banking, health and messaging apps.

## Screen capture and snooping

### {#secure_keyboard_window}
While the keyboard is open, Android refuses screenshots and records the screen black. The keyboard window covers the whole screen, so the app behind it is blanked too. Default: on. Turn it off to take screenshots while typing.

### {#accessibility_warning}
Shows a notice when an accessibility service that can read the screen and what you type is on and does not declare itself an accessibility tool. Default: on. Taen cannot stop such a service; the notice lets you decide.

### {#overlay_mode}
What to do when another app's window covers the keyboard, so that your taps could be watched or faked. Do nothing; Warn (the default); or Block typing while covered. Blocking works for each gesture. Screen dimmers also cover the keyboard, so blocking can stop typing. Default: Warn.

## Clipboard

### {#clipboard_in_secure_fields}
Shows the paste, select all and clear all keys and your pinned keys also in password fields and number password fields, so that you can paste and copy there. A copy made in a password field is marked sensitive: Android hides its preview, and Taen never stores, suggests or logs it. Default: off.

## App lock

The app lock is a PIN of 4 to 16 digits. It guards the settings (including backup and restore) and the clipboard history. The PIN is entered on a digit pad inside the app, never through a keyboard.

- **Set a PIN** asks for the PIN twice. After it is set the entries below appear.
- **Change the PIN** and **Remove the PIN** both ask for the current PIN first.
- **Lock again after N min** shows the time after which the lock closes again, counted from your last use of the settings: 1, 5, 15 or 60 minutes. Tap it to move to the next value. The lock also closes at once when the screen turns off.

A wrong PIN is counted before it is compared. From the fifth wrong try the lock makes you wait 30 seconds, then longer each time, up to one hour. If the lock cannot be read, it stays closed.

## Self-destruct

**Self-destruct** opens its own screen ([Self-destruct](page:self-destruct)): a duress PIN, a trigger word, automatic wipes and what is deleted. Some of its triggers need the app lock to be on.

## Status

The last section is a read-only summary: the app has no network permission, Android backup and device transfer are off, and whether an accessibility service that can read the screen is on. It also lists, in plain words, what a keyboard cannot protect against.
