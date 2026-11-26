"""Per-UID traffic from `dumpsys netstats --uid` (the platform's own per-UID counters, no root needed). The counters are persisted on a poll, which
a change of the network interfaces forces: toggling Wi-Fi does that, so every reading is taken after one toggle."""
import re
import time

from model import NEEDS_USB

IDENT = re.compile(r"ident=\[.*\] uid=(-?\d+) set=(\w+) tag=(0x[0-9a-f]+)")
BUCKET = re.compile(r"st=\d+ rb=(\d+) rp=(\d+) tb=(\d+) tp=(\d+)")


def parse(text):
    """{uid: (rx bytes, tx bytes)} summed over all buckets, sets and interfaces of the 'UID stats:' section, tag 0 only."""
    totals = {}
    in_uid_section = False
    uid = None
    for line in text.splitlines():
        if not line.startswith(" "):
            in_uid_section = line.startswith("UID stats:")
            uid = None
            continue
        if not in_uid_section:
            continue
        m = IDENT.search(line)
        if m:
            uid = int(m.group(1)) if m.group(3) == "0x0" else None
            continue
        b = BUCKET.search(line)
        if b and uid is not None:
            rx, tx = totals.get(uid, (0, 0))
            totals[uid] = (rx + int(b.group(1)), tx + int(b.group(3)))
    return totals


def read(dev, poll=True):
    """Forces a poll (Wi-Fi off and on, waits for the network) and returns {uid: (rx, tx)}. The only code that switches the phone's Wi-Fi off:
    over adb on Wi-Fi it refuses (that would end the session), and the Wi-Fi is switched on again however the switch-off ends."""
    if poll:
        if dev.over_wifi:
            raise RuntimeError(NEEDS_USB)
        try:
            dev.sh("svc wifi disable")
            time.sleep(3)
        finally:
            dev.sh("svc wifi enable")
        for _ in range(30):
            time.sleep(1)
            if "wlan0" in dev.sh("ip -4 addr show wlan0"):
                if "inet " in dev.sh("ip -4 addr show wlan0"):
                    break
        time.sleep(2)
    return parse(dev.sh("dumpsys netstats --uid", timeout=60))


def uid_of(dev, package):
    m = re.search(r"userId=(\d+)", dev.sh("dumpsys package %s | grep -m1 userId=" % package))
    return int(m.group(1)) if m else None
