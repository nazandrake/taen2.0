"""The overlay half of D7: the keyboard warns about a covered touch only when Android marks the touch obscured, and Android does not do that for a window it
treats as a trusted overlay. On the reference phone the helper's overlay window is marked trusted for about 30% of the windows (measured), decided when the window
is created and unchanged for its life (`dumpsys input`: trustedOverlay=true; the touches then carry no obscured flag and no warning is shown, which
is right). The check therefore makes sure the window it taps under is an untrusted one, and says "not run" when it cannot get one.
Run: python3 -m unittest discover -s tools/leak_audit -p "test_*.py"  (make leak-audit-test)"""
import os
import sys
import types
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import device_flow as fl  # noqa: E402
from model import FAIL, GAP, PASS  # noqa: E402

# the shape of the real lines of `dumpsys input` (measured on the reference phone); the long tail of each line is cut
OVERLAY = ("      3: name='%s Sys2038:taen.audit.helper/taen.audit.FieldActivity', id=107222, displayId=0, visible=true, alpha=1.00, "
           "flags=NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN, type=2038, frame=[0,1300][300,1450], touchableRegion=[0,1300][300,1450], "
           "inputFeatures=0x0, ownerPid=26192, ownerUid=10940, dispatchingTimeout=5000ms, trustedOverlay=%s, hasToken=true, touchOcclusionMode=USE_OPACITY")
IME = ("      4: name='d642980 InputMethod', id=107201, displayId=0, visible=true, type=2011, frame=[0,72][1080,1920], "
       "ownerPid=26142, trustedOverlay=true, hasToken=true, touchOcclusionMode=BLOCK_UNTRUSTED")
BAR = "      2: name='9632003 StatusBar', id=101289, visible=true, type=2000, trustedOverlay=true, touchOcclusionMode=BLOCK_UNTRUSTED"


def dump(*overlays):
    return "\n".join(["    Windows:", BAR] + [OVERLAY % (("156b69a%d" % i), "true" if t else "false") for i, t in enumerate(overlays)] + [IME])


class FakeDev:
    def __init__(self, states):
        self.states = states  # what each `dumpsys input` after the n-th start of the overlay shows (a list per start, one entry per poll)
        self.starts = 0
        self.polls = 0

    def sh(self, cmd, timeout=120):
        assert cmd == "dumpsys input", cmd
        seq = self.states[self.starts - 1]
        text = seq[min(self.polls, len(seq) - 1)]
        self.polls += 1
        return text


class FakeRun:
    def __init__(self, states):
        self.dev = FakeDev(states)
        self.fields = []

    def field(self, focus="normal", **extras):
        self.fields.append((focus, extras))
        self.dev.starts += 1
        self.dev.polls = 0


def quick(fn, *a):
    real = fl.time.sleep
    fl.time.sleep = lambda s: None
    try:
        return fn(*a)
    finally:
        fl.time.sleep = real


class OverlayTrustReading(unittest.TestCase):
    def test_untrusted(self):
        self.assertEqual(fl.overlay_trust(dump(False)), "untrusted")

    def test_trusted(self):
        self.assertEqual(fl.overlay_trust(dump(True)), "trusted")

    def test_no_helper_window_is_absent_even_though_other_windows_are_trusted(self):
        self.assertEqual(fl.overlay_trust(dump()), "absent")

    def test_one_trusted_window_among_the_helper_windows_counts_as_trusted(self):
        self.assertEqual(fl.overlay_trust(dump(False, True)), "trusted")
        self.assertEqual(fl.overlay_trust(dump(True, False)), "trusted")


