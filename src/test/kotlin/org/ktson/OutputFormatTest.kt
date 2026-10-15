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
