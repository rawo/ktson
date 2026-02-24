# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KtSON is a JSON Schema validator for Kotlin with comprehensive support for JSON Schema Draft 2019-09 and 2020-12. The project is a **playground for testing AI agents** in programming, with most code refactoring performed by AI with human supervision.

**Package**: `org.ktson`
**Tech Stack**: Kotlin 2.2.20, Java 21, Gradle 9.1.0, kotlinx-serialization-json, Kotest
**Status**: Pre-release (0.0.1-SNAPSHOT), 100% official test suite coverage (2,653/2,653)

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

1. **JsonValidator** (`JsonValidator.kt`) - Main validation engine (~40KB)
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
   - Used by reference resolver

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

**CRITICAL**: The validator has **23 recursive call points** in `validateElement()`. See "Known Limitations" below.

### Test Structure

- **Draft201909ValidationTest.kt** - 47 tests for Draft 2019-09 features
- **Draft202012ValidationTest.kt** - 54 tests for Draft 2020-12 features
- **EdgeCaseAndThreadSafetyTest.kt** - 39 edge case and concurrency tests
- **OfficialTestSuiteRunner.kt** - Runs official JSON Schema Test Suite (2,653 tests)
- **PerformanceTest.kt** - 6 performance tests (excluded from default test run)

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
- All 21 recursive validation paths

**Recommendation**: Use default (1000) for most cases. Lower for untrusted schemas (e.g., 100-500).

### 2. Unsupported Features
- Remaining format validators: idn-hostname, json-pointer, relative-json-pointer, uri-reference, uri-template

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
1. Changes to `validateElement()` affect 23 recursive call sites
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
- Currently supported: email, uri, date, time, date-time, ipv4, ipv6, uuid, hostname, idn-email, iri, iri-reference, regex
- Formats are validated in `validateFormat()` method
- Add new formats by extending the when expression in `validateFormat()`

## Important Files

### Documentation
- `docs/STACK_OVERFLOW_RISK_ANALYSIS.md` - **Read this before production deployment**
- `docs/IMPLEMENTATION_STATUS.md` - Feature completeness tracking
- `docs/OFFICIAL_TEST_SUITE_RESULTS.md` - Detailed test results (100% pass rate)
- `docs/TESTING.md` - Testing methodology
- `docs/FEATURES.md` - Complete feature list

### Configuration
- `build.gradle.kts` - Gradle build configuration with test memory settings
- Performance tests excluded from default test task (line 33-35)
- ktlint version 1.7.1 configured (line 79-88)
