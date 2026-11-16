#!/usr/bin/env python3
"""Cut the fixed Taen voice test set from the public FLEURS test split (google/fleurs, CC-BY-4.0; real speakers, no private data).

usage: prepare_samples.py <test.tar.gz> <test.tsv> <out dir> [count=20]

Takes the first <count> utterances of the tsv (in file order) whose length is 4 to 12 s, writes <out>/NN.wav (16 kHz mono 16-bit,
as in FLEURS) and <out>/refs.tsv (NN<TAB>reference, the FLEURS normalised transcription: lower case, no punctuation). The audio is
never committed: it stays under ~/.cache (BUILDING.md section 8). The selection is deterministic, so the sample set is reproducible.
"""
import csv
import io
import os
import sys
import tarfile

import soundfile  # pip install soundfile (host tool only)

tar_path, tsv_path, out_dir = sys.argv[1:4]
count = int(sys.argv[4]) if len(sys.argv) > 4 else 20
os.makedirs(out_dir, exist_ok=True)

rows = []
with open(tsv_path, encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
        # id, file name, raw transcription, transcription, phonemes, num_samples, gender
        samples = int(r[5])
        if 4 * 16000 <= samples <= 12 * 16000:
            rows.append((r[1], r[3], samples))
rows = rows[:count]
wanted = {name: i for i, (name, _, _) in enumerate(rows)}

found = 0
with tarfile.open(tar_path, "r:gz") as tar:
    for member in tar:
        name = os.path.basename(member.name)
        if name in wanted:
            audio, rate = soundfile.read(io.BytesIO(tar.extractfile(member).read()), dtype="float32")  # FLEURS wavs are float32 or int16
            assert rate == 16000 and audio.ndim == 1, name
            soundfile.write(os.path.join(out_dir, f"{wanted[name]:02d}.wav"), audio, rate, subtype="PCM_16")
            found += 1
            if found == len(wanted):
                break
assert found == len(wanted), f"found {found} of {len(wanted)} files"
with open(os.path.join(out_dir, "refs.tsv"), "w", encoding="utf-8") as w:
    for name, ref, _ in rows:
        w.write(f"{wanted[name]:02d}\t{ref}\n")
print(f"{found} utterances -> {out_dir}")
