package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A schemaLoader may return one parsed document for several retrieval URIs. A relative $id inside it resolves against
 * the URI the document was reached under, so its registration must not outlive that hop: before #91, the first
 * context's base stuck to the nested resource and its references led into the first document's host.
 */
class SharedDocumentBaseTest :
    DescribeSpec({
        fun parse(text: String): JsonElement = Json.parseToJsonElement(text.replace('@', '$'))

        val shared = parse("""{"properties":{"n":{"@id":"child","@ref":"value"}}}""")

        fun validator(requested: MutableList<String> = mutableListOf()) = JsonValidator(schemaLoader = { uri ->
            requested += uri
            when (uri) {
                "https://a.example/root", "https://b.example/root" -> shared
                "https://a.example/value" -> parse("""{"type":"integer"}""")
                "https://b.example/value" -> parse("""{"minimum":10}""")
                else -> null
            }
        })

        SchemaVersion.entries.forEach { version ->
            describe("$version") {
                for (order in listOf(listOf("a", "b"), listOf("b", "a"))) {
                    val schema = JsonSchema.fromString(
                        """{"properties":{${order.joinToString(",") { """"$it":{"${'$'}ref":"https://$it.example/root"}""" }}}}""",
                        version,
                    )

                    it("resolves the nested relative \$id in each retrieval context, ${order.joinToString(" then ")}") {
                        val requested = mutableListOf<String>()
                        validator(requested).validate(parse("""{"a":{"n":1},"b":{"n":5}}"""), schema).isValid shouldBe false
                        requested.toSet() shouldBe setOf("https://a.example/root", "https://a.example/value", "https://b.example/root", "https://b.example/value")
                        validator().validate(parse("""{"a":{"n":1},"b":{"n":20}}"""), schema).isValid shouldBe true
                        validator().validate(parse("""{"a":{"n":1.5},"b":{"n":20}}"""), schema).isValid shouldBe false
                    }
                }
            }
        }
    })
