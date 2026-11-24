# Self-destruct

Self-destruct deletes what the keyboard stores, on a trigger that you choose. First the keys that open the stored files are destroyed (aimed at under 0.2 seconds), so nothing stored can be read again, even from a copy of the files. Then the files are deleted (aimed at under 2 seconds) and the keyboard restarts as new. **Everything is off until you switch it on**, and each trigger is separate. Use the test mode first. The screen is under Settings > Privacy > Self-destruct and, like all settings, it is behind the app lock. The duress PIN and the wrong-PIN count need the app lock to be on ([Privacy settings](page:privacy-settings)). The background is in [Privacy explained](page:privacy).

## Test mode

**Start test mode (15 minutes)** makes every trigger only show "Trigger recognised (test)" and delete nothing. It ends by itself after 15 minutes, or when you tap **Stop test mode**. Try every trigger that you set up before you rely on it.

## Duress PIN

A second PIN that you enter at the app lock. Instead of unlocking, it deletes the data. It must differ from the real PIN; if the two ever collide, the real PIN wins. The time it takes does not tell the two apart. Needs the app lock.

- **Set a duress PIN**, then **Change** and **Remove** it.
- **When the duress PIN is entered**: *Delete at once, without asking* (the default), or *Ask "Clear typing data?" first*.

## Trigger word

A word that you type on the keyboard and finish with Enter deletes the data. Only a salted, slow hash of the word is kept, never the word itself.

The word must be 12 to 64 characters, with no spaces and no separators the keyboard splits words on (. , ; : ! ? / _ brackets, emoji), at least one digit or symbol, and not a word that exists. Case matters. You type it twice to set it. The word is never learned.

After a word is set you can choose:

- **When the word is typed**: delete at once (the default) or ask first.
- **Also check on the space key**: off by default, so only Enter or the action key completes the word.
- **Hold Enter until checked**: on by default. The word is removed from the field and never sent. When it is off, Enter goes ahead and the check runs beside it.
- **Check in password fields**: off by default. The keyboard's own settings fields are never checked.

Typing a trigger word leaves fragments of it in the learned words until the wipe. Choose a word that you never type by accident.

## Automatic

- **Wrong app-lock PINs in a row**: delete after 5 to 20 wrong PINs. Off by default. Needs the app lock.
- **Days without using the keyboard**: delete after 3, 7, 14, 30, 60, 90, 180 or 365 days without an input session. Off by default. It is checked when the app starts, when you start typing and by a daily job. The clock is the phone's clock, so a clock set back postpones the wipe.

## What is deleted

Each kind can be switched on or off. All are on by default.

- **Learned words**: learned words, personal dictionary, snippets, blocked words.
- **Clipboard**: the clipboard history and the clip that is on the phone right now.
- **Settings**: preferences, themes, background images, custom layouts and fonts, and your profiles.
- **Imported dictionaries**: dictionaries that you added yourself, and downloaded files.
- **The app lock and these settings**: when it is off, the lock and the triggers stay armed after a wipe.

If every kind except possibly the last one is on, the keys are destroyed first: nothing stored can be read again. If not everything is selected, the keys stay and only the selected files are deleted. A copy of those files made earlier could then still be read by someone who also gets the keys. The screen says which of the two applies.

## Panic

- **Panic shortcut on the app icon** (below): adds a shortcut to the launcher icon that only opens the question "Clear typing data?". Off by default.
- **Hold for 3 seconds to delete now**: holding this entry in the settings for 3 seconds starts the wipe at once.

### {#self_destruct_panic_shortcut}
Adds a shortcut to the app icon that opens the question "Clear typing data?". Nothing is deleted until you confirm. Default: off. A visible shortcut shows that the feature exists, so leave it off if you do not want that.

## What it cannot do

It cannot remove text that you already sent to apps, other apps' data, the phone maker's clipboard history, or a copy of the files made before a wipe together with the keys (Android may not stop that on a phone without rollback protection of the key store). Someone who forces you to enter the PIN gets what the PIN protects and can see that the app was reset.
