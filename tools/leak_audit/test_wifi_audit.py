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
        # over Wi-Fi the process half of D1 runs on its own (over USB D1 holds it)
        self.assertEqual(set(wifi.steps) - set(usb.steps), {"D1s", "T-D1s", "D1sr"})
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


class TransportKindOnly(unittest.TestCase):
    """results.json and the report name the transport kind (USB or Wi-Fi), never the serial or the address of the phone."""

    def run_and_store(self, serial):
        dev, run, flow, _ = RunAllRestores.run_all(RunAllRestores(), serial)
        store = Store(tempfile.mkdtemp())
        store.put("device", [Result("D1", "t", GAP, REASON)], run.meta)
        with open(store.path) as f:
            stored = f.read()
        with open(os.path.join(store.out_dir, "leak-audit-report.md")) as f:
            report = f.read()
        return run, stored, report

    def test_meta_names_the_kind_not_the_address(self):
        for serial, kind in (("192.0.2.7:5555", "Wi-Fi"), ("[fe80::1]:5555", "Wi-Fi"), ("adb-ABCDEF123456-aB3dEf._adb-tls-connect._tcp", "Wi-Fi"),
                             ("ABCDEF123456", "USB cable")):
            run, stored, report = self.run_and_store(serial)
            self.assertTrue(run.meta["adb transport"].startswith(kind), run.meta["adb transport"])
            self.assertIn("adb transport", stored)  # positive control: the entry is written
            for part in (serial, "192.0.2.7", "fe80::1", "ABCDEF123456"):
                self.assertNotIn(part, json.dumps(run.meta), serial)
                self.assertNotIn(part, stored, serial)
                self.assertNotIn(part, report, serial)

    def test_the_wifi_line_says_which_checks_ran(self):
        self.assertEqual(device_audit.transport_kind("192.0.2.7:5555"),
                         "Wi-Fi: D1, T-D1 and D1r are GAP (needs USB: switches Wi-Fi off); the process and socket checks D1s, T-D1s and D1sr ran")
        self.assertEqual(device_audit.transport_kind("ABCDEF123456"), "USB cable")
        self.assertTrue(device_audit.transport_kind("").startswith("Wi-Fi"))  # unknown: the restrictive reading, as over_wifi


class ProcessChecksOverWifi(unittest.TestCase):
    """The inet-group and /proc/net socket-row checks switch nothing off, so they run over Wi-Fi too."""

    def test_group_and_socket_checks_run_over_wifi(self):
        run, ns, fl = run_flow(True)
        for ident in ("D1s", "T-D1s", "D1sr"):
            self.assertIn(ident, run.steps)
        self.assertEqual([c.kwargs.get("label", "D1s") for c in fl.process_network_check.call_args_list], ["D1s", "D1sr"])
        fl.process_network_control.assert_called_once()
        ns.read.assert_not_called()  # still nothing that switches the Wi-Fi off

    def test_over_usb_d1_holds_them(self):
        run, ns, fl = run_flow(False)
        fl.process_network_check.assert_not_called()
        fl.process_network_control.assert_not_called()
        self.assertEqual([c.kwargs.get("label", "D1") for c in fl.network_check.call_args_list], ["D1", "D1r"])  # positive control


class FakeProcDev:
    """The phone for the process and socket checks: pidof, /proc/<pid>/status, /proc/net rows and the package uid."""

    def __init__(self, pids="4242", groups="3003 9997", rows="", uid="10123", self_groups="1004 3003 9997"):
        self.pids, self.groups, self.rows, self.uid, self.self_groups = pids, groups, rows, uid, self_groups
        self.cmds = []

    def sh(self, cmd, timeout=120):
        self.cmds.append(cmd)
        if cmd.startswith("pidof"):
            return self.pids
        if cmd == "cat /proc/self/status":
            return "Name:\tsh\nGroups:\t%s\n" % self.self_groups
        if cmd.startswith("cat /proc/") and cmd.endswith("/status"):
            return "Name:\ttaen\nGroups:\t%s\n" % self.groups
        if cmd.startswith("cat /proc/net/"):
            return self.rows
        if cmd.startswith("dumpsys package"):
            return "    userId=%s" % self.uid if self.uid else ""
        return ""


def row(uid):
    return "   0: 0100007F:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000  %d        0 12345 1 0000000000000000 100 0 0 10 0" % uid


