package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.lang.management.ManagementFactory

/**
 * Basic output flattens the error tree into one list, each unit before its causes. The work and the
 * allocation must grow linearly with the number of units, and deep trees must not recurse (#75).
 */
class BasicOutputFlatteningTest :
    DescribeSpec({
        fun error(message: String, vararg causes: ValidationError) = ValidationError("", message, "allOf", "/$message", causes.toList())

        fun chain(depth: Int): ValidationError {
            var error = ValidationError("", "leaf", "type", "")
            repeat(depth) { error = ValidationError("", "branch", "allOf", "", listOf(error)) }
            return error
        }

        it("lists every unit before its causes, depth first, across several root errors") {
            val result = ValidationResult.Invalid(
                listOf(
                    error("a", error("a1", error("a1x")), error("a2")),
                    error("b", error("b1")),
                ),
            )
            result.toOutput(OutputFormat.Basic).errors!!.map { it.error } shouldBe listOf("a", "a1", "a1x", "a2", "b", "b1")
        }

        it("flattens a 100,000-level chain without recursing") {
            ValidationResult.Invalid(listOf(chain(100_000))).toOutput(OutputFormat.Basic).errors!!.size shouldBe 100_001
        }

        it("allocates in proportion to the units it produces") {
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val result = ValidationResult.Invalid(listOf(chain(1_024)))
            repeat(3) { result.toOutput(OutputFormat.Basic) }
            val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
            result.toOutput(OutputFormat.Basic).errors!!.size shouldBe 1_025
            // About 100 bytes per unit; the quadratic version allocated about 8.6 MB here
            (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 1_000_000L
        }
    })
