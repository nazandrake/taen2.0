#!/usr/bin/env python3
"""Mutation run for the voice typing risk logic: each mutation changes one condition in the production source, the named tests must
FAIL, then the file is restored byte for byte. Prints one line per mutation. Needs free memory like a normal build; runs Gradle once per mutation.
usage: mutation_run.py <log dir>"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "helium314", "keyboard", "latin")
V = os.path.join(SRC, "voice")

# (name, file, old text, new text, test filter)
MUTATIONS = [
    ("gate ignores password fields", V + "/VoicePolicy.kt", "!enabled || passwordField || locked -> Action.IGNORE", "!enabled || locked -> Action.IGNORE", "*Voice*"),
    ("gate ignores the lock", V + "/VoicePolicy.kt", "!enabled || passwordField || locked -> Action.IGNORE", "!enabled || passwordField -> Action.IGNORE", "*Voice*"),
    ("gate asks the permission before the model", V + "/VoicePolicy.kt", "!modelReady -> Action.NEED_MODEL\n        !permissionGranted -> Action.NEED_PERMISSION", "!permissionGranted -> Action.NEED_PERMISSION\n        !modelReady -> Action.NEED_MODEL", "*Voice*"),
    ("microphone not released before the engine runs", V + "/VoiceSession.kt", "device.release() // the microphone is closed before anything else happens", "Unit", "*VoiceSessionTest*"),
    ("audio not cleared after use", V + "/VoiceSession.kt", "samples.fill(0f)", "Unit", "*VoiceSessionTest*"),
    ("text committed after a field change", V + "/VoiceController.kt", "if (host.fieldGeneration() != startedInGeneration || host.passwordField()", "if (host.passwordField()", "*VoiceControllerTest*"),
    ("hold release does not stop", V + "/VoiceController.kt", "        if (held) {\n            held = false\n            session.stop()\n        }", "        held = false", "*VoiceControllerTest*"),
    ("model hash not compared on import", V + "/VoiceModelStore.kt", "if (copied != entry.size || hex(digest.digest()) != entry.sha256) return ImportResult.Failed", "if (copied != entry.size) return ImportResult.Failed", "*VoiceModelStoreTest*"),
    ("wrong file copied before it is recognised", V + "/VoiceModelStore.kt", "?: return ImportResult.NotAModelFile // more bytes than the largest model file", "?: Pair(\"\", 0L) // more bytes than the largest model file", "*VoiceModelStoreTest*"),
    ("verify accepts a changed file", V + "/VoiceModelStore.kt", "== entry.sha256\n    }", "!= \"\"\n    }", "*VoiceModelStoreTest*"),
    ("partial wipe removes the model", SRC + "/privacy/Wiper.kt", "relative.startsWith(\"no_backup/${Privacy.VOICE_DIR}/\") -> Category.NEVER", "relative.startsWith(\"no_backup/${Privacy.VOICE_DIR}/\") -> Category.SETTINGS", "*FileWiperTest*"),
    ("clipboard fetched at service start (F1)", SRC + "/ClipboardHistoryManager.kt", "        scheduleExpiry()\n    }\n\n    fun onDestroy()", "        if (latinIME.mSettings.current.mClipboardHistoryEnabled) fetchPrimaryClip()\n        scheduleExpiry()\n    }\n\n    fun onDestroy()", "*ClipboardServiceStartTest*"),
]


def main():
    logs = sys.argv[1]
    os.makedirs(logs, exist_ok=True)
    for i, (name, path, old, new, tests) in enumerate(MUTATIONS):
        original = open(path, encoding="utf-8").read()
        if old not in original:
            print("SKIP (pattern not found): " + name)
            continue
        if old == new:
            print("SKIP (no change): " + name)
            continue
        shutil.copy(path, path + ".orig")
        try:
            open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            log = os.path.join(logs, "mutation-%02d.log" % i)
            with open(log, "w") as f:
                r = subprocess.run(["flock", os.environ.get("TAEN_BUILD_LOCK", os.path.expanduser("~/.cache/taen-build.lock")), os.path.join(ROOT, "gradlew"), "--console=plain", "--offline", "--no-daemon",
                                    ":app:testRunTestsUnitTest", "--tests", tests], cwd=ROOT, stdout=f, stderr=subprocess.STDOUT)
            text = open(log, errors="replace").read()
            verdict = "COMPILE ERROR (not a result)" if "\ne: file" in text else "KILLED" if r.returncode != 0 and " FAILED" in text else "SURVIVED" if r.returncode == 0 else "BUILD ERROR (not a result)"
            print("%s: %s (%s, log %s)" % (verdict, name, tests, log), flush=True)
        finally:
            shutil.move(path + ".orig", path)


if __name__ == "__main__":
    main()
