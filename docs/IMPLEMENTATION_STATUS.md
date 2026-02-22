# Implementation Status

## Current State

**Pass Rate**: 99.6% (2,403 / 2,412 official suite tests)
**Failures**: 9 — all require remote schema loading (HTTP/HTTPS)

## What's Implemented ✅

### Core Keywords (all drafts)
- Type, const, enum, boolean schemas
- String: minLength, maxLength, pattern, format (8 formats)
- Numeric: minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf
- Object: properties, required, additionalProperties, patternProperties, minProperties, maxProperties, propertyNames, dependentRequired, dependentSchemas
- Array: items, prefixItems, additionalItems, minItems, maxItems, uniqueItems, contains, minContains, maxContains
- Combiners: allOf, anyOf, oneOf, not
- Conditional: if/then/else

### References and Anchors
- `$ref` — JSON Pointer and named anchor resolution, respects `$id` resource boundaries
- `$anchor` / `$dynamicAnchor` — named anchors (both static and dynamic)
- `$dynamicRef` — Draft 2020-12 dynamic scope resolution
- `$recursiveRef` / `$recursiveAnchor` — Draft 2019-09 dynamic recursion
- `$id`-based schema lookup with RFC 3986 relative URI resolution
- Percent-decoding of URI fragments (RFC 6901 §6)
- Sibling keyword evaluation alongside `$ref` (Draft 2019-09+)

### Unevaluated Keywords
- `unevaluatedProperties` — annotation-based, with full propagation through all applicators
- `unevaluatedItems` — annotation-based, contains annotation (matching indices only)
- Correct scoping: cousins/uncles cannot contribute annotations to a nested unevaluated check

### Infrastructure
- Draft 2019-09 and 2020-12 version detection and dispatch
- Configurable recursion depth limit (default 1000)
- Recursion/cycle detection
- Thread-safe stateless design

## What's Not Implemented ⚠️

### Remote Schema Loading (9 remaining failures)
**Status**: Not implemented
**Effort**: Medium (HTTP client + schema cache + integration)

Needed for:
- `$ref: "http://..."` — fetching external schemas
- `$ref: "other.json"` — cross-document references when documents aren't bundled
- Meta-schema validation (fetches `https://json-schema.org/...`)

### Optional / Low-Priority
- Advanced format validators: hostname, idn-email, idn-hostname, iri, iri-reference, json-pointer, relative-json-pointer, regex (currently silently ignored)
- `$vocabulary` — custom vocabulary declaration
- ECMA-262 regex dialect (optional test suite)

## Historical Progress

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
| After `unevaluatedItems` | **99.6%** | **9** |
