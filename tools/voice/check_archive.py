#!/usr/bin/env python3
"""Checks the one voice-model archive against the locks, independently of the Kotlin code.

usage: python3 -I tools/voice/check_archive.py <downloaded .tar.bz2>
Reads tools/voice-model-archive.lock and tools/voice-model.lock, then: (1) size and SHA-256 of the archive equal the archive lock,
(2) the archive holds the folder of the lock with the four model files, and the size and SHA-256 of each equal the file lock (read as a stream,
nothing is extracted to disk), (3) lists every other entry. Exit status 0 only when everything matches.
"""
import hashlib
import os
import sys
import tarfile

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


def rows(name):
    with open(os.path.join(root, "tools", name), encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f if line.strip() and not line.startswith("#")]


def main(path):
    ((archive_name, archive_size, archive_sha, _url, folder),) = rows("voice-model-archive.lock")
    files = {name: (int(size), sha) for name, size, sha, _ in rows("voice-model.lock")}
    ok = True
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
            size += len(chunk)
    print(f"archive {archive_name}: size {size} (lock {archive_size}), sha256 {h.hexdigest()} (lock {archive_sha})")
    ok &= size == int(archive_size) and h.hexdigest() == archive_sha
    seen = set()
    with tarfile.open(path, "r:bz2") as tar:
        for member in tar:
            if member.isfile() and member.name.startswith(folder + "/") and member.name[len(folder) + 1:] in files:
                name = member.name[len(folder) + 1:]
                inner = hashlib.sha256()
                count = 0
                src = tar.extractfile(member)
                for chunk in iter(lambda: src.read(1 << 20), b""):
                    inner.update(chunk)
                    count += len(chunk)
                want_size, want_sha = files[name]
                match = count == want_size and inner.hexdigest() == want_sha
                print(f"{'OK  ' if match else 'FAIL'} {name}: {count} bytes, sha256 {inner.hexdigest()}")
                ok &= match
                seen.add(name)
            else:
                print(f"other entry: {member.name} type={member.type!r} size={member.size}")
    missing = set(files) - seen
    if missing:
        print("missing in the archive:", ", ".join(sorted(missing)))
        ok = False
    print("ARCHIVE MATCHES THE LOCKS" if ok else "MISMATCH")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
