"""The overlay half of D7: the keyboard warns about a covered touch only when Android marks the touch obscured, and Android does not do that for a window it
treats as a trusted overlay. On the reference phone the helper's overlay window is marked trusted for about one window in four, decided when the window
is created and unchanged for its life (`dumpsys input`: trustedOverlay=true; the touches then carry no obscured flag and no warning is shown, which
is right). The check therefore makes sure the window it taps under is an untrusted one, and says "not run" when it cannot get one.
Run: python3 -m unittest discover -s tools/leak_audit -p "test_*.py"  (make leak-audit-test)"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import device_flow as fl  # noqa: E402

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
