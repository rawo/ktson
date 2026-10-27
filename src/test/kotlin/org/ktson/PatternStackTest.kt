package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Java's regex engine recurses for each repetition of a group such as (a|b)*, so a long string can
 * overflow the stack even when it matches (#32). validate() must not throw: a pattern that cannot be
 * evaluated fails closed with an error instead of guessing a match.
 */
class PatternStackTest :
    DescribeSpec({
        // Long enough to overflow any realistic thread stack in the regex engine.
        val long = "a".repeat(1_000_000)

        describe("pattern") {
            it("reports a pattern the engine cannot evaluate instead of throwing") {
                val result = JsonValidator().validate(JsonPrimitive(long), JsonSchema.fromString("""{"pattern":"^(a|b)*${'$'}"}"""))
                result.isValid shouldBe false
                val error = result.getErrors().single()
                error.keyword shouldBe "pattern"
                error.message shouldContain "could not be checked"
            }

            it("still matches a long string against a pattern the engine handles iteratively") {
                JsonValidator().validate(JsonPrimitive(long), JsonSchema.fromString("""{"pattern":"^a+${'$'}"}""")).isValid shouldBe true
            }
        }

        describe("patternProperties") {
            it("reports a property name the engine cannot evaluate instead of throwing") {
                val instance = buildJsonObject { put(long, JsonPrimitive(1)) }
                val result = JsonValidator().validate(instance, JsonSchema.fromString("""{"patternProperties":{"^(a|b)*${'$'}":{"type":"integer"}}}"""))
                result.isValid shouldBe false
                result.getErrors().single().message shouldContain "could not be checked"
            }

            it("does not throw from additionalProperties or unevaluatedProperties either") {
                val instance = buildJsonObject { put(long, JsonPrimitive(1)) }
                JsonValidator().validate(instance, JsonSchema.fromString("""{"patternProperties":{"^(a|b)*${'$'}":true},"additionalProperties":false}""")).isValid shouldBe false
                JsonValidator().validate(instance, JsonSchema.fromString("""{"patternProperties":{"^(a|b)*${'$'}":true},"unevaluatedProperties":false}""")).isValid shouldBe false
            }
        }
    })
