package org.ktson

import kotlinx.serialization.json.*

/**
 * Example usage of the KtSON JSON Schema validator
 */
fun main() {
    // Create a validator instance
    val validator = JsonValidator()

    println("=== KtSON JSON Schema Validator Examples ===\n")

    // Example 1: Basic type validation
    example1BasicValidation(validator)

    // Example 2: Object validation with required properties
    example2ObjectValidation(validator)

    // Example 3: Array validation
    example3ArrayValidation(validator)

    // Example 4: String format validation
    example4FormatValidation(validator)

    // Example 5: Combining schemas
    example5CombiningSchemas(validator)

    // Example 6: Conditional validation
    example6ConditionalValidation(validator)

    // Example 7: Schema validation
    example7SchemaValidation(validator)
}

fun example1BasicValidation(validator: JsonValidator) {
    println("Example 1: Basic Type Validation")
    println("-".repeat(50))

    val schema = JsonSchema.fromString(
        """
        {
            "type": "string",
            "minLength": 3,
            "maxLength": 10
        }
        """.trimIndent(),
    )

    val validInstance = JsonPrimitive("hello")
    val invalidInstance = JsonPrimitive("hi")

    val result1 = validator.validate(validInstance, schema)
    println("Validating 'hello': ${if (result1.isValid) "✓ Valid" else "✗ Invalid"}")

    val result2 = validator.validate(invalidInstance, schema)
    println("Validating 'hi': ${if (result2.isValid) "✓ Valid" else "✗ Invalid"}")
    if (result2.isInvalid) {
        result2.getErrors().forEach { println("  Error: ${it.message}") }
    }
    println()
}

fun example2ObjectValidation(validator: JsonValidator) {
    println("Example 2: Object Validation")
    println("-".repeat(50))

    val schema = JsonSchema.fromString(
        """
        {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "age": {"type": "integer", "minimum": 0},
                "email": {"type": "string", "format": "email"}
            },
            "required": ["name", "email"]
        }
        """.trimIndent(),
    )

    val validUser = buildJsonObject {
        put("name", "John Doe")
        put("age", 30)
        put("email", "john@example.com")
    }

    val invalidUser = buildJsonObject {
        put("name", "Jane")
        put("age", -5)
    }

    val result1 = validator.validate(validUser, schema)
    println("Valid user: ${if (result1.isValid) "✓ Valid" else "✗ Invalid"}")

    val result2 = validator.validate(invalidUser, schema)
    println("Invalid user: ${if (result2.isValid) "✓ Valid" else "✗ Invalid"}")
    if (result2.isInvalid) {
        result2.getErrors().forEach { println("  Error at '${it.path}': ${it.message}") }
    }
    println()
}

fun example3ArrayValidation(validator: JsonValidator) {
    println("Example 3: Array Validation")
    println("-".repeat(50))

    val schema = JsonSchema.fromString(
        """
        {
            "type": "array",
            "items": {"type": "integer"},
            "minItems": 2,
            "uniqueItems": true
        }
        """.trimIndent(),
    )

    val validArray = buildJsonArray {
        add(1)
        add(2)
        add(3)
    }

    val invalidArray = buildJsonArray {
        add(1)
        add(1)
    }

    val result1 = validator.validate(validArray, schema)
    println("Valid array [1,2,3]: ${if (result1.isValid) "✓ Valid" else "✗ Invalid"}")

    val result2 = validator.validate(invalidArray, schema)
    println("Invalid array [1,1]: ${if (result2.isValid) "✓ Valid" else "✗ Invalid"}")
    if (result2.isInvalid) {
        result2.getErrors().forEach { println("  Error: ${it.message}") }
    }
    println()
}

