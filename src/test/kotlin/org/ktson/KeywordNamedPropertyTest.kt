package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Property names that look like keywords ($id, $anchor, $dynamicAnchor) are just names: under
 * `properties` their value is a schema, not an identifier. Searches for identifiers and anchors walk
 * the whole schema, so they must ignore such values instead of failing on them (issue #7) or treating
 * the map holding them as a resource boundary.
 */
class KeywordNamedPropertyTest :
    DescribeSpec({
        fun isValid(schemaJson: String, instanceJson: String, version: SchemaVersion): Boolean = JsonValidator().validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson, version)).isValid

        SchemaVersion.entries.forEach { version ->
            describe("$version") {
                it("a property named \$id does not break a \$ref resolved by \$id") {
                    val schema = """{"${'$'}id":"https://ex.com/root","properties":{"${'$'}id":{"type":"string"}},"${'$'}defs":{"a":{"${'$'}id":"a.json","required":["${'$'}id"]}},"${'$'}ref":"a.json"}"""
                    isValid(schema, """{"${'$'}id":"x"}""", version) shouldBe true
                    isValid(schema, """{"${'$'}id":1}""", version) shouldBe false
                    isValid(schema, """{}""", version) shouldBe false
                }

                it("a property named \$anchor does not break a \$ref to an anchor") {
                    val schema = """{"properties":{"${'$'}anchor":{"type":"string"}},"${'$'}defs":{"f":{"${'$'}anchor":"foo","type":"object"}},"${'$'}ref":"#foo"}"""
                    isValid(schema, """{"${'$'}anchor":"x"}""", version) shouldBe true
                    isValid(schema, "1", version) shouldBe false
                }

                it("a property named \$id does not hide anchors declared next to it") {
                    val schema = """{"properties":{"${'$'}id":{"type":"string"},"x":{"${'$'}anchor":"xa","type":"integer"}},"${'$'}ref":"#xa"}"""
                    isValid(schema, "1", version) shouldBe true
                    isValid(schema, "\"s\"", version) shouldBe false
                }
            }
        }

        describe("DRAFT_2020_12 dynamic anchors") {
            it("a property named \$dynamicAnchor does not break a \$dynamicRef") {
                val schema = """{"properties":{"${'$'}dynamicAnchor":{"type":"string"}},"${'$'}defs":{"n":{"${'$'}dynamicAnchor":"node","type":"object"}},"${'$'}dynamicRef":"#node"}"""
                isValid(schema, """{"${'$'}dynamicAnchor":"x"}""", SchemaVersion.DRAFT_2020_12) shouldBe true
                isValid(schema, "1", SchemaVersion.DRAFT_2020_12) shouldBe false
            }
        }
    })
