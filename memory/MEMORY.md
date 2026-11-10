# KtSON Project Memory

Keep this file up to date along with the README, `docs/IMPLEMENTATION_STATUS.md` and `docs/FEATURES.md`: update all
of them in the same commit as every change (test counts, fixed issues, features, review decisions).

## Project State (as of 2026-11-06)

- Version: 4.0.0 (released 2026-11-04, tag `v4.0.0`; major because of breaking verdict changes since 3.1.0);
  later changes go under `## [Unreleased]` in `CHANGELOG.md`
- Kotlin 2.4.0, Java 21, Gradle 9.1.0, kotlinx-serialization-json, Kotest
- Official JSON Schema Test Suite: 4,462 assertions run, 100% passing (168 excluded), pinned by `TEST_SUITE_REF`
  in `.github/workflows/build.yml`
- Custom tests: 1,403 (100% passing); the full `./gradlew test` run is 5,873 tests
- `kotlinx-coroutines-core`/`-test` are test-only dependencies; the API is synchronous

## Architecture

- `JsonValidator.kt` (~1,600 lines): the validation engine; `validateElement()` has 22 recursive call sites
- `MetaSchemaCheck.kt` (`internal class MetaSchemaCheck`, ~120 lines): the `validateSchema()` machinery - nesting
  check, 64 MB-stack worker, uninterruptible join, lazily created meta-schema validator (#97); one per validator,
  created lazily, so it dies with it
- `Idna.kt` (`internal object Idna`, ~450 lines): Punycode, IDNA label rules and UTS 46 mapping for the hostname
  and email formats (#94)
- `EcmaRegex.kt` (`internal object EcmaRegex`, ~200 lines): the ECMA 262 regex dialect - the `regex` format's syntax
  check and the ECMA-to-Java pattern translation shared with `pattern` (#95); the pattern cache stays in the validator
- `Formats.kt` (`internal object Formats`, ~340 lines): the format vocabulary - `Formats.isValid(format, value)` and
  the date/time, email, JSON pointer, uuid, uri-template and duration grammars (#96); `formatAssertion` and the error
  stay in the validator
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
- **#94–#98 (2026-11-06): DONE**, the split of `JsonValidator.kt` (2,662 → ~1,610 lines). Each went through its own
  PR (#99–#103) with an independent review, merged by fast-forward.
  - #94–#97 moved the pure helpers out: `Idna`, `EcmaRegex`, `Formats` and `MetaSchemaCheck`.
  - #98: `startsResource()`/`resourceRootAt()`/`scopeAt()` replace three copies of the resource-root/scope rule.
  - Rejected, do not re-propose without a concrete need:
    - per-keyword classes or a `Keyword` interface (YAGNI; one recursive algorithm, no pluggable vocabularies)
    - moving the `unevaluated*` collectors into their own class (would widen private functions for file size alone)
    - a `FormatValidator` interface or registry, or one file per format (YAGNI)
    - moving `jsonEquals` or the numeric helpers (marginal)
  - Deferred: a `Scope` parameter object for the 7–10 parameters threaded through `validateElement`, only when a
    change is blocked by those signatures.
  - Follow-up #104 is done (below).
- **#104 (2026-11-06): DONE** (breaking verdict change). `startsResource()` requires a string `$id`, like every other
  `$id` reader, so a malformed `$id` no longer makes a subschema a resource root in the verdict or collection passes.
- **#105 (2026-11-10): DONE** (breaking verdict change). Found by a read-only review of 40b5b5e, filed with #106–#108
  (nested `unevaluated*` quadratic, `const`/`enum` big-number parsing, unresolvable remote ref reloaded per use; open).
  `enterResource()` registers a resource's absolute URI for the verdict pass and both collectors; before, only
  `validateElement()` did, and `withBase` undoes it after a hop (#91), so collectors lost a relative `$id` there.
- **#106 (2026-11-10): DONE** (perf, verdicts unchanged). Collected `unevaluated*` sets are remembered per `Application`
  in `Evaluation` while the instance is collected, like the #30 verdicts; not when undecided (the #51 test guards it).
  400 levels of `allOf` over 2,000 members: 10.2 s → 0.16 s.
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
