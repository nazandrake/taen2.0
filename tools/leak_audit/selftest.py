"""Planted failures: proof that every static check category can fail (a check that cannot fail proves nothing).

Builds two tiny APKs with the SDK tools (aapt2, javac, d8): a PLANTED one with one violation per category and a CLEAN control. The real checks run
on both: each category must FAIL on the planted one and PASS on the control. Checks that work on lists (dependencies, supply chain) get a planted list.
Nothing here touches the phone or the network."""
import os
import re
import shutil
import subprocess
import tempfile
import zipfile

import apk as apklib
import static_checks as sc
from model import FAIL, PASS, Result

HERE = os.path.dirname(os.path.abspath(__file__))

CLEAN_MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="clean.taen">
    <uses-permission android:name="android.permission.VIBRATE"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <permission android:name="clean.taen.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature"/>
    <uses-permission android:name="clean.taen.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
    <application android:allowBackup="false" android:fullBackupContent="@xml/backup_rules" android:dataExtractionRules="@xml/data_extraction_rules">
        <activity android:name="clean.Clean" android:exported="false"/>
        <provider android:name="androidx.startup.InitializationProvider" android:authorities="clean.taen.androidx-startup" android:exported="false"/>
        <provider android:name="helium314.keyboard.latin.media.MediaShareProvider" android:authorities="clean.taen.mediashare" android:exported="false" android:grantUriPermissions="true"/>
    </application>
</manifest>
"""

PLANTED_MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="planted.taen">
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.READ_CONTACTS"/>
    <queries><package android:name="com.example.other"/></queries>
    <application android:allowBackup="true" android:debuggable="true" android:usesCleartextTraffic="true">
        <activity android:name="planted.Planted" android:exported="true"/>
        <provider android:name="planted.Provider" android:authorities="planted.taen.p" android:exported="true"/>
    </application>
</manifest>
"""


def tools(root):
    sdk = apklib.sdk_dir(root)
    android_jar = os.path.join(sdk, "platforms", "android-37.0", "android.jar")
    return {"aapt2": apklib.build_tool(root, "aapt2"), "d8": apklib.build_tool(root, "d8"), "jar": android_jar}


def build_apk(root, work, name, manifest, sources, with_res=False, native=None):
    t = tools(root)
    d = os.path.join(work, name)
    os.makedirs(d)
    mpath = os.path.join(d, "AndroidManifest.xml")
    with open(mpath, "w") as f:
        f.write(manifest)
    classes = os.path.join(d, "classes")
    os.makedirs(classes)
    apklib.run(["javac", "-nowarn", "--release", "17", "-cp", t["jar"], "-d", classes] + [os.path.join(HERE, "planted", s) for s in sources])
    classfiles = [os.path.join(dp, f) for dp, _, fs in os.walk(classes) for f in fs if f.endswith(".class")]
    dexdir = os.path.join(d, "dex")
    os.makedirs(dexdir)
    apklib.run([t["d8"], "--output", dexdir, "--lib", t["jar"], "--min-api", "30"] + classfiles)
    link = [t["aapt2"], "link", "-o", os.path.join(d, "base.apk"), "--manifest", mpath, "-I", t["jar"], "--min-sdk-version", "30", "--target-sdk-version", "34"]
    if with_res:
        res = os.path.join(d, "res")
        os.makedirs(os.path.join(res, "xml"))
        for n in ("backup_rules", "data_extraction_rules"):
            shutil.copy(os.path.join(root, "app", "src", "main", "res", "xml", n + ".xml"), os.path.join(res, "xml", n + ".xml"))
        apklib.run([t["aapt2"], "compile", "--dir", res, "-o", os.path.join(d, "res.zip")])
        link.append(os.path.join(d, "res.zip"))
    apklib.run(link)
    out = os.path.join(d, name + ".apk")
    with zipfile.ZipFile(os.path.join(d, "base.apk")) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(os.path.join(dexdir, "classes.dex"), "classes.dex")
        if native:
            zout.write(native, "lib/arm64-v8a/" + os.path.basename(native))
    return out


def compile_native(work, name):
    out = os.path.join(work, name)
    apklib.run(["gcc", "-shared", "-fPIC", "-o", out, os.path.join(HERE, "planted", "planted.c")])
    return out


