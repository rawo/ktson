# Standard Output Formats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let callers get JSON Schema 2020-12 §12 standard output (`Flag`, `Basic`, `Detailed`) from KtSON, statically typed per format.

**Architecture:** A pure mapping over the existing `ValidationResult`, living in one new file
`OutputFormat.kt`: one output type per format, a typed `OutputFormat<T>` token that selects it, and
a `ValidationResult.toOutput(format)` extension. `JsonValidator` gains a one-line
`validate(instance, schema, format)` overload. The validation path does not change.

**Tech Stack:** Kotlin 2.4.0, Java 21, kotlinx-serialization-json 1.8.1 (plugin already applied), Kotest 5.7.2 `DescribeSpec`, Gradle 9.1.0, ktlint 1.7.1.

**Spec:** `docs/SPEC_OUTPUT_FORMATS.md` (approved). Read it before starting; this plan implements it.

## Global Constraints

- Package `org.ktson`; all output types, the tokens and the mapping live in `src/main/kotlin/org/ktson/OutputFormat.kt`.
- `validate(instance: JsonElement, schema: JsonSchema): ValidationResult` stays unchanged; `ValidationResult` and `ValidationError` stay public and unchanged.
- No `Verbose` format, no `Native` token, no `absoluteKeywordLocation` field, no annotations in this round.
- The sealed `ValidationOutput` interface is **not** `@Serializable`; every concrete output type has `toJson()` that serialises the concrete type with one fixed internal `Json { encodeDefaults = false }`, so null-defaulted fields are omitted. Do not use `explicitNulls` (experimental API, needs opt-in).
- Tests must not freeze KtSON's message wording: structural JSON assertions mask `error` values (helper `normalized()` in Task 1). Only the dedicated "messages come from the native errors" tests compare message text, and they compare against the native result, not literals.
- Run `./gradlew ktlintFormat` before every commit (a Claude Code hook also runs it after each `.kt` edit).
- Commits follow Conventional Commits; end each message with the attribution trailer the session specifies.

## Review Focus

- A hand-built `ValidationResult.Invalid(emptyList())`: every format must still produce output (`Basic` with `"errors": []`, `Detailed` root with `"errors": []`, never a crash or a collapse into nothing). Test added in Task 3.
- Nested combiners (`anyOf` inside `allOf`): `Basic` must list parents before children at every depth, and `Detailed` must keep all levels. Tests added in Tasks 2 and 3.
- Messages containing `"` and `\` (the `enum` message `Value 1 is not one of: ["a\"b"]`): `toJson()` must escape them and round-trip exactly. Tests added in Tasks 2 and 3.
- A valid result in `Detailed`: the root must stay (there is no child to collapse into). Test added in Task 3.
- `Flag` for a result with many errors: still exactly `{"valid": false}`, no other key. Test added in Task 1.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `src/main/kotlin/org/ktson/OutputFormat.kt` | `ValidationOutput`, `FlagOutput`, `BasicOutput`, `BasicUnit`, `DetailedOutput`, `OutputFormat<T>` tokens, `toOutput`, the mapping |
| Modify `src/main/kotlin/org/ktson/JsonValidator.kt` (after the existing `validate(instance, schema)` at line 99) | the `validate(instance, schema, format)` overload |
| Create `src/test/kotlin/org/ktson/OutputFormatTest.kt` | unit tests for all three formats, both drafts |
| Create `src/test/kotlin/org/ktson/OfficialOutputTestRunner.kt` | runs the suite's `output-tests/*/content` against `Basic` output |
| Modify `docs/SPEC_OUTPUT_FORMATS.md`, `README.md`, `CLAUDE.md`, `CHANGELOG.md` | status, usage, limitation, test inventory, changelog |

Reference facts (verified against the current code):

- Native error tree for schema `{"properties":{"name":{"type":"string"}},"anyOf":[{"required":["a"]},{"required":["b"]}]}` and instance `{"name":1}`, identical in both drafts (`instanceLocation`, `schemaPath`, children indented):
  ```
  [/name] [/properties/name/type]
  []      [/anyOf]
    []    [/anyOf/0]
      []  [/anyOf/0/required]
    []    [/anyOf/1]
      []  [/anyOf/1/required]
  ```
- Schema `{"allOf":[{"anyOf":[{"type":"string"}]}]}`, instance `1`: one top-level error `/allOf/0` → `/allOf/0/anyOf` → `/allOf/0/anyOf/0` → `/allOf/0/anyOf/0/type`, all at instance location `""`.
- Schema `{"enum":["a\"b"]}`, instance `1`: one error at `/enum`, message `Value 1 is not one of: ["a\"b"]`.
- Kotest specs in this repo are `class X : DescribeSpec({ ... })`; `SchemaVersion.entries` lists both drafts; `JsonSchema.fromString(json, version)` builds a schema.

---

### Task 1: Output types, tokens and the Flag format

**Files:**
- Create: `src/main/kotlin/org/ktson/OutputFormat.kt`
- Modify: `src/main/kotlin/org/ktson/JsonValidator.kt` (insert after line 99)
- Test: `src/test/kotlin/org/ktson/OutputFormatTest.kt`

**Interfaces:**
- Consumes: `ValidationResult` (`Valid`, `Invalid(validationErrors)`, `isValid`), `ValidationError` (`instanceLocation`, `message`, `schemaPath: String?`, `causes`).
- Produces:
  - `sealed interface ValidationOutput { val valid: Boolean; fun toJson(): String }`
  - `data class FlagOutput(override val valid: Boolean)`
  - `sealed class OutputFormat<T : ValidationOutput>` with `internal abstract fun from(result: ValidationResult): T` and `data object Flag : OutputFormat<FlagOutput>()`
  - `fun <T : ValidationOutput> ValidationResult.toOutput(format: OutputFormat<T>): T`
  - `fun <T : ValidationOutput> JsonValidator.validate(instance: JsonElement, schema: JsonSchema, format: OutputFormat<T>): T` (a member)
  - `private val outputJson: Json` in `OutputFormat.kt`, used by every `toJson()`
  - Private test helpers in `OutputFormatTest.kt` (later tasks add tests to the same file): `normalized(json: String): JsonElement`, constants `TWO_BRANCH_SCHEMA`, `TWO_BRANCH_INSTANCE`, `NESTED_SCHEMA`, `QUOTED_ENUM_SCHEMA`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/org/ktson/OutputFormatTest.kt`:

```kotlin
package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

/** A failing `properties` member alongside an `anyOf` whose two branches fail. */
private const val TWO_BRANCH_SCHEMA = """{"properties":{"name":{"type":"string"}},"anyOf":[{"required":["a"]},{"required":["b"]}]}"""
private const val TWO_BRANCH_INSTANCE = """{"name":1}"""

