package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.lang.management.ManagementFactory

/**
 * Error messages quote JSON values, but must stay small however large the value is (#48): a failing
 * const or enum check on a 200,000-character string must not produce a 200,000-character message.
 */
class MessagePreviewTest :
    DescribeSpec({
        fun message(instance: JsonElement, schema: JsonElement): String = JsonValidator().validate(instance, JsonSchema(schema)).getErrors().single().message

        describe("bounded rendering of values in messages") {
            it("keeps a const message small for a huge instance string") {
                val text = message(JsonPrimitive("a".repeat(200_000)), buildJsonObject { put("const", JsonPrimitive("fixed")) })
                text.length shouldBeLessThan 300
                text shouldStartWith "Value must be const: \"fixed\", but was: \"aaaa"
                text shouldEndWith "..."
            }

            it("keeps an enum message small for a huge allowed value") {
                val text = message(JsonPrimitive(1), buildJsonObject { put("enum", JsonArray(listOf(JsonPrimitive("b".repeat(100_000))))) })
                text.length shouldBeLessThan 300
            }

            it("keeps an enum message small for many allowed values") {
                val text = message(JsonPrimitive(0), buildJsonObject { put("enum", JsonArray((1..10_000).map { JsonPrimitive(it) })) })
                text.length shouldBeLessThan 300
                text shouldStartWith "Value 0 is not one of: [1,2,3"
            }

            it("keeps a const message small for a huge object") {
                val big = Json.parseToJsonElement((1..5_000).joinToString(",", "{", "}") { "\"k$it\":$it" })
                val text = message(big, buildJsonObject { put("const", JsonPrimitive(1)) })
                text.length shouldBeLessThan 300
                text shouldStartWith "Value must be const: 1, but was: {\"k1\":1,"
            }

            it("renders a deeply nested value without recursion") {
                var deep: JsonElement = JsonPrimitive(0)
                repeat(10_000) { deep = JsonArray(listOf(deep)) }
                val text = deep.preview()
                text shouldBe "[".repeat(MAX_PREVIEW_LENGTH) + "..."
            }

            it("cuts a huge member name before escaping it") {
                // Escaping the whole name first allocated about 31 MB here (#93)
                val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val validator = JsonValidator()
                val schema = JsonSchema(buildJsonObject { put("const", JsonObject(mapOf("\u0001".repeat(1_000_000) to JsonNull))) })
                val instance = JsonPrimitive(0)
                repeat(2) { validator.validate(instance, schema) }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                val text = validator.validate(instance, schema).getErrors().single().message
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 200_000L
                text shouldStartWith "Value must be const: {\"\\u0001\\u0001"
                text.length shouldBeLessThan 300
            }

            it("cuts member names at exactly the prefix of the full rendering") {
                val value = Json.parseToJsonElement("""{"a\"b\u0001c":1,"long name with \\ and \n":{"x":"y"}}""")
                val full = value.toString()
                for (budget in 0..full.length + 1) {
                    value.preview(budget) shouldBe if (full.length <= budget) full else full.take(budget) + "..."
                }
            }

            it("renders small values exactly as before") {
                message(JsonPrimitive(1), Json.parseToJsonElement("""{"enum":["a\"b",null,{"x":[1,2.5,true]}]}""")) shouldBe
                    """Value 1 is not one of: ["a\"b",null,{"x":[1,2.5,true]}]"""
                message(Json.parseToJsonElement("""{"a":[1,"x"]}"""), Json.parseToJsonElement("""{"const":"fixed"}""")) shouldBe
                    """Value must be const: "fixed", but was: {"a":[1,"x"]}"""
            }
        }
    })