def static_results(root, apk_path, work):
    """The checks of static_run.run_all that read the APK itself (the list-based ones are run on planted lists below)."""
    import re
    tree = apklib.manifest_tree(root, apk_path)
    package = tree.attrs.get("package")
    app = tree.find("application")[0]
    res = {}
    res["S1"] = sc.check_permissions(apklib.permissions(root, apk_path), package)

    def rules(attr, default):
        m = re.search(r"xml/(\w+)", app.attrs.get(attr, ""))
        return apklib.xml_resource(root, apk_path, m.group(1) if m else default)

    res["S2"] = sc.check_manifest(tree, rules("android:fullBackupContent", "backup_rules"), rules("android:dataExtractionRules", "data_extraction_rules"))
    dex = apklib.apk_dex(apk_path)
    res["S3"] = sc.check_dex_denied(dex, sc.load_dex_rules(os.path.join(root, "privacy", "dex-rules.txt")))
    patterns = [re.compile(p) for p in sc.FILE_WRITER_PATTERNS]
    loads = [re.compile(p) for p in sc.NATIVE_LOAD_PATTERNS]
    found = apklib.dex_callers(root, apk_path, lambda ref: any(p.match(ref) for p in patterns) or ref.split(".")[-1] in sc.NATIVE_WRITER_ENTRIES or any(p.match(ref) for p in loads))
    res["S4"] = sc.check_file_writers([(c, r) for c, r in found if any(p.match(r) for p in patterns)])
    res["S5"] = sc.check_logging(dex)
    res["S6"] = sc.check_radios_sensors(dex)
    res["S8"] = sc.check_native(apklib.apk_native(apk_path, os.path.join(work, "so")), [(c, r.split(".")[-1]) for c, r in found if r.split(".")[-1] in sc.NATIVE_WRITER_ENTRIES],
                              [(c, r) for c, r in found if any(p.match(r) for p in loads)])
    return res


def run_all(root, out_dir):
    work = tempfile.mkdtemp(dir=os.path.expanduser("~/.cache/taen-audit"), prefix="selftest-")
    results = []
    try:
        planted = static_results(root, build_apk(root, work, "planted", PLANTED_MANIFEST, ["Planted.java", "Analytics.java"], native=compile_native(work, "libplanted.so")), work)
        clean = static_results(root, build_apk(root, work, "clean", CLEAN_MANIFEST, ["Clean.java"], with_res=True), work)
        want = {"S1": "INTERNET", "S2": "allowBackup", "S3": "java/net/Socket", "S4": "openFileOutput", "S5": "Log", "S6": "bluetooth", "S8": "socket"}
        for ident in ("S1", "S2", "S3", "S4", "S5", "S6", "S8"):
            p, c = planted[ident], clean[ident]
            text = " ".join(p.details + [p.summary]).lower()
            ok = p.status == FAIL and want[ident].lower() in text and c.status == PASS
            results.append(Result("T-" + ident, "%s fails on the planted APK (%s) and passes on the clean control" % (ident, p.title.split(":")[0]),
                                  PASS if ok else FAIL,
                                  "planted: %s, clean: %s%s" % (p.status, c.status, "" if ok else "  <- the check does not do its job"),
                                  ["planted: " + d for d in p.details[:6]] + ["clean: " + d for d in c.details[:2]]))
        results += planted_extras(planted, clean)
        results += provider_checks()
        results += release_apk_checks(root, work, build_apk(root, work, "clean2", CLEAN_MANIFEST, ["Clean.java"], with_res=True))
        results += list_checks()
        results += default_language_checks()
        results += clipboard_pin_checks()
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return results


