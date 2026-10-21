package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * absoluteKeywordLocation: the canonical URI of the schema resource holding the failing keyword,
 * plus a JSON Pointer within that resource (JSON Schema 2020-12 §12.3.3). Unlike keywordLocation,
 * it follows $ref, $dynamicRef and $recursiveRef to where the keyword is actually written.
 */
class AbsoluteKeywordLocationTest :
    DescribeSpec({
        val root = "https://example.com/root"

        /** Every error in the result tree as keywordLocation to absoluteKeywordLocation, parents first. */
        fun locations(result: ValidationResult): List<Pair<String?, String?>> {
            fun flatten(errors: List<ValidationError>): List<ValidationError> = errors.flatMap { listOf(it) + flatten(it.causes) }
            return flatten(result.getErrors()).map { it.schemaPath to it.absoluteKeywordLocation }
        }

        fun validate(
            schemaJson: String,
            instanceJson: String,
            version: SchemaVersion = SchemaVersion.DRAFT_2020_12,
            baseUri: String = "",
            loader: ((String) -> JsonElement?)? = null,
        ): ValidationResult = JsonValidator(schemaLoader = loader)
            .validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson, version, baseUri))

        SchemaVersion.entries.forEach { version ->
            describe("$version") {
                it("locates a keyword that never crosses a reference in the root resource") {
                    locations(validate("""{"${'$'}id":"$root","type":"string"}""", "1", version)) shouldBe
                        listOf("/type" to "$root#/type")
                }

                it("follows a local \$ref to the keyword inside \$defs") {
                    val schema = """{"${'$'}id":"$root","properties":{"n":{"${'$'}ref":"#/${'$'}defs/p"}},"${'$'}defs":{"p":{"minimum":0}}}"""
                    locations(validate(schema, """{"n":-1}""", version)) shouldBe
                        listOf("/properties/n/\$ref/minimum" to "$root#/\$defs/p/minimum")
                }

                it("resolves an \$anchor reference to a JSON Pointer, not the anchor") {
                    val schema = """{"${'$'}id":"$root","${'$'}ref":"#pos","${'$'}defs":{"p":{"${'$'}anchor":"pos","minimum":0}}}"""
                    locations(validate(schema, "-1", version)) shouldBe
                        listOf("/\$ref/minimum" to "$root#/\$defs/p/minimum")
                }

                it("uses the canonical URI of a subschema with a relative \$id") {
                    val schema = """{"${'$'}id":"$root","${'$'}ref":"item","${'$'}defs":{"item":{"${'$'}id":"item","minimum":0}}}"""
                    locations(validate(schema, "-1", version)) shouldBe
                        listOf("/\$ref/minimum" to "https://example.com/item#/minimum")
                }

                it("follows a remote \$ref loaded through the schemaLoader") {
                    val remote = Json.parseToJsonElement("""{"${'$'}defs":{"p":{"minimum":0}}}""")
                    val loader: (String) -> JsonElement? = { uri -> if (uri == "https://example.com/other") remote else null }
                    val schema = """{"${'$'}id":"$root","${'$'}ref":"https://example.com/other#/${'$'}defs/p"}"""
                    locations(validate(schema, "-1", version, loader = loader)) shouldBe
                        listOf("/\$ref/minimum" to "https://example.com/other#/\$defs/p/minimum")
                }

                it("takes the innermost hop of a reference chain") {
                    val schema = """{"${'$'}id":"$root","${'$'}ref":"#/${'$'}defs/a","${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/b"},"b":{"type":"string"}}}"""
                    locations(validate(schema, "1", version)) shouldBe
                        listOf("/\$ref/\$ref/type" to "$root#/\$defs/b/type")
                }

                it("locates combiner errors and their causes under a reference") {
                    val schema = """{"${'$'}id":"$root","${'$'}ref":"#/${'$'}defs/a","${'$'}defs":{"a":{"anyOf":[{"type":"string"}]}}}"""
                    locations(validate(schema, "1", version)) shouldBe listOf(
                        "/\$ref/anyOf" to "$root#/\$defs/a/anyOf",
                        "/\$ref/anyOf/0" to "$root#/\$defs/a/anyOf/0",
                        "/\$ref/anyOf/0/type" to "$root#/\$defs/a/anyOf/0/type",
                    )
                }

                it("locates an unresolvable reference at the \$ref keyword itself") {
                    val schema = """{"${'$'}id":"$root","properties":{"n":{"${'$'}ref":"#/${'$'}defs/missing"}}}"""
                    locations(validate(schema, """{"n":1}""", version)) shouldBe
                        listOf("/properties/n/\$ref" to "$root#/properties/n/\$ref")
                }

                it("uses JsonSchema.baseUri when the schema has no \$id") {
                    val schema = """{"${'$'}ref":"#/${'$'}defs/p","${'$'}defs":{"p":{"minimum":0}}}"""
                    locations(validate(schema, "-1", version, baseUri = "https://example.com/base")) shouldBe
                        listOf("/\$ref/minimum" to "https://example.com/base#/\$defs/p/minimum")
                }

                it("omits it when the schema has no absolute URI") {
                    val schema = """{"${'$'}ref":"#/${'$'}defs/p","${'$'}defs":{"p":{"minimum":0}}}"""
                    locations(validate(schema, "-1", version)) shouldBe listOf("/\$ref/minimum" to null)
                }

                it("supports a URN \$id, which has no HTTP scheme") {
                    val urn = "urn:uuid:deadbeef-1234-ffff-ffff-4321feebdaed"
                    val schema = """{"${'$'}id":"$urn","minimum":30,"properties":{"foo":{"${'$'}ref":"$urn"}}}"""
                    locations(validate(schema, """{"foo":12}""", version)) shouldBe
                        listOf("/properties/foo/\$ref/minimum" to "$urn#/minimum")
                }

                it("supports a URN \$id with an r-component") {
                    val urn = "urn:example:foo-bar-baz-qux?+CCResolve:cc=uk"
                    val schema = """{"${'$'}id":"$urn","properties":{"foo":{"${'$'}ref":"#/${'$'}defs/bar"}},"${'$'}defs":{"bar":{"type":"string"}}}"""
                    locations(validate(schema, """{"foo":12}""", version)) shouldBe
                        listOf("/properties/foo/\$ref/type" to "$urn#/\$defs/bar/type")
                }

                it("omits it when the root \$id is relative and there is no base URI") {
                    locations(validate("""{"${'$'}id":"root.json","type":"string"}""", "1", version)) shouldBe listOf("/type" to null)
                }
            }
        }

        describe("dynamic references") {
            it("follows \$dynamicRef to the outermost resource with the dynamic anchor (2020-12)") {
                val schema = """
                    {"${'$'}id":"$root","${'$'}ref":"list",
                     "${'$'}defs":{
                       "item":{"${'$'}dynamicAnchor":"item","type":"string"},
                       "list":{"${'$'}id":"list","items":{"${'$'}dynamicRef":"#item"},"${'$'}defs":{"item":{"${'$'}dynamicAnchor":"item"}}}
                     }}
                """
                locations(validate(schema, "[1]", SchemaVersion.DRAFT_2020_12)) shouldBe
                    listOf("/\$ref/items/\$dynamicRef/type" to "$root#/\$defs/item/type")
            }

            it("follows \$recursiveRef to the resource with \$recursiveAnchor (2019-09)") {
                val schema = """{"${'$'}id":"https://example.com/tree","${'$'}recursiveAnchor":true,"type":"object","properties":{"child":{"${'$'}recursiveRef":"#"}}}"""
                locations(validate(schema, """{"child":1}""", SchemaVersion.DRAFT_2019_09)) shouldBe
                    listOf("/properties/child/\$recursiveRef/type" to "https://example.com/tree#/type")
            }
        }

        describe("ValidationError compatibility") {
            it("keeps the five-argument constructor, defaulting absoluteKeywordLocation to null") {
                val error = ValidationError("/x", "message", "type", "/type", emptyList())
                error.absoluteKeywordLocation shouldBe null
            }
        }
    })
