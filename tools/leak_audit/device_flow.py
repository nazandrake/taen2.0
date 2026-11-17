"""The order of the device audit: setup, typing session, checks, planted-failure proofs, real wipe, release-build session, cleanup."""
import os
import re
import subprocess
import time

import apk as apklib
import device_checks as dc
import device_run as dr
import helper_build
import netstats
import selftest
import static_checks as sc
from device_run import CANARIES, HELPER, HELPER_ACTIVITY, ORIGINAL_IME, Run, version_name
from model import FAIL, GAP, INFO, PASS, Result, verdict

ALL = list(CANARIES.values())


def strings_value(root, name):
    with open(os.path.join(root, "app", "src", "main", "res", "values", "strings.xml")) as f:
        m = re.search(r'<string name="%s">(.*?)</string>' % name, f.read())
    return m.group(1)


# ------------------------------------------------------------------ data at rest (D3, D8)

def data_scan(run, label, expect_stores=True):
    """Scans both data directories of the audit build and the external directories. Returns (hits, problems, rows, sizes {path: sha})."""
    import hashlib
    scan = run.scan
    by_text = {c: 0 for c in ALL}
    problems, rows, digests = [], [], {}
    for lab, base in (("user", "/data/user/0/{pkg}"), ("user_de", "/data/user_de/0/{pkg}")):
        data = scan.fetch(run.dev.serial, run.pkg, base)
        r, h, probs = scan.scan_tar(data, ALL, forbid=list(scan.LEGACY_FORBID))
        for path, size, counts in r:
            rows.append("%s/%s %d bytes" % (lab, path, size))
        for c, n in h.items():
            by_text[c] += n
        problems += ["%s: %s" % (lab, p) for p in probs]
        import io
        import tarfile
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            for m in tar:
                if m.isfile():
                    digests["%s/%s" % (lab, m.name.lstrip("./"))] = hashlib.sha256(tar.extractfile(m).read()).hexdigest()
    problems += run.scan.scan_external(run.dev.serial, run.pkg)
    names = " ".join(rows)
    if expect_stores and not re.search(r"no_backup/taen/\S*\.enc", names):
        problems.append("no encrypted store file under no_backup/taen (the app stored nothing? the check would pass for the wrong reason)")
    hits = {name: by_text[text] for name, text in CANARIES.items()}
    plain = [k for k, v in hits.items() if v]
    problems += ["plaintext canary %s (%s) found %d time(s) in the app's data" % (k, CANARIES[k], hits[k]) for k in plain]
    return hits, problems, rows, digests


def check_data_at_rest(run, ident, title, expect_stores=True):
    hits, problems, rows, digests = data_scan(run, ident, expect_stores)
    run.save("data-scan-%s.txt" % ident.lower(), "\n".join(rows + ["problems:"] + problems))
    return verdict(ident, title, problems, "no canary in any encoding (UTF-8, UTF-16 LE/BE, Windows-1251, dictionary trie) in %d files of both data directories; no file in the external app directories; encrypted stores present" % len(rows), rows[:30]), digests


# ------------------------------------------------------------------ network

