# SPDX-License-Identifier: GPL-3.0-only
"""Tests for scripts/public-check and the git hooks.

Forbidden words are never written out in this file: every sample is joined from
fragments at run time, so the repository check passes on this file too.
"""
import atexit
import importlib.machinery
import importlib.util
import os
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FIXTURES = os.path.join(HERE, "fixtures")
TOOL_FILES = [
    "scripts/public-check",
    "scripts/public-check.patterns",
    "scripts/public-check.allow",
    "scripts/public-check.licences",
    "scripts/setup-hooks",
]
HOOK_FILES = ["githooks/pre-commit", "githooks/commit-msg", "githooks/pre-push"]
BASELINE = "scripts/public-check.licences"


def j(*parts):
    return "".join(parts)


TASK_ID = j("T", "-", "000123")
CO_AUTHOR = j("Co-Authored", "-By: Some Tool <noreply@example.org>")
GENERATED = j("Generated", " with a tool")
SESSION = j("Cla", "ude-Session: https://example.org/x")
CLEAN_ENV = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null")


def git(repo, *args, check=True, env=None):
    full_env = dict(CLEAN_ENV)
    full_env.update(env or {})
    return subprocess.run(
        ["git", "-C", repo, "-c", "user.name=Test Person", "-c", "user.email=test@example.org", *args],
        capture_output=True, text=True, check=check, env=full_env,
    )


def write(repo, rel, text):
    dst = os.path.join(repo, rel)
    os.makedirs(os.path.dirname(dst) or repo, exist_ok=True)
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(text)


def disk_temp_base():
    """Test repositories go on disk: the system temp dir is RAM-backed on some machines."""
    return os.environ.get("TAEN2_TEST_TMP") or os.path.join(os.path.expanduser("~"), ".cache", "taen2-test-tmp")


_TEMP_DIRS = []
atexit.register(lambda: [shutil.rmtree(d, ignore_errors=True) for d in _TEMP_DIRS])


def _terminate(signum, frame):
    raise SystemExit(128 + signum)  # runs the atexit cleanup


for _sig in (signal.SIGTERM, signal.SIGHUP):
    signal.signal(_sig, _terminate)  # an interrupt already raises KeyboardInterrupt, which also reaches atexit


def temp_dir(prefix):
    base = disk_temp_base()
    os.makedirs(base, exist_ok=True)
    path = tempfile.mkdtemp(prefix=prefix, dir=base)
    _TEMP_DIRS.append(path)
    return path


