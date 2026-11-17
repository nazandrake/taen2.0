#!/usr/bin/env python3
"""Dictionary measures for one .dict file, from the text dump of app/src/main/jni/host_test/dump_dict.cpp.

usage: dict_eval.py <file.dict> <dump.tsv> <sentences.txt> <qwerty|bg>

Measures (all deterministic, fixed seed):
  words        number of words with a probability
  pairs        number of stored word pairs (previous word -> next word)
  oov          share of word tokens of the held-out text that the file does not contain (lower-cased)
  fix_top1     of 200 synthetic typos (one letter of a word of the text replaced by an adjacent key of the layout; the same cases for every file), the share where
               the file knows the word, the typo is not itself a word of the file, and the intended word is the most probable word one adjacent-key substitution away.
               This is a measure of the word list, not of the keyboard's correction algorithm (the native engine is not run).
  next_top3    of 1000 contexts (previous word, next word of the held-out text; the same contexts for every file), the share where the
               next word is among the 3 most probable pair targets that the file stores for the previous word. Main dictionaries carry word
               pairs only, so a file without pairs scores 0 (no filling up with frequent words).
"""
import random
import re
import sys
from collections import defaultdict

ROWS = {
    "qwerty": ["qwertyuiop", "asdfghjkl", "zxcvbnm"],
    "bg": ["яертъуиопч", "асдфгхйклшщ", "зьцжбнм"],
}
TOKEN = re.compile(r"[^\W\d_]+", re.UNICODE)


def neighbours(layout):
    rows = ROWS[layout]
    near = defaultdict(set)
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if c > 0:
                near[ch].add(row[c - 1])
            if c + 1 < len(row):
                near[ch].add(row[c + 1])
            for dr in (-1, 1):
                rr = r + dr
                if 0 <= rr < len(rows):
                    for dc in (-1, 0, 1):
                        cc = c + dc
                        if 0 <= cc < len(rows[rr]):
                            near[ch].add(rows[rr][cc])
    for ch in list(near):
        near[ch].discard(ch)
    return near


def read_sentences(text_path):
    """The sentences of a Leipzig file as (raw text, lower-cased word tokens, token spans); sentences without a word are left out."""
    out = []
    with open(text_path, encoding="utf-8") as f:
        for line in f:
            _, _, sentence = line.rstrip("\n").partition("\t")
            spans = [(m.start(), m.end()) for m in TOKEN.finditer(sentence)]
            if spans:
                out.append((sentence, [sentence[a:b].lower() for a, b in spans], spans))
    return out


def select(sentences, layout):
    """The 200 typo cases and the 1000 next-word contexts (sentence index, token index) of the held-out text, with the fixed seed: every file and every
    model is measured on the same cases (tools/nextword/contexts.py uses the same contexts)."""
    rng = random.Random(610)
    near = neighbours(layout)
    tokens = [t for s in sentences for t in s]
    # the typo cases come from the text only, so that every file is measured on the same 200 cases; a word that the file does not know, or a typo that is
    # itself a word of the file, is a case the file does not fix
    cands = [t for t in tokens if len(t) >= 4 and all(ch in near for ch in t)]
    rng.shuffle(cands)
    cases = []
    for word in cands:
        pos = rng.randrange(len(word))
        typo = word[:pos] + rng.choice(sorted(near[word[pos]])) + word[pos + 1:]
        cases.append((word, typo))
        if len(cases) == 200:
            break
    contexts = [(si, i) for si, s in enumerate(sentences) for i in range(len(s) - 1)]  # from the text only: the same 1000 contexts for every file
    rng.shuffle(contexts)
    return cases, contexts[:1000]


def main():
    dict_path, dump_path, text_path, layout = sys.argv[1:5]
    prob = {}
    pairs = defaultdict(dict)
    pair_count = 0
    with open(dump_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "W":
                prob[parts[1]] = int(parts[2])
            elif parts[0] == "P":
                pairs[parts[1]][parts[2]] = int(parts[3])
                pair_count += 1
    lower = {}
    for w, p in prob.items():
        lw = w.lower()
        if lw not in lower or p > lower[lw]:
            lower[lw] = p
    lower_pairs = defaultdict(dict)
    for a, targets in pairs.items():
        for b, p in targets.items():
            la, lb = a.lower(), b.lower()
            if p > lower_pairs[la].get(lb, -1):
                lower_pairs[la][lb] = p

    sentences = [toks for _, toks, _ in read_sentences(text_path)]
    tokens = [t for s in sentences for t in s]
    oov = sum(1 for t in tokens if t not in lower) / len(tokens)

    by_len = defaultdict(list)
    for w in lower:
        by_len[len(w)].append(w)
    near = neighbours(layout)
    cases, picked = select(sentences, layout)
    fixed = 0
    for word, typo in cases:
        if typo in lower or word not in lower:
            continue
        best, best_p = None, -1
        for cand in by_len[len(typo)]:
            diff = [i for i in range(len(typo)) if cand[i] != typo[i]]
            if len(diff) == 1 and cand[diff[0]] in near[typo[diff[0]]] and lower[cand] > best_p:
                best, best_p = cand, lower[cand]
        fixed += best == word

    contexts = [(sentences[si][i], sentences[si][i + 1]) for si, i in picked]
    hits = 0
    for prev, nxt in contexts:
        ranked = [w for w, _ in sorted(lower_pairs.get(prev, {}).items(), key=lambda kv: -kv[1])[:3]]
        hits += nxt in ranked

    print(f"file {dict_path}")
    print(f"  words {len(prob)}  pairs {pair_count}")
    print(f"  oov {oov * 100:.2f} % of {len(tokens)} tokens")
    print(f"  fix_top1 {fixed}/{len(cases)} = {fixed / len(cases) * 100:.1f} %")
    print(f"  next_top3 {hits}/{len(contexts)} = {hits / len(contexts) * 100:.1f} %")


if __name__ == "__main__":
    main()
