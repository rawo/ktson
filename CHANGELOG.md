# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `ValidationError.absoluteKeywordLocation` and the same field on `BasicUnit` and `DetailedOutput`:
  the canonical URI of the schema resource holding the failing keyword plus a JSON Pointer within
  it (JSON Schema 2020-12 §12.3.3), following `$ref`, `$dynamicRef` and `$recursiveRef`. It comes
  from the schema's absolute `$id`, else `JsonSchema.baseUri`, and is omitted when neither gives an
  absolute URI. Basic output for `$ref` schemas now satisfies the official output schema.
- `ValidationError` keeps its five-argument constructor for Java and compiled callers; code compiled
  against 3.1 that calls `ValidationError.copy(...)`, `BasicUnit.copy(...)` or
  `DetailedOutput.copy(...)` must be recompiled.

### Fixed
- **Breaking verdict changes** (bug fixes that turn wrongly rejected instances valid):
  - `unevaluatedProperties` / `unevaluatedItems` no longer reject properties and items evaluated by a
    subschema reached through a URI-based `$ref` that uses local refs of its own resource (#6).
  - References to anchors declared beside a property named `$id` now resolve (#7).
  - References whose fragment percent-encodes non-ASCII characters (`#/$defs/%C3%A9`) now resolve (#8).
  - References inside loaded documents with nested relative `$id` resolve the same way on every
    validation; before, each search re-registered the resource one directory deeper (#15).
- **Breaking loader change:** relative `$ref`s now resolve against `JsonSchema.baseUri` (and a relative
  root `$id` against it), so with a `baseUri` set `schemaLoader` receives the absolute URI
  (`https://ex.com/schemas/other.json`, not `other.json`), and schemas with different base URIs no
  longer share one cached document (#9).
- A schema with a property named `$id`, `$anchor` or `$dynamicAnchor` no longer makes `validate()` throw (#7).
- A `false` schema reports its error at its own location (`/properties/a`, not `/properties/a/false`);
  the error's `keyword` stays `"false"` (#46).
- `const` and `enum` messages render the values they quote within a fixed budget (about 100 characters
  per value) instead of embedding them whole, so a huge instance no longer produces a huge message (#48).
- Concurrent validations needing the same remote schema call `schemaLoader` once and wait for that
  load, and the schema's URI is registered before other threads can see it (#41).
- A `$dynamicRef` target is evaluated in its own resource: an external target, or one found through the
  dynamic scope, now resolves its local `#/...` refs in its own document instead of the caller's (#35).
- URI normalization no longer changes what a URI identifies: opaque URIs such as URNs stay whole
  (`urn:example:schema` was reduced to `urn:`), percent-encoded characters stay encoded (`a%2Fb`, `%23`),
  empty path segments are kept, and only the scheme and host are lowercased. A fragment-only reference
  now resolves against an opaque base (`#/x` against `urn:uuid:…`) (#33).
- JSON Pointers no longer resolve non-canonical array indices such as `/01` or `/+1` (RFC 6901 §4), in
  `JsonPointer.resolve` and in `$ref` fragments (#47).
- `absoluteKeywordLocation` is filled under subresources whose `$id` is relative to `baseUri` (#10).
- `absoluteKeywordLocation` is kept for keywords under a property (or `$defs` entry) named `$ref` (#11).
- `absoluteKeywordLocation` percent-encodes its fragment (RFC 6901 §6), so keys with spaces, `%` or
  non-ASCII characters give a valid URI (#12).

### Changed
- `kotlinx-serialization-json` is now an `api` dependency (compile scope in the POM): `JsonElement` is
  part of KtSON's public API, and consumers could not compile code touching it (#39).
- `kotlinx-coroutines-core` is no longer a runtime dependency. Nothing in KtSON's API used it since
  the move to a synchronous API; the published POM now lists only `kotlinx-serialization-json`
  (and the Kotlin standard library). Projects that used coroutines without declaring them must now
  add the dependency themselves.

## [3.1.0] - 2026-10-21

Additive release: no breaking changes and no changed verdicts.

### Added
- Standard JSON Schema output formats (2020-12 §12): `validate(instance, schema, format)` and
  `ValidationResult.toOutput(format)` return `FlagOutput`, `BasicOutput` or `DetailedOutput`,
  typed by the `OutputFormat` token; `toJson()` gives the standard JSON. `validate(instance, schema)`
  still returns `ValidationResult`. `Verbose` and `absoluteKeywordLocation` are not supported yet.
  Java calls the conversion as `ValidationOutputs.toOutput(result, OutputFormat.Basic.INSTANCE)`.
- The suite's `output-tests` run against Basic output for both drafts (`readOnly` skipped).
- Java-friendly API: `JsonSchema.fromString`/`fromElement`/`extractId` and `JsonPointer.resolve`/
  `encodeToken` are static, and defaulted parameters can be omitted from Java on the `JsonSchema`
  and `JsonValidator` constructors, the `JsonSchema` factories and `validate(instanceJson, schemaJson)`.
  The `Companion`/`INSTANCE` and all-argument forms keep working.

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