def make_repo(files, hooks=False):
    repo = temp_dir("pc-test-")
    git(repo, "init", "-q", "-b", "main")
    for rel in TOOL_FILES + (HOOK_FILES if hooks else []):
        dst = os.path.join(repo, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(os.path.join(ROOT, rel), dst)
        if rel == BASELINE:
            # a test repository starts with an empty baseline: the real one records the lines of this repository's files
            with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
                header = "".join(line for line in fh if line.startswith("#"))
            with open(dst, "w", encoding="utf-8") as fh:
                fh.write(header)
    for rel, text in files.items():
        write(repo, rel, text)
    if hooks:
        git(repo, "config", "core.hooksPath", "githooks")
    git(repo, "add", "-A")
    return repo


def check(repo, *args):
    return subprocess.run(
        ["python3", "-I", os.path.join(repo, "scripts/public-check"), *args],
        cwd=repo, capture_output=True, text=True, env=CLEAN_ENV,
    )


def commit(repo, message, *extra, env=None):
    git(repo, "add", "-A")
    return git(repo, "commit", "-q", "-m", message, *extra, check=False, env=env)


def head(repo):
    return git(repo, "rev-parse", "HEAD").stdout.strip()


def fixture(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as fh:
        return fh.read()


class CoreTest(unittest.TestCase):
    def test_task_id_is_reported_with_file_and_line(self):
        repo = make_repo({"a.txt": "fine\nsee " + TASK_ID + " here\n"})
        r = check(repo)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("a.txt:2: [task-id] " + TASK_ID, r.stdout)

    def test_clean_repository_passes(self):
        repo = make_repo({"a.txt": "fine\nnothing to see\n"})
        r = check(repo)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class PatternClassTest(unittest.TestCase):
    """Every class is caught in file text; the sample sits on line 3 of a file."""

    SAMPLES = {
        "task-id": [TASK_ID],
        "session-url": [j("https://", "cla", "ude.ai/code/session_abc"), j("chat", "gpt.com/c/1"), j("chat.", "open", "ai.com/x"),
                        j("https://www.", "anthro", "pic.com/"), j("gem", "ini.google.com/app"), j("aistu", "dio.google.com/x")],
        "ai-tool": [j("Cap", "tain"), j("Cla", "ude"), j("Cod", "ex"), j("Op", "us"), j("Son", "net"), j("Hai", "ku"),
                    j("Chat", "GPT"), j("Co", "pilot"), j("Gem", "ini"), j("Anthro", "pic"), j("Open", "AI"), j("GP", "T-4")],
        "ai-phrase": [j("AI", "-generated"), j("generated by an ", "AI"), j("written with ", "AI"), j("as an ", "AI"), j("vibe ", "coding")],
        "agent-file": [j("AGEN", "TS.md"), j("CLAU", "DE.md"), j(".cla", "ude/settings.json"), j(".co", "dex/config"),
                       j("~/.cap", "tain/tasks"), j("copi", "lot-instructions"), j(".cursor", "rules")],
        "person-word": [j("the fou", "nder said")],
        "process-word": [j("review ", "round 2"), j("the review", "er"), j("the veri", "fier"), j("blocking ", "finding"),
                         j("design ", "section 4"), j("evid", "ence/foo"), j("not tested by the buil", "der"),
                         j("the ow", "ner's sequence")],
        "test-label": [j("// pa", "ss 1"), j("# pa", "ss 2"), j("/* pa", "ss 1 */")],
        "device-serial": [j("--ser", "ial 9876543210AB"), j("adb -", "s R5CT1234ABC exec-out"), j("ANDROID_", "SERIAL=ab12cd34ef56"),
                          j("adb -e -", "s 0123456789ABCDEF shell")],
        "internal-path": [j("/ho", "me/someone/x"), j("/Us", "ers/someone"), j("C:\\", "Users\\x"), j("~/.ca", "che/t615-guard"),
                          j("x/.ca", "che/t6")],
    }

    def test_every_class_is_caught_in_text(self):
        for cls, samples in self.SAMPLES.items():
            for sample in samples:
                with self.subTest(cls=cls, sample=sample):
                    repo = make_repo({"f.txt": "one\ntwo\nx %s y\n" % sample})
                    r = check(repo)
                    self.assertEqual(r.returncode, 1, r.stdout)
                    self.assertIn("f.txt:3: [%s]" % cls, r.stdout)

    def test_every_pattern_is_hit_by_a_sample_through_its_speed_gate(self):
        loader = importlib.machinery.SourceFileLoader("public_check_module", os.path.join(ROOT, "scripts/public-check"))
        module = importlib.util.module_from_spec(importlib.util.spec_from_loader("public_check_module", loader))
        loader.exec_module(module)
        patterns = module.load_patterns(os.path.join(ROOT, "scripts/public-check.patterns"))
        message_samples = [CO_AUTHOR, GENERATED, SESSION, "\U0001F916 made"]
        for p in patterns:
            samples = message_samples if p.scope == "msg" else [s for v in self.SAMPLES.values() for s in v]
            hit = [s for s in samples if p.rx.search(s) and any(f[1] == p.cls for f in module.scan_text(s, [p]))]
            with self.subTest(cls=p.cls, regex=p.source):
                self.assertTrue(hit, "no sample reaches this pattern through its hints")
                for s in hit:
                    self.assertTrue(any(h in s.casefold() for h in p.hints) or not p.hints, s)

    def test_file_names_are_checked_including_binary_files(self):
        names = [j("AGEN", "TS.md"), j("CLAU", "DE.md"), j(".cla", "ude/settings.json"), j(".co", "dex/c.toml"),
                 j(".cap", "tain/x.json"), "notes-" + TASK_ID + ".txt"]
        for name in names:
            with self.subTest(name=name):
                repo = make_repo({name: "plain\n"})
                r = check(repo)
                self.assertEqual(r.returncode, 1, r.stdout)
                self.assertIn(name + ":0:", r.stdout)
                self.assertIn("/file-name]", r.stdout)

    def test_binary_content_is_skipped_but_its_name_is_not(self):
        repo = make_repo({})
        with open(os.path.join(repo, "blob.bin"), "wb") as fh:
            fh.write(b"\0\0" + TASK_ID.encode() + b"\0")
        git(repo, "add", "-A")
        self.assertEqual(check(repo).returncode, 0)
        os.rename(os.path.join(repo, "blob.bin"), os.path.join(repo, TASK_ID + ".bin"))
        git(repo, "add", "-A")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn(TASK_ID + ".bin:0:", r.stdout)

    def test_trailer_patterns_apply_to_messages_only(self):
        repo = make_repo({"notes.txt": "%s\n%s\n" % (CO_AUTHOR, GENERATED)})
        r = check(repo)
        self.assertEqual(r.returncode, 0, "trailer wording is allowed in file text: " + r.stdout)
        for text in (CO_AUTHOR, GENERATED, SESSION, "\U0001F916 made"):
            with self.subTest(text=text):
                write(repo, "msg.txt", "subject\n\nbody\n%s\n" % text)
                r = check(repo, "--text", "msg.txt")
                self.assertEqual(r.returncode, 1, r.stdout)
                self.assertIn("msg.txt:4: [trailer]", r.stdout)


class SerialAndWordingTest(unittest.TestCase):
    """Device serials in usage examples and wording that names a person of the process."""

    CLEAN = ["--serial SERIAL", "--serial <serial>", "--serial $SERIAL", "adb -s $DEVICE shell", "adb -s emulator-5554 shell",
             "the serial number of the phone", "ANDROID_SERIAL=$X", "adb -s abcdefghij shell",
             "bypass 1 of the test", "pass 1 of the rows", "// the pass count", "tested by the build script", "the owner of the file"]

    def test_literal_serials_are_reported_and_placeholders_are_not(self):
        for text in self.CLEAN:
            with self.subTest(clean=text):
                self.assertEqual(check(make_repo({"f.txt": "x\n%s\n" % text})).returncode, 0, text)
        for text in PatternClassTest.SAMPLES["device-serial"]:
            with self.subTest(bad=text):
                r = check(make_repo({"f.txt": "x\n%s\n" % text}))
                self.assertEqual(r.returncode, 1, r.stdout)
                self.assertIn("f.txt:2: [device-serial]", r.stdout)

    def test_test_pass_labels_and_person_wording_are_reported(self):
        for cls in ("test-label",):
            for text in PatternClassTest.SAMPLES[cls]:
                with self.subTest(text=text):
                    r = check(make_repo({"T.kt": "fun a() {}\n@Test fun x() { %s\n}\n" % text}))
                    self.assertEqual(r.returncode, 1, r.stdout)
                    self.assertIn("[%s]" % cls, r.stdout)
        r = check(make_repo({"T.kt": "/**\n * " + j("pa", "ss 2") + "\n */\nclass T\n"}))
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("T.kt:2: [test-label]", r.stdout)
        for text in PatternClassTest.SAMPLES["process-word"][-2:]:
            with self.subTest(text=text):
                r = check(make_repo({"f.md": "one\n%s\n" % text}))
                self.assertEqual(r.returncode, 1, r.stdout)
                self.assertIn("f.md:2: [process-word]", r.stdout)


class ExtraPatternsTest(unittest.TestCase):
    """Patterns kept outside the tree (the git directory), for words that must never be written into the repository."""

    SECRET = j("ZQ", "7731-private")

    def extra(self, repo, text):
        os.makedirs(os.path.join(repo, ".git", "info"), exist_ok=True)
        write(repo, ".git/info/public-check-extra", text)

    def line(self):
        return "private-word  all  zq7731  \\b%s\\b\n" % self.SECRET.replace("-", "[-]")

    def test_a_word_from_the_local_file_is_reported_in_tree_name_message_and_history(self):
        repo = make_repo({"f.txt": "one\ntwo %s\n" % self.SECRET, "keep.txt": "x\n"})
        self.assertEqual(check(repo).returncode, 0, "without the local file the text is not a finding")
        self.extra(repo, self.line())
        r = check(repo)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("f.txt:2: [private-word]", r.stdout)
        write(repo, "f.txt", "clean\n")
        write(repo, self.SECRET + ".txt", "x\n")
        git(repo, "add", "-A")
        self.assertIn("[private-word/file-name]", check(repo).stdout)
        write(repo, "msg.txt", "subject %s\n" % self.SECRET)
        self.assertIn("msg.txt:1: [private-word]", check(repo, "--text", "msg.txt").stdout)

    def test_history_is_scanned_with_the_local_file(self):
        repo = make_repo({"f.txt": "%s\n" % self.SECRET})
        self.assertEqual(commit(repo, "first").returncode, 0)
        write(repo, "f.txt", "clean\n")
        self.assertEqual(commit(repo, "second").returncode, 0)
        self.assertEqual(check(repo, "--history", "HEAD").returncode, 0)
        self.extra(repo, self.line())
        self.assertEqual(check(repo).returncode, 0, "the tip tree is clean")
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("f.txt:1: [private-word]", r.stdout)

    def test_the_local_file_may_be_empty_or_commented_but_not_malformed(self):
        repo = make_repo({"f.txt": "fine\n"})
        self.extra(repo, "# nothing yet\n")
        self.assertEqual(check(repo).returncode, 0)
        self.extra(repo, "private-word all\n")
        r = check(repo)
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("public-check-extra", r.stderr)

    def test_an_unreadable_local_file_is_an_error_not_a_pass(self):
        repo = make_repo({"f.txt": "fine\n"})
        os.makedirs(os.path.join(repo, ".git", "info", "public-check-extra"))
        r = check(repo)
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)

    def test_the_extra_option_names_a_file_outside_the_repository(self):
        repo = make_repo({"f.txt": "x %s\n" % self.SECRET})
        outside = os.path.join(temp_dir("pc-extra-"), "extra")
        with open(outside, "w", encoding="utf-8") as fh:
            fh.write(self.line())
        self.assertEqual(check(repo).returncode, 0)
        r = check(repo, "--extra", outside)
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("f.txt:1: [private-word]", r.stdout)


class NegativeControlTest(unittest.TestCase):
    def test_gpl_text_heliboard_headers_and_kotlin_pass(self):
        repo = make_repo({
            "COPYING": fixture("gpl-3.0-excerpt.txt"),
            "src/Header.kt": fixture("header-apache-modified.kt"),
            "src/Sample.kt": fixture("sample.kt"),
            "README.md": "# Taen\n\nA keyboard. The cursor keys, the review of suggestions and an AI model for next words.\n",
        })
        r = check(repo)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_shipped_allow_entry_permits_the_zodiac_emoji_name_only_in_its_path(self):
        word = j("Gem", "ini")
        repo = make_repo({"app/src/main/assets/emoji_search/en.txt": "%s\n" % word})
        self.assertEqual(check(repo).returncode, 0)
        write(repo, "app/src/main/assets/other.txt", "%s\n" % word)
        git(repo, "add", "-A")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn("app/src/main/assets/other.txt:1: [ai-tool]", r.stdout)

    def test_negative_control_is_live_a_bad_word_in_the_same_tree_fails(self):
        repo = make_repo({"src/Sample.kt": fixture("sample.kt") + "// " + TASK_ID + "\n"})
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn("src/Sample.kt:21:", r.stdout)

    def test_the_pattern_and_allow_files_pass_their_own_check(self):
        repo = make_repo({})
        r = check(repo)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        for rel in TOOL_FILES:
            self.assertNotIn(rel, r.stdout)

    def test_the_real_repository_passes(self):
        r = check(ROOT)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class AllowListTest(unittest.TestCase):
    def allow_entry(self, cls, glob, rx, reason="product word"):
        return "allow\t%s\t%s\t%s\t%s\n" % (cls, glob, rx, reason)

    def test_entry_permits_only_its_class_glob_and_text(self):
        word = j("Op", "us")
        repo = make_repo({"app/audio.kt": "val c = \"%s\"\n" % word, "app/other.kt": "val c = \"%s\"\n" % word,
                          "app/names.kt": "// %s\n" % j("Cap", "tain")})
        with open(os.path.join(repo, "scripts/public-check.allow"), "a", encoding="utf-8") as fh:
            fh.write(self.allow_entry("ai-tool", "app/audio.kt", "^" + word + "$"))
        git(repo, "add", "-A")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertNotIn("app/audio.kt", r.stdout)
        self.assertIn("app/other.kt:1:", r.stdout)
        self.assertIn("app/names.kt:1:", r.stdout)

    def test_entry_for_another_class_does_not_permit(self):
        word = j("Op", "us")
        repo = make_repo({"a.kt": word + "\n"})
        with open(os.path.join(repo, "scripts/public-check.allow"), "a", encoding="utf-8") as fh:
            fh.write(self.allow_entry("task-id", "a.kt", ".*"))
        git(repo, "add", "-A")
        self.assertEqual(check(repo).returncode, 1)

    def test_malformed_entries_are_a_configuration_error(self):
        for bad in ("allow\tai-tool\ta.kt\t.*\t\n", "allow\tai-tool\ta.kt\t.*\n", "something\tx\n", "allow\tai-tool\ta.kt\t(\tbad regex\n"):
            with self.subTest(bad=bad):
                repo = make_repo({"a.txt": "fine\n"})
                with open(os.path.join(repo, "scripts/public-check.allow"), "a", encoding="utf-8") as fh:
                    fh.write(bad)
                r = check(repo)
                self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
                self.assertIn("allow-list line", r.stderr)

    def test_bad_pattern_file_is_a_configuration_error(self):
        for bad in ("task-id all (\n", "task-id some x\n", "justone\n"):
            with self.subTest(bad=bad):
                repo = make_repo({"a.txt": "fine\n"})
                with open(os.path.join(repo, "scripts/public-check.patterns"), "a", encoding="utf-8") as fh:
                    fh.write(bad)
                r = check(repo)
                self.assertEqual(r.returncode, 2, r.stdout + r.stderr)


class MessageTest(unittest.TestCase):
    def test_commit_msg_file_comment_lines_are_ignored_but_text_is_checked(self):
        repo = make_repo({})
        write(repo, "m.txt", "Add key\n\n# " + TASK_ID + " in a git comment line\nbody\n")
        self.assertEqual(check(repo, "--commit-msg", "m.txt").returncode, 0)
        write(repo, "m.txt", "Add key\n\n# comment\n" + TASK_ID + " in the body\n")
        r = check(repo, "--commit-msg", "m.txt")
        self.assertEqual(r.returncode, 1)
        self.assertIn("m.txt:4: [task-id]", r.stdout)

    def test_text_mode_keeps_markdown_heading_lines(self):
        repo = make_repo({})
        write(repo, "pr.txt", "# " + j("Cla", "ude") + " wrote this\n")
        r = check(repo, "--text", "pr.txt")
        self.assertEqual(r.returncode, 1)
        self.assertIn("pr.txt:1: [ai-tool]", r.stdout)

    def test_clean_message_passes(self):
        repo = make_repo({})
        write(repo, "m.txt", "Add the swipe handler\n\nMoves the cursor by one word.\n")
        self.assertEqual(check(repo, "--commit-msg", "m.txt").returncode, 0)


class HistoryTest(unittest.TestCase):
    def test_removed_file_and_bad_message_stay_visible_in_history(self):
        repo = make_repo({"ok.txt": "fine\n"})
        self.assertEqual(commit(repo, "first").returncode, 0)
        write(repo, "temp.txt", "x\n" + TASK_ID + "\n")
        self.assertEqual(commit(repo, "second\n\n" + CO_AUTHOR).returncode, 0)
        bad = head(repo)
        os.remove(os.path.join(repo, "temp.txt"))
        self.assertEqual(commit(repo, "third").returncode, 0)
        self.assertEqual(check(repo).returncode, 0, "tip tree is clean")
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("%s:temp.txt:2: [task-id]" % bad[:7], r.stdout)
        self.assertIn("%s:message:3: [trailer]" % bad[:7], r.stdout)

    def test_range_limits_the_history_scan(self):
        repo = make_repo({"ok.txt": "fine\n"})
        write(repo, "bad.txt", TASK_ID + "\n")
        commit(repo, "first")
        base = head(repo)
        os.remove(os.path.join(repo, "bad.txt"))
        commit(repo, "second")
        self.assertEqual(check(repo, "--history", base + "..HEAD").returncode, 0)
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1)
        self.assertIn("bad.txt:1:", r.stdout)

    def test_author_and_committer_fields_are_checked(self):
        repo = make_repo({"ok.txt": "fine\n"})
        env = {"GIT_AUTHOR_NAME": j("Cla", "ude"), "GIT_AUTHOR_EMAIL": "x@example.org"}
        self.assertEqual(commit(repo, "first", env=env).returncode, 0)
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1)
        self.assertIn("%s:author:1: [ai-tool]" % head(repo)[:7], r.stdout)

    def test_clean_history_passes_and_merge_commits_are_walked(self):
        repo = make_repo({"ok.txt": "fine\n"})
        commit(repo, "first")
        git(repo, "checkout", "-q", "-b", "side")
        write(repo, "side.txt", "side\n")
        commit(repo, "side work")
        git(repo, "checkout", "-q", "main")
        write(repo, "main.txt", "main\n")
        commit(repo, "main work")
        self.assertEqual(git(repo, "merge", "-q", "--no-ff", "-m", "merge side", "side", check=False).returncode, 0)
        self.assertEqual(check(repo, "--history", "HEAD").returncode, 0)
        write(repo, "side2.txt", TASK_ID + "\n")
        git(repo, "checkout", "-q", "-b", "side2")
        commit(repo, "side2 work")
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "-q", "--no-ff", "-m", "merge side2", "side2")
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1)
        self.assertIn("side2.txt:1:", r.stdout)

    def test_bare_clone_with_several_trees_and_a_baseline_path(self):
        repo = make_repo({"ok.txt": "fine\n", "src/A.kt": LicenceTest.HEADER})
        check(repo, "--licence-update")
        git(repo, "add", "-A")
        commit(repo, "first")
        git(repo, "checkout", "-q", "-b", "other")
        write(repo, "bad.txt", TASK_ID + "\n")
        commit(repo, "on other")
        git(repo, "checkout", "-q", "main")
        write(repo, "src/A.kt", "package a\n")
        with open(os.path.join(repo, BASELINE), encoding="utf-8") as fh:
            kept = [l for l in fh.read().splitlines(keepends=True) if "\tmodified" not in l]
        write(repo, BASELINE, "".join(kept))
        commit(repo, "weaken the baseline and drop licence lines")
        bare = temp_dir("pc-bare-")
        subprocess.run(["git", "clone", "-q", "--bare", repo, bare], check=True, env=CLEAN_ENV, capture_output=True)
        baseline = os.path.join(repo, BASELINE)
        r = subprocess.run(
            ["python3", "-I", os.path.join(repo, "scripts/public-check"), "--tree", "refs/heads/main", "--tree", "refs/heads/other",
             "--history=--branches", "--baseline", baseline, "--baseline-repo-path", BASELINE],
            cwd=bare, capture_output=True, text=True, env=CLEAN_ENV)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("refs/heads/other:bad.txt:1: [task-id]", r.stdout)
        self.assertNotIn("refs/heads/main:bad.txt", r.stdout)
        self.assertIn("refs/heads/main:", r.stdout)
        self.assertIn("[licence-baseline] recorded line removed or lowered", r.stdout)
        self.assertIn("other", [l for l in git(bare, "branch", "--list").stdout.split() if l == "other"])

    def test_content_introduced_only_by_a_merge_resolution_is_found(self):
        repo = make_repo({"f.txt": "base\n"})
        commit(repo, "base")
        git(repo, "checkout", "-q", "-b", "side")
        write(repo, "f.txt", "side\n")
        commit(repo, "side edit")
        git(repo, "checkout", "-q", "main")
        write(repo, "f.txt", "main\n")
        commit(repo, "main edit")
        self.assertNotEqual(git(repo, "merge", "side", check=False).returncode, 0, "the merge must conflict")
        write(repo, "f.txt", "resolved with " + TASK_ID + "\n")
        git(repo, "add", "-A")
        self.assertEqual(git(repo, "commit", "-q", "-m", "merge side", check=False).returncode, 0)
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("%s:f.txt:1: [task-id]" % head(repo)[:7], r.stdout)

    def test_same_finding_in_many_commits_is_reported_once(self):
        repo = make_repo({"a.txt": TASK_ID + "\n"})
        commit(repo, "one")
        write(repo, "a.txt", TASK_ID + "\nmore\n")
        commit(repo, "two")
        write(repo, "a.txt", TASK_ID + "\nmore\nand more\n")
        commit(repo, "three")
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.stdout.count("[task-id]"), 1, r.stdout)


