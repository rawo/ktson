package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
    })
