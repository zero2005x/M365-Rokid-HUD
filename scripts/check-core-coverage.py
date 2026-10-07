#!/usr/bin/env python3
"""Fail local/CI verification when the pure core loses its coverage floor."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--minimum", type=float, default=90.0)
    args = parser.parse_args()
    if not 0 <= args.minimum <= 100:
        parser.error("minimum must be between 0 and 100")
    report = ET.parse(args.report).getroot()
    counters = {item.attrib["type"]: item.attrib for item in report.findall("counter")}
    failed = False
    for metric in ("LINE", "BRANCH"):
        counter = counters.get(metric)
        if counter is None:
            raise ValueError(f"Missing report-level {metric} counter")
        covered, missed = int(counter["covered"]), int(counter["missed"])
        total = covered + missed
        if total <= 0:
            raise ValueError(f"Empty report-level {metric} counter")
        percent = covered / total * 100
        print(f"Core {metric}: {covered}/{total} ({percent:.2f}%; minimum {args.minimum:.2f}%)")
        failed |= percent < args.minimum
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
