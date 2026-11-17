#!/usr/bin/env python3
"""The next-word measurement set: the 1000 held-out contexts of tools/dict_eval (same text, same seed, same contexts), written for the Kotlin ranking.

usage: contexts.py <dump.tsv> <sentences.txt> <qwerty|bg> <out dir>

Writes <out dir>/contexts.tsv (one line per context: the expected next word in lower case, the text before it as typed (the sentence up to the next word,
trailing spaces removed, tabs as spaces), and the dictionary's predictions for the previous word: its stored pair targets, most probable first, at most 18
(the number of predictions the keyboard asks the dictionary for), '|' separated) and <out dir>/words.txt (every word of the dictionary, one per line: the
words the ranking may show). dump.tsv is the text dump of app/src/main/jni/host_test/dump_dict.cpp (tools/dict-eval.sh builds it).
"""
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dict_eval"))
import dict_eval  # noqa: E402

PREDICTIONS = 18


def main():
    dump_path, text_path, layout, out_dir = sys.argv[1:5]
    words = []
    pairs = defaultdict(dict)
    with open(dump_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "W":
                words.append(parts[1])
            elif parts[0] == "P":
                prev, nxt, p = parts[1].lower(), parts[2], int(parts[3])
                key = nxt.lower()
                best = pairs[prev].get(key)
                if best is None or p > best[1]:
                    pairs[prev][key] = (nxt, p)
    rows = dict_eval.read_sentences(text_path)
    _, picked = dict_eval.select([toks for _, toks, _ in rows], layout)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "contexts.tsv"), "w", encoding="utf-8") as out:
        for si, i in picked:
            sentence, toks, spans = rows[si]
            prefix = sentence[: spans[i + 1][0]].rstrip().replace("\t", " ")
            ranked = sorted(pairs.get(toks[i], {}).values(), key=lambda wp: -wp[1])[:PREDICTIONS]
            out.write(toks[i + 1] + "\t" + prefix + "\t" + "|".join(w for w, _ in ranked) + "\n")
    with open(os.path.join(out_dir, "words.txt"), "w", encoding="utf-8") as out:
        for w in words:
            out.write(w + "\n")


if __name__ == "__main__":
    main()
