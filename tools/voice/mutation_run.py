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
    ("microphone not released when the recording ends", V + "/VoiceSession.kt", "device.release() // the microphone is closed before anything else happens", "Unit", "*VoiceSessionTest*"),
    ("audio not cleared after use", V + "/VoiceSession.kt", "samples.fill(0f)", "Unit", "*VoiceSessionTest*"),
    ("text committed after a field change", V + "/VoiceController.kt", "if (host.fieldGeneration() != startedInGeneration || host.passwordField()", "if (host.passwordField()", "*VoiceControllerTest*"),
    ("hold release does not stop", V + "/VoiceController.kt", "        if (held) {\n            held = false\n            session.stop()\n        }", "        held = false", "*VoiceControllerTest*"),
    ("model hash not compared on import", V + "/VoiceModelStore.kt", "if (copied != entry.size || hex(digest.digest()) != entry.sha256) return ImportResult.Failed", "if (copied != entry.size) return ImportResult.Failed", "*VoiceModelStoreTest*"),
    ("wrong file copied before it is recognised", V + "/VoiceModelStore.kt", "?: return ImportResult.NotAModelFile // more bytes than the largest model file", "?: Pair(\"\", 0L) // more bytes than the largest model file", "*VoiceModelStoreTest*"),
    ("verify accepts a changed file", V + "/VoiceModelStore.kt", "== entry.sha256\n    }", "!= \"\"\n    }", "*VoiceModelStoreTest*"),
    ("partial wipe removes the model", SRC + "/privacy/Wiper.kt", "relative.startsWith(\"no_backup/${Privacy.VOICE_DIR}/\") -> Category.NEVER", "relative.startsWith(\"no_backup/${Privacy.VOICE_DIR}/\") -> Category.SETTINGS", "*FileWiperTest*"),
    ("clipboard fetched at service start (F1)", SRC + "/ClipboardHistoryManager.kt", "        scheduleExpiry()\n    }\n\n    fun onDestroy()", "        if (latinIME.mSettings.current.mClipboardHistoryEnabled) fetchPrimaryClip()\n        scheduleExpiry()\n    }\n\n    fun onDestroy()", "*ClipboardServiceStartTest*"),
    # phrase by phrase: the microphone's lifetime
    ("a field change leaves the microphone open", SRC + "/LatinIME.java", "if (!restarting) mVoiceTyping.onFieldChange();", "if (false) mVoiceTyping.onFieldChange();", "*VoiceMicrophoneWiringTest*"),
    ("the window closing leaves the microphone open", SRC + "/LatinIME.java", "        mVoiceTyping.onFieldChange(); // the keyboard window closes", "        // the keyboard window closes", "*VoiceMicrophoneWiringTest*"),
    ("the screen lock leaves the microphone open", SRC + "/VoiceTyping.kt", "        Privacy.onLock(LOCK_HOOK, lockHook) // screen off", "        // screen off", "*VoiceMicrophoneWiringTest*"),
    ("an incognito switch leaves the microphone open", SRC.replace("latin", "keyboard") + "/KeyboardActionListenerImpl.kt", "                latinIME.onIncognitoToggled() // a recording ends", "                // a recording ends", "*VoiceMicrophoneWiringTest*"),
    ("the end of the service leaves the microphone open", SRC + "/VoiceTyping.kt", "        handler.removeCallbacks(unloadEngine)\n        cancel()", "        handler.removeCallbacks(unloadEngine)", "*VoiceMicrophoneWiringTest*"),
    ("a cancel does not stop a blocked read", V + "/VoiceSession.kt", "            stopRequested = true\n            recording?.abort()\n", "            stopRequested = true\n", "*VoiceSessionTest*"),
    ("a failed engine leaves the microphone open", V + "/VoiceSession.kt", "                recording?.abort() // the engine cannot type anything: the microphone is closed too\n                stopRequested = true\n", "", "*VoiceSessionTest*"),
    ("no length limit for the recording", V + "/VoiceSession.kt", "while (!stopRequested && length < pcm.size)", "while (!stopRequested)", "*VoiceSessionTest*"),
    ("no length limit for a phrase", V + "/PauseDetector.kt", "val cut = phrase >= maxPhraseSamples ||", "val cut = false ||", "*Voice*"),
    ("a cancelled recording still transcribes waiting phrases", V + "/VoiceSession.kt", "        if (cancelled || failed) {\n            pcm.fill(0, from, to)", "        if (failed) {\n            pcm.fill(0, from, to)", "*VoiceSessionTest*"),
    ("the phrase audio is not cleared once copied", V + "/VoiceSession.kt", "        val samples = FloatArray(count) { pcm[from + it] / 32768f }\n        pcm.fill(0, from, to)", "        val samples = FloatArray(count) { pcm[from + it] / 32768f }", "*VoiceSessionTest*"),
    ("a pause shorter than 0.8 s ends a phrase", V + "/PauseDetector.kt", "pause >= pauseSamples", "pause >= pauseSamples - VoiceSession.CHUNK", "*PauseDetectorTest*"),
    ("the zeros at the start set the floor", V + "/PauseDetector.kt", "if (rms >= MIN_FLOOR_RMS && rms < floor) floor = rms", "if (rms < floor) floor = rms", "*PauseDetectorTest*"),
    # the user's words on voice text
    ("voice text reads the user words in an incognito field", SRC + "/DictionaryFacilitatorImpl.kt", "        if (!userWordsAllowed(settingsValuesForSuggestion)) return null\n        val words = ArrayList<String>()", "        val words = ArrayList<String>()", "*PersonalSourcesPrivacyTest*"),
    ("voice text keeps user words read across a lock", SRC + "/DictionaryFacilitatorImpl.kt", "return if (userWordsAllowed(settingsValuesForSuggestion)) words else null", "return words", "*PersonalSourcesPrivacyTest*"),
    ("voice text takes a list that is not whole", SRC + "/DictionaryFacilitatorImpl.kt", "if (!dictionary.forEachWord { word, _ -> if (!group.isBlacklisted(word)) words.add(word) }) return null", "dictionary.forEachWord { word, _ -> if (!group.isBlacklisted(word)) words.add(word) }", "*PersonalSourcesPrivacyTest*"),
    ("an unknown main dictionary lets voice words change any word", SRC + "/DictionaryFacilitatorImpl.kt", "return true // not known now", "return false // not known now", "*PersonalSourcesPrivacyTest*"),
    ("dictionary words are replaced", SRC + "/VoiceWords.kt", "if (!source.isDictionaryWord(word) && !source.isDictionaryWord(word.lowercase(casing.locale))) {", "if (true) {", "*VoiceWordsTest*"),
    ("two words one edit away: the first is taken", SRC + "/VoiceWords.kt", "users.filter { oneEdit(it.second, key) }.map { it.first }.singleOrNull()", "users.filter { oneEdit(it.second, key) }.map { it.first }.firstOrNull()", "*VoiceWordsTest*"),
    ("two spellings of the same letters: the first is taken", SRC + "/VoiceWords.kt", "if (word in same || same.size > 1) return null", "if (word in same) return null", "*VoiceWordsTest*"),
    ("short words matched one edit away", SRC + "/VoiceWords.kt", "key.codePointCount(0, key.length) < MIN_EDIT_LETTERS -> return null", "key.codePointCount(0, key.length) < 3 -> return null", "*VoiceWordsTest*"),
    ("voice text ignores the field's decision in the service", SRC + "/LatinIME.java", "VoiceWords.INSTANCE.source(mDictionaryFacilitator, settings.mSettingsValuesForSuggestion)", "VoiceWords.INSTANCE.source(mDictionaryFacilitator, new helium314.keyboard.latin.settings.SettingsValuesForSuggestion(false, false, true))", "*InputLogicTest*"),
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
