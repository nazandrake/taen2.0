# Vim: what is not supported

Vim mode is the editing part of Vim on a keyboard. A keyboard sees one text field, not a screen, a file or a window, and it must never hang or leak what you type. This page lists what is left out and why. A command that is not supported does nothing, and often a short message says why.

## Left out because a keyboard has no screen or file

- **Screen commands**: `H` `M` `L`, `Ctrl-D` `Ctrl-U` `Ctrl-F` `Ctrl-B` `Ctrl-E` `Ctrl-Y`, and the `z` commands (`zz`, `zt`, `zb`). The keyboard cannot see which part of the text the app shows, or scroll it. The message is "Screen commands are not offered".
- **Saving and quitting**: `:w`, `:q`, `:wq`, `:x` and similar. The app you type into saves its own text. The message is "A keyboard cannot save or quit".
- **Highlight**: `:noh` says that nothing is highlighted, because a field cannot show search highlights.
- **Windows, tabs, buffers, files, folds, shell commands, Vim scripts, mappings and options** (`:e`, `:split`, `:!`, `:map`, `:set` and the like): there is nothing for them to work on. The ex commands that exist are the ones listed on [Registers, macros, search and the command line](page:vim-power); any other shows "Not a valid command or range".

## Not built yet

- Sentence and paragraph text objects: `is` `as` `ip` `ap`. The sentence moves `(` and `)` and the paragraph moves `{` and `}` work.
- In patterns: `\n` and `\v`, and back references. A pattern with one of them shows "This pattern is not supported here".
- The `c` (confirm) flag of `:s`.
- `ge`.
- Undo inside a macro or a command line. A macro is one change, so one undo takes all of it back, and the message "Undo cannot run inside a macro or a command" shows if a macro tries.

## Limits that keep the keyboard safe

- Text: Vim mode edits a field only when the app shares the whole text, up to 20 000 characters. Otherwise nothing changes and a message says so. Without any text, only cursor motions work, as cursor keys.
- A register, a macro and the command line hold at most 20 000 characters. A macro records at most 2 000 keys.
- A play of keys (a macro, a repeat with `.`, `:normal`, `:global`) stops at 20 000 keys or 2.5 seconds.
- A search, a pattern and `:substitute` stop at 3 million steps or 0.4 seconds. At a limit the command changes nothing.
- Vim mode is not available in password fields or while the device is locked.

## What differs from Vim on purpose

These are the choices of this keyboard. The examples of this manual do not depend on them.

- Columns count characters, not screen columns. A tab is one column, and a wide character is one column, so `j` and `k` over a tab or a wide character can land on a different column than in Vim.
- `J` joins with one space, never two after a full stop.
- A number with leading zeros is a decimal number for Ctrl-A and Ctrl-X. Numbers that start with `0x` or `0b` are hexadecimal and binary.
- `>` and `<` indent with four spaces, or with a tab if you turn on **Vim indent with a tab**.
- `iw` and `aw` on an empty line, and `aw` on blanks at the end of a line, stay on the line. Vim goes on over the line break.
- `%` does not look at quotes. The paragraph macros of Vim (`.IP` and the like) are not looked at by `{` and `}`.
- The unnamed register and the others are in memory only. The system clipboard is reached through `"+` and `"*`.

See also [Vim mode](page:vim) and [Troubleshooting](page:troubleshooting).
