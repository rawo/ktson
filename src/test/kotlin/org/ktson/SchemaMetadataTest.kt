package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * `$id` and `$schema` whose values are not strings are malformed schemas, not malformed JSON (#57):
 * JsonSchema must still be constructible so validateSchema can report them, and validate() must not throw.
 */
class SchemaMetadataTest :
    DescribeSpec({
        fun locations(errors: List<ValidationError>): List<String> = errors.flatMap { listOf(it.instanceLocation) + locations(it.causes) }

        listOf("\$id" to """{"${'$'}id":{}}""", "\$schema" to """{"${'$'}schema":[]}""", "\$id" to """{"${'$'}id":5}""").forEach { (keyword, text) ->
            describe(text) {
                it("can be wrapped as a JsonSchema, with no $keyword read from it") {
                    val schema = JsonSchema.fromString(text)
                    schema.id.shouldBeNull()
                    schema.schemaUri.shouldBeNull()
                    JsonSchema.extractId(schema.schema).shouldBeNull()
                }

                it("is reported by validateSchema at /$keyword") {
                    val result = JsonValidator().validateSchema(JsonSchema.fromString(text))
                    result.isValid shouldBe false
                    locations(result.getErrors()) shouldContain "/$keyword"
                }

                it("does not make validate() throw") {
                    JsonValidator().validate(JsonPrimitive(1), JsonSchema.fromString(text)).isValid shouldBe true
                }
            }
        }

        it("does not make validate() throw for a malformed \$id in a subschema") {
            JsonValidator().validate(JsonPrimitive(1), JsonSchema.fromString("""{"allOf":[{"${'$'}id":{},"type":"integer"}]}""")).isValid shouldBe true
        }

        // A non-string $id is ignored by validation, so it starts no resource: refs below it resolve in the enclosing
        // resource, for the verdict and for unevaluated* alike (#104)
        listOf("5", "{}", "null").forEach { id ->
            SchemaVersion.entries.forEach { version ->
                describe("a subschema with \$id $id in $version") {
                    fun isValid(schema: String, instance: String) = JsonValidator().validate(Json.parseToJsonElement(instance), JsonSchema.fromString(schema, version)).isValid

                    it("resolves a local \$ref below it in the enclosing resource") {
                        val schema = """{"${'$'}id":"https://ex.com/root","${'$'}defs":{"a":{"type":"integer"}},"properties":{"x":{"${'$'}id":$id,"${'$'}ref":"#/${'$'}defs/a"}}}"""
                        isValid(schema, """{"x":1}""") shouldBe true
                        isValid(schema, """{"x":"a"}""") shouldBe false
                    }

                    it("credits unevaluatedProperties with the enclosing resource's definition, not its own") {
                        val schema =
                            """{"${'$'}id":"https://ex.com/root","${'$'}defs":{"p":{"properties":{"a":true}}},""" +
                                """"allOf":[{"${'$'}id":$id,"${'$'}defs":{"p":{"properties":{"b":true}}},"${'$'}ref":"#/${'$'}defs/p"}],"unevaluatedProperties":false}"""
                        isValid(schema, """{"a":1}""") shouldBe true
                        isValid(schema, """{"b":1}""") shouldBe false
                    }
                }
            }
        }
    })
