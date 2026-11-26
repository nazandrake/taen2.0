"""The Privacy screen's status lines (D7, T-D7): the check waits for the line it needs, not only for the "Status" heading above it.
On the reference phone the heading stops at y 1513-1573 after the scroll and the lines under it, the accessibility line among them, are below the
screen edge until the list is scrolled once more (measured: the UI dump held "Status" and two lines, not the accessibility line).
Run: python3 -m unittest discover -s tools/leak_audit -p "test_*.py"  (make leak-audit-test)"""
import os
import sys
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import device_flow as fl  # noqa: E402

NONE_LINE = "No accessibility service that can read the screen is on."
SOME_PREFIX = "Accessibility services that can read the screen: "
SCREEN_TOP = ["Privacy", "Self-destruct", "Status"]
FIRST_LINES = ["No network access: the app has no network permission.", "Android backup and device transfer are off for this app."]


class FakeDev:
    """The Privacy screen as a list of rows: `rows` is what a dump shows after n swipes up; the last entry repeats at the end of the list."""

    def __init__(self, rows):
        self.rows = rows
        self.swipes = 0

    def texts(self):
        return list(self.rows[min(self.swipes, len(self.rows) - 1)])

    def scroll_down(self):
        self.swipes += 1


class FakeRun:
    def __init__(self, rows):
        self.dev = FakeDev(rows)
        self.calls = []

    def settings_home(self):
        self.calls.append("settings_home")

    def open_section(self, name):
        self.calls.append("open " + name)

    def scroll_to(self, text, exact=True, tries=10, up=False, max_y=1600):
        self.calls.append("scroll_to " + text)


def sleeping(fn, *a):
    real = fl.time.sleep
    fl.time.sleep = lambda s: None
    try:
        return fn(*a)
    finally:
        fl.time.sleep = real


class StatusLineIsAwaited(unittest.TestCase):
    LINES = (NONE_LINE, SOME_PREFIX)

    def status(self, rows):
        run = FakeRun(rows)
        return sleeping(fl.privacy_status_texts, run, self.LINES), run

    def test_a_line_below_the_screen_edge_is_scrolled_into_view(self):
        texts, run = self.status([SCREEN_TOP + FIRST_LINES, SCREEN_TOP[2:] + FIRST_LINES + [NONE_LINE]])
        self.assertIn(NONE_LINE, texts)
        self.assertEqual(run.dev.swipes, 1)

    def test_the_service_line_is_found_by_its_prefix(self):
        listed = SOME_PREFIX + "Taen audit helper"
        texts, run = self.status([SCREEN_TOP + FIRST_LINES, FIRST_LINES + [listed]])
        self.assertIn(listed, texts)

    def test_a_line_already_on_screen_is_not_scrolled_away(self):
        texts, run = self.status([SCREEN_TOP + FIRST_LINES + [NONE_LINE], FIRST_LINES])
        self.assertIn(NONE_LINE, texts)
        self.assertEqual(run.dev.swipes, 0)

    def test_the_heading_is_scrolled_to_before_the_lines_are_looked_for(self):
        _, run = self.status([SCREEN_TOP + FIRST_LINES + [NONE_LINE]])
        self.assertEqual(run.calls, ["settings_home", "open Privacy", "scroll_to Status"])

    def test_a_line_that_never_shows_ends_after_the_bound_and_returns_the_last_screen(self):
        last = FIRST_LINES + ["About"]
        texts, run = self.status([SCREEN_TOP + FIRST_LINES, last])
        self.assertEqual(texts, last)
        self.assertNotIn(NONE_LINE, texts)
        self.assertEqual(run.dev.swipes, fl.STATUS_SCROLLS)

    def test_the_other_status_lines_do_not_count_as_the_wanted_line(self):
        texts, run = self.status([SCREEN_TOP + FIRST_LINES, FIRST_LINES + ["Android backup"], FIRST_LINES + [NONE_LINE]])
        self.assertIn(NONE_LINE, texts)
        self.assertEqual(run.dev.swipes, 2)
