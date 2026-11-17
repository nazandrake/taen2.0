"""Resolved runtime dependencies of the release build (from Gradle) and their licences (from the POMs in the local Gradle cache, offline)."""
import glob
import os
import re
import subprocess
import xml.etree.ElementTree as ET

LINE = re.compile(r"^[| +\\-]*([\w.\-]+):([\w.\-]+)(?::([\w.\-+]+))?(?: -> ([\w.\-+]+))?(?: \((?:\*|c|n)\))?\s*$")


def resolved(root, configuration="releaseRuntimeClasspath"):
    out = subprocess.run(["./gradlew", "--console=plain", "--no-daemon", "--offline", "-q", ":app:dependencies", "--configuration", configuration],
                         cwd=root, capture_output=True, text=True, errors="replace")
    if out.returncode != 0:
        raise SystemExit("gradle dependencies failed:\n" + out.stderr[-400:])
    return parse_tree(out.stdout)


def parse_tree(text):
    best = {}
    for line in text.splitlines():
        if line.rstrip().endswith("(c)") or "FAILED" in line:  # constraint lines
            continue
        m = LINE.match(line)
        if not m:
            continue
        g, a, v, final = m.groups()
        version = final or v
        if version:
            best[(g, a)] = version
    return sorted("%s:%s:%s" % (g, a, v) for (g, a), v in best.items())


def _pom_path(gradle_home, g, a, v):
    hits = glob.glob(os.path.join(gradle_home, "caches", "modules-2", "files-2.1", g, a, v, "*", "%s-%s.pom" % (a, v)))
    return hits[0] if hits else None


def licence_of(g, a, v, gradle_home=None, depth=0):
    """The first <license><name> of the POM, else of its parent POM; '' when none is found."""
    gradle_home = gradle_home or os.path.expanduser("~/.gradle")
    path = _pom_path(gradle_home, g, a, v)
    if not path or depth > 3:
        return ""
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return ""
    names = [e.text.strip() for e in tree.iter() if e.tag.endswith("}name") or e.tag == "name" for _ in [0]
             if _in_licence(tree, e)]
    if names:
        return "; ".join(dict.fromkeys(names))
    for p in tree.getroot():
        if p.tag.endswith("parent"):
            vals = {c.tag.split("}")[-1]: (c.text or "").strip() for c in p}
            if vals.get("groupId") and vals.get("artifactId") and vals.get("version"):
                return licence_of(vals["groupId"], vals["artifactId"], vals["version"], gradle_home, depth + 1)
    return ""


def _in_licence(tree, name_elem):
    for lic in tree.iter():
        if lic.tag.split("}")[-1] == "license" and name_elem in list(lic):
            return True
    return False


def licences(deps, gradle_home=None):
    result = {}
    for d in deps:
        g, a, v = d.split(":")[:3]
        result[g + ":" + a] = licence_of(g, a, v, gradle_home)
    return result
