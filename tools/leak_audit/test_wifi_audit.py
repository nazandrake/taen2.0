"""Tests of the device audit over Wi-Fi adb (no phone): which checks are unrun there, and why.
Run: python3 -m unittest discover -s tools/leak_audit -p "test_*.py"  (make leak-audit-test)"""
import glob
import json
import os
import re
import sys
import tempfile
import types
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import device_audit  # noqa: E402
import netstats  # noqa: E402
from model import GAP, PASS, FAIL, Result, Store  # noqa: E402

REASON = "needs USB: switches Wi-Fi off"
NETWORK_IDS = {"D1", "T-D1", "D1r"}


class FakeRun:
    """Stands in for device_run.Run: records the steps that are run and the results that are added."""

    def __init__(self, wifi):
        self.dev = mock.MagicMock()
        self.dev.over_wifi = wifi
        self.args = types.SimpleNamespace(fast=True)
        self.root = HERE
        self.work = tempfile.gettempdir()
        self.meta = {}
        self.results = []
        self.steps = []

    def __getattr__(self, name):  # install, enable_ime, start_logcat, session...: they touch the phone and are not under test
        m = mock.MagicMock()
        setattr(self, name, m)
        return m

    def add(self, r):
        self.results.append(r)
        return r

    def step(self, ident, title, fn):
        self.steps.append(ident)
        try:
            out = fn()
        except Exception:  # noqa: BLE001 - the mocks return MagicMocks where the real checks return results
            return
        for r in out if isinstance(out, list) else [out]:
            self.results.append(r)




def run_flow(wifi):
    """device_audit.flow and release_session with every phone-touching helper replaced; returns (run, netstats mock, checks mock)."""
    run = FakeRun(wifi)
    with tempfile.NamedTemporaryFile(suffix=".apk") as audit_apk, \
            mock.patch.object(device_audit, "fl", mock.MagicMock()) as fl, \
            mock.patch.object(device_audit, "dv", mock.MagicMock()), \
            mock.patch.object(device_audit, "helper_build", mock.MagicMock()), \
            mock.patch.object(device_audit, "netstats", mock.MagicMock()) as ns, \
            mock.patch("time.sleep"), \
            mock.patch("static_run.sha256", return_value="0" * 64):
        device_audit.flow(run, audit_apk.name, "/nonexistent/release.apk")
    return run, ns, fl


def network_gaps(run):
    return {r.ident: r for r in run.results if r.status == GAP and r.summary == REASON}


class SerialForms(unittest.TestCase):
    def test_wifi_forms(self):
        for serial in ("192.0.2.7:5555", "[fe80::1]:5555", "adb-ABCDEF123456-aB3dEf._adb-tls-connect._tcp"):
            self.assertTrue(device_audit.over_wifi(serial), serial)

    def test_usb_forms(self):
        for serial in ("ABCDEF123456", "0123456789ABCDEF", "emulator-5554"):
            self.assertFalse(device_audit.over_wifi(serial), serial)

    def test_unknown_transport_fails_closed(self):
        for serial in ("", "unknown"):
            self.assertTrue(device_audit.over_wifi(serial), repr(serial))


class ChecksOverWifi(unittest.TestCase):
    def test_exactly_the_network_checks_are_gaps(self):
        run, ns, fl = run_flow(True)
        gaps = network_gaps(run)
        self.assertEqual(set(gaps), NETWORK_IDS)
        for r in gaps.values():
            self.assertEqual(r.summary, REASON)
        self.assertEqual(len([r for r in run.results if r.ident in NETWORK_IDS]), 3)

    def test_nothing_toggles_the_network(self):
        run, ns, fl = run_flow(True)
        ns.read.assert_not_called()
        fl.network_check.assert_not_called()
        fl.network_control.assert_not_called()
        for call in run.dev.method_calls:
            self.assertNotIn("svc wifi", str(call))

    def test_every_other_check_still_runs(self):
        usb, _, _ = run_flow(False)
        wifi, _, _ = run_flow(True)
        self.assertEqual(set(usb.steps) - set(wifi.steps), NETWORK_IDS)
        self.assertEqual(set(wifi.steps) - set(usb.steps), set())
        for ident in ("D2", "D3a", "D6", "D8", "D9", "D10", "D14", "D2r"):
            self.assertIn(ident, wifi.steps)


class ChecksOverUsb(unittest.TestCase):
    def test_nothing_is_skipped(self):
        run, ns, fl = run_flow(False)
        self.assertEqual(network_gaps(run), {})
        self.assertTrue(NETWORK_IDS <= set(run.steps))
        self.assertEqual(ns.read.call_count, 4)  # base and final of the audit twin and of the release build
        self.assertEqual([c.kwargs.get("label", "D1") for c in fl.network_check.call_args_list], ["D1", "D1r"])
        fl.network_control.assert_called_once()


class FakeNetDev:
    def __init__(self, wifi=False, fail_on=None):
        self.over_wifi, self.cmds, self.fail_on = wifi, [], fail_on

    def sh(self, cmd, timeout=120):
        self.cmds.append(cmd)
        if cmd == self.fail_on:
            raise RuntimeError("adb timeout")
        if cmd.startswith("ip -4 addr"):
            return "inet 192.0.2.7/24"
        return ""


