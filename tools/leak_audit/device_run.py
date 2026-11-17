"""The scripted session on the reference phone and the device checks (D1-D12). See BUILDING.md section 9 and PRIVACY.md (leak audit).

Needs: the phone on adb (no root), the audit APK (debuggable twin of the release build: same R8 rules, applicationId .audit), optionally the signed
release APK (non-debuggable: network, log, backup, screenshot and dump checks only, run-as cannot read it). Installs and removes its own helper app
(taen.audit.helper, no network permission). Restores the default keyboard and leaves the phone asleep, whatever happens."""
import glob
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import time
import traceback

import apk as apklib
import device_checks as dc
import helper_build
import netstats
import selftest
import static_checks as sc
from dev import Dev
from model import FAIL, GAP, INFO, PASS, Result, verdict

CANARIES = dc.CANARIES
HELPER = helper_build.PACKAGE
HELPER_ACTIVITY = HELPER + "/taen.audit.FieldActivity"
LAUNCH_FLAGS = "-f 0x10008000"  # NEW_TASK | CLEAR_TASK: a fresh helper task every time
ORIGINAL_IME = "com.android.inputmethod.latin/.LatinIME"


def load_device_scan(root):
    spec = importlib.util.spec_from_file_location("device_scan", os.path.join(root, "tools", "device-scan.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def version_name(root):
    with open(os.path.join(root, "app", "build.gradle.kts")) as f:
        return re.search(r'versionName = "([^"]+)"', f.read()).group(1)


class Run:
    def __init__(self, root, args):
        self.root, self.args = root, args
        self.dev = Dev(args.serial)
        self.out = os.path.join(args.out, "device")
        os.makedirs(self.out, exist_ok=True)
        self.work = os.path.expanduser("~/.cache/taen-audit")
        self.results = []
        self.meta = {}
        self.pkg = None
        self.log_proc = None
        self.log_path = os.path.join(self.out, "logcat-session.txt")
        self.scan = load_device_scan(root)
        self.test_mode_started = None

    # ------------------------------------------------------------ bookkeeping
    def add(self, r):
        self.results.append(r)
        print(r, flush=True)
        return r

    def step(self, ident, title, fn):
        """Runs one check; an exception becomes a GAP (could not run), never a PASS."""
        try:
            out = fn()
            for r in out if isinstance(out, list) else [out]:
                if r is not None:
                    self.add(r)
        except Exception as e:  # noqa: BLE001
            self.add(Result(ident, title, GAP, "could not run: %s: %s" % (type(e).__name__, str(e)[:160]), traceback.format_exc().splitlines()[-6:]))

    def save(self, name, text):
        with open(os.path.join(self.out, name), "w") as f:
            f.write(text)

    # ------------------------------------------------------------ phone helpers
    def settings_home(self):
        d = self.dev
        d.sh("am start %s -n %s/helium314.keyboard.settings.SettingsActivity" % (LAUNCH_FLAGS, self.pkg))
        time.sleep(2.5)
        if d.find("Finished", exact=True):  # first-run wizard
            d.tap_text("Finished", exact=True)
            d.sh("am start %s -n %s/helium314.keyboard.settings.SettingsActivity" % (LAUNCH_FLAGS, self.pkg))
            time.sleep(2.5)
        if d.find("Welcome to Taen", exact=True):  # 1.1.0 first-run guide (shown once after a fresh install): Skip sets its flag
            d.tap_text("Skip", exact=True)
            time.sleep(1)
        if not d.wait_text("Languages & Layouts", 6):
            try:
                self.scroll_to("Languages & Layouts")  # the Everyday section (1.1.0) is above it
            except RuntimeError:
                raise RuntimeError("settings home not shown: " + "; ".join(d.texts()[:8]))

    def open_section(self, name):
        """A section of the settings home (Privacy, Advanced...): since 1.1.0 the Everyday section is above them, so they are below the first screen."""
        self.scroll_to(name, max_y=1780)  # at the end of the list Advanced and About sit at y 1686 and 1842 (measured on the reference phone)
        self.dev.tap_text(name, exact=True)

    def scroll_to(self, text, exact=True, tries=10, up=False, max_y=1600):
        d = self.dev
        for _ in range(tries):
            n = d.find(text, exact=exact)
            if n and 300 < n["cy"] < max_y:
                return n
            d.sh("input swipe 540 %d 540 %d 400" % ((700, 1300) if up else (1300, 700)))
            time.sleep(0.9)
        raise RuntimeError("not found by scrolling: " + text)

    def field(self, focus="normal", **extras):
        d = self.dev
        d.sh("am start %s -n %s --es focus %s%s" % (LAUNCH_FLAGS, HELPER_ACTIVITY, focus, "".join(
            (" --ez %s %s" % (k, str(v).lower())) if isinstance(v, bool) else (" --ei %s %d" % (k, v)) if isinstance(v, int) else (" --es %s '%s'" % (k, v)) for k, v in extras.items())))
        time.sleep(2.5)
        if extras.get("keyboard", True) is not False:
            for _ in range(12):  # the keyboard window is up when the window tree has an input method window
                if 'type="TYPE_INPUT_METHOD"' in d.tree(windows=True):
                    break
                time.sleep(1)
            time.sleep(0.8)
            self.normalize_keyboard()

    def normalize_keyboard(self):
        """The keyboard remembers the clipboard view and the open toolbar across fields: back to the plain letter keys with the suggestion strip."""
        d = self.dev
        self.close_clipboard()
        if d.find("Undo", windows=True, exact=True):  # toolbar open: the arrow closes it and the strip shows suggestions again
            m = d.find("More keys", windows=True)
            if m:
                d.tap(m["cx"], m["cy"])
                time.sleep(0.8)

    def field_text(self, focus="normal"):
        n = self.dev.find("field_" + focus)
        return n["text"] if n else None

    def switch_layout(self, label):
        """Long press on space opens the system input method picker; pick 'label' (language name, then Taen)."""
        d = self.dev
        self.picker_items()
        item = d.find(label + "\nTaen", exact=True)
        if not item:
            raise RuntimeError("no input method item %r: %s" % (label, d.texts()))
        d.tap(item["cx"], item["cy"])
        time.sleep(1.3)

    def strip_texts(self):
        return [t for t in self.dev.texts(True) if t and "notification" not in t and "kB/s" not in t]

    def clipboard_entries(self):
        """Opens the keyboard's clipboard history (toolbar key) and returns the entry texts in the list below the toolbar."""
        d = self.dev
        for _ in range(4):
            if d.find("Close clipboard history", windows=True):
                break
            n = d.find("Clipboard", windows=True)
            if n:
                d.tap(n["cx"], n["cy"])
                time.sleep(1.5)
            else:
                m = d.find("More keys", windows=True)
                if m:
                    d.tap(m["cx"], m["cy"])
                time.sleep(1.0)
        return [x["text"] for x in d.nodes(True) if x["pkg"].startswith(self.pkg) and x["bounds"][1] > 1250 and x["text"]]

    def toolbar_open(self):
        d = self.dev
        if not d.find("Clipboard", windows=True):
            n = d.find("More keys", windows=True)
            if n:
                d.tap(n["cx"], n["cy"])
                time.sleep(1)

    # ------------------------------------------------------------ setup
    def start_logcat(self):
        self.dev.logcat_clear()
        self.log_path = os.path.join(self.out, "logcat-%s.txt" % self.pkg)  # one file per build under test
        self.log_file = open(self.log_path, "wb")
        self.log_proc = subprocess.Popen(self.dev.adb_cmd + ["logcat", "-b", "all", "-v", "threadtime"], stdout=self.log_file, stderr=subprocess.DEVNULL)
        time.sleep(1)
        self.dev.sh("log -t AUDIT-CONTROL 'planted control %s %s'" % (CANARIES["latin"], CANARIES["cyrillic"]))

    def stop_logcat(self):
        time.sleep(1.5)
        if self.log_proc:
            self.log_proc.terminate()
            self.log_proc.wait(timeout=10)
            self.log_file.close()
            self.log_proc = None
        with open(self.log_path, "rb") as f:
            return f.read().decode("utf-8", "replace")

    def install(self, apk_path, package, uninstall_first=True):
        d = self.dev
        if uninstall_first:
            d.sh("pm uninstall %s" % package)
        out = d.adb("install", "-r", apk_path, timeout=180).stdout.decode(errors="replace")
        if "Success" not in out:
            raise RuntimeError("install failed: " + out[:200])

    def enable_ime(self, package):
        d = self.dev
        ime = package + "/helium314.keyboard.latin.LatinIME"
        for _ in range(6):
            if "now enabled" in d.sh("ime enable " + ime) or ime in d.sh("ime list -s"):
                d.sh("ime enable " + ime)
                break
            time.sleep(1.5)
        out = d.sh("ime set " + ime)
        if "selected" not in out:
            raise RuntimeError("ime set failed: " + out[:120])

    def language_switch(self, name, row_text=None):
        """Turns the switch at the right edge of a language row in Languages & Layouts (the row text is `name`)."""
        d = self.dev
        self.settings_home()
        d.tap_text("Languages & Layouts")
        time.sleep(1)
        if name == "English (US)":
            for _ in range(8):
                d.sh("input swipe 540 600 540 1500 250")
                time.sleep(0.4)
        n = self.scroll_to(name)
        d.tap(954, n["cy"])
        time.sleep(1.5)
        d.key("KEYCODE_BACK")
        time.sleep(1)

    def picker_items(self):
        """Opens the input method picker (long press on space, once the keyboard is up) and returns its item texts; leaves it open."""
        d = self.dev
        for _ in range(10):
            if d.find("More keys", windows=True):
                break
            time.sleep(1)
        d.sh("input swipe 540 1822 540 1822 900")
        time.sleep(1.3)
        return [t for t in d.texts() if "\n" in t]

    def check_default_languages(self):
        """A new install has English and Bulgarian enabled without any setup: both are in the picker."""
        d = self.dev
        items = []
        for _ in range(3):  # the first keyboard start after an install can be slow enough to lose the long press: an empty picker is a lost press, not a missing language
            self.field("normal")
            items = self.picker_items()
            d.key("KEYCODE_BACK")
            if items:
                break
            time.sleep(3)
        if not any(i.startswith("Bulgarian\nTaen") for i in items) or not any(i.startswith("English (US)\nTaen") for i in items):
            raise RuntimeError("English and Bulgarian are not both enabled by default: %s" % items)
        return items

    def setup_trigger_word(self):
        d = self.dev
        self.settings_home()
        self.open_section("Privacy")
        time.sleep(1)
        self.scroll_to("Self-destruct")
        d.tap_text("Self-destruct", exact=True)
        time.sleep(1)
        d.tap_text("Set a trigger word")
        time.sleep(1.2)
        fields = [n for n in d.nodes() if n["class"] == "EditText"]
        if len(fields) < 2:
            raise RuntimeError("trigger word dialog not shown: " + "; ".join(d.texts()[:8]))
        d.tap(fields[0]["cx"], fields[0]["cy"])
        time.sleep(0.5)
        d.type_text_field(CANARIES["trigger_word"])
        d.key(61)  # TAB to the second field
        time.sleep(0.4)
        d.type_text_field(CANARIES["trigger_word"])
        d.tap_text("OK", exact=True)
        time.sleep(2)

    def start_test_mode(self):
        d = self.dev
        self.settings_home()
        self.open_section("Privacy")
        time.sleep(1)
        self.scroll_to("Self-destruct")
        d.tap_text("Self-destruct", exact=True)
        time.sleep(1)
        d.tap_text("Start test mode")
        time.sleep(1.5)
        if d.find("Start", exact=True):  # a confirmation dialog, when there is one
            d.tap_text("Start", exact=True)
        self.test_mode_started = time.time()

    # ------------------------------------------------------------ the typing session
    def session(self):
        d = self.dev
        notes = []
        # 1. English: letters, learned word (typed twice with a space)
        self.field("normal")
        self.switch_layout("English (US)")
        d.type_keys("en", CANARIES["latin"] + " " + CANARIES["latin"] + " ")
        notes.append("english normal field: %r" % self.field_text())
        # 2. emoji suggestions in the strip for an English and a Bulgarian keyword
        d.type_keys("en", "dog")
        notes.append("strip after 'dog': %s" % "; ".join(self.strip_texts()[-6:]))
        d.sh("input keyevent 67 67 67")
        # 3. glide in English
        d.glide("en", "hello")
        time.sleep(0.8)
        notes.append("english glide 'hello': %r" % self.field_text())
        # 4. Bulgarian: letters, glide
        self.switch_layout("Bulgarian")
        d.type_keys("bg", " " + CANARIES["cyrillic"] + " " + CANARIES["cyrillic"] + " ")
        d.glide("bg", "здравей")
        time.sleep(0.8)
        notes.append("bulgarian typing and glide: %r" % self.field_text())
        d.type_keys("bg", "куче")
        notes.append("strip after 'куче': %s" % "; ".join(self.strip_texts()[-6:]))
        self.switch_layout("English (US)")
        # 5. password field and the no-learning field (nothing may be learned there)
        self.field("password")
        d.type_keys("en", CANARIES["latin_password"] + " ")
        notes.append("password field typed (strip shows %s)" % "; ".join(self.strip_texts()[-4:]))
        self.field("nolearn")
        d.type_keys("en", CANARIES["latin_password"] + " ")
        # 6. incognito evidence: prefix in a normal field suggests the learned word but not the password-field word
        self.field("normal")
        d.type_keys("en", CANARIES["latin"][:4])
        strip_normal = self.strip_texts()
        d.sh("input keyevent 67 67 67 67")
        d.type_keys("en", CANARIES["latin_password"][:4])
        strip_pw = self.strip_texts()
        d.sh("input keyevent 67 67 67 67")
        self.save("session-notes.txt", "\n".join(notes))
        return notes, strip_normal, strip_pw

    def clipboard_session(self):
        """D6: a normal copy is kept, a sensitive one and one made in a password field are not; paste from the history leaves the system clip alone."""
        d = self.dev
        found = {}
        for key, focus, sens in (("clip_normal", "normal", False), ("clip_sensitive", "normal", True), ("clip_password", "password", False)):
            self.field(focus, copy=CANARIES[key], sensitive=sens, delay=7000)  # long enough for the keyboard to start input in the new field
            time.sleep(6.0)
        self.field("normal")
        shown = self.clipboard_entries()
        for key in ("clip_normal", "clip_sensitive", "clip_password"):
            found[key] = any(CANARIES[key] in t for t in shown)
        pasted = None
        item = d.find(CANARIES["clip_normal"], windows=True)
        if item:
            d.tap(item["cx"], item["cy"])
            time.sleep(1.2)
            pasted = self.field_text()
        self.close_clipboard()
        return found, pasted

    def close_clipboard(self):
        n = self.dev.find("Close clipboard history", windows=True)
        if n:
            self.dev.tap(n["cx"], n["cy"])
            time.sleep(1)

    def read_system_clip(self):
        self.field("normal", read=True)
        time.sleep(1.5)
        text = self.dev.adb("logcat", "-d", "-s", "AuditHelper:I").stdout.decode(errors="replace")
        m = re.findall(r"primary clip read: (.*)", text)
        return m[-1].strip() if m else None
