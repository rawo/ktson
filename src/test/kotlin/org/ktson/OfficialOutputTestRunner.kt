package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File

/**
 * Runs the official JSON Schema Test Suite output-tests against KtSON's Basic output.
 * https://github.com/json-schema-org/JSON-Schema-Test-Suite/tree/main/output-tests
 *
 * readOnly.json is skipped: it needs annotations, which KtSON does not produce.
 * output-tests/v1 targets a later release than KtSON supports and is not run.
 */
class OfficialOutputTestRunner :
    DescribeSpec({
        val outputTestsBase = File("../JSON-Schema-Test-Suite/output-tests")
        val filesToSkip = setOf("readOnly.json")
        val drafts = listOf("draft2019-09" to SchemaVersion.DRAFT_2019_09, "draft2020-12" to SchemaVersion.DRAFT_2020_12)

        if (outputTestsBase.exists()) {
            drafts.forEach { (draftName, version) ->
                describe("Official Output Tests: $draftName") {
                    val draftDir = File(outputTestsBase, draftName)
                    val outputSchema = Json.parseToJsonElement(File(draftDir, "output-schema.json").readText())
                    val outputSchemaId = outputSchema.jsonObject["\$id"]!!.jsonPrimitive.content
                    val outputChecker = JsonValidator(formatAssertion = true, schemaLoader = outputSchemaLoader(outputSchemaId, outputSchema))
                    val validator = JsonValidator()

                    runnableOutputTestFiles(draftDir, filesToSkip)
                        .forEach { file ->
                            describe(file.nameWithoutExtension) {
                                Json.parseToJsonElement(file.readText()).jsonArray.forEach { caseElement ->
                                    val case = caseElement.jsonObject
                                    val schema = JsonSchema.fromElement(case["schema"]!!, version)
                                    describe(case["description"]!!.jsonPrimitive.content) {
                                        case["tests"]!!.jsonArray.forEach { testElement ->
                                            val test = testElement.jsonObject
                                            it(test["description"]!!.jsonPrimitive.content) {
                                                val output = validator.validate(test["data"]!!, schema, OutputFormat.Basic)
                                                val basicSchema = JsonSchema.fromElement(test["output"]!!.jsonObject["basic"]!!, version)
                                                val check = outputChecker.validate(Json.parseToJsonElement(output.toJson()), basicSchema)
                                                if (check.isInvalid) {
                                                    println("\n=== FAILED OUTPUT TEST ===\nFile: ${file.name}\nOutput: ${output.toJson()}\n$check\n")
                                                }
                                                check.isValid shouldBe true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        } else {
            describe("Output Tests Not Found") {
                it("should have output tests at ${outputTestsBase.absolutePath}") {
                    val message = "JSON Schema Test Suite output-tests not found at ${outputTestsBase.absolutePath}; " +
                        "clone https://github.com/json-schema-org/JSON-Schema-Test-Suite.git next to this project"
                    // On CI a missing suite means the output tests silently did not run, so fail loudly.
                    check(System.getenv("CI") == null) { message }
                    println("WARNING: $message")
                }
            }
        }
    })

/** Serves the draft's output schema by its $id, and meta-schemas from the bundled test resources. */
private fun outputSchemaLoader(outputSchemaId: String, outputSchema: JsonElement): (String) -> JsonElement? = { uri ->
    when {
        uri.removeSuffix("#") == outputSchemaId -> outputSchema
        uri.startsWith("https://json-schema.org/") -> {
            val resourcePath = "/schemas/" + uri.removePrefix("https://json-schema.org/").removeSuffix("#") + ".json"
            OfficialOutputTestRunner::class.java.getResourceAsStream(resourcePath)
                ?.use { Json.parseToJsonElement(it.bufferedReader().readText()) }
        }
        else -> null
    }
}

/**
 * The output-test files to run for one draft: the JSON files in [draftDir]/content not in [skip],
 * sorted by name. Fails when there are none, so a suite checkout that empties, moves or renames
 * them turns the runner red instead of letting it pass with zero tests.
 */
internal fun runnableOutputTestFiles(draftDir: File, skip: Set<String>): List<File> {
    val contentDir = File(draftDir, "content")
    check(contentDir.isDirectory) { "Output tests directory not found: ${contentDir.absolutePath}" }
    val files = contentDir.listFiles { file -> file.extension == "json" && file.name !in skip }.orEmpty().sortedBy { it.name }
    check(files.isNotEmpty()) { "No runnable output-test files in ${contentDir.absolutePath} (skipped: $skip)" }
    return files
}
