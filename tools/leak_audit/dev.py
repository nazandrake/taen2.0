"""adb wrapper for the device checks: shell, uiautomator tree (also the IME window), taps, text, typing on the Taen layouts, logcat, screencap."""
import html
import re
import subprocess
import time


class Dev:
    def __init__(self, serial=None):
        self.serial = serial
        self.adb_cmd = ["adb"] + (["-s", serial] if serial else [])

    # ---- adb
    def adb(self, *args, input=None, check=False, timeout=120):
        out = subprocess.run(self.adb_cmd + list(args), capture_output=True, input=input, timeout=timeout)
        if check and out.returncode != 0:
            raise RuntimeError("adb %s failed: %s" % (" ".join(args[:3]), out.stderr.decode(errors="replace")[:200]))
        return out

    def sh(self, cmd, timeout=120):
        """Runs a shell command on the phone; returns stdout text (stderr appended after a marker line when there is any)."""
        out = self.adb("shell", cmd, timeout=timeout)
        text = out.stdout.decode(errors="replace")
        err = out.stderr.decode(errors="replace").strip()
        return text + ("\n[stderr] " + err if err else "")

    def sh_rc(self, cmd, timeout=120):
        out = self.adb("shell", cmd + "; echo __rc=$?", timeout=timeout)
        text = out.stdout.decode(errors="replace")
        m = re.search(r"__rc=(\d+)\s*$", text)
        return int(m.group(1)) if m else -1, text[:m.start()] if m else text

    def present(self):
        return self.adb("get-state").stdout.decode().strip() == "device"

    # ---- screen and input
    def wake(self):
        self.sh("input keyevent KEYCODE_WAKEUP")
        time.sleep(0.5)
        self.sh("wm dismiss-keyguard")
        time.sleep(0.5)

    def sleep_screen(self):
        self.sh("input keyevent KEYCODE_SLEEP")

    def awake(self):
        return "mWakefulness=Awake" in self.sh("dumpsys power | grep mWakefulness=")

    def tap(self, x, y):
        self.sh("input tap %d %d" % (x, y))

    def key(self, code):
        self.sh("input keyevent " + str(code))

    def start(self, component, **extras):
        args = ""
        for k, v in extras.items():
            if isinstance(v, bool):
                args += " --ez %s %s" % (k, "true" if v else "false")
            else:
                args += " --es %s '%s'" % (k, v)
        return self.sh("am start -n %s%s" % (component, args))

    # ---- ui tree
    def tree(self, windows=False):
        cmd = ["exec-out", "uiautomator", "dump"] + (["--windows"] if windows else []) + ["/dev/tty"]
        for _ in range(3):
            xml = self.adb(*cmd).stdout.decode(errors="replace")
            if "<hierarchy" in xml or "<displays" in xml:
                return xml
            time.sleep(0.7)
        return ""

    def nodes(self, windows=False):
        out = []
        for m in re.finditer(r"<node [^>]*>", self.tree(windows)):
            n = m.group(0)

            def g(k):
                a = re.search(r' %s="([^"]*)"' % k, n)
                return html.unescape(a.group(1)) if a else ""

            b = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", g("bounds"))
            if not b:
                continue
            x1, y1, x2, y2 = map(int, b.groups())
            out.append({"text": g("text"), "desc": g("content-desc"), "id": g("resource-id").split("/")[-1], "pkg": g("package"), "class": g("class").split(".")[-1],
                        "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "bounds": (x1, y1, x2, y2), "checked": g("checked"), "clickable": g("clickable") == "true", "password": g("password") == "true"})
        return out

    def texts(self, windows=False):
        return [n["text"] or n["desc"] for n in self.nodes(windows) if n["text"] or n["desc"]]

    def find(self, text, windows=False, exact=False):
        ns = self.nodes(windows)
        for n in ns:
            if text in (n["text"], n["desc"]) or any(t.split("\n")[0] == text for t in (n["text"], n["desc"])):
                return n
        if not exact:
            for n in ns:
                if text.lower() in (n["text"] + " " + n["desc"]).lower():
                    return n
        return None

    def tap_text(self, text, exact=False, wait=0.8, tries=4):
        for _ in range(tries):
            n = self.find(text, exact=exact)
            if n:
                self.tap(n["cx"], n["cy"])
                time.sleep(wait)
                return n
            time.sleep(0.7)
        raise RuntimeError("not on screen: %r (screen: %s)" % (text, "; ".join(self.texts()[:12])))

    def wait_text(self, text, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            if self.find(text):
                return True
            time.sleep(0.6)
        return False

    def scroll_down(self):
        self.sh("input swipe 540 1500 540 700 300")
        time.sleep(0.6)

    def type_text_field(self, s):
        """Text into an app text field (not through the keyboard): input text, spaces as %s."""
        self.sh("input text '%s'" % s.replace(" ", "%s"))

    # ---- the Taen soft keyboard (portrait 1080x1920: strip 1133-1253, rows at y 1335/1497/1660, bottom row 1822; measured on the reference phone)
    EN_ROWS = (("qwertyuiop", 1335, 54, 108.0), ("asdfghjkl", 1497, 108, 108.0), ("zxcvbnm", 1660, 216, 108.0))
    BG_ROWS = (("явертъуиопч", 1340, 48, 98.2), ("асдфгхйклшщ", 1500, 48, 98.2), ("зьцжбнмю", 1660, 210, 94.5))
    SPACE = (540, 1822)

    def key_xy(self, layout, ch):
        if ch == " ":
            return self.SPACE
        for letters, y, x0, step in (self.BG_ROWS if layout == "bg" else self.EN_ROWS):
            if ch in letters:
                return int(x0 + step * letters.index(ch)), y
        raise KeyError("no key %r in layout %s" % (ch, layout))

    def type_keys(self, layout, s, delay=0.3):
        for ch in s:
            x, y = self.key_xy(layout, ch)
            self.tap(x, y)
            time.sleep(delay)

    def glide(self, layout, word, steps=14):
        """Draws the word as one finger path through the key centres (DOWN, MOVE..., UP with `input motionevent`)."""
        pts = [self.key_xy(layout, c) for c in word]
        path = [pts[0]]
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            if (x0, y0) == (x1, y1):
                continue
            n = max(1, int(((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5 / 40))
            path += [(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n) for i in range(1, n + 1)]
        cmds = ["input motionevent DOWN %d %d" % path[0]] + ["input motionevent MOVE %d %d" % p for p in path[1:]] + ["input motionevent UP %d %d" % path[-1]]
        self.sh("; ".join(cmds), timeout=180)

    # ---- logs and screen capture
    def logcat_clear(self):
        self.sh("logcat -b all -c")

    def logcat_dump(self):
        return self.adb("logcat", "-b", "all", "-d", "-v", "threadtime").stdout.decode(errors="replace")

    def screencap_raw(self):
        """(width, height, rgba bytes) or None when the capture is refused (secure window on screen)."""
        out = self.adb("exec-out", "screencap")
        data = out.stdout
        if len(data) < 16:
            return None
        w, h = int.from_bytes(data[0:4], "little"), int.from_bytes(data[4:8], "little")
        body = data[len(data) - w * h * 4:]
        return w, h, body
