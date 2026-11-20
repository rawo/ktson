package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Runs the usage examples in Example.kt, which live in the tests so they are not part of the library's API (#113). */
class ExampleTest :
    DescribeSpec({
        it("runs every example") {
            val out = ByteArrayOutputStream()
            val console = System.out
            System.setOut(PrintStream(out, true))
            try {
                main()
            } finally {
                System.setOut(console)
            }
            out.toString() shouldContain "KtSON JSON Schema Validator Examples"
        }
    })
