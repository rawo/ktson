package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.lang.management.ManagementFactory

/** The `regex` format's syntax scan is linear: it inspects characters in place instead of copying suffixes (#80). */
class RegexFormatScanTest :
    DescribeSpec({
        val validator = JsonValidator()
        val schema = JsonSchema.fromString("""{"format":"regex"}""")
        fun isValid(pattern: String) = validator.validate(JsonPrimitive(pattern), schema).isValid

        it("still recognises every ECMA 262 group form and rejects the others") {
            listOf("(?:a)", "(?=a)", "(?!a)", "(?<=a)", "(?<!a)", "(?<name>a)").forEach { isValid(it) shouldBe true }
            listOf("(?i)a", "(?#c)", "(?<1a)", "(?", "(?<", "(?>a)").forEach { isValid(it) shouldBe false }
        }

        it("allocates linearly for many flat non-capturing groups") {
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val pattern = JsonPrimitive("(?:a)".repeat(4_096))
            repeat(3) { validator.validate(pattern, schema) }
            val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
            validator.validate(pattern, schema).isValid shouldBe true
            // About 43 MB when each group copied the rest of the pattern
            (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 5_000_000L
        }
    })
