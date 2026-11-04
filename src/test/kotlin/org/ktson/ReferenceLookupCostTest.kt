package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.management.ManagementFactory

/**
 * A reference applied to every item of an array finds its target once per validation: anchor and `$id` searches are
 * remembered, so a 2,000-definition schema is not walked again for each of 100 items (#92).
 */
class ReferenceLookupCostTest :
    DescribeSpec({
        val definitions = buildJsonObject {
            repeat(2000) { put("unused$it", buildJsonObject { put("title", "filler") }) }
            put("target", buildJsonObject { put("${'$'}anchor", "target") })
            put("byId", buildJsonObject { put("${'$'}id", "byid.json") })
        }
        val input = JsonArray(List(100) { JsonNull })
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        // Before: 70 MB, 128 MB and 64 MB; a JSON Pointer to the same target: about 0.12 MB
        for (ref in listOf("#target", "https://example.com/remote", "byid.json")) {
            it("resolves $ref for 100 items without walking the schema for each") {
                val schema = JsonSchema(
                    buildJsonObject {
                        put("${'$'}id", "https://example.com/root")
                        put("${'$'}defs", definitions)
                        put("items", buildJsonObject { put("${'$'}ref", ref) })
                    },
                )
                var loads = 0
                val validator = JsonValidator(schemaLoader = {
                    loads++
                    JsonPrimitive(true)
                })
                repeat(3) { validator.validate(input, schema).isValid shouldBe true }
                val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                validator.validate(input, schema).isValid shouldBe true
                (threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) shouldBeLessThan 10_000_000L
                loads shouldBe if (ref.startsWith("https:")) 1 else 0
            }
        }

        it("keeps the same anchor name in different resources apart") {
            val schema = JsonSchema.fromString(
                """
                {"${'$'}id":"https://example.com/root",
                 "properties":{"a":{"${'$'}ref":"a.json#t"},"b":{"${'$'}ref":"b.json#t"},"c":{"${'$'}ref":"#t"}},
                 "${'$'}defs":{
                   "a":{"${'$'}id":"a.json","${'$'}defs":{"t":{"${'$'}anchor":"t","type":"integer"}}},
                   "b":{"${'$'}id":"b.json","${'$'}defs":{"t":{"${'$'}anchor":"t","type":"string"}}},
                   "c":{"${'$'}anchor":"t","type":"boolean"}}}
                """.trimIndent(),
            )
            val validator = JsonValidator()
            validator.validate(Json.parseToJsonElement("""{"a":1,"b":"x","c":true}"""), schema).isValid shouldBe true
            validator.validate(Json.parseToJsonElement("""{"a":"x","b":"x","c":true}"""), schema).isValid shouldBe false
            validator.validate(Json.parseToJsonElement("""{"a":1,"b":1,"c":true}"""), schema).isValid shouldBe false
            validator.validate(Json.parseToJsonElement("""{"a":1,"b":"x","c":1}"""), schema).isValid shouldBe false
        }
    })