def planted_extras(planted, clean):
    """Details that show the planted APK tripped the specific rules, not just any rule."""
    out = []
    expect = {
        "S1": ["network permission requested: android.permission.INTERNET", "unexpected permission: android.permission.READ_CONTACTS"],
        "S2": ["debuggable", "<queries>", "exported provider: planted.Provider", "allowBackup is not false"],
        "S3": ["java/net/Socket", "com/google/firebase/Analytics", "dalvik/system/DexClassLoader", "android/util/Log"],
        "S4": ["Landroid/content/Context;.openFileOutput", "Landroid/util/AtomicFile"],
        "S5": ["Landroid/util/Log;", "Ljava/util/logging/Logger;.info", "Ljava/io/PrintStream;.println"],
        "S6": ["android/bluetooth/BluetoothAdapter", "android/media/MediaRecorder"],
        "S8": ["imports network function socket", "__android_log_print", "imports log or console function puts", "imports log or console function perror", "imports file-writing function unlink", "native file-writing entry point flushNative",
               "native-code loading or registration is not on the allowlist: Ljava/lang/System;.load (from Lplanted/Planted;)",
               "native-code loading or registration is not on the allowlist: Lplanted/Planted;.registerCustomOpLibrary (from Lplanted/Planted;)",
               "native-code loading or registration is not on the allowlist: Lplanted/Planted;.addNnapi (from Lplanted/Planted;)",
               "native-code loading or registration is not on the allowlist: Ljava/lang/Runtime;.loadLibrary (from Lplanted/Planted;)"],
    }
    for ident, needles in expect.items():
        text = "\n".join(planted[ident].details)
        missing = [n for n in needles if n not in text]
        out.append(Result("T-%s-rules" % ident, "%s names each planted violation" % ident, FAIL if missing else PASS,
                          "all %d planted violations named" % len(needles) if not missing else "not reported: " + "; ".join(missing)))
    return out


def provider_checks():
    """T-S2-provider: S2 expects exactly the two allowed providers, both not exported, and URI grants only on the media provider. Each planted manifest
    below changes one thing against a control that passes; the node trees are built by hand (the same shape parse_xmltree makes of aapt2 output)."""
    def node(tag, depth, attrs=None, children=()):
        n = apklib.Node(tag, depth)
        n.attrs = dict(attrs or {})
        n.children = list(children)
        return n

    def rules(section_names):
        excludes = [node("exclude", 3, {"android:domain": d, "android:path": "."}) for d in sc.BACKUP_DOMAINS]
        if section_names is None:
            return node("full-backup-content", 1, children=excludes)
        return node("data-extraction-rules", 1, children=[node(name, 2, children=excludes) for name in section_names])

    def provider(name, exported="false", grant="false"):
        return node("provider", 2, {"android:name": name, "android:exported": exported, "android:grantUriPermissions": grant})

    def check(providers):
        app = node("application", 1, {"android:allowBackup": "false", "android:fullBackupContent": "@xml/backup_rules", "android:dataExtractionRules": "@xml/data_extraction_rules"}, providers)
        tree = node("manifest", 0, {"package": "t.taen"}, [app])
        return sc.check_manifest(tree, rules(None), rules(["cloud-backup", "device-transfer"]))

    startup = provider("androidx.startup.InitializationProvider")
    media = provider(sc.MEDIA_PROVIDER, grant="true")
    control = check([startup, media])
    exported = check([startup, provider(sc.MEDIA_PROVIDER, exported="true", grant="true")])
    second = check([startup, media, provider("planted.SecondProvider")])
    granting = check([provider("androidx.startup.InitializationProvider", grant="true"), media])
    missing = check([startup])
    ok = (control.status == PASS and exported.status == FAIL and second.status == FAIL and granting.status == FAIL and missing.status == FAIL
          and any("exported provider: " + sc.MEDIA_PROVIDER in d for d in exported.details)
          and any("provider not on the allowlist: planted.SecondProvider" in d for d in second.details)
          and any("grants URI permissions but is not the media provider" in d for d in granting.details)
          and any("not exactly the allowlist" in d for d in missing.details))
    return [Result("T-S2-provider", "S2 passes with exactly the two allowed providers and fails on an exported media provider, a second provider, a URI grant elsewhere and a missing media provider", PASS if ok else FAIL,
                   "control %s, exported %s, second %s, grants %s, missing %s%s" % (control.status, exported.status, second.status, granting.status, missing.status, "" if ok else "  <- the check does not do its job"),
                   ["exported: " + d for d in exported.details[:2]] + ["second: " + d for d in second.details[:2]])]


