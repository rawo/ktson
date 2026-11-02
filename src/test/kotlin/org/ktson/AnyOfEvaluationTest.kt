package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One valid branch decides anyOf, so the remaining branches are not evaluated for the verdict (#78). Their
 * annotations still count for unevaluated*, which collects them in its own pass over every branch.
 */
class AnyOfEvaluationTest :
    DescribeSpec({
        fun refs(count: Int): List<JsonElement> = List(count) { index -> buildJsonObject { put("\$ref", JsonPrimitive("https://example.com/branch/$index")) } }

        it("loads no further branch once one branch is valid") {
            val calls = mutableListOf<String>()
            val validator = JsonValidator(schemaLoader = { uri ->
                calls += uri
                JsonPrimitive(true)
            })
            val schema = JsonSchema(buildJsonObject { put("anyOf", JsonArray(listOf(JsonPrimitive(true)) + refs(512))) })
            validator.validate(JsonPrimitive(1), schema).isValid shouldBe true
            calls shouldBe emptyList()
        }

        it("evaluates every branch, and reports each, when none is valid") {
            val calls = mutableListOf<String>()
            val validator = JsonValidator(schemaLoader = { uri ->
                calls += uri
                JsonPrimitive(false)
            })
            val schema = JsonSchema(buildJsonObject { put("anyOf", JsonArray(refs(3))) })
            val error = validator.validate(JsonPrimitive(1), schema).getErrors().single()
            error.keyword shouldBe "anyOf"
            error.causes.size shouldBe 3
            calls.size shouldBe 3
        }

        it("still counts properties evaluated by a later valid branch for unevaluatedProperties") {
            val schema = JsonSchema.fromString("""{"anyOf":[{"properties":{"a":true}},{"properties":{"b":true}}],"unevaluatedProperties":false}""")
            JsonValidator().validate(Json.parseToJsonElement("""{"a":1,"b":2}"""), schema).isValid shouldBe true
            JsonValidator().validate(Json.parseToJsonElement("""{"a":1,"c":2}"""), schema).isValid shouldBe false
        }
    })
