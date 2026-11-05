# KtSON Project Memory

Keep this file up to date along with the README, `docs/IMPLEMENTATION_STATUS.md` and `docs/FEATURES.md`: update all
of them in the same commit as every change (test counts, fixed issues, features, review decisions).

## Project State (as of 2026-11-04)

- Version: 4.0.0 (released 2026-11-04, tag `v4.0.0`; major because of breaking verdict changes since 3.1.0);
  later changes go under `## [Unreleased]` in `CHANGELOG.md`
- Kotlin 2.4.0, Java 21, Gradle 9.1.0, kotlinx-serialization-json, Kotest
- Official JSON Schema Test Suite: 4,462 assertions run, 100% passing (168 excluded), pinned by `TEST_SUITE_REF`
  in `.github/workflows/build.yml`
- Custom tests: 1,284 (100% passing); the full `./gradlew test` run is 5,849 tests
- `kotlinx-coroutines-core`/`-test` are test-only dependencies; the API is synchronous

## Architecture

- `JsonValidator.kt` (~2,660 lines): all validation logic; `validateElement()` has 22 recursive call sites
- `Evaluation.kt`: per-validation state (`ReferenceResolver`, `AbsoluteLocations`, undecided tracking,
  branch-verdict cache for `unevaluated*`)
- `JsonPointer.kt`: RFC 6901, `SchemaStore` (per validator), `ReferenceResolver` (per validation)
- `UriResolver.kt` / `UriSyntax.kt`: RFC 3986/3987 parsing and resolution, no `java.net.URI`
- `OutputFormat.kt`, `AbsoluteLocations.kt`, `MetaSchemas.kt`, `Divisibility.kt`, `SchemaKeywords.kt`
- See `CLAUDE.md` for the invariants to keep when changing validation logic

## Implemented

- Draft 2019-09 and 2020-12: all keywords, including `unevaluated*`, `$dynamicRef`/`$recursiveRef`, remote refs
  through `schemaLoader`
- 19 format validators; `formatAssertion` switches assertion on or off in both drafts
- Exact numeric arithmetic (`BigDecimal`), bounded for huge exponents (#70, #86, #87)
- Flag, Basic and Detailed output; `absoluteKeywordLocation`
- `validateSchema()` against the embedded meta-schemas
- Depth limit (`maxValidationDepth`, default 1000); undecided results never inverted into Valid

## Out of Scope (do not re-propose)

- Async/coroutines API, streaming, schema pool, Verbose output, GraalVM feature, Maven Central publishing
- `$vocabulary` (`vocabulary.json` is skipped in the official suite)

## Code Review Decisions

- **Issue [#83](https://github.com/rawo/ktson/issues/83): DONE. Do not revisit, re-investigate, or refile it in future
  code review rounds.** This exclusion is an explicit user instruction.
  - The Unicode URI-template defect was fixed in commit `6dec486`.
  - The apostrophe allegation was a false positive: RFC 6570 erratum 6937 permits literal apostrophes, and the
    pinned official suite requires acceptance. Keep apostrophes valid; do not report them again.
- **#79–#82 (2026-11-02): DONE**, verified on `d3c85f8`; do not refile.
  - #79: oversized IDN labels fail before Punycode work.
  - #80: regex group openers are scanned without suffix copies.
  - #81: `$schema` dialect matching is exact.
  - #82: ASCII labels in Bidi domains get the Bidi rule.
- **#84–#85 (2026-11-02): DONE**, verified on `c49c1e7`; do not refile.
  - #84: numbers are converted only for numeric keywords.
  - #85: Detailed output JSON is written iteratively.
- **#30, #90 (2026-11-03): DONE.**
  - #30: `unevaluated*` reuses branch verdicts, so nesting is no longer exponential.
  - #90 (breaking verdict change): collectors evaluate an inline `$id` subschema's branches in its own resource.
- **#86–#89 (2026-11-03): DONE.**
  - #86: `type`/`multipleOf` no longer throw for `100e2147483647`.
  - #87: a divisor's fives are counted in O(log n) divisions.
  - #88: IP formats reject impossible lengths first.
  - #89: `contains` without `maxContains` stops at `minContains` matches.
  - Tokens whose exponent is outside BigDecimal's 32-bit scale (`1e-2147483648`) remain documented as unsupported.
- **#91 (2026-11-04): DONE** (breaking verdict change). `$id` registrations made during a `withBase` hop are undone
  with it, so a document shared by several retrieval URIs resolves its nested relative `$id`s per context.
- **#92 (2026-11-04): DONE.** Anchor and `$id` searches are remembered per validation in `ReferenceResolver`.
  #13 (`AbsoluteLocations` searches when stamping failures) is a separate, still-open issue.
- **#93 (2026-11-04): DONE.** `preview()` cuts member names before escaping them, like string values.
- **#22 (removing this file): closed as irrelevant.** This file is kept and maintained.
- **#24: DONE (2026-11-03).** `docs/IMPLEMENTATION_STATUS.md` and `docs/FEATURES.md` describe the current state and
  are updated with every change.

## User Preferences

- AI agent playground project: most refactoring done by AI with human supervision
- Feature branch per issue, small tested commits, fast-forward to master without squashing, ask before pushing
- Run the full suite with the official suite (`CI=true ./gradlew test ktlintCheck`) before merging
- Workflows live in project skills: `/fix-issue` (review, fix or reject issues), `/release`, `/bump-test-suite`
- Conventional Commits; verdict changes are breaking (`!` and a `BREAKING CHANGE:` footer)
- Use constants from `SchemaKeywords`, not string literals
