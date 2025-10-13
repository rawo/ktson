package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class Draft202012ValidationTest :
    DescribeSpec({
    val validator = JsonValidator()

    describe("Draft 2020-12: Type Validation") {
        it("should validate string type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(123), schema).isValid shouldBe false
            }
        }

        it("should validate integer type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "integer"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42.5), schema).isValid shouldBe false
            }
        }

        it("should validate number type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42.5), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("42"), schema).isValid shouldBe false
            }
        }

        it("should validate boolean type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "boolean"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(true), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(false), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("true"), schema).isValid shouldBe false
            }
        }

        it("should validate null type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "null"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonNull, schema).isValid shouldBe true
                validator.validate(JsonPrimitive(0), schema).isValid shouldBe false
            }
        }

        it("should validate object type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "object"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(buildJsonObject {}, schema).isValid shouldBe true
                validator.validate(buildJsonArray {}, schema).isValid shouldBe false
            }
        }

        it("should validate array type") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "array"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(buildJsonArray {}, schema).isValid shouldBe true
                validator.validate(buildJsonObject {}, schema).isValid shouldBe false
            }
        }

        it("should validate multiple types") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": ["string", "number"]}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(true), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: String Validation") {
        it("should validate minLength") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "minLength": 3}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("abc"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("ab"), schema).isValid shouldBe false
            }
        }

        it("should validate maxLength") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "maxLength": 5}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("hello world"), schema).isValid shouldBe false
            }
        }

        it("should validate pattern") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "pattern": "^[A-Z].*"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("Hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe false
            }
        }

        it("should validate email format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "email"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("user@example.com"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("invalid-email"), schema).isValid shouldBe false
            }
        }

        it("should validate date-time format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "date-time"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("2023-10-12T10:30:00Z"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("2023-10-12"), schema).isValid shouldBe false
            }
        }

        it("should validate uri format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "uri"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("https://example.com"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("not a uri"), schema).isValid shouldBe false
            }
        }

        it("should validate ipv4 format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "ipv4"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("192.168.1.1"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("999.999.999.999"), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Number Validation") {
        it("should validate minimum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "minimum": 10}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(10), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(15), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(5), schema).isValid shouldBe false
            }
        }

        it("should validate maximum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "maximum": 100}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(100), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(50), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(150), schema).isValid shouldBe false
            }
        }

        it("should validate exclusiveMinimum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "exclusiveMinimum": 10}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(11), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(10), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(9), schema).isValid shouldBe false
            }
        }

        it("should validate exclusiveMaximum") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "exclusiveMaximum": 100}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(99), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(100), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(101), schema).isValid shouldBe false
            }
        }

        it("should validate multipleOf") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "multipleOf": 5}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(10), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(15), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(7), schema).isValid shouldBe false
            }
        }

        it("should validate multipleOf with decimals") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "multipleOf": 0.5}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive(1.5), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(2.0), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(1.3), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Object Validation") {
        it("should validate properties") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "age": {"type": "integer"},
                            "email": {"type": "string", "format": "email"}
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                    put("email", "john@example.com")
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
                    put("age", "thirty")
                    put("email", "invalid")
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
                        "required": ["name", "age"]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
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
                            "name": {"type": "string"},
                            "age": {"type": "integer"}
                        },
                        "additionalProperties": false
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                    put("city", "NYC")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate additionalProperties with schema") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "integer"}
                        },
                        "additionalProperties": {"type": "string"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("id", 1)
                    put("name", "John")
                    put("city", "NYC")
                }

                val invalidObj = buildJsonObject {
                    put("id", 1)
                    put("age", 30)
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
                            "^str_": {"type": "string"},
                            "^num_": {"type": "number"}
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("str_name", "John")
                    put("num_age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("str_name", 123)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate minProperties and maxProperties") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "minProperties": 2,
                        "maxProperties": 4
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                    put("c", 3)
                }

                val invalidTooFew = buildJsonObject {
                    put("a", 1)
                }

                val invalidTooMany = buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                    put("c", 3)
                    put("d", 4)
                    put("e", 5)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidTooFew, schema).isValid shouldBe false
                validator.validate(invalidTooMany, schema).isValid shouldBe false
            }
        }

        it("should validate propertyNames") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "propertyNames": {
                            "type": "string",
                            "pattern": "^[A-Za-z_][A-Za-z0-9_]*$"
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("validName", 1)
                    put("valid_name_2", 2)
                }

                val invalidObj = buildJsonObject {
                    put("123invalid", 1)
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
                            "creditCard": ["billingAddress", "cvv"],
                            "billingAddress": ["creditCard"]
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("creditCard", "1234-5678")
                    put("billingAddress", "123 Main St")
                    put("cvv", "123")
                }

                val invalidObj = buildJsonObject {
                    put("creditCard", "1234-5678")
                }

                validator.validate(validObj, schema).isValid shouldBe true
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
                                "properties": {
                                    "billingAddress": {"type": "string"}
                                },
                                "required": ["billingAddress"]
                            }
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("creditCard", "1234-5678")
                    put("billingAddress", "123 Main St")
                }

                val invalidObj = buildJsonObject {
                    put("creditCard", "1234-5678")
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Array Validation") {
        it("should validate items as single schema") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "items": {"type": "integer"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
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

        it("should validate prefixItems (tuple validation)") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "prefixItems": [
                            {"type": "string"},
                            {"type": "integer"},
                            {"type": "boolean"}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validArray = buildJsonArray {
                    add("name")
                    add(30)
                    add(true)
                }

                val invalidArray = buildJsonArray {
                    add(30)
                    add("name")
                    add(true)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate prefixItems with additional items") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "prefixItems": [
                            {"type": "string"},
                            {"type": "integer"}
                        ],
                        "items": {"type": "boolean"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validArray = buildJsonArray {
                    add("name")
                    add(30)
                    add(true)
                    add(false)
                }

                val invalidArray = buildJsonArray {
                    add("name")
                    add(30)
                    add("not a boolean")
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidArray, schema).isValid shouldBe false
            }
        }

        it("should validate minItems and maxItems") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "minItems": 2,
                        "maxItems": 4
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validArray = buildJsonArray {
                    add(1)
                    add(2)
                    add(3)
                }

                val invalidTooFew = buildJsonArray {
                    add(1)
                }

                val invalidTooMany = buildJsonArray {
                    add(1)
                    add(2)
                    add(3)
                    add(4)
                    add(5)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidTooFew, schema).isValid shouldBe false
                validator.validate(invalidTooMany, schema).isValid shouldBe false
            }
        }

        it("should validate uniqueItems") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "array",
                        "uniqueItems": true
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

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
                        "contains": {
                            "type": "object",
                            "properties": {
                                "active": {"const": true}
                            },
                            "required": ["active"]
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validArray = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("active", false)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("active", true)
                        },
                    )
                }

                val invalidArray = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("active", false)
                        },
                    )
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
                        "contains": {"type": "integer", "minimum": 10},
                        "minContains": 2,
                        "maxContains": 4
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validArray = buildJsonArray {
                    add(5)
                    add(10)
                    add(20)
                    add(30)
                }

                val invalidTooFew = buildJsonArray {
                    add(5)
                    add(10)
                }

                val invalidTooMany = buildJsonArray {
                    add(10)
                    add(20)
                    add(30)
                    add(40)
                    add(50)
                }

                validator.validate(validArray, schema).isValid shouldBe true
                validator.validate(invalidTooFew, schema).isValid shouldBe false
                validator.validate(invalidTooMany, schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Const and Enum") {
        it("should validate const with primitive values") {
            runTest {
                val schema = JsonSchema.fromString("""{"const": "constant"}""", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("constant"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("other"), schema).isValid shouldBe false
            }
        }

        it("should validate const with object values") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "const": {"name": "John", "age": 30}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validObj = buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }

                val invalidObj = buildJsonObject {
                    put("name", "John")
                    put("age", 31)
                }

                validator.validate(validObj, schema).isValid shouldBe true
                validator.validate(invalidObj, schema).isValid shouldBe false
            }
        }

        it("should validate enum") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "enum": ["red", "green", "blue", 42, true, null]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                validator.validate(JsonPrimitive("red"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(true), schema).isValid shouldBe true
                validator.validate(JsonNull, schema).isValid shouldBe true
                validator.validate(JsonPrimitive("yellow"), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Combining Schemas") {
        it("should validate allOf") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "allOf": [
                            {"type": "integer"},
                            {"minimum": 10},
                            {"maximum": 100}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                validator.validate(JsonPrimitive(50), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(5), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(150), schema).isValid shouldBe false
            }
        }

        it("should validate anyOf") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "anyOf": [
                            {"type": "string", "maxLength": 5},
                            {"type": "integer", "minimum": 0}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(10), schema).isValid shouldBe true
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
                    SchemaVersion.DRAFT_2020_12,
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
                        "not": {"type": "null"}
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("hello"), schema).isValid shouldBe true
                validator.validate(JsonNull, schema).isValid shouldBe false
            }
        }

        it("should validate complex combination") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "allOf": [
                            {"type": "object"},
                            {
                                "anyOf": [
                                    {"required": ["name"]},
                                    {"required": ["id"]}
                                ]
                            }
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validWithName = buildJsonObject {
                    put("name", "John")
                }

                val validWithId = buildJsonObject {
                    put("id", 123)
                }

                val validWithBoth = buildJsonObject {
                    put("name", "John")
                    put("id", 123)
                }

                val invalid = buildJsonObject {
                    put("age", 30)
                }

                validator.validate(validWithName, schema).isValid shouldBe true
                validator.validate(validWithId, schema).isValid shouldBe true
                validator.validate(validWithBoth, schema).isValid shouldBe true
                validator.validate(invalid, schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Conditional Schemas") {
        it("should validate if-then-else") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "if": {
                            "properties": {
                                "type": {"const": "premium"}
                            }
                        },
                        "then": {
                            "properties": {
                                "price": {"minimum": 100}
                            },
                            "required": ["price"]
                        },
                        "else": {
                            "properties": {
                                "price": {"maximum": 99}
                            }
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validPremium = buildJsonObject {
                    put("type", "premium")
                    put("price", 150)
                }

                val invalidPremium = buildJsonObject {
                    put("type", "premium")
                    put("price", 50)
                }

                val validRegular = buildJsonObject {
                    put("type", "regular")
                    put("price", 50)
                }

                validator.validate(validPremium, schema).isValid shouldBe true
                validator.validate(invalidPremium, schema).isValid shouldBe false
                validator.validate(validRegular, schema).isValid shouldBe true
            }
        }

        it("should validate if-then without else") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "if": {
                            "properties": {
                                "premium": {"const": true}
                            }
                        },
                        "then": {
                            "required": ["subscriptionId"]
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validPremium = buildJsonObject {
                    put("premium", true)
                    put("subscriptionId", "SUB-123")
                }

                val invalidPremium = buildJsonObject {
                    put("premium", true)
                }

                val validNonPremium = buildJsonObject {
                    put("premium", false)
                }

                validator.validate(validPremium, schema).isValid shouldBe true
                validator.validate(invalidPremium, schema).isValid shouldBe false
                validator.validate(validNonPremium, schema).isValid shouldBe true
            }
        }
    }

    describe("Draft 2020-12: Boolean Schemas") {
        it("should validate true schema") {
            runTest {
                val schema = JsonSchema.fromString("true", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("anything"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(buildJsonObject {}, schema).isValid shouldBe true
                validator.validate(buildJsonArray {}, schema).isValid shouldBe true
                validator.validate(JsonNull, schema).isValid shouldBe true
            }
        }

        it("should validate false schema") {
            runTest {
                val schema = JsonSchema.fromString("false", SchemaVersion.DRAFT_2020_12)

                validator.validate(JsonPrimitive("anything"), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(42), schema).isValid shouldBe false
                validator.validate(buildJsonObject {}, schema).isValid shouldBe false
                validator.validate(buildJsonArray {}, schema).isValid shouldBe false
                validator.validate(JsonNull, schema).isValid shouldBe false
            }
        }

        it("should use boolean schemas in combinations") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "allOf": [
                            true,
                            {"type": "integer"}
                        ]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                validator.validate(JsonPrimitive(42), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("string"), schema).isValid shouldBe false
            }
        }
    }

    describe("Draft 2020-12: Schema Validation") {
        it("should validate schema structure") {
            runTest {
                val validSchema = JsonSchema.fromString(
                    """
                    {
                        "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "age": {"type": "integer", "minimum": 0}
                        },
                        "required": ["name"]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
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
                        "type": ["not", "an", "object", "or", "string"]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val result = validator.validateSchema(invalidSchema)
                result.isInvalid shouldBe true
            }
        }
    }

    describe("Draft 2020-12: Real-world Schemas") {
        it("should validate user profile schema") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "userId": {"type": "integer", "minimum": 1},
                            "username": {"type": "string", "minLength": 3, "maxLength": 20},
                            "email": {"type": "string", "format": "email"},
                            "age": {"type": "integer", "minimum": 13, "maximum": 120},
                            "interests": {
                                "type": "array",
                                "items": {"type": "string"},
                                "uniqueItems": true,
                                "minItems": 1
                            }
                        },
                        "required": ["userId", "username", "email"]
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val validProfile = buildJsonObject {
                    put("userId", 12345)
                    put("username", "john_doe")
                    put("email", "john@example.com")
                    put("age", 30)
                    putJsonArray("interests") {
                        add("coding")
                        add("music")
                        add("sports")
                    }
                }

                validator.validate(validProfile, schema).isValid shouldBe true
            }
        }

        it("should validate API response schema") {
            runTest {
                val schema = JsonSchema.fromString(
                    """
                    {
                        "type": "object",
                        "properties": {
                            "status": {"enum": ["success", "error"]},
                            "data": {"type": "object"},
                            "timestamp": {"type": "string", "format": "date-time"}
                        },
                        "required": ["status"],
                        "if": {
                            "properties": {"status": {"const": "error"}}
                        },
                        "then": {
                            "properties": {
                                "error": {
                                    "type": "object",
                                    "properties": {
                                        "code": {"type": "string"},
                                        "message": {"type": "string"}
                                    },
                                    "required": ["code", "message"]
                                }
                            },
                            "required": ["error"]
                        },
                        "else": {
                            "required": ["data"]
                        }
                    }
                    """.trimIndent(),
                    SchemaVersion.DRAFT_2020_12,
                )

                val successResponse = buildJsonObject {
                    put("status", "success")
                    putJsonObject("data") {
                        put("result", "OK")
                    }
                    put("timestamp", "2023-10-12T10:30:00Z")
                }

                val errorResponse = buildJsonObject {
                    put("status", "error")
                    putJsonObject("error") {
                        put("code", "E001")
                        put("message", "Something went wrong")
                    }
                }

                validator.validate(successResponse, schema).isValid shouldBe true
                validator.validate(errorResponse, schema).isValid shouldBe true
            }
        }
    }
})
