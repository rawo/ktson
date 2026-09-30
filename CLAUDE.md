# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KtSON is a JSON Schema validator for Kotlin with comprehensive support for JSON Schema Draft 2019-09 and 2020-12. The project is a **playground for testing AI agents** in programming, with most code refactoring performed by AI with human supervision.

**Package**: `org.ktson`
**Tech Stack**: Kotlin 2.4.0, Java 21, Gradle 9.1.0, kotlinx-serialization-json, Kotest
**Status**: 1.0.0, 100% official test suite coverage

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
   - Uses `ReferenceResolver` inner class for `$ref` resolution
   - Entry point: `validate(instance: JsonElement, schema: JsonSchema): ValidationResult`

2. **JsonSchema** (`JsonSchema.kt`) - Schema representation
   - Wraps `JsonElement` with version metadata
   - Auto-detects schema version from `$schema` property
   - Factory methods: `fromString()`, `fromElement()`

3. **ValidationResult** (`ValidationError.kt`) - Sealed class result type
   - `Valid` - validation succeeded
   - `Invalid(validationErrors: List<ValidationError>)` - validation failed
   - Each error contains: path, message, keyword, schemaPath

4. **SchemaKeywords** (`SchemaKeywords.kt`) - Constants object
   - All JSON Schema keywords as constants (REF, TYPE, PROPERTIES, etc.)
   - Valid type names and format types
   - Use these constants instead of string literals

5. **JsonPointer** (`JsonPointer.kt`) - JSON Pointer (RFC 6901) implementation
   - Resolves paths like `/properties/name` or `#/$defs/address`
   - Also contains the `ReferenceResolver` class used for `$ref` resolution

6. **UriResolver** (`UriResolver.kt`) - RFC 3986 URI resolution
   - Resolves reference URIs against base URIs, used when following `$id`/`$ref` chains

7. **SchemaVersion** (`SchemaVersion.kt`) - Enum of supported drafts (`DRAFT_2019_09`, `DRAFT_2020_12`)

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

**CRITICAL**: `validateElement()` has **32 recursive call sites** (plus one entry point from `validateInternal()`). See "Known Limitations" below.

### Test Structure

- **Draft201909ValidationTest.kt** - 47 tests for Draft 2019-09 features
- **Draft202012ValidationTest.kt** - 54 tests for Draft 2020-12 features
- **EdgeCaseAndThreadSafetyTest.kt** - 39 edge case and concurrency tests
- **FormatValidationTest.kt** - 294 tests covering all supported format validators
- **UriResolverTest.kt** - 25 tests for RFC 3986 URI resolution
- **DepthLimitTest.kt** - 14 tests for `maxValidationDepth` protection
- **ErrorMessageTest.kt** - 14 tests for error message content (path, keyword, schema path)
- **OfficialTestSuiteRunner.kt** - Runs official JSON Schema Test Suite (draft2019-09 + draft2020-12), including `optional/format`
- **PerformanceTest.kt** - 6 performance tests (excluded from default test run)

The official suite is **not vendored** - it must be cloned as a sibling of this project:

```bash
git clone https://github.com/json-schema-org/JSON-Schema-Test-Suite.git ../JSON-Schema-Test-Suite
```

Without it, `OfficialTestSuiteRunner` prints a warning and passes locally, but **fails** when `CI` is set.

CI fetches the suite pinned to a SHA - `TEST_SUITE_REF` in `.github/workflows/build.yml` - so upstream additions cannot turn a build red on their own. Bump that SHA periodically; keep the local clone near it (`git -C ../JSON-Schema-Test-Suite fetch && git checkout <ref>`) or local and CI results will diverge.

Skipped in the official suite: `vocabulary.json`, `infinite-loop-detection.json`, and `optional/` apart from `optional/format`, which `runFormatTests` runs separately against a validator with `formatAssertion = true` (format is annotation-only by default in 2020-12, so the shared validator cannot run them). At the pinned SHA that leaves 334 of 4,630 assertions skipped in the two draft directories; the 4,296 that run pass 100%.

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

### 2. Partial Support
- IDNA validation (`hostname`, `idn-hostname`, `idn-email`) implements the hyphen rules, Punycode
  round-tripping, the RFC 5892 Appendix A contextual rules and the RFC 5893 Bidi rule, but decides
  PVALID/DISALLOWED from Unicode categories plus an exception list rather than the full IDNA
  derived-property table. It passes the official suite; exotic code points may still be misjudged.
- `Joining_Type` is approximated by script for the CONTEXTJ rule on ZERO WIDTH NON-JOINER, since
  the JDK exposes no joining-type data.

### 3. Other Notes
- API is synchronous (migrated from async coroutines)
- Format validation in assertion mode only
- Thread-safe (immutable validator, stateless ReferenceResolver)

## Development Guidelines

### Code Style
- Use ktlint with IntelliJ IDEA default Kotlin conventions
- Always run `./gradlew ktlintFormat` before committing
- Use constants from `SchemaKeywords` instead of string literals

### Testing Approach
1. Run standard tests during development: `./gradlew test`
2. Run performance tests only when needed: `./gradlew performanceTest`
3. Official test suite is included in standard test run
4. Thread safety is critical - use `EdgeCaseAndThreadSafetyTest` as reference

### When Modifying Validation Logic
1. Changes to `validateElement()` affect 32 recursive call sites
2. Test against both Draft 2019-09 and 2020-12 test suites
3. Consider stack depth implications for recursive changes
4. Update error messages to include keyword and schema path
5. Maintain thread safety (avoid mutable shared state)

### Reference Resolution
- `$ref` resolution uses `ReferenceResolver` in `JsonPointer.kt`
- Fragment references (`#/...`, `#/$defs/...`) and external URIs supported
- External schemas resolved via `schemaLoader: ((String) -> JsonElement?)?` on `JsonValidator`
- Schema cache: `ConcurrentHashMap<String, JsonElement>` keyed by absolute URI
- Absolute URI tracking: `IdentityHashMap<JsonElement, String>` for schemas with relative `$id`
- `resourceRoot` passed through recursive calls so fragment refs resolve in the correct document
- When adding new ref types, update `ReferenceResolver` in `JsonPointer.kt`

### Format Validation
- Format validation controlled by `formatAssertion` constructor parameter (default: true)
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
- `docs/IMPLEMENTATION_STATUS.md` - Feature completeness tracking
- `docs/OFFICIAL_TEST_SUITE_RESULTS.md` - Detailed test results (100% pass rate)
- `docs/TESTING.md` - Testing methodology
- `docs/FEATURES.md` - Complete feature list

### Configuration
- `build.gradle.kts` - Gradle build configuration with test memory settings
- Performance tests excluded from default test task via `excludeTestsMatching("org.ktson.PerformanceTest")`
- ktlint version 1.7.1 configured in the `ktlint { }` block