/** An `anyOf` nested in an `allOf`: one top-level error, four levels deep. */
private const val NESTED_SCHEMA = """{"allOf":[{"anyOf":[{"type":"string"}]}]}"""

/** Its error message contains a double quote and a backslash. */
private const val QUOTED_ENUM_SCHEMA = """{"enum":["a\"b"]}"""

/**
 * Parses [json] and replaces every `error` value with "*", so structural assertions do not freeze
 * KtSON's message wording, which the output spec deliberately leaves unstandardised.
 */
private fun normalized(json: String): JsonElement = mask(Json.parseToJsonElement(json))

private fun mask(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (key == "error") JsonPrimitive("*") else mask(value) })
    is JsonArray -> JsonArray(element.map { mask(it) })
    else -> element
}

class OutputFormatTest :
    DescribeSpec({
        val validator = JsonValidator()

        SchemaVersion.entries.forEach { version ->
            fun schema(json: String) = JsonSchema.fromString(json, version)
            fun instance(json: String) = Json.parseToJsonElement(json)

            describe("$version Flag") {
                it("is exactly {\"valid\":false} for an invalid result with several errors") {
                    val output: FlagOutput = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Flag)
                    Json.parseToJsonElement(output.toJson()) shouldBe Json.parseToJsonElement("""{"valid":false}""")
                }

                it("is exactly {\"valid\":true} for a valid result") {
                    val output: FlagOutput = validator.validate(instance("""{"name":"x","a":1}"""), schema(TWO_BRANCH_SCHEMA), OutputFormat.Flag)
                    Json.parseToJsonElement(output.toJson()) shouldBe Json.parseToJsonElement("""{"valid":true}""")
                }

                it("matches toOutput on the native result") {
                    val native = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA))
                    validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Flag) shouldBe native.toOutput(OutputFormat.Flag)
                }

                it("round-trips through kotlinx-serialization") {
                    val output = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Flag)
                    Json.decodeFromString(FlagOutput.serializer(), output.toJson()) shouldBe output
                }
            }
        }

        describe("toJson") {
            it("contains only the valid key, with no type discriminator") {
                val json = FlagOutput(valid = false).toJson()
                (Json.parseToJsonElement(json) as JsonObject).keys shouldBe setOf("valid")
            }
        }
    })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ktson.OutputFormatTest"`
