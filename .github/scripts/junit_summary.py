#!/usr/bin/env python3
"""Print a per-test table of JUnit XML results and fail if nothing ran.

Usage: junit_summary.py <results dir> [--min-tests N]

Written as GitHub-flavoured markdown so it can be appended to $GITHUB_STEP_SUMMARY.
Skipped tests are listed explicitly: an assumption failure is how a test silently
stops covering anything.
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    args = sys.argv[1:]
    min_tests = 1
    if "--min-tests" in args:
        i = args.index("--min-tests")
        min_tests = int(args[i + 1])
        del args[i : i + 2]
    root = args[0]

    rows = []
    for path in sorted(glob.glob(f"{root}/**/TEST-*.xml", recursive=True)):
        for case in ET.parse(path).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                status = "FAILED"
            elif case.find("skipped") is not None:
                status = "SKIPPED"
            else:
                status = "passed"
            rows.append((status, case.get("classname", "?").rsplit(".", 1)[-1], case.get("name", "?"), float(case.get("time", 0))))

    counts = {s: sum(1 for r in rows if r[0] == s) for s in ("passed", "SKIPPED", "FAILED")}
    print(f"### Instrumented tests: {len(rows)} ran, {counts['passed']} passed, {counts['SKIPPED']} skipped, {counts['FAILED']} failed\n")
    print("| Result | Class | Test | Time |\n|---|---|---|---|")
    for status, cls, name, t in rows:
        print(f"| {status} | {cls} | {name} | {t:.1f}s |")

    if len(rows) < min_tests:
        print(f"\nExpected at least {min_tests} tests but found {len(rows)} (results dir: {root}).")
        return 1
    return 1 if counts["FAILED"] else 0


if __name__ == "__main__":
    sys.exit(main())
