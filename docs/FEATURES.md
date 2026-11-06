# KtSON Features

## Supported JSON Schema Specifications

### Draft 2020-12 ✅
- All validation keywords, including `prefixItems`, `unevaluatedProperties` and `unevaluatedItems`
- `$dynamicRef` / `$dynamicAnchor` dynamic scope resolution

### Draft 2019-09 ✅
- All validation keywords, including `additionalItems` and array-form `items`
- `$recursiveRef` / `$recursiveAnchor` dynamic recursion

The draft is selected by `$schema` when it names a supported meta-schema exactly; otherwise the caller's version
(default 2020-12) applies.

## Validation Keywords

### Type and Generic ✅
- [x] `type`: string, number, integer, boolean, object, array, null; multiple types
- [x] `const`, `enum`: numerically equal values match (`1` and `1.0`)
- [x] Boolean schemas `true` / `false`

### String ✅
- [x] `minLength` / `maxLength`: counted in Unicode code points
- [x] `pattern`: ECMA 262 dialect (`\s`, `\S`, `\c`, `$`, `.`, `[]`/`[^]`, `\p{...}` aliases translated for Java's engine)
- [x] `format`: asserted by default, an annotation with `formatAssertion = false` (both drafts). 19 formats:
  - Email: `email`, `idn-email`
  - URI: `uri`, `uri-reference`, `uri-template`, `iri`, `iri-reference`
  - Date and time: `date`, `time`, `date-time`, `duration`
  - Network: `ipv4`, `ipv6`, `hostname`, `idn-hostname` (Punycode, RFC 5892 contextual rules, RFC 5893 Bidi rule)
  - Other: `uuid`, `json-pointer`, `relative-json-pointer`, `regex`

### Numeric ✅
- [x] `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`
- [x] Exact arithmetic: `Long` fast path, `BigDecimal` otherwise; no `Double` rounding
- [x] `multipleOf` never expands powers of ten: `1e2147483647` is decided from its digits as written
- [x] Numbers are converted only when a numeric keyword needs them

### Object ✅
- [x] `properties`, `required`, `additionalProperties`, `patternProperties`
- [x] `minProperties`, `maxProperties`, `propertyNames`
- [x] `dependentRequired`, `dependentSchemas`
- [x] `unevaluatedProperties`

### Array ✅
- [x] `items`, `prefixItems` (2020-12), `additionalItems` (2019-09)
- [x] `minItems`, `maxItems`, `uniqueItems`
- [x] `contains`, `minContains`, `maxContains`
- [x] `unevaluatedItems`

### Combining and Conditional ✅
- [x] `allOf`, `anyOf`, `oneOf`, `not`
- [x] `if` / `then` / `else`

### References and Anchors ✅
- [x] `$ref`: JSON Pointer and named anchors, respecting `$id` resource boundaries
- [x] `$ref` to other documents through the pluggable `schemaLoader` callback, which receives absolute URIs
- [x] `$anchor`, `$dynamicAnchor` / `$dynamicRef`, `$recursiveAnchor` / `$recursiveRef`
- [x] `$id` with RFC 3986 relative resolution; relative references resolve against `JsonSchema.baseUri`
- [x] `$defs` / `definitions`
- [x] Sibling keywords evaluated alongside `$ref`
- [x] Percent-decoded fragments (RFC 6901 §6)

## Results and Error Reporting ✅

- `validate()` returns `ValidationResult.Valid` or `ValidationResult.Invalid(validationErrors)`; it reports every
  error, not just the first
- Each `ValidationError` carries:
  - `instanceLocation`: an RFC 6901 JSON Pointer to the failing value (`/users/0/age`)
  - `keyword`: the failing keyword
  - `schemaPath`: the keyword location, including `$ref` hops (`/properties/n/$ref/minimum`)
  - `absoluteKeywordLocation`: the canonical URI of the keyword in its own schema resource
    (`https://example.com/root#/$defs/p/minimum`), when the schema has an absolute `$id` or a base URI
  - `causes`: the branch errors behind `anyOf`, `oneOf` and similar failures
  - `message`: a human-readable description; quoted values are cut at about 100 characters, however large
- `validateSchema()` checks a schema against its draft's embedded meta-schema

## Standard Output Formats ✅

JSON Schema 2020-12 §12 output, through `validate(instance, schema, format)` or `result.toOutput(format)`:

- `OutputFormat.Flag`: `{"valid": …}` only
- `OutputFormat.Basic`: every failing unit in a flat list
- `OutputFormat.Detailed`: the `allOf` / `anyOf` / `oneOf` hierarchy

Serialise with `toJson()`. Basic output passes the official `output-tests`. Annotations in output are not
implemented yet ([#5](https://github.com/rawo/ktson/issues/5)).

## Robustness ✅

- **Depth limit:** `maxValidationDepth` (default 1000) protects against deep and circular schemas. A stack
  overflow is reported as a `depth` error, never thrown
- **Undecided results:** results the validator cannot decide (the depth limit, a regex the engine cannot
  evaluate) are never turned into Valid by `not`, `if`, `oneOf` or `maxContains`
- **Bounded cost:** work follows the size of the input as written, not exponents, nesting of `unevaluated*`, or
  impossible lengths. This applies to `multipleOf`, IDN labels, the `regex` format, IP addresses and `contains`; a
  reference applied to many items finds its target once per validation
- **No throwing on malformed input:** non-string `$id` / `$schema` values are reported by `validateSchema`, not
  thrown, and an unparsable URI leaves `absoluteKeywordLocation` empty instead of failing validation

## Thread Safety and Memory ✅

- One `JsonValidator` can be shared across threads; each validation has its own state
- No global state: every cache belongs to one validator, so dropping a validator frees everything it built
- Caches of compiled patterns and numeric limits are bounded (1,024 entries each)
- Each remote schema is loaded once per validator, even under concurrent use. A loader may validate through the
  same validator, and load cycles across threads are broken

## API ✅

- Synchronous Kotlin API; run validation on a worker thread to keep it off I/O or UI threads
- Java-friendly: `@JvmStatic` factories and `@JvmOverloads` defaults. Java callers are tested in
  `src/test/java/org/ktson/`
- Native-image friendly: no reflection, runtime resources or dynamic class loading
- Validates complete JSON values; frame streams (for example NDJSON) into units before validating

## Test Coverage

- **Official JSON Schema Test Suite:** 4,462 / 4,462 assertions (100%) for `draft2019-09` and `draft2020-12`,
  including `optional/format`, `optional/bignum.json` and `optional/ecmascript-regex.json`; plus the official
  `output-tests` against Basic output
- **Custom tests:** 1,381, covering keywords for both drafts, formats, references, `unevaluated*`, output formats,
  error locations, numeric precision, concurrency, validator lifecycle and Java callers. The README's Test Coverage
  section and `CLAUDE.md` break them down per file
- **Performance tests:** 9, run with `./gradlew performanceTest`

See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for what is not implemented.
