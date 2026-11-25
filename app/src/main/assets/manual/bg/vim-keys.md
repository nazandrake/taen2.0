# Vim: движения и промени

Тази страница изброява движенията и промените на нормалния режим, с примери. За подредбите и правилата за безопасност вижте [Режим Vim](page:vim). За регистри, макроси, търсене и командния ред вижте [Регистри, макроси, търсене и командният ред](page:vim-power).

## Как да четете примерите

Всеки пример показва текста **Преди**, **Клавишите**, които натискате, и текста **След**. Курсорът на нормалния режим стои върху един знак: знакът `|` е поставен точно пред този знак. Клавиш, написан в ъглови скоби, е специален клавиш: `<Esc>` е клавишът Esc, а `<CR>` е Enter. Примерите се пускат през истинския двигател на Vim на клавиатурата при всяко сглобяване на приложението, така че винаги са верни.

## Движения

Движението мести курсора. Напишете първо бройка, за да го повторите: `3w` се движи с три думи.

- `h` `l`: един знак наляво и надясно (остават на реда).
- `j` `k`: един ред надолу и нагоре, със запазване на колоната. Работят като клавишите за курсора на полето.
- `w` `b` `e`: към началото на следващата дума, началото на предишната дума, края на думата. Дума са букви, цифри и долни черти или поредица от други знаци.
- `W` `B` `E`: същото, но дума е всичко между интервали.
- `0` `^` `$`: началото на реда, първият знак, който не е интервал, краят на реда.
- `gg` `G`: първият ред, последният ред. С бройка `3G` отива на ред 3.
- `f` `F` `t` `T` и знак: към следващото (или предишното) срещане на знака на реда, или точно пред него. `;` повтаря, а `,` повтаря в обратната посока.
- `%`: към съответната скоба.
- `{` `}`: към предишния и следващия празен ред. `(` `)`: към предишното и следващото изречение.

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

## Оператори

Операторът действа върху текста, който покрива движение или текстов обект. Напишете оператора, после движението: `dw` изтрива до следващата дума. Напишете оператора два пъти, за да работи върху цели редове: `dd`.

- `d`: изтриване. `c`: промяна (изтрива, после пишете). `y`: копиране (yank).
- `dd` `cc` `yy`: целият ред. `D` `C`: до края на реда.
- `x` изтрива знака под курсора, `X` знака преди него. `s` променя един знак, `S` реда.
- `r` и знак замества знака под курсора. `R` замества, докато пишете: отваря се клавиатурата за писане и когато натиснете Esc, написаният от вас текст е заел мястото на същия брой знаци.
- `J` съединява следващия ред с този с интервал; `gJ` съединява без интервал.
- `~` обръща регистъра на знака и се мести надясно. `g~`, `gu` и `gU` и движение обръщат, правят малки и правят главни буквите на текста.
- `>` и `<` и движение правят и махат отстъп от четири интервала (или табулация: вижте **Vim отстъп с табулация** в [Предпочитания](page:preferences)).
- Ctrl-A и Ctrl-X добавят и изваждат единица от числото при курсора или след него; бройката е стойността.
- `p` поставя копирания или изтрития текст след курсора, `P` преди него. Цели редове отиват под или над реда.
- `u` отменя, а Ctrl-R повтаря. Ползват отмяната и повтарянето на клавиатурата.

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
|xyz1
Keys
Rab<Esc>
After
a|bz1
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

## Текстови обекти

Текстовият обект е парче текст около курсора. Ползвайте го след оператор (или във визуален режим): `i` избира вътрешността, `a` избира обекта и обкръжението му.

- `iw` `aw`: дума и дума с интервала след нея. `iW` `aW`: същото за дума между интервали.
- `i"` `a"`, `i'` `a'` и вариантите с обратен апостроф: вътре в кавичките и с кавичките.
- `i(` `a(` (също `ib` `ab`), `i[` `a[`, `i{` `a{` (също `iB` `aB`), `i<` `a<`: вътре в скобите и със скобите. Скобите могат да обхващат няколко реда.
- `ip` `ap`: абзац (редовете до следващия празен ред) и абзац с празните редове след него. Работят върху цели редове.
- Бройката прави обекта по-голям: `2iw` са две думи.

Текстовите обекти за изречения (`is` `as`) не се поддържат. Вижте [Какво не се поддържа](page:vim-limits).

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
one
t|wo

three
Keys
dip
After
|
three
```

```example
Before
one
t|wo

three
Keys
dap
After
|three
```

```example
Before
|one two three four
Keys
d2w
After
|three four
```

## Бройки

Бройката стои пред команда (`3dd`, `3x`) или между оператор и движението му (`d3w`). Бройката може да е до 999. Бройка пред оператора и бройка пред движението се умножават: `2d3w` изтрива шест думи.

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

## Команди за въвеждане

- `i` вмъква преди курсора, `a` след него. `I` вмъква при първия знак на реда, който не е интервал, а `A` в края на реда.
- `o` отваря нов ред отдолу, а `O` отгоре.
- `gi` вмъква отново там, където последно сте спрели да пишете.
- След като клавиатурата за писане се върне, Esc (клавишът на лентата) се връща към нормален режим с курсора върху последния знак, който сте написали.

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

```example
Before
|one
two
Keys
Aab<Esc>jgiX<Esc>
After
oneab|X
two
```
