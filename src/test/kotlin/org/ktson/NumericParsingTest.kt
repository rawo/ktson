package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory

/** A number is converted to Long or BigDecimal only when a numeric keyword needs it (#84). */
class NumericParsingTest :
    DescribeSpec({
        val validator = JsonValidator()
        val huge = Json.parseToJsonElement("7".repeat(100_000))

        listOf("{}", """{"title":"unconstrained"}""", """{"type":"integer"}""").forEach { schemaText ->
            it("does not parse a 100,000-digit number for $schemaText") {
                val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val schema = JsonSchema.fromString(schemaText)
                repeat(2) { validator.validate(huge, schema) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                validator.validate(huge, schema).isValid shouldBe true
                // About 480 KB (and 225 ms) when the number was converted to BigDecimal for nothing
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 100_000L
            }
        }

        it("classifies integer and non-integer tokens as before") {
            listOf("0", "-12", "1.0", "1e2", "-1.5e1").forEach { token -> validator.validate(Json.parseToJsonElement(token), JsonSchema.fromString("""{"type":"integer"}""")).isValid shouldBe true }
            listOf("1.5", "-0.25", "1e-1").forEach { token -> validator.validate(Json.parseToJsonElement(token), JsonSchema.fromString("""{"type":"integer"}""")).isValid shouldBe false }
            validator.validate(Json.parseToJsonElement("-" + "7".repeat(100_000)), JsonSchema.fromString("""{"type":"integer"}""")).isValid shouldBe true
        }

        it("still compares the number exactly when a numeric keyword is present") {
            validator.validate(huge, JsonSchema.fromString("""{"minimum":1e99999}""")).isValid shouldBe true
            validator.validate(huge, JsonSchema.fromString("""{"maximum":1e99999}""")).isValid shouldBe false
            validator.validate(huge, JsonSchema.fromString("""{"multipleOf":7}""")).isValid shouldBe true
            validator.validate(Json.parseToJsonElement("1.5"), JsonSchema.fromString("""{"exclusiveMaximum":1.5}""")).isValid shouldBe false
        }
    })
