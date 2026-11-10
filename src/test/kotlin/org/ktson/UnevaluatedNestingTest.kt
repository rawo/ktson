package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.system.measureTimeMillis

/**
 * unevaluated* needs to know which anyOf/oneOf branches and if conditions are valid. Re-validating them for that
 * made every level of nesting at one instance location cost about 2.6 times the level below it: 20 levels took
 * 20 seconds (#30). The collection pass now reuses the verdicts the validation already reached.
 */
class UnevaluatedNestingTest :
    DescribeSpec({
        val levels = 40

        fun nested(applicator: String, keyword: String, innermost: JsonElement): JsonSchema {
            var schema = innermost
            repeat(levels) {
                val inner = schema
                schema = buildJsonObject {
                    when (applicator) {
                        "if" -> {
                            put("if", inner)
                            put("then", JsonPrimitive(true))
                        }
                        else -> put(applicator, JsonArray(listOf(inner)))
                    }
                    put(keyword, JsonPrimitive(false))
                }
            }
            return JsonSchema(schema)
        }

        fun json(text: String) = Json.parseToJsonElement(text)

        for (applicator in listOf("anyOf", "oneOf", "if")) {
            describe("$levels levels of $applicator with unevaluated* at one location") {
                it("decide unevaluatedProperties in time") {
                    val schema = nested(applicator, "unevaluatedProperties", json("""{"properties":{"a":true}}"""))
                    val validator = JsonValidator()
                    val millis = measureTimeMillis {
                        validator.validate(json("""{"a":1}"""), schema).isValid shouldBe true
                        validator.validate(json("""{"a":1,"z":2}"""), schema).isValid shouldBe false
                    }
                    millis shouldBeLessThan 2_000
                }

                it("decide unevaluatedItems in time") {
                    val schema = nested(applicator, "unevaluatedItems", json("""{"prefixItems":[true]}"""))
                    val validator = JsonValidator()
                    val millis = measureTimeMillis {
                        validator.validate(json("""[1]"""), schema).isValid shouldBe true
                        validator.validate(json("""[1,2]"""), schema).isValid shouldBe false
                    }
                    millis shouldBeLessThan 2_000
                }
            }
        }

        // Each level's verdict pass collects what its subtree evaluated, and the collection of every level above walked
        // that subtree again: 400 levels of allOf over 2,000 properties took 3.2 s, about 4x per doubling (#106). A
        // collected set is now reused while its instance is being collected, so the cost follows levels + properties
        SchemaVersion.entries.forEach { version ->
            describe("400 levels of allOf with unevaluated* over 2,000 evaluated members in $version") {
                val chainLevels = 400
                val members = 2_000

                fun chain(keyword: String, innermost: String): JsonSchema {
                    var schema = innermost
                    repeat(chainLevels) { schema = """{"allOf":[$schema],"$keyword":false}""" }
                    return JsonSchema.fromString(schema, version)
                }

                it("decide unevaluatedProperties in time") {
                    val schema = chain("unevaluatedProperties", """{"properties":{${(0 until members).joinToString(",") { "\"k$it\":true" }}}}""")
                    val valid = buildJsonObject { repeat(members) { put("k$it", JsonPrimitive(1)) } }
                    val invalid = buildJsonObject {
                        repeat(members) { put("k$it", JsonPrimitive(1)) }
                        put("z", JsonPrimitive(1))
                    }
                    val validator = JsonValidator()
                    val millis = measureTimeMillis {
                        validator.validate(valid, schema).isValid shouldBe true
                        validator.validate(invalid, schema).isValid shouldBe false
                    }
                    millis shouldBeLessThan 1_500
                }

                it("decide unevaluatedItems in time") {
                    val first = if (version == SchemaVersion.DRAFT_2019_09) "items" else "prefixItems"
                    val schema = chain("unevaluatedItems", """{"$first":[${List(members) { "true" }.joinToString(",")}]}""")
                    val validator = JsonValidator()
                    val millis = measureTimeMillis {
                        validator.validate(JsonArray(List(members) { JsonPrimitive(it) }), schema).isValid shouldBe true
                        validator.validate(JsonArray(List(members + 1) { JsonPrimitive(it) }), schema).isValid shouldBe false
                    }
                    millis shouldBeLessThan 1_500
                }
            }
        }

        it("keeps verdicts of one branch reached in different dynamic scopes apart") {
            // shared's anyOf branch is the same schema element under a and under b, but its $dynamicRef leads to
            // a's node (valid, evaluates "a") in one scope and to b's node (invalid) in the other. b is validated
            // last, so a verdict keyed without the scope would hide that a evaluated "a"
            val schema = JsonSchema.fromString(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "${'$'}id": "https://example.com/root",
                  "oneOf": [{"${'$'}ref": "a"}, {"${'$'}ref": "b"}],
                  "unevaluatedProperties": false,
                  "${'$'}defs": {
                    "shared": {
                      "${'$'}id": "shared",
                      "anyOf": [{"${'$'}dynamicRef": "#node"}],
                      "${'$'}defs": {"node": {"${'$'}dynamicAnchor": "node"}}
                    },
                    "a": {
                      "${'$'}id": "a",
                      "${'$'}ref": "shared",
                      "${'$'}defs": {"node": {"${'$'}dynamicAnchor": "node", "properties": {"a": true}}}
                    },
                    "b": {
                      "${'$'}id": "b",
                      "${'$'}ref": "shared",
                      "${'$'}defs": {"node": {"${'$'}dynamicAnchor": "node", "required": ["b"]}}
                    }
                  }
                }
                """.trimIndent(),
            )
            JsonValidator().validate(json("""{"a":1}"""), schema).isValid shouldBe true
            JsonValidator().validate(json("""{"a":1,"z":2}"""), schema).isValid shouldBe false
        }

        it("does not reuse a branch verdict the depth limit left undecided") {
            // e's second anyOf branch cannot be decided within the depth limit. Its verdict pass stops at the first
            // branch, so inside not only the collection of evaluated properties reaches the second one: it must see
            // the undecided result again, or not would invert e's undecided failure into success (#51)
            var deep: JsonElement = json("""{"properties":{"a":true}}""")
            repeat(10) { deep = buildJsonObject { put("allOf", JsonArray(listOf(deep))) } }
            val schema = JsonSchema(
                buildJsonObject {
                    put("anyOf", json("""[{"${'$'}ref":"#/${'$'}defs/e"}, true]"""))
                    put("not", json("""{"${'$'}ref":"#/${'$'}defs/e"}"""))
                    put("unevaluatedProperties", JsonPrimitive(true))
                    put(
                        "${'$'}defs",
                        buildJsonObject {
                            put("e", json("""{"anyOf":[true, {"${'$'}ref":"#/${'$'}defs/deep"}],"unevaluatedProperties":false}"""))
                            put("deep", deep)
                        },
                    )
                },
            )
            JsonValidator(maxValidationDepth = 6).validate(json("""{"a":1}"""), schema).isValid shouldBe false
        }
    })
