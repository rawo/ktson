package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.math.BigInteger

/**
 * multipleOf is exact, and its cost follows the size of the numbers as written, not their exponents:
 * "1e2147483647" is a 12-character literal whose expansion has two billion digits (#70), and a divisor's factors
 * of five are counted in O(log n) divisions (#87).
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

        describe("divisors with many factors of five") {
            it("allocates little for multipleOf 5^16000") {
                // Removing one factor of five per pass allocated about 82 MB here (#87)
                val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val validator = JsonValidator()
                val schema = JsonSchema.fromString("""{"multipleOf":${BigInteger.valueOf(5).pow(16_000)}}""")
                val instance = Json.parseToJsonElement("1")
                repeat(3) { validator.validate(instance, schema) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                validator.validate(instance, schema).isValid shouldBe false
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 5_000_000L
            }

            it("counts the fives exactly") {
                val five = BigInteger.valueOf(5)
                var mismatches = 0
                for (fives in listOf(0, 1, 2, 3, 4, 7, 8, 9, 15, 16, 17, 31, 32, 33, 100, 255, 256, 257)) {
                    val divisor = BigDecimal(five.pow(fives).multiply(BigInteger.valueOf(6)))
                    for (extra in -1..1) {
                        for (scale in listOf(0, 3, -2)) {
                            val value = BigDecimal(five.pow((fives + extra).coerceAtLeast(0)).multiply(BigInteger.valueOf(42)), scale)
                            val expected = value.remainder(divisor).signum() == 0
                            if (value.isMultipleOf(divisor) != expected) mismatches++
                            if (isValid(value.toString(), """{"multipleOf":$divisor}""") != expected) mismatches++
                        }
                    }
                }
                mismatches shouldBeLessThan 1
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
