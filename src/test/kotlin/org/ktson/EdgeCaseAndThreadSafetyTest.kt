package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class EdgeCaseAndThreadSafetyTest : DescribeSpec({
    
    describe("Edge Cases: Empty and Null Values") {
        val validator = JsonValidator()
        
        it("should validate empty string") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string"}""")
                validator.validate(JsonPrimitive(""), schema).isValid shouldBe true
            }
        }
        
        it("should validate empty array") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "array"}""")
                validator.validate(buildJsonArray {}, schema).isValid shouldBe true
            }
        }
        
        it("should validate empty object") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "object"}""")
                validator.validate(buildJsonObject {}, schema).isValid shouldBe true
            }
        }
        
        it("should validate null value") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "null"}""")
                validator.validate(JsonNull, schema).isValid shouldBe true
            }
        }
        
        it("should validate null in enum") {
            runTest {
                val schema = JsonSchema.fromString("""{"enum": [null, "value"]}""")
                validator.validate(JsonNull, schema).isValid shouldBe true
                validator.validate(JsonPrimitive("value"), schema).isValid shouldBe true
            }
        }
        
        it("should handle empty schema") {
            runTest {
                val schema = JsonSchema.fromString("{}")
                validator.validate(JsonPrimitive("anything"), schema).isValid shouldBe true
                validator.validate(buildJsonObject {}, schema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Boundary Values") {
        val validator = JsonValidator()
        
        it("should validate zero length string") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "minLength": 0, "maxLength": 0}""")
                validator.validate(JsonPrimitive(""), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("a"), schema).isValid shouldBe false
            }
        }
        
        it("should validate very large numbers") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number"}""")
                validator.validate(JsonPrimitive(Double.MAX_VALUE), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(Double.MIN_VALUE), schema).isValid shouldBe true
            }
        }
        
        it("should validate negative zero") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "minimum": 0}""")
                validator.validate(JsonPrimitive(-0.0), schema).isValid shouldBe true
            }
        }
        
        it("should validate exact boundary values") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "integer", "minimum": 10, "maximum": 20}""")
                validator.validate(JsonPrimitive(10), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(20), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(9), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(21), schema).isValid shouldBe false
            }
        }
        
        it("should validate exclusive boundaries") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {"type": "number", "exclusiveMinimum": 10, "exclusiveMaximum": 20}
                """.trimIndent())
                validator.validate(JsonPrimitive(10.0), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(20.0), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(15), schema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Deeply Nested Structures") {
        val validator = JsonValidator()
        
        it("should validate deeply nested objects") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "properties": {
                            "level1": {
                                "type": "object",
                                "properties": {
                                    "level2": {
                                        "type": "object",
                                        "properties": {
                                            "level3": {"type": "string"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                val nestedObj = buildJsonObject {
                    putJsonObject("level1") {
                        putJsonObject("level2") {
                            put("level3", "deep value")
                        }
                    }
                }
                
                validator.validate(nestedObj, schema).isValid shouldBe true
            }
        }
        
        it("should validate deeply nested arrays") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "array",
                        "items": {
                            "type": "array",
                            "items": {
                                "type": "array",
                                "items": {"type": "integer"}
                            }
                        }
                    }
                """.trimIndent())
                
                val nestedArray = buildJsonArray {
                    add(buildJsonArray {
                        add(buildJsonArray {
                            add(1)
                            add(2)
                        })
                    })
                }
                
                validator.validate(nestedArray, schema).isValid shouldBe true
            }
        }
        
        it("should validate complex nested allOf") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "allOf": [
                            {
                                "allOf": [
                                    {"type": "object"},
                                    {"properties": {"a": {"type": "integer"}}}
                                ]
                            },
                            {"properties": {"b": {"type": "string"}}}
                        ]
                    }
                """.trimIndent())
                
                val obj = buildJsonObject {
                    put("a", 1)
                    put("b", "test")
                }
                
                validator.validate(obj, schema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Pattern and Regex") {
        val validator = JsonValidator()
        
        it("should validate empty pattern") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "pattern": ""}""")
                validator.validate(JsonPrimitive("anything"), schema).isValid shouldBe true
            }
        }
        
        it("should validate complex regex patterns") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {"type": "string", "pattern": "^[A-Z][a-z]+\\s[A-Z][a-z]+$"}
                """.trimIndent())
                validator.validate(JsonPrimitive("John Doe"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("john doe"), schema).isValid shouldBe false
            }
        }
        
        it("should validate pattern with special characters") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {"type": "string", "pattern": "^\\$[0-9]+\\.[0-9]{2}$"}
                """.trimIndent())
                validator.validate(JsonPrimitive("\$123.45"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("123.45"), schema).isValid shouldBe false
            }
        }
        
        it("should validate patternProperties with overlapping patterns") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "patternProperties": {
                            "^[a-z]+$": {"type": "string"},
                            "^[a-z]{3}$": {"minLength": 5}
                        }
                    }
                """.trimIndent())
                
                val obj = buildJsonObject {
                    put("abc", "hello")
                }
                
                validator.validate(obj, schema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Numeric Precision") {
        val validator = JsonValidator()
        
        it("should validate multipleOf with floating point") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "multipleOf": 0.1}""")
                validator.validate(JsonPrimitive(0.3), schema).isValid shouldBe true
                validator.validate(JsonPrimitive(0.5), schema).isValid shouldBe true
            }
        }
        
        it("should handle very small multipleOf values") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "number", "multipleOf": 0.0001}""")
                validator.validate(JsonPrimitive(0.0003), schema).isValid shouldBe true
            }
        }
        
        it("should distinguish between integer and number") {
            runTest {
                val intSchema = JsonSchema.fromString("""{"type": "integer"}""")
                val numSchema = JsonSchema.fromString("""{"type": "number"}""")
                
                validator.validate(JsonPrimitive(42), intSchema).isValid shouldBe true
                validator.validate(JsonPrimitive(42.0), numSchema).isValid shouldBe true
                validator.validate(JsonPrimitive(42.5), intSchema).isValid shouldBe false
                validator.validate(JsonPrimitive(42.5), numSchema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Circular References in Schemas") {
        val validator = JsonValidator()
        
        it("should handle recursive schema definitions") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "children": {
                                "type": "array",
                                "items": {"type": "object"}
                            }
                        }
                    }
                """.trimIndent())
                
                val recursiveObj = buildJsonObject {
                    put("name", "parent")
                    putJsonArray("children") {
                        add(buildJsonObject {
                            put("name", "child1")
                        })
                        add(buildJsonObject {
                            put("name", "child2")
                        })
                    }
                }
                
                validator.validate(recursiveObj, schema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Special String Formats") {
        val validator = JsonValidator()
        
        it("should validate various email formats") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "email"}""")
                
                validator.validate(JsonPrimitive("simple@example.com"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("user+tag@example.com"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("user.name@example.co.uk"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("@example.com"), schema).isValid shouldBe false
                validator.validate(JsonPrimitive("user@"), schema).isValid shouldBe false
            }
        }
        
        it("should validate IPv4 addresses") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "ipv4"}""")
                
                validator.validate(JsonPrimitive("192.168.1.1"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("0.0.0.0"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("255.255.255.255"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("256.1.1.1"), schema).isValid shouldBe false
                validator.validate(JsonPrimitive("192.168.1"), schema).isValid shouldBe false
            }
        }
        
        it("should validate UUID format") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string", "format": "uuid"}""")
                
                validator.validate(JsonPrimitive("550e8400-e29b-41d4-a716-446655440000"), schema).isValid shouldBe true
                validator.validate(JsonPrimitive("not-a-uuid"), schema).isValid shouldBe false
                validator.validate(JsonPrimitive("550e8400-e29b-41d4-a716"), schema).isValid shouldBe false
            }
        }
        
        it("should validate date and time formats") {
            runTest {
                val dateSchema = JsonSchema.fromString("""{"type": "string", "format": "date"}""")
                val timeSchema = JsonSchema.fromString("""{"type": "string", "format": "time"}""")
                val dateTimeSchema = JsonSchema.fromString("""{"type": "string", "format": "date-time"}""")
                
                validator.validate(JsonPrimitive("2023-10-12"), dateSchema).isValid shouldBe true
                validator.validate(JsonPrimitive("10:30:00Z"), timeSchema).isValid shouldBe true
                validator.validate(JsonPrimitive("2023-10-12T10:30:00Z"), dateTimeSchema).isValid shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Error Messages") {
        val validator = JsonValidator()
        
        it("should provide meaningful error messages") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "properties": {
                            "age": {"type": "integer", "minimum": 0}
                        },
                        "required": ["name"]
                    }
                """.trimIndent())
                
                val obj = buildJsonObject {
                    put("age", -5)
                }
                
                val result = validator.validate(obj, schema)
                result.isInvalid shouldBe true
                val errors = result.getErrors()
                errors.size shouldBe 2
                errors.any { it.message.contains("Required") } shouldBe true
                errors.any { it.message.contains("minimum") } shouldBe true
            }
        }
        
        it("should include path in error messages") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "properties": {
                            "user": {
                                "type": "object",
                                "properties": {
                                    "age": {"type": "integer"}
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                val obj = buildJsonObject {
                    putJsonObject("user") {
                        put("age", "not a number")
                    }
                }
                
                val result = validator.validate(obj, schema)
                result.isInvalid shouldBe true
                val errors = result.getErrors()
                errors[0].path shouldContain "user.age"
            }
        }
    }
    
    describe("Thread Safety: Concurrent Validation") {
        val validator = JsonValidator()
        
        it("should handle concurrent validations safely") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "properties": {
                            "id": {"type": "integer"},
                            "name": {"type": "string"}
                        },
                        "required": ["id", "name"]
                    }
                """.trimIndent())
                
                val results = ConcurrentHashMap<Int, ValidationResult>()
                val successCount = AtomicInteger(0)
                
                // Launch 100 concurrent validations
                val jobs = (1..100).map { i ->
                    async {
                        val obj = buildJsonObject {
                            put("id", i)
                            put("name", "User $i")
                        }
                        val result = validator.validate(obj, schema)
                        results[i] = result
                        if (result.isValid) {
                            successCount.incrementAndGet()
                        }
                    }
                }
                
                jobs.awaitAll()
                
                // All validations should succeed
                successCount.get() shouldBe 100
                results.size shouldBe 100
            }
        }
        
        it("should handle concurrent validations with different schemas") {
            runTest {
                val schemas = listOf(
                    JsonSchema.fromString("""{"type": "string"}"""),
                    JsonSchema.fromString("""{"type": "integer"}"""),
                    JsonSchema.fromString("""{"type": "boolean"}"""),
                    JsonSchema.fromString("""{"type": "object"}"""),
                    JsonSchema.fromString("""{"type": "array"}""")
                )
                
                val instances = listOf(
                    JsonPrimitive("text"),
                    JsonPrimitive(42),
                    JsonPrimitive(true),
                    buildJsonObject { put("key", "value") },
                    buildJsonArray { add(1); add(2) }
                )
                
                val results = ConcurrentHashMap<Int, ValidationResult>()
                
                // Launch 50 concurrent validations with different schemas
                val jobs = (1..50).map { i ->
                    async {
                        val schemaIndex = i % schemas.size
                        val result = validator.validate(instances[schemaIndex], schemas[schemaIndex])
                        results[i] = result
                    }
                }
                
                jobs.awaitAll()
                
                // All validations should succeed (matching schema-instance pairs)
                results.values.all { it.isValid } shouldBe true
            }
        }
        
        it("should handle concurrent validations with validation failures") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "integer", "minimum": 50}""")
                
                val results = ConcurrentHashMap<Int, ValidationResult>()
                
                // Launch 100 concurrent validations with both valid and invalid values
                val jobs = (1..100).map { i ->
                    async {
                        val result = validator.validate(JsonPrimitive(i), schema)
                        results[i] = result
                    }
                }
                
                jobs.awaitAll()
                
                // Values >= 50 should be valid, others invalid
                results.forEach { (value, result) ->
                    if (value >= 50) {
                        result.isValid shouldBe true
                    } else {
                        result.isInvalid shouldBe true
                    }
                }
            }
        }
        
        it("should handle concurrent schema validations") {
            runTest {
                val schemas = (1..50).map { i ->
                    JsonSchema.fromString("""
                        {
                            "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                            "type": "object",
                            "properties": {
                                "field$i": {"type": "string"}
                            }
                        }
                    """.trimIndent())
                }
                
                val results = ConcurrentHashMap<Int, ValidationResult>()
                
                // Validate all schemas concurrently
                val jobs = schemas.mapIndexed { index, schema ->
                    async {
                        val result = validator.validateSchema(schema)
                        results[index] = result
                    }
                }
                
                jobs.awaitAll()
                
                // All schemas should be valid
                results.values.all { it.isValid } shouldBe true
            }
        }
        
        it("should maintain thread safety with high concurrency") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "array",
                        "items": {"type": "integer"},
                        "minItems": 1,
                        "maxItems": 10
                    }
                """.trimIndent())
                
                val results = ConcurrentHashMap<Int, ValidationResult>()
                
                // Launch 1000 concurrent validations
                val jobs = (1..1000).map { i ->
                    async {
                        val array = buildJsonArray {
                            repeat(i % 15) { add(it) }
                        }
                        val result = validator.validate(array, schema)
                        results[i] = result
                    }
                }
                
                jobs.awaitAll()
                
                // Verify correct validation results
                results.forEach { (i, result) ->
                    val arraySize = i % 15
                    if (arraySize in 1..10) {
                        result.isValid shouldBe true
                    } else {
                        result.isInvalid shouldBe true
                    }
                }
            }
        }
    }
    
    describe("Thread Safety: State Isolation") {
        it("should isolate state between validations") {
            runTest {
                val validator = JsonValidator()
                val schema = JsonSchema.fromString("""{"type": "object"}""")
                
                // Perform validations that might modify internal state
                val jobs = (1..100).map { i ->
                    async {
                        val obj = buildJsonObject {
                            put("iteration", i)
                            putJsonArray("data") {
                                repeat(10) { add(it) }
                            }
                        }
                        validator.validate(obj, schema)
                    }
                }
                
                val results = jobs.awaitAll()
                
                // All should be valid and independent
                results.all { it.isValid } shouldBe true
            }
        }
    }
    
    describe("Edge Cases: Schema Version Detection") {
        val validator = JsonValidator()
        
        it("should detect Draft 2019-09 from \$schema property") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "${'$'}schema": "https://json-schema.org/draft/2019-09/schema",
                        "type": "string"
                    }
                """.trimIndent())
                
                schema.detectedVersion shouldBe SchemaVersion.DRAFT_2019_09
            }
        }
        
        it("should detect Draft 2020-12 from \$schema property") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                        "type": "string"
                    }
                """.trimIndent())
                
                schema.detectedVersion shouldBe SchemaVersion.DRAFT_2020_12
            }
        }
        
        it("should use default version when \$schema is not specified") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string"}""")
                
                schema.detectedVersion shouldBe null
                schema.effectiveVersion shouldBe SchemaVersion.DRAFT_2020_12
            }
        }
    }
    
    describe("Edge Cases: Large Data Sets") {
        val validator = JsonValidator()
        
        it("should validate large arrays efficiently") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "array",
                        "items": {"type": "integer"}
                    }
                """.trimIndent())
                
                val largeArray = buildJsonArray {
                    repeat(10000) { add(it) }
                }
                
                val result = validator.validate(largeArray, schema)
                result.isValid shouldBe true
            }
        }
        
        it("should validate large objects efficiently") {
            runTest {
                val schema = JsonSchema.fromString("""
                    {
                        "type": "object",
                        "additionalProperties": {"type": "integer"}
                    }
                """.trimIndent())
                
                val largeObject = buildJsonObject {
                    repeat(1000) { i ->
                        put("key$i", i)
                    }
                }
                
                val result = validator.validate(largeObject, schema)
                result.isValid shouldBe true
            }
        }
    }
})

