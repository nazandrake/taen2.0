"""run_all of the device audit (see device_flow.py for the checks)."""
import glob
import os
import re
import subprocess
import time

import apk as apklib
import device_checks as dc
import device_flow as fl
import device_voice as dv
import helper_build
import netstats
import selftest
from device_run import CANARIES, HELPER, HELPER_ACTIVITY, ORIGINAL_IME, Run, version_name
from model import FAIL, GAP, INFO, PASS, Result, verdict

TEST_MODE_SECONDS = 15 * 60


def export_backup(run):
    """Settings > Advanced > Backup and restore > Backup: file picker, passphrase, then the file is pulled, scanned and deleted."""
    d = run.dev
    d.sh("rm -f /sdcard/Download/Taen_backup_*.taenbk")
    run.settings_home()
    run.open_section("Advanced")
    time.sleep(1)
    run.scroll_to("Backup and restore")
    d.tap_text("Backup and restore", exact=True)
    time.sleep(1)
    d.tap_text("Backup", exact=True)
    time.sleep(2.5)
    d.tap_text("SAVE", exact=True)
    time.sleep(1.5)
    fields = [n for n in d.nodes() if n["class"] == "EditText"]
    if len(fields) < 2:
        raise RuntimeError("passphrase dialog not shown: " + "; ".join(d.texts()[:6]))
    d.tap(fields[0]["cx"], fields[0]["cy"])
    time.sleep(0.5)
    d.sh("input keyevent 123 " + "67 " * 30)
    d.type_text_field(CANARIES["passphrase"])
    d.key(61)
    time.sleep(0.4)
    d.type_text_field(CANARIES["passphrase"])
    d.tap_text("OK", exact=True)
    listing = ""
    for _ in range(40):  # the key derivation (Argon2id, 64 MiB) takes a few seconds on this phone
        time.sleep(1.5)
        listing = d.sh("ls /sdcard/Download/ | grep taenbk").strip()
        if listing and int(d.sh("stat -c %%s /sdcard/Download/%s" % listing.splitlines()[0]).strip() or 0) > 0:
            break
    if not listing:
        raise RuntimeError("no export file written")
    local = os.path.join(run.work, "export.taenbk")
    d.adb("pull", "/sdcard/Download/" + listing.splitlines()[0], local)
    d.sh("rm -f /sdcard/Download/Taen_backup_*.taenbk")
    with open(local, "rb") as f:
        data = f.read()
    os.remove(local)
    return data


def save_canary_profile(run):
    """Settings > Profiles and templates > Save the current settings: the canary name goes into ONE value of the encrypted preferences. The scans after it
    (D3 at rest, D9 dumps, D10 export, D13b after the wipe) look for it like for every other canary."""
    d = run.dev
    run.settings_home()
    d.tap_text("Profiles and templates", exact=True)
    time.sleep(1.5)
    d.scroll_down()  # the row is the last one of the page, so scroll_to's band (it never sits above y 1600 at the end of the list) cannot be used
    d.tap_text("Save the current settings as a profile", exact=True)
    time.sleep(1)
    fields = [n for n in d.nodes() if n["class"] == "EditText"]
    if not fields:
        raise RuntimeError("profile name dialog not shown: " + "; ".join(d.texts()[:6]))
    d.tap(fields[0]["cx"], fields[0]["cy"])
    time.sleep(0.5)
    d.type_text_field(CANARIES["profile"])
    d.tap_text("OK", exact=True)
    time.sleep(1.5)
    ok = bool(d.wait_text(CANARIES["profile"], 5))
    return Result("P1", "A profile named with the canary was saved in Profiles and templates", PASS if ok else FAIL,
                  "the list shows the canary name" if ok else "the saved profile is not listed: " + "; ".join(d.texts()[:8]))


def export_check(data):
    hits = {k: v for k, v in dc.scan_bytes(data, CANARIES).items() if v}
    problems = ["canary %s in the export file" % k for k in hits]
    if not data.startswith(b"TAENBK1"):
        problems.append("export file does not start with the TAENBK1 magic: %r" % data[:8])
    if len(data) < 200:
        problems.append("export file is only %d bytes" % len(data))
    return verdict("D10", "Encrypted export: TAENBK1 header, no canary (typed words, clipboard, passphrase) in the file", problems,
                   "%d bytes, TAENBK1 header, no canary in any encoding" % len(data), ["first bytes: %r" % data[:16]]), hits