class LicenceTest(unittest.TestCase):
    HEADER = "// Copyright (C) 2013 The Android Open Source Project\n// modified\n// SPDX-License-Identifier: GPL-3.0-only\npackage a\n"

    def recorded_repo(self, files=None):
        repo = make_repo(files or {"src/A.kt": self.HEADER, "src/B.kt": self.HEADER.replace("2013", "2020")})
        r = check(repo, "--licence-update")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        git(repo, "add", "-A")
        self.assertEqual(check(repo).returncode, 0)
        return repo

    def baseline(self, repo):
        with open(os.path.join(repo, BASELINE), encoding="utf-8") as fh:
            return fh.read()

    def test_update_records_normalised_lines_with_counts(self):
        repo = self.recorded_repo()
        body = [l for l in self.baseline(repo).splitlines() if not l.startswith("#")]
        self.assertEqual(sorted(body), sorted([
            "1\tCopyright (C) 2013 The Android Open Source Project",
            "1\tCopyright (C) 2020 The Android Open Source Project",
            "2\tmodified",
            "2\tSPDX-License-Identifier: GPL-3.0-only",
        ]))

    def test_text_whose_lowercase_changes_length_is_still_recorded_and_compared(self):
        text = "// \u0130stanbul\n" + self.HEADER
        repo = self.recorded_repo({"src/A.kt": text})
        self.assertIn("1\tSPDX-License-Identifier: GPL-3.0-only", self.baseline(repo))
        write(repo, "src/A.kt", text.replace("// SPDX-License-Identifier: GPL-3.0-only\n", ""))
        git(repo, "add", "-A")
        self.assertIn("(recorded 1, found 0): SPDX-License-Identifier: GPL-3.0-only", check(repo).stdout)

    def test_removed_spdx_line_fails(self):
        repo = self.recorded_repo()
        write(repo, "src/A.kt", self.HEADER.replace("// SPDX-License-Identifier: GPL-3.0-only\n", ""))
        git(repo, "add", "-A")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn("[licence] line removed or changed (recorded 2, found 1): SPDX-License-Identifier: GPL-3.0-only", r.stdout)

    def test_altered_copyright_line_fails(self):
        repo = self.recorded_repo()
        write(repo, "src/A.kt", self.HEADER.replace("2013 The Android Open Source Project", "2013 Someone Else"))
        git(repo, "add", "-A")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn("(recorded 1, found 0): Copyright (C) 2013 The Android Open Source Project", r.stdout)

    def test_removed_modified_line_and_deleted_file_fail(self):
        repo = self.recorded_repo()
        write(repo, "src/A.kt", self.HEADER.replace("// modified\n", ""))
        git(repo, "add", "-A")
        self.assertIn("(recorded 2, found 1): modified", check(repo).stdout)
        write(repo, "src/A.kt", self.HEADER)
        git(repo, "rm", "-q", "-f", "src/B.kt")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn("Copyright (C) 2020", r.stdout)

    def test_comment_style_and_indent_changes_do_not_count_as_alteration(self):
        repo = self.recorded_repo()
        write(repo, "src/A.kt", "/*\n *   Copyright (C) 2013 The Android Open Source Project\n * modified\n *   SPDX-License-Identifier: GPL-3.0-only */\npackage a\n")
        git(repo, "add", "-A")
        self.assertEqual(check(repo).returncode, 0)

    def test_added_licence_lines_and_moved_files_are_fine(self):
        repo = self.recorded_repo()
        write(repo, "src/C.kt", "// Copyright (C) 2024 New Author\n")
        git(repo, "mv", "src/A.kt", "src/renamed.kt")
        git(repo, "add", "-A")
        self.assertEqual(check(repo).returncode, 0)

    def test_waiver_with_reason_permits_a_removal(self):
        repo = self.recorded_repo()
        git(repo, "rm", "-q", "-f", "src/B.kt")
        self.assertEqual(check(repo).returncode, 1)
        with open(os.path.join(repo, "scripts/public-check.allow"), "a", encoding="utf-8") as fh:
            fh.write("licence-waive\t^Copyright \\(C\\) 2020\t file removed on purpose\n")
        r = check(repo)
        self.assertEqual(r.returncode, 1, "count of the other recorded lines still dropped: " + r.stdout)
        with open(os.path.join(repo, "scripts/public-check.allow"), "a", encoding="utf-8") as fh:
            fh.write("licence-waive\t^(modified|SPDX-License-Identifier)\tfile removed on purpose\n")
        self.assertEqual(check(repo).returncode, 0)

    def test_ignored_paths_are_not_recorded(self):
        repo = self.recorded_repo({"src/A.kt": self.HEADER, "scripts/extra.txt": "Copyright (C) 1999 Tool Maker\n"})
        self.assertNotIn("1999", self.baseline(repo))

    def test_update_never_drops_a_recorded_line(self):
        repo = self.recorded_repo()
        before = self.baseline(repo)
        write(repo, "src/A.kt", "package a\n")
        git(repo, "add", "-A")
        r = check(repo, "--licence-update")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertEqual(self.baseline(repo), before)

    def test_update_adds_new_lines_and_raises_counts(self):
        repo = self.recorded_repo()
        write(repo, "src/C.kt", self.HEADER)
        git(repo, "add", "-A")
        self.assertEqual(check(repo, "--licence-update").returncode, 0)
        self.assertIn("3\tSPDX-License-Identifier: GPL-3.0-only", self.baseline(repo))

    def test_missing_baseline_is_a_configuration_error(self):
        repo = make_repo({"a.txt": "fine\n"})
        os.remove(os.path.join(repo, BASELINE))
        r = check(repo)
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)

    def test_shrinking_the_baseline_is_visible_in_history(self):
        repo = self.recorded_repo()
        commit(repo, "record")
        lines = self.baseline(repo).splitlines(keepends=True)
        kept = [l for l in lines if "2020" not in l]
        write(repo, BASELINE, "".join(kept))
        commit(repo, "tweak")
        r = check(repo, "--history", "HEAD")
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("%s:%s:" % (head(repo)[:7], BASELINE), r.stdout)
        self.assertIn("[licence-baseline]", r.stdout)

    def test_growing_the_baseline_is_not_a_finding(self):
        repo = self.recorded_repo()
        commit(repo, "record")
        write(repo, "src/C.kt", "// Copyright (C) 2024 New Author\n")
        git(repo, "add", "-A")
        check(repo, "--licence-update")
        commit(repo, "grow")
        self.assertEqual(check(repo, "--history", "HEAD").returncode, 0)


