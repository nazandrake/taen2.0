#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Writes the golden table of the Vim layer: every case is run through the real vim (-u NONE -N -es) and the result is recorded.

The unit tests (VimGoldenTest) read app/src/test/resources/vim/golden.jsonl and never need vim; this script is the only place
that runs it. The expected values therefore come from Vim, not from the Kotlin code.

    tools/gen-vim-golden.py            rewrite the golden table
    tools/gen-vim-golden.py --check    regenerate in memory and fail if the table on disk differs

A case is a text, a cursor (an index in code points; the cursor of Normal mode stands on a character), and keys. A case with
"insert" true enters insert mode: "X" is typed and Esc pressed, so that the table records where the insert started.

Keys may name control keys with tokens: <Esc> <CR> <BS> <C-r> <C-a> <C-x> <C-v> <C-w> <C-u> <C-h> <C-o>. The cases of the first group
(motions, operators, put, find, objects, visual) run with ":normal!"; the second group (repeat, macros, registers, marks, search, ex
commands, objects across lines, case, join, shift, numbers, Visual block, insert-mode keys) runs with feedkeys(keys, "tx"), because only typed
keys are recorded into a macro and into the ": register. Rows of the second group also record every non-empty register ("regs") and
the options they ran under ("opts").

Options the cases run under (the choices of this keyboard; everything else is Vim's default with -u NONE): nojoinspaces, nrformats=bin,hex
(a number with leading zeros is decimal), shiftwidth=4 and tabstop=4 with expandtab ("indent": "spaces", the default setting) or
noexpandtab ("indent": "tab"). Allowed deviations (cases filtered out by supported(), with the reason): a column is a count of characters,
so a vertical motion or a block over a tab or a wide character differs from Vim's screen columns; the unnamed paragraph macros of 'para'
(.IP and the like) and quotes in % and bracket matching are not looked at; a mark follows edits made on other lines but keeps its column
on the line that was edited (Vim keeps the column too, but counts bytes).
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "test", "resources", "vim", "golden.jsonl")
ESC = "\x1b"

VIM_SCRIPT = r"""
let c = json_decode(readfile($VIM_CASE)[0])
set nojoinspaces nrformats=bin,hex shiftwidth=4 tabstop=4
if c.indent == 'tab'
  set noexpandtab
else
  set expandtab
endif
call setline(1, c.lines)
call cursor(c.line, c.col)
" not inside :try for typed keys: an error in a try block becomes an exception and the typed-ahead keys of a macro are not dropped as they are in real use
if c.feed
  call feedkeys(c.keys, 'tx')
else
  try
    execute 'normal! ' . c.keys
  catch
  endtry
endif
let regs = {}
for n in split('0123456789-abcdefghijklmnopqrstuvwxyz', '\zs') + ['.', ':', '/']
  let v = getreg(n)
  if v != ''
    let regs[n] = [v, getregtype(n)]
  endif
endfor
call writefile([json_encode({'lines': getline(1, '$'), 'line': line('.'), 'col': col('.'), 'reg': getreg('"'), 'regtype': getregtype('"'), 'regs': regs})], $VIM_OUT)
qa!
"""

TEXTS = {
    "words": "foo bar baz",
    "punct": "foo, bar.baz  qux",
    "lines": "  indented line\nsecond line\n\nfourth: word-word (x)\nlast",
    "trail": "one two\nthree four\n",
    "empty": "",
    "blankline": "\n",
    "single": "x",
    "cyr": "Здравей свят, как си\nвторият ред",
    "emoji": "a\U0001F600b \U0001F600\U0001F600 c",
    "accent": "café au lait",
    "objects": "call(foo, [bar]) = \"quoted text\" + 'single one' ok",
    "dots": "a.b.c.d e-f g_h",
    "tabs": "\tfoo\tbar  baz\nnext",
    "multi": "alpha beta\ngamma delta\nepsilon zeta\neta theta",
    "ends": "end of line   \n   \nafter blank",
}


def lines_of(text):
    return text.split("\n")


def valid_cursors(text):
    """Cursor positions of Normal mode (code point indexes): on a character, or on an empty line."""
    out = []
    start = 0
    for line in lines_of(text):
        chars = _clusters(line)
        if not chars:
            out.append(start)
        else:
            for i, offset in enumerate(chars):
                out.append(start + offset)
        start += len(line) + 1
    return out


def _clusters(line):
    """Code point offsets of the characters of a line (a combining mark belongs to its base character)."""
    import unicodedata
    offsets = []
    for i, ch in enumerate(line):
        if unicodedata.combining(ch) and offsets:
            continue
        offsets.append(i)
    return offsets


def position(text, index):
    """(line, byte column) of a code point index, both 1-based, as Vim wants them."""
    before = text[:index]
    line = before.count("\n") + 1
    in_line = before.rsplit("\n", 1)[-1]
    return line, len(in_line.encode("utf-8")) + 1


def index_of(lines, line, col):
    """Code point index of a Vim position (1-based line, byte column) in the joined lines."""
    start = sum(len(l) + 1 for l in lines[: line - 1])
    text = lines[line - 1]
    return start + len(text.encode("utf-8")[: col - 1].decode("utf-8"))


TOKENS = {"<Esc>": "\x1b", "<CR>": "\r", "<BS>": "\x08", "<C-r>": "\x12", "<C-a>": "\x01", "<C-x>": "\x18", "<C-v>": "\x16",
          "<C-w>": "\x17", "<C-u>": "\x15", "<C-h>": "\x08", "<C-o>": "\x0f"}


def vim_keys(keys):
    for token, ch in TOKENS.items():
        keys = keys.replace(token, ch)
    return keys


