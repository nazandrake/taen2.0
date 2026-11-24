#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Writes the golden table of the Vim layer: every case is run through the real vim (-u NONE -N -es) and the result is recorded.

The unit tests (VimGoldenTest) read app/src/test/resources/vim/golden.jsonl and never need vim; this script is the only place
that runs it. The expected values therefore come from Vim, not from the Kotlin code.

    tools/gen-vim-golden.py            rewrite the golden table
    tools/gen-vim-golden.py --check    regenerate in memory and fail if the table on disk differs

A case is a text, a cursor (an index in code points; the cursor of Normal mode stands on a character), and keys. A case with
"insert" true enters insert mode: "X" is typed and Esc pressed, so that the table records where the insert started.
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
call setline(1, c.lines)
call cursor(c.line, c.col)
try
  execute 'normal! ' . c.keys
catch
endtry
call writefile([json_encode({'lines': getline(1, '$'), 'line': line('.'), 'col': col('.'), 'reg': getreg('"'), 'regtype': getregtype('"')})], $VIM_OUT)
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


def run_vim(text, cursor, keys, workdir):
    line, col = position(text, cursor)
    case = os.path.join(workdir, "case.json")
    out = os.path.join(workdir, "out.json")
    script = os.path.join(workdir, "run.vim")
    with open(case, "w", encoding="utf-8") as fh:
        json.dump({"lines": lines_of(text), "line": line, "col": col, "keys": keys}, fh)
        fh.write("\n")
    if not os.path.exists(script):
        with open(script, "w", encoding="utf-8") as fh:
            fh.write(VIM_SCRIPT)
    if os.path.exists(out):
        os.unlink(out)
    env = dict(os.environ, VIM_CASE=case, VIM_OUT=out, LC_ALL="C.UTF-8", HOME=workdir)
    subprocess.run(["vim", "-u", "NONE", "-i", "NONE", "-N", "-n", "-es", "-S", script], env=env, check=True,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
    with open(out, encoding="utf-8") as fh:
        got = json.load(fh)
    lines = got["lines"]
    return {
        "text_after": "\n".join(lines),
        "cursor_after": index_of(lines, got["line"], got["col"]),
        "reg": got["reg"],
        "regtype": got["regtype"],
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


def build():
    seen = set()
    rows = []
    with tempfile.TemporaryDirectory(prefix="vimgold", dir=os.path.expanduser("~/.cache") if os.path.isdir(os.path.expanduser("~/.cache")) else None) as work:
        for tid, cursor, keys, insert in cases():
            ident = (tid, cursor, keys)
            if ident in seen:
                continue
            seen.add(ident)
            text = TEXTS[tid]
            typed = keys + ("X" + ESC if insert else "")
            try:
                result = run_vim(text, cursor, typed, work)
            except (subprocess.CalledProcessError, FileNotFoundError) as exc:
                sys.exit("vim failed on %s@%d:%r (%s)" % (tid, cursor, keys, exc))
            rows.append({"name": "%s@%d:%s" % (tid, cursor, keys), "text": text, "cursor": cursor, "keys": keys, "insert": insert, **result})
    return rows


def render(rows):
    return "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n" for r in rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if subprocess.run(["vim", "--version"], stdout=subprocess.DEVNULL).returncode != 0:
        sys.exit("vim is not installed")
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
