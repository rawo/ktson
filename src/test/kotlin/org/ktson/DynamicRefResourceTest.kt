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
        }
    })
