package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory
import java.math.BigDecimal

/**
 * multipleOf is exact, and its cost follows the size of the numbers as written, not their exponents:
 * "1e2147483647" is a 12-character literal whose expansion has two billion digits (#70).
 */
class MultipleOfTest :
    DescribeSpec({
        fun isValid(instance: String, schema: String): Boolean = JsonValidator().validate(Json.parseToJsonElement(instance), JsonSchema.fromString(schema)).isValid

        describe("compact numbers with huge exponents") {
            it("decides multipleOf for 1e2147483647 without throwing") {
                isValid("1e2147483647", """{"multipleOf":1}""") shouldBe true
                isValid("1e2147483647", """{"multipleOf":2}""") shouldBe true
                isValid("1e2147483647", """{"multipleOf":3}""") shouldBe false
                isValid("1e2147483647", """{"multipleOf":0.5}""") shouldBe true
                isValid("1e2147483647", """{"maximum":100,"multipleOf":3}""") shouldBe false
                isValid("3e2147483647", """{"multipleOf":3}""") shouldBe true
            }

            it("decides tiny numbers against huge and tiny divisors") {
                isValid("1e-1000000", """{"multipleOf":1}""") shouldBe false
                isValid("1e-1000000", """{"multipleOf":1e-1000000}""") shouldBe true
                isValid("2e-1000000", """{"multipleOf":1e-1000000}""") shouldBe true
                isValid("1e-1000000", """{"multipleOf":2e-1000000}""") shouldBe false
                isValid("1", """{"multipleOf":1e100000}""") shouldBe false
                isValid("0", """{"multipleOf":1e100000}""") shouldBe true
            }

            it("allocates little for 1e100000 against multipleOf 3") {
                val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val validator = JsonValidator()
                val instance = Json.parseToJsonElement("1e100000")
                val schema = JsonSchema.fromString("""{"multipleOf":3}""")
                repeat(3) { validator.validate(instance, schema) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                validator.validate(instance, schema).isValid shouldBe false
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 1_000_000L
            }
        }

        describe("agreement with BigDecimal.remainder") {
            it("gives the same answer on a grid of values and divisors") {
                val values = listOf("0", "1", "-1", "6", "-6", "7", "10", "0.5", "1.5", "2.25", "-2.25", "0.0075", "120", "1e3", "2.5e3", "1e-3", "3e-3", "12.5", "0.1", "0.3", "1e10", "4096", "390625", "7e-2", "123456789012345678901234567890")
                val divisors = listOf("1", "2", "3", "5", "0.5", "0.25", "0.1", "0.01", "0.0025", "1.5", "7", "10", "0.3", "1e2", "4e-3", "12.5", "2048", "15625", "0.07", "1e-2")
                var mismatches = 0
                for (v in values) {
                    for (d in divisors) {
                        val expected = BigDecimal(v).remainder(BigDecimal(d)).signum() == 0
                        if (isValid(v, """{"multipleOf":$d}""") != expected) mismatches++
                    }
                }
                mismatches shouldBeLessThan 1
            }
        }
    })
