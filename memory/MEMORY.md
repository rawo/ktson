# KtSON Project Memory

## Code Review Decisions (2026-11-02)

- **Issues #84 and #85: DONE — fixes independently verified on fetched master `c49c1e7`.** The original findings are resolved; do not refile them. Verification passed 5,804 standard tests, nine performance tests, Gradle checks, and five independent tests.
- #84: schemas without numeric assertions skip conversion; the original 100,000-digit empty-schema case now allocates 544 bytes and takes about 0.004 ms instead of 484 KB and 225 ms. Exact numeric assertions and integer classification were verified.
- #85: Detailed JSON serialization is iterative. The original depth-950 result now serializes on a 1 MB consumer stack, matching generated serialization exactly. Another 250 branching/escaping comparisons and direct serialization of a 100,000-level output on that stack passed.
- Verification report: `/private/tmp/ktson-issues84-85-verification/VERIFICATION_SUMMARY.txt`. The default branch remains `master`, not `main`; both fixing commits are present there. No GitHub issue states or comments were changed.
- **Issues #79, #80, #81, and #82: DONE — fixes independently verified on fetched master `d3c85f8`.** The original findings are resolved; do not refile the same defects. Verification passed 5,794 standard tests, nine performance tests, Gradle checks, and six independent reproducer/boundary tests.
- #79: oversized IDN labels now fail before Punycode work; the original 2,048-code-point case allocates about 16 KB instead of 65 MB. Mapping, supplementary characters, and the exact A-label length boundary were verified.
- #80: regex group openers are scanned without suffix copies; the original 4,096-group case allocates about 485 KB instead of 43 MB, with linear scaling and syntax controls verified.
- #81: dialect matching is exact, allowing an empty fragment; unknown identifiers retain the caller's default. Both drafts and the Java API were verified.
- #82: ASCII labels in Bidi domains receive Bidi checks; reported hostname/email cases are rejected consistently while valid ASCII and internationalized controls remain accepted.
- Verification report: `/private/tmp/ktson-issues79-82-verification/VERIFICATION_SUMMARY.txt`. No GitHub issue states or comments were changed during verification.
- **Issue [#83](https://github.com/rawo/ktson/issues/83): DONE. Do not revisit, re-investigate, or refile it in future code review rounds.** This exclusion is an explicit user instruction.
- The Unicode URI-template defect was fixed in commit `6dec486`; the response review passed all 418 format-validation tests, including the new regressions.
- The apostrophe allegation was a false positive: verified RFC 6570 erratum 6937 permits literal apostrophes, and the pinned official suite requires acceptance. Keep apostrophes valid; do not report them again as a URI-template defect.

## Project State (as of 2026-02-22)

- Version: 0.0.2-SNAPSHOT, pre-release
- Pass rate: ~92.8% on official test suite (2,205/2,376 tests)
- Custom tests: 155 tests, 100% passing
- Kotlin 2.2.20, Java 21, Gradle 9.1.0

## Architecture

- `JsonValidator.kt` (995 lines) — monolithic, all validation logic, 23 recursive call sites
- `JsonPointer.kt` — RFC 6901 + `ReferenceResolver` inner class
- `UriResolver.kt` — RFC 3986 URI utilities (added in remote refs phase 1)
- `SchemaKeywords.kt` — all keyword constants, always use these not string literals
- `JsonSchema.kt` — schema wrapper with version, $id, baseUri (updated in phase 1)
- `ValidationResult.kt` / `ValidationError.kt` — sealed result type

## What Is and Isn't Implemented

### Done ✅
- All core validation keywords (type, properties, arrays, combiners, conditionals, const, enum)
- 8 format validators: email, uri, date, time, date-time, ipv4, ipv6, uuid
- Local `$ref` resolution (`#/...`, `#/$defs/...`)
- Basic `$recursiveRef` / `$dynamicRef` (treated as static $ref)
- Depth limiting (configurable, default 1000)
- URI resolver + $id extraction (remote refs phase 1)
- Thread-safe, lock-free, stateless design

### Not Done ❌
- `unevaluatedProperties` / `unevaluatedItems` (~100 failures, needs eval tracking)
- Remote HTTP/HTTPS schema references (phase 2+)
- Full `$dynamicRef` / `$dynamicAnchor` dynamic scoping (~40 failures)
- `$vocabulary` meta-schema validation (~10 failures)
- Advanced format validators (hostname, idn-email, iri, etc.)
- Unicode grapheme cluster counting (needs ICU4J, ~2 failures)
- Numeric type distinction 1.0 vs 1 (kotlinx.serialization limitation, ~16 failures)

## Test Suite Skipped Files (OfficialTestSuiteRunner.kt)

These are intentionally skipped — some may now be re-enableable:
- `ref.json` — local ref (now implemented! worth re-testing)
- `defs.json` — $defs (now implemented! worth re-testing)
- `anchor.json` — $anchor (partially done via $id)
- `id.json` — $id (phase 1 added $id support, worth re-testing)
- `refRemote.json` — remote refs (not yet)
- `vocabulary.json` — $vocabulary (not implemented)
- `infinite-loop-detection.json` — circular refs (depth limit covers this)
- `optional/*` — optional features

## Stale Docs (do not trust these for current state)
- `docs/IMPLEMENTATION_STATUS.md` — reflects pre-$ref-implementation state (88.8%)
- `docs/OFFICIAL_TEST_SUITE_RESULTS.md` — also outdated (88.8%)

## Remote References Plan
- Phase 1 (done): UriResolver, $id extraction, base URI tracking in JsonSchema
- Phase 2 (next): HTTP client (Ktor recommended), SchemaFetcher with security controls
- Phase 3: SchemaCache (ConcurrentHashMap, TTL, LRU)
- Phase 4: Integration into ReferenceResolver + baseUri param in validateElement()
- Phase 5: Tests + docs
- See `docs/REMOTE_REFERENCES_IMPL_PLAN.md` and `docs/HTTP_CLIENT_ANALYSIS.md`

## Unused Dependency
- `kotlinx-coroutines-core` still in build.gradle.kts but unused after async→sync migration

## User Preferences
- AI agent playground project — most refactoring done by AI with human supervision
- Run `./gradlew ktlintFormat` before committing
- Use constants from SchemaKeywords, not string literals