def network_check(run, base_all, final_all, pkgs, label="D1"):
    """The kernel's per-UID counters did not move for the Taen UID(s); the shell UID (curl, run as positive control) moved."""
    d = run.dev
    problems, details = [], []
    for pkg in pkgs:
        uid = netstats.uid_of(d, pkg)
        b, f = base_all.get(uid, (0, 0)), final_all.get(uid, (0, 0))
        details.append("%s uid %s: rx/tx bytes before %s after %s" % (pkg, uid, b, f))
        if f != (0, 0) or b != (0, 0):
            problems.append("%s (uid %s) has network traffic: before %s after %s" % (pkg, uid, b, f))
        status = ""
        for pid in d.sh("pidof %s" % pkg).split():
            status = d.sh("cat /proc/%s/status" % pid)
            problems += ["%s pid %s: %s" % (pkg, pid, p) for p in dc.check_no_inet_group(status)]
            details.append("%s pid %s Groups: %s" % (pkg, pid, sorted(dc.gids_of(status) or [])))
    rx = [r for r in d.sh("cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6").splitlines()]
    for pkg in pkgs:
        rows = dc.socket_rows_for_uid("\n".join(rx), netstats.uid_of(d, pkg))
        if rows:
            problems.append("%s owns %d socket row(s) in /proc/net/*" % (pkg, len(rows)))
    details.append("/proc/net/{tcp,tcp6,udp,udp6}: %d rows readable, none owned by the Taen uid(s)" % len(rx))
    details.append("no root: tcpdump and iptables counters are not available; per-UID platform counters, process groups and /proc/net rows are the evidence (positive control below)")
    return verdict(label, "No network: per-UID traffic zero across the session, no inet group, no socket rows", problems,
                   "traffic 0 bytes for %s over the whole session; the processes lack group 3003 (inet), so socket() is refused by the kernel" % ", ".join(pkgs), details)


def network_control(run):
    """The same readers on the shell UID after a real download must see the traffic and the inet group: shows the checks can fail."""
    d = run.dev
    before = netstats.read(d)
    out = d.sh("curl -s -o /dev/null -w '%{size_download}' http://neverssl.com/")
    after = netstats.read(d)
    shell_before, shell_after = before.get(2000, (0, 0)), after.get(2000, (0, 0))
    moved = shell_after != shell_before
    grp = dc.check_no_inet_group(d.sh("cat /proc/self/status"))
    ok = moved and bool(grp)
    return Result("T-D1", "D1 can fail: the same counters see the shell's own download, the same group test flags the shell", PASS if ok else FAIL,
                  "after a curl download the shell uid 2000 counters moved %s -> %s; group test on the shell: %s" % (shell_before, shell_after, "flagged (holds 3003)" if grp else "NOT FLAGGED"))


# ------------------------------------------------------------------ logs and dumps

def log_check(run, text, label="D2"):
    hits, scanned = dc.scan_log(text, CANARIES)
    activity = len([1 for l in text.splitlines() if run.pkg in l])
    problems = ["canary %s (%s) in logcat: %s" % (k, CANARIES[k], v[0]) for k, v in hits.items()]
    if activity == 0:
        problems.append("logcat holds no line that mentions %s: the capture may have missed the session" % run.pkg)
    return verdict(label, "logcat has no canary (typed text, clipboard, passphrase, trigger word)", problems,
                   "%d log lines scanned (all buffers, streamed through the whole session; %d mention the package), 0 canary hits" % (scanned, activity)), text


def log_control(text):
    hits, _ = dc.scan_log(text, CANARIES, skip_tags=())
    ok = "latin" in hits and "cyrillic" in hits
    return Result("T-D2", "D2 can fail: the planted control line (tag AUDIT-CONTROL, both canaries) is found by the same scanner", PASS if ok else FAIL,
                  "control found: %s" % sorted(hits))


def dump_check(run):
    d = run.dev
    outs = {}
    outs["service"] = d.sh("dumpsys activity service %s/helium314.keyboard.latin.LatinIME" % run.pkg, timeout=60)
    outs["package"] = d.sh("dumpsys package %s" % run.pkg, timeout=60)
    outs["input_method"] = d.sh("dumpsys input_method", timeout=60)
    outs["clipboard"] = d.sh("dumpsys clipboard", timeout=60)
    outs["meminfo"] = d.sh("dumpsys meminfo %s" % run.pkg, timeout=60)
    problems = []
    for k, text in outs.items():
        h = dc.scan_bytes(text.encode("utf-8", "replace"), CANARIES)
        for c, n in h.items():
            if n and not (k == "clipboard" and c.startswith("clip_password")):  # the system clipboard holds the last clip by design
                problems.append("canary %s in dumpsys %s (%d)" % (c, k, n))
    clip_has = dc.scan_bytes(outs["clipboard"].encode(), CANARIES)
    run.save("dumpsys-service.txt", outs["service"])
    return verdict("D9", "dumpsys of the keyboard service, package, input method and meminfo holds no canary", problems,
                   "5 dumps (%s) scanned, no canary; (the system clipboard dump %s the last system clip, which is the phone's own state, not Taen's)" % (
                       ", ".join("%s %d B" % (k, len(v)) for k, v in outs.items()), "shows" if any(clip_has.values()) else "does not show"))