def run_vim(text, cursor, keys, workdir, feed=False, indent="spaces"):
    line, col = position(text, cursor)
    case = os.path.join(workdir, "case.json")
    out = os.path.join(workdir, "out.json")
    script = os.path.join(workdir, "run.vim")
    with open(case, "w", encoding="utf-8") as fh:
        json.dump({"lines": lines_of(text), "line": line, "col": col, "keys": vim_keys(keys), "feed": feed, "indent": indent}, fh)
        fh.write("\n")
    if not os.path.exists(script):
        with open(script, "w", encoding="utf-8") as fh:
            fh.write(VIM_SCRIPT)
    if os.path.exists(out):
        os.unlink(out)
    env = dict(os.environ, VIM_CASE=case, VIM_OUT=out, LC_ALL="C.UTF-8", HOME=workdir)
    # vim exits with status 1 after an error in the typed keys (-es); what counts is that the script reached its end and wrote the result
    subprocess.run(["vim", "-u", "NONE", "-i", "NONE", "-N", "-n", "-es", "-S", script], env=env, check=False,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
    if not os.path.exists(out):
        raise FileNotFoundError("vim wrote no result")
    with open(out, encoding="utf-8") as fh:
        got = json.load(fh)
    lines = got["lines"]
    return {
        "text_after": "\n".join(lines),
        "cursor_after": index_of(lines, got["line"], got["col"]),
        "reg": got["reg"],
        "regtype": got["regtype"],
        "regs": got.get("regs", {}),
    }


CATEGORY_SIZE = {"motion": 900, "opmotion": 800, "lineop": 250, "insertcmd": 150, "put": 300, "find": 400, "object": 200, "visual": 600}


def supported(text, cursor, motion):
    """False for the few cases that this layer leaves out on purpose (they are listed in INVARIANTS.md): a column is a count of
    characters, so a vertical motion over a tab differs from Vim's screen columns; iw and aw on an empty line and aw on the blanks
    that end a line go on over the line break in Vim."""
    if motion.lstrip("0123456789") in ("j", "k") and "\t" in text:
        return False
    if motion.lstrip("0123456789") in ("iw", "aw", "iW", "aW"):
        line_start = text.rfind("\n", 0, cursor) + 1
        end = text.find("\n", cursor)
        line_end = len(text) if end < 0 else end
        if line_start == line_end:
            return False
        if motion.endswith(("aw", "aW")) and text[cursor:line_end].strip(" \t") == "" and cursor < line_end:
            return False
    return True


def cases():
    cats = {name: [] for name in CATEGORY_SIZE}

    def add(cat, tid, cursor, keys, insert=False):
        cats[cat].append((tid, cursor, keys, insert))

    motions = ["h", "l", "w", "b", "e", "W", "B", "E", "0", "^", "$", "2w", "3b", "2e", "2W", "3B", "2E", "3l", "4h", "gg", "G", "2G", "5w", "9e"]
    for tid, text in TEXTS.items():
        cursors = valid_cursors(text)
        for cur in cursors:
            for m in motions:
                add("motion", tid, cur, m)
            for m in ["w", "b", "e", "W", "B", "E", "0", "^", "$", "h", "l", "2w", "3e", "2b", "j", "k", "2j", "3k", "G", "gg", "iw", "aw", "iW", "aW"]:
                if not supported(text, cur, m):
                    continue
                add("opmotion", tid, cur, "d" + m)
                add("opmotion", tid, cur, "y" + m)
                add("opmotion", tid, cur, "c" + m, insert=True)
            for k in ["dd", "yy", "cc", "2dd", "3yy", "2cc", "x", "X", "3x", "2X", "D", "2D", "s", "S", "C", "2C", "Y"]:
                add("lineop", tid, cur, k, insert=k in ("cc", "2cc", "s", "S", "C", "2C"))
            for k in ["i", "a", "I", "A", "o", "O"]:
                add("insertcmd", tid, cur, k, insert=True)
            for k in ["rz", "3rz", "ry", "r\u0416"]:
                add("insertcmd", tid, cur, k)
            for k in ["ywP", "ywp", "yyp", "yyP", "2yyp", "ddp", "ddP", "xp", "xP", "dwp", "dwP", "y$p", "yiwP", "3ywp", "2ddP", "yl3p", "dbp"]:
                add("put", tid, cur, k)
    for tid in ["words", "punct", "cyr", "objects", "dots", "emoji"]:
        text = TEXTS[tid]
        chars = sorted({ch for ch in text if ch not in "\n"})[:8] + ["q"]
        for cur in valid_cursors(text):
            for ch in chars[:6]:
                for f in ["f", "F", "t", "T"]:
                    for tail in ["", ";", ",", ";;"]:
                        add("find", tid, cur, f + ch + tail)
                    add("find", tid, cur, "2" + f + ch)
                    add("find", tid, cur, "d" + f + ch)
                    add("find", tid, cur, "y" + f + ch)
                    add("find", tid, cur, "c" + f + ch, insert=True)
                    add("find", tid, cur, "d2" + f + ch)
    for cur in valid_cursors(TEXTS["objects"]):
        for obj in ['i"', 'a"', "i'", "a'", "i(", "a(", "ib", "ab", "i[", "a[", "i)", "a)", "i]"]:
            add("object", "objects", cur, "d" + obj)
            add("object", "objects", cur, "y" + obj)
            add("object", "objects", cur, "c" + obj, insert=True)
    for tid in ["words", "punct", "lines", "trail", "cyr", "multi", "ends", "emoji"]:
        for cur in valid_cursors(TEXTS[tid]):
            for mv in ["", "l", "h", "w", "b", "e", "W", "B", "E", "0", "^", "$", "2l", "2w", "3e", "j", "k", "2j", "G", "gg", "jl", "jj", "kk", "wj", "$j", "$k", "lo", "wwo", "fa", "ta", "Fo", "2h", "jh"]:
                for opk in ["d", "y", "x", "c"]:
                    add("visual", tid, cur, "v" + mv + opk, insert=(opk == "c"))
                    add("visual", tid, cur, "V" + mv + opk, insert=(opk == "c"))
                add("visual", tid, cur, "v" + mv + "vy")
                add("visual", tid, cur, "v" + mv + "Vy")
                add("visual", tid, cur, "V" + mv + "vy")
                add("visual", tid, cur, "v" + mv + "yP")
                add("visual", tid, cur, "V" + mv + "yp")
    out = []
    for name, size in CATEGORY_SIZE.items():
        unique = {}
        for c in cats[name]:
            unique.setdefault((c[0], c[1], c[2]), c)
        # a fixed pseudo-random subset (hash order), so that every text, position and key is likely to be hit
        ordered = sorted(unique.values(), key=lambda c: hashlib.sha1(("%s@%d:%s" % (c[0], c[1], c[2])).encode("utf-8")).hexdigest())
        out.extend(sorted(ordered[:size], key=lambda c: (c[0], c[1], c[2])))
    return out


TEXTS2 = {
    "nums": "x 7 y 08 z -3 0x1f 0b101 a-5\n007 99 id-12 -0 9",
    "block": "abcdef\nghijkl\nmno\npqrstuv\nwx",
    "para": "one two. Three four!  Five?\n\nsecond para\nline two\n\n\nthird",
    "sent": "Hello there.  How are you?  Fine!\nNext line here. And more.\n\nNew paragraph. End.",
    "brk": "fn(a, [b,\n  c], {d})\nx = (1 +\n  (2 * 3))\n{\n  inner\n}\nend",
    "srch": "foo bar foo baz\nbar foo\nfoobar baz foo\nFoo Bar\nend",
    "cyr2": "Иван и Мария\nработят заедно\nИван пак",
    "indent": "  two spaces\n    four\nnone\n\n\ttab line\nlast",
    "csv": "a,b,c\nd,e,f\ng,h,i",
}
ALL_TEXTS = {**TEXTS, **TEXTS2}
FEED = True  # the cases below run with feedkeys


def pick(tid, keys, k):
    """k cursors of the text, chosen by hash so that the table is the same on every run."""
    cur = valid_cursors(ALL_TEXTS[tid])
    ordered = sorted(cur, key=lambda c: hashlib.sha1(("%s@%d:%s" % (tid, c, keys)).encode("utf-8")).hexdigest())
    return sorted(ordered[:k])


def line_count(text):
    return text.count("\n") + 1


def cases2():
    """The second group: (category, text id, cursor, keys, insert, indent). Keys name control keys by tokens."""
    out = []

    def add(cat, texts, keys_list, per=3, insert=False, indent="spaces", only=None):
        for tid in texts:
            for keys in keys_list:
                if only and not only(tid, keys):
                    continue
                for cur in pick(tid, keys, per):
                    out.append((cat, tid, cur, keys, insert, indent))

    # -- repeat with "."
    dot_keys = ["x.", "x2.", "3x.", "3x2.", "dw.", "d2w.", "dw3.", "2dw.", "dd.", "2dd.", "dd3.", "cwX<Esc>.", "cwX<Esc>w.", "ciwX<Esc>w.", "ciw<Esc>.",
                "AX<Esc>.", "IX<Esc>.", "oX<Esc>.", "OX<Esc>.", "iX<Esc>.", "3iX<Esc>.", "rX.", "rXl.", "~.", "3~.", "J.", "gUiw.", "guiw.", ">>.", "<<.",
                "p.", "yyp.", "yyp2.", "xp.", "3.", "D.", "C!<Esc>.", "s_<Esc>.", "S_<Esc>.", "xyw.", "dfa.", "dta.", "xw.", "xw..", "dwj.", "dwj..", "ddj.", "x3.",
                "cwX<Esc>3.", "A;<Esc>j.", "A;<Esc>j.j.", "I-<Esc>j.", "ox<Esc>j.", "yyp.", "3J.", "gJ.", "d$.", "dj.", "dd..", "3rX.", "vld.", "vjd.", "Vd.", "vlcX<Esc>.",
                "vly.", "<C-a>.", "5<C-a>.", "<C-x>.", "g~w.", "gUU.", "x.u"]
    dot_keys = [k for k in dot_keys if not k.endswith("u")]
    add("dot", ["words", "punct", "lines", "multi", "cyr", "ends", "trail"], dot_keys, per=4)
    add("dot", ["nums"], ["<C-a>.", "5<C-a>.", "<C-x>.", "<C-a>w.", "<C-a>w<C-a>"], per=6)
    add("dot", ["multi"], ["dddddd\"1p..", "dddd\"1p.", "dd\"1pj.", "\"adwj.", "\"aywj\"ap.", "\"1p."], per=2)

    # -- macros
    macro_keys = ["qaxq@a", "qaxq3@a", "qaxq@a@@", "qadwq@a", "qadwq2@a", "qadwq@a@@@@", "qaAX<Esc>jq@a", "qaIX<Esc>jq2@a", "qaA;<Esc>jq3@a",
                  "qa0xjq@a@@", "qaxjq4@a", "qaq", "qaxq@b", "qaxqqAdwq@a", "qaq@a", "qaqqaxj@aq@a", "qaq3@a", "qa~lq5@a", "qaddq@a", "qaxj0q9@a",
                  "qaxq@a3@@", "qaxjq@a@@@@@@", "qa/o<CR>xq@a", "qa:s/o/0/<CR>jq@a@@", "qaq:s/o/0/<CR>@:", "qbxqqadwq@b@a", "qaqqa0xq@a", "qaqqaxjq@a",
                  "qadwqu", "qaxq:norm @a<CR>", "qaq0qaxq3@a", "qaA!<Esc>q:%norm @a<CR>", "qaciwX<Esc>wq@a@@", "qayyPq@a"]
    macro_keys = [k for k in macro_keys if not k.endswith("qu")]
    add("macro", ["multi", "lines", "words", "ends", "csv"], macro_keys, per=3)

    # -- registers
    reg_keys = ["\"ayy", "\"ayw", "\"Ayw", "\"ayy\"Ayy", "\"ayw\"Ayy", "\"ayy\"Ayw", "\"add", "\"adw", "\"_dd", "\"_dw", "yy\"_ddp", "dd\"1p", "dddd\"2p",
                "dddd\"1p\"2p", "ywdw\"0p", "yw\"_dwP", "yy\"-p", "\"ayy\"ap", "\"ayw\"ap", "\"ayy\"aP", "\"ayy2\"ap", "\"Ayy\"ap", "x\"-p", "dw\"-p", "d$\"-p",
                "dj\"1p", "d}\"1p", "d/o<CR>\"1p", "\"ay$\"aP", "\"a3yy\"ap", "\"adiw\"-p", "yiw\"0P", "yiwdd\"0p", "dd\"-p", "ddp\"1p", "\"adw\"bdw\"ap\"bp",
                "dwdwdw\"-p\"1p", "dddddd\"3p\"2p\"1p", "dddddddd\"4p", "\"ayw\"Adw\"ap", "\"add\"Add\"ap", "\"ayy\"Ayw\"ap", "dw\"_dw\"-p", "yw\"ayw\"_x\"0p",
                "\"\"yy", "\"\"p", "yyj\"\"p", "d%\"1p", "ddu", "\"_yy"]
    reg_keys = [k for k in reg_keys if not k.endswith("ddu")]
    add("register", ["multi", "lines", "words", "trail", "csv"], reg_keys, per=3)

    # -- marks
    mark_keys = []
    for move in ["w", "$", "G", "gg", "2w", "j", "jj", "b", "0", "e"]:
        for jump in ["`a", "'a", "d`a", "d'a", "y`aP", "y'aP", "c'aX<Esc>", "c`aX<Esc>", "``", "''", "```a", "'`", "y`a`]"]:
            mark_keys.append("ma" + move + jump)
    mark_keys += ["mamb`a`b", "ma$mbgg`b`a", "majmb'a'b", "ma$`ax", "mawmbd`a", "mawmbd`b", "mag`a", "mxjmy'xd'y", "ma''", "`a", "'a", "ma`a", "jmaggdd`a",
                  "jmajdd`a", "jmakdd`a", "jmaggddjj'a", "majddk'a", "majyyP'a", "jmaggOnew<Esc>'a", "jmaggoz<Esc>'ax", "jmaggddp'a", "mawdw`ax",
                  "wmaxx`ax", "majJ`ax", "ma:s/o/0/<CR>`ax", "jmagg:2d<CR>'ax", "jmagg:1t.<CR>'ax",
                  "vl<Esc>gg`>", "vj<Esc>G'<", "wvl<Esc>0gvd", "Vj<Esc>gvd", "vjo<Esc>gvd", "vl<Esc>`<", "vj<Esc>`>x", "vjl<Esc>G`<d`>", "vl<Esc>jgvd", "vlo<Esc>gvhd",
                  "yw`]", "yw`[", "yy`]", "wyiw`[", "dw`[", "p`[", "p`]", "ywP`]", "yw$p`[", "yyp`[", "yjP`]", "yy3p`]", "xp`[x", "ywwd`[", "yiwwP`]x", "dd`[",
                  "cwX<Esc>`[x", "cwXY<Esc>`]x", "AX<Esc>`[x", "iX<Esc>gg`^x", "iXY<Esc>0`^x", "ciwX<Esc>wgiY<Esc>", "ifoo<Esc>wgiZ<Esc>", "AX<Esc>ggaY<Esc>gi!<Esc>"]
    add("mark", ["multi", "lines", "words", "csv"], mark_keys, per=2)

    # -- search
    patterns = ["o", "ba", "foo", "o\\+", "fo*", "^b", "o$", "\\<foo\\>", "b.r", "[bf]o", "\\(foo\\|bar\\)", "\\cFOO", "x\\=foo", "ba[rz]", "\\d", "\\w\\+", "a.*o",
                "\\<.", ".\\>", "o\\{2}", "o\\{1,2}", "[^a-z]", "[[:upper:]]", "\\sb", "fo\\?o", "b\\|f", "^$", "\\Cfoo", "оо", "\\<И", "\\k\\+", "r\\>"]
    search_keys = []
    for pat in patterns:
        for form in ["/{p}<CR>", "?{p}<CR>", "/{p}<CR>n", "/{p}<CR>nN", "?{p}<CR>n", "/{p}<CR>3n", "2/{p}<CR>", "d/{p}<CR>", "y/{p}<CR>P", "c/{p}<CR>X<Esc>",
                     "d?{p}<CR>", "/{p}/e<CR>", "/{p}/e+1<CR>", "/{p}/s-1<CR>", "/{p}/+1<CR>", "/{p}/b<CR>", "?{p}?e<CR>", "d/{p}/e<CR>", "wd/{p}<CR>", "/{p}<CR>//<CR>",
                     "/{p}<CR>/<CR>", "v/{p}<CR>d", "/{p}/;/x<CR>"]:
            search_keys.append(form.replace("{p}", pat))
    search_keys = [k for k in search_keys if not k.endswith(";/x<CR>")]
    add("search", ["srch", "multi", "cyr2"], search_keys, per=1)
    word_keys = ["*", "#", "g*", "g#", "d*", "*n", "#n", "3*", "*N", "y*P", "c*X<Esc>", "v*d", "*x", "#x", "wd*", "w*", "$*", "2#", "*nn", "d#", "yw*P"]
    add("search", ["srch", "multi", "cyr2", "punct", "dots", "brk"], word_keys, per=6)
    add("search", ["srch"], ["/xyz<CR>", "/foo<CR>x", "/\\(<CR>", "/[a<CR>", "n", "N", "/foo<CR>dn", "/foo<CR>d3n", "/foo/e<CR>nx", "/foo/e<CR>Nx", "?foo?e<CR>nx",
                             "/foo/e+3<CR>x", "/foo/s+3<CR>x", "/foo/+9<CR>x", "/foo/-1<CR>x", "/foo/s-9<CR>x", "/foo/e-9<CR>x", "/o\\{<CR>"], per=4)

    # -- ex commands
    ex = []
    for cmd in ["d", "2d", "2,3d", "%d", "$d", ".,+1d", ".,$d", "1,.d", ".-1,.d", "d a", "2,3d a", "d 2", "3d", "1d", "2,2d", ",+1d", "+1d", "-1d", "/o/d",
                "/o/,/a/d", "/a/;+1d", "?o?d", "0d", "5,2d", "2,100d", "100d", "d_", "$-1,$d", ".+1,.+2d", "2;+1d", "%d x", "%d A"]:
        ex.append(":" + cmd + "<CR>")
    for cmd in ["y", "2,3y a", "y A", "%y", "$y", "y 2", "1,2y b", ".,+2y", "2y x"]:
        ex.append(":" + cmd + "<CR>")
        ex.append(":" + cmd + "<CR>P")
        ex.append(":" + cmd + "<CR>\"ap")
    for cmd in ["pu", "2pu", "0pu", "pu!", "$pu a", "pu a", "1pu!", "pu x", "3pu b"]:
        ex.append("\"ayy\"byw" + ":" + cmd + "<CR>")
        ex.append("yw:" + cmd + "<CR>")
        ex.append("2yy:" + cmd + "<CR>")
    for cmd in ["m0", "m$", "1m2", "2,3m0", "2,3m$", "m+1", "m-2", "t.", "t0", "t$", "1,2t$", "2,3co0", "m1", "1m1", "2,3m2", "2,3m3", "t-1", "t+1", "1,2t.", "m.",
                "%t$", "1,2m$", "$m0", "$t0", "2,3m1", "1,3m3", "m5", "m 0", "co.", "copy 0", "move $"]:
        ex.append(":" + cmd + "<CR>")
    for cmd in ["j", "j!", "1,3j", "j 3", "%j", "j2", "2,3j!", "$j", "1,2j", "j 2", ".,+1j", "3j", "1j"]:
        ex.append(":" + cmd + "<CR>")
    for cmd in [">", "<", ">>", "2,3>", "%>", "> 2", "<<", ">>>", "2,3<", "%<", "2<", "$>", ">> 2"]:
        ex.append(":" + cmd + "<CR>")
    subs = ["s/o/0/", "s/o/0/g", "%s/o/0/g", "%s/\\(o\\)\\(o\\)/\\2\\1/g", "s/foo/[&]/", "s/o\\+/X/", "s/^/> /", "s/$/;/", "%s/ \\+$//", "s/\\<./\\u&/g",
            "%s/a/b/gi", "%s/A/b/gI", "s/x*/-/g", "%s/o/\\&/", "s/\\(.\\)\\(.\\)/\\2\\1/", "%s/\\s\\+/ /g", "%s#o#/#g", "%s+o+-+", "s/o/\\r/", "s/o/a\\rb/g",
            "1,2s/o/0/", "2,3s/o/0/g", "s/o//g", "s/\\(foo\\)\\|\\(bar\\)/<\\1\\2>/g", "%s/./&&/g", "s/\\<\\(.\\)\\(.*\\)\\>/\\U\\1\\E\\2/", "s/o/\\\\/", "%s/^\\s*//",
            "s/[aeiou]/*/g", "s/.*/\\U&/", "s/.*/\\L&/", "s/\\w\\+/\\u&/g", "s/o/0/ 2", "%s/o/0/g 2", "s/xyz/q/", "%s/\\<o/0/g", "%s/o\\>/0/g", "s/\\cO/0/", "s/O/0/i",
            "s/o/0/gi", "s/\\n//", "s/\\v(o)/x/", "s/o/\\n/", "s/o/0/c", "%s/\\(a\\|b\\)\\+/-/g", "s/\\(\\)/x/", "s/^\\|$/|/g", "%s/$/!/", "%s/^/- /", "2s/o/0/", "$s/o/0/",
            "s/o/&&/", "s/\\(o\\)/\\1\\1/g", "%s/ab*/X/g", "s/o\\{2}/00/", "s/o\\?/-/g", "s,o,0,", "s/о/0/g", "s/\\<И/X/", "s/./x/3"]
    for cmd in subs:
        ex.append(":" + cmd + "<CR>")
    for cmd in ["s/o/0/<CR>:&&<CR>", "s/o/0/g<CR>j:&&<CR>", "s/o/0/<CR>j:&<CR>", "s/o/0/<CR>j:s<CR>", "s/o/0/g<CR>:%&&<CR>", "/o<CR>:s//X/<CR>", "s/o/0/<CR>n",
                "%s/o/0/g<CR>:%s/0/o/<CR>", "s/a/b/<CR>j:&&<CR>j:&&<CR>", "s/o/0/g<CR>\":p", "s/o/0/g<CR>@:", "s/o/0/g<CR>j@:", "s/o/0/g<CR>j@:@@", ":d<CR>@:", ":s/o/0/<CR>u"]:
        ex.append(":" + cmd)
    ex = [e for e in ex if not e.endswith("<CR>u") and not e.startswith("::")] + [e[1:] for e in ex if e.startswith("::")]
    for cmd in ["g/o/d", "g/^$/d", "v/o/d", "g!/o/d", "g/o/s/o/0/", "g/o/norm Ax", "g/o/m0", "g/^/m0", "g/o/t.", "g/o/j", "2,3g/o/d", "g/o/y A", "g/o/normal dw",
                "g/o/s/o/0/g", "v/o/s/^/#/", "g/a/+1d", "g/o/-1j", "g/./norm I>", "g/o/>", "g/foo/s/foo/bar/", "g#o#d", "g/o/norm x", "g/o/norm ~", "g/o/.,+1d", "g/^\\s*$/d",
                "g/b/t$", "g/b/m$", "g/o/d_", "%g/o/d", ".g/o/d", "g/o", "g//d", "g/o/p", "g/o/norm A!", "g/\\(o\\)\\1/d", "g/zzz/d", "g/o/norm dd", "g/o/norm ddp", "g/^/norm Ax"]:
        ex.append(":" + cmd + "<CR>")
    for cmd in ["norm Ax", "%norm Ax", "2,3norm Ix", "norm dw", "%norm dw", "norm! x", "%norm 0x", "1,2norm ~", "norm A", "normal yyp", "%norm yyp", "norm ciwQ", "%norm $x", "norm 2x",
                "%norm 2wD", "norm gUU", "%norm >>", "norm J", "%norm J", "norm 3J", "norm .", "%norm A.", "norm xp", "norm ddp", "%norm ddp", "$norm Ax", "1norm ox", "%norm Ox"]:
        ex.append(":" + cmd + "<CR>")
    for cmd in ["2", "$", "+1", "-1", "100", "0", "/o/", "?o?", "1", ".", ".+1", "$-1", "'a", "/a/+1", "2;/o/", "+", "++", "-", "--", "/o/;+1"]:
        ex.append(":" + cmd + "<CR>x")
    ex += ["majmb:'a,'bd<CR>", "majmb:'a,'bs/o/0/<CR>", "jmagg:'ad<CR>", "mamb:'a,'bj<CR>", "jmajmb:'a;'bd<CR>", "majmbgg:'a,'bm$<CR>", "ma:k b<CR>'bx", "j:mark a<CR>gg'ax",
           "Vj:s/o/0/<CR>", "vj:d<CR>", "Vj:norm Ax<CR>", "VG:><CR>", "Vj:y a<CR>\"ap", "vl:s/o/0/<CR>", "Vj:j<CR>", "Vj:m$<CR>", "Vj:t.<CR>", "Vj:g/o/d<CR>", "Vj<Esc>:'<,'>d<CR>",
           "vjj<Esc>gg:'<,'>y<CR>P", "Vj:'<d<CR>", "Vj:'>d<CR>", "Vj:'<,'>s/o/0/<CR>:&&<CR>", "VG:s/^/#/<CR>", ":w<CR>x", ":q<CR>x", ":x<CR>x", ":wq<CR>x", ":noh<CR>x",
           ":reg<CR>x", ":marks<CR>x", ":foo<CR>x", ":d<CR>:d<CR>", ":d<CR>:d<CR>:d<CR>", ":d<CR>P", ":s/o/0/<CR>:d<CR>", ":%d<CR>:pu<CR>", ":1,2d<CR>:$pu<CR>", ":j<CR>.", ":d<CR>.", ":2<CR>.",
           ":s/o/0/g<CR>.", ":d<CR>j.", ":><CR>.", ":t.<CR>.", ":s/o/0/<CR>:s/o/0/<CR>", ":%d<CR>x", ":1,2d<BS>3<CR>x", ":2,3d<BS><BS>d<CR>"]
    for keys in ex:
        for tid in ["multi", "lines", "srch", "cyr2"]:
            for cur in pick(tid, keys, 1):
                out.append(("ex", tid, cur, keys, False, "spaces"))
    for keys in [":%s/^/\\t/<CR>", ":>><CR>", ":%><CR>", ":2,3><CR>", ":><CR>:<<CR>", ":%> 2<CR>", ":%s/^\\s*/&&/<CR>"]:
        for ind in ["spaces", "tab"]:
            for tid in ["indent", "multi"]:
                for cur in pick(tid, keys, 2):
                    out.append(("ex", tid, cur, keys, False, ind))

    # -- objects across lines, counts, %, paragraphs, sentences
    objs = []
    for o in ["iw", "aw", "iW", "aW", "2iw", "3iw", "2aw", "3aw", "2iW", "2aW", "4iw", "5aw"]:
        for op in ["d", "y", "c", "g~", "gu"]:
            objs.append(op + o + ("X<Esc>" if op == "c" else ""))
    for o in ["i(", "a(", "ib", "ab", "2i(", "2a(", "i{", "a{", "iB", "2iB", "i[", "a[", "2i[", "i<", "a<", "i)", "a}", "i]", "i>", "3i(", "2ab", "2aB"]:
        for op in ["d", "y", "c", ">"]:
            objs.append(op + o + ("X<Esc>" if op == "c" else ""))
    for o in ["i\"", "a\"", "2i\"", "2a\"", "i'", "a'", "2i'", "i`", "a`"]:
        for op in ["d", "y", "c"]:
            objs.append(op + o + ("X<Esc>" if op == "c" else ""))
    for m in ["%", "d%", "y%P", "v%d", "c%X<Esc>", "y%$p", "2%", "50%", "g%"]:
        objs.append(m)
    for m in ["}", "{", "2}", "2{", "d}", "d{", "y}P", "y{p", "c}X<Esc>", "v}d", "}x", "{x", "d2}", "3}", "y2{P", ">}", "g~}", "}}", "{{", "}}}"]:
        objs.append(m)
    for m in [")", "(", "2)", "2(", "d)", "d(", "y)P", "c)X<Esc>", "v)d", ")x", "(x", "d2)", "3)", "))", "((", ")))", "y(p", "d))"]:
        objs.append(m)
    for m in ["dip", "dap", "yip", "yap", "cipX<Esc>", "capX<Esc>", "d2ap", "d2ip", "d3ip", ">ip", ">ap", "g~ip", "dipp", "yipP"]:
        objs.append(m)
    for m in ["viwd", "vawd", "vi(d", "va(d", "vi\"d", "va\"d", "v2iwd", "viwiwd", "vi{d", "vibd", "viwy", "vawy", "vaw", "viw", "viW", "vaWd", "vi[d", "va[d", "vi<d", "va<d"]:
        objs.append(m)
    add("object", ["brk", "words", "punct", "multi", "lines", "ends", "dots", "objects", "cyr", "para", "sent"], objs, per=1)

    # -- case, join, shift, increment
    cj = ["J", "2J", "3J", "5J", "gJ", "3gJ", "~", "3~", "10~", "g~w", "g~iw", "g~~", "g~g~", "gUw", "gUiw", "gUU", "gUgU", "guw", "guu", "gugu", "gu$", "gUj", "g~2w", "2gUU",
          "gUG", "guk", "gUgg", "g~$", "g~0", "g~l", "2~", "~~", "~l~", "J.", "JJ", "2JJ", "gJgJ", "Jx", "$J", "jJ", "G~", "G~~", "gUap", "gUip", "g~f,", "gUt,", "gu2e", "g~^"]
    add("case", ["multi", "lines", "words", "punct", "cyr", "ends", "emoji", "accent", "csv", "cyr2", "trail"], cj, per=3)
    nums = ["<C-a>", "<C-x>", "5<C-a>", "5<C-x>", "100<C-a>", "100<C-x>", "<C-a><C-a>", "w<C-a>", "$<C-a>", "0<C-a>", "wwww<C-x>", "3<C-a>.", "<C-a>w<C-a>", "999<C-a>", "<C-x>j<C-x>",
            "j<C-a>", "Vj<C-a>", "Vj<C-x>", "vj<C-a>", "Vj3<C-a>", "VGg<C-a>", "VGg<C-x>", "Vjg<C-a>", "v$<C-a>", "vee<C-a>", "<C-v>j<C-a>", "<C-v>jj<C-x>", "V<C-a>"]
    add("number", ["nums"], nums, per=6)
    add("number", ["multi", "words"], ["<C-a>", "<C-x>", "5<C-a>"], per=3)
    sh = [">>", "3>>", "<<", ">j", ">G", ">gg", ">w", "2>>", ">2j", "<k", "<j", "3<<", ">>>>", "<<<<", ">>.", ">}", ">ap", "V>", "Vj>", "V2>", "Vj<", "V3<", "v>", "vj>", "VG<", "Vj>.", "vjj>gv",
          "gg>G", ">>j.", ">>j.j.", "xx>>", ">>x", ">>u"]
    sh = [k for k in sh if not k.endswith("u")]
    for ind in ["spaces", "tab"]:
        add("shift", ["indent", "multi", "lines", "words", "ends"], sh, per=3, indent=ind)

    vis = ["vj~", "vwU", "vwu", "vjJ", "VjJ", "VjgJ", "vlrX", "VrX", "vwp", "vwP", "Vp", "Vp", "vjp", "VjP", "vwpP", "vl~~", "V~", "Vjgu", "VjgU", "vlgU", "vlg~", "vjoJ",
           "vlloo", "vwwo<Esc>", "vjo<Esc>x", "v$o<Esc>x", "v$y", "Vjjy", "VjjjJ", "vgJ", "V3J", "vjjJ", "vlld.", "vlly.", "vwcX<Esc>.", "VcX<Esc>", "VjcX<Esc>", "vjcX<Esc>", "vjx", "vjX", "vjD",
           "vjC", "vjS", "vjY", "VY", "vD", "vC", "vS", "VjD", "VjC", "VjS", "ywvwp", "yyvwp", "ywVp", "yyVp", "yyVjp", "ywvlp", "yy2Vp", "ywvl3p", "ywvlP", "yyvlP", "ywwvlpgvd",
           "v\"ay", "V\"ay", "v\"ayj\"ap", "vl\"_d", "vl\"adw\"ap", "vjgvd", "vj<Esc>gvd", "Vj<Esc>jgvy", "vj<Esc>Gvd", "vl<Esc>gvgvd"]
    vis = [k for k in vis if not k.endswith("p") or True]
    add("visual2", ["multi", "lines", "words", "punct", "cyr", "ends", "trail", "csv"], vis, per=3)

    blk = ["<C-v>jld", "<C-v>jlly", "<C-v>jlyP", "<C-v>jlyp", "<C-v>jj$d", "<C-v>jIX<Esc>", "<C-v>jAX<Esc>", "<C-v>j$AX<Esc>", "<C-v>jcX<Esc>", "<C-v>jlrX", "<C-v>jl~", "<C-v>jlU", "<C-v>jlu",
           "<C-v>jlod", "<C-v>jjlld", "<C-v>jjIab<Esc>", "<C-v>jly3p", "<C-v>jhd", "<C-v>kd", "<C-v>jjjd", "<C-v>jjjjd", "<C-v>llly", "<C-v>jlyjp", "<C-v>jlyGp",
           "<C-v>jlyP", "<C-v>j$y", "<C-v>j$yP", "<C-v>jAXY<Esc>", "<C-v>jjAX<Esc>", "<C-v>jjIX<Esc>", "<C-v>jjj$AX<Esc>", "<C-v>jjcX<Esc>", "<C-v>jjlcXY<Esc>", "<C-v>jlcX<Esc>.", "<C-v>lld", "<C-v>j3ld",
           "<C-v>jlgvd", "<C-v>jl<Esc>gvd", "<C-v>jlv", "<C-v>jlV", "<C-v>jlvd", "<C-v>jlVd", "v<C-v>d", "V<C-v>jd", "<C-v><C-v>", "<C-v>jlp", "<C-v>jlJ", "<C-v>jl>", "<C-v>jl<", "<C-v>jlD",
           "<C-v>jlC<Esc>", "<C-v>jlX", "<C-v>jlx", "<C-v>jlsX<Esc>", "<C-v>jlyu", "<C-v>j2lyP", "<C-v>jlyP.", "wl<C-v>jld", "ll<C-v>jjd", "$<C-v>jd", "$<C-v>jjAX<Esc>", "0<C-v>jjIX<Esc>", "<C-v>jlAX<Esc>."]
    blk = [k for k in blk if not k.endswith("yu")]
    add("block", ["block", "multi", "cyr2", "csv"], blk, per=3)

    ins = ["RXY<Esc>", "3RXY<Esc>", "RXYZW<Esc>", "RXY<BS><Esc>", "RXY<BS><BS><Esc>", "R<Esc>", "3iX<Esc>", "2AX<Esc>", "3oX<Esc>", "2OX<Esc>", "3IX<Esc>", "2aXY<Esc>", "5iab<Esc>", "R<CR><Esc>",
           "ifoo<Esc>wgiX<Esc>", "gigiX<Esc>", "A foo bar<C-w>X<Esc>", "A foo bar<C-w><C-w>X<Esc>", "Afoo bar<C-u>X<Esc>", "A<C-u>X<Esc>", "Afoo<C-h><C-h>X<Esc>", "I<C-h>X<Esc>", "A<C-w>X<Esc>",
           "Afoo<C-h>X<Esc>", "ifoo<C-o>0X<Esc>", "ifoo<C-o>dwX<Esc>", "A<C-o>hX<Esc>", "ifoo<C-o>$X<Esc>", "Afoo<C-o>0X<Esc>", "ifoo<C-o>xX<Esc>", "A<C-o>ddX<Esc>", "ifoo<C-o>pX<Esc>",
           "ywA<C-r>0<Esc>", "\"ayyA<C-r>a<Esc>", "yiwA <C-r>\"<Esc>", "yiwI<C-r>\"<Esc>", "A<C-r>a<Esc>", "yyA<C-r>\"<Esc>", "ywi<C-r>0<C-r>0<Esc>", "ifoo<Esc>\".p", "ifoo<Esc>\".P", "ifoo bar<Esc>u\".p",
           "A<C-r>.<Esc>", "ifoo<Esc>A<C-r>.<Esc>", "A<C-r>-<Esc>", "dwA<C-r>-<Esc>", "A<C-r>/<Esc>", "/o<CR>A<C-r>/<Esc>", ":s/o/0/<CR>A<C-r>:<Esc>", ":s/o/0/<CR>\":p", "/o<CR>\"/p", "ifoo<C-w><C-w>X<Esc>",
           "A foo<C-w>X<C-w>Y<Esc>", "Afoo bar<C-w>baz<C-u>X<Esc>", "A  <C-w>X<Esc>", "Afoo   <C-w>X<Esc>", "Afoo,<C-w>X<Esc>", "Afoo, bar<C-w><C-w>X<Esc>", "ox<C-u>y<Esc>", "ox<C-w>y<Esc>", "ox<C-h>y<Esc>", "ox<C-h><C-h>y<Esc>", "ox<C-h><C-h><C-h>y<Esc>",
           "A<C-h><C-h>X<Esc>", "I<C-w>X<Esc>", "I<C-u>X<Esc>", "jI<C-h>X<Esc>", "jI<C-w>X<Esc>", "jI<C-u>X<Esc>", "ifoo<CR>bar<C-u>X<Esc>", "ifoo<CR>bar<C-h><C-h><C-h><C-h>X<Esc>"]
    ins = [k for k in ins if not k.endswith("<Esc>u\".p") and "bar<Esc>u" not in k]
    add("insert2", ["words", "multi", "lines", "punct", "cyr", "ends", "trail"], ins, per=2)
    return out


def supported2(text, cursor, keys, tid):
    """False for the cases where this layer differs from Vim on purpose (the reasons are in the module description)."""
    # a pattern with a line break ("\n") or in very magic mode ("\v"), the confirm flag, and "\n" in a replacement (Vim puts a NUL there, a field a line break)
    if keys.startswith(":") and (":s/" in keys or ":%s/" in keys or ":g" in keys) and any(x in keys for x in ["\\n", "\\v", "/c<CR>", "\\1\\)", ")\\1"]):
        return False
    if keys.endswith("<CR>u") or "<CR>u" in keys:
        return False
    # a tilde operator over an empty range at the start of a line: what Vim changes there depends on the line (it is a quirk of Vim)
    if keys in ("g~0", "g~^", "gU0", "gu0") and (cursor == 0 or text[cursor - 1] == "\n"):
        return False
    if "\t" in text and any(k in keys for k in ["<C-v>", "j", "k", "~"]) and tid != "indent":
        return False
    if tid == "emoji" and any(k in keys for k in ["<C-v>"]):
        return False
    # an empty yank at the start of a line: Vim puts the ] mark at the end of the line before
    if keys.startswith("ma0y`a`]") and text[cursor:cursor + 1] in ("\n", "") or (keys.startswith("ma0y`a`]") and cursor > 0 and text[cursor - 1] == "\n"):
        return False
    return True


_WORK = None


def _job(job):
    """Runs one case in a worker process (each has its own scratch directory) and returns its row."""
    global _WORK
    if _WORK is None:
        base = os.path.expanduser("~/.cache") if os.path.isdir(os.path.expanduser("~/.cache")) else None
        _WORK = tempfile.mkdtemp(prefix="vimgold", dir=base)
    tid, cursor, keys, insert, feed, indent, cat = job
    text = ALL_TEXTS[tid]
    typed = keys + ("X" + ESC if insert else "")
    try:
        result = run_vim(text, cursor, typed, _WORK, feed=feed, indent=indent)
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired) as exc:
        sys.exit("vim failed on %s@%d:%r (%s)" % (tid, cursor, keys, exc))
    if not feed:
        result.pop("regs", None)  # the first group compares the unnamed register only
        return {"name": "%s@%d:%s" % (tid, cursor, keys), "text": text, "cursor": cursor, "keys": keys, "insert": insert, **result}
    name = "%s@%d:%s" % (tid, cursor, keys) + ("" if indent == "spaces" else "[" + indent + "]")
    # the "." register of Vim keeps the typed keys, control keys and deleted characters included; this layer keeps what is left of the typed text
    if any(k in keys for k in ("<BS>", "<C-w>", "<C-u>", "<C-h>", "<C-r>", "<C-o>")):
        result["regs"].pop(".", None)
    return {"name": name, "text": text, "cursor": cursor, "keys": keys, "insert": insert, "opts": {"indent": indent, "cat": cat}, **result}


