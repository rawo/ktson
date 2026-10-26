package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * unevaluatedProperties / unevaluatedItems must see what a subschema reached through a URI-based $ref
 * evaluated, even when that subschema uses local refs (#/...) of its own resource (issue #6). Those
 * local refs must resolve in the target's resource, exactly as they do during validation.
 */
class UnevaluatedAcrossResourcesTest :
    DescribeSpec({
        fun isValid(schemaJson: String, instanceJson: String, version: SchemaVersion, loader: ((String) -> JsonElement?)? = null): Boolean = JsonValidator(schemaLoader = loader).validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson, version)).isValid

        SchemaVersion.entries.forEach { version ->
            val firstItem = if (version == SchemaVersion.DRAFT_2019_09) """"items":[true]""" else """"prefixItems":[true]"""

            describe("$version") {
                it("unevaluatedProperties sees properties evaluated behind a local ref in an embedded resource") {
                    val schema = """
                        {"${'$'}id":"https://ex.com/root",
                         "${'$'}defs":{"b":{"${'$'}id":"b.json","${'$'}defs":{"x":{"properties":{"foo":true}},"y":{"${'$'}ref":"#/${'$'}defs/x"}}}},
                         "${'$'}ref":"b.json#/${'$'}defs/y",
                         "unevaluatedProperties":false}
                    """
                    isValid(schema, """{"foo":1}""", version) shouldBe true
                    isValid(schema, """{"foo":1,"bar":2}""", version) shouldBe false
                }

                it("unevaluatedItems sees items evaluated behind a local ref in an embedded resource") {
                    val schema = """
                        {"${'$'}id":"https://ex.com/root",
                         "${'$'}defs":{"b":{"${'$'}id":"b.json","${'$'}defs":{"x":{$firstItem},"y":{"${'$'}ref":"#/${'$'}defs/x"}}}},
                         "${'$'}ref":"b.json#/${'$'}defs/y",
                         "unevaluatedItems":false}
                    """
                    isValid(schema, "[1]", version) shouldBe true
                    isValid(schema, "[1,2]", version) shouldBe false
                }

                it("unevaluatedProperties sees properties evaluated behind a local ref in a loaded document") {
                    val library = Json.parseToJsonElement("""{"${'$'}defs":{"x":{"properties":{"foo":true}},"y":{"${'$'}ref":"#/${'$'}defs/x"}}}""")
                    val loader: (String) -> JsonElement? = { uri -> if (uri == "https://ex.com/lib.json") library else null }
                    val schema = """{"${'$'}id":"https://ex.com/root","${'$'}ref":"lib.json#/${'$'}defs/y","unevaluatedProperties":false}"""
                    isValid(schema, """{"foo":1}""", version, loader) shouldBe true
                    isValid(schema, """{"bar":1}""", version, loader) shouldBe false
                }
            }
        }
    })