def export_control(data):
    planted = data + CANARIES["latin"].encode()
    hits = dc.scan_bytes(planted, CANARIES)
    ok = hits["latin"] >= 1 and dc.scan_bytes(data, CANARIES)["latin"] == 0
    return Result("T-D10", "D10 can fail: the same export scan finds a canary appended to the file", PASS if ok else FAIL, "planted hits: %s" % hits["latin"])


def clip_results(run, found, pasted, system_clip, other_read):
    problems = []
    if not found["clip_normal"]:
        problems.append("the normal copy is not in the history (the check has no positive control)")
    if found["clip_sensitive"]:
        problems.append("the copy flagged sensitive is in the history")
    if found["clip_password"]:
        problems.append("the copy made in a password field is in the history")
    if not pasted or CANARIES["clip_normal"] not in pasted:
        problems.append("paste from the history did not insert the entry (got %r)" % pasted)
    if system_clip is None or CANARIES["clip_password"] not in system_clip:
        problems.append("system clip after the history paste is %r, expected the unchanged last clip" % system_clip)
    d = run.dev
    denied = d.sh("ls /data/user/0/%s/no_backup 2>&1" % run.pkg)
    if "Permission denied" not in denied and "No such file" not in denied:
        problems.append("another UID (shell) could list the app's private directory: " + denied[:100])
    return verdict("D6", "Clipboard: normal copy kept (control), sensitive and password-field copies not kept, history paste leaves the system clip, other UIDs cannot read the stores", problems,
                   "history shows %s; paste inserted the entry; the system clip stayed %r; shell UID: %s" % (
                       sorted(k for k, v in found.items() if v), system_clip, denied.strip()[:60]),
                   ["found: %s" % found, "pasted field: %r" % pasted])


def run_all(root, args):
    release_apk = apklib.signed_release_apk(root, version_name(root), args.release_apk)  # before the phone is touched
    run = Run(root, args)
    d = run.dev
    if not d.present():
        return [Result("D0", "Phone on adb", GAP, "no phone found: every device check is unrun")], {}
    serial = d.adb("get-serialno").stdout.decode().strip()
    if ":" in serial:  # adb over Wi-Fi (host:port)
        return [Result("D0", "Phone on USB adb", GAP, "the phone is connected over Wi-Fi (%s): the network check (D1) switches the phone's Wi-Fi off and on, which ends that connection "
                       "and leaves the phone in the audit's state; connect it by USB and run again: every device check is unrun" % serial)], {}
    ver = version_name(root)
    audit_apk = args.audit_apk or os.path.join(root, "app", "build", "outputs", "apk", "audit", "Taen_%s-audit.apk" % ver)
    run.meta = {"model": d.sh("getprop ro.product.model").strip(), "android": d.sh("getprop ro.build.version.release").strip(),
                "build type": d.sh("getprop ro.build.type").strip(), "root": "no (adb shell uid 2000)", "audit apk": audit_apk}
    # the screen stays on for the whole session: steps without a touch for longer than the screen timeout (the clipboard copies, the wait for the
    # end of the self-destruct test mode) would otherwise run on a sleeping phone, where no field has focus, nothing is on screen and a capture is black
    stay_on = d.sh("settings get global stay_on_while_plugged_in").strip()
    run.meta["stay on while plugged in, before"] = stay_on
    d.sh("settings put global stay_on_while_plugged_in 7")
    try:
        flow(run, audit_apk, release_apk)
    finally:
        d.sh("settings put global stay_on_while_plugged_in %s" % (stay_on if stay_on.isdigit() else "0"))
        d.sh("settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0")
        d.sh("ime set " + (run.meta.get("default keyboard before") or ORIGINAL_IME))
        d.sh("pm uninstall %s; pm uninstall io.github.nazandrake.taen.audit" % HELPER)
        if run.log_proc:
            run.stop_logcat()
        d.sleep_screen()
        run.meta["default keyboard after the audit"] = d.sh("settings get secure default_input_method").strip()
    return run.results, run.meta


