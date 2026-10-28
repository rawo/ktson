# KtSON

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-blue.svg)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Test Coverage](https://img.shields.io/badge/Official%20Test%20Suite-100%25-brightgreen.svg)](docs/OFFICIAL_TEST_SUITE_RESULTS.md)
[![JSON Schema 2019-09 and 2020-12 fully supported](https://img.shields.io/badge/JSON%20Schema-2019--09%20%26%202020--12%20full%20support-brightgreen.svg)](#supported-json-schema-versions)

JSON Schema validator for Kotlin with comprehensive support for JSON Schema Draft 2019-09 and 2020-12.

#### NOTE   
This project is a playground for testing AI agents as a day-to-day tools in programming.  
You can see the summaries and results from discussions with AI in the [./docs](./docs/) directory.  
Most of the code refactoring were performed by AI agent with supervision and code review done by human.  

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Basic Usage](#basic-usage)
  - [Standard output formats](#standard-output-formats)
  - [Using KtSON from Java](#using-ktson-from-java)
  - [Memory and validator lifecycle](#memory-and-validator-lifecycle)
  - [Large datasets and streams](#large-datasets-and-streams)
  - [Native images](#native-images)
- [Available Scripts](#available-scripts)
- [Project Scope](#project-scope)
  - [Supported JSON Schema Versions](#supported-json-schema-versions)
  - [Feature Completeness](#feature-completeness)
  - [Known Limitations](#known-limitations)
- [Project Status](#project-status)
- [Documentation](#documentation)
- [Performance](#performance)
- [Code Quality](#code-quality)
- [License](#license)

## Features

✅ **Comprehensive Validation**
- Full type validation (string, number, integer, boolean, object, array, null)
- String constraints (minLength, maxLength, pattern, format)
- Numeric constraints (minimum, maximum, multipleOf, exclusive bounds)
- Object constraints (properties, required, additionalProperties, patternProperties, dependencies)
- Array constraints (items, prefixItems, contains, uniqueItems, minItems, maxItems)

✅ **Advanced Schema Features**
- Schema references (`$ref`) with local, fragment, and external schema support
- Pluggable remote schema loading (`schemaLoader` callback)
- Schema combiners (allOf, anyOf, oneOf, not)
- Conditional validation (if/then/else)
- Unevaluated properties and items (Draft 2020-12)
- Property name validation
- Dependent schemas and required properties

## Tech Stack

**Core**
- **Language**: Kotlin 2.4.0
- **JVM**: Java 21
- **Build Tool**: Gradle 9.1.0

**Dependencies**
- `kotlinx-serialization-json` - JSON parsing and manipulation
- `kotest` - Testing framework
- `ktlint` - Code style and quality

**Package**: `org.ktson`

## Getting Started

### Prerequisites

- JDK 21 or higher
- Gradle 9.1.0 or higher (or use the included wrapper)

### Installation

KtSON is not yet published to Maven Central or another public repository, so dependency
coordinates alone will not resolve. Build a release into your local Maven repository first (the
official test suite is not needed for this):

```bash
git clone https://github.com/rawo/ktson.git
cd ktson
git checkout v3.1.0            # a release tag; master may contain unreleased changes
./gradlew publishToMavenLocal  # installs org.ktson:ktson:3.1.0 into ~/.m2/repository
```

Then add `mavenLocal()` to your repositories and depend on that version.

#### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.ktson:ktson:3.1.0")
}
```

#### Gradle (Groovy)

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'org.ktson:ktson:3.1.0'
}
```

#### Maven

Maven reads `~/.m2/repository` by default, so no extra repository is needed:

```xml
<dependency>
    <groupId>org.ktson</groupId>
    <artifactId>ktson</artifactId>
    <version>3.1.0</version>
</dependency>
```

### Basic Usage

#### Validating JSON Against a Schema

```kotlin
import org.ktson.*
import kotlinx.serialization.json.*

fun main() {
    // Create a JSON schema
    val schemaJson = """
    {
        "type": "object",
        "properties": {
            "name": { "type": "string" },
            "age": { "type": "integer", "minimum": 0 }
        },
        "required": ["name", "age"]
    }
    """
    
    // Create instance data
    val instanceJson = """
    {
        "name": "John Doe",
        "age": 30
    }
    """
    
    // Validate
    val validator = JsonValidator()
    val result = validator.validate(instanceJson, schemaJson, SchemaVersion.DRAFT_2020_12)
    
    when (result) {
        is ValidationResult.Valid -> println("✓ Validation successful!")
        is ValidationResult.Invalid -> {
            println("✗ Validation failed:")
            result.validationErrors.forEach { error ->
                println("  - ${error.instanceLocation}: ${error.message}")
            }
        }
    }
}
```

#### Using JsonElement

```kotlin
import org.ktson.*
import kotlinx.serialization.json.*

val schema = buildJsonObject {
    put("type", "string")
    put("minLength", 3)
    put("maxLength", 10)
}

val instance = JsonPrimitive("hello")

val validator = JsonValidator()
val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)
val result = validator.validate(instance, jsonSchema)
```

#### Validating Schema Structure

`validateSchema` checks a schema against the official meta-schema of its draft (embedded in KtSON, no
network access), with format assertion on, so for example an invalid `pattern` regex is reported. Errors
point into the schema (`instanceLocation` is `/properties/a/type`, say). Schemas nested deeper than
`maxValidationDepth` get a `depth` error; the check itself runs on a dedicated thread with a large stack,
so it does not depend on the caller's stack size. That thread does not inherit the caller's thread-locals,
and `validateSchema` always waits for it: like `validate`, the call runs to completion, and an interrupt
received meanwhile stays set for the caller to handle.

```kotlin
val validator = JsonValidator(enableMetaSchemaValidation = true)
val schema = JsonSchema.fromString(schemaJson, SchemaVersion.DRAFT_2020_12)

val result = validator.validateSchema(schema)
if (result is ValidationResult.Invalid) {
    println("Invalid schema:")
    result.validationErrors.forEach { println("  - ${it.message}") }
}
```

#### Working with $ref

```kotlin
val schema = """
{
    "${'$'}defs": {
        "address": {
            "type": "object",
            "properties": {
                "street": { "type": "string" },
                "city": { "type": "string" }
            }
        }
    },
    "type": "object",
    "properties": {
        "billing": { "${'$'}ref": "#/${'$'}defs/address" },
        "shipping": { "${'$'}ref": "#/${'$'}defs/address" }
    }
}
"""
```

For more examples, see [docs/REF_USAGE_EXAMPLES.md](docs/REF_USAGE_EXAMPLES.md) and [src/main/kotlin/org/ktson/Example.kt](src/main/kotlin/org/ktson/Example.kt).

### Standard output formats

`validate(instance, schema)` returns KtSON's own `ValidationResult`. To get the standard
JSON Schema output (2020-12 §12) instead, pass a format; the return type follows it:

```kotlin
val flag: FlagOutput = validator.validate(instance, schema, OutputFormat.Flag)
val basic: BasicOutput = validator.validate(instance, schema, OutputFormat.Basic)
val detailed: DetailedOutput = validator.validate(instance, schema, OutputFormat.Detailed)

println(basic.toJson())
// {"valid":false,"keywordLocation":"","instanceLocation":"","errors":[{"valid":false,
//  "keywordLocation":"/properties/name/type","instanceLocation":"/name","error":"..."}]}
```

- `Flag` is `{"valid": …}` only; `Basic` lists every failing unit flat; `Detailed` keeps the
  `allOf`/`anyOf`/`oneOf` hierarchy. `result.toOutput(format)` converts a result you already have.
- Serialise with `toJson()`. Encoding through the `ValidationOutput` interface would add a type
  discriminator that is not part of the standard.
- `error` texts are KtSON's messages, which the standard does not fix; do not match on them.
- Each failing unit carries `absoluteKeywordLocation`: the canonical URI of the schema resource
  holding the keyword plus a JSON Pointer within it, following `$ref`, `$dynamicRef` and
  `$recursiveRef` (`https://example.com/root#/$defs/p/minimum` where `keywordLocation` is
  `/properties/n/$ref/minimum`), with the pointer percent-encoded as a URI fragment (`a b` becomes
  `a%20b`). It needs an absolute `$id` or `JsonSchema.fromString(json, version,
  baseUri)`; without either it is omitted. The same value is on `ValidationError.absoluteKeywordLocation`.
- There is no `Verbose` format: the next output-format draft removes it.

From Java, pass the token's `INSTANCE`; the conversion lives on `ValidationOutputs`:

```java
BasicOutput basic = validator.validate(instance, schema, OutputFormat.Basic.INSTANCE);
DetailedOutput detailed = ValidationOutputs.toOutput(result, OutputFormat.Detailed.INSTANCE);
```

### Using KtSON from Java

The API is callable from Java without `Companion` or `INSTANCE` on the core types, and defaulted
parameters can be left out from the end:

```java
JsonSchema schema = JsonSchema.fromString(schemaJson);                    // version defaults to 2020-12
JsonSchema older = JsonSchema.fromString(schemaJson, SchemaVersion.DRAFT_2019_09);

JsonValidator validator = new JsonValidator();
JsonValidator lenient = new JsonValidator(true, false);                   // format assertion off
JsonValidator remote = new JsonValidator(true, true, 1000, uri -> loadSchema(uri)); // schemaLoader as a lambda

ValidationResult result = validator.validate(instanceJson, schemaJson);
JsonElement value = JsonPointer.resolve(document, "/users/0/age");
```

Constructor arguments are positional (`enableMetaSchemaValidation`, `formatAssertion`,
`maxValidationDepth`, `schemaLoader`), so setting a later one means passing the earlier ones too.
Kotlin objects such as `OutputFormat.Basic` and `ValidationResult.Valid` are reached through `INSTANCE`.

### Memory and validator lifecycle

KtSON keeps no global state: every cache (remote schemas loaded through `schemaLoader`, compiled
patterns, numeric limits) belongs to one `JsonValidator`, and what a validation learns about a schema
(the absolute URI of each `$id`) is discarded when that validation ends. That shapes how a
long-running service should use it:

- **Dropping a validator frees everything it built.** Once a `JsonValidator` is unreachable, its
  caches, the schemas it validated and the remote schemas it loaded can all be garbage-collected
  (`ValidatorLifecycleTest` checks this). A service holding one validator per schema can retire a
  schema by dropping its validator.
- **A live validator does not hold on to the schemas it validates.** Once the application drops a
  `JsonSchema`, it can be collected even while the validator that checked it stays in use. The same
  parsed schema can be validated under different `baseUri`s; each validation resolves its `$id`s
  against its own base.
- **Derived caches are bounded.** Compiled `pattern`s and parsed numeric limits are cached per
  validator, up to 1,024 of each; beyond that, entries are evicted and recomputed when needed.
- **Remote schemas are loaded once per validator.** The validator keeps one document per distinct
  URI its `schemaLoader` returned, for its whole life. To pick up a changed remote schema, or to
  release remote schemas that are no longer referenced, create a new validator.
- **Pooling is up to the application.** KtSON has no schema pool: how many schemas to keep, how much
  memory they may use and when to refresh them are application decisions, made by choosing which
  validators to keep.

### Large datasets and streams

KtSON validates complete JSON values; it does not parse streams. A validator cannot give a verdict on
an incomplete document, so framing and buffering belong to whatever reads the stream: it collects
one complete, self-contained unit (a record, a message, a request body) and passes it to KtSON.
Memory is then bounded by the largest unit, not by the size of the stream.

For newline-delimited JSON, that is one parse and one validation per line:

```kotlin
val validator = JsonValidator()
val schema = JsonSchema.fromString(recordSchemaJson)

File("records.ndjson").useLines { lines ->
    lines.filter { it.isNotBlank() }.forEachIndexed { index, line ->
        val result = validator.validate(Json.parseToJsonElement(line), schema)
        if (result.isInvalid) println("record $index: ${result.getErrors()}")
    }
}
```

A `JsonValidator` is thread-safe, so units can be validated in parallel with one shared validator.
Validation is CPU work: to keep it off an I/O or UI thread, run it on a worker thread or
`Dispatchers.Default` rather than expecting a non-blocking API from KtSON.

### Native images

KtSON needs no GraalVM reachability metadata: the library uses no reflection, loads no resources at
runtime and does no dynamic class loading, and its only runtime dependency, kotlinx-serialization-json,
is used through compiler-generated serializers. Applications built as native images (Quarkus,
Micronaut, Spring Native, CLI tools) should be able to use it without extra configuration. This is not
tested in CI yet; reports of problems are welcome.

## Available Scripts

### Build

```bash
# Build the project
./gradlew build

# Compile only
./gradlew compileKotlin

# Create JAR
./gradlew jar
```

### Testing

```bash
# Run all tests (excludes performance tests for speed)
./gradlew test

# Run specific test suite
./gradlew test --tests "Draft201909ValidationTest"
./gradlew test --tests "OfficialTestSuiteRunner"

# Run performance tests (on demand)
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

## Project Scope

### Supported JSON Schema Versions

- ✅ **JSON Schema Draft 2020-12** (primary support)
- ✅ **JSON Schema Draft 2019-09** (full support)

### Feature Completeness

| Feature Category | Status | Coverage |
|-----------------|--------|----------|
| Type Validation | ✅ Complete | 100% |
| String Validation | ✅ Complete | 100% (including Unicode codepoints) |
| Numeric Validation | ✅ Complete | Exact `Long`/`BigDecimal` comparisons, including `optional/bignum` |
| Object Validation | ✅ Complete | 100% |
| Array Validation | ✅ Complete | 100% |
| Schema Combiners | ✅ Complete | 100% |
| Conditional Validation | ✅ Complete | 100% |
| References ($ref) | ✅ Complete | Local and external (via schemaLoader) |
| Unevaluated Keywords | ✅ Complete | 100% |
| Remote Schema Loading | ✅ Complete | Pluggable schemaLoader callback |
| Format Validation | ✅ Complete | All 19 formats, assertion mode only: email, idn-email, uri, uri-reference, uri-template, iri, iri-reference, date, time, date-time, duration, ipv4, ipv6, uuid, hostname, idn-hostname, json-pointer, relative-json-pointer, regex |
| **Official Test Suite** | ✅ **100%** | **4,314/4,314 passing** (includes `optional/format` and `optional/bignum`) |

See [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) for detailed feature breakdown.

### Known Limitations

1. **~~Stack Overflow Risk~~** ✅ **RESOLVED**
   - ✅ Global recursion depth limiting implemented (default: 1000 levels)
   - ✅ Configurable via `maxValidationDepth` constructor parameter
   - Whether the limit fits depends on the thread's stack: on a 1 MB stack a recursive tree schema can
     run out of stack around 700 levels. `validate()` then reports a `depth` error instead of throwing;
     use a lower limit (or a larger thread stack) if deep, valid input must pass
   - See [docs/STACK_OVERFLOW_RISK_ANALYSIS.md](docs/STACK_OVERFLOW_RISK_ANALYSIS.md) for historical analysis

2. **Format Validation**
   - Asserted by default; `formatAssertion = false` makes `format` an annotation in both drafts
   - IDNA validation (`hostname`, `idn-hostname`, `idn-email`) passes the official suite, but decides
     code point validity from Unicode categories plus the RFC 5892 §2.6 exception list rather than the
     IDNA derived-property table, which the JDK does not expose. `Joining_Type` is approximated by
     script for the CONTEXTJ rule and the Virama set is a fixed list, so both drift with new Unicode
     versions. Exotic code points may be misjudged; everyday names are not affected.

3. **Output Formats**
   - No annotations in output ([#5](https://github.com/rawo/ktson/issues/5)).
   - `absoluteKeywordLocation` is omitted when the schema has neither an absolute `$id` nor a base
     URI, as JSON Schema 2020-12 §12.3.3 allows.

## Upgrading from 3.0.0

3.1.0 only adds API, so it is a drop-in replacement: no code changes and no changed verdicts.

- [Standard output formats](#standard-output-formats): `validate(instance, schema, OutputFormat.Basic)`
  and `result.toOutput(format)` return JSON Schema 2020-12 §12 output.
- [Java-friendly API](#using-ktson-from-java): static factories on `JsonSchema` and `JsonPointer`,
  and overloads that let Java omit defaulted arguments.

## Upgrading from 2.0.0

3.0.0 has two breaking changes: one to the error API, one to numeric verdicts.

**Error locations are RFC 6901 JSON Pointers.** `ValidationError.instanceLocation` replaces
`path` as the primary field: the root is `""`, nested properties use `/user/age`, and array
elements use `/users/0`. Tokens escape `~` as `~0` and `/` as `~1`; `/` identifies a member with
an empty key. `propertyNames` failures point to the owning object and name the offending key in
the message. Nested causes and `toString()` use these canonical locations too.

The deprecated `error.path` getter derives the old dotted/bracket notation for display only.
This projection loses information: `/a.b` and `/a/b` both become `a.b`, and numeric object keys
are indistinguishable from array indices. Use `instanceLocation` for navigation:

```kotlin
val error = ValidationError(instanceLocation = "/users/0/age", message = "Expected an integer")
val moved = error.copy(instanceLocation = "/users/1/age")
val failingValue = JsonPointer.resolve(instance, error.instanceLocation)
```

Migrate named constructor and `copy(path = ...)` arguments to `instanceLocation = ...`, passing
a JSON Pointer. Positional construction and the first destructured component also use pointer
semantics now. See [the instance-location implementation](docs/SPEC_INSTANCE_LOCATION.md).

**Numbers are compared exactly.** Numeric comparisons use exact `Long`/`BigDecimal` arithmetic
instead of `Double`. This changes verdicts for large integers, high-precision fractions and
near-multiples previously accepted by the floating-point tolerance; valid large integers such as
`1e400` are now accepted as integers. Numeric strings remain distinct from numbers.
See [the numeric precision implementation and benchmarks](docs/SPEC_ARBITRARY_PRECISION_NUMBERS.md).

Pin 2.0.0 if you need the old verdicts.

## Upgrading from 1.0.0

The public API is unchanged — same classes, same constructor parameters, same methods, so 2.0.0
is a drop-in replacement at compile time. The major version reflects **changed validation verdicts**:

- **Format validation is much stricter.** Of 879 official format cases, 112 now answer differently
  (100 valid → invalid, 10 invalid → valid, 2 that used to throw). Every change moves toward the
  official test suite, which 1.0.0 failed in 220 places. Affected most: `hostname` and
  `idn-hostname` (A-labels are now decoded and checked), `uri-template` (full RFC 6570 grammar),
  the `uri`/`iri` family (percent-encoding, control characters, authority rules), and `regex`
  (ECMA 262 rather than Java syntax). This applies on default settings, since `formatAssertion`
  defaults to `true`.
- **`pattern` no longer throws on `[]` and `[^]`.** 1.0.0 let a `PatternSyntaxException` escape
  `validate()`; these are valid ECMA 262 and now validate normally.
- **Error content changed.** `ValidationError.schemaPath` was always `null` and is now always
  populated with the keyword location, so `toString()` output differs. Messages render numbers as
  written (`maximum 10`, not `10.0`) and quote the offending value. Do not assert on message text.
- **Dependencies**: Kotlin 2.4.0, kotlinx-serialization-json 1.8.1, coroutines 1.10.2.

If a value your data relies on now fails, it is worth checking against the official suite before
assuming a regression — but pin 1.0.0 if you need the old verdicts.

## Project Status

**Version**: 3.1.0  
**Status**: ✅ Stable

### Test Coverage

- **Custom Tests**: 1002 tests (100% passing)
  - Draft 2019-09: 47 tests
  - Draft 2020-12: 54 tests
  - Edge cases & thread safety: 39 tests
  - Depth limit protection: 14 tests
  - URI resolver (including the RFC 3986 §5.4 examples): 80 tests
  - Format validation: 330 tests
  - Error messages: 32 tests
  - Numeric precision and concurrent numeric validation: 88 tests
  - Instance locations, legacy access and JSON Pointer resolution: 117 tests
  - Output formats: 40 tests
  - Absolute keyword locations: 45 tests
  - Reference resolution (percent-encoding, base URIs, `$id` registration, reference resources): 36 tests
  - Keyword-named properties (`$id`, `$anchor`, `$dynamicAnchor`): 7 tests
  - `unevaluated*` across schema resources: 6 tests
  - Validator lifecycle (garbage collection, bounded caches): 3 tests
  - Undecided results under `not`, `if`, `oneOf` and `maxContains`: 17 tests
  - Bounded cache: 4 tests
  - `validateSchema` worker thread (thread-locals, interrupts): 3 tests
  - Remote loading (single flight, reentrant loaders): 5 tests
  - `formatAssertion` option in both drafts: 4 tests
  - Malformed `$id` / `$schema` values: 10 tests
  - `$dynamicRef` resources and encoded anchors: 5 tests
  - Output-tests runner file discovery: 4 tests
  - Java interop for output formats: 2 tests
  - Java interop for the core API: 10 tests

- **Official JSON Schema Test Suite**: 4,314 tests
  - ✅ Passing: 4,314 (100%)
  - ❌ Failing: 0
  - Includes the `optional/format` files and `optional/bignum.json` for both drafts; `vocabulary.json`,
    `infinite-loop-detection.json` and the rest of `optional/` are not run yet

- **Official output-tests**: 6 (Basic format, both drafts; `readOnly.json` needs annotations and is not run),
  plus 2 checks of KtSON's Basic output for a `$ref` schema against the official output schema

- **Performance Tests**: 9 tests (100% passing)
  - Tested with schemas up to 50MB
  - Tested with data up to 20MB
  - Concurrent validation verified
  - Three numeric benchmarks cover integer, decimal and wide-integer validation

### Recent Changes

- ✅ **Errors carry a keyword location** (`ValidationError.schemaPath`), e.g. `/properties/n/$ref/minimum`
- ✅ **Errors carry an instance location** (`ValidationError.instanceLocation`), e.g. `/users/0/age`
- ✅ **Numbers are reported as written** — `maximum 10`, not `maximum 10.0`
- ✅ **Messages quote the offending value** for `pattern`, `format` and the length bounds
- ✅ **All 19 formats pass the official `optional/format` suite** (1,748 assertions that previously did not run)
- ✅ **IDNA validation** for `hostname` / `idn-hostname` / `idn-email`: Punycode round-tripping, UTS 46
  mapping, RFC 5892 contextual rules and the RFC 5893 Bidi rule
- ✅ **One RFC 3986/3987 parser** behind `uri`, `uri-reference`, `iri` and `iri-reference`
- ✅ **RFC 6570 grammar** for `uri-template`; **ECMA 262 syntax rules** for `regex` and `pattern`
- ✅ **Remote schema loading implemented** (pluggable `schemaLoader` callback)
- ✅ **100% official test suite pass rate** (4,314/4,314 tests)
- ✅ **Exact numeric comparisons** for bounds, integer classification, `multipleOf` and numeric equality
- ✅ `unevaluatedProperties` and `unevaluatedItems` implemented
- ✅ `$dynamicRef` / `$dynamicAnchor` full dynamic scope resolution
- ✅ `$recursiveRef` / `$recursiveAnchor` dynamic recursion
- ✅ Stack overflow protection (configurable depth limiting)
- ✅ Converted from async to synchronous API
- ✅ Package migrated from `com.ktson` to `org.ktson`

### Roadmap

**Before 1.0 Release:**
- [x] ~~Implement recursion depth limiting~~ ✅ **COMPLETED**
- [x] ~~Implement `unevaluatedProperties` and `unevaluatedItems`~~ ✅ **COMPLETED**
- [x] ~~Remote schema reference support~~ ✅ **COMPLETED**
- [x] ~~Add more format validators (hostname, idn-email, iri, regex)~~ ✅ **COMPLETED** (all 19 formats, verified against the official `optional/format` suite)
- [x] ~~Improve error messages~~ ✅ **COMPLETED** (keyword locations, literal-accurate numbers, offending values)

**Future Enhancements:**
- [x] [RFC 6901 instance locations](docs/SPEC_INSTANCE_LOCATION.md) in errors (`instanceLocation`, deprecated legacy `path` getter)
- [x] [Standard output formats](docs/SPEC_OUTPUT_FORMATS.md): flag, basic, detailed
- [x] `absoluteKeywordLocation` in errors and standard output
- [ ] Annotations in standard output ([#5](https://github.com/rawo/ktson/issues/5))
- [x] [Arbitrary-precision numbers](docs/SPEC_ARBITRARY_PRECISION_NUMBERS.md) — exact comparisons beyond 2^53
- [x] [No global state](#memory-and-validator-lifecycle): dropping a validator frees its schemas and caches (tested); schema pooling is left to applications

## Documentation

Comprehensive documentation is available in the [docs](docs/) directory:

### Getting Started
- [FEATURES.md](docs/FEATURES.md) - Complete feature list
- [REF_USAGE_EXAMPLES.md](docs/REF_USAGE_EXAMPLES.md) - Reference usage examples
- [Example.kt](src/main/kotlin/org/ktson/Example.kt) - Code examples

### Testing
- [TESTING.md](docs/TESTING.md) - Testing approach and methodology
- [OFFICIAL_TEST_SUITE_RESULTS.md](docs/OFFICIAL_TEST_SUITE_RESULTS.md) - Detailed test results
- [PERFORMANCE_TEST_RESULTS.md](docs/PERFORMANCE_TEST_RESULTS.md) - Performance benchmarks
- [PERFORMANCE_TESTING_SUMMARY.md](docs/PERFORMANCE_TESTING_SUMMARY.md) - Performance test implementation

### Technical
- [STACK_OVERFLOW_RISK_ANALYSIS.md](docs/STACK_OVERFLOW_RISK_ANALYSIS.md) - Recursion depth analysis (⚠️ Important)
- [GRAPHEME_CLUSTER_ISSUE.md](docs/GRAPHEME_CLUSTER_ISSUE.md) - Unicode handling details
- [IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) - Feature implementation tracking

### Migration & History
- [MIGRATION_COMPLETE.md](docs/MIGRATION_COMPLETE.md) - Async to sync migration summary
- [ASYNC_TO_SYNC_MIGRATION.md](docs/ASYNC_TO_SYNC_MIGRATION.md) - Detailed migration guide
- [PACKAGE_MIGRATION.md](docs/PACKAGE_MIGRATION.md) - Package refactoring documentation
- [VERSION_UPGRADE.md](docs/VERSION_UPGRADE.md) - Version upgrade summary

See [docs/README.md](docs/README.md) for a complete documentation index.

## Performance

KtSON demonstrates excellent performance with large and complex schemas:

| Test Case | Schema Size | Data Size | Execution Time | Memory Used |
|-----------|-------------|-----------|----------------|-------------|
| Nested Objects | 25.2 MB | 10.2 MB | 84ms | 73 MB |
| Large Arrays | 905 KB | 80 KB | 7ms | 1 MB |
| Complex Properties | 534 KB | 205 KB | 6ms | 4 MB |
| Combiners | 221 KB | 38 KB | 4ms | 1 MB |
| Pattern Properties | 63 KB | 148 KB | 48ms | 175 MB |
| Concurrent (10 threads) | 50 MB | 20 MB | 19ms avg | 392 MB |

**Key Characteristics:**
- Fast: Most validations complete in single-digit milliseconds
- Scalable: Handles schemas up to 50MB efficiently
- Thread-safe: Zero synchronization overhead
- Memory efficient: Proportional to schema complexity

See [docs/PERFORMANCE_TEST_RESULTS.md](docs/PERFORMANCE_TEST_RESULTS.md) for detailed benchmarks.

## Code Quality

KtSON follows strict code quality standards:

- **Code Style**: Enforced with Ktlint 1.7.1
- **Style Guide**: IntelliJ IDEA default Kotlin conventions
- **Test Coverage**: 2,500+ tests across multiple suites
- **Thread Safety**: Verified with concurrent validation tests
- **Documentation**: Comprehensive inline documentation and separate docs

Check code style:
```bash
./gradlew ktlintCheck
```

Auto-format code:
```bash
./gradlew ktlintFormat
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

```
MIT License

Copyright (c) 2024 KtSON Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

**Note**: This is a pre-release version. The validator passes 100% of the official JSON Schema Test Suite. Recursion depth protection is implemented (default limit: 1000). See [STACK_OVERFLOW_RISK_ANALYSIS.md](docs/STACK_OVERFLOW_RISK_ANALYSIS.md) for details on depth limiting.