class BoundsTest(unittest.TestCase):
    def test_large_text_file_is_reported_not_silently_skipped(self):
        repo = make_repo({})
        with open(os.path.join(repo, "huge.txt"), "w") as fh:
            fh.write("a" * (17 * 1024 * 1024))
        git(repo, "add", "-A")
        r = check(repo)
        self.assertEqual(r.returncode, 1)
        self.assertIn("huge.txt:0: [unscanned]", r.stdout)

    def test_scan_of_a_large_clean_tree_is_fast(self):
        files = {"src/f%04d.kt" % i: ("// line %d of ordinary code\nval x%d = %d\n" % (i, i, i)) * 200 for i in range(2000)}
        repo = make_repo(files)
        start = time.monotonic()
        r = check(repo)
        elapsed = time.monotonic() - start
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertLess(elapsed, 10.0, "scan took %.1f s" % elapsed)


class HookTest(unittest.TestCase):
    def test_pre_commit_blocks_a_bad_file_and_allows_a_clean_one(self):
        repo = make_repo({"ok.txt": "fine\n"}, hooks=True)
        self.assertEqual(commit(repo, "clean").returncode, 0)
        first = head(repo)
        write(repo, "bad.txt", "see " + TASK_ID + "\n")
        r = commit(repo, "add bad")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("bad.txt:1: [task-id]", r.stdout + r.stderr)
        self.assertEqual(head(repo), first)

    def test_commit_msg_blocks_a_bad_message(self):
        repo = make_repo({"ok.txt": "fine\n"}, hooks=True)
        self.assertEqual(commit(repo, "clean").returncode, 0)
        first = head(repo)
        write(repo, "more.txt", "fine too\n")
        r = commit(repo, "add more\n\n" + CO_AUTHOR)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("[trailer]", r.stdout + r.stderr)
        self.assertEqual(head(repo), first)

    def test_commit_msg_checks_the_message_given_by_an_editor_style_file(self):
        repo = make_repo({"ok.txt": "fine\n"}, hooks=True)
        write(repo, "more.txt", "fine too\n")
        git(repo, "add", "-A")
        write(repo, "msg.txt", "add more\n\n# " + j("Cla", "ude") + " is only in a comment line\n")
        r = git(repo, "commit", "-q", "-F", "msg.txt", check=False)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def hooked_repo_with_remote(self):
        repo = make_repo({"ok.txt": "fine\n"}, hooks=True)
        remote = temp_dir("pc-remote-")
        git(remote, "init", "-q", "--bare", "-b", "main")
        git(repo, "remote", "add", "origin", remote)
        self.assertEqual(commit(repo, "clean").returncode, 0)
        return repo, remote

    def test_pre_push_blocks_a_bad_commit_hidden_below_a_clean_tip(self):
        repo, remote = self.hooked_repo_with_remote()
        write(repo, "temp.txt", TASK_ID + "\n")
        self.assertEqual(commit(repo, "bad file", "--no-verify").returncode, 0)
        bad = head(repo)
        os.remove(os.path.join(repo, "temp.txt"))
        self.assertEqual(commit(repo, "remove it").returncode, 0)
        r = git(repo, "push", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("%s:temp.txt:1: [task-id]" % bad[:7], r.stdout + r.stderr)
        self.assertEqual(git(remote, "branch", "--list", "main").stdout.strip(), "")

    def test_pre_push_blocks_a_bad_message_in_the_range(self):
        repo, remote = self.hooked_repo_with_remote()
        write(repo, "more.txt", "fine too\n")
        self.assertEqual(commit(repo, "tool trailer\n\n" + SESSION, "--no-verify").returncode, 0)
        write(repo, "more.txt", "fine three\n")
        self.assertEqual(commit(repo, "clean tip").returncode, 0)
        r = git(repo, "push", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("[trailer]", r.stdout + r.stderr)
        self.assertEqual(git(remote, "branch", "--list", "main").stdout.strip(), "")

    def test_pre_push_blocks_a_bad_tip_tree_without_new_commit_in_scan_gap(self):
        repo, remote = self.hooked_repo_with_remote()
        write(repo, "bad.txt", j("the fou", "nder") + "\n")
        self.assertEqual(commit(repo, "plain message", "--no-verify").returncode, 0)
        r = git(repo, "push", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("bad.txt:1: [person-word]", r.stdout + r.stderr)

    def test_pre_push_allows_a_clean_push_and_checks_only_the_new_range(self):
        repo, remote = self.hooked_repo_with_remote()
        self.assertEqual(git(repo, "push", "origin", "main", check=False).returncode, 0)
        self.assertEqual(git(remote, "rev-parse", "main").stdout.strip(), head(repo))
        write(repo, "more.txt", "fine too\n")
        self.assertEqual(commit(repo, "second").returncode, 0)
        r = git(repo, "push", "origin", "main", check=False)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(git(remote, "rev-parse", "main").stdout.strip(), head(repo))

    def test_pre_push_range_excludes_commits_the_remote_already_has_but_the_tip_tree_is_still_checked(self):
        repo, remote = self.hooked_repo_with_remote()
        write(repo, "old.txt", TASK_ID + "\n")
        self.assertEqual(commit(repo, "bad file", "--no-verify").returncode, 0)
        self.assertEqual(git(repo, "push", "--no-verify", "origin", "main", check=False).returncode, 0)
        write(repo, "new.txt", "fine\n")
        self.assertEqual(commit(repo, "clean change", "--no-verify").returncode, 0)
        r = git(repo, "push", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0, "the tip tree still holds the bad file")
        self.assertIn("old.txt:1: [task-id]", r.stdout + r.stderr)
        self.assertNotIn("%s:old.txt" % git(repo, "rev-parse", "HEAD~1").stdout.strip()[:7], r.stdout + r.stderr,
                         "the already pushed commit is not part of the range")
        os.remove(os.path.join(repo, "old.txt"))
        self.assertEqual(commit(repo, "remove it").returncode, 0)
        r = git(repo, "push", "origin", "main", check=False)
        self.assertEqual(r.returncode, 0, "a fix push is allowed although the remote history holds the old commit: " + r.stdout + r.stderr)

    def test_pre_push_does_not_block_deleting_a_remote_branch(self):
        repo, remote = self.hooked_repo_with_remote()
        git(repo, "push", "origin", "main")
        git(repo, "push", "origin", "main:extra")
        r = git(repo, "push", "origin", ":extra", check=False)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_without_the_hooks_path_the_bad_commit_goes_through(self):
        repo = make_repo({"bad.txt": TASK_ID + "\n"}, hooks=True)
        git(repo, "config", "--unset", "core.hooksPath")
        self.assertEqual(commit(repo, "bad").returncode, 0)

    def test_setup_script_enables_the_hooks(self):
        repo = make_repo({"ok.txt": "fine\n"}, hooks=True)
        git(repo, "config", "--unset", "core.hooksPath")
        r = subprocess.run(["sh", os.path.join(repo, "scripts/setup-hooks")], cwd=repo, capture_output=True, text=True, env=CLEAN_ENV)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(git(repo, "config", "core.hooksPath").stdout.strip(), "githooks")
        write(repo, "bad.txt", TASK_ID + "\n")
        self.assertNotEqual(commit(repo, "bad").returncode, 0)

    def test_hook_files_are_executable_and_in_the_repository(self):
        for rel in HOOK_FILES + ["scripts/public-check", "scripts/setup-hooks"]:
            path = os.path.join(ROOT, rel)
            self.assertTrue(os.access(path, os.X_OK), rel)


class TempLocationTest(unittest.TestCase):
    """Test repositories live on disk, never in the RAM-backed system temp dir, and are always removed."""

    def run_probe(self, disk_dir, tail):
        code = (
            "import os, signal, sys\n"
            "sys.path.insert(0, %r)\n"
            "import test_public_check as t\n"
            "print(t.make_repo({'a.txt': 'x\\n'}), flush=True)\n"
            % HERE
        ) + tail
        return subprocess.run(
            ["python3", "-c", code], capture_output=True, text=True,
            env=dict(CLEAN_ENV, TAEN2_TEST_TMP=disk_dir, TMPDIR="/nonexistent-ram-tmp"),
        )

    def test_repositories_are_created_under_the_disk_dir_and_removed_at_exit(self):
        disk = tempfile.mkdtemp(prefix="pc-disk-")
        self.addCleanup(shutil.rmtree, disk, ignore_errors=True)
        r = self.run_probe(disk, "")
        self.assertEqual(r.returncode, 0, r.stderr)
        repo = r.stdout.strip()
        self.assertTrue(repo.startswith(disk + os.sep), repo)
        self.assertEqual(os.listdir(disk), [])

    def test_repositories_are_removed_when_the_run_is_terminated(self):
        disk = tempfile.mkdtemp(prefix="pc-disk-")
        self.addCleanup(shutil.rmtree, disk, ignore_errors=True)
        r = self.run_probe(disk, "os.kill(os.getpid(), signal.SIGTERM)\ntime = __import__('time'); time.sleep(5)\n")
        self.assertNotEqual(r.returncode, 0)
        repo = r.stdout.strip()
        self.assertTrue(repo.startswith(disk + os.sep), repo)
        self.assertEqual(os.listdir(disk), [])

    def test_default_dir_is_under_the_user_cache(self):
        loader = importlib.machinery.SourceFileLoader("tmp_probe", os.path.join(HERE, "test_public_check.py"))
        module = importlib.util.module_from_spec(importlib.util.spec_from_loader("tmp_probe", loader))
        saved = os.environ.pop("TAEN2_TEST_TMP", None)
        try:
            loader.exec_module(module)
            self.assertEqual(module.disk_temp_base(), os.path.join(os.path.expanduser("~"), ".cache", "taen2-test-tmp"))
        finally:
            if saved is not None:
                os.environ["TAEN2_TEST_TMP"] = saved


if __name__ == "__main__":
    unittest.main()
