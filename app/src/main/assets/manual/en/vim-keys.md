# Vim: motions and edits

This page lists the moves and edits of Normal mode, with examples. For the layouts and the safety rules see [Vim mode](page:vim). For registers, macros, search and the command line see [Registers, macros, search and the command line](page:vim-power).

## How to read the examples

Each example shows the text **Before**, the **Keys** you press and the text **After**. The cursor of Normal mode stands on a character: the mark `|` is placed just before that character. A key written in angle brackets is a special key: `<Esc>` is the Esc key and `<CR>` is Enter. The examples are run through the real Vim engine of the keyboard every time the app is built, so they are always right.

## Motions

A motion moves the cursor. Type a count first to repeat it: `3w` moves three words.

- `h` `l`: one character left and right (they stay on the line).
- `j` `k`: one line down and up, keeping the column. They work as the cursor keys of the field.
- `w` `b` `e`: to the start of the next word, the start of the previous word, the end of the word. A word is letters, digits and underscores, or a run of other marks.
- `W` `B` `E`: the same, but a word is anything between spaces.
- `0` `^` `$`: start of the line, first character that is not a blank, end of the line.
- `gg` `G`: first line, last line. With a count, `3G` goes to line 3.
- `f` `F` `t` `T` and a character: to the next (or previous) occurrence of the character on the line, or to just before it. `;` repeats and `,` repeats the other way.
- `%`: to the matching bracket.
- `{` `}`: to the previous and next empty line. `(` `)`: to the previous and next sentence.

```example
Before
|one two three four five
Keys
3w
After
one two three |four five
```

```example
Before
|one two
Keys
e
After
on|e two
```

```example
Before
one two |three
Keys
b
After
one |two three
```

```example
Before
|one two
Keys
$
After
one tw|o
```

```example
Before
    in|dented
Keys
^
After
    |indented
```

```example
Before
|one two six
Keys
fs
After
one two |six
```

```example
Before
|abc,def
Keys
t,
After
ab|c,def
```

```example
Before
|a-b-c-d
Keys
f-;
After
a-b|-c-d
```

```example
Before
|(a [b] c)
Keys
%
After
(a [b] c|)
```

```example
Before
ab|cd
wxyz
Keys
j
After
abcd
wx|yz
```

```example
Before
|a
b

c
Keys
}
After
a
b
|
c
```

```example
Before
one
two
|three
Keys
gg
After
|one
two
three
```

```example
Before
|one
two
three
Keys
2G
After
one
|two
three
```

## Operators

An operator works on the text that a motion or a text object covers. Type the operator, then the motion: `dw` deletes to the next word. Type the operator twice to work on whole lines: `dd`.

- `d`: delete. `c`: change (delete, then type). `y`: yank (copy).
- `dd` `cc` `yy`: the whole line. `D` `C`: to the end of the line.
- `x` deletes the character under the cursor, `X` the one before it. `s` changes one character, `S` the line.
- `r` and a character replaces the character under the cursor.
- `J` joins the next line to this one with a space; `gJ` joins without a space.
- `~` switches the case of the character and moves right. `g~`, `gu` and `gU` and a motion switch, lower and upper the case of the text.
- `>` and `<` and a motion indent and unindent by four spaces (or a tab: see **Vim indent with a tab** in [Preferences](page:preferences)).
- Ctrl-A and Ctrl-X add one to and subtract one from the number at or after the cursor; a count is the amount.
- `p` puts the yanked or deleted text after the cursor, `P` before it. Whole lines go below or above the line.
- `u` undoes and Ctrl-R redoes. They use the undo and redo of the keyboard.

```example
Before
one |two three
Keys
dw
After
one |three
```

```example
Before
one t|wo three
Keys
D
After
one |t
```

```example
Before
one
|two
three
Keys
dd
After
one
|three
```

```example
Before
one |two three
Keys
cwTWO<Esc>
After
one TW|O three
```

```example
Before
one t|wo three
Keys
ciwX<Esc>
After
one |X three
```

```example
Before
one
|two
three
Keys
yyp
After
one
two
|two
three
```

```example
Before
|ab
Keys
xp
After
b|a
```

```example
Before
|cat
Keys
rb
After
|bat
```

```example
Before
|abc
Keys
~~
After
AB|c
```

```example
Before
one |two
Keys
gUiw
After
one |TWO
```

```example
Before
|one
two
Keys
J
After
one| two
```

```example
Before
|one
Keys
>>
After
    |one
```

```example
Before
item |7
Keys
5<C-a>
After
item 1|2
```

## Text objects

A text object is a piece of text around the cursor. Use it after an operator (or in Visual mode): `i` selects the inside, `a` selects the object and its surroundings.

- `iw` `aw`: a word, and a word with the space after it. `iW` `aW`: the same for a word between spaces.
- `i"` `a"`, `i'` `a'`, and the backtick versions: inside quotes, and with the quotes.
- `i(` `a(` (also `ib` `ab`), `i[` `a[`, `i{` `a{` (also `iB` `aB`), `i<` `a<`: inside brackets, and with the brackets. Brackets can span several lines.
- A count makes the object bigger: `2iw` is two words.

Sentence and paragraph objects (`is` `as` `ip` `ap`) are not supported. See [What is not supported](page:vim-limits).

```example
Before
say "he|llo" now
Keys
ci"X<Esc>
After
say "|X" now
```

```example
Before
f(a, |b) x
Keys
da(
After
f| x
```

```example
Before
one |two three
Keys
daw
After
one |three
```

```example
Before
call(a, [b|, c], d)
Keys
di[
After
call(a, [|], d)
```

```example
Before
f(
    |a,
    b
)
Keys
di(
After
f(
|)
```

```example
Before
|one two three four
Keys
d2w
After
|three four
```

## Counts

A count goes before a command (`3dd`, `3x`), or between an operator and its motion (`d3w`). A count before the operator and a count before the motion multiply: `2d3w` deletes six words.

```example
Before
|one
two
three
four
Keys
2dd
After
|three
four
```

```example
Before
|abcdef
Keys
3x
After
|def
```

## Insert commands

- `i` inserts before the cursor, `a` after it. `I` inserts at the first non-blank of the line and `A` at the end of the line.
- `o` opens a new line below and `O` above.
- After the typing keyboard is back, Esc (the key on the strip) returns to Normal mode with the cursor on the last character you typed.

```example
Before
|one
Keys
Atwo<Esc>
After
onetw|o
```

```example
Before
|one
three
Keys
otwo<Esc>
After
one
tw|o
three
```
