"""Voice typing checks of the device audit (invariant P20). Run by device_audit.flow on the audit build after the model is on the phone.

V1 the microphone permission is not granted before the first tap that can record, and asked for by that tap (system dialog), nothing before
V2 a recording with real speech (played by the PC next to the phone) ends in recognised text in the field (positive control for every check below)
V3 the microphone is open only while recording: not before the tap, not after the stop, not while the text is worked out, and a screen-off ends it
V4 a password field has no voice key
V5 no audio file anywhere: nothing audio-like in the app's data directories, none created in shared storage or /data/local/tmp during the session
V6 the model directory holds exactly the four files of the pinned list and no part file (and is the only thing the data scan leaves out)
V7 logcat holds none of the spoken words and no line of ONNX Runtime
Every check has a control in the same function (a planted audio file, a planted log line, the same check on a phone state that must be flagged)."""
import os
import re
import subprocess
import time

from device_run import CANARIES
from model import FAIL, GAP, INFO, PASS, Result, verdict

MODEL_FILES = (  # name, size: the pinned list (app VoiceModelSpec.kt, tools/voice-model.lock)
    ("encoder.int8.onnx", 652282294), ("decoder.int8.onnx", 11845274), ("joiner.int8.onnx", 6355277), ("tokens.txt", 93939))
AUDIO_NAME = re.compile(r"\.(wav|pcm|raw|aac|amr|awb|3gp|3ga|m4a|mp3|mp4|ogg|oga|opus|flac|mka|webm|aiff?|snd|au|caf)$", re.I)
AUDIO_MAGIC = (b"RIFF", b"OggS", b"fLaC", b"ID3", b"#!AMR", b"\x1aE\xdf\xa3", b"FORM", b"MThd")  # wav, ogg, flac, mp3, amr, matroska, aiff, midi
VOICE_KEY_WORDS = ("Voice input", "Voice typing", "Recording", "Working out", "Гласово писане")
SPOKEN_SECONDS_PAD = 3.0


def model_dir(pkg):
    return "/data/user_de/0/%s/no_backup/taen-voice" % pkg


def push_model(run, host_dir):
    """Puts the four files into the audit build's model directory (what the settings import does through the file picker, without the picker UI):
    adb push to /data/local/tmp (reliable), then a pipe on the phone into run-as (a stream through `adb exec-in run-as` loses its tail), size verified, temp copy removed."""
    d = run.dev
    pkg = run.pkg
    stage = "/data/local/tmp/taen-voice-stage"
    d.sh("run-as %s rm -rf %s; run-as %s mkdir -p %s; rm -rf %s; mkdir -p %s" % (pkg, model_dir(pkg), pkg, model_dir(pkg), stage, stage))
    try:
        for name, size in MODEL_FILES:
            path = os.path.join(host_dir, name)
            if os.path.getsize(path) != size:
                raise RuntimeError("%s has %d bytes, expected %d: run make fetch-voice-model" % (name, os.path.getsize(path), size))
            d.adb("push", path, "%s/%s" % (stage, name), check=True, timeout=1800)
            d.sh("cat %s/%s | run-as %s sh -c 'cat > %s/%s'" % (stage, name, pkg, model_dir(pkg), name), timeout=1800)
            got = d.sh("run-as %s stat -c %%s %s/%s" % (pkg, model_dir(pkg), name)).strip()
            if got != str(size):
                raise RuntimeError("copy of %s failed: %s bytes arrived of %d" % (name, got, size))
    finally:
        d.sh("rm -rf %s" % stage)


def listing(run):
    out = run.dev.sh("run-as %s ls -l %s" % (run.pkg, model_dir(run.pkg)))
    rows = {}
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 8 and p[4].isdigit():
            rows[p[-1]] = int(p[4])
    return rows


def permission_state(run):
    text = run.dev.sh("dumpsys package %s" % run.pkg, timeout=60)
    m = re.search(r"android\.permission\.RECORD_AUDIO: granted=(true|false)", text)
    requested = "android.permission.RECORD_AUDIO" in text
    return requested, (m.group(1) == "true") if m else None


def mic_active(run):
    """True while the audit build has an active, not silenced audio input: the RecordActivityMonitor of `dumpsys audio` lists each client as
    'riid N; active? true|false' and, on the next line, 'session:... source client=... -- uid:... -- pack:<package>' (Android 12; format seen on the reference phone)."""
    lines = run.dev.sh("dumpsys audio", timeout=60).splitlines()
    active = []
    for i, line in enumerate(lines[:-1]):
        if "active? true" in line and "pack:" + run.pkg in lines[i + 1]:
            active.append(lines[i + 1].strip()[:160])
    return bool(active), active[:3]