def permissions_check(run, pkg=None):
    d = run.dev
    pkg = pkg or run.pkg
    text = d.sh("dumpsys package %s" % pkg, timeout=60)
    section = text.split("requested permissions:")[-1].split("install permissions:")[0] if "requested permissions:" in text else ""
    requested = sorted(set(re.findall(r"^\s{4,}([\w.]+)(?::.*)?$", section, re.M)))
    inst = text.split("install permissions:")[-1].split("User 0:")[0] if "install permissions:" in text else ""
    granted = sorted(set(re.findall(r"^\s+([\w.]+): granted=true", inst, re.M)))
    return requested, granted


# ------------------------------------------------------------------ backup, screen

def backup_archive(run, pkg, name):
    """`adb backup` of one package: (members, raw size). The phone asks for a confirmation, which the script taps."""
    out = os.path.join(run.work, name)
    if os.path.exists(out):
        os.remove(out)
    p = subprocess.Popen(run.dev.adb_cmd + ["backup", "-f", out, "-noapk", pkg], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(4)
    for label in ("Back up my data", "BACK UP MY DATA"):
        n = run.dev.find(label)
        if n:
            run.dev.tap(n["cx"], n["cy"])
            break
    try:
        p.communicate(timeout=90)
    except subprocess.TimeoutExpired:
        p.kill()
        raise RuntimeError("adb backup did not finish")
    with open(out, "rb") as f:
        raw = f.read()
    os.remove(out)
    return dc.backup_members(raw), len(raw)


def backup_check(run, pkg, debuggable):
    d = run.dev
    flags = d.sh("dumpsys package %s | grep -m1 pkgFlags" % pkg)
    bm = d.sh("bmgr backupnow %s" % pkg)
    members, size = backup_archive(run, pkg, "t.ab")
    leaks = dc.backup_leaks(members, pkg)
    problems = []
    if "ALLOW_BACKUP" in flags:
        problems.append("package flags contain ALLOW_BACKUP: " + flags.strip())
    if "not allowed" not in bm.lower() and "not eligible" not in bm.lower():
        problems.append("bmgr backupnow did not refuse: " + " ".join(bm.split())[:160])
    if leaks and not debuggable:
        problems.append("adb backup archive has app data: %s" % ", ".join(leaks[:5]))
    details = ["flags: " + flags.strip(), "bmgr backupnow: " + " ".join(bm.split())[:200], "adb backup (%d bytes) members: %s" % (size, members or "none")]
    if debuggable and members:
        details.append("note: Android archives a DEBUGGABLE package for adb backup even with allowBackup=false, so this twin is not the verdict (the release build is, D4r). Files in its archive: %s" % ", ".join(members[:8]))
        stores = [m for m in members if "no_backup" in m or ".enc" in m]
        details.append("files from the encrypted stores in the archive: %s" % (stores or "none"))
        if stores:
            problems.append("adb backup of the debuggable twin contains encrypted store files: %s" % stores[:3])
    return verdict("D4", "Backup: package not backup-eligible (bmgr refuses) and adb backup yields no user data", problems,
                   "ALLOW_BACKUP absent, bmgr refuses (Backup is not allowed), adb backup archive: %s" % ("empty" if not members else "%d public file(s), no user data" % len(members)), details)


def backup_control(run):
    d = run.dev
    allowed = None
    for p in ("com.android.messaging", "com.android.contacts", "com.android.calculator2", "com.android.gallery3d", "org.lineageos.recorder", "com.android.deskclock"):
        flags = d.sh("dumpsys package %s | grep -m1 pkgFlags" % p)
        if "ALLOW_BACKUP" in flags:
            allowed = (p, flags.strip())
            break
    audit_members, size = backup_archive(run, run.pkg, "c.ab") if run.pkg.endswith(".audit") else ([], 0)
    strict = dc.backup_leaks(audit_members, run.pkg, allowed_public=("/_manifest",))
    ok = allowed is not None and bool(strict)
    return Result("T-D4", "D4 can fail: a package with ALLOW_BACKUP is flagged, and a debuggable build's adb backup shows files under the strict rule", PASS if ok else FAIL,
                  "ALLOW_BACKUP package: %s; strict rule on the audit build's archive: %d file(s)" % (allowed and allowed[0], len(strict)), [allowed and allowed[1] or "", "archive: %s" % audit_members[:4]])


def screen_check(run, with_settings=True):
    d = run.dev
    rows, problems = [], []
    run.field("normal")
    if not d.awake():  # a sleeping screen captures black whatever the app does: that would be a PASS without evidence
        raise RuntimeError("the screen is off: no capture can show whether the keyboard window is protected")
    shot = d.screencap_raw()
    st = dc.capture_state(shot)
    rows.append("keyboard shown in a text field: screencap %s (non-black %.3f)" % (st[0], st[1]))
    if not dc.capture_blocked(st):
        problems.append("screenshot with the keyboard on screen shows content (non-black %.3f)" % st[1])
    # screen recording of the same screen
    rec = record_state(run)
    rows.append("keyboard shown: screenrecord %s" % rec[0])
    if rec[0] == "content":
        problems.append("screen recording with the keyboard on screen shows content")
    if with_settings:
        run.settings_home()
        st2 = dc.capture_state(d.screencap_raw())
        rows.append("settings screen: screencap %s (non-black %.3f)" % (st2[0], st2[1]))
        if not dc.capture_blocked(st2):
            problems.append("screenshot of the settings screen shows content")
    return verdict("D5", "Screen capture: screenshot and screen recording are refused or black with the keyboard and the settings screen on screen", problems,
                   "keyboard window: %s; recording: %s%s" % (st[0], rec[0], "; settings: %s" % st2[0] if with_settings else ""), rows)


def record_state(run):
    d = run.dev
    d.sh("rm -f /sdcard/audit-rec.mp4; screenrecord --time-limit 3 /sdcard/audit-rec.mp4", timeout=30)
    local = os.path.join(run.work, "audit-rec.mp4")
    d.adb("pull", "/sdcard/audit-rec.mp4", local)
    d.sh("rm -f /sdcard/audit-rec.mp4")
    if not os.path.exists(local) or os.path.getsize(local) < 1000:
        return ("refused", 0.0)
    out = subprocess.run(["ffmpeg", "-v", "quiet", "-i", local, "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-", "-f", "null", "-"], capture_output=True, text=True)
    vals = [float(x) for x in re.findall(r"YAVG=([\d.]+)", out.stdout)]
    os.remove(local)
    if not vals:
        return ("refused", 0.0)
    avg = sum(vals) / len(vals)
    return ("black" if avg < 20 else "content", avg)


def screen_control(run):
    d = run.dev
    run.field("normal", keyboard=False)
    st = dc.capture_state(d.screencap_raw())
    rec = record_state(run)
    ok = not dc.capture_blocked(st) and rec[0] == "content"
    return Result("T-D5", "D5 can fail: a plain screen without the keyboard (helper, white) is captured and recorded with content", PASS if ok else FAIL,
                  "screencap %s (non-black %.3f), screenrecord %s (mean luma %.1f)" % (st[0], st[1], rec[0], rec[1]))


# ------------------------------------------------------------------ warnings

def helper_log(run):
    return run.dev.adb("logcat", "-d", "-s", "AuditHelper:I").stdout.decode(errors="replace").splitlines()


def toasts_since(run, mark, needle):
    """Toast texts (the helper's accessibility service receives the toast events) logged after line `mark` that contain `needle`."""
    return [l for l in helper_log(run)[mark:] if "class=android.widget.Toast" in l and needle in l]


def a11y_and_overlay(run):
    """UiAutomation (uiautomator dumps) cannot see toasts, so the helper's own accessibility service reports them; its events are the evidence."""
    d = run.dev
    out = []
    a11y_needle = strings_value(run.root, "privacy_a11y_warning").split(":")[0]  # "An accessibility service can read the screen and what you type"
    overlay_needle = strings_value(run.root, "privacy_overlay_warning")[:30]
    some_prefix = strings_value(run.root, "privacy_status_a11y_some").split("%")[0]
    none_line = strings_value(run.root, "privacy_status_a11y_none")

    def privacy_status():
        run.settings_home()
        run.open_section("Privacy")
        time.sleep(1)
        run.scroll_to("Status", tries=6)
        return d.texts()

    d.sh("settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0")
    time.sleep(2)
    base_status = privacy_status()
    base_ok = any(none_line in t for t in base_status) and not any(some_prefix in t for t in base_status)
    out.append(Result("T-D7", "D7 can fail: with no accessibility service on the status line says none and does not list a service", PASS if base_ok else FAIL,
                      "status none line shown: %s; service line shown: %s" % (any(none_line in t for t in base_status), any(some_prefix in t for t in base_status))))
    # enable the helper's reading service; the keyboard warns when input starts in a field
    d.sh("settings put secure enabled_accessibility_services %s/taen.audit.ReadService; settings put secure accessibility_enabled 1" % HELPER)
    time.sleep(3)
    mark = len(helper_log(run))
    run.field("normal")
    time.sleep(5)
    seen = toasts_since(run, mark, a11y_needle)
    # control: a second start of input with the same service does not warn again (the warning is for a change of the set)
    mark2 = len(helper_log(run))
    run.field("normal")
    time.sleep(5)
    again = toasts_since(run, mark2, a11y_needle)
    status = privacy_status()
    status_some = [t for t in status if some_prefix in t]
    problems = []
    if not seen:
        problems.append("no warning toast ('%s') when input started with an accessibility service that reads the screen on" % a11y_needle)
    if again:
        problems.append("the warning repeated on the second start of input in a normal field (control)")
    if not status_some:
        problems.append("the Privacy status line did not list the service")
    out.append(verdict("D7", "Accessibility warning: a service that can read the screen triggers the toast once and the status line; no toast without the change", problems,
                       "toast: %s; no repeat on the second start; status: %r" % (seen[0].split("text=")[-1][:90] if seen else None, status_some[0][:80] if status_some else None),
                       ["toast: %s" % [l[-130:] for l in seen[:1]], "status: %s" % status_some[:1]]))
    # what that service could read (information, a limitation the keyboard can only warn about)
    mark3 = len(helper_log(run))
    run.field("normal")
    d.type_keys("en", "abc ")
    run.field("password")
    d.type_keys("en", "xyz ")
    ev = [l for l in helper_log(run)[mark3:] if "a11y" in l and ("abc" in l or "xyz" in l or "\u2022" in l)]
    out.append(Result("D7i", "What an enabled reading service sees of typing (limitation, PRIVACY.md 'What a keyboard cannot protect against')", INFO,
                      "%d event line(s) with typed text from the helper's log" % len(ev), [l[-150:] for l in ev[:8]]))
    # overlay: first without an overlay (control), then with the helper's touch-through window over the keyboard
    d.sh("appops set %s SYSTEM_ALERT_WINDOW allow" % HELPER)
    run.field("normal")
    time.sleep(3)  # the first tap right after a uiautomator dump can be lost: wait, then tap three times
    mark4 = len(helper_log(run))
    for _ in range(3):
        d.tap(900, 1660)
        time.sleep(1.5)
    time.sleep(3)
    none = toasts_since(run, mark4, overlay_needle)
    run.field("normal", overlay=True)
    time.sleep(1)
    time.sleep(3)
    mark5 = len(helper_log(run))
    for _ in range(3):
        d.tap(900, 1660)  # outside the overlay's corner
        time.sleep(1.5)
    time.sleep(3)
    seen2 = toasts_since(run, mark5, overlay_needle)
    added = any("overlay added" in l for l in helper_log(run))
    problems = []
    if none:
        problems.append("overlay warning without any overlay (control)")
    if not added:
        problems.append("the helper's overlay window was not added (appop?)")
    if not seen2:
        problems.append("no overlay warning toast after a tap on the covered keyboard")
    out.append(verdict("D7o", "Overlay warning: a tap on a keyboard covered by another window shows the warning, a tap without an overlay does not", problems,
                       "overlay over a keyboard corner (300x150 px): toast %r; without the overlay: no toast" % (seen2[0].split("text=")[-1][:70] if seen2 else None),
                       ["with overlay: %s" % [l[-120:] for l in seen2[:1]], "control (no overlay): %s" % none[:1]]))
    d.sh("settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0")
    d.sh("am force-stop %s" % HELPER)
    return out


def clipboard_restart_check(run):
    """D6b: the last copy on the phone was made in a password field (D6). When the keyboard service starts again (process restart, boot), it must
    not take that clip into its history: at start there is no field context to tell. Found on 2026-11-12: ClipboardHistoryManager.onCreate
    calls fetchPrimaryClip(), which stores the clip that is on the system clipboard at that moment."""
    d = run.dev
    d.sh("am force-stop %s" % run.pkg)
    time.sleep(2)
    d.sh("ime set %s/helium314.keyboard.latin.LatinIME" % run.pkg)
    run.field("normal")
    time.sleep(3)
    entries = run.clipboard_entries()
    opened = bool(d.find("Close clipboard history", windows=True))  # positive control: an empty list is only evidence when the history is on screen
    run.close_clipboard()
    if not opened:
        raise RuntimeError("the clipboard history did not open after the service restart: %s" % "; ".join(d.texts(True)[:8]))
    stored = any(CANARIES["clip_password"] in t for t in entries)
    problems = ["the clip copied in a password field was stored in the history when the keyboard service restarted (entries: %s)" % entries] if stored else []
    return verdict("D6b", "Clipboard at service start: a clip left on the system clipboard from a password field is not taken into the history", problems,
                   "the history after a service restart holds %s" % entries, ["entries: %s" % entries])


def pinned_forever_setup(run):
    """D6p: retention Forever (the last choice of the setting) and one copied entry pinned with its pin button; the real wipe (D13b) must remove it."""
    d = run.dev
    run.settings_home()
    run.scroll_to("Preferences")
    d.tap_text("Preferences", exact=True)
    time.sleep(1.5)
    run.scroll_to("History retention time")
    d.tap_text("History retention time", exact=True)
    time.sleep(1.5)
    choices = [t for t in d.texts() if t not in ("History retention time", "Default", "Cancel")]
    d.tap_text("Forever", exact=True)
    time.sleep(1.5)
    shown = [t for t in d.texts() if t == "Forever"]
    run.field("normal", copy=CANARIES["clip_pinned"], delay=4000)
    time.sleep(6)
    run.field("normal")
    run.clipboard_entries()
    entry = d.find(CANARIES["clip_pinned"], windows=True)
    pins = [n for n in d.nodes(True) if n["desc"] == "Pin" and n["pkg"].startswith(run.pkg)]
    if entry and pins:  # the pin button of the row that holds the entry
        row = min(pins, key=lambda n: abs(n["cy"] - entry["cy"]))
        d.tap(row["cx"], row["cy"])
        time.sleep(1.5)
    entry = d.find(CANARIES["clip_pinned"], windows=True)
    pinned = bool(entry) and any(n["desc"] == "Unpin" and abs(n["cy"] - entry["cy"]) < 80 for n in d.nodes(True))
    run.close_clipboard()
    problems = []
    if choices[-1:] != ["Forever"]:
        problems.append("Forever is not the last retention choice: %s" % choices)
    if not shown:
        problems.append("the setting does not show Forever after choosing it")
    if not pinned:
        problems.append("the copied entry could not be pinned with its pin button (entry %s, pin buttons %d)" % (bool(entry), len(pins)))
    return verdict("D6p", "Clipboard: retention Forever and a pinned entry (set up for the real wipe, D13b)", problems,
                   "retention Forever chosen from %s; the copied entry is pinned (its button now says Unpin)" % choices, ["choices: %s" % choices])


def language_key_check(run):
    """D15: a new install has English and Bulgarian enabled and a language key on the main layout; one tap switches
    EN -> BG (phonetic) and the next tap back. Evidence: the character a letter key types after each tap."""
    d = run.dev
    items = run.check_default_languages()
    run.field("normal")
    x = 324  # centre of the "Switch language" key in the bottom row (left of the space bar); measured from the key's accessibility node (bounds 270-379) on the reference phone
    key = {"desc": "Switch language (fixed position, behaviour checked below)", "bounds": (270, 1822, 379, 1822)}

    time.sleep(3.0)  # let the uiautomator connection of the last dump go: the first taps after a dump can be lost
    for phase in range(4):  # 3 letter taps, then one tap on the language key; no dump in between (it can swallow the next tap)
        for _ in range(2):  # the first switch to Bulgarian on a new install loads the dictionary: taps are lost for several seconds, hence the long wait below
            d.tap(54, 1335)  # first letter key of the top row: q in English, я in Bulgarian phonetic
            time.sleep(1.8)
        if phase < 3:
            d.tap(x, 1822)
            time.sleep(14.0)
    text = run.field_text() or ""
    import itertools
    seq = [k for k, _ in itertools.groupby(text)]  # runs of the same letter, in order
    problems = []
    if len(seq) != 4 or set(seq) != {"q", "я"} or any(a == b for a, b in zip(seq, seq[1:])):
        problems.append("one tap per switch did not alternate English q and Bulgarian я over four phases: runs %s of %r" % (seq, text))
    return verdict("D15", "Language key: shown by default with English and Bulgarian enabled; one tap switches EN <-> BG (phonetic) and back", problems,
                   "picker has %s; one tap on the key at x=%d between four phases; the top-left letter key typed the runs %s" % (", ".join(i.replace("\n", " ") for i in items if "Taen" in i), x, "".join(seq)),
                   ["key: %s" % key["desc"], "typed text: %r" % text])


# ------------------------------------------------------------------ planted plaintext (D3 can fail)

def data_control(run):
    d = run.dev
    try:
        d.sh("run-as %s sh -c 'echo %s > files/audit-plant.txt'" % (run.pkg, CANARIES["latin"]))
        hits, problems, rows, _ = data_scan(run, "plant", expect_stores=False)
    finally:
        d.sh("run-as %s rm -f files/audit-plant.txt" % run.pkg)
    try:
        d.sh("mkdir -p /sdcard/Android/data/%s/files; echo x > /sdcard/Android/data/%s/files/audit-plant.txt" % (run.pkg, run.pkg))
        ext = run.scan.scan_external(run.dev.serial, run.pkg)
    finally:
        d.sh("rm -rf /sdcard/Android/data/%s" % run.pkg)
    ok = hits.get("latin", 0) >= 1 and any("external file present" in e for e in ext)
    return Result("T-D3", "D3 can fail: a planted plaintext canary in the app's files and a planted external file are both found", PASS if ok else FAIL,
                  "planted canary hits: %d; external listing: %s" % (hits.get("latin", 0), ext[:1]))


def permissions_control(run):
    req, granted = permissions_check(run, HELPER)
    r = sc.check_permissions(granted + [p for p in req if p.startswith("android.permission.")], HELPER)
    return Result("T-D8", "D8 can fail: the helper (which asks for SYSTEM_ALERT_WINDOW) does not equal the permission allowlist", PASS if r.status == FAIL else FAIL, r.summary)
