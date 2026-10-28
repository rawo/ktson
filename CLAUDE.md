# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KtSON is a JSON Schema validator for Kotlin with comprehensive support for JSON Schema Draft 2019-09 and 2020-12. The project is a **playground for testing AI agents** in programming, with most code refactoring performed by AI with human supervision.

**Package**: `org.ktson`
**Tech Stack**: Kotlin 2.4.0, Java 21, Gradle 9.1.0, kotlinx-serialization-json, Kotest
**Status**: 3.1.0, 100% official test suite coverage

## Common Commands

### Build Commands
```bash
# Full build (compile + test + lint)
./gradlew build

# Compile only
./gradlew compileKotlin

# Create JAR
./gradlew jar
```

### Testing Commands
```bash
# Run all tests (excludes performance tests by default)
./gradlew test

# Run specific test suite
./gradlew test --tests "Draft201909ValidationTest"
./gradlew test --tests "Draft202012ValidationTest"
./gradlew test --tests "EdgeCaseAndThreadSafetyTest"
./gradlew test --tests "OfficialTestSuiteRunner"

# Run performance tests (separate task)
./gradlew performanceTest

# Run all tests including performance
./gradlew test performanceTest
```

### Code Quality
```bash
# Check code style with ktlint
./gradlew ktlintCheck

# Auto-format code with ktlint
./gradlew ktlintFormat
```

## Architecture

### Core Components

The validator architecture is straightforward with minimal abstractions:

1. **JsonValidator** (`JsonValidator.kt`) - Main validation engine (~90KB)
   - Thread-safe synchronous validation
   - Contains all validation logic in a single class
   - Creates a per-validation `Evaluation` (`Evaluation.kt`) holding a `ReferenceResolver` for `$ref` resolution
   - Entry point: `validate(instance: JsonElement, schema: JsonSchema): ValidationResult`

2. **JsonSchema** (`JsonSchema.kt`) - Schema representation
   - Wraps `JsonElement` with version metadata
   - Auto-detects schema version from `$schema` property
   - Factory methods: `fromString()`, `fromElement()`

3. **ValidationResult** (`ValidationError.kt`) - Sealed class result type
   - `Valid` - validation succeeded
   - `Invalid(validationErrors: List<ValidationError>)` - validation failed
   - Each error contains: instanceLocation (RFC 6901), message, keyword, schemaPath, causes, absoluteKeywordLocation
   - `path` is a deprecated computed legacy display string; never use it for navigation

4. **SchemaKeywords** (`SchemaKeywords.kt`) - Constants object
   - All JSON Schema keywords as constants (REF, TYPE, PROPERTIES, etc.)
   - Valid type names and format types
   - Use these constants instead of string literals

5. **JsonPointer** (`JsonPointer.kt`) - JSON Pointer (RFC 6901) implementation
   - Resolves paths like `/properties/name` or `#/$defs/address`
   - Also contains `SchemaStore` (remote schemas, per validator) and `ReferenceResolver` (per validation)

6. **UriResolver** (`UriResolver.kt`) - RFC 3986 URI resolution
   - Resolves reference URIs against base URIs, used when following `$id`/`$ref` chains

7. **SchemaVersion** (`SchemaVersion.kt`) - Enum of supported drafts (`DRAFT_2019_09`, `DRAFT_2020_12`)

8. **OutputFormat** (`OutputFormat.kt`) - Standard JSON Schema output (2020-12 §12)
   - `FlagOutput`, `BasicOutput`/`BasicUnit`, `DetailedOutput`, selected by the `OutputFormat<T>` token
   - `validate(instance, schema, format)` / `result.toOutput(format)`; a pure mapping over `ValidationResult`
   - Serialise with `toJson()`, never through the `ValidationOutput` interface
   - `@file:JvmName("ValidationOutputs")`: Java calls `ValidationOutputs.toOutput(...)`; renaming it breaks Java callers

9. **AbsoluteLocations** (`AbsoluteLocations.kt`) - Fills `ValidationError.absoluteKeywordLocation`
   - After each `$ref`/`$dynamicRef`/`$recursiveRef` hop, `stamp()` locates the target in its resource and stamps the hop's new errors; the innermost hop wins
   - `finish()` in `validateInternal()` stamps the rest against the root URI (absolute `$id`, else `JsonSchema.baseUri`)
   - Only runs when a hop produced errors; an unknown or unparsable URI leaves the field null, never fails validation

