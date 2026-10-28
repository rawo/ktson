package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A $dynamicRef target is evaluated in its own resource (#35): local refs (#/...) inside it resolve
 * in the document holding the target, not in the document holding the $dynamicRef.
 */
class DynamicRefResourceTest :
    DescribeSpec({
        fun isValid(schemaJson: String, instanceJson: String, loader: ((String) -> JsonElement?)? = null): Boolean = JsonValidator(schemaLoader = loader).validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson)).isValid

        describe("DRAFT_2020_12 \$dynamicRef") {
            it("evaluates an external target in its own document") {
                val remote = Json.parseToJsonElement("""{"${'$'}defs":{"n":{"type":"integer"}},"${'$'}ref":"#/${'$'}defs/n"}""")
                val loader: (String) -> JsonElement? = { uri -> if (uri == "https://example.com/remote") remote else null }
                val schema = """{"${'$'}id":"https://example.com/root","${'$'}dynamicRef":"https://example.com/remote"}"""
                isValid(schema, "1", loader) shouldBe true
                isValid(schema, "\"x\"", loader) shouldBe false
            }

            it("evaluates a target found through the dynamic scope in the resource that declares the anchor") {
                // The dynamic anchor "item" is overridden by the root resource, whose item refers to #/$defs/str:
                // that must be the root's str (string), not the list resource's str (integer).
                val schema = """
                    {"${'$'}id":"https://example.com/root","${'$'}ref":"list",
                     "${'$'}defs":{
                       "item":{"${'$'}dynamicAnchor":"item","${'$'}ref":"#/${'$'}defs/str"},
                       "str":{"type":"string"},
                       "list":{"${'$'}id":"list","items":{"${'$'}dynamicRef":"#item"},
                               "${'$'}defs":{"item":{"${'$'}dynamicAnchor":"item"},"str":{"type":"integer"}}}
                     }}
                """
                isValid(schema, """["x"]""") shouldBe true
                isValid(schema, "[1]") shouldBe false
            }

            it("puts a root without \$id in the dynamic scope (#64)") {
                val remote = Json.parseToJsonElement(
                    """{"${'$'}id":"https://example.com/remote","${'$'}dynamicAnchor":"node","properties":{"child":{"${'$'}dynamicRef":"#node"}}}""",
                )
                val outer = """{"${'$'}dynamicAnchor":"node","${'$'}ref":"https://example.com/remote","required":["must"]}"""
                listOf("https://example.com/root", "").forEach { base ->
                    val validator = JsonValidator(schemaLoader = { remote })
                    validator.validate(Json.parseToJsonElement("""{"must":1,"child":{}}"""), JsonSchema.fromString(outer, SchemaVersion.DRAFT_2020_12, base)).isValid shouldBe false
                    validator.validate(Json.parseToJsonElement("""{"must":1,"child":{"must":2}}"""), JsonSchema.fromString(outer, SchemaVersion.DRAFT_2020_12, base)).isValid shouldBe true
                }
            }

            it("puts a loaded document without \$id in the dynamic scope (#64)") {
                // root -> middle (no $id, declares the anchor) -> leaf: the middle resource is the outermost "node"
                val documents = mapOf(
                    "https://example.com/middle" to """{"${'$'}dynamicAnchor":"node","${'$'}ref":"leaf","required":["m"]}""",
                    "https://example.com/leaf" to """{"${'$'}id":"https://example.com/leaf","${'$'}dynamicAnchor":"node","properties":{"child":{"${'$'}dynamicRef":"#node"}}}""",
                )
                val loader: (String) -> JsonElement? = { uri -> documents[uri]?.let { Json.parseToJsonElement(it) } }
                val schema = """{"${'$'}id":"https://example.com/root","${'$'}ref":"middle"}"""
                isValid(schema, """{"m":1,"child":{}}""", loader) shouldBe false
                isValid(schema, """{"m":1,"child":{"m":2}}""", loader) shouldBe true
            }

            listOf("#node", "#%6Eode", "#%6E%6F%64%65").forEach { fragment ->
                it("lets the outer dynamic anchor override a \$dynamicRef to '$fragment' (#60)") {
                    // %6E is an unreserved "n": every spelling names anchor "node", so the root's override applies.
                    val remote = Json.parseToJsonElement(
                        """{"${'$'}id":"https://example.com/remote","${'$'}dynamicAnchor":"node","properties":{"child":{"${'$'}dynamicRef":"$fragment"}}}""",
                    )
                    val schema = """{"${'$'}id":"https://example.com/root","${'$'}dynamicAnchor":"node","${'$'}ref":"remote","required":["required"]}"""
                    isValid(schema, """{"required":true,"child":{}}""") { remote } shouldBe false
                    isValid(schema, """{"required":true,"child":{"required":1}}""") { remote } shouldBe true
                }
            }
        }
    })
