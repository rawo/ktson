package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** How $ref values are turned into targets: percent-decoding of pointer fragments and base URIs. */
class ReferenceResolutionTest :
    DescribeSpec({
        fun isValid(schemaJson: String, instanceJson: String, version: SchemaVersion, baseUri: String = ""): Boolean = JsonValidator().validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson, version, baseUri)).isValid

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

            describe("$version relative \$id resolved against the reference's own base (#34)") {
                // a/target (string) and b/target (integer) share the relative $id "target"; a $ref "target"
                // inside b/ means https://example.com/b/target, wherever a/target sits in the document.
                val schema = """
                    {"${'$'}id":"https://example.com/root",
                     "${'$'}defs":{
                       "a":{"${'$'}id":"a/","${'$'}defs":{"target":{"${'$'}id":"target","type":"string"}}},
                       "b":{"${'$'}id":"b/","${'$'}defs":{"target":{"${'$'}id":"target","type":"integer"}},"${'$'}ref":"target"}
                     },
                     "${'$'}ref":"b/"}
                """

                it("selects the resource under the reference's base, not the first one with the same raw \$id") {
                    isValid(schema, "1", version) shouldBe true
                    isValid(schema, "\"x\"", version) shouldBe false
                }
            }

            describe("$version base URI of the root schema") {
                /** Validates through a loader that serves {"type":"string"} for every URI and records what it was asked for. */
                fun requested(schemaJson: String, baseUri: String, vararg instances: String): List<String> {
                    val asked = mutableListOf<String>()
                    val validator = JsonValidator(schemaLoader = { uri ->
                        asked += uri
                        Json.parseToJsonElement("""{"type":"string"}""")
                    })
                    val schema = JsonSchema.fromString(schemaJson, version, baseUri)
                    instances.forEach { validator.validate(Json.parseToJsonElement(it), schema) }
                    return asked
                }

                it("resolves a relative \$ref against JsonSchema.baseUri when there is no \$id") {
                    requested("""{"${'$'}ref":"other.json"}""", "https://ex.com/schemas/root.json", "\"x\"") shouldBe
                        listOf("https://ex.com/schemas/other.json")
                }

                it("resolves a relative root \$id against baseUri before resolving refs") {
                    requested("""{"${'$'}id":"root.json","${'$'}ref":"other.json"}""", "https://ex.com/schemas/", "\"x\"") shouldBe
                        listOf("https://ex.com/schemas/other.json")
                }

                it("lets an absolute root \$id take precedence over baseUri") {
                    requested("""{"${'$'}id":"https://other.org/s/root.json","${'$'}ref":"x.json"}""", "https://ex.com/schemas/", "\"x\"") shouldBe
                        listOf("https://other.org/s/x.json")
                }

                it("finds the root by its baseUri in an absolute self-reference when it has no \$id (#53)") {
                    val schema = """{"${'$'}defs":{"n":{"type":"integer"}},"${'$'}ref":"https://ex.com/root#/${'$'}defs/n"}"""
                    isValid(schema, "1", version, "https://ex.com/root") shouldBe true
                    isValid(schema, "\"x\"", version, "https://ex.com/root") shouldBe false
                    requested(schema, "https://ex.com/root", "1", "\"x\"") shouldBe emptyList()
                }

                it("still passes the reference unchanged when there is neither \$id nor baseUri") {
                    requested("""{"${'$'}ref":"other.json"}""", "", "\"x\"") shouldBe listOf("other.json")
                }

                it("validates against the schema loaded from the resolved URI") {
                    val validator = JsonValidator(
                        schemaLoader = { uri -> if (uri == "https://ex.com/schemas/other.json") Json.parseToJsonElement("""{"type":"string"}""") else null },
                    )
                    val schema = JsonSchema.fromString("""{"${'$'}ref":"other.json"}""", version, "https://ex.com/schemas/root.json")
                    validator.validate(Json.parseToJsonElement("\"x\""), schema).isValid shouldBe true
                    validator.validate(Json.parseToJsonElement("1"), schema).isValid shouldBe false
                }

                it("resolves refs inside a loaded document's nested relative \$id the same way every time") {
                    val documents = mapOf(
                        "https://ex.com/lib.json" to """{"${'$'}defs":{"n":{"${'$'}id":"nested/n.json","${'$'}ref":"leaf.json"}}}""",
                        "https://ex.com/nested/leaf.json" to """{"type":"string"}""",
                    )
                    val asked = mutableListOf<String>()
                    val validator = JsonValidator(schemaLoader = { uri ->
                        asked += uri
                        documents[uri]?.let { Json.parseToJsonElement(it) }
                    })
                    val schema = JsonSchema.fromString("""{"${'$'}id":"https://ex.com/root.json","${'$'}ref":"lib.json#/${'$'}defs/n"}""", version)
                    repeat(2) {
                        validator.validate(Json.parseToJsonElement("\"x\""), schema).isValid shouldBe true
                        validator.validate(Json.parseToJsonElement("1"), schema).isValid shouldBe false
                    }
                    asked.distinct() shouldBe listOf("https://ex.com/lib.json", "https://ex.com/nested/leaf.json")
                }

                it("does not share one cached document between schemas with different base URIs") {
                    val asked = mutableListOf<String>()
                    val validator = JsonValidator(schemaLoader = { uri ->
                        asked += uri
                        Json.parseToJsonElement("true")
                    })
                    listOf("https://a.example/", "https://b.example/").forEach { base ->
                        validator.validate(Json.parseToJsonElement("1"), JsonSchema.fromString("""{"${'$'}ref":"other.json"}""", version, base))
                    }
                    asked shouldBe listOf("https://a.example/other.json", "https://b.example/other.json")
                }

                it("resolves a nested relative \$id against each validation's base URI when the schema element is reused (#37)") {
                    val asked = mutableListOf<String>()
                    val validator = JsonValidator(schemaLoader = { uri ->
                        asked += uri
                        Json.parseToJsonElement("true")
                    })
                    val element = Json.parseToJsonElement("""{"properties":{"x":{"${'$'}id":"child","${'$'}ref":"value"}}}""")
                    listOf("https://a.example/root", "https://b.example/root").forEach { base ->
                        validator.validate(Json.parseToJsonElement("""{"x":1}"""), JsonSchema(element, version, base))
                    }
                    asked shouldBe listOf("https://a.example/value", "https://b.example/value")
                }
            }
            describe("$version the resource a reference leads into") {
                it("evaluates a fragment of a relative URI in the resource the URI resolved to (#52)") {
                    // "target" under b/ is b/target (n: integer), not root/target (n: string)
                    val schema = """{"${'$'}id":"https://ex.com/root.json",
                        "${'$'}defs":{"a":{"${'$'}id":"target","${'$'}defs":{"n":{"type":"string"}}},
                                  "b":{"${'$'}id":"b/","${'$'}defs":{"t":{"${'$'}id":"target","${'$'}defs":{"n":{"type":"integer"},"use":{"${'$'}ref":"#/${'$'}defs/n"}}}},"${'$'}ref":"target#/${'$'}defs/use"}},
                        "${'$'}ref":"b/"}"""
                    isValid(schema, "1", version) shouldBe true
                    isValid(schema, "\"x\"", version) shouldBe false
                }

                it("applies a loaded document's own \$id before following refs from one of its fragments (#52)") {
                    val asked = mutableListOf<String>()
                    val remote = Json.parseToJsonElement("""{"${'$'}id":"https://canonical.example/schemas/root","${'$'}defs":{"x":{"${'$'}ref":"value"}}}""")
                    val validator = JsonValidator(schemaLoader = { uri ->
                        asked += uri
                        when (uri) {
                            "https://retrieval.example/root" -> remote
                            "https://canonical.example/schemas/value" -> Json.parseToJsonElement("""{"type":"integer"}""")
                            else -> Json.parseToJsonElement("false")
                        }
                    })
                    val schema = JsonSchema.fromString("""{"${'$'}ref":"https://retrieval.example/root#/${'$'}defs/x"}""", version)
                    validator.validate(Json.parseToJsonElement("1"), schema).isValid shouldBe true
                    validator.validate(Json.parseToJsonElement("\"x\""), schema).isValid shouldBe false
                    asked shouldBe listOf("https://retrieval.example/root", "https://canonical.example/schemas/value")
                }

                it("resolves a document served for two URIs against the URI each validation retrieved it from (#54)") {
                    val shared = Json.parseToJsonElement("""{"${'$'}ref":"value"}""")
                    val validator = JsonValidator(schemaLoader = { uri ->
                        when (uri) {
                            "https://a.example/root", "https://b.example/root" -> shared
                            "https://a.example/value" -> Json.parseToJsonElement("true")
                            "https://b.example/value" -> Json.parseToJsonElement("false")
                            else -> null
                        }
                    })
                    val a = JsonSchema.fromString("""{"${'$'}ref":"https://a.example/root"}""", version)
                    val b = JsonSchema.fromString("""{"${'$'}ref":"https://b.example/root"}""", version)
                    listOf(a, b, a).map { validator.validate(Json.parseToJsonElement("1"), it).isValid } shouldBe listOf(true, false, true)
                }

                it("resolves a document served for two URIs against each URI within one validation (#54)") {
                    val shared = Json.parseToJsonElement("""{"${'$'}ref":"value"}""")
                    val validator = JsonValidator(schemaLoader = { uri ->
                        when (uri) {
                            "https://a.example/root", "https://b.example/root" -> shared
                            "https://a.example/value" -> Json.parseToJsonElement("""{"type":"integer"}""")
                            "https://b.example/value" -> Json.parseToJsonElement("""{"minimum":10}""")
                            else -> null
                        }
                    })
                    val schema = JsonSchema.fromString("""{"properties":{"a":{"${'$'}ref":"https://a.example/root"},"b":{"${'$'}ref":"https://b.example/root"}}}""", version)
                    validator.validate(Json.parseToJsonElement("""{"a":1,"b":20}"""), schema).isValid shouldBe true
                    validator.validate(Json.parseToJsonElement("""{"a":1,"b":5}"""), schema).isValid shouldBe false
                    validator.validate(Json.parseToJsonElement("""{"a":1.5,"b":20}"""), schema).isValid shouldBe false
                }
            }
        }
    })
