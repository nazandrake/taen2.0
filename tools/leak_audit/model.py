"""Result model and the one report of the leak audit (static and device checks, planted-failure proofs)."""
import json
import os
import time

PASS, FAIL, GAP, INFO = "PASS", "FAIL", "GAP", "INFO"


class Result:
    """One check. status: PASS, FAIL (a leak or a broken invariant), GAP (could not be verified here, named) or INFO (a list, no verdict)."""

    def __init__(self, ident, title, status, summary, details=()):
        self.ident, self.title, self.status, self.summary, self.details = ident, title, status, summary, list(details)

    def as_dict(self):
        return {"id": self.ident, "title": self.title, "status": self.status, "summary": self.summary, "details": self.details}

    def __repr__(self):
        return "%s %s: %s" % (self.ident, self.status, self.summary)


def verdict(ident, title, problems, ok_summary, details=()):
    """PASS when there are no problems, else FAIL listing them."""
    if problems:
        return Result(ident, title, FAIL, "%d problem(s): %s" % (len(problems), "; ".join(problems[:3]) + (" ..." if len(problems) > 3 else "")), list(problems) + list(details))
    return Result(ident, title, PASS, ok_summary, details)


class Store:
    """results.json in the output directory holds the sections (static, device, selftest); report() renders all of them as one markdown file."""

    def __init__(self, out_dir):
        self.out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)
        self.path = os.path.join(out_dir, "results.json")
        try:
            with open(self.path) as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {}

    def put(self, section, results, meta=None):
        self.data[section] = {"time": time.strftime("%Y-%m-%d %H:%M:%S"), "meta": meta or {}, "results": [r.as_dict() for r in results]}
        with open(self.path, "w") as f:
            json.dump(self.data, f, indent=1, ensure_ascii=False)
        self.report()

    def report(self):
        lines = ["# Taen leak audit report", ""]
        counts = {}
        for section in ("static", "device", "selftest"):
            for r in self.data.get(section, {}).get("results", []):
                counts[(section, r["status"])] = counts.get((section, r["status"]), 0) + 1
        for section, label in (("static", "Static (APK)"), ("device", "Device"), ("selftest", "Planted failures (every check can fail)")):
            sec = self.data.get(section)
            if not sec:
                lines += ["## %s" % label, "", "not run", ""]
                continue
            lines += ["## %s" % label, "", "run %s" % sec["time"], ""]
            for k, v in sec["meta"].items():
                lines.append("- %s: %s" % (k, v))
            lines += ["", "| id | status | check | result |", "|---|---|---|---|"]
            for r in sec["results"]:
                lines.append("| %s | %s | %s | %s |" % (r["id"], r["status"], r["title"], r["summary"].replace("|", "/")))
            lines.append("")
            for r in sec["results"]:
                if r["details"]:
                    lines.append("### %s %s (%s)" % (r["id"], r["title"], r["status"]))
                    lines += ["    " + d for d in r["details"][:80]]
                    if len(r["details"]) > 80:
                        lines.append("    ... %d more lines in results.json" % (len(r["details"]) - 80))
                    lines.append("")
        total = {s: sum(v for (sec, st), v in counts.items() if st == s and sec != "selftest") for s in (PASS, FAIL, GAP, INFO)}
        lines.insert(2, "Totals (static + device): PASS %(PASS)d, FAIL %(FAIL)d, GAP %(GAP)d, INFO %(INFO)d" % total)
        lines.insert(3, "")
        with open(os.path.join(self.out_dir, "leak-audit-report.md"), "w") as f:
            f.write("\n".join(lines) + "\n")
        return total
