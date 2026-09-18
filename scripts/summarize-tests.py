"""Export test names/counts only; never publish Surefire properties or logs."""

from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    reports = sorted(Path("devmate-server/target/surefire-reports").glob("TEST-*.xml"))
    lines = ["# Backend test summary", ""]
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for report in reports:
        suite = ET.parse(report).getroot()
        counts = {key: int(suite.get(key, "0")) for key in totals}
        for key, value in counts.items():
            totals[key] += value
        lines.append(f"- {report.stem}: {counts}")
    if not reports:
        lines.append("No test reports produced. This is not a passing test result.")
    lines.extend(["", f"Totals: {totals}", ""])
    output = Path("tmp/test-summary.md")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")
    print(lines[-2])


if __name__ == "__main__":
    main()