def flow(run, audit_apk, release_apk):
    d = run.dev
    d.wake()
    orig = d.sh("settings get secure default_input_method").strip()
    run.meta["default keyboard before"] = orig
    # ---------------------------------------------------------------- helper
    helper_apk = helper_build.build(run.root, run.work)
    d.sh("pm uninstall %s" % HELPER)
    run.install(helper_apk, HELPER, uninstall_first=False)
    d.sh("appops set %s SYSTEM_ALERT_WINDOW allow" % HELPER)
    run.meta["helper"] = "taen.audit.helper (no network permission), built from tools/leak_audit/helper"
    run.add(fl.permissions_control(run))
    # ---------------------------------------------------------------- audit twin
    if not os.path.exists(audit_apk):
        run.add(Result("D0", "Audit APK", GAP, "missing %s (run ./gradlew :app:assembleAudit)" % audit_apk))
        return
    run.pkg = "io.github.nazandrake.taen.audit"
    run.install(audit_apk, run.pkg)
    run.enable_ime(run.pkg)
    run.start_logcat()
    run.meta["audit apk sha256"] = __import__("static_run").sha256(audit_apk)
    base1 = netstats.read(d)
    run.step("D15", "Default languages and language key", lambda: fl.language_key_check(run))
    run.step("D0c", "Setup: trigger word", run.setup_trigger_word)

    session = {}

    def typing():
        notes, strip_normal, strip_pw = run.session()
        session.update(notes=notes, strip_normal=strip_normal, strip_pw=strip_pw)
        learned = any(CANARIES["latin"] in t for t in strip_normal)
        pw_suggested = any(CANARIES["latin_password"] in t for t in strip_pw)
        problems = []
        if not learned:
            problems.append("the learned word was not suggested in a normal field (no positive control for incognito)")
        if pw_suggested:
            problems.append("the word typed in a password / no-learning field was learned and suggested")
        return verdict("D11", "Typing session ran (EN, BG, glide, emoji strip) and incognito holds: normal-field word learned (control), password and no-learning field words not", problems,
                       "learned word suggested in a normal field; the password-field word was not suggested", notes + ["strip normal: %s" % strip_normal[-5:], "strip password-word prefix: %s" % strip_pw[-5:]])

    run.step("D11", "Typing session", typing)
    clip = {}

    def clips():
        found, pasted = run.clipboard_session()
        sysclip = run.read_system_clip()
        clip.update(found=found, pasted=pasted, sysclip=sysclip)
        return clip_results(run, found, pasted, sysclip, None)

    run.step("D6", "Clipboard", clips)
    run.step("D6b", "Clipboard at service start", lambda: fl.clipboard_restart_check(run))
    export = {}

    def exporting():
        export["data"] = export_backup(run)
        r, hits = export_check(export["data"])
        return [r, export_control(export["data"])]

    run.step("D10", "Export", exporting)

    def lock_unlock():
        d.sleep_screen()
        time.sleep(4)
        d.wake()
        time.sleep(1.5)
        run.field("normal")
        d.type_keys("en", CANARIES["latin"][:4])
        strip = run.strip_texts()
        d.sh("input keyevent 67 67 67 67")
        ok = any(CANARIES["latin"] in t for t in strip)
        return verdict("D12", "Screen lock and unlock: the keyboard closes and reopens its encrypted stores (learned word suggested again after unlock)", [] if ok else ["learned word not suggested after screen off/on: %s" % strip[-5:]],
                       "after screen off and on the learned word is suggested again", ["strip: %s" % strip[-5:]])

    run.step("D12", "Lock/unlock", lock_unlock)
    voice = {}

    def voice_session():
        model = os.environ.get("TAEN_VOICE_MODEL_DIR") or os.path.join(run.root, "dist", "voice-model")
        samples = os.environ.get("TAEN_VOICE_SAMPLES_DIR") or os.path.expanduser("~/.cache/taen-voice-samples")
        results = dv.run_voice(run, model, samples)
        ref = os.path.join(samples, "en", "refs.tsv")
        if os.path.isfile(ref):
            with open(ref, encoding="utf-8") as f:
                voice["words"] = dv.distinct_words([l.split("\t", 1)[1] for l in f.read().splitlines() if l.startswith("00\t")][0])
        heard = getattr(run, "voice_heard", "")
        voice["words"] = sorted(set(voice.get("words", [])) | set(w for w in dv.words(heard) if len(w) >= 5))
        return results

    run.step("V", "Voice typing session", voice_session)
    run.step("P1", "Save the canary profile (1.1.0)", lambda: save_canary_profile(run))
    run.step("D3a", "Data at rest after the session", lambda: _scan_step(run, session, "D3", "No plaintext at rest: no canary in either data directory or the external app directories after the session"))
    run.step("D3c", "planted plaintext", lambda: fl.data_control(run))
    # self-destruct: test mode, then the real trigger after it has ended
    run.step("D13a", "Self-destruct test mode", lambda: _rehearsal(run, session))
    run.step("D8", "Permissions granted", lambda: _perm_step(run))
    run.step("D9", "dumpsys canaries", lambda: fl.dump_check(run))
    run.step("D4", "Backup (audit twin)", lambda: [fl.backup_check(run, run.pkg, True), fl.backup_control(run)])
    run.step("D5", "Screen capture", lambda: [fl.screen_check(run), fl.screen_control(run)])
    run.step("D7", "Accessibility and overlay warnings", lambda: fl.a11y_and_overlay(run))
    if getattr(run.args, "fast", False):
        run.add(Result("D13b", "Self-destruct real wipe", GAP, "skipped (--fast): the 15 minute test mode was not waited for"))
    else:
        run.step("D13b", "Real wipe by the trigger word", lambda: _real_wipe(run, session))
    # network, logs
    final1 = netstats.read(d)
    run.step("D1", "Network (audit twin)", lambda: [fl.network_check(run, base1, final1, [run.pkg])])
    run.step("T-D1", "network control", lambda: fl.network_control(run))
    log = run.stop_logcat()
    run.step("D2", "logcat (audit twin)", lambda: _log_steps(run, log, voice.get("words", ())))
    # ---------------------------------------------------------------- release build: reduced session
    run.meta["release apk"] = release_apk
    run.meta["release apk sha256"] = __import__("static_run").sha256(release_apk)
    release_session(run, release_apk)


