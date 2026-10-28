package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/** The formatAssertion constructor option decides whether `format` asserts, in every draft (#61). */
class FormatAssertionOptionTest :
    DescribeSpec({
        SchemaVersion.entries.forEach { version ->
            describe("$version") {
                val schema = JsonSchema.fromString("""{"format":"email"}""", version)

                it("rejects an invalid value when formatAssertion is true") {
                    JsonValidator(formatAssertion = true).validate(JsonPrimitive("not an email"), schema).isValid shouldBe false
                }

                it("treats format as an annotation when formatAssertion is false") {
                    JsonValidator(formatAssertion = false).validate(JsonPrimitive("not an email"), schema).isValid shouldBe true
                }
            }
        }
    })
