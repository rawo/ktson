#!/usr/bin/env python3
"""Count official JSON-Schema-Test-Suite assertions the way OfficialTestSuiteRunner runs them.

Usage: count_assertions.py [SUITE_TESTS_DIR]   (default: ../JSON-Schema-Test-Suite/tests)

Keep EXCLUDED in sync with the skip list in src/test/kotlin/org/ktson/OfficialTestSuiteRunner.kt.
"""
import json
import os
import sys

DRAFTS = ["draft2019-09", "draft2020-12"]


def excluded(rel: str) -> bool:
    if rel in ("vocabulary.json", "infinite-loop-detection.json"):
        return True
    if rel.startswith("optional/"):
        return not (rel.startswith("optional/format/") or rel == "optional/bignum.json")
    return False


def main() -> None:
    base = sys.argv[1] if len(sys.argv) > 1 else "../JSON-Schema-Test-Suite/tests"
    total = run = 0
    for draft in DRAFTS:
        root = os.path.join(base, draft)
        d_total = d_run = 0
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not name.endswith(".json"):
                    continue
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, root).replace(os.sep, "/")
                with open(path, encoding="utf-8") as f:
                    n = sum(len(case["tests"]) for case in json.load(f))
                d_total += n
                if not excluded(rel):
                    d_run += n
        print(f"{draft}: total={d_total} run={d_run} excluded={d_total - d_run}")
        total += d_total
        run += d_run
    print(f"ALL: total={total} run={run} excluded={total - run}")


if __name__ == "__main__":
    main()
