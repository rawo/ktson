package org.ktson

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * OfficialOutputTestRunner must fail, not pass vacuously, when a suite checkout has nothing to run
 * for a draft (an emptied, moved or renamed output-tests/<draft>/content directory).
 */
class OutputTestFilesTest :
    DescribeSpec({
        fun draftDir(vararg contentFiles: String): File {
            val dir = Files.createTempDirectory("output-tests-draft").toFile().apply { deleteOnExit() }
            if (contentFiles.isNotEmpty()) {
                val content = File(dir, "content").apply { mkdir() }
                contentFiles.forEach { File(content, it).writeText("[]") }
            }
            return dir
        }

        describe("runnableOutputTestFiles") {
            it("returns the JSON files in content/, sorted, without the skipped ones") {
                val dir = draftDir("type.json", "escape.json", "readOnly.json", "README.md")
                runnableOutputTestFiles(dir, skip = setOf("readOnly.json")).map { it.name } shouldBe listOf("escape.json", "type.json")
            }

            it("fails when content/ is missing") {
                val error = shouldThrow<IllegalStateException> { runnableOutputTestFiles(draftDir(), skip = emptySet()) }
                error.message shouldContain "content"
            }

            it("fails when content/ has no JSON files") {
                shouldThrow<IllegalStateException> { runnableOutputTestFiles(draftDir("README.md"), skip = emptySet()) }
            }

            it("fails when every JSON file is skipped") {
                shouldThrow<IllegalStateException> { runnableOutputTestFiles(draftDir("readOnly.json"), skip = setOf("readOnly.json")) }
            }
        }
    })
