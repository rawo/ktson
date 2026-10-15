package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Runs [JavaOutputUsage], a Java caller of the standard output API, and checks it agrees with Kotlin. */
class JavaOutputUsageTest :
    DescribeSpec({
        val schemaJson = """{"properties":{"name":{"type":"string"}}}"""
        val instanceJson = """{"name":1}"""
        val validator = JsonValidator()

        describe("standard output from Java") {
            it("validate(instance, schema, OutputFormat.Basic.INSTANCE) matches the Kotlin call") {
                val kotlinOutput = validator.validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson), OutputFormat.Basic)
                JavaOutputUsage.validateBasic(instanceJson, schemaJson) shouldBe kotlinOutput
            }

            it("ValidationOutputs.toOutput matches the Kotlin extension") {
                val kotlinOutput = validator.validate(instanceJson, schemaJson).toOutput(OutputFormat.Detailed)
                JavaOutputUsage.toDetailed(instanceJson, schemaJson) shouldBe kotlinOutput
            }
        }
    })