def _scan_step(run, session, ident, title):
    r, digests = fl.check_data_at_rest(run, ident, title)
    session["digests_before_wipe"] = digests
    return r


def _perm_step(run):
    req, granted = fl.permissions_check(run)
    r = sc_perm(req, granted, run.pkg)
    return r


def sc_perm(req, granted, pkg):
    import static_checks as sc
    base = pkg
    declared = [p for p in req if p.startswith("android.permission.") or p.startswith(base)]
    res = sc.check_permissions(sorted(set(declared)), base)
    return Result("D8", "Runtime permissions of the installed package equal the allowlist (dumpsys package)", res.status, res.summary, res.details + ["granted install permissions: %s" % granted])


def _log_steps(run, log, spoken=()):
    r, _ = fl.log_check(run, log)
    out = [r, fl.log_control(log)]
    if spoken:
        out += [dv.log_check(run, log, list(spoken)), dv.log_control(run, list(spoken))]
    return out


def _rehearsal(run, session):
    d = run.dev
    run.start_test_mode()
    toast = strings_toast(run)
    before = fl.data_scan(run, "pre-rehearsal")[3]
    # toasts are invisible to uiautomator: the helper's accessibility service receives the toast event
    d.sh("settings put secure enabled_accessibility_services %s/taen.audit.ReadService; settings put secure accessibility_enabled 1" % HELPER)
    time.sleep(3)
    run.field("normal")
    mark = len(fl.helper_log(run))
    d.sh("input text '%s'" % CANARIES["trigger_word"])
    time.sleep(1.0)
    d.tap(990, 1822)  # Enter on the soft keyboard
    time.sleep(5)
    seen = fl.toasts_since(run, mark, toast)
    d.sh("settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0")
    after = fl.data_scan(run, "post-rehearsal")[3]
    enc_before = {k for k in before if k.endswith(".enc")}
    enc_after = {k for k in after if k.endswith(".enc")}
    problems = []
    if not seen:
        problems.append("no 'Trigger recognised (test)' toast after the trigger word and Enter")
    if not enc_before or enc_before - enc_after:
        problems.append("encrypted stores missing after the rehearsal: %s" % sorted(enc_before - enc_after)[:3])
    return verdict("D13a", "Self-destruct test mode: the trigger word is recognised (toast) and nothing is deleted", problems,
                   "toast %r shown; all %d encrypted store files still present" % (seen[0].split("text=")[-1][:40] if seen else None, len(enc_before)), ["before: %d enc files, after: %d" % (len(enc_before), len(enc_after))])


