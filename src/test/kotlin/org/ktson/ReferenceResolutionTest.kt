package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** How $ref values are turned into targets: percent-decoding of pointer fragments and base URIs. */
class ReferenceResolutionTest :
    DescribeSpec({
        fun isValid(schemaJson: String, instanceJson: String, version: SchemaVersion): Boolean = JsonValidator().validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson, version)).isValid

        SchemaVersion.entries.forEach { version ->
            describe("$version percent-encoded pointer fragments") {
                fun refTo(key: String, encodedToken: String) = """{"${'$'}defs":{"$key":{"type":"string"}},"${'$'}ref":"#/${'$'}defs/$encodedToken"}"""

                it("decodes a two-byte UTF-8 sequence (%C3%A9 is é)") {
                    isValid(refTo("é", "%C3%A9"), "\"x\"", version) shouldBe true
                    isValid(refTo("é", "%C3%A9"), "1", version) shouldBe false
                }

                it("decodes a four-byte UTF-8 sequence (%F0%9F%98%80 is an emoji)") {
                    isValid(refTo("😀", "%F0%9F%98%80"), "\"x\"", version) shouldBe true
                    isValid(refTo("😀", "%F0%9F%98%80"), "1", version) shouldBe false
                }

                it("decodes %25 to a literal percent sign") {
                    isValid(refTo("a%b", "a%25b"), "\"x\"", version) shouldBe true
                    isValid(refTo("a%b", "a%25b"), "1", version) shouldBe false
                }

                it("keeps a % that does not start two hex digits (no sign, no short escape)") {
                    isValid(refTo("%-1", "%-1"), "\"x\"", version) shouldBe true
                    isValid(refTo("%-1", "%-1"), "1", version) shouldBe false
                }

                it("leaves unencoded non-ASCII tokens as they are") {
                    isValid(refTo("é😀", "é😀"), "\"x\"", version) shouldBe true
                    isValid(refTo("é😀", "é😀"), "1", version) shouldBe false
                }

                it("mixes encoded and unencoded characters in one token") {
                    isValid(refTo("café ☕", "caf%C3%A9%20☕"), "\"x\"", version) shouldBe true
                    isValid(refTo("café ☕", "caf%C3%A9%20☕"), "1", version) shouldBe false
                }
            }
        }
    })
