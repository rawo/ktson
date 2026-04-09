# KtSON Project Memory

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
