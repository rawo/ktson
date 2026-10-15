# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- JSON Pointer `/` and `#/` now resolve to a member with an empty key instead of the root.
- `propertyNames` errors point to the owning object and identify the rejected key, including
  failures from boolean schemas and combiners.
- Numeric bounds, integer classification, `multipleOf`, and equality for `const`, `enum` and
  `uniqueItems` now use exact `Long`/`BigDecimal` arithmetic. Large integers and high-precision
  fractions no longer collapse through `Double`, and numeric strings remain distinct from numbers.
- **Breaking verdict changes:** previously accepted rounded values and near-multiples may now
  fail validation; valid large integers such as `1e400` are accepted as integers.

### Added
- **Breaking error API change:** `ValidationError.instanceLocation` replaces the primary `path`
  field with an RFC 6901 JSON Pointer. Named constructor and `copy(path = ...)` arguments must
  migrate to `instanceLocation = ...`; positional construction and destructuring now use pointers.
  The deprecated `path` getter provides a lossy legacy display string; `toString()` uses pointers.
- 117 instance-location and JSON Pointer tests, including both drafts, nested causes, references,
  unusual keys, schema-structure errors and the deprecated accessor.
- Official `optional/bignum.json` coverage for both drafts and 88 numeric regression/concurrency tests.
- Three numeric performance benchmarks; integer fast paths and a cache of parsed schema limits
  reduce the cost of exact arithmetic. Decimal validation remains slower than approximate arithmetic;
  see `docs/SPEC_ARBITRARY_PRECISION_NUMBERS.md` for measurements.

## [1.0.1] - 2025-11-04

### Fixed
- **Stack overflow protection**: Implemented configurable recursion depth limiting to prevent stack overflow with deeply nested or circular schemas
  - Default limit: 1000 validation depth levels
  - Covers all 21 recursive validation paths (references, combiners, properties, arrays, conditionals)
  - Thread-safe implementation using parameter-based depth tracking
  - Resolves HIGH severity security issue (7.5/10 risk level)

### Changed
- `JsonValidator` constructor now accepts optional `maxValidationDepth: Int = 1000` parameter
- `ReferenceResolver` simplified to be stateless, improving thread-safety
  - Removed mutable state (`recursionDepth`, `schemaCache`)
  - Reference resolution depth now handled by global validation depth tracking

### Added
- `DepthLimitTest.kt` with 15 comprehensive test cases covering:
  - Deeply nested objects and arrays
  - Circular `$ref` references
  - Complex schema combiners (allOf, anyOf, oneOf, not)
  - Conditional validation (if/then/else)
  - Pattern properties
  - Mixed nesting scenarios
  - Edge cases at exact depth limits

### Documentation
- Updated README.md to reflect resolved stack overflow limitation
- Updated CLAUDE.md with new configuration options and recommendations
- Updated test coverage counts (155 total custom tests)
- Marked recursion depth limiting as completed in roadmap

### Technical Details
- Backward compatible: existing code works without changes (new parameter has default value)
- Performance impact: minimal (single integer increment per validation level)
- Error message includes actual depth limit and helpful context
- Error keyword: "depth" for programmatic detection

## [1.0.0] - 2024-XX-XX

### Initial Release
- JSON Schema validator for Kotlin
- Support for Draft 2019-09 and Draft 2020-12
- 92.8% official test suite coverage (2,205/2,376 tests passing)
- Thread-safe synchronous validation
- Comprehensive feature support (types, combiners, conditionals, references)
- Performance tested with schemas up to 50MB and data up to 20MB
