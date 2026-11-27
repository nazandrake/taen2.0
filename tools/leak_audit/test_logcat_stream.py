"""D2 reads the logcat that a child process streams through the whole session. If that process ends early (observed once: the file stopped about a
minute into a session of half an hour and D2 failed only because no line of the package had been captured yet), what is left is not the session:
the audit says so and does not report a clean scan of a part.
Run: python3 -m unittest discover -s tools/leak_audit -p "test_*.py"  (make leak-audit-test)"""
import os
import sys
import tempfile
import types
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import device_flow as fl  # noqa: E402
import device_run  # noqa: E402
import device_voice as dv  # noqa: E402
from model import FAIL, GAP, PASS  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
PKG = "io.github.nazandrake.taen.audit"
LINES = "11-26 18:38:21.241 1 1 I ActivityManager: start u0 {cmp=%s/x}\n11-26 18:38:22.000 1 1 I Other: nothing\n" % PKG


class FakeProc:
    def __init__(self, rc=None):
        self.rc = rc
        self.terminated = False

    def poll(self):
        return self.rc

    def terminate(self):
        self.terminated = True
        self.rc = -15

    def wait(self, timeout=None):
        return self.rc


def make_run(tmp):
    run = device_run.Run(ROOT, types.SimpleNamespace(serial="x:1", out=tmp))
    run.pkg = PKG
    run.dev = mock.MagicMock()
    return run


def started(run, rc=None):
    """What start_logcat leaves behind, with the streaming process replaced."""
    fake = FakeProc(rc)
    with mock.patch.object(device_run.subprocess, "Popen", return_value=fake), mock.patch.object(device_run.time, "sleep"):
        run.start_logcat()
    with open(run.log_path, "w") as f:
        f.write(LINES)
    return fake


