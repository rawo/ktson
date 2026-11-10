package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory
import kotlin.system.measureTimeMillis

/**
 * A number is converted to Long or BigDecimal only when a numeric keyword needs it (#84). `const`, `enum` and
 * `uniqueItems` compare numbers by their digits and exponent instead, without converting them (#107).
 */
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

        // Converting both numbers to BigDecimal is quadratic in their digits: a 400,000-digit instance took 3.6 s against
        // {"const":1} and {"enum":[1]} (#107)
        SchemaVersion.entries.forEach { version ->
            describe("a 400,000-digit number in $version") {
                val digits = "1" + "0".repeat(400_000)
                val other = "1" + "0".repeat(399_999) + "1"

                fun decidesInTime(schema: String, instance: String, valid: Boolean) {
                    val parsedSchema = JsonSchema.fromString(schema, version)
                    val parsedInstance = Json.parseToJsonElement(instance)
                    val millis = measureTimeMillis { validator.validate(parsedInstance, parsedSchema).isValid shouldBe valid }
                    millis shouldBeLessThan 500
                }

                it("is compared to const in time") {
                    decidesInTime("""{"const":1}""", digits, valid = false)
                    decidesInTime("""{"const":$digits}""", "$digits.0", valid = true)
                }

                it("is compared to enum in time") {
                    decidesInTime("""{"enum":[1,"a",$other]}""", digits, valid = false)
                }

                it("is compared by uniqueItems in time") {
                    decidesInTime("""{"uniqueItems":true}""", "[$digits,$other]", valid = true)
                    decidesInTime("""{"uniqueItems":true}""", "[$digits,${digits}e0]", valid = false)
                }
            }
        }

        it("decides numeric equality as BigDecimal did, including numbers whose scale BigDecimal rejects") {
            val tokens = listOf(
                "0", "-0", "0.0", "-0.0", "0e5", "0E-5", "1", "1.0", "10e-1", "100e-2", "0.1e1", "0.10E+1", "1e2", "100", "1e+2",
                "-1", "-1.0", "-10e-1", "1.5", "15e-1", "-1.5", "2.5", "007", "7", "12345678901234567890", "1234567890123456789e1",
                "12345678901234567890.000", "1e2147483647", "10e2147483646", "1e-2147483648", "0.1e-2147483647", "1e2147483648",
                "10e2147483647", "1e2147483649", "10e2147483648", "1e99999999999999999999", "10e99999999999999999998",
                "1e-99999999999999999999", "1e0000000000000000000000002", "100",
            )
            val wrong = mutableListOf<String>()
            for (a in tokens) {
                for (b in tokens) {
                    val expected = a == b || (a.toBigDecimalOrNull()?.let { x -> b.toBigDecimalOrNull()?.let { y -> x.compareTo(y) == 0 } } ?: false)
                    val actual = validator.validate(Json.parseToJsonElement(b), JsonSchema.fromString("""{"const":$a}""")).isValid
                    if (actual != expected) wrong += "$a vs $b: $actual"
                }
            }
            wrong.shouldBeEmpty()
        }
    })
