# Spec: standard JSON Schema output formats

**Status**: design approved, in progress — `Flag`, `Basic` and `Detailed` in this round;
`Verbose` and `absoluteKeywordLocation` deferred (see [Deferred](#deferred))
**Depends on**: [SPEC_INSTANCE_LOCATION.md](SPEC_INSTANCE_LOCATION.md) (implemented in 3.0.0)

## Motivation

JSON Schema Core 2019-09 and 2020-12 define an interchange format for validation results, so that
output from one implementation can be consumed by tooling written against another — test harnesses,
IDE integrations, API gateways, error renderers.

KtSON emits `List<ValidationError>`, a shape only KtSON understands. Anything wanting to consume
its results must be written specifically against it. Producing the standard format costs nothing
at validation time and makes the library interoperable.

## Current behaviour

```kotlin
ValidationError(
    instanceLocation = "/user/age",                // RFC 6901 JSON Pointer
    message = "Expected type(s): integer, but got: string",
    keyword = "type",
    schemaPath = "/properties/user/properties/age/type",   // = keywordLocation
    causes = emptyList(),
)
```

Against the spec's output unit, KtSON has `keywordLocation`, `error` and `instanceLocation`,
and has no `absoluteKeywordLocation` (the URI of the schema resource plus fragment,
which matters once `$ref` crosses into another document).

Only `allOf`, `anyOf` and `oneOf` nest errors through `causes`; every other keyword reports a flat
leaf carrying its own keyword location. Every error the validator constructs sets `schemaPath`,
including depth-limit errors; it can only be `null` on a hand-built `ValidationError`.

## Scope of this round

`Flag`, `Basic` and `Detailed`, implemented as a pure mapping over `ValidationResult`. The
validation path does not change, so callers who never ask for output pay nothing.

## Design

All of it lives in one file, `src/main/kotlin/org/ktson/OutputFormat.kt`, so that a later revision
of the output section (the next draft reworks it) is a local change.

### API

```kotlin
/** VERBOSE is deferred and deliberately not declared, so it cannot fail at runtime. */
enum class OutputFormat { FLAG, BASIC, DETAILED }

@Serializable
data class OutputUnit(
    val valid: Boolean,
    val keywordLocation: String? = null,
    val absoluteKeywordLocation: String? = null,   // always null in this round
    val instanceLocation: String? = null,
    val error: String? = null,
    val errors: List<OutputUnit>? = null,
) {
    /** Standard JSON, independent of the caller's Json configuration: null fields are omitted. */
    fun toJson(): String
}

fun ValidationResult.toOutput(format: OutputFormat = OutputFormat.BASIC): OutputUnit
```

`keywordLocation` and `instanceLocation` are nullable because `Flag` output is `{"valid": …}` and
nothing else. `toJson()` uses a fixed internal `Json` instance (no defaults encoded, no nulls), so a
caller whose own `Json` sets `encodeDefaults = true` still gets exact standard output from it.
Callers may also serialise `OutputUnit` with their own `Json`.

Adding `VERBOSE` later is source-compatible except for exhaustive `when` expressions over
`OutputFormat`; the KDoc says so.

### Mapping a `ValidationError` to a unit

| Unit field | Source |
|---|---|
| `valid` | `false` |
| `keywordLocation` | `schemaPath`, or `""` (the root) when `null` |
| `instanceLocation` | `instanceLocation` |
| `error` | `message` |
| `errors` | `causes`, mapped recursively (`Detailed` only) |

### Formats

| Format | Valid result | Invalid result |
|---|---|---|
| `FLAG` | `{"valid": true}` | `{"valid": false}` — never an `errors` key |
| `BASIC` | `{"valid": true, "keywordLocation": "", "instanceLocation": ""}` | root `{"valid": false, "keywordLocation": "", "instanceLocation": "", "errors": [...]}` with the whole error tree flattened depth-first, each parent before its causes |
| `DETAILED` | as `BASIC` | the same root, with `errors` keeping the `causes` hierarchy |

Decisions:

- **`Basic` includes parent units.** An `anyOf` summary unit and its leaves all appear in the flat
  list, as in the spec's own `Basic` example. Each parent carries KtSON's message as `error`.
- **`Detailed` collapses only the root.** If the root would have exactly one child, the output is
  that child. Inner nodes are never collapsed: each carries its own keyword location and message,
  so none of them "adds nothing".
- **The root carries `""` locations** in `Basic` and `Detailed`. The spec's prose example omits
  them, but the output schema's `outputUnit` definition requires `keywordLocation` and
  `instanceLocation`, so including them is the stricter reading.

## Known limitation: `absoluteKeywordLocation`

2020-12 §12.3.3 allows `absoluteKeywordLocation` to be omitted only if the dynamic scope did not
pass over a reference, or if the schema declares no absolute URI as its `$id`. The official output
schema likewise requires it for any unit whose `keywordLocation` contains `/$ref/` or
`/$dynamicRef/`.

This round always omits it. Output for errors reached through `$ref` in a schema with an absolute
`$id` is therefore not fully compliant. The KDoc and README say so. (The official output schema
does not catch this in practice: its root `anyOf` admits any object with a boolean `valid` through
the `flag` branch.)

## Deferred

- **`absoluteKeywordLocation`.** `ReferenceResolver` already tracks absolute URIs per schema element
  (`schemaAbsoluteUris`, `registerAbsoluteUri`) for relative `$id` resolution. Threading the resource
  URI alongside the keyword location — as `validateElement` already threads `resourceRoot` — and
  adding it to `ValidationError` is enough. It touches the recursive validation path.
- **`Verbose`.** Requires recording *successful* applications, which the validator does not keep.
- **Annotations** (`annotation`/`annotations` in output units). KtSON does not collect annotations;
  the suite's `readOnly` output test depends on them.

## Caveats

- The output section of 2019-09/2020-12 is thin, and later drafts have reworked it. This implements
  2020-12 §12; 2019-09 §10.4 is the same shape.
- Message text is deliberately not standardised by the spec. Do not let the format tempt anyone
  into freezing KtSON's message wording.

## Test plan

**`OutputFormatTest.kt`**, under both drafts:

- One schema failing in two branches (a failing `properties` member alongside an `anyOf` whose
  branches fail); exact expected JSON for `FLAG`, `BASIC` and `DETAILED`, compared via `toJson()`.
- `Flag` output never contains an `errors` key, for valid and invalid results.
- Valid results in every format.
- `Detailed` collapsing: one top-level error collapses to that unit; two or more stay under the root.
- Escaping: a property named `~a/b` yields `/properties/~0a~1b/type` and `/~0a~1b`.
- Round trip: `Json.decodeFromString<OutputUnit>(unit.toJson()) == unit`.
- A hand-built `ValidationError` without `schemaPath` maps to `keywordLocation: ""`.

**`OfficialOutputTestRunner.kt`**, alongside `OfficialTestSuiteRunner`:

- Reads `../JSON-Schema-Test-Suite/output-tests/{draft2019-09,draft2020-12}/content/*.json`.
- For each test, validates `data` against `schema`, maps the result with `toOutput(BASIC)`, and
  validates that output with KtSON against the test's `output.basic` schema. A `schemaLoader` serves
  the draft's `output-schema.json` for its `https://json-schema.org/draft/<draft>/output/schema` `$id`.
- Skips `readOnly.json` (needs annotations); ignores `v1/` (a later release KtSON does not support).
- Missing suite: warns locally, fails when `CI` is set — as `OfficialTestSuiteRunner` does.
- These are separate from the `tests/` assertion counts in CLAUDE.md and the `bump-test-suite`
  skill, and are reported separately.

## Effort

Medium: one file holding a data class, an enum and a mapper; a unit test file; a runner.

## References

- JSON Schema Core 2020-12 §12, Output Formatting
- JSON Schema Core 2019-09 §10.4
- `JSON-Schema-Test-Suite/output-tests/README.md`
- `src/main/kotlin/org/ktson/ValidationError.kt`
- `src/main/kotlin/org/ktson/JsonPointer.kt` — `ReferenceResolver.getAbsoluteUri`
