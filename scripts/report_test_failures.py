#!/usr/bin/env python3
"""Print the names of failing and skipped unit tests from Gradle's JUnit XML reports.

Gradle's console output says "81 tests completed, 2 failed" without saying
which two. CI runs this after the test task so the failure is named in the
build log and in the commit comment, instead of only in a report artefact.

Skipped tests are named too, with their reason when the report has one. A test
that did not run has not passed, and the summary should not read as if it had:
the live Groq tests, for example, are skipped whenever no API key is available.

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
    skipped = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            print(f"Unreadable test report {os.path.basename(report)}: {error}")
            continue

        for case in root.iter("testcase"):
            skip = case.find("skipped")
            if skip is not None:
                skipped += 1
                print(f"SKIPPED {case.get('classname')}.{case.get('name')}")
                reason = (skip.get("message") or skip.text or "").replace("\n", " ").strip()
                # Always two lines per skipped test, so the CI summary can pair them.
                print(f"  {reason[:300] if reason else '(the report gives no reason)'}")
                continue
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
    elif skipped:
        print(f"No unit test failed; {skipped} did not run (listed above as SKIPPED).")
    else:
        print("All unit tests passed.")

    # Reporting only: the test task's own exit code decides whether the
    # build failed, so this script must never change it.
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