10. **MetaSchemas** (`MetaSchemas.kt`) - The official 2019-09 and 2020-12 meta-schemas, embedded as source
   - Used by `validateSchema()`, which validates the schema against its draft's meta-schema with a separate,
     lazily created validator (format assertion on) on a thread with a 64 MB stack, after an iterative
     nesting check against `maxValidationDepth`. The worker inherits no thread-locals and is joined
     uninterruptibly (interrupt status restored), so it never outlives the call (#56)
   - Generated from `src/test/resources/schemas/draft/`; keep the two in sync (no runtime resources)

### Validation Flow

```
validate()
  → validateInternal()
    → validateElement() [MAIN RECURSIVE FUNCTION]
      → Resolves $ref/$recursiveRef/$dynamicRef
      → Validates type, const, enum
      → Dispatches to type-specific validators:
        - validateObject() → recursively validates properties
        - validateArray() → recursively validates items
        - validateString()
        - validateNumber()
      → Validates combiners (allOf/anyOf/oneOf/not) → recursive
      → Validates conditionals (if/then/else) → recursive
```

**CRITICAL**: `validateElement()` has **30 recursive call sites** (plus one entry point from `validateInternal()`). See "Known Limitations" below.

### Test Structure

- **Draft201909ValidationTest.kt** - 47 tests for Draft 2019-09 features
- **Draft202012ValidationTest.kt** - 54 tests for Draft 2020-12 features
- **EdgeCaseAndThreadSafetyTest.kt** - 39 edge case and concurrency tests
- **FormatValidationTest.kt** - 330 tests covering all supported format validators
- **UriResolverTest.kt** - 75 tests for RFC 3986 URI resolution, including every §5.4 example
- **DepthLimitTest.kt** - 14 tests for `maxValidationDepth` protection
- **ErrorMessageTest.kt** - 32 tests for error content (instance path, keyword, keyword location, wording)
- **NumericPrecisionTest.kt** - 88 tests for exact numeric comparisons and concurrent numeric validation
- **InstanceLocationTest.kt** - 117 tests for instance pointers, schema-structure locations, causes and legacy access
- **OutputFormatTest.kt** - 40 tests for the Flag, Basic and Detailed output formats
- **ValidatorLifecycleTest.kt** - 3 tests: a dropped validator frees its schema, remote schemas and caches; a live one does not keep schemas with `$id` it validated, and its pattern and numeric-limit caches stay bounded
- **FormatAssertionOptionTest.kt** - 4 tests: `formatAssertion` true asserts and false does not, in both drafts
- **SchemaMetadataTest.kt** - 10 tests: non-string `$id`/`$schema` construct, are reported by `validateSchema` and never make `validate()` throw
- **DynamicRefResourceTest.kt** - 5 tests: `$dynamicRef` targets evaluated in their own resource; percent-encoded anchors keep the dynamic override
- **ConcurrentLoadingTest.kt** - 5 tests: one `schemaLoader` call per URI under concurrency; loaders may validate through the same validator (#55)
- **ValidateSchemaWorkerTest.kt** - 3 tests: the `validateSchema` worker inherits no thread-locals and never outlives an interrupted call
- **BoundedCacheTest.kt** - 4 tests for `BoundedCache` (compute once, null not cached, size bound, concurrent bound)
- **ReferenceResolutionTest.kt** - 30 tests for percent-decoded `$ref` fragments, `JsonSchema.baseUri` resolution and stable `$id` registration
- **KeywordNamedPropertyTest.kt** - 7 tests: properties named `$id`/`$anchor`/`$dynamicAnchor` neither throw nor hide anchors
- **UnevaluatedAcrossResourcesTest.kt** - 6 tests: `unevaluated*` sees what subschemas behind URI-based `$ref`s evaluated
- **AbsoluteKeywordLocationTest.kt** - 45 tests for `absoluteKeywordLocation` across `$ref`, anchors, relative `$id`, remote refs, `$dynamicRef`, `$recursiveRef`, URN `$id` and base URIs
- **JavaOutputUsageTest.kt** - 2 tests running `src/test/java/org/ktson/JavaOutputUsage.java`, a Java caller of the output API
- **JavaApiUsageTest.kt** - 10 tests running `src/test/java/org/ktson/JavaApiUsage.java`, a Java caller of `JsonSchema`, `JsonValidator` and `JsonPointer`
- **OfficialOutputTestRunner.kt** - Runs the suite's `output-tests` content files against Basic output (skips `readOnly.json`, which needs annotations); fails if a draft has no runnable files; also checks KtSON's Basic output for a `$ref` schema against the output schema's `basic` definition
- **OutputTestFilesTest.kt** - 4 tests for the runner's file discovery (missing, empty or fully skipped `content/` must fail)
- **OfficialTestSuiteRunner.kt** - Runs official JSON Schema Test Suite (draft2019-09 + draft2020-12), including `optional/format`
- **PerformanceTest.kt** - 9 performance tests, including three numeric benchmarks (excluded from default test run)

The official suite is **not vendored** - it must be cloned as a sibling of this project:

```bash
git clone https://github.com/json-schema-org/JSON-Schema-Test-Suite.git ../JSON-Schema-Test-Suite
```

Without it, `OfficialTestSuiteRunner` prints a warning and passes locally, but **fails** when `CI` is set.

CI fetches the suite pinned to a SHA - `TEST_SUITE_REF` in `.github/workflows/build.yml` - so upstream additions cannot turn a build red on their own. Bump that SHA periodically; keep the local clone near it (`git -C ../JSON-Schema-Test-Suite fetch && git checkout <ref>`) or local and CI results will diverge.

Skipped in the official suite: `vocabulary.json`, `infinite-loop-detection.json`, and `optional/` apart from `optional/format` and `optional/bignum.json`. Format tests run separately against a validator with `formatAssertion = true` (format is annotation-only by default in 2020-12, so the shared validator cannot run them). At the pinned SHA that leaves 316 of 4,630 assertions excluded in the two draft directories; the 4,314 that run pass 100%.

Test memory configuration: min 512MB, max 2GB heap

## Known Limitations

### 1. Stack Overflow Risk (RESOLVED ✅)
~~The validator has **no global recursion depth limit**.~~

**FIXED**: The validator now has configurable depth limiting with comprehensive protection.

**Configuration:**
```kotlin
val validator = JsonValidator(
    maxValidationDepth = 1000  // Default: 1000, adjust based on needs
)
```

**Protection covers:**
- Deeply nested schemas (properties, arrays)
- Circular `$ref` references
- Complex combiner nesting (allOf/anyOf/oneOf)
- All recursive validation paths

**Recommendation**: Use default (1000) for most cases. Lower for untrusted schemas (e.g., 100-500).

**Stack size caveat (#50):** whether a limit fits depends on the thread's stack. On a 1 MB stack a recursive tree
schema (`{"properties":{"c":{"$ref":"#"}}}`) overflows around 700 levels, before the default limit. `validateInternal()`
catches `StackOverflowError` and reports a `depth` error, so `validate()` never throws it. Comparisons, schema checks,
anchor/`$id` searches and error-location walks are iterative and need no limit; `validateSchema()` runs on its own
64 MB-stack thread.

### 2. Partial Support
- IDNA validation (`hostname`, `idn-hostname`, `idn-email`) implements the hyphen rules, Punycode
  round-tripping, the RFC 5892 Appendix A contextual rules and the RFC 5893 Bidi rule, but decides
  PVALID/DISALLOWED from Unicode categories plus an exception list rather than the full IDNA
  derived-property table. It passes the official suite; exotic code points may still be misjudged.
- `Joining_Type` is approximated by script for the CONTEXTJ rule on ZERO WIDTH NON-JOINER, since
  the JDK exposes no joining-type data.

### 3. Numeric Precision
Bounds, integer classification, `multipleOf` and numeric equality use exact `Long`/`BigDecimal`
arithmetic. Integer tokens within `Long` range take a fast path; parsed decimal schema limits are
cached in a `ConcurrentHashMap` (instance numbers are never cached). `optional/bignum.json` runs
for both drafts. Decimal validation costs more than approximate `Double` arithmetic; see
`docs/SPEC_ARBITRARY_PRECISION_NUMBERS.md` for benchmarks and `BigDecimal` range limitations.

### 4. Other Notes
- API is synchronous (migrated from async coroutines)
- `format` asserts only when `formatAssertion` is true (the default), in both drafts (#61)
- Thread-safe (immutable validator configuration; `SchemaStore` holds the concurrent per-validator remote-schema cache, and each validation gets its own `Evaluation`)
- No global state: every cache is an instance field, so a dropped validator is fully collectible (`ValidatorLifecycleTest`)
- Caches keyed by schema content (`patternCache`, `numericLimitCache`) are `BoundedCache`s (1,024 entries each); the
  remote-schema cache holds one document per distinct URI and is not evicted (evicting would reload with a new identity)

## Development Guidelines

### Releasing
Bump `version` in `build.gradle.kts` (and the README/CLAUDE.md version references, CHANGELOG section),
commit, then push a tag `v<version>`. `.github/workflows/release.yml` then:
1. `check` - fails unless the tag is on master and equals `v` + the `build.gradle.kts` version
2. `build` - calls `build.yml` (the same build as CI: tests, official suite at `TEST_SUITE_REF`, ktlint)
3. `release` - creates the GitHub release with generated notes and attaches the JAR and sources JAR

Only tags matching `v*` trigger it. KtSON is not published to Maven Central (out of scope for now).

### Commit Messages
Release notes are generated from the commit log, so commits must follow Conventional Commits:
- `feat: ...` and `fix: ...` become the **What's New** and **Bug Fixes** sections; a scope
  (`feat(format): ...`) is rendered as a bold label. Other types (`chore`, `docs`, `ci`, `test`,
  `refactor`) are not listed.
- **Mark breaking changes**, or they will not appear: either `!` after the type
  (`feat(errors)!: ...`) or a `BREAKING CHANGE: ...` footer in the body. Either one moves the
  commit into a **Breaking Changes** section, whatever its type, and the footer text is included.
  Verdict changes in a validator count as breaking even when the API is untouched.

### Code Style
- Use ktlint with IntelliJ IDEA default Kotlin conventions
- Always run `./gradlew ktlintFormat` before committing
- Use constants from `SchemaKeywords` instead of string literals
- Keep public API Java-friendly: `@JvmStatic` on companion/object functions, `@JvmOverloads` on
  functions and constructors with default arguments. Cover new public calls in
  `src/test/java/org/ktson/JavaApiUsage.java` (run by `JavaApiUsageTest`)
- Keep `src/main` native-image friendly: no reflection (`Class.forName`, `::class.java`, kotlin-reflect,
  reified `serializer<T>()` lookups), no runtime resources (`getResource`, `src/main/resources`), no
  `ServiceLoader` or dynamic proxies. The README's "Native images" section promises this

### Testing Approach
1. Run standard tests during development: `./gradlew test`
2. Run performance tests only when needed: `./gradlew performanceTest`
3. Official test suite is included in standard test run
4. Thread safety is critical - use `EdgeCaseAndThreadSafetyTest` as reference

### When Modifying Validation Logic
1. Changes to `validateElement()` affect 30 recursive call sites
2. Test against both Draft 2019-09 and 2020-12 test suites
3. Consider stack depth implications for recursive changes
4. Pass the keyword location on every error: `ValidationError(path, msg, KEYWORD, at(schemaPath, KEYWORD))`.
   `at()` appends a keyword, `at(schemaPath, keyword, token)` appends a keyword and an escaped token
   (`properties/name`, `anyOf/0`). Recursive calls pass the sub-schema's location, so `$ref` hops are
   visible in the result (`/properties/n/$ref/minimum`)
5. Maintain thread safety (avoid mutable shared state)
   - Never add mutable state to a companion object, singleton or top-level value: caches must be
     instance fields so a dropped validator is collectible. `ValidatorLifecycleTest` fails otherwise
6. Instance paths are RFC 6901 pointers. Use `childOf(path, token)` for properties and array
   indices; the root is `""`. `propertyNames` errors stay at the owning object's location.
   Construct errors with `instanceLocation`, and migrate `copy(path = ...)` to
   `copy(instanceLocation = ...)`. `toString()` prints canonical pointers.
7. Reference keywords (`$ref`, `$recursiveRef`, `$dynamicRef`) are resolved only in `resolveReferences()`,
   which returns each hop's target, resource root and dynamic scope. `validateElement()` and both
   `collectEvaluated*()` functions use it, so validation and `unevaluated*` always agree on where a
   reference leads, and `validateElement()` stamps `absoluteKeywordLocation` for every hop. A new
   reference keyword goes into `resolveReferences()`, never into a copy

### Reference Resolution
- `$ref` resolution uses `ReferenceResolver` in `JsonPointer.kt`, one per validation: `validateInternal()` creates an
  `Evaluation` (root schema, `ReferenceResolver`, `AbsoluteLocations`) and threads it through every recursive call
- Fragment references (`#/...`, `#/$defs/...`) and external URIs supported
- External schemas resolved via `schemaLoader: ((String) -> JsonElement?)?` on `JsonValidator`
- Schema cache: `SchemaStore` (per validator, shared by its validations): `ConcurrentHashMap<String, JsonElement>` keyed by absolute URI.
  Loads are single-flight through per-URI in-flight records; never call `schemaLoader` inside a map callback
  (`computeIfAbsent`): a loader may validate through the same validator (#55)
- Absolute URI tracking: `IdentityHashMap<JsonElement, String>` in the per-validation `ReferenceResolver`. It must stay
  per validation: one element can be validated under different `JsonSchema.baseUri`s (#37)
- `resourceRoot` passed through recursive calls so fragment refs resolve in the correct document
- When adding new ref types, update `ReferenceResolver` in `JsonPointer.kt` and `resolveReferences()` in `JsonValidator.kt`
- The root's absolute base (`JsonSchema.absoluteBaseUri()`: absolute `$id`, else `$id` resolved against `baseUri`, else `baseUri`) is registered before validation, so `schemaLoader` receives absolute URIs
- `$id` registrations only improve: an absolute URI is never replaced by a relative one, and a registered resource is not re-resolved (its relative `$id` would otherwise nest again)
- `$id`, `$anchor`, `$dynamicAnchor` and `$schema` count only when their value is a string (`stringKeyword()`): a property may be named `$id`, and a malformed value is left to `validateSchema` (#57)

### Format Validation
- Format validation controlled by `formatAssertion` constructor parameter (default: true); `false` makes it an annotation in both drafts
- Currently supported (19): email, uri, uri-reference, uri-template, date, time, date-time, duration, ipv4, ipv6, uuid, hostname, idn-hostname, idn-email, iri, iri-reference, json-pointer, relative-json-pointer, regex
- Formats are validated in `validateFormat()` method
- Add new formats by extending the when expression in `validateFormat()`
- Helper validators live in `JsonValidator.kt`, grouped by family:
  - `isValidUriLike()` backs uri, uri-reference, iri and iri-reference from one RFC 3986/3987 parse
  - `isValidHostname()` / `isValidIdnHostname()` share `isValidULabel()`, `punycodeDecode()`,
    `punycodeEncode()` and `idnaMap()` (UTS 46 mapping: drop ignorables, NFKC, case-fold)
  - `isValidEcmaRegex()` rejects Java-only regex syntax (inline flags, `\a`, `(?#...)`), while
    `translateEmptyCharacterClasses()` accepts the ECMA-only `[]` and `[^]` for both `format` and
    `pattern`

## Important Files

### Documentation
- `docs/STACK_OVERFLOW_RISK_ANALYSIS.md` - **Read this before production deployment**
- `docs/SPEC_INSTANCE_LOCATION.md` - implemented in 3.0.0: RFC 6901 instance locations and legacy `path` deprecation
- `docs/SPEC_OUTPUT_FORMATS.md` - Flag/Basic/Detailed implemented in 3.1.0; absoluteKeywordLocation implemented, unreleased; annotations deferred; Verbose dropped
- `docs/SPEC_ARBITRARY_PRECISION_NUMBERS.md` - implemented: exact numeric comparisons, regression coverage and benchmarks
- `docs/IMPLEMENTATION_STATUS.md` - Feature completeness tracking
- `docs/OFFICIAL_TEST_SUITE_RESULTS.md` - Detailed test results (100% pass rate)
- `docs/TESTING.md` - Testing methodology
- `docs/FEATURES.md` - Complete feature list

### Configuration
- `build.gradle.kts` - Gradle build configuration with test memory settings
- Performance tests excluded from default test task via `excludeTestsMatching("org.ktson.PerformanceTest")`
- ktlint version 1.7.1 configured in the `ktlint { }` block