class UntrustedOverlayIsAwaited(unittest.TestCase):
    def test_an_untrusted_window_at_once_needs_one_start(self):
        run = FakeRun([[dump(False)]])
        self.assertEqual(quick(fl.add_untrusted_overlay, run), 1)
        self.assertEqual(run.fields, [("normal", {"overlay": True})])

    def test_a_trusted_window_is_replaced_by_a_new_start(self):
        run = FakeRun([[dump(True)], [dump(True)], [dump(False)]])
        self.assertEqual(quick(fl.add_untrusted_overlay, run), 3)
        self.assertEqual(len(run.fields), 3)

    def test_a_window_that_shows_up_late_is_waited_for_without_a_new_start(self):
        run = FakeRun([[dump(), dump(), dump(False)]])
        self.assertEqual(quick(fl.add_untrusted_overlay, run), 1)
        self.assertEqual(len(run.fields), 1)

    def test_a_window_that_never_shows_up_counts_as_not_obtained(self):
        run = FakeRun([[dump()]] * fl.OVERLAY_TRIES)
        self.assertIsNone(quick(fl.add_untrusted_overlay, run))
        self.assertEqual(len(run.fields), fl.OVERLAY_TRIES)

    def test_trusted_every_time_ends_after_the_bound(self):
        run = FakeRun([[dump(True)]] * fl.OVERLAY_TRIES)
        self.assertIsNone(quick(fl.add_untrusted_overlay, run))
        self.assertEqual(len(run.fields), fl.OVERLAY_TRIES)

    def test_the_last_allowed_start_can_still_succeed(self):
        run = FakeRun([[dump(True)]] * (fl.OVERLAY_TRIES - 1) + [[dump(False)]])
        self.assertEqual(quick(fl.add_untrusted_overlay, run), fl.OVERLAY_TRIES)


NEEDLE = "A window covers the keyboard"
TOAST = "I/AuditHelper: a11y class=android.widget.Toast text=" + NEEDLE


class OverlayPhone:
    """The helper's log as the checks read it: a tap on the keyboard adds a warning toast when `warns_without_overlay` is set (a control that must fail)
    or when an untrusted overlay window is up and `warns_with_overlay` is set."""

    def __init__(self, untrusted_overlay_starts, warns_without_overlay=False, warns_with_overlay=True):
        self.untrusted_overlay_starts = untrusted_overlay_starts
        self.warns_without_overlay = warns_without_overlay
        self.warns_with_overlay = warns_with_overlay
        self.overlay = False
        self.lines = []

    # the production code reads these through run.dev
    def sh(self, cmd, timeout=120):
        return ""

    def tap(self, x, y):
        if (self.overlay and self.warns_with_overlay) or (not self.overlay and self.warns_without_overlay):
            self.lines.append(TOAST)

    def add_untrusted_overlay(self, run):
        if self.untrusted_overlay_starts is None:
            return None
        self.overlay = True
        self.lines.append("I/AuditHelper: overlay added")
        return self.untrusted_overlay_starts

    def helper_log(self, run):
        return list(self.lines)


def overlay_rows(phone):
    run = types.SimpleNamespace(dev=phone, field=lambda *a, **k: None)
    with mock.patch.object(fl, "add_untrusted_overlay", phone.add_untrusted_overlay), mock.patch.object(fl, "helper_log", phone.helper_log):
        return {r.ident: r for r in quick(fl.overlay_warning_checks, run, NEEDLE)}


class OverlayControlIsItsOwnRow(unittest.TestCase):
    def test_a_warning_without_any_overlay_is_a_visible_fail_when_no_untrusted_overlay_was_had(self):
        rows = overlay_rows(OverlayPhone(None, warns_without_overlay=True))
        self.assertEqual(rows["D7o"].status, GAP)
        self.assertEqual(rows["D7o-c"].status, FAIL)
        self.assertIn("without any overlay", rows["D7o-c"].summary)

    def test_a_clean_control_with_no_untrusted_overlay_is_a_pass_row_beside_the_gap(self):  # positive control
        rows = overlay_rows(OverlayPhone(None))
        self.assertEqual((rows["D7o"].status, rows["D7o-c"].status), (GAP, PASS))

    def test_with_an_untrusted_overlay_the_control_stays_inside_d7o(self):
        rows = overlay_rows(OverlayPhone(2))
        self.assertEqual(rows["D7o"].status, PASS)
        self.assertNotIn("D7o-c", rows)
        failed = overlay_rows(OverlayPhone(2, warns_without_overlay=True))
        self.assertEqual(failed["D7o"].status, FAIL)
        self.assertIn("without any overlay", failed["D7o"].summary)