def release_apk_checks(root, work, unsigned_apk):
    """The device audit takes the owner-signed APK and never signs: a missing file and an unsigned APK are refused with a clear error, a signed one (throw-away key
    made here, never the owner's) is accepted."""
    def refused(path, *words):
        try:
            apklib.signed_release_apk(root, "0.0.0", path)
        except SystemExit as e:
            return all(w in str(e) for w in words)
        return False
    d = os.path.join(work, "signcheck")
    os.makedirs(d)
    missing = refused(os.path.join(d, "none.apk"), "no signed release APK", "make sign")
    unsigned = refused(unsigned_apk, "not a validly signed APK")
    ks, pw, signed = os.path.join(d, "t.p12"), os.path.join(d, "t.pass"), os.path.join(d, "signed.apk")
    with open(pw, "w") as f:
        f.write("selftest-" + os.path.basename(work))
    apklib.run(["keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks,
                "-storepass:file", pw, "-keypass:file", pw, "-dname", "CN=selftest"])
    apklib.run([apklib.build_tool(root, "apksigner"), "sign", "--ks", ks, "--ks-pass", "file:" + pw, "--out", signed, unsigned_apk])
    accepted = apklib.signed_release_apk(root, "0.0.0", signed) == signed
    ok = missing and unsigned and accepted
    return [Result("T-D14", "The device audit refuses a missing and an unsigned release APK and accepts a signed one", PASS if ok else FAIL,
                   "missing refused: %s, unsigned refused: %s, signed accepted: %s" % (missing, unsigned, accepted))]


def default_language_checks():
    """D15 reads the input method picker: an empty picker (the long press was lost) is retried, a picker without Bulgarian or English still fails, and a picker
    that stays empty fails."""
    import types
    import device_run

    class Fake:
        def __init__(self, pickers):
            self.pickers = list(pickers)
            self.dev = types.SimpleNamespace(key=lambda k: None)

        def field(self, focus):
            pass

        def picker_items(self):
            return self.pickers.pop(0) if self.pickers else []

    both = ["Bulgarian\nTaen", "English (US)\nTaen"]

    def run(pickers):
        try:
            return device_run.Run.check_default_languages(Fake(pickers))
        except RuntimeError:
            return "FAIL"
    retried = run([[], both]) == both
    control = run([both]) == both
    no_bg = run([["English (US)\nTaen"]]) == "FAIL"
    no_en = run([["Bulgarian\nTaen"]]) == "FAIL"
    empty = run([[], [], []]) == "FAIL"
    ok = retried and control and no_bg and no_en and empty
    return [Result("T-D15", "D15 retries a lost long press, passes the control and fails when Bulgarian or English is missing or the picker stays empty", PASS if ok else FAIL,
                   "retry: %s, control: %s, no Bulgarian fails: %s, no English fails: %s, always empty fails: %s" % (retried, control, no_bg, no_en, empty))]


