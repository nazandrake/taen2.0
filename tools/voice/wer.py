#!/usr/bin/env python3
"""Word error rate for the Taen voice samples.

usage: wer.py <refs.tsv> <hyps.tsv>      both files: NN<TAB>text; prints one line per utterance and the corpus WER

Normalisation (same as the Kotlin test, WerTest): lower case, every character that is not a letter or digit becomes a space, white
space collapsed. Corpus WER = total word edits / total reference words.
"""
import sys
import unicodedata


def normalize(text):
    text = unicodedata.normalize("NFC", text).lower()
    return "".join(c if c.isalnum() else " " for c in text).split()


def edits(ref, hyp):
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i]
        for j, h in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h)))
        prev = cur
    return prev[-1]


def load(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        key, _, text = line.rstrip("\n").partition("\t")
        out[key] = text
    return out


if __name__ == "__main__":
    refs, hyps = load(sys.argv[1]), load(sys.argv[2])
    total_edits = total_words = 0
    for key in sorted(refs):
        r, h = normalize(refs[key]), normalize(hyps.get(key, ""))
        e = edits(r, h)
        total_edits += e
        total_words += len(r)
        print(f"{key} {e}/{len(r)}")
    print(f"WER {100.0 * total_edits / total_words:.2f} % ({total_edits}/{total_words})")
