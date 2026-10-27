package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** RFC 6901 §4: an array index is "0" or a digit 1-9 followed by digits; anything else does not resolve. */
class JsonPointerTest :
    DescribeSpec({
        val array = Json.parseToJsonElement("[10,20]")

        describe("array indices") {
            it("resolves canonical indices") {
                JsonPointer.resolve(array, "/0") shouldBe JsonPrimitive(10)
                JsonPointer.resolve(array, "/1") shouldBe JsonPrimitive(20)
            }

            listOf("/01", "/00", "/+1", "/-1", "/1a", "/ 1", "/1 ", "/").forEach { pointer ->
                it("does not resolve the non-canonical index '$pointer'") {
                    JsonPointer.resolve(array, pointer) shouldBe null
                }
            }

            it("does not resolve an index past the end") {
                JsonPointer.resolve(array, "/2") shouldBe null
                JsonPointer.resolve(array, "/99999999999") shouldBe null
            }
        }

        describe("object members") {
            it("still treats numeric-looking keys as ordinary names") {
                val document = Json.parseToJsonElement("""{"01":"a","+1":"b"}""")
                JsonPointer.resolve(document, "/01") shouldBe JsonPrimitive("a")
                JsonPointer.resolve(document, "/+1") shouldBe JsonPrimitive("b")
            }
        }

        describe("references") {
            it("does not resolve a \$ref fragment with a non-canonical index") {
                val schema = JsonSchema.fromString("""{"${'$'}ref":"#/prefixItems/01","prefixItems":[{"type":"string"},{"type":"integer"}]}""")
                JsonValidator().validate(JsonPrimitive(1), schema).isValid shouldBe false
            }
        }
    })