def strings_toast(run):
    return fl.strings_value(run.root, "self_destruct_rehearsal_toast")


def _real_wipe(run, session):
    d = run.dev
    wait = (run.test_mode_started or 0) + TEST_MODE_SECONDS + 20 - time.time()
    if wait > 0:
        print("waiting %d s for the test mode to end" % wait, flush=True)
        time.sleep(wait)
    before = session.get("digests_before_wipe", {})
    d.wake()
    run.field("normal")
    d.sh("input text '%s'" % CANARIES["trigger_word"])
    time.sleep(0.5)
    # positive control: the word is in the field, so the Enter below is a real trigger (a word that never arrived proves nothing about the wipe)
    typed = run.field_text()
    if CANARIES["trigger_word"] not in (typed or ""):
        raise RuntimeError("the trigger word did not reach the field (field text %r): the wipe was not triggered" % typed)
    time.sleep(3.0)  # the first tap after a uiautomator dump can be lost (see language_key_check)
    t0 = time.time()
    d.tap(990, 1822)
    time.sleep(8)
    hits, problems, rows, after = fl.data_scan(run, "post-wipe", expect_stores=False)
    kept = [k for k, v in after.items() if k in before and before[k] == v and k.endswith(".enc")]
    enc_before = [k for k in before if k.endswith(".enc")]
    problems = [p for p in problems]
    problems += ["encrypted store survived the wipe unchanged: %s" % k for k in kept]
    if not enc_before:
        problems.append("no encrypted store existed before the wipe (nothing proved)")
    run.save("data-scan-post-wipe.txt", "\n".join(rows + problems))
    return verdict("D13b", "Self-destruct: the trigger word (test mode over) deletes every encrypted store; no canary and no old ciphertext file remains", problems,
                   "%d encrypted store files before, none of them left unchanged after; files now: %d; no canary" % (len(enc_before), len(rows)),
                   ["after the wipe: " + r for r in rows[:25]])


def release_session(run, release_apk):
    d = run.dev
    pkg = "io.github.nazandrake.taen"
    run.pkg = pkg
    run.install(release_apk, pkg)
    run.enable_ime(pkg)
    run.start_logcat()
    base2 = netstats.read(d)

    def type_en():
        run.field("normal")
        d.type_keys("en", CANARIES["latin"] + " " + CANARIES["latin"] + " ")
        text = run.field_text()
        if CANARIES["latin"] not in (text or ""):
            raise RuntimeError("typing in the release build failed: %r" % text)
        run.field("normal", copy=CANARIES["clip_normal"])
        time.sleep(2)
        run.field("password", copy=CANARIES["clip_password"])
        time.sleep(2)
        return Result("D14", "Release build (signed by the owner, non-debuggable) installed, selected and typed in", PASS, "typed English text with the canary, copied a clip, in a normal and a password field")

    run.step("D14", "Release typing", type_en)
    run.step("D8r", "Permissions (release)", lambda: _release_perm(run))
    run.step("D9r", "dumpsys canaries (release)", lambda: _release_label(fl.dump_check(run), "D9r"))
    run.step("D4r", "Backup (release)", lambda: _release_label(fl.backup_check(run, pkg, False), "D4r"))
    run.step("D5r", "Screen capture (release)", lambda: _release_label(fl.screen_check(run), "D5r"))
    run.add(Result("D3r", "Data at rest (release)", GAP, "not run: the release build is not debuggable, so run-as cannot read its data directory without root; the audit twin carries the same code (D3, D13b)"))
    final2 = netstats.read(d)
    run.step("D1r", "Network (release)", lambda: [fl.network_check(run, base2, final2, [pkg], label="D1r")])
    log = run.stop_logcat()
    run.step("D2r", "logcat (release)", lambda: [fl.log_check(run, log, "D2r")[0]])


def _release_label(r, ident):
    """The release build's row of a check shared with the audit twin: its own id and title, so the report keeps both rows."""
    r.ident = ident
    r.title += " (release)"
    return r


def _release_perm(run):
    req, granted = fl.permissions_check(run)
    r = sc_perm(req, granted, run.pkg)
    r.ident = "D8r"
    r.title = "Runtime permissions of the release package equal the allowlist"
    return r
