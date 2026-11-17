#!/usr/bin/env python3
"""Reference token ids for the Kotlin tokenizer test, made with the models' own tokenizers (Hugging Face tokenizers 0.22.1).

usage: make_token_fixtures.py <strings.txt> <tokenizer.json> <vocab.json> <merges.txt> <out.tsv>

Each line of strings.txt is one string; the escapes \\n, \\r, \\t, \\\\ and \\uXXXX are decoded. Every output line is the escaped string, a tab, and the
token ids separated by spaces (no special tokens added). Before writing, the script checks that vocab.json and merges.txt (the two files the app ships)
hold exactly the vocabulary and merges of tokenizer.json, so that the fixture also proves the shipped files are the tokenizer's own.
"""
import json
import sys

from tokenizers import Tokenizer


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u":
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "r": "\r", "t": "\t", "\\": "\\"}[n])
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main():
    strings, tok_json, vocab_json, merges_txt, out = sys.argv[1:6]
    spec = json.load(open(tok_json, encoding="utf-8"))
    vocab = json.load(open(vocab_json, encoding="utf-8"))
    if vocab != spec["model"]["vocab"]:
        sys.exit("vocab.json differs from tokenizer.json")
    merges = [line.rstrip("\n") for line in open(merges_txt, encoding="utf-8") if not line.startswith("#version")]
    merges = [m for m in merges if m]
    expected = [" ".join(m) if isinstance(m, list) else m for m in spec["model"]["merges"]]
    if merges != expected:
        sys.exit("merges.txt differs from tokenizer.json")
    tok = Tokenizer.from_file(tok_json)
    with open(out, "w", encoding="utf-8") as f:
        for line in open(strings, encoding="utf-8"):
            raw = line.rstrip("\n")
            if not raw:
                continue
            ids = tok.encode(unescape(raw), add_special_tokens=False).ids
            f.write(raw + "\t" + " ".join(map(str, ids)) + "\n")


if __name__ == "__main__":
    main()
