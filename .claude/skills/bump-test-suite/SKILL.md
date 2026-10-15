---
name: bump-test-suite
description: Bump the pinned JSON-Schema-Test-Suite SHA (TEST_SUITE_REF in .github/workflows/build.yml), sync the local ../JSON-Schema-Test-Suite clone, run the official suite and update the assertion counts in CLAUDE.md and README.md. Use when asked to bump, update or refresh the official test suite, its pin or its SHA.
disable-model-invocation: true
---

# Bump the official JSON Schema Test Suite

CI checks out the suite at the SHA in `TEST_SUITE_REF` (`.github/workflows/build.yml`), and
`OfficialTestSuiteRunner` reads it from `../JSON-Schema-Test-Suite`. The local clone must sit at
the same SHA as CI, or local and CI results diverge.

## 1. Record the current state

```bash
grep -n TEST_SUITE_REF .github/workflows/build.yml
git -C ../JSON-Schema-Test-Suite rev-parse HEAD
python3 .claude/skills/bump-test-suite/scripts/count_assertions.py   # baseline counts
```

If the local clone is missing, clone it:
`git clone https://github.com/json-schema-org/JSON-Schema-Test-Suite.git ../JSON-Schema-Test-Suite`.
If it has local changes, stop and ask before touching it.

## 2. Move the clone to the latest upstream main

```bash
git -C ../JSON-Schema-Test-Suite fetch -q origin main
git -C ../JSON-Schema-Test-Suite checkout -q origin/main
git -C ../JSON-Schema-Test-Suite log -1 --format='%H %cs %s'
```

If the new SHA equals the pinned one, report "already up to date" and stop.

Look at what changed in the drafts that run. Only these directories matter:

```bash
git -C ../JSON-Schema-Test-Suite diff --stat <OLD_SHA> HEAD -- tests/draft2019-09 tests/draft2020-12
```

Also note any **new files under `optional/`**. The runner skips `optional/` except
`optional/format` and `optional/bignum.json`, so new optional files are not run. Mention them to
the user rather than enabling them.

## 3. Run the official suite

```bash
./gradlew test --tests "OfficialTestSuiteRunner" --rerun-tasks
```

`--rerun-tasks` is needed: Gradle does not see the sibling suite as a task input and will
otherwise report UP-TO-DATE. Failed assertions print a `=== FAILED TEST ===` block with the file,
schema, data and expected verdict.

**If anything fails, do not bump the pin.** Put the clone back on the old SHA
(`git -C ../JSON-Schema-Test-Suite checkout -q <OLD_SHA>`) and report each failure: file, case,
expected vs actual verdict, and the upstream commit that added it
(`git -C ../JSON-Schema-Test-Suite log --format='%h %s' <OLD_SHA>..origin/main -- <file>`).
Offer two options: fix the validator first (a verdict change is a breaking change; see the
commit-message rules in CLAUDE.md), or pin to the last upstream commit before the failing
addition. Let the user choose.

## 4. Update the pin and the counts

When everything passes:

1. In `.github/workflows/build.yml`, set `TEST_SUITE_REF` to the full 40-character SHA and update
   the trailing comment to `# main @ <commit date, YYYY-MM-DD>`.
2. Recount: `python3 .claude/skills/bump-test-suite/scripts/count_assertions.py`.
   If the skip list in `OfficialTestSuiteRunner.kt` changed, update `excluded()` in the script first.
3. Replace the old numbers wherever they describe the *current* suite:
   - `CLAUDE.md`: the "At the pinned SHA that leaves X of Y assertions excluded … the Z that run
     pass 100%" sentence.
   - `README.md`: the Official Test Suite row of the status table, the test-coverage list and the
     "100% official test suite pass rate (Z/Z tests)" line.
   - Find any others with `grep -rnE '<old run count with and without comma>' --include='*.md' .`
   Leave `docs/SPEC_*.md` alone: their counts record the test run at the time each spec shipped.
4. Run the full check: `./gradlew test ktlintCheck`.

## 5. Report and commit

Report old SHA → new SHA, the upstream commit range, the old and new run/excluded/total counts, and
any new optional files. Then commit only if the user agrees:

```
ci: bump JSON-Schema-Test-Suite to <short SHA> (<YYYY-MM-DD>)

<one line: how many assertions were added, and which files they came from>
```
