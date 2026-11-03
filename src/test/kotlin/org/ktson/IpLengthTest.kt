package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.lang.management.ManagementFactory

/**
 * An IPv4 address has at most 15 characters and an IPv6 address at most 45 (six groups and an IPv4 tail): longer
 * strings are rejected before they are split or copied, which allocated about 60 MB for a 1 MB string (#88).
 */
class IpLengthTest :
    DescribeSpec({
        val validator = JsonValidator(formatAssertion = true)
        fun isValid(value: String, format: String) = validator.validate(JsonPrimitive(value), JsonSchema.fromString("""{"format":"$format"}""")).isValid

        it("accepts addresses of the longest length") {
            isValid("255.255.255.255", "ipv4") shouldBe true
            isValid("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "ipv6") shouldBe true
            isValid("ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255", "ipv6") shouldBe true
            isValid("http://[ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255]/", "uri") shouldBe true
            isValid("a@[IPv6:ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255]", "email") shouldBe true
        }

        it("rejects one character more") {
            isValid("0255.255.255.255", "ipv4") shouldBe false
            isValid("0ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255", "ipv6") shouldBe false
            isValid("http://[0ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255]/", "uri") shouldBe false
        }

        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        for ((format, text) in listOf("ipv6" to "::" + "a".repeat(1_000_000), "ipv4" to "1.".repeat(1_000_000))) {
            it("rejects a 1 MB $format candidate without allocating in proportion to it") {
                val schema = JsonSchema.fromString("""{"format":"$format"}""")
                val instance = JsonPrimitive(text)
                repeat(2) { validator.validate(instance, schema) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                validator.validate(instance, schema).isValid shouldBe false
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 100_000L
            }
        }
    })
