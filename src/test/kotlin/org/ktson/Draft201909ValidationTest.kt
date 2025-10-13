package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class Draft201909ValidationTest :
    DescribeSpec({
    val validator = JsonValidator()

    describe("Draft 2019-09: Type Validation") {
        it("should validate string type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(123), schema).isValid shouldBe false
            }
        }

        it("should validate integer type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "integer"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42.5), schema).isValid shouldBe false
            }
        }

        it("should validate number type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42.5), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("42"), schema).isValid shouldBe false
            }
        }

        it("should validate boolean type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "boolean"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(true), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(false), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("true"), schema).isValid shouldBe false
            }
        }

        it("should validate null type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "null"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonNull, schema).isValid shouldBe true
                validator.validate(JsonPrimitive(0), schema).isValid shouldBe false
            }
        }

        it("should validate object type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "object"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(buildJsonObject {}, schema).isValid shouldBe true
                validator.validate(buildJsonArray {}, schema).isValid shouldBe false
            }
        }

        it("should validate array type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "array"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(buildJsonArray {}, schema).isValid shouldBe true
                validator.validate(buildJsonObject {}, schema).isValid shouldBe false
            }
        }

        it("should validate multiple types") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": ["string", "number"]}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(true), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: String Validation") {
        it("should validate minLength") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "minLength": 3}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("abc"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("ab"), schema).isValid shouldBe false
            }
        }

        it("should validate maxLength") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "maxLength": 5}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("hello world"), schema).isValid shouldBe false
            }
        }

        it("should validate pattern") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "pattern": "^[A-Z].*"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("Hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe false
            }
        }

        it("should validate email format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "email"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("user@example.com"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("invalid-email"), schema).isValid shouldBe false
            }
        }

        it("should validate date-time format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "date-time"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("2023-10-12T10:30:00Z"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("2023-10-12"), schema).isValid shouldBe false
            }
        }

        it("should validate uuid format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "uuid"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("550e8400-e29b-41d4-a716-446655440000"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("invalid-uuid"), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Number Validation") {
        it("should validate minimum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "minimum": 10}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(10), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(15), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(5), schema).isValid shouldBe false
            }
        }

        it("should validate maximum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "maximum": 100}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(100), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(50), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(150), schema).isValid shouldBe false
            }
        }

        it("should validate exclusiveMinimum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "exclusiveMinimum": 10}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(11), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(10), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(9), schema).isValid shouldBe false
            }
        }

        it("should validate exclusiveMaximum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "exclusiveMaximum": 100}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(99), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(100), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(101), schema).isValid shouldBe false
            }
        }

        it("should validate multipleOf") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "multipleOf": 5}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive(10), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(15), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(7), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Object Validation") {
        it("should validate properties") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "age": {"type": "integer"}
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
                    put("age", "thirty")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate required properties") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "age": {"type": "integer"}
                        },
                        "required": ["name"]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                }

                val invalidObj = buildJsonObject {
                    put("age", 30)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate additionalProperties false") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"}
                        },
                        "additionalProperties": false
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate additionalProperties schema") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"}
                        },
                        "additionalProperties": {"type": "number"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
                    put("age", "thirty")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate patternProperties") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "patternProperties": {
                            "^age_": {"type": "integer"}
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("age_min", 18)
                    put("age_max", 65)
                }

                val invalidObj = buildJsonObject {
                    put("age_min", "eighteen")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate minProperties") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "object", "minProperties": 2}""", SchemaVersion.DRAFT_2019_09)

                val validObj = buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                }

                val invalidObj = buildJsonObject {
                    put("a", 1)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate maxProperties") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "object", "maxProperties": 2}""", SchemaVersion.DRAFT_2019_09)

                val validObj = buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                }

                val invalidObj = buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                    put("c", 3)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate propertyNames") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "propertyNames": {
                            "pattern": "^[a-z]+$"
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("Name", "John")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate dependentRequired") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "dependentRequired": {
                            "creditCard": ["billingAddress"]
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj1 = buildJsonObject {
                    put("name", "John")
                }

                val validObj2 = buildJsonObject {
                    put("creditCard", "1234-5678-9012-3456")
                    put("billingAddress", "123 Main St")
                }

                val invalidObj = buildJsonObject {
                    put("creditCard", "1234-5678-9012-3456")
                }

                validator.validate(validObj1, schema).isValid shouldBe true
                validator.validate(validObj2, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate dependentSchemas") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "dependentSchemas": {
                            "creditCard": {
                                "required": ["billingAddress"]
                            }
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("creditCard", "1234-5678-9012-3456")
                    put("billingAddress", "123 Main St")
                }

                val invalidObj = buildJsonObject {
                    put("creditCard", "1234-5678-9012-3456")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Array Validation") {
        it("should validate items as single schema") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "items": {"type": "integer"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validArray = buildJsonArray {
                    add(1)
                    add(2)
                    add(3)
                }

                val invalidArray = buildJsonArray {
                    add(1)
                    add("two")
                    add(3)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate items as tuple") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "items": [
                            {"type": "string"},
                            {"type": "integer"}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validArray = buildJsonArray {
                    add("name")
                    add(30)
                }

                val invalidArray = buildJsonArray {
                    add(30)
                    add("name")
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate minItems") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "array", "minItems": 2}""", SchemaVersion.DRAFT_2019_09)

                val validArray = buildJsonArray {
                    add(1)
                    add(2)
                }

                val invalidArray = buildJsonArray {
                    add(1)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate maxItems") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "array", "maxItems": 2}""", SchemaVersion.DRAFT_2019_09)

                val validArray = buildJsonArray {
                    add(1)
                    add(2)
                }

                val invalidArray = buildJsonArray {
                    add(1)
                    add(2)
                    add(3)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate uniqueItems") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "array", "uniqueItems": true}""", SchemaVersion.DRAFT_2019_09)

                val validArray = buildJsonArray {
                    add(1)
                    add(2)
                    add(3)
                }

                val invalidArray = buildJsonArray {
                    add(1)
                    add(2)
                    add(1)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate contains") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "contains": {"type": "integer", "minimum": 5}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validArray = buildJsonArray {
                    add(1)
                    add(10)
                    add(3)
                }

                val invalidArray = buildJsonArray {
                    add(1)
                    add(2)
                    add(3)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate minContains and maxContains") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "contains": {"type": "integer", "minimum": 5},
                        "minContains": 2,
                        "maxContains": 3
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validArray = buildJsonArray {
                    add(1)
                    add(10)
                    add(20)
                }

                val invalidArrayTooFew = buildJsonArray {
                    add(1)
                    add(10)
                }

                val invalidArrayTooMany = buildJsonArray {
                    add(10)
                    add(20)
                    add(30)
                    add(40)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArrayTooFew, schema).isValid shouldBe false
                validator.validate(invalidArrayTooMany, schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Const and Enum") {
        it("should validate const") {
            runTest {
                val schema = JsonSchema.fromString("""{"const": "hello"}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("world"), schema).isValid shouldBe false
            }
        }

        it("should validate enum") {
            runTest {
                val schema = JsonSchema.fromString("""{"enum": ["red", "green", "blue"]}""", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("red"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("green"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("yellow"), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Combining Schemas") {
        it("should validate allOf") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "allOf": [
                            {"type": "object"},
                            {"properties": {"name": {"type": "string"}}, "required": ["name"]},
                            {"properties": {"age": {"type": "integer"}}}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("age", 30)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate anyOf") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "anyOf": [
                            {"type": "string"},
                            {"type": "integer"}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(true), schema).isValid shouldBe false
            }
        }

        it("should validate oneOf") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "oneOf": [
                            {"type": "integer", "multipleOf": 3},
                            {"type": "integer", "multipleOf": 5}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                validator.validate(JsonPrimitive(3), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(5), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(15), schema).isValid shouldBe false // matches both
                validator.validate(JsonPrimitive(7), schema).isValid shouldBe false // matches neither
            }
        }

        it("should validate not") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "not": {"type": "string"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Conditional Schemas") {
        it("should validate if-then-else") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "if": {
                            "properties": {"country": {"const": "USA"}}
                        },
                        "then": {
                            "properties": {"postalCode": {"pattern": "^[0-9]{5}$"}}
                        },
                        "else": {
                            "properties": {"postalCode": {"pattern": "^[A-Z0-9]+$"}}
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val validUSA = buildJsonObject {
                    put("country", "USA")
                    put("postalCode", "12345")
                }

                val invalidUSA = buildJsonObject {
                    put("country", "USA")
                    put("postalCode", "ABC123")
                }

                val validOther = buildJsonObject {
                    put("country", "Canada")
                    put("postalCode", "K1A0B1")
                }

                validator.validate(validUSA, schema).isValid shouldBe true
                validator.validate(invalidUSA, schema).isValid shouldBe false
                validator.validate(validOther, schema).isValid shouldBe true
            }
        }
    }

    describe("Draft 2019-09: Boolean Schemas") {
        it("should validate true schema") {
            runTest {
                val schema = JsonSchema.fromString("true", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("anything"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(buildJsonObject {}, schema).isValid shouldBe true
            }
        }

        it("should validate false schema") {
            runTest {
                val schema = JsonSchema.fromString("false", SchemaVersion.DRAFT_2019_09)

                validator.validate(JsonPrimitive("anything"), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe false
                validator.validate(buildJsonObject {}, schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2019-09: Schema Validation") {
        it("should validate schema structure") {
            runTest {
                val validSchema = JsonSchema.fromString(
                    """
                    {
                        "${'$'}schema": "https://json-schema.org/draft/2019-09/schema",
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"}
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val result = validator.validateSchema(validSchema)
                result.isValid shouldBe true
            }
        }

        it("should detect invalid schema structure") {
            runTest {
                val invalidSchema = JsonSchema.fromString(
                    """
                    {
                        "type": 123
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2019_09,
                )

                val result = validator.validateSchema(invalidSchema)
                result.isInvalid shouldBe true
            }
        }
    }
})