Expected: FAIL at compilation — `Unresolved reference: FlagOutput` / `OutputFormat`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/org/ktson/OutputFormat.kt`:

```kotlin
package org.ktson

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Standard JSON Schema output formats (JSON Schema Core 2020-12 §12, same shape as 2019-09 §10.4).
 *
 * Everything about the standard output lives in this file, so that a later revision of the output
 * section (the next draft reworks it) is a local change. See docs/SPEC_OUTPUT_FORMATS.md.
 *
 * Known limitation: absoluteKeywordLocation is never produced. 2020-12 §12.3.3 allows omitting it
 * only when evaluation did not pass through a reference or the schema has no absolute $id, so
 * output for errors reached through $ref in such schemas is not fully compliant.
 */

/**
 * Serialises the concrete output types independently of any caller's Json. With encodeDefaults off,
 * a property equal to its default (null) is omitted. explicitNulls is not used: it is experimental.
 */
private val outputJson = Json { encodeDefaults = false }

/**
 * Standard validation output in one of the formats selected by [OutputFormat].
 *
 * Serialise with [toJson]. Do not encode through this interface polymorphically: that would add a
 * type discriminator, which is not part of the standard output.
 */
sealed interface ValidationOutput {
    val valid: Boolean

    /** Standard JSON for this format. */
    fun toJson(): String
}

/** The `flag` format: only whether validation passed. */
@Serializable
data class FlagOutput(override val valid: Boolean) : ValidationOutput {
    override fun toJson(): String = outputJson.encodeToString(serializer(), this)
}

/**
 * Selects a standard output format; the type parameter is the output type it produces.
 *
 * `Verbose` is not supported yet. Adding it later adds a subclass here and to [ValidationOutput],
 * which breaks exhaustive `when` expressions over either.
 */
sealed class OutputFormat<T : ValidationOutput> {
    internal abstract fun from(result: ValidationResult): T

    /** `{"valid": true|false}` and nothing else. */
    data object Flag : OutputFormat<FlagOutput>() {
        override fun from(result: ValidationResult): FlagOutput = FlagOutput(result.isValid)
    }
}

/** Maps this native result to the standard output [format]. */
fun <T : ValidationOutput> ValidationResult.toOutput(format: OutputFormat<T>): T = format.from(this)
```

In `src/main/kotlin/org/ktson/JsonValidator.kt`, directly after
`fun validate(instance: JsonElement, schema: JsonSchema): ValidationResult = validateInternal(instance, schema, "")`, add:

```kotlin

    /**
     * Validates a JSON instance against a JSON schema and returns standard output in [format],
     * for example `validate(instance, schema, OutputFormat.Basic)`.
     */
    fun <T : ValidationOutput> validate(instance: JsonElement, schema: JsonSchema, format: OutputFormat<T>): T = validate(instance, schema).toOutput(format)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ktson.OutputFormatTest"`
