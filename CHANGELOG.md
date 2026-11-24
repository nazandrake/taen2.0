# Changelog

## 2.0.3

- New: Vim mode (Settings > Preferences, off by default). An Esc key on the suggestion strip opens Normal mode, with motions, operators, text
  objects, counts, registers, macros, marks, search and a command line. Esc brings the normal keyboard back.
- Vim mode is not available in password fields or while the device is locked, and what it remembers stays in memory only.
- New: the first time you open Taen's settings after an update, a box like this one shows what is new in that version, once.
- Help: a manual for every setting and for Vim mode, with examples and lessons, under Settings › Help.

Details of the Vim mode:

- New: Vim mode (Settings > Preferences, off by default). With it on, an Esc key appears on the suggestion strip. One tap opens
  Normal mode with its own keys (the Normal layout has more on long press): move with h l w b e W B E 0 ^ $ gg G f F t T ; , % { } ( ),
  type a count first (3w), and use d, c, y with a motion, a text object (iw aw, ip ap, quotes, brackets over several lines, with counts) or twice
  (dd, cc, yy), x X D C s S r R, J gJ, ~ and g~ gu gU, > and < (a tab or four spaces: a setting), Ctrl-A and Ctrl-X on numbers, p P, u and
  Ctrl-R (the undo and redo of the keyboard), v V and Ctrl-V (a block, shown as its span in the field) with d c y x r I A and more, gv, gi,
  i a I A o O and c commands (they give you the normal keyboard back; Esc returns), and in insert mode Ctrl-R (a register), Ctrl-W, Ctrl-U,
  Ctrl-H and Ctrl-O through the Ctrl key.
- Also new: `.` repeats the last change (with a count); macros (q and a letter to record, q to stop, @ and a letter, @@, with counts; at most
  2 000 keys; a macro, a repeat or a command line has one budget of 20 000 keys, 100 000 steps and 2.5 seconds, and no command builds text
  over 20 000 characters; macros, :normal, @:, @@ and . can call one another at most 10 levels deep: at a limit nothing is changed); registers `"a` to `"z` (A to Z add), `"0` to `"9`, `"-`, `"_`, `".`, `"/`, `":`, and `"+` and `"*`
  for the system clipboard through the copy and paste keys' own code; marks (m, ', `, '' and the last selection and change); search (/ ? n N *
  # g* g#, with offsets, and the usual patterns: . * \+ \= ^ $ \< \> [ ] \( \) \| \c \C) that cannot hang the keyboard; and a command line
  on the strip with ranges and :s :d :y :pu :m :t :co :j :> :< :normal :g :v :reg :marks :undo :redo (:w and :q say that a keyboard cannot
  save or quit). A command, a macro or a command line that changes text is one change. Screen commands (H M L, scrolling, zz) are not offered.
  A line above the keys shows the mode, what you typed so far, a recording and the command line. English and Bulgarian.
- Vim mode is not available in password fields or while the device is locked. What it remembers (registers, macros, the repeat, marks,
  searches, the command line history) is in memory only and is kept only where clipboard history may keep a copy; it is forgotten when the
  screen locks, at a wipe and whenever Vim mode is off (also after a restored backup); where it may keep nothing it is asked again at every
  write, and an insert during which incognito was switched on, or the field changed, keeps nothing at all (not its repeat, its macro or its last inserted text). The command line text never goes into the field and is never learned
  or suggested; :reg and :marks show their list in the line above the keys, never in a pop-up message; a command line that Vim mode does
  not understand is refused, not run in part. If an app does not share its text, or changes it while a command runs, the command does nothing and says so;
  only cursor motions then work, as cursor keys.
- Not in this version: sentence text objects (is as), `\n` and `\v` in patterns, back references, the `c` flag of :s, Vim
  scripts, `ge`, and undo inside a macro.

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
