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

                // An inline subschema with $id is a resource of its own: the branches of its applicators are
                // evaluated in it when collecting annotations too, as they are during validation (#90)
                describe("branches of an inline subschema with ${'$'}id") {
                    fun inline(branches: String, subDefs: String, rootDefs: String = "{}", keyword: String = "unevaluatedProperties") = """
                        {"${'$'}id":"https://ex.com/root",
                         "allOf":[{"${'$'}id":"sub",$branches,"${'$'}defs":$subDefs}],
                         "$keyword":false,
                         "${'$'}defs":$rootDefs}
                    """

                    it("count properties a local ref of the subschema evaluated") {
                        val schema = inline(""""anyOf":[{"${'$'}ref":"#/${'$'}defs/x"}]""", """{"x":{"properties":{"a":true}}}""")
                        isValid(schema, """{"a":1}""", version) shouldBe true
                        isValid(schema, """{"a":1,"z":2}""", version) shouldBe false
                    }

                    it("are not judged against a definition of the same name in the parent resource") {
                        val schema = inline(""""anyOf":[{"${'$'}ref":"#/${'$'}defs/x"}]""", """{"x":{"properties":{"a":true}}}""", """{"x":{"required":["b"]}}""")
                        isValid(schema, """{"a":1}""", version) shouldBe true
                    }

                    it("do not take credit for properties the parent's definition would evaluate") {
                        val schema = inline(""""anyOf":[{"${'$'}ref":"#/${'$'}defs/x"}]""", """{"x":{"properties":{"q":true}}}""", """{"x":{"properties":{"a":true}}}""")
                        isValid(schema, """{"a":1}""", version) shouldBe false
                    }

                    it("count properties of an if condition resolved in the subschema") {
                        val schema = inline(""""if":{"${'$'}ref":"#/${'$'}defs/x"},"then":true""", """{"x":{"properties":{"a":{"const":1}}}}""")
                        isValid(schema, """{"a":1}""", version) shouldBe true
                        isValid(schema, """{"a":2}""", version) shouldBe false
                    }

                    it("count items a local ref of the subschema evaluated") {
                        val schema = inline(""""anyOf":[{"${'$'}ref":"#/${'$'}defs/x"}]""", """{"x":{$firstItem}}""", keyword = "unevaluatedItems")
                        isValid(schema, "[1]", version) shouldBe true
                        isValid(schema, "[1,2]", version) shouldBe false
                    }

                    if (version == SchemaVersion.DRAFT_2020_12) {
                        it("count properties a dynamic ref of the subschema evaluated") {
                            val schema = inline(""""anyOf":[{"${'$'}dynamicRef":"#node"}]""", """{"node":{"${'$'}dynamicAnchor":"node","properties":{"a":true}}}""")
                            isValid(schema, """{"a":1}""", version) shouldBe true
                            isValid(schema, """{"a":1,"z":2}""", version) shouldBe false
                        }

                        // "outer" enters the dynamic scope before its $ref reaches "inner", so the outermost
                        // #ext (outer's) wins; a collector that did not enter "outer" would pick inner's (#98)
                        it("enter its dynamic scope, so a dynamic ref below it resolves to its outermost anchor") {
                            fun drift(keyword: String, outer: String, inner: String) = """
                                {"${'$'}id":"https://ex.com/root",
                                 "allOf":[{"${'$'}id":"outer","${'$'}ref":"inner","${'$'}defs":{"x":{"${'$'}dynamicAnchor":"ext",$outer}}}],
                                 "$keyword":false,
                                 "${'$'}defs":{"inner":{"${'$'}id":"inner","${'$'}dynamicRef":"#ext","${'$'}defs":{"y":{"${'$'}dynamicAnchor":"ext",$inner}}}}}
                            """
                            val properties = drift("unevaluatedProperties", """"properties":{"a":true}""", """"properties":{"b":true}""")
                            isValid(properties, """{"a":1}""", version) shouldBe true
                            isValid(properties, """{"b":1}""", version) shouldBe false
                            val items = drift("unevaluatedItems", """"prefixItems":[true]""", """"prefixItems":[true,true]""")
                            isValid(items, "[1]", version) shouldBe true
                            isValid(items, "[1,2]", version) shouldBe false
                        }
                    }
                }
            }
        }
    })
