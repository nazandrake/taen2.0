# Privacy explained

Taen is built so that what you type stays on your phone and is stored only encrypted. This page explains each protection in plain words, and what it cannot do. The settings for these are in [Privacy settings](page:privacy-settings) and [Self-destruct](page:self-destruct). The full technical text is PRIVACY.md in the source of the app.

## No network

The app has no internet permission, no Wi-Fi or network-state permission, no contact access, no list of the apps that are installed and no Android backup. The build fails if a library brings one in. Files that are too large for the app (extra dictionaries, the voice model, next-word models) are not downloaded by the keyboard: the Download button opens a fixed address in your browser, and you pick the file yourself. Taen installs it only if its size and SHA-256 match the values built into the app.

## What is stored, and where

- **Your settings, clipboard history, personal dictionary, snippets, learned words and background images** are stored only as encrypted files (AES-256-GCM). The keys that open them are kept by the Android Keystore and cannot be exported from it.
- If a file is damaged, or its key is lost for good, the file is deleted and starts empty. A temporary error never deletes anything: the data is unavailable until it works again.
- **Learned words** are kept in memory and saved as one encrypted file for each language.
- **Voice typing**: audio and text stay in memory, are cleared after use, and are never written or logged. Recognition runs on the phone ([Voice typing](page:voice)).
- **Next-word models** read only the text before the cursor, keep nothing on disk and run only where learning is allowed ([Downloads](page:downloads)).
- **Your pictures** are stored encrypted, and location data is removed when you import them ([GIFs and stickers](page:media)).
- The app keeps **no crash reports, no logs and no diagnostic files**.

## Incognito: what is not remembered

Nothing is learned, no clipboard entry and no recent emoji is kept, and no learned word is suggested, in:

- password fields (all four kinds),
- any field that asks for no personalized learning,
- an app on your incognito list,
- every field while **Force incognito mode** is on,
- every field while the device or screen is locked.

Password fields also get no suggestion strip, no key previews and no clipboard suggestion.

## The locks

- **Screen lock**: when the screen turns off, the encrypted data (clipboard history, learned words, personal dictionary, protected settings) is removed from memory and its key is forgotten, until you unlock the phone. While locked nothing is stored, learned or shown. Learned words are saved just before the lock.
- **App lock**: an optional PIN of 4 to 16 digits, entered on a digit pad inside the app, never through a keyboard. It guards the settings (including backup and restore) and the clipboard history. After the fifth wrong try the lock waits 30 seconds, then doubles the wait up to one hour, and the wait survives a restart. If the lock cannot be read, it counts as closed. An unlock lasts 1, 5, 15 or 60 minutes from your last activity and ends when the screen turns off.

## The wipe

Self-destruct can delete the keys and the data on a trigger you choose: a duress PIN, a trigger word, too many wrong PINs, a long time without use, or a panic shortcut. Everything is off until you switch it on, and there is a test mode that deletes nothing. First the keys are destroyed, so what was stored cannot be read again, then the files are deleted and the keyboard restarts as new. See [Self-destruct](page:self-destruct).

## Backup and export

A backup is one encrypted file. Argon2id and AES-256-GCM protect it under a passphrase of at least 12 characters, which is asked for and never stored. There is no plain option. A wrong passphrase or a damaged file changes nothing, and an import is checked completely before anything is replaced. It is found under Backup and restore in [Advanced](page:advanced).

## Screen capture and snooping

- While the keyboard is open, screenshots and screen recordings are refused or show black (**Hide the keyboard from screenshots and screen recording**). Every screen of the app itself is protected too.
- A notice shows when an enabled accessibility service can read the screen or what you type (**Warn about accessibility services**). Taen has no accessibility service of its own.
- When another app's window covers the keyboard, Taen warns, or blocks typing if you choose so (**Windows over the keyboard**). Taen draws no overlay of its own.

## Vim mode

What Vim mode remembers (registers, macros, marks, searches, the command line history) stays in memory only, and is forgotten when the screen locks, at a wipe and when Vim mode is turned off. The `"+` and `"*` registers are the system clipboard. See [Vim mode](page:vim).

## What a keyboard cannot protect against

Taen sees what you type, so it can protect what it stores and shows, not what other software does with the same text.

- The app you type into receives everything and can store or send it.
- Accessibility services can read other apps' fields and the screen. Taen can only warn and, from Android 14, hide its own keys.
- Content capture, autofill services and the clipboard history of the phone maker work through Android, not through the keyboard.
- Root, a compromised system, and a computer that is allowed to use adb bypass the keyboard.
- Hardware keyboards bypass the keyboard.
- Someone who watches you type, a camera, and the sound or vibration of the keys are outside the keyboard. The key sound is off by default.
- A copy of the files taken before a wipe could be restorable, together with its key, on a phone without rollback protection of the key store.
- Someone who forces you to give up your PIN gets what the PIN protects.
- Play Protect's "improve harmful app detection" may upload unknown apps: turn it off if you do not want that.
- The secure window flag does not stop a person from photographing the screen, and an app can ignore it.

If you want the strictest setting in one step, apply the **Maximum privacy** template ([Profiles and templates](page:profiles)). It only ever makes privacy settings stricter.
