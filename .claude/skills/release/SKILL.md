---
name: release
description: Prepare and publish a KtSON release - choose the version from the commits since the last tag, bump every version reference, write the CHANGELOG section and README upgrade notes, run the full build, then after confirmation push, tag and watch release.yml. Use when asked to prepare, cut or publish a release or bump the version.
argument-hint: "[version, e.g. 4.1.0; chosen from the commits if omitted]"
---

# Prepare and publish a release

`.github/workflows/release.yml` runs on a pushed `v*` tag. It fails unless the tag is on master and equals `v` plus
the `build.gradle.kts` version. It then runs the CI build (tests, official suite at `TEST_SUITE_REF`, ktlint) and
creates the GitHub release with generated notes, the JAR and the sources JAR. KtSON is not published to Maven Central.

## 1. Check the starting point

```bash
git switch master && git pull --ff-only && git status --short   # must be clean
gh auth status
```

## 2. Choose the version

```bash
prev=$(git describe --tags --abbrev=0)
git log --oneline "$prev"..HEAD
git log --format='%s%n%b' "$prev"..HEAD | grep -cE '^[a-z]+(\([^)]*\))?!:|^BREAKING CHANGE:'
```

Semantic Versioning, as the CHANGELOG promises:
- **major**: any breaking commit, verdict changes included
- **minor**: any `feat`
- **patch**: otherwise

Use $ARGUMENTS if given, but say so if it contradicts the commits. State the version and the reason before editing.

Look at `TEST_SUITE_REF` in `.github/workflows/build.yml` too. If its date comment is more than a month old, offer
`/bump-test-suite` before releasing, as a separate commit. Don't do it unasked.

## 3. Edit, on `release/<version>`

```bash
git switch -c release/<version>
```

- `build.gradle.kts`: `version = "<version>"`.
- `README.md`:
  - `git checkout v<version>` and the `publishToMavenLocal` comment
  - the three dependency snippets (Kotlin DSL, Groovy, Maven)
  - `**Version**:` under Project Status
- `CLAUDE.md`: the `**Status**:` line.
- `CHANGELOG.md`: below `## [Unreleased]`, which stays and is left empty, insert `## [<version>] - <today>` and a
  one-paragraph summary of what kind of release it is. Point to the README's upgrade section when anything breaks.
- `README.md`: a `## Upgrading from <prev>` section above the previous one, in the style of the existing ones.
  - **Minor or patch**: "drop-in replacement" and the additions.
  - **Major**, grouped:
    - what must be recompiled
    - API changes
    - `schemaLoader` URI changes
    - verdict changes by area
    - what no longer throws
    - "Pin <prev> if you need the old verdicts."

    Derive it from the CHANGELOG section, not from memory.
- Every "unreleased" that now means this version:
  `git grep -ni unreleased -- '*.md' ':!CHANGELOG.md' ':!docs/superpowers'`. Typical places are CLAUDE.md, the
  `docs/IMPLEMENTATION_STATUS.md` heading (`## Current State (<version>, <today>)`) and `docs/SPEC_*.md` status lines.
  Leave plans under `docs/superpowers/` alone: they are history.
- `git grep -n '<prev>'`: update the references to the *current* version. Leave historical ones ("added after
  3.1.0", older Upgrading sections, CHANGELOG entries).
- `memory/MEMORY.md`: the Version line (`<version> (release prepared <today>)`).

## 4. Verify

```bash
./gradlew ktlintFormat
CI=true ./gradlew build --rerun-tasks
ls build/libs/ktson-<version>.jar build/libs/ktson-<version>-sources.jar
python3 .claude/skills/fix-issue/scripts/test_counts.py
```

All must pass, and the count check must exit 0. Fix stale counts before releasing.

## 5. Commit and merge

```
chore: bump version to <version>

<One paragraph: what kind of release and why that version. Which docs changed.>

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
```

```bash
git switch master && git merge --ff-only release/<version> && git branch -d release/<version>
```

## 6. Ask, then push and tag

Pushing the tag publishes the release. Stop and ask, showing the version, the reason and these commands. Ask in the
same question whether to record the release in `memory/MEMORY.md` afterwards.

```bash
git push origin master
git tag -a v<version> -m v<version>
git push origin v<version>
```

## 7. Watch the release

```bash
gh run list --workflow release.yml --limit 1 --json databaseId,status
gh run watch <id> --exit-status --interval 30    # run in the background; you are notified when it ends
gh release view v<version> --json url,assets --jq '.url, (.assets[].name)'
```

On failure, report the failing job (`gh run view <id> --log-failed`). Never delete or move a pushed tag without
asking.

On success, set the Version line in `memory/MEMORY.md` to `<version> (released <today>, tag v<version>)`. Commit it as
`docs(memory): record the <version> release` and push it, if the user agreed in step 6. Report the release URL and
assets.
