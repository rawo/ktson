package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Runs [JavaApiUsage], a Java caller of KtSON's public API, and checks it agrees with Kotlin. */
class JavaApiUsageTest :
    DescribeSpec({
        val schemaJson = """{"${'$'}id":"https://example.com/s","type":"string"}"""

        describe("JsonSchema from Java") {
            it("fromString with defaults matches Kotlin") {
                JavaApiUsage.schemaFromString(schemaJson) shouldBe JsonSchema.fromString(schemaJson)
            }

            it("fromString with a version matches Kotlin") {
                JavaApiUsage.schemaFromString(schemaJson, SchemaVersion.DRAFT_2019_09) shouldBe JsonSchema.fromString(schemaJson, SchemaVersion.DRAFT_2019_09)
            }

            it("fromElement with defaults matches Kotlin") {
                JavaApiUsage.schemaFromElement(schemaJson) shouldBe JsonSchema.fromElement(Json.parseToJsonElement(schemaJson))
            }

            it("the constructor with defaults matches Kotlin") {
                JavaApiUsage.schemaFromConstructor(schemaJson) shouldBe JsonSchema(Json.parseToJsonElement(schemaJson))
            }

            it("extractId matches Kotlin") {
                JavaApiUsage.extractId(schemaJson) shouldBe "https://example.com/s"
            }
        }
    })
