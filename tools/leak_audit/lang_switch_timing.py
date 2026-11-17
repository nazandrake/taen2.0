"""D15 measurement on the phone: how long the first switch English -> Bulgarian leaves the keyboard unable to type.
usage: python3 lang_switch_timing.py <audit package> [idle seconds before the switch, default 30] [runs, default 3]
Each run: stop the app (a cold service), open a text field, wait for the dictionaries, tap the language key, then tap the top-left letter key (q in
English, я in Bulgarian phonetic) about every 0.3 s, 80 taps in one shell command (timing on the phone, not over adb). The field then holds the letters
that were typed; taps that gave no Bulgarian letter (lost, or still English) times the measured tap interval is the delay.
Prints the delay per run. Needs the helper app (tools/leak_audit/helper, installed by the device audit) and the phone on adb."""
import os
import sys
import time

import device_run as dr


class A:
    serial = None
    out = os.path.expanduser("~/.cache/taen-audit/devout")


def main():
    pkg = sys.argv[1]
    idle = float(sys.argv[2]) if len(sys.argv) > 2 else 30.0
    runs = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    run = dr.Run(root, A())
    run.pkg = pkg
    d = run.dev
    d.wake()
    d.sh("ime set %s/helium314.keyboard.latin.LatinIME" % pkg)
    for n in range(runs):
        d.wake()
        d.sh("am force-stop %s" % pkg)
        time.sleep(2)
        d.sh("ime set %s/helium314.keyboard.latin.LatinIME" % pkg)
        run.field("normal")
        # the service remembers the last language: start every run in English (a probe letter shows which layout is up)
        d.tap(54, 1335)
        time.sleep(1.5)
        if any("\u0400" <= c <= "\u04ff" for c in (run.field_text() or "")):
            d.tap(324, 1822)
            time.sleep(25)  # the switch back to English also loads a dictionary
            d.sh("am force-stop %s" % pkg)  # a cold start in English: this is the situation measured
            time.sleep(2)
            d.sh("ime set %s/helium314.keyboard.latin.LatinIME" % pkg)
            run.field("normal")
        time.sleep(idle)  # the first dictionary is loaded; with D15 the other language is warmed meanwhile
        d.wake()
        d.tap(324, 1822)  # the language key (measured position on the reference phone, see device_flow.language_key_check)
        taps = int(20 / 0.25)
        t0 = time.time()
        d.sh("; ".join(["input tap 54 1335; sleep 0.2"] * taps), timeout=120)
        interval = (time.time() - t0) / taps  # measured: `input tap` itself takes time on the phone
        text = run.field_text() or ""
        bg = sum(1 for c in text if "Ѐ" <= c <= "ӿ")
        print("run %d: %d taps, %.2f s apart, %d Bulgarian letters typed, %d other: delay about %.1f s (field %r)" % (
            n + 1, taps, interval, bg, len(text) - bg - len("normal"), (taps - bg) * interval, text[-30:]))
    d.sleep_screen()


if __name__ == "__main__":
    main()
