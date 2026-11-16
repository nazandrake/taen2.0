#!/usr/bin/env python3
"""Scans the data directories of a debuggable Taen build on a phone for plaintext user data (privacy invariant P7).

  tools/device-scan.py --canary TAENcanary-Qx93 --canary ТАЕНканарче --expect 'no_backup/taen/learned_*.enc' \
      --forbid 'files/UserHistoryDictionary*' [--package io.github.nazandrake.taen.debug] [--serial SERIAL]
  tools/device-scan.py --selftest        # no phone needed: checks the scanner itself (positive and negative controls)

The data is read with `adb exec-out run-as <package> tar` (only a debuggable build allows it), never stored: the tar is
scanned in memory. Every canary is searched as UTF-8, UTF-16 LE, UTF-16 BE and Windows-1251. Exit status 1 when a canary is
found, a forbidden file exists, or an expected file is missing.

The app writes no file to shared (external) storage (P11: no crash report, no log, no diagnostics): the scan also lists
/sdcard/Android/data/<package> and /sdcard/Android/media/<package> with `adb shell find` and fails on ANY file there (or when
the directory cannot be listed for another reason than not existing).
"""
import argparse
import fnmatch
import io
import subprocess
import sys
import tarfile

ENCODINGS = ("utf-8", "utf-16-le", "utf-16-be", "cp1251", "trie")

# Plain files of earlier versions that must be gone after the upgrade (they are moved into the encrypted stores and deleted):
# the old preferences file (device-protected and credential-encrypted storage both used <package>_preferences.xml), the
# clipboard database, the readable background image and the readable learned-word dictionary.
LEGACY_FORBID = ("shared_prefs/*_preferences.xml", "databases/heliboard.db*", "files/custom_background_image*", "files/UserHistoryDictionary*")


def encode(canary, enc):
    if enc == "trie":
        # the native dictionary's own character encoding: one byte for U+0020..U+00FF, otherwise three bytes big endian
        return b"".join(bytes([ord(c)]) if 0x20 <= ord(c) <= 0xFF else ord(c).to_bytes(3, "big") for c in canary)
    return canary.encode(enc)


def needles(canary):
    result = {}
    for enc in ENCODINGS:
        try:
            result.setdefault(encode(canary, enc), enc)  # ASCII is the same bytes in UTF-8 and Windows-1251: count once
        except UnicodeEncodeError:
            pass  # a text outside the code page just has no such form
    return [(enc, b) for b, enc in result.items()]


def scan_tar(data, canaries, forbid=(), expect=()):
    """Returns (rows, hits, problems): rows are (path, size, {canary: count}); hits the total count per canary."""
    rows = []
    hits = {c: 0 for c in canaries}
    paths = []
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for member in tar:
            if not member.isfile():
                continue
            path = member.name.lstrip("./")
            paths.append(path)
            blob = tar.extractfile(member).read()
            counts = {c: sum(blob.count(n) for _, n in needles(c)) for c in canaries}
            for c, n in counts.items():
                hits[c] += n
            rows.append((path, len(blob), counts))
    problems = []
    for pattern in forbid:
        for p in paths:
            if fnmatch.fnmatch(p, pattern):
                problems.append("forbidden file present: " + p)
    for pattern in expect:
        if not any(fnmatch.fnmatch(p, pattern) for p in paths):
            problems.append("expected file missing: " + pattern)
    return rows, hits, problems


# the voice model (public data, 670 MB, no_backup/taen-voice) is not user data: the tar leaves it out and the audit checks its file list and sizes instead
# (tools/leak_audit/device_voice.py, V6: exactly the four pinned names and sizes, nothing else in that directory).
MODEL_DIR = "./no_backup/taen-voice"


def fetch(serial, package, base):
    cmd = ["adb"] + (["-s", serial] if serial else []) + ["exec-out", "run-as", package, "tar", "-c", "--exclude", MODEL_DIR, "-C", base.format(pkg=package), "."]
    out = subprocess.run(cmd, capture_output=True, check=False)
    if not out.stdout:
        raise SystemExit("no data from %s (is the build debuggable and installed?): %s" % (base, out.stderr.decode(errors="replace")[:200]))
    return out.stdout


EXTERNAL_BASES = ("/sdcard/Android/data/{pkg}", "/sdcard/Android/media/{pkg}")


def external_problems(base, listing, stderr):
    """Problems of one external directory: every listed file, and a listing that failed for any reason but a missing directory."""
    problems = ["external file present: " + line.strip() for line in listing.splitlines() if line.strip()]
    err = stderr.strip()
    if err and "No such file" not in err:
        problems.append("cannot list %s: %s" % (base, err[:120]))
    return problems


def scan_external(serial, package):
    problems = []
    for pattern in EXTERNAL_BASES:
        base = pattern.format(pkg=package)
        out = subprocess.run(["adb"] + (["-s", serial] if serial else []) + ["shell", "find", base, "-type", "f"], capture_output=True, check=False)
        problems += external_problems(base, out.stdout.decode(errors="replace"), out.stderr.decode(errors="replace"))
    return problems


