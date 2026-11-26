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
from model import GAP, PASS  # noqa: E402

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