Expected: PASS (9 tests: 4 per draft + 1).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/org/ktson/OutputFormat.kt src/main/kotlin/org/ktson/JsonValidator.kt src/test/kotlin/org/ktson/OutputFormatTest.kt
git commit -m "feat(output): add typed standard output formats, starting with Flag"
```

---

### Task 2: The Basic format

**Files:**
- Modify: `src/main/kotlin/org/ktson/OutputFormat.kt`
- Test: `src/test/kotlin/org/ktson/OutputFormatTest.kt`

**Interfaces:**
- Consumes: Task 1's `ValidationOutput`, `outputJson`, `OutputFormat` (with `internal abstract fun from`), test helpers `normalized`, `TWO_BRANCH_*`, `NESTED_SCHEMA`, `QUOTED_ENUM_SCHEMA`.
- Produces:
  - `data class BasicOutput(valid: Boolean, keywordLocation: String, instanceLocation: String, errors: List<BasicUnit>? = null)`
  - `data class BasicUnit(valid: Boolean, keywordLocation: String, instanceLocation: String, error: String)`
  - `data object OutputFormat.Basic : OutputFormat<BasicOutput>()`
  - `private fun ValidationError.keywordLocationOrRoot(): String` (= `schemaPath ?: ""`), reused by Task 3

- [ ] **Step 1: Write the failing tests**

In `OutputFormatTest.kt`, inside `SchemaVersion.entries.forEach { version -> ... }` after the Flag `describe`, add:

```kotlin
            describe("$version Basic") {
                it("flattens the error tree, parents before causes, under a root with empty locations") {
                    val output: BasicOutput = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Basic)
                    normalized(output.toJson()) shouldBe normalized(
                        """
                        {"valid":false,"keywordLocation":"","instanceLocation":"","errors":[
                          {"valid":false,"keywordLocation":"/properties/name/type","instanceLocation":"/name","error":"*"},
                          {"valid":false,"keywordLocation":"/anyOf","instanceLocation":"","error":"*"},
                          {"valid":false,"keywordLocation":"/anyOf/0","instanceLocation":"","error":"*"},
                          {"valid":false,"keywordLocation":"/anyOf/0/required","instanceLocation":"","error":"*"},
                          {"valid":false,"keywordLocation":"/anyOf/1","instanceLocation":"","error":"*"},
                          {"valid":false,"keywordLocation":"/anyOf/1/required","instanceLocation":"","error":"*"}
                        ]}
                        """,
                    )
                }

                it("lists parents before children at every nesting depth") {
                    val output = validator.validate(instance("1"), schema(NESTED_SCHEMA), OutputFormat.Basic)
                    output.errors!!.map { it.keywordLocation } shouldBe listOf("/allOf/0", "/allOf/0/anyOf", "/allOf/0/anyOf/0", "/allOf/0/anyOf/0/type")
                }

                it("takes every error message from the native result, in the same order") {
                    val native = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA))
                    fun flatten(errors: List<ValidationError>): List<String> = errors.flatMap { listOf(it.message) + flatten(it.causes) }
                    native.toOutput(OutputFormat.Basic).errors!!.map { it.error } shouldBe flatten(native.getErrors())
                }

                it("is the root alone for a valid result") {
                    val output = validator.validate(instance("""{"name":"x","a":1}"""), schema(TWO_BRANCH_SCHEMA), OutputFormat.Basic)
                    Json.parseToJsonElement(output.toJson()) shouldBe Json.parseToJsonElement("""{"valid":true,"keywordLocation":"","instanceLocation":""}""")
                }

                it("escapes ~ and / in both locations") {
                    val output = validator.validate(instance("""{"~a/b":"x"}"""), schema("""{"properties":{"~a/b":{"type":"number"}}}"""), OutputFormat.Basic)
                    output.errors!!.single().keywordLocation shouldBe "/properties/~0a~1b/type"
                    output.errors!!.single().instanceLocation shouldBe "/~0a~1b"
                }

                it("round-trips a message containing a quote and a backslash") {
                    val output = validator.validate(instance("1"), schema(QUOTED_ENUM_SCHEMA), OutputFormat.Basic)
                    output.errors!!.single().error shouldBe validator.validate(instance("1"), schema(QUOTED_ENUM_SCHEMA)).getErrors().single().message
                    Json.decodeFromString(BasicOutput.serializer(), output.toJson()) shouldBe output
                }

                it("matches toOutput on the native result") {
                    val native = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA))
                    validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Basic) shouldBe native.toOutput(OutputFormat.Basic)
                }
            }
```

And inside the top-level `describe("toJson") { ... }`, add:

```kotlin
            it("maps a hand-built error without a schemaPath to the root keyword location") {
                val result = ValidationResult.Invalid(listOf(ValidationError(instanceLocation = "/x", message = "custom")))
                result.toOutput(OutputFormat.Basic).errors!!.single().keywordLocation shouldBe ""
            }

            it("has no type discriminator in Basic output") {
                val json = ValidationResult.Valid.toOutput(OutputFormat.Basic).toJson()
                (Json.parseToJsonElement(json) as JsonObject).containsKey("type") shouldBe false
            }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ktson.OutputFormatTest"`
Expected: FAIL at compilation — `Unresolved reference: BasicOutput` / `Basic`.

- [ ] **Step 3: Write the implementation**

In `OutputFormat.kt`, after `FlagOutput`, add:

```kotlin
/** The `basic` format: a root unit with every failing unit in one flat list. */
@Serializable
data class BasicOutput(
    override val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    /** Every failing unit, each parent before its causes; null when valid. */
    val errors: List<BasicUnit>? = null,
) : ValidationOutput {
    override fun toJson(): String = outputJson.encodeToString(serializer(), this)
}

/** One failing unit in [BasicOutput.errors]; never nests. */
@Serializable
data class BasicUnit(
    val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val error: String,
)
```

Inside `sealed class OutputFormat`, after `Flag`, add:

```kotlin

    /** The root unit plus every failing unit, flattened depth-first. */
    data object Basic : OutputFormat<BasicOutput>() {
        override fun from(result: ValidationResult): BasicOutput = when (result) {
            is ValidationResult.Valid -> BasicOutput(valid = true, keywordLocation = "", instanceLocation = "")
            is ValidationResult.Invalid ->
                BasicOutput(valid = false, keywordLocation = "", instanceLocation = "", errors = result.validationErrors.flatMap { it.toBasicUnits() })
        }
    }
```

At the end of the file, add:

```kotlin

/** The validator always sets schemaPath; only a hand-built error can lack one, and maps to the root. */
private fun ValidationError.keywordLocationOrRoot(): String = schemaPath ?: ""

private fun ValidationError.toBasicUnits(): List<BasicUnit> =
    listOf(BasicUnit(valid = false, keywordLocation = keywordLocationOrRoot(), instanceLocation = instanceLocation, error = message)) +
        causes.flatMap { it.toBasicUnits() }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ktson.OutputFormatTest"`