class StreamEndedEarly(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.run_ = make_run(self.tmp.name)

    def test_a_stream_that_lives_to_the_stop_is_a_pass(self):
        started(self.run_)
        with mock.patch.object(device_run.time, "sleep"):
            text = self.run_.stop_logcat()
        self.assertIsNone(self.run_.log_ended)
        r, _ = fl.log_check(self.run_, text)
        self.assertEqual(r.status, PASS, r.summary)

    def test_a_stream_that_ended_before_a_step_end_is_named_with_the_step(self):
        fake = started(self.run_, rc=-9)
        self.run_.step("D11", "Typing session", lambda: None)
        self.assertIn("exit code -9", self.run_.log_ended)
        self.assertIn("end of step D11", self.run_.log_ended)
        with mock.patch.object(device_run.time, "sleep"):
            text = self.run_.stop_logcat()
        r, _ = fl.log_check(self.run_, text, "D2")
        self.assertEqual(r.status, GAP)
        self.assertIn("end of step D11", r.summary)
        self.assertIn(PKG, text)  # the partial capture does hold a line of the package: that is what made the old check pass

    def test_a_stream_that_ended_since_the_last_step_is_found_at_the_stop(self):
        fake = started(self.run_)
        fake.rc = -9
        with mock.patch.object(device_run.time, "sleep"):
            text = self.run_.stop_logcat()
        r, _ = fl.log_check(self.run_, text)
        self.assertEqual(r.status, GAP)
        self.assertIn("stop of the capture", r.summary)

    def test_stopping_the_stream_ourselves_is_not_an_early_end(self):
        fake = started(self.run_)
        self.run_.step("D6", "Clipboard", lambda: None)
        with mock.patch.object(device_run.time, "sleep"):
            self.run_.stop_logcat()
        self.assertTrue(fake.terminated)
        self.assertIsNone(self.run_.log_ended)

    def test_the_next_capture_starts_clean(self):
        started(self.run_, rc=-9)
        self.run_.step("D11", "Typing session", lambda: None)
        self.assertIsNotNone(self.run_.log_ended)
        with mock.patch.object(device_run.time, "sleep"):
            self.run_.stop_logcat()
        started(self.run_)
        self.assertIsNone(self.run_.log_ended)


LATIN = fl.CANARIES["latin"]
WORDS = ["hippopotamus", "conversation"]


class ADeadStreamStillFindsWhatItCaptured(unittest.TestCase):
    """A capture that stopped early cannot prove the whole session clean, but a canary already in it is a leak: FAIL first, GAP only for a clean part."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.run_ = make_run(self.tmp.name)

    def dead(self, extra):
        """The stream exits with -9 at step D11; the capture holds the package line and `extra`."""
        started(self.run_, rc=-9)
        with open(self.run_.log_path, "a") as f:
            f.write(extra)
        self.run_.step("D11", "Typing session", lambda: None)
        with mock.patch.object(device_run.time, "sleep"):
            return self.run_.stop_logcat()

    def test_a_canary_in_the_part_captured_before_the_stream_died_is_a_fail(self):
        text = self.dead("11-26 18:38:23.000 1 1 D Leaky: %s typed %s\n" % (PKG, LATIN))
        r, _ = fl.log_check(self.run_, text, "D2")
        self.assertEqual(r.status, FAIL, r.summary)
        self.assertIn("canary latin", r.summary)

    def test_v7_finds_a_spoken_word_in_the_part_captured_before_the_stream_died(self):
        text = self.dead("11-26 18:38:23.000 1 1 D Leaky: heard hippopotamus\n")
        r = dv.log_check(self.run_, text, WORDS)
        self.assertEqual(r.status, FAIL, r.summary)
        self.assertIn("hippopotamus", r.summary)

    # ---- pass 2: the rest of the contract ----

    def test_a_clean_partial_capture_is_a_gap_in_both_checks(self):
        text = self.dead("")
        r, _ = fl.log_check(self.run_, text, "D2")
        self.assertEqual(r.status, GAP, r.summary)
        self.assertIn("end of step D11", r.summary)
        v = dv.log_check(self.run_, text, WORDS)
        self.assertEqual(v.status, GAP, v.summary)
        self.assertIn("end of step D11", v.summary)

    def test_a_dead_stream_that_captured_nothing_of_the_package_is_a_gap_not_a_fail(self):
        started(self.run_, rc=-9)
        with open(self.run_.log_path, "w") as f:
            f.write("11-26 18:38:22.000 1 1 I Other: nothing\n")
        self.run_.step("D11", "Typing session", lambda: None)
        with mock.patch.object(device_run.time, "sleep"):
            text = self.run_.stop_logcat()
        r, _ = fl.log_check(self.run_, text, "D2")
        self.assertEqual(r.status, GAP, r.summary)

    def test_the_release_label_is_kept_for_a_hit_on_a_dead_stream(self):
        text = self.dead("11-26 18:38:23.000 1 1 D Leaky: %s typed %s\n" % (PKG, LATIN))
        r, _ = fl.log_check(self.run_, text, "D2r")
        self.assertEqual((r.ident, r.status), ("D2r", FAIL))

    def test_a_hit_on_a_live_stream_is_still_a_fail_and_a_clean_one_a_pass(self):  # positive controls: the same inputs, the stream alive
        started(self.run_)
        with open(self.run_.log_path, "a") as f:
            f.write("11-26 18:38:23.000 1 1 D Leaky: %s typed %s\n" % (PKG, LATIN))
        with mock.patch.object(device_run.time, "sleep"):
            text = self.run_.stop_logcat()
        self.assertEqual(fl.log_check(self.run_, text)[0].status, FAIL)
        self.assertEqual(dv.log_check(self.run_, LINES, WORDS).status, PASS)
        self.assertEqual(dv.log_check(self.run_, LINES + "heard hippopotamus\n", WORDS).status, FAIL)

    def test_the_v7_control_still_proves_the_check_can_fail_when_the_stream_is_dead(self):
        self.dead("")
        self.assertIsNotNone(self.run_.log_ended)
        self.assertEqual(dv.log_control(self.run_, WORDS).status, PASS)

    def test_a_whole_capture_without_any_line_of_the_package_is_a_fail(self):
        started(self.run_)
        with open(self.run_.log_path, "w") as f:
            f.write("11-26 18:38:22.000 1 1 I Other: nothing\n")
        with mock.patch.object(device_run.time, "sleep"):
            text = self.run_.stop_logcat()
        r, _ = fl.log_check(self.run_, text)
        self.assertEqual(r.status, FAIL)
        self.assertIn("no line that mentions", r.summary)
