package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.core.spec.style.scopes.DescribeSpecContainerScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File

/**
 * Test runner for the official JSON Schema Test Suite
 * https://github.com/json-schema-org/JSON-Schema-Test-Suite
 */
class OfficialTestSuiteRunner :
    DescribeSpec({
    // For Draft 2020-12, format is annotation by default (not assertion)
    // For Draft 2019-09, format should be validated
    val testSuiteBase = File("../JSON-Schema-Test-Suite/tests")

    if (testSuiteBase.exists()) {
        val schemaLoader = buildSchemaLoader(testSuiteBase)

        // Draft 2019-09 Tests
        describe("Official Test Suite: Draft 2019-09") {
            val draft201909Dir = File(testSuiteBase, "draft2019-09")

            if (draft201909Dir.exists()) {
                val validator201909 = JsonValidator(formatAssertion = true, schemaLoader = schemaLoader)
                runTestsFromDirectory(
                    directory = draft201909Dir,
                    version = SchemaVersion.DRAFT_2019_09,
                    validator = validator201909,
                )
            } else {
                it("directory not found") {
                    throw IllegalStateException("Draft 2019-09 directory not found")
                }
            }
        }

        // Draft 2020-12 Tests
        describe("Official Test Suite: Draft 2020-12") {
            val draft202012Dir = File(testSuiteBase, "draft2020-12")

            if (draft202012Dir.exists()) {
                // In 2020-12, format is annotation by default
                val validator202012 = JsonValidator(formatAssertion = false, schemaLoader = schemaLoader)
                runTestsFromDirectory(
                    directory = draft202012Dir,
                    version = SchemaVersion.DRAFT_2020_12,
                    validator = validator202012,
                )
            } else {
                it("directory not found") {
                    throw IllegalStateException("Draft 2020-12 directory not found")
                }
            }
        }
    } else {
        describe("Test Suite Not Found") {
            it("should have test suite at ${testSuiteBase.absolutePath}") {
                println("WARNING: JSON Schema Test Suite not found")
                println("Expected location: ${testSuiteBase.absolutePath}")
                println("Please clone the test suite or skip this test class")
                // Don't fail - just skip
            }
        }
    }
})

/**
 * Build a schema loader that maps:
 *  - http://localhost:1234/... → remotes/ directory next to the test suite
 *  - https://json-schema.org/... → bundled resources in src/test/resources/schemas/
 */
private fun buildSchemaLoader(testSuiteBase: File): (String) -> JsonElement? {
    val remotesBase = File(testSuiteBase.parentFile, "remotes")
    return { uri ->
        when {
            uri.startsWith("http://localhost:1234/") -> {
                val relativePath = uri.removePrefix("http://localhost:1234/")
                val file = File(remotesBase, relativePath)
                if (file.exists()) Json.parseToJsonElement(file.readText()) else null
            }
            uri.startsWith("https://json-schema.org/") -> {
                val resourcePath = "/schemas/" + uri.removePrefix("https://json-schema.org/") + ".json"
                OfficialTestSuiteRunner::class.java.getResourceAsStream(resourcePath)
                    ?.use { Json.parseToJsonElement(it.bufferedReader().readText()) }
            }
            else -> null
        }
    }
}

/**
 * Recursively run tests from a directory
 */
private suspend fun DescribeSpecContainerScope.runTestsFromDirectory(directory: File, version: SchemaVersion, validator: JsonValidator) {
    // Skip optional tests and unsupported features
    val filesToSkip = setOf(
        "optional",
        "vocabulary.json",
        "infinite-loop-detection.json",
    )

    directory.listFiles()?.sortedBy { it.name }?.forEach { file ->
        when {
            file.isDirectory && file.name !in filesToSkip -> {
                describe(file.name) {
                    runTestsFromDirectory(file, version, validator)
                }
            }
            file.isFile && file.extension == "json" && file.name !in filesToSkip -> {
                runTestsFromFile(file, version, validator)
            }
        }
    }
}

/**
 * Run tests from a single JSON file
 */
private suspend fun DescribeSpecContainerScope.runTestsFromFile(file: File, version: SchemaVersion, validator: JsonValidator) {
    try {
        val content = file.readText()
        val testCases = Json.parseToJsonElement(content).jsonArray

        describe(file.nameWithoutExtension) {
            testCases.forEachIndexed { caseIndex, testCaseElement ->
                val testCase = testCaseElement.jsonObject
                val description = testCase["description"]?.jsonPrimitive?.content ?: "Test case $caseIndex"
                val schema = testCase["schema"] ?: JsonObject(emptyMap())

                describe(description) {
                    val tests = testCase["tests"]?.jsonArray ?: JsonArray(emptyList())

                    tests.forEachIndexed { testIndex, testElement ->
                        val test = testElement.jsonObject
                        val testDescription = test["description"]?.jsonPrimitive?.content ?: "Test $testIndex"
                        val data = test["data"] ?: JsonNull
                        val expectedValid = test["valid"]?.jsonPrimitive?.boolean ?: true

                        it(testDescription) {
                            runTest {
                                val jsonSchema = JsonSchema.fromElement(schema, version)
                                val result = validator.validate(data, jsonSchema)

                                val actualValid = result.isValid
                                if (actualValid != expectedValid) {
                                    val errorDetails = if (result is ValidationResult.Invalid) {
                                        "\nValidation errors:\n" + result.validationErrors.joinToString("\n") {
                                            "  - ${it.path}: ${it.message}"
                                        }
                                    } else {
                                        ""
                                    }

                                    println("\n=== FAILED TEST ===")
                                    println("File: ${file.name}")
                                    println("Test Case: $description")
                                    println("Test: $testDescription")
                                    println("Schema: ${Json.encodeToString(JsonElement.serializer(), schema)}")
                                    println("Data: ${Json.encodeToString(JsonElement.serializer(), data)}")
                                    println("Expected: ${if (expectedValid) "VALID" else "INVALID"}")
                                    println("Actual: ${if (actualValid) "VALID" else "INVALID"}")
                                    println(errorDetails)
                                    println("==================\n")
                                }

                                actualValid shouldBe expectedValid
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        it("ERROR: Failed to parse ${file.name}: ${e.message}") {
            throw e
        }
    }
}