Expected: PASS (25 tests: 11 per draft + 3).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/org/ktson/OutputFormat.kt src/test/kotlin/org/ktson/OutputFormatTest.kt
git commit -m "feat(output): add the Basic output format"
```

---

### Task 3: The Detailed format

**Files:**
- Modify: `src/main/kotlin/org/ktson/OutputFormat.kt`
- Test: `src/test/kotlin/org/ktson/OutputFormatTest.kt`

**Interfaces:**
- Consumes: Task 1's `ValidationOutput`, `outputJson`, `OutputFormat`; Task 2's `keywordLocationOrRoot()`; test helpers.
- Produces:
  - `data class DetailedOutput(valid: Boolean, keywordLocation: String, instanceLocation: String, error: String? = null, errors: List<DetailedOutput>? = null)`
  - `data object OutputFormat.Detailed : OutputFormat<DetailedOutput>()`

- [ ] **Step 1: Write the failing tests**

In `OutputFormatTest.kt`, inside `SchemaVersion.entries.forEach { version -> ... }` after the Basic `describe`, add:

```kotlin
            describe("$version Detailed") {
                it("keeps the causes hierarchy under a root with empty locations") {
                    val output: DetailedOutput = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Detailed)
                    normalized(output.toJson()) shouldBe normalized(
                        """
                        {"valid":false,"keywordLocation":"","instanceLocation":"","errors":[
                          {"valid":false,"keywordLocation":"/properties/name/type","instanceLocation":"/name","error":"*"},
                          {"valid":false,"keywordLocation":"/anyOf","instanceLocation":"","error":"*","errors":[
                            {"valid":false,"keywordLocation":"/anyOf/0","instanceLocation":"","error":"*","errors":[
                              {"valid":false,"keywordLocation":"/anyOf/0/required","instanceLocation":"","error":"*"}
                            ]},
                            {"valid":false,"keywordLocation":"/anyOf/1","instanceLocation":"","error":"*","errors":[
                              {"valid":false,"keywordLocation":"/anyOf/1/required","instanceLocation":"","error":"*"}
                            ]}
                          ]}
                        ]}
                        """,
                    )
                }

                it("collapses a root with a single child into that child, keeping every inner level") {
                    val output = validator.validate(instance("1"), schema(NESTED_SCHEMA), OutputFormat.Detailed)
                    normalized(output.toJson()) shouldBe normalized(
                        """
                        {"valid":false,"keywordLocation":"/allOf/0","instanceLocation":"","error":"*","errors":[
                          {"valid":false,"keywordLocation":"/allOf/0/anyOf","instanceLocation":"","error":"*","errors":[
                            {"valid":false,"keywordLocation":"/allOf/0/anyOf/0","instanceLocation":"","error":"*","errors":[
                              {"valid":false,"keywordLocation":"/allOf/0/anyOf/0/type","instanceLocation":"","error":"*"}
                            ]}
                          ]}
                        ]}
                        """,
                    )
                }

                it("is the root alone for a valid result") {
                    val output = validator.validate(instance("""{"name":"x","a":1}"""), schema(TWO_BRANCH_SCHEMA), OutputFormat.Detailed)
                    Json.parseToJsonElement(output.toJson()) shouldBe Json.parseToJsonElement("""{"valid":true,"keywordLocation":"","instanceLocation":""}""")
                }

                it("round-trips a message containing a quote and a backslash") {
                    val output = validator.validate(instance("1"), schema(QUOTED_ENUM_SCHEMA), OutputFormat.Detailed)
                    output.error shouldBe validator.validate(instance("1"), schema(QUOTED_ENUM_SCHEMA)).getErrors().single().message
                    Json.decodeFromString(DetailedOutput.serializer(), output.toJson()) shouldBe output
                }

                it("matches toOutput on the native result") {
                    val native = validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA))
                    validator.validate(instance(TWO_BRANCH_INSTANCE), schema(TWO_BRANCH_SCHEMA), OutputFormat.Detailed) shouldBe native.toOutput(OutputFormat.Detailed)
                }
            }
