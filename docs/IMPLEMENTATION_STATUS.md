# Implementation Status

## Current State (3.1.0 and unreleased fixes, 2026-11-03)

**Official test suite**: 100% (4,462 / 4,462 assertions in `draft2019-09` and `draft2020-12`, including
`optional/format`, `optional/bignum.json` and `optional/ecmascript-regex.json`). 168 assertions are excluded:
`vocabulary.json`, `infinite-loop-detection.json` and the rest of `optional/`. The suite is pinned to
`TEST_SUITE_REF` in `.github/workflows/build.yml`.

**Custom tests**: 1,274 (100% passing); see the README's Test Coverage section and the per-file list in `CLAUDE.md`.

## What's Implemented ✅

### Core Keywords (both drafts)
- Type, const, enum, boolean schemas
- String: minLength, maxLength (Unicode code points), pattern (ECMA 262 dialect), format (19 formats)
- Numeric: minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf
- Object: properties, required, additionalProperties, patternProperties, minProperties, maxProperties,
  propertyNames, dependentRequired, dependentSchemas
- Array: items, prefixItems, additionalItems, minItems, maxItems, uniqueItems, contains, minContains, maxContains
- Combiners: allOf, anyOf, oneOf, not
- Conditional: if/then/else

### References and Anchors
- `$ref`: JSON Pointer and named anchor resolution, respecting `$id` resource boundaries
- `$anchor` / `$dynamicAnchor`, `$dynamicRef` (2020-12), `$recursiveRef` / `$recursiveAnchor` (2019-09)
- `$id`-based lookup with RFC 3986 resolution (`UriResolver`, `UriSyntax`; no `java.net.URI`)
- Relative references resolved against `JsonSchema.baseUri`
- Percent-decoded fragments, sibling keywords alongside `$ref`

### Unevaluated Keywords
- `unevaluatedProperties` / `unevaluatedItems`, annotation-based, through every applicator, across URI-based
  `$ref`s and inline `$id` resources
- Branch verdicts are reused during annotation collection, so nesting costs linear rather than exponential time (#30)

### Remote Schema Loading
- Pluggable `schemaLoader: ((String) -> JsonElement?)?` receiving absolute URIs
- Per-validator `SchemaStore`: one load per URI under concurrency, loaders may re-enter the validator, load cycles
  across threads are broken

### Formats
- 19 formats: email, idn-email, uri, uri-reference, uri-template, iri, iri-reference, date, time, date-time,
  duration, ipv4, ipv6, uuid, hostname, idn-hostname, json-pointer, relative-json-pointer, regex
- Asserted by default; `formatAssertion = false` makes `format` an annotation in both drafts

### Numbers
- Exact `Long`/`BigDecimal` comparisons, integer classification, equality and `multipleOf`
- Bounded by the digits as written, never by exponents: `1e2147483647` and `100e2147483647` neither throw nor
  expand (#70, #86); numbers are converted only for numeric keywords (#84)

### Results and Output
- `ValidationResult` with `ValidationError`: RFC 6901 `instanceLocation`, `keyword`, `schemaPath` (keyword
  location through `$ref` hops), `causes`, `absoluteKeywordLocation`
- Standard output formats (2020-12 §12): Flag, Basic, Detailed
- `validateSchema()` against the embedded 2019-09 and 2020-12 meta-schemas

### Infrastructure
- Draft 2019-09 and 2020-12 detection from `$schema` (exact meta-schema identifiers)
- Configurable depth limit (`maxValidationDepth`, default 1000); stack overflow is reported, never thrown
- Undecided results (depth limit, unevaluable regex) are never inverted into Valid
- Thread-safe, no global state; per-validator caches are bounded
- Java-friendly API (`@JvmStatic`, `@JvmOverloads`), native-image friendly (no reflection or runtime resources)

## What's Not Implemented ⚠️

- `$vocabulary`: custom vocabulary declaration
- Annotations in output ([#5](https://github.com/rawo/ktson/issues/5)); the Verbose output format is not planned
- Full IDNA derived-property table: code point validity comes from Unicode categories plus the RFC 5892 exception
  list, which passes the official suite but may misjudge exotic code points
- Numbers whose exponent falls outside `BigDecimal`'s 32-bit scale (such as `1e-2147483648`)

## Historical Progress

The early milestones were measured against a smaller selection of the suite (2,376, later 2,653 assertions); the
selection grew to 4,462 as `optional/format`, `optional/bignum.json` and `optional/ecmascript-regex.json` were added.

| Milestone | Pass Rate | Failures |
|---|---|---|
| Initial (MVP) | 88.8% | 251 |
| After decimal constraint fixes | 90.1% | 222 |
| After re-enabling ref/anchor/defs/id tests | — | 217 |
| After sibling keyword fix | — | 215 |
| After `$anchor` + `$id`-based lookup | — | 179 |
| After `$recursiveRef` / `$recursiveAnchor` | — | 167 |
| After `$dynamicRef` dynamic scope | — | 154 |
| After `$ref` resource-boundary scoping | — | 148 |
| After `unevaluatedProperties` | — | 56 |
| After `unevaluatedItems` | 99.6% | 9 |
| After remote schema loading (2,653 assertions) | 100% | 0 |
| With `optional/format`, `bignum`, `ecmascript-regex` (4,462 assertions) | **100%** | **0** |
