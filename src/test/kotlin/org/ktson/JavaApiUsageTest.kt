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

        describe("JsonPointer from Java") {
            it("resolve matches Kotlin") {
                val document = """{"a/b":[10,20]}"""
                JavaApiUsage.resolvePointer(document, "/a~1b/1") shouldBe JsonPointer.resolve(Json.parseToJsonElement(document), "/a~1b/1")
                JavaApiUsage.resolvePointer(document, "/a~1b/1").toString() shouldBe "20"
            }

            it("encodeToken matches Kotlin") {
                JavaApiUsage.encodePointerToken("~a/b") shouldBe "~0a~1b"
            }
        }

        describe("JsonValidator from Java") {
            val emailSchema = """{"format":"email"}"""

            it("new JsonValidator(true, false) switches format assertion off") {
                JavaApiUsage.isValidWithoutFormatAssertion("\"not-an-email\"", emailSchema) shouldBe true
                JsonValidator().validate("\"not-an-email\"", emailSchema).isValid shouldBe false
            }

            it("validate(instanceJson, schemaJson) uses the default version") {
                JavaApiUsage.isValid("1", """{"type":"string"}""") shouldBe false
                JavaApiUsage.isValid("\"x\"", """{"type":"string"}""") shouldBe true
            }

            it("accepts a schemaLoader written as a Java lambda") {
                val schema = """{"${'$'}ref":"https://example.com/remote"}"""
                JavaApiUsage.isValidWithLoader("\"x\"", schema, "https://example.com/remote", """{"type":"string"}""") shouldBe true
                JavaApiUsage.isValidWithLoader("1", schema, "https://example.com/remote", """{"type":"string"}""") shouldBe false
            }
        }
    })