```

And inside the top-level `describe("toJson") { ... }`, add:

```kotlin
            it("still produces output for a hand-built Invalid result with no errors") {
                val empty = ValidationResult.Invalid(emptyList())
                Json.parseToJsonElement(empty.toOutput(OutputFormat.Flag).toJson()) shouldBe Json.parseToJsonElement("""{"valid":false}""")
                Json.parseToJsonElement(empty.toOutput(OutputFormat.Basic).toJson()) shouldBe
                    Json.parseToJsonElement("""{"valid":false,"keywordLocation":"","instanceLocation":"","errors":[]}""")
                Json.parseToJsonElement(empty.toOutput(OutputFormat.Detailed).toJson()) shouldBe
                    Json.parseToJsonElement("""{"valid":false,"keywordLocation":"","instanceLocation":"","errors":[]}""")
            }

            it("has no type discriminator in Detailed output") {
                val json = ValidationResult.Valid.toOutput(OutputFormat.Detailed).toJson()
                (Json.parseToJsonElement(json) as JsonObject).containsKey("type") shouldBe false
            }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ktson.OutputFormatTest"`
Expected: FAIL at compilation — `Unresolved reference: DetailedOutput` / `Detailed`.

- [ ] **Step 3: Write the implementation**

In `OutputFormat.kt`, after `BasicUnit`, add:

```kotlin
/** The `detailed` format: a node of the failure tree. The root has no [error]; leaves have no [errors]. */
@Serializable
data class DetailedOutput(
    override val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val error: String? = null,
    val errors: List<DetailedOutput>? = null,
) : ValidationOutput {
    override fun toJson(): String = outputJson.encodeToString(serializer(), this)
}
```

Inside `sealed class OutputFormat`, after `Basic`, add:

```kotlin

    /**
     * The failure hierarchy. A root with exactly one child collapses into that child; inner nodes
     * never collapse, since each carries its own keyword location and message.
     */
    data object Detailed : OutputFormat<DetailedOutput>() {
        override fun from(result: ValidationResult): DetailedOutput = when (result) {
            is ValidationResult.Valid -> DetailedOutput(valid = true, keywordLocation = "", instanceLocation = "")
            is ValidationResult.Invalid -> {
                val children = result.validationErrors.map { it.toDetailed() }
                children.singleOrNull() ?: DetailedOutput(valid = false, keywordLocation = "", instanceLocation = "", errors = children)
            }
        }
    }
```

At the end of the file, add:

```kotlin

private fun ValidationError.toDetailed(): DetailedOutput = DetailedOutput(
    valid = false,
    keywordLocation = keywordLocationOrRoot(),
    instanceLocation = instanceLocation,
    error = message,
    errors = causes.takeIf { it.isNotEmpty() }?.map { it.toDetailed() },
)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ktson.OutputFormatTest"`
Expected: PASS (37 tests: 16 per draft + 5).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/org/ktson/OutputFormat.kt src/test/kotlin/org/ktson/OutputFormatTest.kt
git commit -m "feat(output): add the Detailed output format"
```

---

### Task 4: Run the official output-tests

**Files:**
- Create: `src/test/kotlin/org/ktson/OfficialOutputTestRunner.kt`

**Interfaces:**
- Consumes: `JsonValidator(formatAssertion: Boolean, schemaLoader: ((String) -> JsonElement?)?)`, `validate(instance, schema, OutputFormat.Basic)`, `BasicOutput.toJson()`, `JsonSchema.fromElement(element, version)`.
- Produces: nothing used by later tasks.

The suite's `output-tests/<draft>/content/*.json` files look like the validation tests, but each test has
`output.basic`: a schema the `basic` output must satisfy. That schema `$ref`s
`/draft/<draft>/output/schema`, which resolves against the test's `$id` to
`https://json-schema.org/draft/<draft>/output/schema` — the `$id` of `output-tests/<draft>/output-schema.json`
(verified for both drafts). Bundled meta-schemas live under `src/test/resources/schemas/`, as
`OfficialTestSuiteRunner` uses them.

- [ ] **Step 1: Write the runner**

Create `src/test/kotlin/org/ktson/OfficialOutputTestRunner.kt`:

```kotlin
package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File

/**
 * Runs the official JSON Schema Test Suite output-tests against KtSON's Basic output.
 * https://github.com/json-schema-org/JSON-Schema-Test-Suite/tree/main/output-tests
 *
 * readOnly.json is skipped: it needs annotations, which KtSON does not produce.
 * output-tests/v1 targets a later release than KtSON supports and is not run.
 */
