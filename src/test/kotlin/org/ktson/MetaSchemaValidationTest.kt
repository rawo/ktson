package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * validateSchema checks a schema against the official meta-schema of its draft (#38): what the
 * meta-schema rejects is invalid, wherever it is in the schema, and the error points at the offending
 * keyword. Deep schemas are checked without depending on the caller's thread stack.
 */
class MetaSchemaValidationTest :
    DescribeSpec({
        val validator = JsonValidator()

        fun check(schemaJson: String, version: SchemaVersion = SchemaVersion.DRAFT_2020_12) = validator.validateSchema(JsonSchema.fromString(schemaJson, version))

        fun locations(result: ValidationResult): List<String> {
            fun flatten(errors: List<ValidationError>): List<ValidationError> = errors.flatMap { listOf(it) + flatten(it.causes) }
            return flatten(result.getErrors()).map { it.instanceLocation }
        }

        SchemaVersion.entries.forEach { version ->
            describe("$version") {
                listOf(
                    "\"text\"",
                    """{"required":1}""",
                    """{"minLength":-1}""",
                    """{"${'$'}defs":{"x":{"type":"bogus"}}}""",
                    """{"pattern":"["}""",
                    """{"unevaluatedProperties":1}""",
                    """{"properties":{"a":{"type":"bogus"}}}""",
                    """{"allOf":[]}""",
                    """{"enum":"not an array"}""",
                ).forEach { schema ->
                    it("rejects $schema") {
                        check(schema, version).isValid shouldBe false
                    }
                }

                listOf(
                    "true",
                    "false",
                    "{}",
                    """{"type":"object","properties":{"name":{"type":"string","minLength":1},"tags":{"type":"array","items":{"type":"string"},"uniqueItems":true}},"required":["name"]}""",
                    """{"${'$'}defs":{"pos":{"type":"integer","minimum":0}},"anyOf":[{"${'$'}ref":"#/${'$'}defs/pos"},{"type":"null"}],"pattern":"^[a-z]+${'$'}"}""",
                    """{"x-custom-keyword":{"anything":[1,2,3]}}""",
                ).forEach { schema ->
                    it("accepts $schema") {
                        check(schema, version).isValid shouldBe true
                    }
                }

                it("points at the offending keyword") {
                    locations(check("""{"minLength":-1}""", version)) shouldContain "/minLength"
                    locations(check("""{"properties":{"a/b":{"type":"bogus"}}}""", version)) shouldContain "/properties/a~1b/type"
                }
            }
        }

        describe("draft-specific keywords") {
            it("accepts \$recursiveAnchor in 2019-09 and \$dynamicAnchor in 2020-12") {
                check("""{"${'$'}recursiveAnchor":true}""", SchemaVersion.DRAFT_2019_09).isValid shouldBe true
                check("""{"${'$'}dynamicAnchor":"node"}""", SchemaVersion.DRAFT_2020_12).isValid shouldBe true
            }

            it("rejects a non-boolean \$recursiveAnchor in 2019-09") {
                check("""{"${'$'}recursiveAnchor":"yes"}""", SchemaVersion.DRAFT_2019_09).isValid shouldBe false
            }
        }

        describe("deep schemas") {
            fun nested(levels: Int): JsonElement {
                var element: JsonElement = JsonObject(emptyMap())
                repeat(levels) { element = buildJsonObject { put("not", element) } }
                return element
            }

            it("checks a schema 500 levels deep, whatever the caller's stack") {
                var result: ValidationResult? = null
                val smallStack = Thread(null, { result = validator.validateSchema(JsonSchema(nested(500))) }, "small-stack", 256L * 1024)
                smallStack.start()
                smallStack.join()
                result!!.isValid shouldBe true
            }

            it("reports a schema nested deeper than maxValidationDepth as a depth error, without overflowing") {
                val result = JsonValidator(maxValidationDepth = 100).validateSchema(JsonSchema(nested(10_000)))
                result.isValid shouldBe false
                result.getErrors().single().keyword shouldBe "depth"
            }
        }
    })
