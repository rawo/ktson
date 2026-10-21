# Spec: standard JSON Schema output formats

**Status**: `Flag`, `Basic` and `Detailed` implemented in 3.1.0; `absoluteKeywordLocation`
implemented, unreleased (see [absoluteKeywordLocation](#absolutekeywordlocation)); annotations
deferred (see [Deferred](#deferred)); `Verbose` dropped (see [Dropped](#dropped))
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

Against the spec's output unit, KtSON had `keywordLocation`, `error` and `instanceLocation`,
but no `absoluteKeywordLocation` (the URI of the schema resource plus fragment, which matters once
`$ref` crosses into another document). That was added after 3.1.0; see
[absoluteKeywordLocation](#absolutekeywordlocation).

Only `allOf`, `anyOf` and `oneOf` nest errors through `causes`; every other keyword reports a flat
leaf carrying its own keyword location. Every error the validator constructs sets `schemaPath`,
including depth-limit errors; it can only be `null` on a hand-built `ValidationError`.

## Scope of this round

`Flag`, `Basic` and `Detailed`, implemented as a pure mapping over `ValidationResult`. The
validation path does not change, so callers who never ask for output pay nothing.

`absoluteKeywordLocation` was not a field of any output type in 3.1.0; it was added afterwards
with its implementation (see [absoluteKeywordLocation](#absolutekeywordlocation)).

## Design

The output types, the tokens and the mapping live in one file, `src/main/kotlin/org/ktson/OutputFormat.kt`,
so that a later revision of the output section (the next draft reworks it) is a local change. The
only other change is the `validate(instance, schema, format)` overload on `JsonValidator`.

### API

Each format is its own type, carrying exactly the fields that format defines. The caller picks the
format with a typed token, so the return type is known statically, with no reified generics and no
runtime type checks, and the API is callable from Java.

```kotlin
sealed interface ValidationOutput {
    val valid: Boolean
    /** Standard JSON for this format; never contains a type discriminator. */
    fun toJson(): String
}

@Serializable
data class FlagOutput(override val valid: Boolean) : ValidationOutput

@Serializable
data class BasicOutput(
    override val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val errors: List<BasicUnit>? = null,          // null when valid
) : ValidationOutput

@Serializable
data class BasicUnit(                             // a flat list entry: never nests
    val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val error: String,
    val absoluteKeywordLocation: String? = null,  // added after 3.1.0
)

@Serializable
data class DetailedOutput(                        // a node of the failure tree
    override val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val error: String? = null,                    // null on an uncollapsed root
    val errors: List<DetailedOutput>? = null,     // null on leaves and when valid
    val absoluteKeywordLocation: String? = null,  // added after 3.1.0
) : ValidationOutput

/** No VERBOSE: see Dropped. A later format is added together with its output type and implementation. */
sealed class OutputFormat<T : ValidationOutput> {
    object Flag : OutputFormat<FlagOutput>()
    object Basic : OutputFormat<BasicOutput>()
    object Detailed : OutputFormat<DetailedOutput>()
}

// JsonValidator
fun validate(instance: JsonElement, schema: JsonSchema): ValidationResult            // unchanged
fun <T : ValidationOutput> validate(instance: JsonElement, schema: JsonSchema, format: OutputFormat<T>): T

// ValidationResult
fun <T : ValidationOutput> ValidationResult.toOutput(format: OutputFormat<T>): T
```

```kotlin
val result = validator.validate(instance, schema)                        // ValidationResult, as today
val basic = validator.validate(instance, schema, OutputFormat.Basic)     // BasicOutput
println(basic.toJson())
```

Decisions:

- **KtSON's native result stays the default.** `validate(instance, schema)` keeps returning
  `ValidationResult`; `ValidationResult` and `ValidationError` stay public and unchanged. Adding the
  format overload is not breaking (a minor release).
- **No `Native` token.** Omitting the format already yields the native result; a token would force
  `ValidationResult` to implement `ValidationOutput`, including a `toJson()` with no standard shape.
- **One implementation.** The format overload is `validate(instance, schema).toOutput(format)`.
  `toOutput` is public for callers who already hold a result. The `validate(instanceJson,
  schemaJson, version)` string overload gets no format variant; such callers use `toOutput`.
- **Java facade `ValidationOutputs`.** `OutputFormat.kt` is annotated
  `@file:JvmName("ValidationOutputs")`, so Java calls
  `ValidationOutputs.toOutput(result, OutputFormat.Basic.INSTANCE)` rather than `OutputFormatKt`.
  Renaming it after release would break Java callers. `JavaOutputUsageTest` runs a Java caller.
- **`toJson()` on every output type.** Serialising through the sealed `ValidationOutput` type would
  make kotlinx-serialization add a `"type"` discriminator, which is not standard output. `toJson()`
  serialises the concrete type with a fixed internal `Json` instance (no defaults encoded, nulls
  omitted), independent of the caller's configuration. The KDoc warns against polymorphic encoding.
- **Adding a format later** (for example the next draft's `hierarchical`) adds a subclass to two
  sealed hierarchies. That is source-compatible except for exhaustive `when` expressions over them;
  the KDoc says so.

### Mapping a `ValidationError` to a unit

| Unit field | Source |
|---|---|
| `valid` | `false` |
| `keywordLocation` | `schemaPath`, or `""` (the root) when `null` |
| `instanceLocation` | `instanceLocation` |
| `error` | `message` |
| `errors` | `causes`, mapped recursively (`DetailedOutput` only) |

### Formats

| Format | Valid result | Invalid result |
|---|---|---|
| `Flag` | `{"valid": true}` | `{"valid": false}` |
| `Basic` | `{"valid": true, "keywordLocation": "", "instanceLocation": ""}` | root `{"valid": false, "keywordLocation": "", "instanceLocation": "", "errors": [...]}` with the whole error tree flattened depth-first, each parent before its causes |
| `Detailed` | as `Basic` | the same root, with `errors` keeping the `causes` hierarchy |

Decisions:

- **`Basic` includes parent units.** An `anyOf` summary unit and its leaves all appear in the flat
  list, as in the spec's own `Basic` example. Each parent carries KtSON's message as `error`.
- **`Detailed` collapses only the root.** If the root would have exactly one child, the output is
  that child. Inner nodes are never collapsed: each carries its own keyword location and message,
  so none of them "adds nothing".
- **The root carries `""` locations** in `Basic` and `Detailed`. The spec's prose example omits
  them, but the output schema's `outputUnit` definition requires `keywordLocation` and
  `instanceLocation`, so including them is the stricter reading.

## absoluteKeywordLocation

2020-12 §12.3.3 allows `absoluteKeywordLocation` to be omitted only if the dynamic scope did not
pass over a reference, or if the schema declares no absolute URI as its `$id`. The official output
schema likewise requires it for any unit whose `keywordLocation` contains `/$ref/` or
`/$dynamicRef/`. 3.1.0 always omitted it; it is now produced.

**Value.** The canonical URI of the schema resource holding the failing keyword, `#`, and a JSON
Pointer within that resource: `https://example.com/root#/$defs/p/minimum` where `keywordLocation`
is `/properties/n/$ref/minimum`. The resource URI is the absolute `$id` of the innermost resource;
for the root without one, `JsonSchema.baseUri`. Anchors and `$id`-identified targets are reported as
JSON Pointers, as §12.3.3 requires.

**Where it lives.** `ValidationError.absoluteKeywordLocation`, copied to `BasicUnit` and
`DetailedOutput` (omitted when null). `ValidationError` keeps a secondary five-argument constructor;
the data-class `copy()` signatures change, so callers compiled against 3.1 must recompile.

**How it is filled** (`AbsoluteLocations.kt`). The evaluation path and the absolute location agree
until the first reference hop. After each `$ref` / `$dynamicRef` / `$recursiveRef` hop, `stamp()`
finds the hop's target by identity in its resource (an iterative depth-first search that switches
resource at each nested `$id`) and stamps the errors the hop produced, including their `causes`,
with the target's location plus the rest of their evaluation path. Errors a deeper hop already
stamped are kept, so the innermost hop wins. `finish()` in `validateInternal()` stamps the rest
against the root URI.

**Cost.** Stamping runs only when a hop produced errors, so valid instances pay nothing; the
search runs once per failing hop.

**Never wrong, never failing.** Where the target cannot be located, or a remaining path still
crosses an unlocated hop, the field stays null rather than wrong. An unparsable URI (the suite's
URN `$id` cases exposed one) counts as not absolute instead of throwing.

**Tests.** `AbsoluteKeywordLocationTest` (local `$ref`, `$anchor`, relative `$id`, remote refs via
`schemaLoader`, reference chains, combiner causes, unresolvable refs, `$dynamicRef`, `$recursiveRef`,
URN `$id`, `baseUri`, and omission without an absolute URI); `OfficialOutputTestRunner` checks KtSON's
Basic output for a `$ref` schema against the official output schema's `basic` definition, and the
suite's `type` and `escape` output tests now compare the exact value.

## Deferred

- **Annotations** (`annotation`/`annotations` in output units). KtSON does not collect annotations;
  the suite's `readOnly` output test depends on them. Tracked in
  [#5](https://github.com/rawo/ktson/issues/5).

## Dropped

- **`Verbose`.** Not planned. The next output-format draft removes it: the suite's
  `output-tests/v1/output-schema.json` defines only `flag`, `basic` and `hierarchical` (which also
  replaces `detailed`). Building it would mean recording every *successful* application — work
  inside the validation path, at a cost to every caller — for a format on its way out, and nobody
  has asked for it. Later output work targets the v1 shapes instead.

## Caveats

- The output section of 2019-09/2020-12 is thin, and later drafts have reworked it. This implements
  2020-12 §12; 2019-09 §10.4 is the same shape.
- Message text is deliberately not standardised by the spec. Do not let the format tempt anyone
  into freezing KtSON's message wording.

## Test plan

**`OutputFormatTest.kt`**, under both drafts:

- One schema failing in two branches (a failing `properties` member alongside an `anyOf` whose
  branches fail); exact expected JSON for `Flag`, `Basic` and `Detailed`, compared via `toJson()`.
- The return types are the concrete ones: `validate(i, s, OutputFormat.Basic)` is a `BasicOutput`
  without a cast (a compile-time check, asserted by assigning to typed vals).
- `validate(i, s, format)` equals `validate(i, s).toOutput(format)` for every format.
- `Flag` output contains only `valid`, for valid and invalid results.
- Valid results in every format.
- `Detailed` collapsing: one top-level error collapses to that unit; two or more stay under the root.
- Escaping: a property named `~a/b` yields `/properties/~0a~1b/type` and `/~0a~1b`.
- No type discriminator: `toJson()` of each output type contains no `"type"` key.
- Round trip: `Json.decodeFromString<BasicOutput>(output.toJson()) == output`, and likewise for
  `FlagOutput` and `DetailedOutput`.
- A hand-built `ValidationError` without `schemaPath` maps to `keywordLocation: ""`.

**`OfficialOutputTestRunner.kt`**, alongside `OfficialTestSuiteRunner`:

- Reads `../JSON-Schema-Test-Suite/output-tests/{draft2019-09,draft2020-12}/content/*.json`.
- For each test, validates `data` against `schema`, calls `validate(data, schema, OutputFormat.Basic)`, and
  validates that output with KtSON against the test's `output.basic` schema. A `schemaLoader` serves
  the draft's `output-schema.json` for its `https://json-schema.org/draft/<draft>/output/schema` `$id`.
- Skips `readOnly.json` (needs annotations); ignores `v1/` (a later release KtSON does not support).
- Missing suite: warns locally, fails when `CI` is set — as `OfficialTestSuiteRunner` does.
- These are separate from the `tests/` assertion counts in CLAUDE.md and the `bump-test-suite`
  skill, and are reported separately.

## Effort

Medium: one file holding four output types, the format tokens and the mapper; one `JsonValidator`
overload; a unit test file; a runner.

## References

- JSON Schema Core 2020-12 §12, Output Formatting
- JSON Schema Core 2019-09 §10.4
- `JSON-Schema-Test-Suite/output-tests/README.md`
- `src/main/kotlin/org/ktson/ValidationError.kt`
- `src/main/kotlin/org/ktson/JsonPointer.kt` — `ReferenceResolver.getAbsoluteUri`
