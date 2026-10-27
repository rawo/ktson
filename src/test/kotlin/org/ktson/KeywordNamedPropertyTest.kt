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

        SchemaVersion.entries.forEach { version ->
            describe("$version data keywords hold data, not schemas (#36)") {
                it("an \$anchor inside a const value does not capture a \$ref") {
                    val schema = """{"${'$'}defs":{"unused":{"const":{"${'$'}anchor":"real","type":"string"}},"actual":{"${'$'}anchor":"real","type":"integer"}},"${'$'}ref":"#real"}"""
                    isValid(schema, "1", version) shouldBe true
                    isValid(schema, "\"s\"", version) shouldBe false
                }

                it("an \$id inside a default value does not capture a \$ref") {
                    val schema = """{"${'$'}id":"https://ex.com/root","${'$'}defs":{"d":{"default":{"${'$'}id":"x.json","type":"string"}},"x":{"${'$'}id":"x.json","type":"integer"}},"${'$'}ref":"x.json"}"""
                    isValid(schema, "1", version) shouldBe true
                    isValid(schema, "\"s\"", version) shouldBe false
                }

                it("anchors in enum and examples values are ignored too") {
                    val schema = """{"${'$'}defs":{"e":{"enum":[{"${'$'}anchor":"a","type":"string"}],"examples":[{"${'$'}anchor":"a","type":"string"}]},"real":{"${'$'}anchor":"a","type":"integer"}},"${'$'}ref":"#a"}"""
                    isValid(schema, "1", version) shouldBe true
                }

                it("a property named default or const is still a schema") {
                    val schema = """{"properties":{"default":{"${'$'}anchor":"d","type":"integer"},"const":{"${'$'}anchor":"c","type":"string"}},"allOf":[{"${'$'}ref":"#d"},{"not":{"${'$'}ref":"#c"}}]}"""
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
