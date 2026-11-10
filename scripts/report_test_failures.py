#!/usr/bin/env python3
"""Print the names of failing unit tests from Gradle's JUnit XML reports.

Gradle's console output says "81 tests completed, 2 failed" without saying
which two. CI runs this after the test task so the failure is named in the
build log and in the commit comment, instead of only in a report artefact.

Usage: python3 scripts/report_test_failures.py build/test-results/test
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: report_test_failures.py <results-dir>")
        return 2

    results_dir = argv[1]
    reports = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
    if not reports:
        print(f"No JUnit XML reports found in {results_dir}.")
        return 0

    failures = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            print(f"Unreadable test report {os.path.basename(report)}: {error}")
            continue

        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                failures += 1
                print(f"FAILED {case.get('classname')}.{case.get('name')}")
                message = (node.get("message") or "").replace("\n", " ").strip()
                if message:
                    print(f"  {message[:600]}")
                body = (node.text or "").strip().splitlines()
                if body:
                    print(f"  {body[0][:600]}")
                break

    if failures:
        print(f"{failures} failing test(s).")
    else:
        print("All unit tests passed.")

    # Reporting only: the test task's own exit code decides whether the
    # build failed, so this script must never change it.
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
