# Changelog

## 2.0.3

- New: Vim mode (Settings > Preferences, off by default). With it on, an Esc key appears on the suggestion strip. One tap opens
  Normal mode with its own keys: move with h l w b e W B E 0 ^ $ gg G f F t T ; , (j and k go by visual line), type a count first
  (3w), and use d, c, y with a motion or twice (dd, cc, yy), x X D C s S, r and a letter, p P, u and Ctrl-R (they use the
  undo and redo of the keyboard), v and V for Visual mode with d, c, y and x, and the text objects iw aw and quotes and brackets on
  the line (diw, ci", da(). i a I A o O, and c commands, give you the normal keyboard back; Esc returns. A line above the keys shows
  the mode (NORMAL, VISUAL, V-LINE) and what you typed so far. English and Bulgarian.
- Vim mode is not available in password fields. The text it cuts or copies is kept in one memory-only register, only where clipboard
  history may keep a copy; it is forgotten when the screen locks, at a wipe and when you turn Vim mode off, and it never reaches the
  system clipboard. If an app does not share its text, or changes it while a command runs, the command does nothing and says so; only
  cursor motions then work, as cursor keys.
- Not in this version: `.` (repeat), macros, marks, search with /, named registers, ex commands, J, counts on text objects, and
  brackets that span lines.

## 2.0.2

- Bulgarian and other languages: names (Джеймс) and abbreviations (САЩ, ЦСКА) that no word list contains are no longer underlined in red by Taen's
  spell checker, unless the dictionary has a close word that it suggests instead. In a Wikipedia text, 2 in 3 of the Bulgarian words that the dictionary lacks
  are such names and abbreviations. A misspelled lowercase word is still underlined, and a language without a dictionary is not underlined at all.
- New setting Privacy > Clipboard keys in password fields (off by default). When it is on, password and number password fields get the paste, select all
  and clear all keys and your pinned keys like other fields. A copy made in a password field is marked sensitive: Android hides its preview, and Taen
  never stores, suggests or logs it. "Suggest clipboard content" no longer offers any clip marked sensitive, from Taen or another app, not even as stars.
- The keyboard is 10 percent taller in portrait by default, so that letters are easier to hit. A height you set yourself is kept; set 100 percent
  under Appearance to go back.
- Fixed: a clip copied in a password field before the keyboard restarted could be taken into the clipboard history some seconds after the restart.
  A clip that was on the clipboard before the keyboard started is now never stored.
- Setting a trigger word: the dialog's OK button could end up behind the taller keyboard; the text area of the dialog now scrolls.
- make sign now signs without asking for the keystore password: run make keystore-pass once to keep it in your desktop keyring or in a private file.
## 2.0.1

- New Quick Settings tile "Switch keyboard": it opens the system keyboard picker, after the unlock when the phone is locked.
  Add it from the Quick Settings edit screen. It needs no permission and stores nothing.
- A note in About and in the setup guide (English and Bulgarian): some banking apps start only with a system keyboard because of
  their app shielding, which Taen cannot bypass; use the tile to switch.
- The About text now describes Taen: a private offline keyboard based on HeliBoard and AOSP.

## 2.0.0

- Next-word suggestions from optional on-device models, voice typing, pictures, profiles and templates, optional downloads,
  cursor navigation and several cursors, the Android 16 keyboard switcher. See `README.md` and `PRIVACY.md`.