class NetstatsPoll(unittest.TestCase):
    def test_poll_refused_over_wifi_without_touching_the_phone(self):
        dev = FakeNetDev(wifi=True)
        with self.assertRaisesRegex(RuntimeError, REASON):
            netstats.read(dev)
        self.assertEqual(dev.cmds, [])

    def test_no_poll_over_wifi_only_reads(self):
        dev = FakeNetDev(wifi=True)
        netstats.read(dev, poll=False)
        self.assertEqual(dev.cmds, ["dumpsys netstats --uid"])

    def test_poll_over_usb_toggles_and_reads(self):
        dev = FakeNetDev()
        with mock.patch("time.sleep"):
            netstats.read(dev)
        self.assertEqual(dev.cmds[0], "svc wifi disable")
        self.assertIn("svc wifi enable", dev.cmds)
        self.assertEqual(dev.cmds[-1], "dumpsys netstats --uid")

    def test_wifi_is_switched_on_again_when_the_disable_fails(self):
        dev = FakeNetDev(fail_on="svc wifi disable")
        with mock.patch("time.sleep"), self.assertRaises(RuntimeError):
            netstats.read(dev)
        self.assertEqual(dev.cmds, ["svc wifi disable", "svc wifi enable"])

    def test_wifi_is_switched_on_again_when_the_wait_is_interrupted(self):
        dev = FakeNetDev()
        with mock.patch("time.sleep", side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
            netstats.read(dev)
        self.assertEqual(dev.cmds, ["svc wifi disable", "svc wifi enable"])


class FakeDev:
    """The phone as run_all sees it: scripted answers, every shell command recorded."""

    def __init__(self, serial, wifi_on="1", adb_enabled_after="1", fail=(), adb_before="1"):
        self.serial, self.cmds, self.over_wifi = serial, [], False
        self.wifi_on, self.adb_after, self.fail, self.adb_reads, self.adb_before = wifi_on, adb_enabled_after, fail, 0, adb_before

    def present(self):
        return True

    def adb(self, *args, **kw):
        return types.SimpleNamespace(stdout=(self.serial + "\n").encode())

    def sh(self, cmd, timeout=120):
        self.cmds.append(cmd)
        if any(f in cmd for f in self.fail):
            raise RuntimeError("adb timeout")
        if cmd == "settings get global wifi_on":
            return self.wifi_on
        if cmd == "settings get global adb_enabled":
            self.adb_reads += 1
            return self.adb_before if self.adb_reads == 1 else self.adb_after
        if cmd == "settings get global stay_on_while_plugged_in":
            return "0"
        if cmd == "settings get secure default_input_method":
            return "ime/.Ime"
        return ""

    def sleep_screen(self):
        self.cmds.append("SLEEP")


class RunAllRestores(unittest.TestCase):
    def run_all(self, serial, flow_effect=None, **kw):
        dev = FakeDev(serial, **kw)
        run = FakeRun(False)
        run.dev, run.log_proc = dev, None
        run.args = types.SimpleNamespace(audit_apk="/x/audit.apk", release_apk=None, serial=serial)
        with mock.patch.object(device_audit, "Run", return_value=run), \
                mock.patch.object(device_audit.apklib, "signed_release_apk", return_value="/x/release.apk"), \
                mock.patch.object(device_audit, "version_name", return_value="9.9.9"), \
                mock.patch.object(device_audit, "flow", side_effect=flow_effect) as flow:
            try:
                device_audit.run_all(HERE, run.args)
                raised = None
            except Exception as e:  # noqa: BLE001
                raised = e
        return dev, run, flow, raised

    def test_wifi_transport_is_recognised_and_never_switched_off(self):
        dev, run, flow, _ = self.run_all("192.0.2.7:5555")
        flow.assert_called_once()
        self.assertTrue(dev.over_wifi)
        self.assertFalse([c for c in dev.cmds if "svc wifi disable" in c])

    def test_usb_transport_is_recognised(self):
        dev, run, flow, _ = self.run_all("ABCDEF123456")
        flow.assert_called_once()
        self.assertFalse(dev.over_wifi)

    def test_wifi_and_adb_state_restored_when_the_flow_raises(self):
        dev, run, flow, raised = self.run_all("ABCDEF123456", flow_effect=RuntimeError("boom"), adb_enabled_after="0")
        self.assertIsInstance(raised, RuntimeError)
        self.assertIn("svc wifi enable", dev.cmds)
        self.assertIn("settings put global adb_enabled 1", dev.cmds)
        self.assertIn("SLEEP", dev.cmds)

    def test_adb_setting_not_rewritten_when_unchanged(self):
        dev, run, flow, _ = self.run_all("ABCDEF123456")
        self.assertNotIn("settings put global adb_enabled 1", dev.cmds)

    def test_wifi_left_off_when_it_was_off(self):
        dev, run, flow, _ = self.run_all("ABCDEF123456", wifi_on="0")
        self.assertIn("svc wifi disable", dev.cmds)
        self.assertNotIn("svc wifi enable", dev.cmds)

    def test_wifi_never_switched_off_over_wifi_even_when_it_read_off(self):
        dev, run, flow, _ = self.run_all("192.0.2.7:5555", wifi_on="0")
        self.assertFalse([c for c in dev.cmds if c.startswith("svc wifi")])

    def test_wifi_state_unknown_is_left_alone(self):
        dev, run, flow, _ = self.run_all("ABCDEF123456", wifi_on="null")
        self.assertFalse([c for c in dev.cmds if c.startswith("svc wifi")])

    def test_adb_setting_not_written_when_it_was_off_before(self):
        dev, run, flow, _ = self.run_all("ABCDEF123456", adb_before="0", adb_enabled_after="0")
        self.assertNotIn("settings put global adb_enabled 1", dev.cmds)

    def test_one_failing_restore_step_does_not_skip_the_rest(self):
        dev, run, flow, _ = self.run_all("ABCDEF123456", flow_effect=RuntimeError("boom"), fail=("svc wifi enable", "ime set"))
        self.assertIn("pm uninstall %s; pm uninstall io.github.nazandrake.taen.audit" % device_audit.HELPER, dev.cmds)
        self.assertIn("SLEEP", dev.cmds)
        self.assertIn("settings put global stay_on_while_plugged_in 0", dev.cmds)
        self.assertTrue(run.meta.get("restore errors"))


class OnlyNetstatsCutsTheNetwork(unittest.TestCase):
    CUTTING = re.compile(r"svc wifi|svc data|airplane|cmd connectivity|cmd wifi|adb\W+usb|tcpip|\"usb\"|disconnect", re.I)

    RESTORE = {("device_audit.py", 'd.sh("svc wifi enable")'), ("device_audit.py", 'd.sh("svc wifi disable")')}

    def test_no_other_source_can_cut_the_connection(self):
        offenders = []
        for path in sorted(glob.glob(os.path.join(HERE, "*.py"))):
            name = os.path.basename(path)
            if name in ("netstats.py", "test_wifi_audit.py"):
                continue
            with open(path, encoding="utf-8") as f:
                for n, line in enumerate(f, 1):
                    if self.CUTTING.search(line) and (name, line.strip()) not in self.RESTORE:  # run_all's restore, tested above
                        offenders.append("%s:%d: %s" % (name, n, line.strip()[:80]))
        self.assertEqual(offenders, [])


class ReportLine(unittest.TestCase):
    def store(self, static, device_results, static_sha="abc", release_sha="abc"):
        s = Store(tempfile.mkdtemp())
        if static is not None:
            s.put("static", [Result(i, "t", st, "x") for i, st in static], {"sha256": static_sha})
        s.put("device", device_results, {"release apk sha256": release_sha})
        return s

    def gaps(self):
        return [Result(i, "t", GAP, REASON) for i in ("D1", "T-D1", "D1r")]

    def test_rule_met(self):
        s = self.store([("S1", PASS), ("S3", PASS), ("S8", PASS)], self.gaps())
        self.assertEqual(s.network_gap_line(), "Network (D1, T-D1, D1r) is GAP over Wi-Fi (needs USB): static S1, S3 and S8 PASS on the same APK (sha256 abc): "
                         "no INTERNET permission and no network code, so a release may pass with these GAPs.")
        with open(os.path.join(s.out_dir, "leak-audit-report.md")) as f:
            self.assertIn(s.network_gap_line(), f.read())

    def test_rule_not_met(self):
        for static, sha in (([("S1", PASS), ("S3", FAIL), ("S8", PASS)], "abc"),
                            ([("S1", PASS), ("S3", PASS)], "abc"),
                            ([("S1", PASS), ("S3", PASS), ("S8", PASS)], "other"),
                            (None, "abc")):
            s = self.store(static, self.gaps(), static_sha=sha)
            line = s.network_gap_line()
            self.assertTrue(line.startswith("Network (D1, T-D1, D1r) is GAP over Wi-Fi (needs USB) and the static proof is NOT met"), line)
            self.assertTrue(line.endswith("a release may NOT pass with these GAPs."), line)

    def test_rule_not_met_when_neither_run_has_a_hash(self):
        s = Store(tempfile.mkdtemp())
        s.put("static", [Result(i, "t", PASS, "x") for i in ("S1", "S3", "S8")], {})
        s.put("device", self.gaps(), {})
        self.assertIn("NOT met", s.network_gap_line())

    def test_a_d1_that_failed_is_not_a_wifi_gap(self):
        s = self.store([("S1", PASS), ("S3", PASS), ("S8", PASS)], [Result("D1", "t", FAIL, REASON)])
        self.assertIsNone(s.network_gap_line())

    def test_no_line_when_d1_ran(self):
        s = self.store([("S1", PASS), ("S3", PASS), ("S8", PASS)], [Result("D1", "t", PASS, "ok")])
        self.assertIsNone(s.network_gap_line())
        with open(os.path.join(s.out_dir, "leak-audit-report.md")) as f:
            self.assertNotIn("GAP over Wi-Fi", f.read())


if __name__ == "__main__":
    unittest.main()