fun example4FormatValidation(validator: JsonValidator) {
    println("Example 4: Format Validation")
    println("-".repeat(50))

    val emailSchema = JsonSchema.fromString("""{"type": "string", "format": "email"}""")
    val uuidSchema = JsonSchema.fromString("""{"type": "string", "format": "uuid"}""")
    val dateTimeSchema = JsonSchema.fromString("""{"type": "string", "format": "date-time"}""")

    val validEmail = validator.validate(JsonPrimitive("user@example.com"), emailSchema)
    println("Email 'user@example.com': ${if (validEmail.isValid) "✓ Valid" else "✗ Invalid"}")

    val invalidEmail = validator.validate(JsonPrimitive("invalid-email"), emailSchema)
    println("Email 'invalid-email': ${if (invalidEmail.isValid) "✓ Valid" else "✗ Invalid"}")

    val validUuid = validator.validate(
        JsonPrimitive("550e8400-e29b-41d4-a716-446655440000"),
        uuidSchema,
    )
    println("UUID: ${if (validUuid.isValid) "✓ Valid" else "✗ Invalid"}")

    val validDateTime = validator.validate(
        JsonPrimitive("2023-10-12T10:30:00Z"),
        dateTimeSchema,
    )
    println("DateTime: ${if (validDateTime.isValid) "✓ Valid" else "✗ Invalid"}")
    println()
}

fun example5CombiningSchemas(validator: JsonValidator) {
    println("Example 5: Combining Schemas (allOf, anyOf, oneOf)")
    println("-".repeat(50))

    val allOfSchema = JsonSchema.fromString(
        """
        {
            "allOf": [
                {"type": "integer"},
                {"minimum": 10},
                {"maximum": 100}
            ]
        }
        """.trimIndent(),
    )

    val result1 = validator.validate(JsonPrimitive(50), allOfSchema)
    println("allOf validation (50): ${if (result1.isValid) "✓ Valid" else "✗ Invalid"}")

    val result2 = validator.validate(JsonPrimitive(5), allOfSchema)
    println("allOf validation (5): ${if (result2.isValid) "✓ Valid" else "✗ Invalid"}")

    val oneOfSchema = JsonSchema.fromString(
        """
        {
            "oneOf": [
                {"type": "integer", "multipleOf": 3},
                {"type": "integer", "multipleOf": 5}
            ]
        }
        """.trimIndent(),
    )

    val result3 = validator.validate(JsonPrimitive(3), oneOfSchema)
    println("oneOf validation (3): ${if (result3.isValid) "✓ Valid" else "✗ Invalid"}")

    val result4 = validator.validate(JsonPrimitive(15), oneOfSchema)
    println("oneOf validation (15 - matches both): ${if (result4.isValid) "✓ Valid" else "✗ Invalid"}")
    println()
}

fun example6ConditionalValidation(validator: JsonValidator) {
    println("Example 6: Conditional Validation (if-then-else)")
    println("-".repeat(50))

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
    )

    val usAddress = buildJsonObject {
        put("country", "USA")
        put("postalCode", "12345")
    }

    val canadianAddress = buildJsonObject {
        put("country", "Canada")
        put("postalCode", "K1A0B1")
    }

    val result1 = validator.validate(usAddress, schema)
    println("US address with zip 12345: ${if (result1.isValid) "✓ Valid" else "✗ Invalid"}")

    val result2 = validator.validate(canadianAddress, schema)
    println("Canadian address with postal K1A0B1: ${if (result2.isValid) "✓ Valid" else "✗ Invalid"}")
    println()
}

fun example7SchemaValidation(validator: JsonValidator) {
    println("Example 7: Schema Validation")
    println("-".repeat(50))

    val validSchema = JsonSchema.fromString(
        """
        {
            "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "name": {"type": "string"}
            }
        }
        """.trimIndent(),
    )

    val invalidSchema = JsonSchema.fromString(
        """
        {
            "type": 123
        }
        """.trimIndent(),
    )

    val result1 = validator.validateSchema(validSchema)
    println("Valid schema: ${if (result1.isValid) "✓ Valid" else "✗ Invalid"}")

    val result2 = validator.validateSchema(invalidSchema)
    println("Invalid schema: ${if (result2.isValid) "✓ Valid" else "✗ Invalid"}")
    if (result2.isInvalid) {
        result2.getErrors().forEach { println("  Error: ${it.message}") }
    }

    println("\nSchema version detection:")
    println("  Detected: ${validSchema.detectedVersion}")
    println("  Effective: ${validSchema.effectiveVersion}")
}
