# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Standard JSON Schema output formats (2020-12 §12): `validate(instance, schema, format)` and
  `ValidationResult.toOutput(format)` return `FlagOutput`, `BasicOutput` or `DetailedOutput`,
  typed by the `OutputFormat` token; `toJson()` gives the standard JSON. `validate(instance, schema)`
  still returns `ValidationResult`. `Verbose` and `absoluteKeywordLocation` are not supported yet.
  Java calls the conversion as `ValidationOutputs.toOutput(result, OutputFormat.Basic.INSTANCE)`.
- The suite's `output-tests` run against Basic output for both drafts (`readOnly` skipped).

## [3.0.0] - 2026-10-15

Two breaking changes; see "Upgrading from 2.0.0" in the README for migration steps.

### Changed
- **Breaking error API change:** `ValidationError.instanceLocation` replaces the primary `path`
  field with an RFC 6901 JSON Pointer. Named constructor and `copy(path = ...)` arguments must
  migrate to `instanceLocation = ...`; positional construction and destructuring now use pointers.
  The deprecated `path` getter provides a lossy legacy display string; `toString()` uses pointers.
- **Breaking verdict changes:** numeric bounds, integer classification, `multipleOf`, and equality
  for `const`, `enum` and `uniqueItems` now use exact `Long`/`BigDecimal` arithmetic. Large
  integers and high-precision fractions no longer collapse through `Double`, and numeric strings
  remain distinct from numbers. Previously accepted rounded values and near-multiples may now fail
  validation; valid large integers such as `1e400` are accepted as integers.
- `propertyNames` errors point to the owning object and identify the rejected key in the message,
  including failures from boolean schemas and combiners.

### Fixed
- JSON Pointer `/` and `#/` now resolve to a member with an empty key instead of the root.
- Schema-structure errors report the right keyword location: nested `patternProperties` and
  `dependentSchemas` were reported under `properties`, and `allOf`, `anyOf`, `oneOf` and
  `prefixItems` sub-schemas were missing their keyword.

### Added
- 117 instance-location and JSON Pointer tests, including both drafts, nested causes, references,
  unusual keys, schema-structure errors and the deprecated accessor.
- Official `optional/bignum.json` coverage for both drafts and 88 numeric regression/concurrency tests.
- Three numeric performance benchmarks; integer fast paths and a cache of parsed schema limits
  reduce the cost of exact arithmetic. Decimal validation remains slower than approximate arithmetic;
  see `docs/SPEC_ARBITRARY_PRECISION_NUMBERS.md` for measurements.
- GitHub release notes list breaking changes in their own section, from a `!` after the commit
  type or a `BREAKING CHANGE:` footer.

## [2.0.0] - 2026-09-30

The public API is unchanged, so 2.0.0 is a drop-in replacement at compile time. The major version
reflects **changed validation verdicts**; see "Upgrading from 1.0.0" in the README.

### Changed
- **Breaking verdict changes:** format validation is much stricter. Of 879 official format cases,
  112 now answer differently (100 valid → invalid, 10 invalid → valid, 2 that previously threw).
  Most affected: `hostname` and `idn-hostname` (A-labels are decoded and validated),
  `uri-template` (full RFC 6570 grammar), the `uri`/`iri` family (percent-encoding, control
  characters, authority rules) and `regex` (ECMA 262 instead of Java syntax). This applies on
  default settings, since `formatAssertion` defaults to `true`.
- `ValidationError.schemaPath` is now always populated with the keyword location
  (e.g. `/properties/n/$ref/minimum`), so `toString()` output differs. Messages render numbers as
  written (`maximum 10`, not `10.0`) and quote the offending value.
- Dependency floor: Kotlin 2.4.0, kotlinx-serialization-json 1.8.1, coroutines 1.10.2.

### Fixed
- `pattern` no longer throws `PatternSyntaxException` on the ECMA 262 empty character classes
  `[]` and `[^]`; both now validate normally.

### Added
- Complete validation for all 19 supported formats.
- The official `optional/format` suite now runs: 1,748 assertions that previously did not run at
  all. Official coverage rose from 2,548 to 4,296 assertions, all passing.
- CI runs the official test suite, pinned to a known commit (`TEST_SUITE_REF`).

### Known Limitations
- Numeric comparisons go through `Double`, so `minimum`, `maximum`, `const` and `enum` can report
  valid for instances beyond 2^53 that violate the schema. Fixed in 3.0.0.

## [1.0.0] - 2026-06-05

First release.

### Added
- JSON Schema validator for Kotlin supporting Draft 2019-09 and Draft 2020-12, with a thread-safe
  synchronous API.
- 100% pass rate on the official JSON Schema Test Suite (2,548 assertions).
- Reference resolution: `$ref` with sibling keywords, `$anchor` and `$id`-based lookup,
  `$recursiveRef`/`$recursiveAnchor`, `$dynamicRef` dynamic scope, and remote schemas through the
  pluggable `schemaLoader`.
- `unevaluatedProperties` and `unevaluatedItems`.
- Format validators: email, uri, uri-reference, uri-template, iri, iri-reference, date, time,
  date-time, duration, ipv4, ipv6, uuid, hostname, idn-hostname, idn-email, json-pointer,
  relative-json-pointer and regex.
- Error messages with actual values and nested causes.
- **Stack overflow protection:** configurable recursion depth limit,
  `JsonValidator(maxValidationDepth = 1000)`. Covers nested schemas, circular `$ref`, combiners
  and conditionals; exceeding it yields an error with keyword `depth`. `ReferenceResolver` is
  stateless, since depth is tracked by the validator.
- Performance tests with schemas up to 50MB and data up to 20MB.
