"""Reads an APK with the SDK's own tools (aapt2, dexdump) and small parsers; no network, nothing is installed or run."""
import os
import re
import struct
import subprocess
import hashlib
import zipfile


def sdk_dir(root):
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return os.environ[key]
    with open(os.path.join(root, "local.properties")) as f:
        for line in f:
            if line.startswith("sdk.dir="):
                return line.strip().split("=", 1)[1]
    raise SystemExit("set ANDROID_HOME or sdk.dir in local.properties")


def build_tool(root, name):
    with open(os.path.join(root, "app", "build.gradle.kts")) as f:
        version = re.search(r'buildToolsVersion = "([^"]+)"', f.read()).group(1)
    path = os.path.join(sdk_dir(root), "build-tools", version, name)
    if not os.path.exists(path):
        raise SystemExit("missing %s (install build-tools %s)" % (path, version))
    return path


def run(cmd, **kw):
    out = subprocess.run(cmd, capture_output=True, text=True, errors="replace", **kw)
    if out.returncode != 0:
        raise SystemExit("command failed (%d): %s\n%s" % (out.returncode, " ".join(cmd[:4]), out.stderr[:300]))
    return out.stdout


def signed_release_apk(root, version, override=None):
    """The signed release APK the device audit installs: dist/taen-<version>-signed.apk (made by `make sign`), or --release-apk. The audit never signs
    the release APK and never needs its key; a missing or unsigned file is an error before the phone is touched."""
    path = override or os.path.join(root, "dist", "taen-%s-signed.apk" % version)
    if not os.path.isfile(path):
        raise SystemExit("no signed release APK at %s: run make build, then make sign (you sign with your own key; the audit never signs it)" % path)
    out = subprocess.run([build_tool(root, "apksigner"), "verify", path], capture_output=True, text=True, errors="replace")
    if out.returncode != 0:
        raise SystemExit("%s is not a validly signed APK (apksigner verify failed: %s)" % (path, (out.stdout + out.stderr).strip()[:200]))
    return path


# ---------------------------------------------------------------- manifest (aapt2 dump xmltree)

class Node:
    def __init__(self, tag, depth):
        self.tag, self.depth, self.attrs, self.children = tag, depth, {}, []

    def find(self, tag):
        return [c for c in self.children if c.tag == tag]

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()


ATTR = re.compile(r'^\s*A: (.+?)(?:\(0x[0-9a-f]+\))?=(.*?)(?: \(Raw: "(.*)"\))?$')
ANDROID_NS = "http://schemas.android.com/apk/res/android:"


def parse_xmltree(text):
    """Parses `aapt2 dump xmltree` output into a Node tree; attribute values are the raw string when there is one, else the printed value."""
    root, stack = None, []
    for line in text.splitlines():
        m = re.match(r"^(\s*)E: (\S+)", line)
        if m:
            node = Node(m.group(2), len(m.group(1)))
            while stack and stack[-1].depth >= node.depth:
                stack.pop()
            if stack:
                stack[-1].children.append(node)
            else:
                root = root or node
            stack.append(node)
            continue
        a = ATTR.match(line)
        if a and stack:
            name, value, raw = a.group(1), a.group(2), a.group(3)
            name = "android:" + name[len(ANDROID_NS):] if name.startswith(ANDROID_NS) else name
            stack[-1].attrs[name] = raw if raw is not None else value.strip().strip('"')
    return root


