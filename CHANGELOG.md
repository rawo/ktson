# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