class OfficialOutputTestRunner :
    DescribeSpec({
        val outputTestsBase = File("../JSON-Schema-Test-Suite/output-tests")
        val filesToSkip = setOf("readOnly.json")
        val drafts = listOf("draft2019-09" to SchemaVersion.DRAFT_2019_09, "draft2020-12" to SchemaVersion.DRAFT_2020_12)

        if (outputTestsBase.exists()) {
            drafts.forEach { (draftName, version) ->
                describe("Official Output Tests: $draftName") {
                    val draftDir = File(outputTestsBase, draftName)
                    val outputSchema = Json.parseToJsonElement(File(draftDir, "output-schema.json").readText())
                    val outputSchemaId = outputSchema.jsonObject["\$id"]!!.jsonPrimitive.content
                    val outputChecker = JsonValidator(formatAssertion = true, schemaLoader = outputSchemaLoader(outputSchemaId, outputSchema))
                    val validator = JsonValidator()

                    File(draftDir, "content").listFiles { file -> file.extension == "json" && file.name !in filesToSkip }!!
                        .sortedBy { it.name }
                        .forEach { file ->
                            describe(file.nameWithoutExtension) {
                                Json.parseToJsonElement(file.readText()).jsonArray.forEach { caseElement ->
                                    val case = caseElement.jsonObject
                                    val schema = JsonSchema.fromElement(case["schema"]!!, version)
                                    describe(case["description"]!!.jsonPrimitive.content) {
                                        case["tests"]!!.jsonArray.forEach { testElement ->
                                            val test = testElement.jsonObject
                                            it(test["description"]!!.jsonPrimitive.content) {
                                                val output = validator.validate(test["data"]!!, schema, OutputFormat.Basic)
                                                val basicSchema = JsonSchema.fromElement(test["output"]!!.jsonObject["basic"]!!, version)
                                                val check = outputChecker.validate(Json.parseToJsonElement(output.toJson()), basicSchema)
                                                if (check.isInvalid) {
                                                    println("\n=== FAILED OUTPUT TEST ===\nFile: ${file.name}\nOutput: ${output.toJson()}\n$check\n")
                                                }
                                                check.isValid shouldBe true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        } else {
            describe("Output Tests Not Found") {
                it("should have output tests at ${outputTestsBase.absolutePath}") {
                    val message = "JSON Schema Test Suite output-tests not found at ${outputTestsBase.absolutePath}; " +
                        "clone https://github.com/json-schema-org/JSON-Schema-Test-Suite.git next to this project"
                    // On CI a missing suite means the output tests silently did not run, so fail loudly.
                    check(System.getenv("CI") == null) { message }
                    println("WARNING: $message")
                }
            }
        }
    })

/** Serves the draft's output schema by its $id, and meta-schemas from the bundled test resources. */
private fun outputSchemaLoader(outputSchemaId: String, outputSchema: JsonElement): (String) -> JsonElement? = { uri ->
    when {
        uri.removeSuffix("#") == outputSchemaId -> outputSchema
        uri.startsWith("https://json-schema.org/") -> {
            val resourcePath = "/schemas/" + uri.removePrefix("https://json-schema.org/").removeSuffix("#") + ".json"
            OfficialOutputTestRunner::class.java.getResourceAsStream(resourcePath)
                ?.use { Json.parseToJsonElement(it.bufferedReader().readText()) }
        }
        else -> null
    }
}
```

- [ ] **Step 2: Run the runner**

Run: `./gradlew test --tests "org.ktson.OfficialOutputTestRunner"`
Expected: PASS, 6 tests (type, escape, general × 2 drafts).

If a test fails, read the printed `=== FAILED OUTPUT TEST ===` block. A failure means either the
output shape is wrong (fix `OutputFormat.kt`, add a unit test in `OutputFormatTest.kt` pinning it)
or the output schema `$ref` did not resolve (the check result names `$ref`; confirm the loader is
asked for `outputSchemaId`). Do not edit or skip suite files to make a test pass.

- [ ] **Step 3: Prove the runner can fail**

Temporarily change `OutputFormat.Basic`'s `keywordLocation = keywordLocationOrRoot()` in
`toBasicUnits()` to `keywordLocation = ""`, run
`./gradlew test --tests "org.ktson.OfficialOutputTestRunner"`, and confirm the `type` and `escape`
tests FAIL. Revert the change and confirm all 6 PASS again.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat
git add src/test/kotlin/org/ktson/OfficialOutputTestRunner.kt
git commit -m "test(output): run the official output-tests against Basic output"
```

---

### Task 5: Documentation

**Files:**
- Modify: `docs/SPEC_OUTPUT_FORMATS.md`, `README.md`, `CLAUDE.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: everything above; the test counts from the run in Step 1.
- Produces: nothing.

- [ ] **Step 1: Run the whole build and collect counts**

Run: `./gradlew build --rerun-tasks`
Expected: BUILD SUCCESSFUL.

Then count the new tests from the XML reports:

```bash
for c in OutputFormatTest OfficialOutputTestRunner; do
  grep -ho 'tests="[0-9]*"' build/test-results/test/TEST-org.ktson.$c.xml | head -1
done
grep -h '<testsuite ' build/test-results/test/*.xml | sed -E 's/.* tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: 37, 6, and the new standard-run total (5,024 before this work + 43 = 5,067). Use the
numbers actually printed.

- [ ] **Step 2: Update the spec status**

In `docs/SPEC_OUTPUT_FORMATS.md`, replace the two status lines with:

```markdown
**Status**: `Flag`, `Basic` and `Detailed` implemented, unreleased; `Verbose`, annotations and
`absoluteKeywordLocation` deferred (see [Deferred](#deferred))
```

- [ ] **Step 3: Document usage in the README**

In `README.md`, add a subsection at the end of `### Basic Usage`, directly before the
`## Available Scripts` heading:

````markdown
### Standard output formats

`validate(instance, schema)` returns KtSON's own `ValidationResult`. To get the standard
JSON Schema output (2020-12 §12) instead, pass a format; the return type follows it:

```kotlin
val flag: FlagOutput = validator.validate(instance, schema, OutputFormat.Flag)
val basic: BasicOutput = validator.validate(instance, schema, OutputFormat.Basic)
val detailed: DetailedOutput = validator.validate(instance, schema, OutputFormat.Detailed)

println(basic.toJson())
// {"valid":false,"keywordLocation":"","instanceLocation":"","errors":[{"valid":false,
//  "keywordLocation":"/properties/name/type","instanceLocation":"/name","error":"..."}]}
```

- `Flag` is `{"valid": …}` only; `Basic` lists every failing unit flat; `Detailed` keeps the
  `allOf`/`anyOf`/`oneOf` hierarchy. `result.toOutput(format)` converts a result you already have.
- Serialise with `toJson()`. Encoding through the `ValidationOutput` interface would add a type
  discriminator that is not part of the standard.
- `error` texts are KtSON's messages, which the standard does not fix; do not match on them.
- `Verbose` is not supported yet.
````

In the README's `### Known Limitations` list, after item `2. **Format Validation**` and its
sub-bullets, add:

```markdown
3. **Output Formats**
   - `absoluteKeywordLocation` is never produced. JSON Schema 2020-12 §12.3.3 allows omitting it
     only when evaluation did not pass through `$ref` or the schema has no absolute `$id`, so output
     for errors reached through a reference in such schemas is not fully compliant.
   - No `Verbose` format and no annotations in output.
```

- [ ] **Step 4: Update CLAUDE.md**

In `CLAUDE.md`:
- Under "### Core Components", after the `SchemaVersion` item, add:
  ```markdown
  8. **OutputFormat** (`OutputFormat.kt`) - Standard JSON Schema output (2020-12 §12)
     - `FlagOutput`, `BasicOutput`/`BasicUnit`, `DetailedOutput`, selected by the `OutputFormat<T>` token
     - `validate(instance, schema, format)` / `result.toOutput(format)`; a pure mapping over `ValidationResult`
     - Serialise with `toJson()`, never through the `ValidationOutput` interface
  ```
- Under "### Test Structure", after the `InstanceLocationTest.kt` line, add (with the counts from Step 1):
  ```markdown
  - **OutputFormatTest.kt** - 37 tests for the Flag, Basic and Detailed output formats
  - **OfficialOutputTestRunner.kt** - Runs the suite's `output-tests` content files against Basic output (skips `readOnly.json`, which needs annotations)
  ```
- In "### Documentation", change the `SPEC_OUTPUT_FORMATS.md` line to:
  ```markdown
  - `docs/SPEC_OUTPUT_FORMATS.md` - Flag/Basic/Detailed implemented, unreleased; Verbose and absoluteKeywordLocation deferred
  ```

- [ ] **Step 5: Update the CHANGELOG and README test counts**

In `CHANGELOG.md`, under `## [Unreleased]`, add:

```markdown
### Added
- Standard JSON Schema output formats (2020-12 §12): `validate(instance, schema, format)` and
  `ValidationResult.toOutput(format)` return `FlagOutput`, `BasicOutput` or `DetailedOutput`,
  typed by the `OutputFormat` token; `toJson()` gives the standard JSON. `validate(instance, schema)`
  still returns `ValidationResult`. `Verbose` and `absoluteKeywordLocation` are not supported yet.
- The suite's `output-tests` run against Basic output for both drafts (`readOnly` skipped).
```

In `README.md`, under `### Test Coverage`, change `**Custom Tests**: 710 tests` to 710 plus the
`OutputFormatTest` count from Step 1 (expected 747), and add a sub-bullet
`  - Output formats: 37 tests` (the actual count). The 6 output-tests are official suite tests, not
custom ones; add `- **Official output-tests**: 6 (Basic format, both drafts)` after the official
test suite lines in the same section.

- [ ] **Step 6: Verify and commit**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL.

```bash
git add docs/SPEC_OUTPUT_FORMATS.md README.md CLAUDE.md CHANGELOG.md
git commit -m "docs: document the standard output formats"
```