def build(only=None):
    import concurrent.futures
    seen = set()
    jobs = []
    if only is None:
        for tid, cursor, keys, insert in cases():
            ident = (tid, cursor, keys)
            if ident in seen:
                continue
            seen.add(ident)
            jobs.append((tid, cursor, keys, insert, False, "spaces", ""))
    for cat, tid, cursor, keys, insert, indent in cases2():
        if only is not None and cat not in only:
            continue
        if not supported2(ALL_TEXTS[tid], cursor, keys, tid):
            continue
        ident = (tid, cursor, keys, indent)
        if ident in seen:
            continue
        seen.add(ident)
        jobs.append((tid, cursor, keys, insert, True, indent, cat))
    workers = max(1, min(8, (os.cpu_count() or 2) - 1))
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(_job, jobs, chunksize=64))


def render(rows):
    return "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n" for r in rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--only", help="comma-separated categories of the second group: write only those rows to --out (for development)")
    ap.add_argument("--out", help="write here instead of the golden table (development)")
    args = ap.parse_args()
    if subprocess.run(["vim", "--version"], stdout=subprocess.DEVNULL).returncode != 0:
        sys.exit("vim is not installed")
    if args.only or args.out:
        rows = build(set(args.only.split(",")) if args.only else None)
        out = args.out or "/dev/stdout"
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(render(rows))
        print("wrote %d cases to %s" % (len(rows), out))
        return
    text = render(build())
    if args.check:
        with open(OUT, encoding="utf-8") as fh:
            if fh.read() != text:
                sys.exit("golden table is out of date: run tools/gen-vim-golden.py")
        print("golden table is up to date (%d cases)" % text.count("\n"))
        return
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("wrote %d cases to %s" % (text.count("\n"), os.path.relpath(OUT)))


if __name__ == "__main__":
    main()
