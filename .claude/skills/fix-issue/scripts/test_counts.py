#!/usr/bin/env python3
"""Check (and with --write, fix) the test counts the docs state against the last test run.

Usage: test_counts.py [--write]      (run from the project root, after a full `./gradlew test --rerun-tasks`)

Counts come from build/test-results/test/*.xml, never from adding to the previous total:
- full:   every test in the run
- custom: every test except OfficialTestSuiteRunner and OfficialOutputTestRunner

Checked: the custom total in README.md, docs/FEATURES.md and docs/IMPLEMENTATION_STATUS.md, the custom and full
totals in memory/MEMORY.md, the per-file counts in CLAUDE.md's Test Structure list, and that the README's
per-area list adds up to the custom total. --write fixes the totals and the CLAUDE.md per-file counts; a test file
missing from CLAUDE.md and the README's per-area lines need a hand edit, since only you can describe them.
Exit status 1 if anything is still wrong.
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET

OFFICIAL = {"OfficialTestSuiteRunner", "OfficialOutputTestRunner"}
NOT_IN_RUN = {"PerformanceTest"}  # excluded from `./gradlew test`, listed in CLAUDE.md with its own count


def fmt(n: int) -> str:
    return f"{n:,}"


def load_results() -> dict:
    counts, failed = {}, 0
    for path in glob.glob("build/test-results/test/*.xml"):
        suite = ET.parse(path).getroot()
        name = suite.get("name").rsplit(".", 1)[-1]
        counts[name] = counts.get(name, 0) + int(suite.get("tests"))
        failed += int(suite.get("failures")) + int(suite.get("errors"))
    if not counts:
        sys.exit("No results in build/test-results/test: run `CI=true ./gradlew test --rerun-tasks` first.")
    if failed:
        sys.exit(f"The last run has {failed} failing tests: fix them before counting.")
    if "OfficialTestSuiteRunner" not in counts or counts["OfficialTestSuiteRunner"] < 1000:
        sys.exit("The official suite did not run: rerun with CI=true and ../JSON-Schema-Test-Suite present.")
    return counts


def main() -> None:
    write = "--write" in sys.argv[1:]
    counts = load_results()
    full = sum(counts.values())
    custom = sum(n for name, n in counts.items() if name not in OFFICIAL)
    print(f"full run: {fmt(full)}, custom: {fmt(custom)}")
    problems = []

    # Totals: (file, pattern whose groups are (prefix, number, suffix), expected value as written)
    totals = [
        ("README.md", r"(- \*\*Custom Tests\*\*: )([\d,]+)( tests)", str(custom)),
        ("docs/FEATURES.md", r"(- \*\*Custom tests:\*\* )([\d,]+)(,)", fmt(custom)),
        ("docs/IMPLEMENTATION_STATUS.md", r"(\*\*Custom tests\*\*: )([\d,]+)( )", fmt(custom)),
        ("memory/MEMORY.md", r"(- Custom tests: )([\d,]+)( \(100% passing\))", fmt(custom)),
        ("memory/MEMORY.md", r"(the full `\./gradlew test` run is )([\d,]+)( tests)", fmt(full)),
    ]
    for path, pattern, expected in totals:
        text = open(path, encoding="utf-8").read()
        match = re.search(pattern, text)
        if not match:
            problems.append(f"{path}: no line matches {pattern!r}")
            continue
        if match.group(2) != expected:
            if write:
                text = text[: match.start(2)] + expected + text[match.end(2):]
                open(path, "w", encoding="utf-8").write(text)
                print(f"fixed {path}: {match.group(2)} -> {expected}")
            else:
                problems.append(f"{path}: says {match.group(2)}, the run has {expected}")

    # CLAUDE.md per-file list
    text = open("CLAUDE.md", encoding="utf-8").read()
    entry = re.compile(r"^(- \*\*(\w+)\.kt\*\* - )(\d+)( )", re.M)
    listed = set()

    def fix(match: re.Match) -> str:
        name, stated = match.group(2), int(match.group(3))
        listed.add(name)
        if name in NOT_IN_RUN or name not in counts or counts[name] == stated:
            if name not in NOT_IN_RUN and name not in counts:
                problems.append(f"CLAUDE.md: {name}.kt is listed but has no results (renamed or deleted?)")
            return match.group(0)
        if not write:
            problems.append(f"CLAUDE.md: {name}.kt says {stated}, the run has {counts[name]}")
            return match.group(0)
        print(f"fixed CLAUDE.md: {name}.kt {stated} -> {counts[name]}")
        return f"{match.group(1)}{counts[name]}{match.group(4)}"

    updated = entry.sub(fix, text)
    if write and updated != text:
        open("CLAUDE.md", "w", encoding="utf-8").write(updated)
    listed |= set(re.findall(r"^- \*\*(\w+)\.kt\*\* - ", text, re.M))
    for name in sorted(set(counts) - listed - OFFICIAL):
        problems.append(f"CLAUDE.md: {name}.kt ({counts[name]} tests) is missing from the Test Structure list")

    # README per-area list under "Custom Tests"
    readme = open("README.md", encoding="utf-8").read()
    block = re.search(r"- \*\*Custom Tests\*\*: [\d,]+ tests.*?\n((?:  - .*\n)+)", readme)
    if block:
        listed_sum = sum(int(n.replace(",", "")) for n in re.findall(r": ([\d,]+) tests?$", block.group(1), re.M))
        if listed_sum != custom:
            problems.append(f"README.md: the per-area list adds up to {fmt(listed_sum)}, the run has {fmt(custom)}")
    else:
        problems.append("README.md: no per-area list under Custom Tests")

    for problem in problems:
        print("TODO " + problem)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