def manifest_tree(root, apk):
    return parse_xmltree(run([build_tool(root, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk]))


def permissions(root, apk):
    out = run([build_tool(root, "aapt2"), "dump", "permissions", apk])
    return sorted(set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", out)))


def xml_resource(root, apk, name):
    """The tree of res/xml/<name>.xml inside the APK (found through the resource table, so renamed paths work)."""
    res = run([build_tool(root, "aapt2"), "dump", "resources", apk])
    m = re.search(r"resource 0x[0-9a-f]+ xml/%s\n.*?\(file\) (\S+)" % re.escape(name), res, re.S)
    if not m:
        return None
    return parse_xmltree(run([build_tool(root, "aapt2"), "dump", "xmltree", "--file", m.group(1), apk]))


# ---------------------------------------------------------------- dex

def uleb(data, pos):
    result = shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def parse_dex(data):
    """Returns {"types": [descriptor], "methods": [(class, name)], "fields": [(class, name)], "strings": [str]} of one classes.dex."""
    if data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    (ss, so, ts, to, ps, po, fs, fo, ms, mo) = struct.unpack_from("<10I", data, 56)
    strings = []
    for i in range(ss):
        off = struct.unpack_from("<I", data, so + 4 * i)[0]
        _, pos = uleb(data, off)
        end = data.index(b"\0", pos)
        strings.append(data[pos:end].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, to + 4 * i)[0]] for i in range(ts)]
    methods = []
    for i in range(ms):
        c, _, n = struct.unpack_from("<HHI", data, mo + 8 * i)
        methods.append((types[c], strings[n]))
    fields = []
    for i in range(fs):
        c, _, n = struct.unpack_from("<HHI", data, fo + 8 * i)
        fields.append((types[c], strings[n]))
    return {"types": types, "methods": methods, "fields": fields, "strings": strings}


def apk_dex(apk):
    merged = {"types": set(), "methods": set(), "fields": set(), "strings": set()}
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        for n in names:
            d = parse_dex(z.read(n))
            for k in merged:
                merged[k].update(d[k])
    merged["files"] = names
    return merged


def dex_callers(root, apk, interesting):
    """Which class calls which of the `interesting` method references: [(caller class, 'Lcls;.name')], from dexdump -d.
    `interesting` is a predicate on 'Lpkg/Cls;.method'."""
    found = set()
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        tmp = os.path.join(os.environ.get("TAEN_AUDIT_TMP", os.path.expanduser("~/.cache/taen-audit")), "dex")
        os.makedirs(tmp, exist_ok=True)
        for n in names:
            path = os.path.join(tmp, n)
            with open(path, "wb") as f:
                f.write(z.read(n))
            proc = subprocess.Popen([build_tool(root, "dexdump"), "-d", path], stdout=subprocess.PIPE, text=True, errors="replace")
            cls = None
            for line in proc.stdout:
                m = re.match(r"\s*Class descriptor\s*:\s*'([^']+)'", line)
                if m:
                    cls = m.group(1)
                    continue
                m = re.search(r"(?:invoke-\S+|sget\S*) .*?, (L[^;]+;\.[^:]+):", line)
                if m and interesting(m.group(1)):
                    found.add((cls, m.group(1)))
            proc.wait()
            os.remove(path)
    return sorted(found)


# ---------------------------------------------------------------- native libraries

def elf_imports(data_path):
    """(needed libraries, undefined dynamic symbols) of a shared object, from readelf."""
    needed = re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", run(["readelf", "-d", data_path]))
    syms = []
    for line in run(["readelf", "--dyn-syms", "--wide", data_path]).splitlines():
        parts = line.split()
        if len(parts) >= 8 and parts[6] == "UND" and parts[7] != "":
            syms.append(parts[7].split("@")[0])
    return needed, sorted(set(syms))


def apk_native(apk, tmp_dir):
    """{lib path in apk: (needed, imports)} for every lib/*/*.so (extracted to tmp_dir, readelf reads any ABI)."""
    result = {}
    os.makedirs(tmp_dir, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        for n in sorted(z.namelist()):
            if n.startswith("lib/") and n.endswith(".so"):
                p = os.path.join(tmp_dir, n.replace("/", "_"))
                with open(p, "wb") as f:
                    f.write(z.read(n))
                result[n] = elf_imports(p)
                os.remove(p)
    return result


def entry_hashes(apk):
    """{entry name: (size, sha256)} of every entry of the APK."""
    out = {}
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            h = hashlib.sha256()
            with z.open(info) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            out[info.filename] = (info.file_size, h.hexdigest())
    return out
