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
        }

        describe("toJson") {
            it("contains only the valid key, with no type discriminator") {
                val json = FlagOutput(valid = false).toJson()
                (Json.parseToJsonElement(json) as JsonObject).keys shouldBe setOf("valid")
            }

            it("maps a hand-built error without a schemaPath to the root keyword location") {
                val result = ValidationResult.Invalid(listOf(ValidationError(instanceLocation = "/x", message = "custom")))
                result.toOutput(OutputFormat.Basic).errors!!.single().keywordLocation shouldBe ""
            }

            it("has no type discriminator in Basic output") {
                val json = ValidationResult.Valid.toOutput(OutputFormat.Basic).toJson()
                (Json.parseToJsonElement(json) as JsonObject).containsKey("type") shouldBe false
            }

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
        }
    })
