package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Comparing instance values, checking schema structure and searching for anchors and $ids walk
 * arbitrarily deep JSON (#31). They must work iteratively: maxValidationDepth bounds validation, not
 * these walks, so deep input must neither overflow the stack nor depend on the thread's stack size.
 */
class DeepStructureTest :
    DescribeSpec({
        fun nestedArray(depth: Int, leaf: Int): JsonElement {
            var element: JsonElement = JsonPrimitive(leaf)
            repeat(depth) { element = JsonArray(listOf(element)) }
            return element
        }

        fun nestedNot(depth: Int, innermost: JsonObject = JsonObject(emptyMap())): JsonElement {
            var element: JsonElement = innermost
            repeat(depth) { element = buildJsonObject { put("not", element) } }
            return element
        }

        val shallow = JsonValidator(maxValidationDepth = 2)

        describe("value equality (const, enum, uniqueItems)") {
            it("compares 10,000-level values under const") {
                shallow.validate(nestedArray(10_000, 0), JsonSchema(buildJsonObject { put("const", nestedArray(10_000, 1)) })).isValid shouldBe false
                shallow.validate(nestedArray(10_000, 0), JsonSchema(buildJsonObject { put("const", nestedArray(10_000, 0)) })).isValid shouldBe true
            }

            it("compares 10,000-level values under enum") {
                val schema = JsonSchema(buildJsonObject { put("enum", JsonArray(listOf(nestedArray(10_000, 1), nestedArray(10_000, 0)))) })
                shallow.validate(nestedArray(10_000, 0), schema).isValid shouldBe true
            }

            it("finds 10,000-level duplicates under uniqueItems") {
                val schema = JsonSchema(buildJsonObject { put("uniqueItems", JsonPrimitive(true)) })
                shallow.validate(JsonArray(listOf(nestedArray(10_000, 0), nestedArray(10_000, 0))), schema).isValid shouldBe false
                shallow.validate(JsonArray(listOf(nestedArray(10_000, 0), nestedArray(10_000, 1))), schema).isValid shouldBe true
            }

            it("keeps numeric equivalence (1 equals 1.0) deep inside values") {
                val schema = JsonSchema(buildJsonObject { put("const", JsonArray(listOf(buildJsonObject { put("n", JsonPrimitive(1.0)) }))) })
                shallow.validate(JsonArray(listOf(buildJsonObject { put("n", JsonPrimitive(1)) })), schema).isValid shouldBe true
            }
        }

        describe("schema structure checks") {
            it("checks a schema nested 10,000 levels deep") {
                shallow.validateSchema(JsonSchema(nestedNot(10_000))).isValid shouldBe true
                shallow.validateSchema(JsonSchema(nestedNot(10_000, buildJsonObject { put("type", JsonPrimitive("bogus")) }))).isValid shouldBe false
            }
        }

        describe("anchor and \$id searches") {
            it("searches 100,000 nested schemas for a missing anchor") {
                val root = buildJsonObject {
                    put("not", nestedNot(100_000))
                    put("\$ref", JsonPrimitive("#missing"))
                }
                shallow.validate(JsonPrimitive(1), JsonSchema(root)).isValid shouldBe false
            }

            it("finds an anchor 10,000 levels deep") {
                val root = buildJsonObject {
                    put("\$defs", buildJsonObject {
                        put("deep", nestedNot(10_000, buildJsonObject {
                        put("\$anchor", JsonPrimitive("bottom"))
                        put("type", JsonPrimitive("integer"))
                    }))
                    })
                    put("\$ref", JsonPrimitive("#bottom"))
                }
                JsonValidator().validate(JsonPrimitive(1), JsonSchema(root)).isValid shouldBe true
                JsonValidator().validate(JsonPrimitive("x"), JsonSchema(root)).isValid shouldBe false
            }

            it("finds an \$id 10,000 levels deep") {
                val root = buildJsonObject {
                    put("\$id", JsonPrimitive("https://example.com/root"))
                    put("\$defs", buildJsonObject {
                        put("deep", nestedNot(10_000, buildJsonObject {
                        put("\$id", JsonPrimitive("bottom.json"))
                        put("type", JsonPrimitive("integer"))
                    }))
                    })
                    put("\$ref", JsonPrimitive("bottom.json"))
                }
                JsonValidator().validate(JsonPrimitive(1), JsonSchema(root)).isValid shouldBe true
                JsonValidator().validate(JsonPrimitive("x"), JsonSchema(root)).isValid shouldBe false
            }
        }
    })
