package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

class InstanceLocationTest :
    DescribeSpec({
    val validator = JsonValidator()
    val cases = listOf(
        LocationCase("root type error", """{"type":"integer"}""", "\"x\"", ""),
        LocationCase("false schema", "false", "1", ""),
        LocationCase("nested object", """{"properties":{"user":{"properties":{"age":{"type":"integer"}}}}}""", """{"user":{"age":"x"}}""", "/user/age"),
        LocationCase("array item", """{"items":{"type":"integer"}}""", """[1,"x"]""", "/1"),
        LocationCase("mixed objects and arrays", """{"properties":{"users":{"items":{"properties":{"roles":{"items":{"type":"integer"}}}}}}}""", """{"users":[{"roles":[1,2,"x"]}]}""", "/users/0/roles/2"),
        LocationCase("pattern property", """{"patternProperties":{"^a/":{"type":"integer"}}}""", """{"a/b":"x"}""", "/a~1b"),
        LocationCase("additional property schema", """{"properties":{"outer":{"additionalProperties":{"type":"integer"}}}}""", """{"outer":{"a/b":"x"}}""", "/outer/a~1b"),
        LocationCase("required property owner", """{"properties":{"outer":{"required":["missing"]}}}""", """{"outer":{}}""", "/outer"),
        LocationCase("additional property owner", """{"properties":{"outer":{"additionalProperties":false}}}""", """{"outer":{"a/b":1}}""", "/outer"),
        LocationCase("unevaluated property owner", """{"properties":{"outer":{"unevaluatedProperties":false}}}""", """{"outer":{"a/b":1}}""", "/outer"),
        LocationCase("unevaluated property schema", """{"properties":{"outer":{"unevaluatedProperties":{"type":"integer"}}}}""", """{"outer":{"a/b":"x"}}""", "/outer/a~1b"),
        LocationCase("unevaluated item owner", """{"properties":{"outer":{"unevaluatedItems":false}}}""", """{"outer":[1]}""", "/outer"),
        LocationCase("unevaluated item schema", """{"properties":{"outer":{"unevaluatedItems":{"type":"integer"}}}}""", """{"outer":["x"]}""", "/outer/0"),
        LocationCase("prefix item", """{"prefixItems":[{"type":"integer"}]}""", """["x"]""", "/0"),
        LocationCase("items after prefix", """{"prefixItems":[true],"items":{"type":"integer"}}""", """[1,"x"]""", "/1"),
        LocationCase("tuple item", """{"items":[true,{"type":"integer"}]}""", """[1,"x"]""", "/1"),
        LocationCase("additional item prohibition", """{"items":[true],"additionalItems":false}""", "[1,2]", "/1"),
        LocationCase("additional item schema", """{"items":[true],"additionalItems":{"type":"integer"}}""", """[1,"x"]""", "/1"),
        LocationCase("contains owner", """{"properties":{"outer":{"contains":{"type":"integer"}}}}""", """{"outer":["x"]}""", "/outer"),
        LocationCase("dependent schema", """{"dependentSchemas":{"trigger":{"properties":{"a/b":{"type":"integer"}}}}}""", """{"trigger":true,"a/b":"x"}""", "/a~1b"),
        LocationCase("then branch", """{"if":true,"then":{"properties":{"a/b":{"type":"integer"}}}}""", """{"a/b":"x"}""", "/a~1b"),
        LocationCase("else branch", """{"if":false,"else":{"properties":{"a/b":{"type":"integer"}}}}""", """{"a/b":"x"}""", "/a~1b"),
        LocationCase("local reference", """{"${'$'}defs":{"integer":{"type":"integer"}},"properties":{"a/b":{"${'$'}ref":"#/${'$'}defs/integer"}}}""", """{"a/b":"x"}""", "/a~1b"),
        LocationCase("not owner", """{"properties":{"outer":{"not":{"type":"integer"}}}}""", """{"outer":1}""", "/outer"),
    )

    SchemaVersion.entries.forEach { version ->
        describe("${version.name} instance locations") {
            cases.forEach { case ->
                it(case.description) {
                    val instance = Json.parseToJsonElement(case.instance)
                    val error = validator.validate(instance, JsonSchema.fromString(case.schema, version)).getErrors().single()
                    error.instanceLocation shouldBe case.location
                    // Every reported location must resolve to a value in the original instance.
                    (JsonPointer.resolve(instance, error.instanceLocation) != null) shouldBe true
                }
            }

            val keys = listOf("a.b", "a[0]", "a/b", "a~b", "~1", "~/", "", "0", "01", "a%20b", "#", "żółć💩")
            keys.forEach { key ->
                it("round-trips property key ${JsonPrimitive(key)}") {
                    val instance = buildJsonObject { put(key, "x") }
                    val schema = JsonSchema.fromElement(buildJsonObject {
                        putJsonObject("properties") { putJsonObject(key) { put("type", "integer") } }
                    }, version)
                    val error = validator.validate(instance, schema).getErrors().single()
                    error.instanceLocation shouldBe "/${JsonPointer.encodeToken(key)}"
                    JsonPointer.resolve(instance, error.instanceLocation) shouldBe JsonPrimitive("x")
                }
            }

            it("distinguishes a dotted key from nested properties") {
                val flat = validator.validate("""{"a.b":"x"}""", """{"properties":{"a.b":{"type":"integer"}}}""", version).getErrors().single()
                val nested = validator.validate("""{"a":{"b":"x"}}""", """{"properties":{"a":{"properties":{"b":{"type":"integer"}}}}}""", version).getErrors().single()
                flat.instanceLocation shouldBe "/a.b"
                nested.instanceLocation shouldBe "/a/b"
            }

            listOf("false", """{"pattern":"^ok"}""", """{"anyOf":[false,false]}""").forEach { nameSchema ->
                it("reports the owning object and failed key for propertyNames $nameSchema") {
                    val result = validator.validate("""{"outer":{"bad/key":1}}""", """{"properties":{"outer":{"propertyNames":$nameSchema}}}""", version)
                    val error = result.getErrors().single()
                    error.message shouldContain "bad/key"
                    errorTree(error).forEach { it.instanceLocation shouldBe "/outer" }
                    error.schemaPath shouldContain "/properties/outer/propertyNames"
                }
            }

            it("reports the root object for root propertyNames failures") {
                val error = validator.validate("""{"bad/key":1}""", """{"propertyNames":false}""", version).getErrors().single()
                error.instanceLocation shouldBe ""
                error.message shouldContain "bad/key"
            }

            listOf("allOf", "anyOf", "oneOf").forEach { combiner ->
                it("keeps instance locations throughout $combiner causes") {
                    val error = validator.validate(
                        """{"outer":{"a/b":"x"}}""",
                        """{"properties":{"outer":{"$combiner":[{"properties":{"a/b":{"type":"integer"}}}]}}}""",
                        version,
                    ).getErrors().single()
                    error.instanceLocation shouldBe "/outer"
                    val tree = errorTree(error)
                    tree.last().instanceLocation shouldBe "/outer/a~1b"
                    tree.last().schemaPath shouldBe "/properties/outer/$combiner/0/properties/a~1b/type"
                }
            }

            it("keeps instance locations across external references") {
                val remoteValidator = JsonValidator(schemaLoader = { uri ->
                    if (uri == "https://example.com/integer") Json.parseToJsonElement("""{"type":"integer"}""") else null
                })
                val error = remoteValidator.validate("""{"a/b":"x"}""", """{"properties":{"a/b":{"${'$'}ref":"https://example.com/integer"}}}""", version).getErrors().single()
                error.instanceLocation shouldBe "/a~1b"
                error.schemaPath shouldBe "/properties/a~1b/${'$'}ref/type"
            }

            it("reports a pointer when the depth limit is reached") {
                val error = JsonValidator(maxValidationDepth = 0).validate("""{"a/b":1}""", """{"properties":{"a/b":true}}""", version).getErrors().single()
                error.keyword shouldBe "depth"
                error.instanceLocation shouldBe "/a~1b"
            }
        }
    }

    describe("schema structure error locations") {
        val schemas = listOf(
            """{"type":"invalid"}""" to "",
            """{"properties":{"a/b":{"type":"invalid"}}}""" to "/properties/a~1b",
            """{"patternProperties":{"^a/b":{"type":"invalid"}}}""" to "/patternProperties/^a~1b",
            """{"dependentSchemas":{"a~b":{"type":"invalid"}}}""" to "/dependentSchemas/a~0b",
            """{"allOf":[true,{"type":"invalid"}]}""" to "/allOf/1",
            """{"prefixItems":[{"type":"invalid"}]}""" to "/prefixItems/0",
            """{"not":{"properties":{"":{"type":"invalid"}}}}""" to "/not/properties/",
        )
        schemas.forEach { (text, location) ->
            it("locates the schema node at ${JsonPrimitive(location)}") {
                val schema = JsonSchema.fromString(text)
                val error = validator.validateSchema(schema).getErrors().single()
                error.instanceLocation shouldBe location
                error.schemaPath shouldBe location
                (JsonPointer.resolve(schema.schema, location) != null) shouldBe true
            }
        }
    }

    describe("ValidationError API") {
        val legacyPaths = listOf(
            "" to "",
            "/user/age" to "user.age",
            "/users/0/roles/2" to "users[0].roles[2]",
            "/0" to "[0]",
            "/01" to "01",
            "/a~1b/a~0b" to "a/b.a~b",
            "/~01" to "~1",
            "/a.b" to "a.b",
            "/a/b" to "a.b",
            "/" to "",
            "/a//b" to "a..b",
        )
        legacyPaths.forEach { (location, legacy) ->
            it("projects ${JsonPrimitive(location)} to legacy notation") {
                @Suppress("DEPRECATION")
                val path = ValidationError(instanceLocation = location, message = "error").path
                path shouldBe legacy
            }
        }

        it("uses instanceLocation in constructor copy and destructuring") {
            val error = ValidationError(instanceLocation = "/a~1b", message = "error")
            val copy = error.copy(instanceLocation = "/c")
            val (location) = copy
            location shouldBe "/c"
            error.instanceLocation shouldBe "/a~1b"
        }

        it("prints canonical pointers in parent and child errors") {
            val error = ValidationError("/outer", "parent", causes = listOf(ValidationError("/outer/a~1b", "child")))
            error.toString() shouldContain "Validation error at '/outer': parent"
            error.toString() shouldContain "\n  Validation error at '/outer/a~1b': child"
        }
    }

    describe("JSON Pointer empty-key resolution") {
        val instance = Json.parseToJsonElement("""{"":{"":"value"}}""")
        it("resolves the empty pointer to the root") { JsonPointer.resolve(instance, "") shouldBe instance }
        it("resolves the empty fragment to the root") { JsonPointer.resolve(instance, "#") shouldBe instance }
        it("resolves slash to an empty member") { JsonPointer.resolve(instance, "/") shouldBe instance.jsonObject[""] }
        it("resolves a fragment with an empty member") { JsonPointer.resolve(instance, "#/") shouldBe instance.jsonObject[""] }
        it("preserves consecutive empty tokens") { JsonPointer.resolve(instance, "//") shouldBe JsonPrimitive("value") }
    }
})

private data class LocationCase(val description: String, val schema: String, val instance: String, val location: String)

private fun errorTree(error: ValidationError): List<ValidationError> = listOf(error) + error.causes.flatMap { errorTree(it) }
