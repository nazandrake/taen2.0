"""Static checks of the release APK (S1-S8). Every check is a function of plain data so that selftest.py can feed it a planted violation.
The network, tracker, class-loader and log rules are read from privacy/dex-rules.txt (the one owner of that list); the audit scans with
its own dex parser (apk.parse_dex), a second opinion next to the Gradle scan in buildSrc."""
import os
import re
import xml.etree.ElementTree as ET

from model import FAIL, GAP, INFO, PASS, Result, verdict

NETWORK_PERMISSIONS = ("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "CHANGE_WIFI_STATE")
# Exactly these, nothing else (design P2). The third one is the signature permission Android adds for runtime receivers. RECORD_AUDIO is the
# one runtime permission (P20): asked for at the first tap on the voice key that can record, never at install or start.
EXPECTED_PERMISSIONS = ("android.permission.VIBRATE", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.RECORD_AUDIO", "{pkg}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

# Components the merged manifest may export, with the reason (design P3 plus what the merged manifest really has; reviewed 2026-11-12).
EXPORTED_ALLOWED = {
    "helium314.keyboard.latin.LatinIME": "the input method service, protected by BIND_INPUT_METHOD",
    "helium314.keyboard.latin.spellcheck.AndroidSpellCheckerService": "spell-checker service, protected by BIND_TEXT_SERVICE; uses no personal words",
    "helium314.keyboard.settings.SettingsActivity": "launcher activity (FLAG_SECURE)",
    "helium314.keyboard.settings.SettingsActivity2": "settings alias for older Android, also opens a .dict file the user chose (content: VIEW); reads, sends nothing",
    "helium314.keyboard.latin.spellcheck.SpellCheckerSettingsActivity": "settings page the system opens for the spell checker",
    "helium314.keyboard.latin.quick.SwitchKeyboardTileService": "Quick Settings tile (2.0.1), protected by BIND_QUICK_SETTINGS_TILE: only the system binds it; it opens the keyboard picker, no data",
    "helium314.keyboard.latin.SystemBroadcastReceiver": "system broadcasts (boot, locale, package replaced); acts on its own state only",
    "androidx.profileinstaller.ProfileInstallReceiver": "AndroidX baseline-profile installer, protected by the DUMP permission",
}
MEDIA_PROVIDER = "helium314.keyboard.latin.media.MediaShareProvider"
# exactly these two providers, both not exported; only the media provider hands out URI permissions (the receiving app of commitContent reads one picture)
PROVIDERS_ALLOWED = ("androidx.startup.InitializationProvider", MEDIA_PROVIDER)

BACKUP_DOMAINS = ("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")

# android:allowBackup and friends print as "false", "true" or "(type 0x12)0x0/0xffffffff" depending on the aapt2 version
def truthy(value):
    return value in ("true", "0xffffffff", "(type 0x12)0xffffffff", "-1")


def falsy(value):
    return value in ("false", "0x0", "(type 0x12)0x0", "0")


# ---------------------------------------------------------------- S1 permissions

def check_permissions(perms, package):
    expected = {p.format(pkg=package) for p in EXPECTED_PERMISSIONS}
    problems = ["network permission requested: " + p for p in perms if p.rsplit(".", 1)[-1] in NETWORK_PERMISSIONS]
    problems += ["unexpected permission: " + p for p in perms if p not in expected and p.rsplit(".", 1)[-1] not in NETWORK_PERMISSIONS]
    problems += ["expected permission missing: " + p for p in sorted(expected - set(perms))]
    return verdict("S1", "Permissions equal the allowlist; no INTERNET or other network permission", problems,
                   "%d permissions, exactly the allowlist, no network permission" % len(perms), ["permission: " + p for p in perms])


# ---------------------------------------------------------------- S2 merged manifest

def components(app):
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in app.find(tag):
            yield tag, c


def check_manifest(tree, backup_rules, extraction_rules):
    """tree: merged AndroidManifest Node tree; backup_rules, extraction_rules: Node trees of the two res/xml files (or None)."""
    problems, details = [], []
    app = tree.find("application")[0]
    a = app.attrs
    if not falsy(a.get("android:allowBackup", "true")):
        problems.append("allowBackup is not false (%r)" % a.get("android:allowBackup"))
    if truthy(a.get("android:debuggable", "false")):
        problems.append("the APK is debuggable")
    if truthy(a.get("android:usesCleartextTraffic", "false")):
        problems.append("usesCleartextTraffic")
    if "android:networkSecurityConfig" in a:
        problems.append("networkSecurityConfig present")
    if truthy(a.get("android:hasCode", "true")) is False and "android:hasCode" in a:
        problems.append("hasCode=false")
    if "android:sharedUserId" in tree.attrs:
        problems.append("sharedUserId")
    if tree.find("queries"):
        problems.append("<queries> present (installed-app enumeration)")
    if "android:fullBackupContent" not in a:
        problems.append("fullBackupContent missing")
    if "android:dataExtractionRules" not in a:
        problems.append("dataExtractionRules missing")
    if app.attrs.get("android:backupAgent"):
        problems.append("backupAgent " + app.attrs["android:backupAgent"])
    # backup rules: every domain excluded (a domain missing from the list would be backed up)
    def excluded(node):
        return {c.attrs.get("android:domain", c.attrs.get("domain")) for c in node.find("exclude") if c.attrs.get("android:path", c.attrs.get("path")) == "."}
    if backup_rules is None:
        problems.append("backup rules XML not found in the APK")
    else:
        for d in BACKUP_DOMAINS:
            if d not in excluded(backup_rules):
                problems.append("full-backup-content does not exclude domain " + d)
        if backup_rules.find("include"):
            problems.append("full-backup-content has an <include>")
    if extraction_rules is None:
        problems.append("data-extraction rules XML not found in the APK")
    else:
        for section in ("cloud-backup", "device-transfer"):
            nodes = extraction_rules.find(section)
            if not nodes:
                problems.append("data-extraction-rules has no <%s> (then everything is copied)" % section)
                continue
            for d in BACKUP_DOMAINS:
                if d not in excluded(nodes[0]):
                    problems.append("%s does not exclude domain %s" % (section, d))
            if nodes[0].find("include"):
                problems.append("%s has an <include>" % section)
    # components
    providers = []
    for tag, c in components(app):
        name = c.attrs.get("android:name", "?")
        if name.startswith("."):
            name = tree.attrs.get("package", "") + name
        exported = truthy(c.attrs.get("android:exported", "false"))
        if tag == "provider":
            if name not in PROVIDERS_ALLOWED:
                problems.append("provider not on the allowlist: " + name)
            if exported:
                problems.append("exported provider: " + name)
            grants = truthy(c.attrs.get("android:grantUriPermissions", "false")) or bool(c.find("grant-uri-permission"))
            if grants and name != MEDIA_PROVIDER:
                problems.append("provider grants URI permissions but is not the media provider: " + name)
            if name == MEDIA_PROVIDER and not grants:
                problems.append("the media provider must have grantUriPermissions=true")
            providers.append(name)
            details.append("provider %s exported=%s grantUriPermissions=%s" % (name, exported, grants))
        elif exported:
            short = name
            if short not in EXPORTED_ALLOWED:
                problems.append("exported %s not on the allowlist: %s" % (tag, name))
            else:
                details.append("exported %s %s: %s (permission %s)" % (tag, name, EXPORTED_ALLOWED[short], c.attrs.get("android:permission", "none")))
        if c.attrs.get("android:permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE":
            problems.append("accessibility service declared: " + name)
    if sorted(providers) != sorted(PROVIDERS_ALLOWED):
        problems.append("the providers are not exactly the allowlist: found %s, expected %s" % (sorted(providers), sorted(PROVIDERS_ALLOWED)))
    details.append("application: allowBackup=%s fullBackupContent=%s dataExtractionRules=%s" % (a.get("android:allowBackup"), a.get("android:fullBackupContent"), a.get("android:dataExtractionRules")))
    return verdict("S2", "Merged manifest: backup off and fully excluded, not debuggable, no <queries>, exactly the two allowed providers (none exported), exported components on the allowlist",
                   problems, "backup off with every domain excluded (cloud and device transfer), not debuggable, no <queries>, %d exported component(s) all on the allowlist, no exported provider" % sum(1 for d in details if d.startswith("exported")), details)


# ---------------------------------------------------------------- S3 dex: network, trackers, class loaders, logs

def load_dex_rules(path):
    rules = []
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            m = re.match(r"(deny|allow)\s+(\S+)$", line)
            if m:
                rules.append((m.group(1), m.group(2)))
    return rules


def rule_hits(descriptors, rules):
    """Descriptors ('Lpkg/Cls;' or 'pkg/Cls') denied by the longest matching rule."""
    hits = []
    for d in sorted(descriptors):
        name = d[1:-1] if d.startswith("L") and d.endswith(";") else d.lstrip("[").lstrip("L").rstrip(";")
        best = None
        for kind, prefix in rules:
            if name.startswith(prefix) and (best is None or len(prefix) > len(best[1])):
                best = (kind, prefix)
        if best and best[0] == "deny":
            hits.append("%s (rule deny %s)" % (name, best[1]))
    return hits


def check_dex_denied(dex, rules):
    names = set(dex["types"])
    names |= {c for c, _ in dex["methods"]} | {c for c, _ in dex["fields"]}
    hits = rule_hits(names, rules)
    return verdict("S3", "Release dex references no network, tracker, analytics, class-loader or android.util.Log class", hits,
                   "%d type descriptors in %d dex file(s), %d deny rules, no hit" % (len(dex["types"]), len(dex.get("files", [])) or 1, sum(1 for k, _ in rules if k == "deny")))


# Methods and fields that write a file or leave the process by another route than a denied class (S4). Matched on 'Lcls;.name'.
FILE_WRITER_PATTERNS = (
    r"Ljava/io/(FileOutputStream|FileWriter|RandomAccessFile|PrintWriter);\.<init>", r"Ljava/nio/file/Files;\.(write|newOutputStream|newBufferedWriter|copy|move|createFile|createTempFile)",
    r"L[^;]+;\.(openFileOutput|getSharedPreferences|getExternal\w+)$", r"Landroid/database/sqlite/", r"Landroid/provider/MediaStore",
    r"Landroid/content/ContentResolver;\.(insert|openOutputStream|openFileDescriptor)", r"Ljava/io/File;\.(createNewFile|createTempFile|renameTo)",
    r"Landroid/util/AtomicFile;", r"Ljava/nio/channels/FileChannel;\.(write|transferFrom|truncate)", r"Ljava/io/FileDescriptor;",
    r"Landroid/os/Environment;\.getExternal",
)
# The writer APIs the release dex may reference, with the reason. A reference to any other writer API (AtomicFile, Files.write, openFileOutput,
# MediaStore, external storage, RandomAccessFile, FileWriter ...) is a FAIL: it needs a reviewed entry here and a P7 argument. The callers printed
# in the details come from dexdump; R8 merges classes, so a caller's name can be that of an unrelated class (the class-level owner view is in
# privacyCheck, which scans the sources). Whether any of these ever writes plaintext user data is the device scan (D3), not this list.
FILE_WRITER_ALLOWED = {
    "Ljava/io/FileOutputStream;.<init>": "the encrypted stores (SecureStore, FileSet, wipers: ciphertext only, P7), file copies the user starts (dictionary, layout, theme import), "
                                         "kotlin File.writeText for the plain blacklist (main-dictionary words only, documented) and custom layouts, Compose RectList debug dump and "
                                         "the baseline-profile installer (code profile, no user data)",
    "Ljava/nio/file/Files;.move": "atomic rename of a hash-verified voice model file into place (VoiceModelStore: public model data, not user data); R8 may name any class as the caller",
    "Ljava/io/File;.renameTo": "atomic replace of an encrypted store; moves of the legacy plain files into the encrypted stores (LegacyMigration); imports",
    "Ljava/io/File;.createNewFile": "baseline-profile installer marker (androidx.profileinstaller), no user data",
    "Ljava/io/FileDescriptor;.sync": "fsync of the encrypted store before the rename (FileSet) and in the wipers",
    "Landroid/content/ContentResolver;.openOutputStream": "export to a file the user picks (the encrypted backup, a theme) through the system file picker",
    "Landroid/content/ContentResolver;.openFileDescriptor": "read access to a file the user picks (import)",
    "Landroid/database/sqlite/SQLiteDatabase;.openDatabase": "reads the legacy plain clipboard database once to move it into the encrypted store (LegacyMigration), then deletes it",
    "Landroid/database/sqlite/SQLiteDatabase;.rawQuery": "same read of the legacy clipboard database",
    "Landroid/content/Context;.getSharedPreferences": "reads the legacy plain preferences once to move them into the encrypted store (Privacy.openAtStart)",
}


def check_file_writers(callers, allowed=None):
    """callers: [(class descriptor, 'Lcls;.method')] from dexdump."""
    allowed = allowed or FILE_WRITER_ALLOWED
    def key(ref):  # openFileOutput and friends are called on whatever Context subclass the caller has
        method = ref.split(";.")[-1]
        return "Landroid/content/Context;." + method if method in ("openFileOutput", "getSharedPreferences") or method.startswith("getExternal") else ref

    problems = sorted({"file-writer API not on the allowlist: %s (from %s)" % (key(ref), cls) for cls, ref in callers if key(ref) not in allowed})
    refs = {}
    for cls, ref in callers:
        refs.setdefault(key(ref), set()).add(cls)
    details = ["%s  [%s]" % (ref, allowed[ref]) for ref in sorted(refs) if ref in allowed]
    details += ["    from %s" % ", ".join(sorted(refs[ref])) for ref in sorted(refs) if ref in allowed]
    return verdict("S4", "File-writer APIs in the release dex are only the allowlisted ones (no AtomicFile, openFileOutput, MediaStore, external storage, RandomAccessFile)", problems,
                   "%d writer API(s) referenced, all allowlisted; none of the other writer APIs is referenced" % len(refs), details)


LOG_PATTERNS = (r"Landroid/util/Log;\.", r"Ljava/util/logging/Logger;\.(log|logp|logrb|severe|warning|info|config|fine|finer|finest|entering|exiting|throwing)", r"Ljava/util/logging/(Handler|ConsoleHandler|StreamHandler|FileHandler)", r"Landroid/util/Slog", r"Landroid/util/EventLog", r"Ljava/lang/Throwable;\.printStackTrace", r"Landroid/util/Printer", r"Ljava/io/PrintStream;\.(print|println|printf|format|write|append)")
def check_logging(dex):
    problems = []
    for cls, name in sorted(dex["methods"]):
        ref = cls + "." + name
        if any(re.match(p, ref) for p in LOG_PATTERNS):
            problems.append("method reference " + ref)
    return verdict("S5", "Release dex has no logging call: android.util.Log, java.util.logging, PrintStream (System.out/err) print, printStackTrace", problems,
                   "no log, java.util.logging, PrintStream.print* or printStackTrace reference in %d method references" % len(dex["methods"]))


RADIO_SENSOR_PREFIXES = ("Landroid/bluetooth/", "Landroid/nfc/", "Landroid/location/", "Landroid/telephony/", "Landroid/net/wifi/", "Landroid/hardware/camera", "Landroid/hardware/usb/",
                         "Landroid/media/MediaRecorder", "Landroid/media/projection/", "Landroid/speech/", "Landroid/webkit/", "Landroid/app/DownloadManager", "Landroid/accounts/",
                         "Landroid/provider/ContactsContract", "Landroid/provider/CallLog", "Landroid/provider/Telephony", "Landroid/provider/Browser", "Landroid/app/usage/")


def check_radios_sensors(dex):
    names = {c for c, _ in dex["methods"]} | set(dex["types"])
    hits = sorted(n for n in names if n.startswith(RADIO_SENSOR_PREFIXES))
    # the one microphone API of the app (P20): AudioRecord reads samples into memory; it is listed so that a second user shows up in review
    mic = sorted(n for n in names if n.startswith("Landroid/media/AudioRecord"))
    return verdict("S6", "Release dex references no radio, location, camera, contacts, account or usage-stats API and no recorder or speech service (AudioRecord only)",
                   ["reference to " + h for h in hits],
                   "no reference to bluetooth, nfc, location, telephony, wifi, camera, MediaRecorder, speech, webkit, accounts, contacts, call log or usage stats; "
                   "the microphone is reached through %s only" % (", ".join(mic) or "no class"), ["microphone class reference: " + m for m in mic])


URL = re.compile(r"https?://[^\s\"'<>)]+")
URL_QUIET = ("schemas.android.com", "www.w3.org", "www.apache.org", "apache.org", "xmlpull.org", "xml.org", "java.sun.com", "ns.adobe.com", "www.gnu.org", "creativecommons.org", "unicode.org", "kotlinlang.org", "github.com", "codeberg.org")


def check_urls(strings):
    """URL strings inside the dex. There is no network code, so none can be opened by the app itself; an ACTION_VIEW would hand a URL to a browser."""
    urls = sorted({m.group(0) for s in strings for m in [URL.search(s)] if m})
    other = [u for u in urls if not any(q in u for q in URL_QUIET)]
    return Result("S7", "URL strings in the dex (information: no network code can open them)", INFO if other or urls else PASS,
                  "%d URL string(s), %d outside licence and namespace hosts" % (len(urls), len(other)), ["url: " + u for u in urls])


# ---------------------------------------------------------------- S8 native libraries

NATIVE_NETWORK = {"socket", "connect", "bind", "listen", "accept", "accept4", "send", "sendto", "sendmsg", "sendmmsg", "recv", "recvfrom", "recvmsg", "recvmmsg", "getaddrinfo", "freeaddrinfo",
                  "gethostbyname", "gethostbyname2", "gethostbyaddr", "getnameinfo", "socketpair", "shutdown", "getsockname", "getpeername", "setsockopt", "getsockopt", "res_init", "res_query", "inet_pton", "inet_ntop"}
NATIVE_LOG_OR_CONSOLE = {"__android_log_print", "__android_log_write", "__android_log_vprint", "__android_log_assert", "__android_log_buf_print", "__android_log_is_loggable",
                         "printf", "vprintf", "puts", "putchar", "perror", "fprintf", "fputs", "stdout", "stderr", "__stdoutp"}
NATIVE_FILE_WRITERS = {"fopen", "fopen64", "freopen", "fdopen", "creat", "creat64", "mkstemp", "tmpfile", "rename", "unlink", "ftruncate", "ftruncate64", "truncate", "remove", "mkdir", "write", "pwrite", "writev", "fwrite", "fputc"}
NATIVE_NEEDED_DENY = ("liblog.so", "libssl", "libcrypto", "libcurl", "libandroid.so", "libnetd_client", "libcutils", "libbinder", "libandroid_runtime")
# Imported on purpose, with the reason. Each file-writing one is the native dictionary file writer (dict_file_writing_utils.cpp, ver4_dict_buffers.cpp,
# file_utils.cpp); it is safe only while the JNI entry points that reach it have no caller in the release dex (NATIVE_WRITER_ENTRIES, checked below).
NATIVE_ALLOWED = {
    "fopen": "dictionary file writer", "fdopen": "dictionary file writer", "fwrite": "dictionary file writer", "fputc": "dictionary file writer",
    "mkdir": "dictionary file writer", "rename": "dictionary file writer", "remove": "dictionary file writer",
    "vfprintf": "libc++abi abort_message (fatal error text: C++ exception type names, no user text)", "fflush": "libc++abi abort_message / dictionary file writer",
    "android_set_abort_message": "libc++abi abort_message",
}
NATIVE_WRITER_ENTRIES = ("flushNative", "flushWithGCNative", "migrateNative", "createEmptyDictFileNative")
# one prebuilt library is allowed more, by name (privacy/native-libs.allow marks it links-liblog): ONNX Runtime 1.27.0 (MIT). Checked on its arm64-v8a file:
# no socket, DNS or other network import. It links liblog (logger, level set to fatal by the app, device check D2 reads logcat) and libandroid (NNAPI loader), and
# imports console and file-writing functions for features the app never calls (profiling output, optimised-model output: privacyCheck forbids the two Java calls
# setOptimizedModelFilePath and enableProfiling, and the file functions are not reachable otherwise). Network imports are never allowed, for any library.
NATIVE_LIB_ALLOWANCES = {
    "libonnxruntime.so": {
        "needed": {"liblog.so": "ONNX Runtime logger (level fatal)", "libandroid.so": "ONNX Runtime NNAPI/asset loader (CPU provider only is used)"},
        "imports": {s: "ONNX Runtime logger" for s in ("__android_log_print", "__android_log_vprint", "__android_log_write")}
                   | {s: "ONNX Runtime console diagnostics (stderr is not logcat in an app process)" for s in ("fprintf", "fputs", "stderr")}
                   | {s: "ONNX Runtime profiling / optimised-model output, never enabled (privacyCheck)" for s in ("fopen", "fputc", "fwrite", "mkdir", "remove", "write")},
    },
}


# native code can enter the process after the build only through a call that ends in dlopen: System.load or loadLibrary of a
# path or name, ONNX Runtime's registerCustomOpLibrary / registerExecutionProviderLibrary, or an execution provider (addNnapi, addXnnpack, addCUDA ... addExecutionProvider)
# whose library is loaded at run time. The release dex may reference them from the places below only; a call from anywhere else is a FAIL (same rule as SourceRules in privacyCheck,
# which rejects the names in the sources). The library's own classes refer to their provider methods internally (the overloads); our code never calls them.
NATIVE_LOAD_PATTERNS = (
    r"^Ljava/lang/(System|Runtime);\.(load|loadLibrary|loadLibrary0)$",
    r"^L[^;]+;\.(register\w*Library|addExecutionProvider\w*|add(Nnapi|Xnnpack|XNNPACK|Cuda|CUDA|Tensorrt|TensorRT|CoreML|Qnn|QNN|Rocm|ROCM|DirectML|Dnnl|OpenVINO|Acl|ACL|ArmNN|Armnn|Cann|CANN|Snpe|SNPE|Tvm|TVM|Rknpu|MIGraphX|Vitis\w*|WebGPU|Webgpu)\w*)$",
)
# (caller class prefix, reference pattern) -> reason
NATIVE_LOAD_ALLOWED = (
    ("Lhelium314/keyboard/latin/utils/JniUtils;", r"^Ljava/lang/System;\.loadLibrary$", "the built-in libjni_latinime (P6)"),
    ("Lai/onnxruntime/OnnxRuntime;", r"^Ljava/lang/System;\.(load|loadLibrary)$", "ONNX Runtime loads its two libraries (libonnxruntime, libonnxruntime4j_jni: privacy/native-libs.allow)"),
    ("Landroidx/graphics/path/PathIteratorPreApi34Impl;", r"^Ljava/lang/System;\.loadLibrary$", "AndroidX graphics-path library (privacy/native-libs.allow)"),
    ("Lai/onnxruntime/", r"^L[^;]+;\.(register\w*Library|addExecutionProvider\w*|add[A-Z]\w*)$", "ONNX Runtime's own overloads of its provider methods; app code never calls them (that would be a caller outside ai/onnxruntime)"),
)


def check_native_loading(callers):
    """callers: [(class descriptor, 'Lcls;.method')] from dexdump for the NATIVE_LOAD_PATTERNS. Returns (problems, details)."""
    problems, allowed = set(), {}
    for cls, ref in callers:
        reason = next((why for prefix, pat, why in NATIVE_LOAD_ALLOWED if cls.startswith(prefix) and re.match(pat, ref)), None)
        if reason is None:
            problems.add("native-code loading or registration is not on the allowlist: %s (from %s)" % (ref, cls))
        else:
            allowed.setdefault(reason, set()).add(cls)
    details = ["native loading allowed from %s: %s" % (", ".join(sorted(who)), why) for why, who in sorted(allowed.items())]
    return sorted(problems), details


def check_native(libs, writer_entry_callers=(), load_callers=()):
    """libs: {path: (needed, imports)}; writer_entry_callers: [(caller class, JNI method name)] found in the dex for NATIVE_WRITER_ENTRIES;
    load_callers: [(caller class, 'Lcls;.method')] for NATIVE_LOAD_PATTERNS."""
    problems, details = [], []
    for cls, name in writer_entry_callers:
        problems.append("native file-writing entry point %s is called from %s" % (name, cls))
    load_problems, load_details = check_native_loading(load_callers)
    problems += load_problems
    details += load_details
    for path, (needed, imports) in sorted(libs.items()):
        extra = NATIVE_LIB_ALLOWANCES.get(os.path.basename(path), {"needed": {}, "imports": {}})
        for n in needed:
            if n.startswith(NATIVE_NEEDED_DENY) and n not in extra["needed"]:
                problems.append("%s links %s" % (path, n))
        for s in imports:
            if s in NATIVE_ALLOWED or (s in extra["imports"] and s not in NATIVE_NETWORK):
                continue
            if s in NATIVE_NETWORK:
                problems.append("%s imports network function %s" % (path, s))
            elif s in NATIVE_LOG_OR_CONSOLE:
                problems.append("%s imports log or console function %s" % (path, s))
            elif s in NATIVE_FILE_WRITERS:
                problems.append("%s imports file-writing function %s" % (path, s))
        allowed = sorted(s for s in imports if s in NATIVE_ALLOWED or s in extra["imports"])
        details.append("%s: needs %s; %d imported symbols; allowed: %s" % (path, ", ".join(needed) or "-", len(imports), ", ".join(allowed) or "-"))
        if extra["needed"] or extra["imports"]:
            details.append("%s has a per-library allowance: %s" % (os.path.basename(path), "; ".join(sorted(set(list(extra["needed"].values()) + list(extra["imports"].values()))))))
    details.append("allowed imports: " + "; ".join("%s (%s)" % kv for kv in sorted(NATIVE_ALLOWED.items())))
    details.append("JNI file-writing entry points %s have %d caller(s) in the dex" % (", ".join(NATIVE_WRITER_ENTRIES), len(writer_entry_callers)))
    return verdict("S8", "Native libraries: no socket, DNS, log or console import, no linked log/TLS/network library, file writers unreachable from Java, no native-code loading outside the allowlist", problems,
                   "%d libraries scanned: no network, log or console import; the dictionary file writers are imported but their JNI entry points have no caller in the dex" % len(libs), details)


# ---------------------------------------------------------------- S9 dependencies, supply chain

DENIED_GROUPS = ("com.google.firebase", "com.google.android.gms", "com.google.mlkit", "io.sentry", "com.crashlytics", "com.bugsnag", "com.microsoft.appcenter", "com.facebook", "com.flurry",
                 "com.amplitude", "com.mixpanel", "com.google.android.play", "com.google.android.datatransport", "com.adjust", "com.appsflyer", "com.onesignal", "com.segment")
OPEN_LICENCE_WORDS = ("apache", "mit license", "the mit", "bsd", "gpl", "lgpl", "mpl", "mozilla", "eclipse public", "epl", "isc", "unicode", "bouncy castle", "public domain", "cc0", "creative commons", "zlib", "bouncycastle")
# POM licence lookups that are missing or non-standard in the artifact's POM (verified against the project pages, 2026-11-12)
LICENCE_FALLBACK = {
    "org.bouncycastle:bcprov-jdk18on": "Bouncy Castle Licence (MIT-style, OSI-listed as MIT)",
}


def check_dependencies(deps, licences):
    """deps: ['group:artifact:version']; licences: {'group:artifact': licence text or ''}."""
    problems, details = [], []
    for d in deps:
        g, a, v = d.split(":")[:3]
        if any(g == p or g.startswith(p + ".") for p in DENIED_GROUPS):
            problems.append("denied dependency group: " + d)
        lic = licences.get(g + ":" + a, "") or LICENCE_FALLBACK.get(g + ":" + a, "")
        if not lic:
            problems.append("no licence found for " + d)
        elif not any(w in lic.lower() for w in OPEN_LICENCE_WORDS):
            problems.append("licence not recognised as open source for %s: %s" % (d, lic))
        details.append("%s  %s" % (d, lic or "UNKNOWN"))
    return verdict("S9", "Dependencies: none from the tracker/analytics/Play-services denylist, each with an open-source licence", problems,
                   "%d runtime dependencies, no denylisted group, every licence recognised" % len(deps), details)


def check_supply_chain(root, wrapper_props, verification_xml):
    problems = []
    if not re.search(r"^distributionSha256Sum=[0-9a-f]{64}$", wrapper_props, re.M):
        problems.append("gradle-wrapper.properties has no distributionSha256Sum")
    try:
        tree = ET.fromstring(verification_xml)
        ns = {"g": "https://schema.gradle.org/dependency-verification"}
        enabled = tree.find("g:configuration/g:verify-metadata", ns)
        if enabled is None or enabled.text.strip() != "true":
            problems.append("verify-metadata is not true")
        comps = tree.findall("g:components/g:component", ns)
        weak = [c.get("name") for c in comps if not c.findall(".//g:sha256", ns)]
        problems += ["component without sha256: " + w for w in weak[:5]]
        if tree.findall("g:configuration/g:trusted-artifacts/*", ns):
            problems.append("trusted-artifacts disables verification for some artifacts")
        n = len(comps)
    except ET.ParseError as e:
        problems.append("verification-metadata.xml unreadable: %s" % e)
        n = 0
    return verdict("S10", "Supply chain: Gradle sha256 dependency verification is strict, the wrapper is pinned by checksum", problems,
                   "verify-metadata on, %d components all with sha256, no trusted-artifacts, wrapper distributionSha256Sum set" % n)


# ---------------------------------------------------------------- S11 next-word models
def load_tokenizer_lock(text):
    """tools/nextword/tokenizers.lock: asset path -> (size, sha256)."""
    out = {}
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        path, size, sha, _ = line.split("\t")
        out[path] = (int(size), sha)
    return out


def check_models(entries, lock):
    """entries: {APK entry name: (size, sha256)} of every entry. No model file is in the APK (the models are downloads the user picks, pinned in
    tools/downloads.lock), and the tokenizer files under assets/nextword are exactly the pinned ones of tools/nextword/tokenizers.lock."""
    problems = sorted("a model file is in the APK: %s" % n for n in entries if n.lower().endswith((".onnx", ".onnx_data", ".ort", ".gguf", ".tflite")))
    shipped = {n[len("assets/"):]: v for n, v in entries.items() if n.startswith("assets/nextword/")}
    for path, (size, sha) in sorted(lock.items()):
        got = shipped.get(path)
        if got is None:
            problems.append("pinned tokenizer file missing from the APK: assets/%s" % path)
        elif got != (size, sha):
            problems.append("tokenizer file differs from tools/nextword/tokenizers.lock: assets/%s" % path)
    problems += sorted("unpinned file under assets/nextword: assets/%s" % p for p in shipped if p not in lock)
    return verdict("S11", "Next-word models: no model file in the APK; the tokenizer files are exactly the pinned ones", problems,
                   "no model file among %d entries; %d pinned tokenizer files match" % (len(entries), len(lock)))
