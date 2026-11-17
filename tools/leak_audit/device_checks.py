"""Detectors of the device checks as functions of plain data (so selftest.py can feed them planted data) and the canaries of the session."""
import io
import re
import tarfile
import zlib

# What the scripted session types, copies or sets. Chosen so that no word list, log line or system text contains them by accident.
CANARIES = {
    "latin": "qzxjvkw",              # typed on the English layout in a normal field, twice (learned)
    "latin_password": "wvkxjzq",     # typed in a password field (never learned)
    "cyrillic": "жщцъюьч",           # typed on the Bulgarian layout (learned)
    "emoji_search": "kjvqzxw",       # typed in the emoji search
    "clip_normal": "clipqzxcanary",  # copied by the helper in a normal field (kept in the history)
    "clip_sensitive": "sensqzxcanary",  # copied with the sensitive flag (never kept)
    "clip_password": "pwqzxcanary",  # copied while a password field has focus (never kept)
    "clip_pinned": "pinqzxcanary",   # copied in a normal field, pinned, retention Forever (kept until the wipe, D6p and D13b)
    "trigger_word": "zqtrigwordqx9871",
    "passphrase": "zqpassphraseqz",
    "profile": "TaenCanaryProfile7q",  # 1.1.0: the name of a saved settings profile (one value of the encrypted preferences; never in plain text, in the export or after a wipe)
}


def encodings(text):
    """The byte forms a canary can take in a file: UTF-8, UTF-16 LE and BE, Windows-1251 (Cyrillic) and, for logs, the escaped form."""
    forms = {text.encode("utf-8"), text.encode("utf-16-le"), text.encode("utf-16-be")}
    try:
        forms.add(text.encode("cp1251"))
    except UnicodeEncodeError:
        pass
    return forms


def scan_bytes(data, canaries):
    """{name: count} over every encoding."""
    return {name: sum(data.count(f) for f in encodings(text)) for name, text in canaries.items()}


def scan_log(text, canaries, skip_tags=("AuditHelper", "AUDIT-CONTROL")):
    """Hits of the canaries in a logcat dump; lines with the helper's own tag are not counted (it logs what it reads on purpose).
    Returns (hits {name: [line]}, number of lines scanned)."""
    hits = {}
    scanned = 0
    for line in text.splitlines():
        if any(" %s:" % t in line or "/%s(" % t in line or " %s " % t in line for t in skip_tags):
            continue
        scanned += 1
        for name, canary in canaries.items():
            if canary in line:
                hits.setdefault(name, []).append(line[:200])
    return hits, scanned


# ---------------------------------------------------------------- network

def gids_of(status_text):
    m = re.search(r"^Groups:\s*(.*)$", status_text, re.M)
    return {int(x) for x in m.group(1).split()} if m else None


INET_GID = 3003  # AID_INET: the group that lets a process open sockets


def check_no_inet_group(status_text):
    """Problems of a process: it can open sockets when it holds AID_INET (3003)."""
    g = gids_of(status_text)
    if g is None:
        return ["no Groups line in /proc status (cannot verify)"]
    return ["process holds the inet group 3003 (can open sockets), groups %s" % sorted(g)] if INET_GID in g else []


def socket_rows_for_uid(proc_net_text, uid):
    """Rows of /proc/net/tcp, tcp6, udp, udp6 owned by a uid."""
    rows = []
    for line in proc_net_text.splitlines():
        parts = line.split()
        if len(parts) > 8 and parts[0].endswith(":") and parts[7].isdigit() and int(parts[7]) == uid:
            rows.append(line.strip())
    return rows


# ---------------------------------------------------------------- screen capture

def capture_state(shot, min_rows=200, luma_black=12):
    """('refused'|'black'|'content', fraction of non-black pixels). shot is (w, h, rgba) or None when screencap refused."""
    if shot is None:
        return "refused", 0.0
    w, h, rgba = shot
    step = max(1, (w * h) // 20000)
    lit = total = 0
    for i in range(0, w * h, step):
        r, g, b = rgba[i * 4], rgba[i * 4 + 1], rgba[i * 4 + 2]
        total += 1
        if (r + g + b) // 3 > luma_black:
            lit += 1
    frac = lit / total if total else 0.0
    return ("black" if frac < 0.01 else "content"), frac


def capture_blocked(state):
    return state[0] in ("refused", "black")


# ---------------------------------------------------------------- backup archive (adb backup)

def backup_members(ab_bytes):
    """Names of the files in an adb backup archive (header, then a zlib stream of a tar); [] for a header-only file."""
    parts = ab_bytes.split(b"\n", 4)
    if len(parts) < 5 or parts[0] != b"ANDROID BACKUP":
        raise ValueError("not an adb backup file")
    if parts[3] != b"none":
        raise ValueError("encrypted backup")
    body = parts[4]
    if not body:
        return []
    tar = zlib.decompress(body)
    if not tar.strip(b"\0"):
        return []
    with tarfile.open(fileobj=io.BytesIO(tar)) as t:
        return [m.name for m in t if m.isfile()]


def backup_leaks(members, package, allowed_public=("/f/dicts/", "/f/profileInstalled", "/_manifest")):
    """Members that are neither the manifest nor public dictionary data (a debuggable build is backed up by adb whatever allowBackup says)."""
    return [m for m in members if not any(a in m for a in allowed_public)]


# ---------------------------------------------------------------- warnings (toasts, status lines)

def warning_present(texts, needles):
    return [t for t in texts if any(n.lower() in t.lower() for n in needles)]


# ---------------------------------------------------------------- clipboard history (pin buttons)

PIN_DESCRIPTIONS = ("Pin", "Unpin")


def _inside(outer, x, y):
    return outer[0] <= x <= outer[2] and outer[1] <= y <= outer[3]


def entry_pin_node(nodes, entry):
    """The pin button of the history entry whose text node is `entry`. The history is a grid of several columns, so the button is not the nearest one by
    height: it is the one inside the entry's own item, the smallest node that holds the entry text and exactly one pin button. None when there is no such item."""
    pins = [n for n in nodes if n["desc"] in PIN_DESCRIPTIONS]
    best = None
    for item in nodes:
        b = item["bounds"]
        if item == entry or item in pins or not (b[0] <= entry["bounds"][0] and b[1] <= entry["bounds"][1] and entry["bounds"][2] <= b[2] and entry["bounds"][3] <= b[3]):
            continue
        inside = [p for p in pins if _inside(b, p["cx"], p["cy"])]
        area = (b[2] - b[0]) * (b[3] - b[1])
        if len(inside) == 1 and (best is None or area < best[0]):
            best = (area, inside[0])
    return best[1] if best else None
