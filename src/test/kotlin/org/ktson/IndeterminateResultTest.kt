package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * A subschema that could not be evaluated (a regex the engine cannot run, the depth limit) has no
 * verdict. Applicators that turn a failure into success - not, the if condition, oneOf with one other
 * match, maxContains - must not do that with it, or an undecided result becomes Valid (#51).
 */
class IndeterminateResultTest :
    DescribeSpec({
        // Java's regex engine overflows the stack on (a|b)* over a string this long (#32).
        val long = JsonPrimitive("a".repeat(1_000_000))
        val unknown = """{"pattern":"^(a|b)*${'$'}"}"""

        fun isValid(schema: String, instance: kotlinx.serialization.json.JsonElement = long, validator: JsonValidator = JsonValidator()): Boolean = validator.validate(instance, JsonSchema.fromString(schema)).isValid

        describe("a pattern the engine cannot evaluate") {
            listOf(
                """{"not":$unknown}""",
                """{"oneOf":[true,$unknown]}""",
                """{"if":$unknown,"then":false,"else":true}""",
                """{"not":{"not":$unknown}}""",
                """{"not":{"anyOf":[$unknown]}}""",
                """{"not":{"properties":{"x":$unknown}}}""",
            ).forEach { schema ->
                it("is not turned into a valid result by $schema") {
                    val instance = if ("properties" in schema) Json.parseToJsonElement("""{"x":"${"a".repeat(1_000_000)}"}""") else long
                    isValid(schema, instance) shouldBe false
                }
            }

            it("is not turned into a valid result by maxContains") {
                isValid("""{"contains":$unknown,"minContains":0,"maxContains":0}""", JsonArray(listOf(long))) shouldBe false
            }

            it("still passes maxContains when even an undecided item matching would stay within it") {
                isValid("""{"contains":$unknown,"minContains":0,"maxContains":1}""", JsonArray(listOf(long))) shouldBe true
            }

            it("reports the undecided applicator with the evaluation failure as its cause") {
                val error = JsonValidator().validate(long, JsonSchema.fromString("""{"not":$unknown}""")).getErrors().single()
                error.keyword shouldBe "not"
                error.causes.single().keyword shouldBe "pattern"
            }

            it("does not affect anyOf when another branch is valid") {
                isValid("""{"anyOf":[true,$unknown]}""") shouldBe true
            }
        }

        describe("the depth limit") {
            val deep = """{"allOf":[{"allOf":[true]}]}"""
            val shallow = JsonValidator(maxValidationDepth = 1)

            it("is not turned into a valid result by if") {
                isValid("""{"if":$deep,"then":false,"else":true}""", JsonPrimitive(1), shallow) shouldBe false
            }

            it("is not turned into a valid result by oneOf with one other match") {
                isValid("""{"oneOf":[true,$deep]}""", JsonPrimitive(1), shallow) shouldBe false
            }
        }

        describe("ordinary failures keep their meaning") {
            val failing = """{"pattern":"^b"}"""
            it("not") { isValid("""{"not":$failing}""", JsonPrimitive("a")) shouldBe true }
            it("oneOf") { isValid("""{"oneOf":[true,$failing]}""", JsonPrimitive("a")) shouldBe true }
            it("if") { isValid("""{"if":$failing,"then":false,"else":true}""", JsonPrimitive("a")) shouldBe true }
            it("maxContains") { isValid("""{"contains":$failing,"maxContains":0}""", JsonArray(listOf(JsonPrimitive("a")))) shouldBe false }
            it("maxContains with no matches") { isValid("""{"contains":$failing,"minContains":0,"maxContains":0}""", JsonArray(listOf(JsonPrimitive("a")))) shouldBe true }
        }
    })