def make_tar(files):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        for name, blob in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(blob)
            tar.addfile(info, io.BytesIO(blob))
    return buf.getvalue()


def selftest():
    latin, cyr = "TAENcanary-Qx93", "ТАЕНканарче-Жщ93"
    failures = []

    def check(cond, text):
        if not cond:
            failures.append(text)

    # positive controls: the canary is found in every encoding, in a file of any name
    for canary in (latin, cyr):
        for enc in ("utf-8", "utf-16-le", "utf-16-be", "cp1251", "trie"):
            tar = make_tar({"user/files/x.bin": b"\x01\x02" + encode(canary, enc) + b"\x03"})
            rows, hits, problems = scan_tar(tar, [canary])
            check(hits[canary] == 1, "%s in %s not found (%r)" % (canary, enc, hits))
    # negative control: a file that does not contain it, with the same size and a near miss
    tar = make_tar({"user/no_backup/taen/learned_en.enc": bytes(range(256)) * 4, "user/files/near": b"TAENcanary-Qx9"})
    rows, hits, problems = scan_tar(tar, [latin, cyr])
    check(hits == {latin: 0, cyr: 0}, "false hit %r" % (hits,))
    # expectations and forbidden files
    rows, hits, problems = scan_tar(tar, [latin], forbid=["user/files/near"], expect=["user/no_backup/taen/learned_*.enc"])
    check(problems == ["forbidden file present: user/files/near"], "forbid: %r" % (problems,))
    rows, hits, problems = scan_tar(tar, [latin], expect=["user/no_backup/taen/snippets.enc"])
    check(problems == ["expected file missing: user/no_backup/taen/snippets.enc"], "expect: %r" % (problems,))
    # the plain files of earlier versions are always forbidden, in either data directory
    legacy = make_tar({"shared_prefs/io.github.nazandrake.taen.debug_preferences.xml": b"<map/>", "no_backup/taen/prefs_ce.enc": b"x"})
    rows, hits, problems = scan_tar(legacy, [latin], forbid=LEGACY_FORBID)
    check(problems == ["forbidden file present: shared_prefs/io.github.nazandrake.taen.debug_preferences.xml"], "legacy prefs: %r" % (problems,))
    rows, hits, problems = scan_tar(make_tar({"no_backup/taen/prefs_ce.enc": b"x", "shared_prefs/other.xml": b"<map/>"}), [latin], forbid=LEGACY_FORBID)
    check(problems == [], "no false legacy hit: %r" % (problems,))
    # external storage: any file is a problem; a missing directory is fine; another listing error cannot be verified and fails
    base = "/sdcard/Android/data/io.github.nazandrake.taen.debug"
    check(external_problems(base, "", "") == [], "empty external dir")
    check(external_problems(base, "", "find: /sdcard/Android/data/x: No such file or directory\n") == [], "missing external dir")
    check(external_problems(base, base + "/files/crash_report_2026.txt\n", "") == ["external file present: " + base + "/files/crash_report_2026.txt"], "one external file")
    check(len(external_problems(base, "a\nb\n", "")) == 2, "two external files")
    check(len(external_problems(base, "", "find: Permission denied")) == 1, "unlistable external dir")
    for f in failures:
        print("SELFTEST FAIL:", f)
    print("SELFTEST PASS" if not failures else "SELFTEST FAILED")
    return 0 if not failures else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--serial")
    ap.add_argument("--package", default="io.github.nazandrake.taen.debug")
    ap.add_argument("--canary", action="append", default=[])
    ap.add_argument("--forbid", action="append", default=[], help="glob of files that must not exist (relative to user/ or user_de/)")
    ap.add_argument("--expect", action="append", default=[], help="glob of files that must exist")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if not args.canary:
        ap.error("give at least one --canary")
    total = {c: 0 for c in args.canary}
    problems = []
    for label, base in (("user", "/data/user/0/{pkg}"), ("user_de", "/data/user_de/0/{pkg}")):
        data = fetch(args.serial, args.package, base)
        rows, hits, probs = scan_tar(data, args.canary, list(LEGACY_FORBID) + [p.replace("user/", "", 1) for p in args.forbid], [p.replace("user/", "", 1) for p in args.expect])
        for path, size, counts in rows:
            print("%s/%s %d %r" % (label, path, size, counts))
        for c, n in hits.items():
            total[c] += n
        # expectations are for one of the two directories; report a missing file only if it is in neither
        problems.append((label, probs))
    external = scan_external(args.serial, args.package)
    for p in external:
        print("PROBLEM:", p)
    print("TOTAL hits", total, "external problems", len(external))
    expect_missing = set.intersection(*[{p for p in probs if p.startswith("expected")} for _, probs in problems]) if problems else set()
    other = [p for _, probs in problems for p in probs if not p.startswith("expected")]
    for p in sorted(expect_missing) + other:
        print("PROBLEM:", p)
    return 1 if any(total.values()) or expect_missing or other or external else 0


if __name__ == "__main__":
    sys.exit(main())
