package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Without maxContains, contains is decided once minContains items match, so later items are not evaluated for the
 * verdict (#89). unevaluatedItems still sees every match, which it collects in its own pass.
 */
class ContainsEvaluationTest :
    DescribeSpec({
        val ref = "${'$'}ref"
        val input = JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0)) + List(1000) { JsonPrimitive(1) })

        fun loads(schema: String): Int {
            var calls = 0
            val validator = JsonValidator(schemaLoader = {
                calls++
                null
            })
            validator.validate(input, JsonSchema.fromString(schema))
            return calls
        }

        fun result(instance: String, schema: String) = JsonValidator().validate(Json.parseToJsonElement(instance), JsonSchema.fromString(schema))

        val matchOrLoad = """{"anyOf":[{"const":0},{"$ref":"https://example.com/unused"}]}"""

        it("evaluates no item after the first match") {
            loads("""{"contains":$matchOrLoad}""") shouldBe 0
            JsonValidator().validate(input, JsonSchema.fromString("""{"contains":{"const":0}}"""), OutputFormat.Flag).valid shouldBe true
        }

        it("evaluates no item after minContains matches") {
            loads("""{"contains":$matchOrLoad,"minContains":2}""") shouldBe 0
            loads("""{"contains":$matchOrLoad,"minContains":3}""") shouldBe 1000
        }

        it("evaluates no item when minContains is 0 and there is no maxContains") {
            loads("""{"contains":{"$ref":"https://example.com/unused"},"minContains":0}""") shouldBe 0
        }

        it("still counts every match against maxContains") {
            loads("""{"contains":$matchOrLoad,"maxContains":5}""") shouldBe 1000
            result("[0,1,0]", """{"contains":{"const":0},"maxContains":1}""").isValid shouldBe false
            result("[0,1,0]", """{"contains":{"const":0},"maxContains":2}""").isValid shouldBe true
            result("[0,0]", """{"contains":{"const":0},"minContains":0,"maxContains":1}""").isValid shouldBe false
        }

        it("reports every match when minContains is not reached") {
            val errors = (result("[0,1,0]", """{"contains":{"const":0},"minContains":3}""") as ValidationResult.Invalid).validationErrors
            errors.map { it.message } shouldBe listOf("Array contains 2 matching items, minimum is 3")
            result("[]", """{"contains":true}""").isValid shouldBe false
        }

        it("still lets unevaluatedItems see every match") {
            result("[0,0,0]", """{"contains":{"const":0},"unevaluatedItems":false}""").isValid shouldBe true
            result("[0,1,0]", """{"contains":{"const":0},"unevaluatedItems":false}""").isValid shouldBe false
            result("[0,1,0]", """{"anyOf":[{"contains":{"const":0}}],"unevaluatedItems":{"const":1}}""").isValid shouldBe true
        }
    })
