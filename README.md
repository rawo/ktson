# KtSON

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-blue.svg)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Test Coverage](https://img.shields.io/badge/Official%20Test%20Suite-92.8%25-brightgreen.svg)](docs/OFFICIAL_TEST_SUITE_RESULTS.md)

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
- Schema references (`$ref`) with local and fragment support
- Schema combiners (allOf, anyOf, oneOf, not)
- Conditional validation (if/then/else)
- Property name validation
- Dependent schemas and required properties

## Tech Stack

**Core**
- **Language**: Kotlin 2.2.20
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

#### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("org.ktson:ktson:0.0.1-SNAPSHOT")
}
```

#### Gradle (Groovy)

```groovy
dependencies {
    implementation 'org.ktson:ktson:0.0.1-SNAPSHOT'
}
```

#### Maven

```xml
<dependency>
    <groupId>org.ktson</groupId>
    <artifactId>ktson</artifactId>
    <version>0.0.1-SNAPSHOT</version>
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
            result.errors.forEach { error ->
                println("  - ${error.path}: ${error.message}")
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

```kotlin
val validator = JsonValidator(enableMetaSchemaValidation = true)
val schema = JsonSchema.fromString(schemaJson, SchemaVersion.DRAFT_2020_12)

val result = validator.validateSchema(schema)
if (result is ValidationResult.Invalid) {
    println("Invalid schema:")
    result.errors.forEach { println("  - ${it.message}") }
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
| Numeric Validation | ✅ Complete | 100% |
| Object Validation | ✅ Complete | 95% |
| Array Validation | ✅ Complete | 95% |
| Schema Combiners | ✅ Complete | 100% |
| Conditional Validation | ✅ Complete | 100% |
| References ($ref) | ✅ Complete | Local references only |
| Format Validation | ⚠️ Partial | Basic formats (email, URI, date, time, IPv4, IPv6, UUID) |
| **Official Test Suite** | ✅ 92.8% | **2,205/2,376 passing** |

**Not Implemented:**
- `unevaluatedProperties` and `unevaluatedItems` (Draft 2020-12 features)
- Remote schema references (HTTP/HTTPS)
- Full `$recursiveRef` and `$dynamicRef` dynamic scoping
- Some advanced format validators

See [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) for detailed feature breakdown.

### Known Limitations

1. **Stack Overflow Risk** (Risk Level: HIGH - 7.5/10)
   - No global recursion depth limit
   - Deeply nested schemas (>500 levels) may cause stack overflow
   - Complex combiner schemas (>50 levels) at high risk
   - See [docs/STACK_OVERFLOW_RISK_ANALYSIS.md](docs/STACK_OVERFLOW_RISK_ANALYSIS.md)
   - **Recommendation**: Implement depth tracking before production use with untrusted schemas

2. **Remote References**
   - Only local references (`#/...`) are supported
   - HTTP/HTTPS schema references are not implemented

3. **Advanced Draft 2020-12 Features**
   - `unevaluatedProperties` and `unevaluatedItems` not supported
   - Full dynamic scoping for `$dynamicRef` not implemented

4. **Format Validation**
   - Basic assertion mode only
   - Limited format validators (no regex-heavy formats like hostname)

## Project Status

**Version**: 0.0.1-SNAPSHOT  
**Status**: ⚠️ Pre-release (Beta)

### Test Coverage

- **Custom Tests**: 140 tests (100% passing)
  - Draft 2019-09: 47 tests
  - Draft 2020-12: 54 tests
  - Edge cases & thread safety: 39 tests

- **Official JSON Schema Test Suite**: 2,376 tests
  - ✅ Passing: 2,205 (92.8%)
  - ❌ Failing: 171 (7.2%)
  - Primary failures: `unevaluatedProperties` and `unevaluatedItems`

- **Performance Tests**: 6 tests (100% passing)
  - Tested with schemas up to 50MB
  - Tested with data up to 20MB
  - Concurrent validation verified

### Recent Changes

- ✅ Converted from async to synchronous API
- ✅ Package migrated from `com.ktson` to `org.ktson`
- ✅ Upgraded to Kotlin 2.2.20 and Java 21
- ✅ Added Ktlint for code quality
- ✅ Extracted schema keywords to constants
- ✅ Performance test suite added

### Roadmap

**Before 1.0 Release:**
- [ ] Implement recursion depth limiting (CRITICAL)
- [ ] Add configurable validation limits
- [ ] Implement `unevaluatedProperties` and `unevaluatedItems`
- [ ] Add more format validators
- [ ] Improve error messages

**Future Enhancements:**
- [ ] Remote schema reference support
- [ ] Full `$dynamicRef` dynamic scoping
- [ ] Schema caching improvements
- [ ] Streaming validation for large datasets
- [ ] GraalVM native image support

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

**Note**: This is a pre-release version. While the validator is functional and passes 92.8% of official tests, it has known limitations (particularly around recursion depth) that should be addressed before production use with untrusted schemas. See [STACK_OVERFLOW_RISK_ANALYSIS.md](docs/STACK_OVERFLOW_RISK_ANALYSIS.md) for details.
