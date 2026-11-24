# Vim: registers, macros, search, command line

The basics are in [Motions and edits](page:vim-keys). This page covers the commands that remember things or work on many lines. Everything Vim mode remembers stays in memory and is forgotten at the screen lock (see [Vim mode](page:vim)).

## Repeat with a dot

`.` repeats the last change, with its count. Give `.` a count to change the count of the repeat.

```example
Before
|one two three
Keys
dw.
After
|three
```

```example
Before
|one two
Keys
ciwX<Esc>w.
After
X |X
```

## Registers

A register is a named place for text. Without a name, delete and yank use the unnamed register, and `p` puts it.

- `"a` to `"z`: your own registers. Type the register before the command: `"ayy` yanks a line into register `a`, `"ap` puts it. An uppercase name (`"Ayy`) adds to the register.
- `"0`: the last yank. `"1` to `"9`: the last deletions of whole lines or more than a line, newest first.
- `"-`: the last small delete. `"_`: the black hole; it keeps nothing.
- `".`: the last text you typed. `"/`: the last search. `":`: the last command line.
- `"+` and `"*`: the system clipboard. `"+y` copies to the clipboard and `"+p` pastes from it, through the same code as the copy key and the paste key of the keyboard (so a copy keeps the sensitive flag of a password field).
- In insert mode, Ctrl-R and a register name types the register. Hold the Ctrl key for it.

Type the `"` from the long press of `@`.

```example
Before
|one
two
Keys
"ayyj"ap
After
one
two
|one
```

```example
Before
|one
two
three
Keys
yyj"_ddp
After
one
three
|one
```

```example
Before
|one
two
three
Keys
"ayyj"AyyG"ap
After
one
two
three
|one
two
```

## Macros

A macro records keys and plays them back.

- `q` and a letter starts recording into that register. The mode line shows `recording @a`. `q` stops.
- `@a` plays the macro in register `a`. `@@` plays the last one again. A count plays it many times: `5@a`.
- A macro holds at most 2 000 keys, and a play stops at 20 000 keys or 2.5 seconds, so a wrong macro cannot hang the keyboard. A macro or a repeat stops at the first command that fails.
- Undo cannot run inside a macro. A macro is one change: one undo takes all of it back.

```example
Before
|one
two
three
Keys
qaA;<Esc>jq2@a
After
one;
two;
three|;
```

## Marks

`m` and a letter sets a mark at the cursor. `` `a `` goes to the mark, `'a` goes to the line of the mark. `''` and ` `` ` go back to where you jumped from. Long press `q` for `m`, `%` for `'` and `~` for the backtick. Marks go away when the text is changed by anything but Vim mode, and when you leave the field.

```example
Before
|one two three four
Keys
wmawd`a
After
one |three four
```

## Search

- `/text` and Enter searches forward, `?text` backward. `n` goes to the next match and `N` to the previous.
- `*` and `#` search for the word under the cursor, forward and backward. `g*` and `g#` do not need the whole word.
- A search works after an operator: `d/x` and Enter deletes up to the match.
- An offset after the pattern (`/text/e`, `/text/+1`) moves the cursor from the match.
- Patterns are the usual Vim ones: `.` `*` `\+` `\=` `^` `$` `\<` `\>` `[ ]` `\( \)` `\|` `\c` `\C`. They are limited to 3 million steps and 0.4 seconds, so no pattern can hang the keyboard. See [What is not supported](page:vim-limits) for what is missing.
- The search text is typed on the typing keyboard into the engine only. It never goes into the field.

```example
Before
|one two three two
Keys
/two<CR>n
After
one two three |two
```

```example
Before
|foo bar foo
Keys
*
After
foo bar |foo
```

```example
Before
foo bar |foo
Keys
#
After
|foo bar foo
```

```example
Before
one two three |two
Keys
?two<CR>
After
one |two three two
```

```example
Before
|one two three
Keys
d/th<CR>
After
|three
```

## The command line

`:` opens the command line. Type the command on the typing keyboard (it appears on the mode line) and press Enter. Esc, from the strip, cancels. Backspace on an empty line cancels too. The up and down keys walk through the history of the command line, and Ctrl-U clears it.

A command can start with a range: `:2,4d` deletes lines 2 to 4. A range is made of:

