---
name: fix-issue
description: Review KtSON GitHub issues critically, then fix or reject each one on its own branch - a test that fails on the old code, ktlint, the full suite with the official suite, every doc updated in the same commit, fast-forward master, push, close. Use when asked to review, fix or close issues (for example "new issues from #91 to #93").
argument-hint: "<issue numbers, e.g. 91 92 93 or 91-93>"
---

# Review and fix GitHub issues

Issues: $ARGUMENTS. Handle them one at a time, in order, each from an up-to-date master. Finish one (merged,
pushed, closed) before starting the next.

Before the first one, read `memory/MEMORY.md` ("Out of Scope" and "Code Review Decisions"). Never revisit #83, never
re-propose what is out of scope, and treat an issue that refiles a recorded decision as a rejection.

## 1. Review: be critical

```bash
gh issue view <n> --comments
```

Reproduce the claim on master before believing it: a failing test, or a scratch program in the scratchpad.
For performance claims, measure allocation and time on the issue's own reproducer. Issues overstate and understate:
check whether the claim holds, whether it is wider than described (for example untrusted instances hitting the same
path as schemas), and whether the proposed fix is right.

Decide:
- **Fix** when the defect is real and the fix costs less than it saves: wrong verdicts, throwing from `validate()`,
  cost proportional to something other than the input as written.
- **Reject** a false positive, an out-of-scope request or a fix that costs more than it saves. Close it with a comment
  that gives the evidence (the test, the spec section, the measurement):
  `gh issue close <n> --comment "..."`. Record the decision in `memory/MEMORY.md` under "Code Review Decisions",
  commit (`docs(memory): record the decision on #<n>`), and push.

## 2. Branch and test first

```bash
git switch master && git pull --ff-only
git switch -c fix/<n>-<short-slug>
```

Write the test before the fix and run it to see it fail on the old code. Put it in a new `<Topic>Test.kt` when the
issue is a topic of its own, else in the existing file for that area. Cover both drafts where the keyword exists in
both. For performance issues, assert a bound: allocation through `ThreadMXBean` or time with a generous margin. Guard
tests that pass before and after (for example the boundary values) are fine, but say so in the report.

## 3. Fix

Follow CLAUDE.md, especially "When Modifying Validation Logic" and "Reference Resolution". In particular:
- Locations go on every error through `at()`.
- Undecided results are never inverted.
- Reference keywords are resolved only in `resolveReferences()`.
- Caches stay per instance or per validation, never global.
- Match the surrounding code's style and comment density.

Then check the test catches the bug: revert or mutate the fix's key line and watch the test fail.

## 4. Verify

```bash
./gradlew ktlintFormat
CI=true ./gradlew test ktlintCheck --rerun-tasks
```

`CI=true` makes the official suite mandatory. `--rerun-tasks` matters because Gradle does not see the sibling suite
as an input. Merge only when this passes; on a failure, fix it and run both commands again.

## 5. Docs, in the same commit

Update whatever the change makes stale (CLAUDE.md "Keeping docs current"):
- `CHANGELOG.md` under `## [Unreleased]`: what was wrong, what happens now, with the measurements for performance
  fixes and `(#<n>)` at the end. Start a verdict change with **Breaking verdict change:**.
- `CLAUDE.md`: a Test Structure entry for a new test file, and any new invariant future changes must keep.
- `README.md`: a per-area line under Custom Tests for a new test file, and features or limitations that changed.
- `docs/IMPLEMENTATION_STATUS.md` and `docs/FEATURES.md`, if a feature, limitation or guarantee changed.
- `memory/MEMORY.md`: the review decision under "Code Review Decisions" (date, issue, one line on the fix).

Then recount from the test run you just did:

```bash
python3 .claude/skills/fix-issue/scripts/test_counts.py --write
```

This fixes the totals and CLAUDE.md's per-file counts from `build/test-results`. Any `TODO` lines it prints are left
for you, such as a missing CLAUDE.md entry or a README per-area list that doesn't add up. Fix them and rerun until it
exits 0. Never compute a count by adding to the old total.

## 6. Commit

Conventional Commits, because release notes are generated from them:

```
<fix|perf|feat>(<scope>)[!]: <what the code does now, lowercase>

<What was wrong and its cost, what changed, measurements. Verdicts unchanged, if so.>

Closes #<n>

BREAKING CHANGE: <only for verdict or API changes: what callers will see differ>

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
```

Verdict changes are breaking even when the API is untouched: use `!` and the footer.

## 7. Merge, push, close

```bash
git switch master
git merge --ff-only fix/<n>-<short-slug>   # if master moved: rebase the branch onto it, never squash
git branch -d fix/<n>-<short-slug>
git push origin master
gh issue close <n> --comment "Fixed in <sha>. <what changed, measurements, which test covers it>"
```

Push only when the request asked to merge and close. Otherwise stop after the merge and ask. Push before commenting,
so the comment's commit exists on GitHub. `Closes #<n>` may close the issue on push already; comment anyway.

## 8. Report

One row per issue: worth doing or not and why, what changed, the commit, measurements, the final test count. Say
plainly what was skipped or failed and what stays open (related issues you noticed but did not fix).
