package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import java.lang.management.ManagementFactory
import kotlin.system.measureTimeMillis

/**
 * The `regex` format's syntax scan is linear: it inspects characters in place instead of copying suffixes (#80), and the
 * check compiles a pattern without the Boyer-Moore table Java builds for a leading literal run in quadratic time (#112).
 */
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

        // Pattern.compile builds a Boyer-Moore table for a pattern starting with literal characters, quadratic in the
        // run for repetitive text: 200,000 "a"s took 7.5 s (#112)
        it("checks a pattern with a 200,000-character literal run in time") {
            val run = "a".repeat(200_000)
            val millis = measureTimeMillis {
                isValid(run) shouldBe true
                isValid(run + "\\d") shouldBe true
                isValid("$run(") shouldBe false
            }
            millis shouldBeLessThan 1_000
        }

        it("accepts and rejects the same patterns as a plain compile") {
            listOf("abc", "(a)\\1", "(?<n>a)\\k<n>", "\\p{Lu}", "[\\u212A]", "K", "[a-z]+", "a{1,2}").forEach { isValid(it) shouldBe true }
            listOf("[b-a]", "[a-Z]", "a{2,1}", "(a", "a)", "*a", "[a").forEach { isValid(it) shouldBe false }
        }
    })
