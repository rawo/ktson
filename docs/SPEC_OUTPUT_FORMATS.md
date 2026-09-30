# Spec: standard JSON Schema output formats

**Status**: proposed, not implemented
**Depends on**: [SPEC_INSTANCE_LOCATION.md](SPEC_INSTANCE_LOCATION.md) (needs RFC 6901 instance locations)

## Motivation

JSON Schema Core 2019-09 and 2020-12 define an interchange format for validation results, so that
output from one implementation can be consumed by tooling written against another — test harnesses,
IDE integrations, API gateways, error renderers.

KtSON emits `List<ValidationError>`, a shape only KtSON understands. Anything wanting to consume
its results must be written specifically against it. Producing the standard format costs nothing
at validation time and makes the library interoperable.

The pieces are now mostly in place: `ValidationError` carries the keyword location (`schemaPath`),
the keyword, a message, and nested `causes`. What is missing is the instance location as a pointer,
the absolute keyword location, and the serialisation itself.

## Current behaviour

```kotlin
ValidationError(
    path = "user.age",                             // non-standard notation
    message = "Expected type(s): integer, but got: string",
    keyword = "type",
    schemaPath = "/properties/user/properties/age/type",   // = keywordLocation
    causes = emptyList(),
)
```

Against the spec's output unit, KtSON has `keywordLocation` and `error`, needs `instanceLocation`
in pointer form, and has no `absoluteKeywordLocation` (the URI of the schema resource plus fragment,
which matters once `$ref` crosses into another document).

## Design

### Output unit

```kotlin
@Serializable
data class OutputUnit(
    val valid: Boolean,
    val keywordLocation: String,
    val absoluteKeywordLocation: String? = null,
    val instanceLocation: String,
    val error: String? = null,
    val errors: List<OutputUnit>? = null,
)
```

### Formats

| Format | Content |
|---|---|
| `Flag` | `{"valid": false}` and nothing else |
| `Basic` | one flat list of every failing unit under `errors` |
| `Detailed` | the failure hierarchy, collapsing nodes that add nothing |
| `Verbose` | the full hierarchy including successful units |

`Flag` and `Basic` are the useful ones and should land first. `Detailed` follows naturally from
`ValidationError.causes`, which is already a tree. `Verbose` requires recording *successful*
applications, which the validator does not currently keep — it is the only format needing changes
inside the validation path, and it should be treated as optional.

### Entry point

```kotlin
enum class OutputFormat { FLAG, BASIC, DETAILED, VERBOSE }

fun ValidationResult.toOutput(format: OutputFormat = OutputFormat.BASIC): OutputUnit
```

A pure function over the existing result — no change to the validation path for the first three
formats, so there is no cost for callers who do not ask for it.

### absoluteKeywordLocation

`ReferenceResolver` already tracks absolute URIs per schema element (`schemaAbsoluteUris`,
`registerAbsoluteUri`) for relative `$id` resolution. Threading the resource URI alongside the
keyword location — as `validateElement` already threads `resourceRoot` — is enough to fill this in.
Until then the field should be omitted rather than guessed; the spec allows it to be absent.

## Caveats

- The output section of 2019-09/2020-12 is thin, and later drafts have reworked it. Implement
  against 2020-12 §12 and expect the shape to move in a future draft; keep the mapping in one file
  so a later revision is a local change.
- The official test suite does not test output formats. There is a separate
  [JSON-Schema-Test-Suite output-tests](https://github.com/json-schema-org/JSON-Schema-Test-Suite)
  directory for them; check whether it is usable before hand-rolling fixtures.
- Message text is deliberately not standardised by the spec. Do not let the format tempt anyone
  into freezing KtSON's message wording.

## Test plan

- Unit tests per format against a schema failing in two branches, asserting exact JSON.
- A test that `Flag` output never contains an `errors` key.
- A round-trip test: serialise with kotlinx-serialization and deserialise back.
- If the suite's output-tests are usable, wire them in behind a new runner alongside
  `OfficialTestSuiteRunner`.

## Effort

Medium for `Flag`/`Basic`/`Detailed` (a data class, a mapper, tests). Larger for `Verbose` plus
`absoluteKeywordLocation`, both of which reach into the validation path.

## References

- JSON Schema Core 2020-12 §12, Output Formatting
- JSON Schema Core 2019-09 §10.4
- `src/main/kotlin/org/ktson/ValidationError.kt`
- `src/main/kotlin/org/ktson/JsonPointer.kt` — `ReferenceResolver.getAbsoluteUri`
