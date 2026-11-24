# Vim lessons

Six short lessons, in order. Each has a goal, the keys step by step, and the result you should see. Turn on **Vim mode** first (Settings > Preferences) and open a text field that holds several lines, for example a note. Tap the Esc key on the strip to open the Normal layout. [Vim mode](page:vim) explains the layouts. Every example below is also run through the engine when the app is built, so the results are exact.

The mark `|` in an example stands just before the character the cursor is on.

## Lesson 1: move around

**Goal:** go anywhere in a line and between lines without touching the screen.

1. Type a line of text, for example `one two three four five`, then tap Esc. The cursor is on the last character.
2. Press `0`. The cursor goes to the start of the line.
3. Press `w` twice. The cursor goes to the start of the third word.
4. Press `e`. The cursor goes to the end of that word.
5. Press `$`. The cursor goes to the end of the line.
6. Press `b`. The cursor goes back to the start of the last word.
7. Press `0`, then `fe`. The cursor jumps to the first `e` on the line; press `;` to jump to the next one.

```example
Before
one two thre|e four five
Keys
0wwe$b
After
one two three four |five
```

```example
Before
one two three fou|r five
Keys
0fe;
After
one two thr|ee four five
```

**Result:** you can reach any word with `w` `b` `e`, the ends of the line with `0` and `$`, and any letter with `f` and `;`. A count repeats a move: `3w` moves three words.

## Lesson 2: delete and change

**Goal:** remove a word, change a word and fix a typo.

1. Put the cursor on the first letter of a word: for example the `t` of `two` in `one two three`.
2. Press `d` and then `w`. The word and the space after it are deleted.
3. Put the cursor on a word you want to replace and press `c`, `i`, `w`. The word is deleted and the typing keyboard opens. Type the new word and tap Esc.
4. To fix a letter, put the cursor on it and press `r` and the right letter.
5. To remove a whole line, press `d` twice (`dd`).

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
one |two three
Keys
ciwtwo and a half<Esc>
After
one two and a hal|f three
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
one
|two
three
Keys
dd
After
one
|three
```

**Result:** `dw` deletes a word, `ciw` changes the word under the cursor, `r` replaces one letter and `dd` deletes a line. Undo any of them with `u`: the whole command comes back at once.

## Lesson 3: operators, motions and counts

**Goal:** see that every operator works with every motion.

1. An operator (`d`, `c`, `y`) waits for a motion. `d$` deletes to the end of the line, and so does `D`.
2. Add a count: `d2w` deletes two words, `2dd` deletes two lines.
3. Copy with `y` and paste with `p`: `yy` copies a line, then `p` puts it below the cursor line.
4. `yw` copies a word, `P` puts it before the cursor.

```example
Before
one |two three four
Keys
d2w
After
one |four
```

```example
Before
|one
two
three
Keys
2dd
After
|three
```

```example
Before
|one
two
Keys
yyjp
After
one
two
|one
```

```example
Before
one t|wo three
Keys
d$
After
one |t
```

**Result:** `operator` + `motion` is the pattern behind everything: learn a new motion and every operator already works with it. In the last example the cursor ends on the last character that is left, because the cursor of Normal mode cannot stand after the end of the line.

## Lesson 4: text objects

**Goal:** change or delete the thing around the cursor, wherever you are inside it.

1. Put the cursor anywhere inside a word. Press `d`, `a`, `w`. The word and one space are deleted.
2. Put the cursor anywhere between quotes. Press `c`, `i`, `"`. The text inside the quotes is deleted and you can type new text; the quotes stay.
3. Put the cursor anywhere between brackets. Press `d`, `i`, `(`. The text inside the brackets goes. `da(` takes the brackets too.
4. The brackets can span several lines.

```example
Before
say "he|llo there" now
Keys
ci"bye<Esc>
After
say "by|e" now
```

```example
Before
f(a, |b) x
Keys
di(
After
f(|) x
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

**Result:** `i` means inside and `a` means around. They work with `d`, `c`, `y` and in Visual mode.

## Lesson 5: repeat and macros

**Goal:** do the same change many times.

1. Change a word: put the cursor on it and press `ciw`, type `X`, tap Esc.
2. Move to another word with `w` and press `.`. The same change is made again.
3. To repeat several commands, record a macro: press `q`, then `a`, then your commands, then `q` again. The mode line shows `recording @a` while you record.
4. Play it with `@a`, or `3@a` three times.

```example
Before
|one two three
Keys
ciwX<Esc>w.w.
After
X X |X
```

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

**Result:** `.` repeats the last change and `@a` repeats a recorded macro. Both count as one change, so one undo takes back a whole macro.

## Lesson 6: search, replace and block edits

**Goal:** find text, replace it everywhere, and edit a column.

1. Press `/`, type a word on the typing keyboard, press Enter. The cursor jumps to the next match. Press `n` for the next one and `N` for the previous.
2. Press `*` on a word to search for that word.
3. Replace on the current line: press `:` and type `s/old/new/` then Enter. Add `g` at the end (`s/old/new/g`) to replace every match on the line.
4. Replace in the whole text: `:%s/old/new/g` and Enter.
5. Edit a column: press Ctrl-V (long press `v`), then `j` to extend the block down, then `I`, type the text and tap Esc. The text is added to every line of the block.

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
:%s/foo/bar/g<CR>
After
bar bar
|bar
```

```example
Before
|one
two
three
Keys
<C-v>jjI- <Esc>
After
|- one
- two
- three
```

**Result:** you can find and replace text with `/`, `*` and `:s`, and add the same text to many lines with Visual block. The command line is described in [Registers, macros, search and the command line](page:vim-power). If a key is missing or does nothing, see [Troubleshooting](page:troubleshooting).
