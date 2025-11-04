package org.ktson

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

class DepthLimitTest :
    FunSpec({

    test("deeply nested objects should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 5)

        // Create schema: {properties: {a: {properties: {a: ...}}}} nested 10 times
        var schema: JsonElement = buildJsonObject { put("type", "object") }
        repeat(10) {
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", schema)
                })
            }
        }

        // Create instance that matches the schema structure
        var instance: JsonElement = buildJsonObject {}
        repeat(8) {
            instance = buildJsonObject { put("a", instance) }
        }

        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
        result.getErrors().first().message shouldContain "5"
    }

    test("deeply nested arrays should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 5)

        // Create schema: {items: {items: ...}} nested 10 times
        var schema: JsonElement = buildJsonObject { put("type", "array") }
        repeat(10) {
            schema = buildJsonObject {
                put("type", "array")
                put("items", schema)
            }
        }

        // Create instance that triggers deep validation
        var instance: JsonElement = buildJsonArray {}
        repeat(8) {
            instance = buildJsonArray { add(instance) }
        }

        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("circular \$ref references should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 10)

        val schema = """
        {
            "type": "object",
            "properties": {
                "child": { "${'$'}ref": "#" }
            }
        }
        """

        // Create instance: {child: {child: {child: ...}}} nested 20 times
        var instance = buildJsonObject {}
        repeat(20) {
            instance = buildJsonObject { put("child", instance) }
        }

        val result = validator.validate(Json.encodeToJsonElement(instance), JsonSchema.fromString(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("complex combiners should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 10)

        // Create schema: {allOf: [{allOf: [...]}]} nested 20 times
        var schema: JsonElement = JsonPrimitive(true)
        repeat(20) {
            schema = buildJsonObject {
                put("allOf", buildJsonArray { add(schema) })
            }
        }

        val instance = JsonPrimitive(42)
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("anyOf combiner should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 15)

        // Create schema: {anyOf: [{anyOf: [...]}]} nested 30 times
        var schema: JsonElement = buildJsonObject { put("type", "string") }
        repeat(30) {
            schema = buildJsonObject {
                put("anyOf", buildJsonArray { add(schema) })
            }
        }

        val instance = JsonPrimitive("test")
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
        result.getErrors().first().message shouldContain "15"
    }

    test("oneOf combiner should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 12)

        // Create schema: {oneOf: [{oneOf: [...]}]} nested 25 times
        var schema: JsonElement = buildJsonObject { put("type", "number") }
        repeat(25) {
            schema = buildJsonObject {
                put("oneOf", buildJsonArray { add(schema) })
            }
        }

        val instance = JsonPrimitive(123)
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("not combiner should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 8)

        // Create schema: {not: {not: ...}} nested 20 times
        var schema: JsonElement = buildJsonObject { put("type", "null") }
        repeat(20) {
            schema = buildJsonObject {
                put("not", schema)
            }
        }

        val instance = JsonPrimitive(true)
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("patternProperties should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 5)

        // Create schema with nested patternProperties
        var schema: JsonElement = buildJsonObject { put("type", "string") }
        repeat(10) {
            schema = buildJsonObject {
                put("type", "object")
                put("patternProperties", buildJsonObject {
                    put(".*", schema)
                })
            }
        }

        var instance: JsonElement = buildJsonObject { put("x", "test") }
        repeat(8) {
            instance = buildJsonObject { put("prop", instance) }
        }

        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("reasonable depth should work fine") {
        val validator = JsonValidator(maxValidationDepth = 100)

        // Create schema nested 50 times (under limit)
        var schema: JsonElement = buildJsonObject { put("type", "object") }
        repeat(50) {
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", schema)
                })
            }
        }

        val instance = JsonObject(emptyMap())
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isValid shouldBe true
    }

    test("default depth limit should be reasonable (1000)") {
        val validator = JsonValidator()

        // Create moderately nested schema (100 levels)
        var schema: JsonElement = buildJsonObject { put("type", "object") }
        repeat(100) {
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", schema)
                })
            }
        }

        val instance = JsonObject(emptyMap())
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isValid shouldBe true
    }

    test("validation at exact depth limit should succeed") {
        val validator = JsonValidator(maxValidationDepth = 10)

        // Create schema that requires exactly 10 depth
        var schema: JsonElement = buildJsonObject { put("type", "number") }
        repeat(9) {
            // 9 + initial = 10 total depth
            schema = buildJsonObject {
                put("allOf", buildJsonArray { add(schema) })
            }
        }

        val instance = JsonPrimitive(42)
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isValid shouldBe true
    }

    test("validation at depth limit + 1 should fail") {
        val validator = JsonValidator(maxValidationDepth = 5)

        // Create schema that requires 6+ depth
        var schema: JsonElement = buildJsonObject { put("type", "number") }
        repeat(8) {
            // Ensure we exceed depth limit
            schema = buildJsonObject {
                put("allOf", buildJsonArray { add(schema) })
            }
        }

        val instance = JsonPrimitive(42)
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("mixed nesting types should respect depth limit") {
        val validator = JsonValidator(maxValidationDepth = 15)

        // Mix different nesting types: objects, arrays, combiners, refs
        val schema = """
        {
            "${'$'}defs": {
                "recursive": {
                    "type": "object",
                    "properties": {
                        "nested": {
                            "allOf": [{
                                "type": "array",
                                "items": {
                                    "${'$'}ref": "#/${'$'}defs/recursive"
                                }
                            }]
                        }
                    }
                }
            },
            "${'$'}ref": "#/${'$'}defs/recursive"
        }
        """

        // Create deeply nested instance
        var instance: JsonElement = buildJsonObject {}
        repeat(10) {
            instance = buildJsonObject {
                put("nested", buildJsonArray { add(instance) })
            }
        }

        val result = validator.validate(instance, JsonSchema.fromString(schema))

        result.isInvalid shouldBe true
        result.getErrors().first().message shouldContain "Maximum validation depth"
    }

    test("depth limit error should include keyword") {
        val validator = JsonValidator(maxValidationDepth = 5)

        var schema: JsonElement = buildJsonObject { put("type", "string") }
        repeat(10) {
            schema = buildJsonObject {
                put("allOf", buildJsonArray { add(schema) })
            }
        }

        val instance = JsonPrimitive("test")
        val result = validator.validate(instance, JsonSchema.fromElement(schema))

        result.isInvalid shouldBe true
        val error = result.getErrors().first()
        error.keyword shouldBe "depth"
    }
})
