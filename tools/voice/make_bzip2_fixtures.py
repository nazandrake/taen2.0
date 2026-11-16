#!/usr/bin/env python3
"""Makes the bzip2 fixtures of Bzip2InputStreamTest with Python's bz2 module (libbz2), the independent reference of the decoder.

usage: python3 -I tools/voice/make_bzip2_fixtures.py [output dir, default app/src/test/resources/bzip2]
Writes <name>.bz2 files and fixtures.txt (tab separated: name, length of the plain data, SHA-256 of the plain data). The plain data is made from
a fixed seed (nothing private, nothing random between runs); the expected values in fixtures.txt come from hashlib, not from the Kotlin code.
"""
import bz2
import hashlib
import os
import random
import sys

out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "bzip2")
os.makedirs(out, exist_ok=True)
rng = random.Random(595)

words = ["taen", "keyboard", "voice", "model", "българия", "клавиатура", "the", "of", "and", "tokens", "encoder", "decoder"]
text = " ".join(rng.choice(words) for _ in range(60000)).encode("utf-8")  # about 400 KB of text: several blocks at level 1
noise = bytes(rng.getrandbits(8) for _ in range(150_000))  # incompressible: the largest symbol alphabet, many selectors
runs = b"".join(bytes([b]) * n for b, n in [(97, 1), (98, 3), (99, 4), (100, 5), (101, 255), (102, 258), (103, 259), (104, 260), (105, 1000), (97, 4), (97, 4), (0, 70000)])
mixed = b"".join(runs if i % 2 else noise[:3000] for i in range(6))

cases = {
    "empty": (b"", 9),
    "one-byte": (b"x", 9),
    "short-text": (b"hello hello hello bzip2 world\n", 9),
    "runs": (runs, 9),
    "mixed": (mixed, 1),
    "text-multi-block": (text, 1),
    "noise": (noise, 9),
    "all-byte-values": (bytes(range(256)) * 40, 5),
}
with open(os.path.join(out, "fixtures.txt"), "w", encoding="utf-8") as manifest:
    for name, (data, level) in cases.items():
        with open(os.path.join(out, name + ".bz2"), "wb") as f:
            f.write(bz2.compress(data, level))
        manifest.write(f"{name}\t{len(data)}\t{hashlib.sha256(data).hexdigest()}\n")
        print(name, len(data), level)

# A stand-in model archive with the layout of the real one (a folder with the model files, README.md and test_wavs/), made with tarfile:
# the end-to-end test of the archive installer reads it with the real decoder. SHA-256 and size of the archive itself go to the manifest.
import io
import tarfile

folder = "standin-model"
members = [
    (folder, None),
    (folder + "/tokens.txt", b"tokens-A"),
    (folder + "/README.md", b"stand-in readme\n"),
    (folder + "/decoder.int8.onnx", b"decoder-bytes"),
    (folder + "/encoder.int8.onnx", b"encoder-bytes-0123456789"),
    (folder + "/test_wavs", None),
    (folder + "/test_wavs/en.wav", b"RIFF-standin"),
]
raw = io.BytesIO()
with tarfile.open(fileobj=raw, mode="w", format=tarfile.USTAR_FORMAT) as tar:
    for name, content in members:
        info = tarfile.TarInfo(name)
        info.mtime = 1_700_000_000
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        if content is None:
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            tar.addfile(info)
        else:
            info.size = len(content)
            info.mode = 0o644
            tar.addfile(info, io.BytesIO(content))
archive = bz2.compress(raw.getvalue(), 9)
with open(os.path.join(out, "standin-model.tar.bz2"), "wb") as f:
    f.write(archive)
with open(os.path.join(out, "fixtures.txt"), "a", encoding="utf-8") as manifest:
    manifest.write(f"standin-model.tar.bz2\t{len(archive)}\t{hashlib.sha256(archive).hexdigest()}\n")
print("standin-model.tar.bz2", len(archive))