class ProcessSocketCheck(unittest.TestCase):
    import device_flow as fl_real  # noqa: E402

    def check(self, **kw):
        dev = FakeProcDev(**kw)
        r = self.fl_real.process_network_check(types.SimpleNamespace(dev=dev), ["io.github.nazandrake.taen"])
        return r, dev

    def test_a_clean_process_passes_and_nothing_is_switched_off(self):
        r, dev = self.check(groups="9997 50123", rows=row(2000))
        self.assertEqual(r.status, PASS, r.summary)
        self.assertEqual(r.ident, "D1s")
        self.assertFalse([c for c in dev.cmds if "svc" in c or "settings put" in c])

    def test_the_inet_group_fails(self):
        r, _ = self.check(groups="3003 9997")
        self.assertEqual(r.status, FAIL)
        self.assertIn("inet group 3003", r.summary)

    def test_a_socket_row_of_the_app_fails(self):
        r, _ = self.check(groups="9997", rows=row(2000) + "\n" + row(10123))
        self.assertEqual(r.status, FAIL)
        self.assertIn("owns 1 socket row", r.summary)

    def test_no_process_fails(self):
        r, _ = self.check(pids="", groups="9997")
        self.assertEqual(r.status, FAIL)
        self.assertIn("no running process", r.summary)

    def test_a_uid_that_cannot_be_read_fails(self):
        r, _ = self.check(groups="9997", uid="")
        self.assertEqual(r.status, FAIL)
        self.assertIn("uid", r.summary)

    def test_the_release_row_has_its_own_id(self):
        dev = FakeProcDev(groups="9997")
        r = self.fl_real.process_network_check(types.SimpleNamespace(dev=dev), ["io.github.nazandrake.taen"], label="D1sr")
        self.assertEqual((r.ident, r.status), ("D1sr", PASS))

    def test_the_control_finds_the_shell_group_and_the_adb_socket(self):
        ok = self.fl_real.process_network_control(types.SimpleNamespace(dev=FakeProcDev(rows=row(2000))))
        self.assertEqual((ok.ident, ok.status), ("T-D1s", PASS), ok.summary)
        for kw in ({"rows": ""}, {"rows": row(2000), "self_groups": "1004 9997"}):  # either reader blind: the control fails
            r = self.fl_real.process_network_control(types.SimpleNamespace(dev=FakeProcDev(**kw)))
            self.assertEqual(r.status, FAIL, kw)


class ExitStatus(unittest.TestCase):
    """The audit exits non-zero when the network checks are GAPs over Wi-Fi and the release "may pass" rule is not met."""

    def gaps(self):
        return [Result(i, "t", GAP, REASON) for i in ("D1", "T-D1", "D1r")]

    def device(self, out, results, sha="abc"):
        with mock.patch.object(device_audit, "run_all", return_value=(results, {"release apk sha256": sha})):
            return load_main().main(["device", "--out", out])

    def static_proof(self, out, statuses=(PASS, PASS, PASS), sha="abc"):
        Store(out).put("static", [Result(i, "t", st, "x") for i, st in zip(("S1", "S3", "S8"), statuses)], {"sha256": sha})

    def test_exit_is_1_when_the_rule_is_not_met(self):
        for statuses, sha in (((PASS, FAIL, PASS), "abc"), ((PASS, PASS, PASS), "other"), (None, "abc")):
            out = tempfile.mkdtemp()
            if statuses:
                self.static_proof(out, statuses, sha)
            self.assertEqual(self.device(out, self.gaps()), 1, (statuses, sha))

    def test_exit_is_0_when_the_rule_is_met(self):
        out = tempfile.mkdtemp()
        self.static_proof(out)
        self.assertEqual(self.device(out, self.gaps()), 0)

    def test_exit_is_0_without_network_gaps(self):
        self.assertEqual(self.device(tempfile.mkdtemp(), [Result("D1", "t", PASS, "ok")]), 0)

    def test_a_fail_still_exits_1(self):
        out = tempfile.mkdtemp()
        self.static_proof(out)
        self.assertEqual(self.device(out, self.gaps() + [Result("D2", "t", FAIL, "leak")]), 1)

    def test_a_static_run_after_the_device_run_reads_the_rule_too(self):
        out = tempfile.mkdtemp()
        self.assertEqual(self.device(out, self.gaps()), 1)  # no static proof yet
        with mock.patch("static_run.run_all", return_value=[Result(i, "t", PASS, "x") for i in ("S1", "S3", "S8")]), \
                mock.patch("static_run.sha256", return_value="abc"), mock.patch("os.path.exists", return_value=True):
            self.assertEqual(load_main().main(["static", "--apk", "/x/release.apk", "--out", out, "--no-deps"]), 0)
        with mock.patch("static_run.run_all", return_value=[Result("S1", "t", PASS, "x"), Result("S3", "t", PASS, "x")]), \
                mock.patch("static_run.sha256", return_value="abc"), mock.patch("os.path.exists", return_value=True):
            self.assertEqual(load_main().main(["static", "--apk", "/x/release.apk", "--out", out, "--no-deps"]), 1)  # S8 missing: not met


def load_main():
    """tools/leak_audit/__main__.py as a module (its name clashes with the test runner's __main__)."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("leak_audit_main", os.path.join(HERE, "__main__.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

if __name__ == "__main__":
    unittest.main()
