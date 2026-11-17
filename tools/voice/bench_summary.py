#!/usr/bin/env python3
"""Summarises a device benchmark log (BenchMain BENCH lines): WER per language against refs.tsv, real-time factor, load time, peak memory.
usage: bench_summary.py <bench log> <samples dir with en/refs.tsv and bg/refs.tsv>"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wer  # noqa: E402

log, samples = sys.argv[1:3]
rows = {"en": {}, "bg": {}}
stats = {"en": [0.0, 0.0], "bg": [0.0, 0.0]}
for line in open(log, encoding="utf-8"):
    m = re.match(r"BENCH (en|bg) (\d+) ms=(\d+) audio_ms=(\d+) \| (.*)", line.rstrip("\n"))
    if m:
        lang, i, ms, audio, text = m.groups()
        rows[lang][i] = text
        stats[lang][0] += int(ms) / 1000
        stats[lang][1] += int(audio) / 1000
    elif line.startswith("BENCH LOAD") or line.startswith("BENCH DONE"):
        print(line.strip())
for lang in ("en", "bg"):
    refs = wer.load(os.path.join(samples, lang, "refs.tsv"))
    e = w = 0
    for k, ref in refs.items():
        r, h = wer.normalize(ref), wer.normalize(rows[lang].get(k, ""))
        e += wer.edits(r, h)
        w += len(r)
    print("%s: WER %.2f %% (%d/%d words), decode %.1f s for %.1f s audio, RTF %.2f" % (lang, 100.0 * e / w, e, w, stats[lang][0], stats[lang][1], stats[lang][0] / stats[lang][1]))
