# Implementation Status

## Current State

**Pass Rate**: 100% (2,653 / 2,653 official suite tests)
**Failures**: 0

## What's Implemented ✅

### Core Keywords (all drafts)
- Type, const, enum, boolean schemas
- String: minLength, maxLength, pattern, format (13 formats)
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

### Remote Schema Loading ✅
- Pluggable `schemaLoader: ((String) -> JsonElement?)?` on `JsonValidator`
- Thread-safe schema cache (`ConcurrentHashMap`)
- Correct `resourceRoot` tracking when following cross-document refs
- Absolute URI registration for inline schemas with relative `$id`
- Bundled meta-schemas for Draft 2019-09 and Draft 2020-12

### Infrastructure
- Draft 2019-09 and 2020-12 version detection and dispatch
- Configurable recursion depth limit (default 1000)
- Recursion/cycle detection
- Thread-safe stateless design

## What's Not Implemented ⚠️

### Optional / Low-Priority
- Additional format validators: idn-hostname, json-pointer, relative-json-pointer, uri-reference, uri-template (silently ignored)
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
| After `unevaluatedItems` | 99.6% | 9 |
| After remote schema loading | **100%** | **0** |
