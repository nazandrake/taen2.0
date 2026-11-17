"""Runs the static checks on one release APK and returns the results."""
import hashlib
import os
import re
import tempfile

import apk as apklib
import deps as depslib
import static_checks as sc
from model import GAP, Result


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run_all(root, apk, with_deps=True, deps=None):
    tree = apklib.manifest_tree(root, apk)
    package = tree.attrs.get("package")
    results = [sc.check_permissions(apklib.permissions(root, apk), package)]
    app = tree.find("application")[0]

    def rules_name(attr):
        v = app.attrs.get(attr, "")
        m = re.search(r"xml/(\w+)", v)
        return m.group(1) if m else {"android:fullBackupContent": "backup_rules", "android:dataExtractionRules": "data_extraction_rules"}[attr]

    results.append(sc.check_manifest(tree, apklib.xml_resource(root, apk, rules_name("android:fullBackupContent")),
                                     apklib.xml_resource(root, apk, rules_name("android:dataExtractionRules"))))
    dex = apklib.apk_dex(apk)
    rules = sc.load_dex_rules(os.path.join(root, "privacy", "dex-rules.txt"))
    results.append(sc.check_dex_denied(dex, rules))
    patterns = [re.compile(p) for p in sc.FILE_WRITER_PATTERNS]
    loads = [re.compile(p) for p in sc.NATIVE_LOAD_PATTERNS]
    found = apklib.dex_callers(root, apk, lambda ref: any(p.match(ref) for p in patterns) or ref.split(".")[-1] in sc.NATIVE_WRITER_ENTRIES or any(p.match(ref) for p in loads))  # one dexdump pass
    entries = [(c, r.split(".")[-1]) for c, r in found if r.split(".")[-1] in sc.NATIVE_WRITER_ENTRIES]
    load_callers = [(c, r) for c, r in found if any(p.match(r) for p in loads)]
    results.append(sc.check_file_writers([(c, r) for c, r in found if any(p.match(r) for p in patterns)]))
    results.append(sc.check_logging(dex))
    results.append(sc.check_radios_sensors(dex))
    results.append(sc.check_urls(dex["strings"]))
    with tempfile.TemporaryDirectory(dir=os.path.expanduser("~/.cache/taen-audit")) as tmp:
        results.append(sc.check_native(apklib.apk_native(apk, tmp), entries, load_callers))
    if with_deps:
        d = deps or depslib.resolved(root)
        results.append(sc.check_dependencies(d, depslib.licences(d)))
    else:
        results.append(Result("S9", "Dependencies and licences", GAP, "skipped (--no-deps)"))
    with open(os.path.join(root, "gradle", "wrapper", "gradle-wrapper.properties")) as f1, open(os.path.join(root, "gradle", "verification-metadata.xml")) as f2:
        results.append(sc.check_supply_chain(root, f1.read(), f2.read()))
    with open(os.path.join(root, "tools", "nextword", "tokenizers.lock"), encoding="utf-8") as f:
        results.append(sc.check_models(apklib.entry_hashes(apk), sc.load_tokenizer_lock(f.read())))
    return results