- numbers: `3`. `.` is the current line and `$` the last. `%` is the whole text.
- marks (`'a`), the last Visual selection (`'<,'>`, which Vim types for you when you press `:` in Visual mode) and offsets (`.+1`, `$-2`).
- a search: `/pattern/`.
- two parts joined by a comma: `.,+2`.

The commands:

- `:d` delete lines, `:y` yank, `:pu` put the register below the line (`:pu!` above).
- `:m` move lines below an address (`:m0` to the top, `:m$` to the end). `:t` (or `:co`) copy lines below an address.
- `:j` join lines (`:j!` joins without a space). `:>` and `:<` indent.
- `:s/pattern/replacement/flags` substitute. With a range it works on those lines, else on the current line. Flags: `g` all matches on the line, `i` ignore case, `I` do not. Use `&` in the replacement for the matched text. The `c` flag (confirm) is not supported.
- `:g/pattern/command` runs the command on every line that matches. `:v/pattern/command` (or `:g!`) runs it on every line that does not.
- `:normal keys` plays Normal mode keys on each line of the range.
- A line number alone (`:3`) goes to that line. `:k` and a letter (or `:mark`) sets a mark on a line.
- `:reg` and `:marks` show registers and marks in a message. `:undo` and `:redo` use the keyboard's undo and redo.
- `:w`, `:q` and the like say that a keyboard cannot save or quit.

A command line that changes text is one change.

```example
Before
|a b a
Keys
:s/a/x/g<CR>
After
|x b x
```

```example
Before
|foo foo
foo
Keys
:%s/foo/bar/<CR>
After
bar foo
|bar
```

```example
Before
|cat dog
Keys
:s/cat/<&>/<CR>
After
|<cat> dog
```

```example
Before
|a b
a b
a b
Keys
:1,2s/a/x/<CR>
After
x b
|x b
a b
```

```example
Before
|a
b
Keys
yy:2pu<CR>
After
a
b
|a
```

```example
Before
|a1
b
a2
c
Keys
:g/a/d<CR>
After
b
|c
```

```example
Before
|a1
b
a2
c
Keys
:v/a/d<CR>
After
a1
|a2
```

```example
Before
|a
b
c
d
Keys
:2,3d<CR>
After
a
|d
```

```example
Before
|a
b
c
Keys
:.,+1d<CR>
After
|c
```

```example
Before
|a
b
c
Keys
:1m$<CR>
After
b
c
|a
```

```example
Before
|a
b
Keys
:t.<CR>
After
a
|a
b
```

```example
Before
|a
b
c
Keys
:1,3j<CR>
After
|a b c
```

```example
Before
|a
b
Keys
:%normal A;<CR>
After
a;
b|;
```

## Visual mode

Visual mode selects text; then an operator acts on the selection.

- `v` selects by character, `V` by line, Ctrl-V (long press `v`) by block. The mode line shows the mode.
- Move to change the selection. `o` jumps to the other end. `iw`, `a(` and the other text objects select too.
- Then use `d`, `c`, `y`, `x`, `>`, `<`, `~`, `u`, `U`, `J`, `r` and a character, `p`, and `:` for a command line on the lines selected.
- `gv` selects the last selection again. Esc leaves Visual mode.

```example
Before
one |two three
Keys
viwd
After
one | three
```

```example
Before
a
|b
c
Keys
Vd
After
a
|c
```

```example
Before
|a
b
c
Keys
Vjd
After
|c
```

```example
Before
|abc def
Keys
veU
After
|ABC def
```

## Visual block

Ctrl-V selects a rectangle. The field shows the block as its span of text. With a block you can:

- `d`, `c`, `y`, `x`, `r` and a character: delete, change, yank, delete and replace the block.
- `I` inserts the text you type at the start of the block on every line; `A` appends at its end. `$` before `A` appends at the end of each line, whatever its length.
- `o` and `O` move to the other corners.

```example
Before
|one
two
three
Keys
<C-v>jjI# <Esc>
After
|# one
# two
# three
```

```example
Before
|abcd
efgh
Keys
<C-v>jld
After
|cd
gh
```

```example
Before
|ab
cde
Keys
<C-v>j$A!<Esc>
After
|ab!
cde!
```
