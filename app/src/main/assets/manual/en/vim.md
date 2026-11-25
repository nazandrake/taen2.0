# Vim mode

Vim mode gives the keyboard the Normal and Visual modes of the Vim editor: move, delete, change and copy text with short key sequences. It is for people who know Vim or want to learn it. It is off until you turn it on.

## Turn it on

1. Open Settings > Preferences.
2. Turn on **Vim mode** (see [Preferences](page:preferences)).
3. Open any text field. An **Esc** key now shows on the suggestion strip and in the toolbar.
4. Tap Esc. The Normal layout opens.

If you also want the Vim commands `>` and `<` to indent with a tab instead of four spaces, turn on **Vim indent with a tab** in the same screen.

## The two layouts

The keys of the Normal and Visual layouts are commands, not typing. Nothing they do is learned, suggested, corrected or added to your recent emoji.

The Normal layout has ten keys in each of five rows. Long press a key to reach the capital letter and the other commands that hang on it.

- Row 1: `:` `/` `?` `n` `N` `.` `q` `@` `%` `~`. Long press `n` for `*`, `N` for `#`, `q` for `m` (marks), `@` for `"` (registers), `%` for `'` and `~` for the backtick.
- Row 2: the digits, for counts. Type a count first: `3w` moves three words.
- Row 3: **Esc** `w` `e` `r` `t` `y` `u` `i` `o` `p`. Long press `w` for `W` and also `}` and `)`.
- Row 4: `a` `s` `d` `f` `g` `h` `j` `k` `l` `;`. Long press `h` for `<` and `l` for `>`; the other letters give their capitals.
- Row 5: Ctrl-R (long press for Ctrl-A and Ctrl-X), `x` `c` `v` `b` `^` `$` `,` and **ABC**. Long press `v` for `V` and Ctrl-V; long press `b` for `B`, `{` and `(`.

The **ABC** key starts typing at the cursor, like `i`. Visual mode has its own layout with the same keys.

## The mode line

A line above the keys shows the mode (NORMAL, VISUAL, V-LINE, V-BLOCK), what you have typed so far (a count, a register, an operator), `recording @a` while a macro is being recorded, and the command line while you type one.

## Back to typing

- `i` `a` `I` `A` `o` `O` `R` `gi`, and the change commands (`cw`, `cc`, `s`, `S`, `C`) give you the normal typing keyboard back. Esc, from the strip or the toolbar, returns to Normal mode.
- Keys that ask for one more character (`f` `F` `t` `T` `r`, a register after `"`, a mark after `m` `'` or the backtick, `q`, `@`) and the command line (`:` `/` `?`) show the normal typing keyboard for what you type, so that you can reach any letter or symbol.
- When you leave the field, the typing keyboard is back the next time you type.

## Safe by design

- Vim mode is not available in password fields and while the device is locked. The Esc key is not shown in a password field, and a message says so if a Vim key is used there.
- What Vim mode remembers (registers, macros, the repeat, marks, searches, the command line history) stays in memory only. It is forgotten when the screen locks, at a wipe and whenever Vim mode is turned off. See [Privacy explained](page:privacy).
- The command line text never goes into the field, and it is never learned or suggested.
- Where clipboard history may not keep a copy (a password field, an app on your incognito list, incognito mode), a command still does its work, but nothing is remembered and a recording stops.

## How edits are made

A command that changes text reads the text of the field (up to 20 000 characters), changes it and writes it back as one change. The keyboard asks for the text again just before the change. If the app does not share the whole text, or the text changed in the meantime, or some text is selected, nothing happens and a short message tells you. If an app does not share its text at all, only cursor motions work, as cursor keys.

- One command is one change. A macro, a repeat with `.` and a command line that changes text are also one change.
- Undo (`u`) and redo (Ctrl-R) use the undo and redo of the keyboard, so they undo the whole command.
- The results of the commands are checked against the real Vim with many thousands of cases. The examples in this manual are run through the same engine every time the app is built.

## Where to go next

- [Motions and edits](page:vim-keys): moves, operators, text objects, counts.
- [Registers, macros, search and the command line](page:vim-power).
- [What is not supported](page:vim-limits).
- [Vim lessons](page:vim-lessons): short tutorials with the keys and the result of each step.
- [Troubleshooting](page:troubleshooting): a Vim key is missing.
