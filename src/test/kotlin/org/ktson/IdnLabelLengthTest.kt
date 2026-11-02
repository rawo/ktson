package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.lang.management.ManagementFactory

/**
 * A DNS label is at most 63 characters as an A-label. idn-hostname rejects a label that cannot fit before doing
 * any Punycode work on it, so an oversized label costs little (#79), while the limit itself stays exact.
 */
class IdnLabelLengthTest :
    DescribeSpec({
        val validator = JsonValidator()
        fun isValid(value: String, format: String = "idn-hostname") = validator.validate(JsonPrimitive(value), JsonSchema.fromString("""{"format":"$format"}""")).isValid

        it("accepts a label whose A-label is exactly 63 characters and rejects the next longer one") {
            // "a" repeated, then "ü"; java.net.IDN measures the A-label and refuses one over 63 characters
            val atLimit = (1..63).map { "a".repeat(it) + "ü" }.first { java.net.IDN.toASCII(it).length == 63 }
            val overLimit = "a$atLimit"
            runCatching { java.net.IDN.toASCII(overLimit) }.isFailure shouldBe true
            isValid("$atLimit.example") shouldBe true
            isValid("$overLimit.example") shouldBe false
        }

        it("rejects an A-label longer than 63 characters") {
            isValid("xn--" + "a".repeat(60) + "-kva.example") shouldBe false
        }

        it("still accepts ordinary internationalized names") {
            isValid("bücher.example") shouldBe true
            isValid("user@bücher.example", "idn-email") shouldBe true
        }

        it("rejects a 2,048-character label without quadratic allocation") {
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val label = buildString { repeat(2_048) { append((0x4E00 + it).toChar()) } }
            listOf("idn-hostname" to label, "idn-email" to "user@$label.example").forEach { (format, value) ->
                repeat(2) { isValid(value, format) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                isValid(value, format) shouldBe false
                // About 65 MB when the whole label was Punycode-encoded first
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 2_000_000L
            }
        }
    })