def clipboard_pin_checks():
    """D6p finds the pin button inside the item of the entry. The history is a two-column grid: a neighbour's button can be nearer by height, and a pinned
    neighbour in the same row must not count as the entry's own pin. The old rule (nearest by height; any Unpin in the same row) is run on the same
    planted grid to show that it picks and accepts the neighbour."""
    import device_checks as dc

    def node(text="", desc="", bounds=(0, 0, 0, 0)):
        return {"text": text, "desc": desc, "pkg": "taen", "bounds": bounds, "cx": (bounds[0] + bounds[2]) // 2, "cy": (bounds[1] + bounds[3]) // 2}

    def grid(target_pin, neighbour_pin):
        # left column: the target (text centre 150, pin centre 120); right column: the neighbour whose pin centre (150) is nearer by height
        return [node(bounds=(0, 0, 1080, 400)),
                node(bounds=(0, 100, 540, 200)), node(desc=target_pin, bounds=(4, 100, 44, 140)), node(text="pinqzxcanary", bounds=(60, 110, 540, 190)),
                node(bounds=(540, 100, 1080, 200)), node(desc=neighbour_pin, bounds=(544, 130, 584, 170)), node(text="other entry", bounds=(600, 110, 1080, 190))]

    def old_pin(nodes, entry):
        return min([n for n in nodes if n["desc"] == "Pin"], key=lambda n: abs(n["cy"] - entry["cy"]))

    def old_pinned(nodes, entry):
        return any(n["desc"] == "Unpin" and abs(n["cy"] - entry["cy"]) < 80 for n in nodes)

    unpinned = grid("Pin", "Pin")
    entry = unpinned[3]
    right = dc.entry_pin_node(unpinned, entry) is unpinned[2]
    old_wrong = old_pin(unpinned, entry) is unpinned[5]  # the old rule taps the neighbour's button
    mixed = grid("Pin", "Unpin")  # the neighbour is pinned, the target is not
    not_pinned = dc.entry_pin_node(mixed, mixed[3])["desc"] == "Pin"
    old_accepts = old_pinned(mixed, mixed[3])  # the old rule calls the target pinned
    done = grid("Unpin", "Pin")
    pinned = dc.entry_pin_node(done, done[3])["desc"] == "Unpin"  # control: its own Unpin is accepted
    no_item = dc.entry_pin_node([n for n in unpinned if n["bounds"] not in ((0, 100, 540, 200), (0, 0, 1080, 400))], entry) is None  # no item that holds the entry and one button
    ok = right and old_wrong and not_pinned and old_accepts and pinned and no_item
    return [Result("T-D6p", "D6p takes the pin button inside the entry's own item and accepts only that entry's Unpin; the old nearest-by-height rule fails on the same grid", PASS if ok else FAIL,
                   "own button chosen: %s, old rule picks the neighbour: %s, own Pin not taken for pinned beside a pinned neighbour: %s, old rule accepts it: %s, own Unpin accepted: %s, no item: no button: %s"
                   % (right, old_wrong, not_pinned, old_accepts, pinned, no_item))]


def secure_clipboard_checks():
    """D6c detects each way the setting can leak: a paste key with the setting off, none with it on, an unflagged copy, a copy in the history, no control."""
    import device_checks as dc
    c = dc.CANARIES["clip_secure"]
    good = dict(control_has_paste=True, off_has_paste=False, on_has_paste=True, copied=c, sensitive=True, history=["clip entry"], canary=c, kept_control=True)
    planted = {
        "paste key with the setting off": dict(good, off_has_paste=True),
        "no paste key with the setting on": dict(good, on_has_paste=False),
        "copy not on the clipboard": dict(good, copied="other"),
        "copy not flagged sensitive": dict(good, sensitive=False),
        "flag unreadable": dict(good, sensitive=None),
        "copy in the history": dict(good, history=["clip entry", c]),
        "an earlier password-field clip in the history": dict(good, history=["clip entry", dc.CANARIES["clip_password"]], never_kept=(dc.CANARIES["clip_password"],)),
        "no control paste key": dict(good, control_has_paste=False),
        "history not shown": dict(good, kept_control=False),
    }
    control_ok = not dc.secure_clipboard_problems(**good)
    failed = {name: bool(dc.secure_clipboard_problems(**args)) for name, args in planted.items()}
    ok = control_ok and all(failed.values())
    return [Result("T-D6c", "D6c passes on the clean data and fails on a paste key with the setting off, none with it on, an unflagged copy, a copy in the history and a missing control",
                   PASS if ok else FAIL, "control: %s; failed: %s" % ("clean" if control_ok else "problems", ", ".join("%s=%s" % kv for kv in failed.items())))]


def secure_suggestion_checks():
    """D6d detects a password copy offered as a clipboard suggestion (as text or as stars) and a missing control or copy."""
    import device_checks as dc
    c, n = dc.CANARIES["clip_secure_suggest"], dc.CANARIES["clip_suggest"]
    good = dict(control_chip=n, copied=c, sensitive=True, chip_after=None, strip_after=["Clipboard", "Paste"], canary=c, control_canary=n)
    planted = {
        "password copy offered as a chip": dict(good, chip_after="*" * len(c)),
        "password copy offered as text": dict(good, chip_after=c),
        "stars on the strip": dict(good, strip_after=["*" * len(c)]),
        "the copy itself on the strip": dict(good, strip_after=[c]),
        "no control chip": dict(good, control_chip=None),
        "control chip of another clip": dict(good, control_chip="other"),
        "copy not on the clipboard": dict(good, copied="other"),
        "copy not flagged sensitive": dict(good, sensitive=False),
    }
    control_ok = not dc.secure_suggestion_problems(**good)
    failed = {name: bool(dc.secure_suggestion_problems(**args)) for name, args in planted.items()}
    ok = control_ok and all(failed.values())
    return [Result("T-D6d", "D6d passes on the clean data and fails on a password copy offered as a chip, as text or stars on the strip, a missing control and a missing or unflagged copy",
                   PASS if ok else FAIL, "control: %s; failed: %s" % ("clean" if control_ok else "problems", ", ".join("%s=%s" % kv for kv in failed.items())))]


def native_allowance_checks():
    """ONNX Runtime may link liblog and import logger, console and writer functions by name; no library may import a network function, and no other
    library may link liblog or import a log function (the planted S8 above already proves the base rules)."""
    ort = "lib/arm64-v8a/libonnxruntime.so"
    needed = ["liblog.so", "libandroid.so", "libm.so", "libc.so"]
    imports = ["__android_log_print", "__android_log_write", "fprintf", "fputs", "stderr", "fopen", "fwrite", "mkdir", "remove", "write", "memcpy"]
    good = sc.check_native({ort: (needed, imports)})
    other_name = sc.check_native({"lib/arm64-v8a/libother.so": (needed, imports)})
    network = sc.check_native({ort: (needed, imports + ["socket", "getaddrinfo"])})
    unknown_link = sc.check_native({ort: (needed + ["libssl.so"], imports)})
    ok = (good.status == PASS and other_name.status == FAIL and network.status == FAIL and unknown_link.status == FAIL
          and any("libother.so links liblog.so" in d for d in other_name.details) and any("libonnxruntime.so imports network function socket" in d for d in network.details)
          and any("links libssl.so" in d for d in unknown_link.details))
    return [Result("T-S8-ort", "S8 accepts the named ONNX Runtime allowance and rejects the same imports in another library, any network import in it, and an extra link", PASS if ok else FAIL,
                   "ort: %s, other library: %s, ort with socket: %s, ort with libssl: %s" % (good.status, other_name.status, network.status, unknown_link.status))]


def native_loading_checks():
    """Native code may be loaded or registered only from the allowlisted callers (the built-in library, ONNX Runtime's own two libraries and its own overloads)."""
    ok_callers = [("Lhelium314/keyboard/latin/utils/JniUtils;", "Ljava/lang/System;.loadLibrary"), ("Lai/onnxruntime/OnnxRuntime;", "Ljava/lang/System;.loadLibrary"),
                  ("Lai/onnxruntime/OnnxRuntime;", "Ljava/lang/System;.load"), ("Lai/onnxruntime/OrtSession$SessionOptions;", "Lai/onnxruntime/OrtSession$SessionOptions;.addNnapi"),
                  ("Lai/onnxruntime/OrtEnvironment;", "Lai/onnxruntime/OrtEnvironment;.registerExecutionProviderLibrary")]
    bad = {
        "path load from the JNI wrapper": ("Lhelium314/keyboard/latin/utils/JniUtils;", "Ljava/lang/System;.load"),
        "library load from another class": ("Lhelium314/keyboard/latin/voice/engine/OrtParakeet;", "Ljava/lang/System;.loadLibrary"),
        "Runtime.load": ("Lhelium314/keyboard/latin/voice/VoiceSession;", "Ljava/lang/Runtime;.load"),
        "custom op library from app code": ("Lhelium314/keyboard/latin/voice/engine/OrtParakeet;", "Lai/onnxruntime/OrtSession$SessionOptions;.registerCustomOpLibrary"),
        "NNAPI from app code": ("Lhelium314/keyboard/latin/voice/engine/OrtParakeet;", "Lai/onnxruntime/OrtSession$SessionOptions;.addNnapi"),
        "execution provider from app code": ("Lhelium314/keyboard/latin/VoiceTyping;", "Lai/onnxruntime/OrtSession$SessionOptions;.addExecutionProvider"),
        "System.load from the ONNX Runtime namespace look-alike": ("Lai/onnxruntimeX/Fake;", "Ljava/lang/System;.load"),
    }
    good_problems, _ = sc.check_native_loading(ok_callers)
    rejected = {name: bool(sc.check_native_loading([c])[0]) for name, c in bad.items()}
    ok = not good_problems and all(rejected.values())
    return [Result("T-S8-load", "S8 accepts the allowlisted native-code loaders and rejects every other System.load*, Runtime.load*, custom-op library or execution-provider registration", PASS if ok else FAIL,
                   "allowlisted callers: %d problem(s); rejected: %s" % (len(good_problems), ", ".join("%s=%s" % kv for kv in rejected.items())))]


def model_checks():
    """S11: a model file in the APK, a changed, missing or extra tokenizer file each fail; the pinned set passes."""
    lock = {"nextword/m/vocab.json": (10, "a" * 64), "nextword/m/merges.txt": (5, "b" * 64)}
    good = {"classes.dex": (1, "c" * 64), "assets/nextword/m/vocab.json": (10, "a" * 64), "assets/nextword/m/merges.txt": (5, "b" * 64)}
    cases = {
        "model file": dict(good, **{"assets/model-en.onnx": (100, "d" * 64)}),
        "changed tokenizer": dict(good, **{"assets/nextword/m/vocab.json": (10, "e" * 64)}),
        "missing tokenizer": {k: v for k, v in good.items() if not k.endswith("merges.txt")},
        "extra file": dict(good, **{"assets/nextword/m/extra.json": (3, "f" * 64)}),
    }
    control = sc.check_models(good, lock)
    failed = {name: sc.check_models(entries, lock).status == FAIL for name, entries in cases.items()}
    ok = control.status == PASS and all(failed.values())
    return [Result("T-S11", "S11 passes on the pinned tokenizer files and fails on a model file in the APK and a changed, missing or extra tokenizer file", PASS if ok else FAIL,
                   "control: %s; failed: %s" % (control.status, ", ".join("%s=%s" % kv for kv in failed.items())))]


def list_checks():
    out = native_allowance_checks() + native_loading_checks() + model_checks() + secure_clipboard_checks() + secure_suggestion_checks()
    deps = ["com.google.firebase:firebase-analytics:21.0.0", "org.example:no-licence:1.0", "org.example:gpl-lib:1.0", "org.apache:fine:1.0"]
    lic = {"com.google.firebase:firebase-analytics": "Apache 2.0", "org.example:gpl-lib": "Proprietary EULA", "org.apache:fine": "Apache License, Version 2.0"}
    r = sc.check_dependencies(deps, lic)
    ok = r.status == FAIL and all(any(k in d for d in r.details) for k in ("denied dependency group: com.google.firebase", "no licence found for org.example:no-licence", "licence not recognised as open source for org.example:gpl-lib"))
    ok = ok and not any("org.apache:fine" in d and "problem" in d for d in r.details[:3]) and sc.check_dependencies(["org.apache:fine:1.0"], lic).status == PASS
    out.append(Result("T-S9", "S9 fails on a planted tracker dependency, a missing and a non-open licence; passes on a clean list", PASS if ok else FAIL, "status %s" % r.status, r.details[:4]))
    wrapper_bad, wrapper_ok = "distributionUrl=x\n", "distributionSha256Sum=" + "a" * 64 + "\n"
    xml_bad = '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"><configuration><verify-metadata>false</verify-metadata></configuration><components><component group="g" name="n" version="1"><artifact name="n.jar"/></component></components></verification-metadata>'
    xml_ok = '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"><configuration><verify-metadata>true</verify-metadata></configuration><components><component group="g" name="n" version="1"><artifact name="n.jar"><sha256 value="%s"/></artifact></component></components></verification-metadata>' % ("b" * 64)
    bad, good = sc.check_supply_chain(None, wrapper_bad, xml_bad), sc.check_supply_chain(None, wrapper_ok, xml_ok)
    ok = bad.status == FAIL and good.status == PASS and any("distributionSha256Sum" in d for d in bad.details) and any("verify-metadata" in d for d in bad.details) and any("without sha256" in d for d in bad.details)
    out.append(Result("T-S10", "S10 fails on a wrapper without checksum, verification off and an artifact without sha256; passes on a clean pair", PASS if ok else FAIL, "bad: %s, good: %s" % (bad.status, good.status), bad.details[:4]))
    return out
