#!/usr/bin/env python3
"""Fail closed when focused VDisplay tests are empty, broad or failing."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def verify(directory: Path) -> int:
    required = {
        "CoreVDisplayContractTest", "VDisplayUiModelTest",
        "VDisplayHandlerContractTest", "VDisplayScreenshotPathTest",
        "VDisplayCliParseTest", "VDisplayUnicodeContractTest",
    }
    seen = set()
    total = 0
    failures = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        count = int(suite.get("tests", "0"))
        name = suite.get("name", "")
        seen.add(name.rsplit(".", 1)[-1])
        total += count
        failed = int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
        skipped = int(suite.get("skipped", "0"))
        print(f"{name}: tests={count}, failed={failed}, skipped={skipped}")
        if "VDisplay" not in name or count == 0 or failed or skipped:
            failures.append(f"unexpected, empty, failed or skipped suite: {name}")
    missing = required - seen
    if missing:
        failures.append("missing required suites: " + ", ".join(sorted(missing)))
    if not total:
        failures.append("no tests executed")
    for message in failures:
        print("ERROR:", message)
    print(f"Total executed: {total}; suites: {len(seen)}")
    return 1 if failures else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    raise SystemExit(verify(parser.parse_args().directory))