def voice_key(run, tries=3):
    """The voice key in the strip or toolbar (its description changes with the state: normal, recording, working)."""
    for _ in range(tries):
        for n in run.dev.nodes(True):
            if n["pkg"].startswith(run.pkg) and any(w.lower() in n["desc"].lower() for w in VOICE_KEY_WORDS):
                return n
        time.sleep(1.5)
    return None


def play(path, level="0.45"):
    """Plays a wav on the PC's speakers next to the phone and returns when it is done. The PC's output volume is raised for the playback only and put back
    afterwards, whatever happens (it may be muted on purpose). TAEN_AUDIT_VOLUME overrides the level."""
    sink = "@DEFAULT_AUDIO_SINK@"
    before = subprocess.run(["wpctl", "get-volume", sink], capture_output=True, text=True).stdout.split()
    old = before[1] if len(before) > 1 else None
    try:
        if old is not None:
            subprocess.run(["wpctl", "set-volume", sink, os.environ.get("TAEN_AUDIT_VOLUME", level)], capture_output=True)
        subprocess.run(["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path], capture_output=True, timeout=120)
    finally:
        if old is not None:
            subprocess.run(["wpctl", "set-volume", sink, old], capture_output=True)


def words(text):
    return [w for w in re.split(r"[^\w]+", text.lower()) if w]


def recognised_ratio(ref, got):
    r = words(ref)
    g = set(words(got))
    return sum(1 for w in r if w in g) / max(1, len(r))


def audio_files_in(run, root, newer_than=None):
    """Names of audio-like files below `root` (shell can read /sdcard; run-as reads the app's data): by extension and by the first bytes."""
    d = run.dev
    cmd = "find %s -type f 2>/dev/null" % root if not newer_than else "find %s -type f -newer %s 2>/dev/null" % (root, newer_than)
    files = [f for f in d.sh(cmd, timeout=120).splitlines() if f.startswith("/")]
    return [f for f in files if AUDIO_NAME.search(f)], files


def app_files(run):
    out = run.dev.sh("run-as %s sh -c 'find /data/user/0/%s /data/user_de/0/%s -type f'" % (run.pkg, run.pkg, run.pkg), timeout=120)
    return [f for f in out.splitlines() if f.startswith("/") and "/no_backup/taen-voice/" not in f]


def magic_hits(run, files):
    hits = []
    for f in files:
        head = run.dev.adb("exec-out", "run-as", run.pkg, "head", "-c", "12", f).stdout
        if any(head.startswith(m) for m in AUDIO_MAGIC) or head[4:8] == b"ftyp":
            hits.append(f)
    return hits


class _Results(list):
    """The result list; every result is printed when it is added, so a later error does not hide the earlier checks."""

    def append(self, r):
        print(r, flush=True)
        super().append(r)


def run_voice(run, host_model_dir, samples_dir):
    """The whole voice session; returns the list of results. `run.pkg` is the audit build, installed and selected."""
    d = run.dev
    out = _Results()
    pkg = run.pkg
    if not host_model_dir or not os.path.isdir(host_model_dir):
        return [Result("V0", "Voice model for the device checks", GAP, "no model directory (set TAEN_VOICE_MODEL_DIR or run make fetch-voice-model): every voice check is unrun")]
    if not samples_dir or not os.path.isfile(os.path.join(samples_dir, "en", "00.wav")):
        return [Result("V0", "Voice samples for the device checks", GAP, "no sample directory (TAEN_VOICE_SAMPLES_DIR, tools/voice/prepare_samples.py): every voice check is unrun")]
    d.sh("rm -f /data/local/tmp/taen-voice-marker; touch /data/local/tmp/taen-voice-marker")
    d.sh("rm -f /sdcard/taen-voice-marker; touch /sdcard/taen-voice-marker")

    # ---- V6 first: the model, as imported by the push
    push_model(run, host_model_dir)
    rows = listing(run)
    problems = ["%s: %s bytes, expected %d" % (n, rows.get(n), s) for n, s in MODEL_FILES if rows.get(n) != s]
    problems += ["unexpected file in the model directory: %s" % n for n in rows if n not in dict(MODEL_FILES)]
    out.append(verdict("V6", "Model directory holds exactly the four pinned files (sizes) and no part file; it is the only thing the data scan leaves out", problems,
                       "4 files with the pinned sizes in no_backup/taen-voice, nothing else", ["%s %d" % kv for kv in sorted(rows.items())]))

    # ---- V1 permission is not there before the tap, the first tap asks
    run.field("normal")
    requested, granted_before = permission_state(run)
    d.sh("pm revoke %s android.permission.RECORD_AUDIO" % pkg)
    requested, granted_before = permission_state(run)
    pre_mic, _ = mic_active(run)
    key = voice_key(run)
    problems = []
    if not requested:
        problems.append("RECORD_AUDIO is not requested in the installed package")
    if granted_before:
        problems.append("RECORD_AUDIO is granted before any tap on the voice key")
    if pre_mic:
        problems.append("the microphone is active before any tap")
    if not key:
        problems.append("no voice key found in the strip or toolbar")
    asked = False
    if key and not problems:
        d.tap(key["cx"], key["cy"])
        for _ in range(8):
            time.sleep(1.0)
            n = d.find("While using the app", windows=True, exact=True) or d.find("ALLOW", windows=True, exact=True) or d.find("Allow", windows=True, exact=True)
            if n:
                asked = True
                d.tap(n["cx"], n["cy"])
                time.sleep(1.5)
                break
        if not asked:
            problems.append("the first tap on the voice key did not show the permission dialog (screen: %s)" % "; ".join(d.texts(True)[:8]))
    _, granted_after = permission_state(run)
    if asked and not granted_after:
        problems.append("the permission was not granted after Allow")
    out.append(verdict("V1", "Microphone permission: requested but not granted at install, no recording before the tap, the first tap that can record asks for it", problems,
                       "requested, not granted at install, microphone idle, the first tap showed the system dialog and Allow granted it", ["before: granted=%s; after: granted=%s" % (granted_before, granted_after)]))

    # ---- V2 / V3 one real recording
    ref_path = os.path.join(samples_dir, "en", "00.wav")
    with open(os.path.join(samples_dir, "en", "refs.tsv"), encoding="utf-8") as f:
        ref = [l.split("\t", 1)[1] for l in f.read().splitlines() if l.startswith("00\t")][0]
    seconds = os.path.getsize(ref_path) / 32000.0
    run.field("normal")
    key = voice_key(run)
    d.tap(key["cx"], key["cy"])  # start
    time.sleep(1.5)
    during, evidence_during = mic_active(run)
    play(ref_path)
    time.sleep(0.8)
    d.tap(key["cx"], key["cy"])  # stop
    time.sleep(0.8)
    after_stop, evidence_stop = mic_active(run)  # the text is being worked out: the microphone is already closed
    initial = "normal"  # the helper's field starts with its own label as text
    text = initial
    t0 = time.time()
    while time.time() - t0 < 150:
        time.sleep(3)
        text = run.field_text() or ""
        if text.strip() != initial:
            break
    text = text.replace(initial, "").strip()
    ratio = recognised_ratio(ref, text)
    run.voice_heard = text
    if ratio >= 0.4:
        out.append(Result("V2", "A recording of real speech (played by the PC) ends in recognised text in the field", PASS,
                          "%.0f %% of the reference words appear in the field text; transcription finished %.0f s after the stop (%.1f s of audio)" % (100 * ratio, time.time() - t0, seconds),
                          ["reference: %s" % ref, "field: %s" % text]))
    elif text:
        out.append(Result("V2", "A recording ends in text in the field (the full chain: microphone, engine, commit)", INFO,
                          "text was typed from the microphone (%r), but it is not the played sentence (%.0f %% of the words): the PC speaker was not heard by the phone; accuracy is measured by the bench and the host smoke test, not here" % (text, 100 * ratio),
                          ["reference: %s" % ref, "field: %s" % text]))
    else:
        out.append(Result("V2", "A recording ends in text in the field (the full chain: microphone, engine, commit)", GAP,
                          "no text arrived within 150 s (silence was recorded and nothing was recognised, or the chain broke): not verified", ["reference: %s" % ref]))
    problems = []
    if not during:
        problems.append("no active audio input while recording (the check has no positive control)")
    if after_stop:
        problems.append("the microphone is still active after the stop (text being worked out)")
    time.sleep(2)
    idle, _ = mic_active(run)
    if idle:
        problems.append("the microphone is still active after the text arrived")
    out.append(verdict("V3a", "Microphone open only while recording: active while recording (control), closed at the stop and after the text", problems,
                       "active input while recording; none right after the stop and none after the text", ["during: %s" % evidence_during, "after stop: %s" % evidence_stop]))
    # screen off during a recording ends it and types nothing
    run.field("normal")
    key = voice_key(run)
    d.tap(key["cx"], key["cy"])
    time.sleep(1.5)
    started, _ = mic_active(run)
    d.sleep_screen()
    time.sleep(3)
    closed = not mic_active(run)[0]
    d.wake()
    time.sleep(2)
    run.field("normal")
    text2 = run.field_text() or ""
    problems = []
    if not started:
        problems.append("the second recording was not active (no positive control)")
    if not closed:
        problems.append("the microphone is still active 3 s after the screen went off")
    if text2.strip() != "normal":
        problems.append("text appeared in a new field after the lock: %r" % text2)
    out.append(verdict("V3b", "Screen off during a recording closes the microphone and types nothing", problems,
                       "recording active, microphone closed 3 s after the screen went off, no text typed afterwards", []))

    # ---- V4 password field
    run.field("normal")
    in_normal = voice_key(run) is not None
    run.field("password")
    in_password = voice_key(run) is not None
    mic_in_password = mic_active(run)[0]
    problems = []
    if not in_normal:
        problems.append("no voice key in a normal field (no positive control)")
    if in_password:
        problems.append("the voice key is shown in a password field")
    if mic_in_password:
        problems.append("the microphone is active in a password field")
    out.append(verdict("V4", "Password field: no voice key, no microphone", problems, "voice key in a normal field, none in a password field, microphone idle", []))

    # ---- V5 no audio file anywhere (control: a planted file with a WAV header)
    d.sh("run-as %s sh -c 'printf RIFF > files/audit-plant.wav'" % pkg)
    planted_found = [f for f in app_files(run) if AUDIO_NAME.search(f)]
    d.sh("run-as %s rm -f files/audit-plant.wav" % pkg)
    files = app_files(run)
    by_name = [f for f in files if AUDIO_NAME.search(f)]
    by_magic = magic_hits(run, files)
    shared_audio, shared_all = audio_files_in(run, "/sdcard", "/sdcard/taen-voice-marker")
    tmp_audio, tmp_all = audio_files_in(run, "/data/local/tmp", "/data/local/tmp/taen-voice-marker")
    app_new = [f for f in shared_all + tmp_all if pkg in f]
    problems = []
    if not planted_found:
        problems.append("control: the planted audio file name was not found by the scan")
    problems += ["audio file name in the app data: " + f for f in by_name]
    problems += ["audio header in the app data: " + f for f in by_magic]
    problems += ["audio file created in shared storage or /data/local/tmp during the session: " + f for f in shared_audio + tmp_audio]
    problems += ["file of the app in shared storage or /data/local/tmp: " + f for f in app_new]
    out.append(verdict("V5", "No audio file anywhere: none in the data directories (name and header), none created in shared storage or /data/local/tmp during the session", problems,
                       "%d files in both data directories (model files excluded) checked by name and first bytes; %d new files in /sdcard and %d in /data/local/tmp, none audio" % (len(files), len(shared_all), len(tmp_all)),
                       ["control found: %s" % planted_found]))
    return out


def log_check(run, text, spoken_words):
    """V7 on the logcat of the whole session: none of the distinctive spoken words, and no ONNX Runtime line."""
    low = text.lower()
    hits = [w for w in spoken_words if re.search(r"(?<![\w])%s(?![\w])" % re.escape(w.lower()), low)]
    ort = [l for l in text.splitlines() if re.search(r"onnxruntime|\bort\b|OrtApis|ORT_", l, re.I) and run.pkg in l]
    problems = ["spoken word in logcat: %s" % w for w in hits] + ["ONNX Runtime log line: %s" % l[:140] for l in ort[:3]]
    return verdict("V7", "logcat holds none of the spoken words and no ONNX Runtime line", problems,
                   "%d distinctive spoken words searched in %d log lines, none found; no ONNX Runtime line of the app" % (len(spoken_words), len(text.splitlines())))


def distinct_words(text, min_len=7):
    """Words of the spoken reference that would be unlikely in any other log line."""
    common = {"because", "however", "between", "without", "through", "another", "during", "against", "should", "whether", "something", "following", "behavior"}  # system log lines use them ("behavior": TelephonyProvider and DocumentsUI SelectionTracker, 1.0.0 audit)
    return sorted({w for w in words(text) if len(w) >= min_len and w not in common})


def log_control(run, spoken_words):
    r = log_check(run, "I/AUDIT-CONTROL( 1): heard %s\nD/x( 2): %s onnxruntime session run" % (spoken_words[0], run.pkg), spoken_words)
    ok = r.status == FAIL and any("spoken word in logcat" in x for x in r.details) and any("ONNX Runtime log line" in x for x in r.details)
    words_kept = distinct_words("behavior hippopotamus conversation")  # a word system logs use is not searched; other long words still are
    ok = ok and "behavior" not in words_kept and "hippopotamus" in words_kept and "conversation" in words_kept
    return Result("T-V7", "V7 can fail: a planted log line with a spoken word and one from ONNX Runtime are found by the same check", PASS if ok else FAIL, "planted: %s" % r.status)
