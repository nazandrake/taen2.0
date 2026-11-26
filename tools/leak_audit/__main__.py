"""Taen leak audit. Usage (from the repository root, see BUILDING.md section 9):

  python3 tools/leak_audit static  [--apk APK] [--out DIR]     static checks of the release APK  (make leak-audit)
  python3 tools/leak_audit selftest [--out DIR]                planted failures: every check category must be able to fail
  python3 tools/leak_audit device  [--serial S] [--out DIR]    scripted session on a phone       (make leak-audit-device)

All sections go to one report, DIR/leak-audit-report.md (DIR/results.json holds the raw results). The exit status is 1 when any
check FAILs, and when the network checks are GAPs over adb on Wi-Fi and the release "may pass" rule is not met (the report line says why);
any other GAP is a named check that could not be run and does not change the status."""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from model import FAIL, Store  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))


def default_apk():
    import re
    with open(os.path.join(ROOT, "app", "build.gradle.kts")) as f:
        version = re.search(r'versionName = "([^"]+)"', f.read()).group(1)
    return os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "Taen_%s-release.apk" % version)


def main(argv):
    p = argparse.ArgumentParser(prog="leak_audit")
    p.add_argument("section", choices=("static", "selftest", "device"))
    p.add_argument("--apk", default=None)
    p.add_argument("--out", default=os.path.join(ROOT, "build", "leak-audit"))
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    p.add_argument("--no-deps", action="store_true", help="skip the Gradle dependency listing")
    p.add_argument("--audit-apk", default=None, help="device: the debuggable audit APK (default app/build/outputs/apk/audit)")
    p.add_argument("--fast", action="store_true", help="device: skip the real wipe (it waits for the 15 minute test mode to end)")
    p.add_argument("--release-apk", default=None, help="device: the owner-signed release APK (default dist/taen-<version>-signed.apk from make sign)")
    args = p.parse_args(argv)
    store = Store(args.out)
    if args.section == "static":
        import apk as apklib
        import static_run
        apk = args.apk or default_apk()
        if not os.path.exists(apk):
            raise SystemExit("no release APK at %s (run make build or ./gradlew :app:assembleRelease)" % apk)
        results = static_run.run_all(ROOT, apk, with_deps=not args.no_deps)
        store.put("static", results, {"apk": apk, "sha256": static_run.sha256(apk), "commit": git_head()})
    elif args.section == "selftest":
        import selftest
        results = selftest.run_all(ROOT, args.out)
        store.put("selftest", results, {"commit": git_head()})
    else:
        import device_audit
        results, meta = device_audit.run_all(ROOT, args)
        store.put("device", results, meta)
    for r in results:
        print(r)
    print("report: " + os.path.join(args.out, "leak-audit-report.md"))
    if store.release_may_pass() is False:  # network GAPs over Wi-Fi without the static proof on the same APK
        print("the release may NOT pass: " + store.network_gap_line())
        return 1
    return 1 if any(r.status == FAIL for r in results) else 0


def git_head():
    import subprocess
    out = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short=12", "HEAD"], capture_output=True, text=True)
    dirty = subprocess.run(["git", "-C", ROOT, "status", "--porcelain", "--untracked-files=no"], capture_output=True, text=True).stdout.strip()
    return out.stdout.strip() + (" (+uncommitted changes)" if dirty else "")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
