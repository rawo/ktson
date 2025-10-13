package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import kotlin.system.measureTimeMillis

/**
 * Performance tests for JsonValidator with large and complex schemas and data
 *
 * Tests validation performance with:
 * - Schema sizes: 1MB - 4MB
 * - Data sizes: 500KB - 1MB
 * - Various complexity patterns
 */
class PerformanceTest :
    DescribeSpec({

    describe("Performance: Large Schema Validation") {

        it("should validate 1MB schema with 500KB data - Nested Objects") {
            // Force GC before test
            System.gc()
            Thread.sleep(200)

            val (schema, data) = generateNestedObjectSchema(depth = 4, breadth = 25)
            val schemaSize = Json.encodeToString(JsonElement.serializer(), schema).length
            val dataSize = Json.encodeToString(JsonElement.serializer(), data).length

            println("\n=== Nested Objects Test ===")
            println("Schema size: ${schemaSize / 1024}KB")
            println("Data size: ${dataSize / 1024}KB")

            val validator = JsonValidator()
            val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)

            // Warmup
            validator.validate(data, jsonSchema)

            // Measure
            System.gc()
            val runtime = Runtime.getRuntime()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val executionTime = measureTimeMillis {
                val result = validator.validate(data, jsonSchema)
                result.isValid shouldBe true
            }

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / 1024 / 1024

            println("Execution time: ${executionTime}ms")
            println("Memory used: ~${memUsed}MB")
            println("Validation: SUCCESS")
        }

        it("should validate 2MB schema with 750KB data - Large Arrays") {
            System.gc()
            Thread.sleep(200)

            val (schema, data) = generateLargeArraySchema(itemCount = 8000, arraySize = 1500)
            val schemaSize = Json.encodeToString(JsonElement.serializer(), schema).length
            val dataSize = Json.encodeToString(JsonElement.serializer(), data).length

            println("\n=== Large Arrays Test ===")
            println("Schema size: ${schemaSize / 1024}KB")
            println("Data size: ${dataSize / 1024}KB")

            val validator = JsonValidator()
            val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)

            // Warmup
            validator.validate(data, jsonSchema)

            // Measure
            System.gc()
            val runtime = Runtime.getRuntime()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val executionTime = measureTimeMillis {
                val result = validator.validate(data, jsonSchema)
                result.isValid shouldBe true
            }

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / 1024 / 1024

            println("Execution time: ${executionTime}ms")
            println("Memory used: ~${memUsed}MB")
            println("Validation: SUCCESS")
        }

        it("should validate 3MB schema with 1MB data - Complex Properties") {
            System.gc()
            Thread.sleep(200)

            val (schema, data) = generateComplexPropertiesSchema(propertyCount = 8000)
            val schemaSize = Json.encodeToString(JsonElement.serializer(), schema).length
            val dataSize = Json.encodeToString(JsonElement.serializer(), data).length

            println("\n=== Complex Properties Test ===")
            println("Schema size: ${schemaSize / 1024}KB")
            println("Data size: ${dataSize / 1024}KB")

            val validator = JsonValidator()
            val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)

            // Warmup
            validator.validate(data, jsonSchema)

            // Measure
            System.gc()
            val runtime = Runtime.getRuntime()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val executionTime = measureTimeMillis {
                val result = validator.validate(data, jsonSchema)
                result.isValid shouldBe true
            }

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / 1024 / 1024

            println("Execution time: ${executionTime}ms")
            println("Memory used: ~${memUsed}MB")
            println("Validation: SUCCESS")
        }

        it("should validate 4MB schema with 1MB data - AllOf/AnyOf Combiners") {
            System.gc()
            Thread.sleep(200)

            val (schema, data) = generateCombinerSchema(combinerCount = 2000)
            val schemaSize = Json.encodeToString(JsonElement.serializer(), schema).length
            val dataSize = Json.encodeToString(JsonElement.serializer(), data).length

            println("\n=== Combiner Schema Test ===")
            println("Schema size: ${schemaSize / 1024}KB")
            println("Data size: ${dataSize / 1024}KB")

            val validator = JsonValidator()
            val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)

            // Warmup
            validator.validate(data, jsonSchema)

            // Measure
            System.gc()
            val runtime = Runtime.getRuntime()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val executionTime = measureTimeMillis {
                val result = validator.validate(data, jsonSchema)
                result.isValid shouldBe true
            }

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / 1024 / 1024

            println("Execution time: ${executionTime}ms")
            println("Memory used: ~${memUsed}MB")
            println("Validation: SUCCESS")
        }

        it("should validate 2.5MB schema with 800KB data - Pattern Properties") {
            System.gc()
            Thread.sleep(200)

            val (schema, data) = generatePatternPropertiesSchema(patternCount = 300, propertyCount = 1200)
            val schemaSize = Json.encodeToString(JsonElement.serializer(), schema).length
            val dataSize = Json.encodeToString(JsonElement.serializer(), data).length

            println("\n=== Pattern Properties Test ===")
            println("Schema size: ${schemaSize / 1024}KB")
            println("Data size: ${dataSize / 1024}KB")

            val validator = JsonValidator()
            val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)

            // Warmup
            validator.validate(data, jsonSchema)

            // Measure
            System.gc()
            val runtime = Runtime.getRuntime()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val executionTime = measureTimeMillis {
                val result = validator.validate(data, jsonSchema)
                result.isValid shouldBe true
            }

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / 1024 / 1024

            println("Execution time: ${executionTime}ms")
            println("Memory used: ~${memUsed}MB")
            println("Validation: SUCCESS")
        }
    }

    describe("Performance: Concurrent Validation") {

        it("should handle concurrent validation of large schemas") {
            // Force GC before test
            System.gc()
            Thread.sleep(100)

            val (schema, data) = generateNestedObjectSchema(depth = 5, breadth = 15)
            val schemaSize = Json.encodeToString(JsonElement.serializer(), schema).length
            val dataSize = Json.encodeToString(JsonElement.serializer(), data).length

            println("\n=== Concurrent Validation Test ===")
            println("Schema size: ${schemaSize / 1024}KB")
            println("Data size: ${dataSize / 1024}KB")
            println("Concurrent validations: 10")

            val validator = JsonValidator()
            val jsonSchema = JsonSchema.fromElement(schema, SchemaVersion.DRAFT_2020_12)

            // Warmup
            validator.validate(data, jsonSchema)

            System.gc()
            val runtime = Runtime.getRuntime()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val executionTime = measureTimeMillis {
                val threads = (1..10).map { threadNum ->
                    Thread {
                        val result = validator.validate(data, jsonSchema)
                        result.isValid shouldBe true
                    }
                }
                threads.forEach { it.start() }
                threads.forEach { it.join() }
            }

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memUsed = (memAfter - memBefore) / 1024 / 1024

            println("Total execution time: ${executionTime}ms")
            println("Average per validation: ${executionTime / 10}ms")
            println("Memory used: ~${memUsed}MB")
            println("All validations: SUCCESS")
        }
    }
})

/**
 * Generate a nested object schema with specified depth and breadth
 */
private fun generateNestedObjectSchema(depth: Int, breadth: Int): Pair<JsonObject, JsonObject> {
    fun buildNestedSchema(currentDepth: Int): JsonObject {
        if (currentDepth == 0) {
            return buildJsonObject {
                put("type", "string")
                put("minLength", 5)
                put("maxLength", 100)
            }
        }

        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                repeat(breadth) { i ->
                    put("field_$i", buildNestedSchema(currentDepth - 1))
                }
            }
            putJsonArray("required") {
                repeat(breadth / 2) { i ->
                    add("field_$i")
                }
            }
        }
    }

    fun buildNestedData(currentDepth: Int): JsonObject {
        if (currentDepth == 0) {
            return buildJsonObject {
                // Return empty since parent expects string
            }
        }

        return buildJsonObject {
            repeat(breadth) { i ->
                if (currentDepth == 1) {
                    put("field_$i", "test_value_$i")
                } else {
                    put("field_$i", buildNestedData(currentDepth - 1))
                }
            }
        }
    }

    return buildNestedSchema(depth) to buildNestedData(depth)
}

/**
 * Generate a large array schema
 */
private fun generateLargeArraySchema(itemCount: Int, arraySize: Int): Pair<JsonObject, JsonArray> {
    val schema = buildJsonObject {
        put("type", "array")
        put("minItems", 1)
        put("maxItems", arraySize * 2)
        putJsonObject("items") {
            putJsonArray("anyOf") {
                repeat(itemCount) { i ->
                    add(
                        buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                put("id", buildJsonObject { put("type", "integer") })
                                put("name", buildJsonObject { put("type", "string") })
                                put("category_$i", buildJsonObject { put("type", "string") })
                            }
                        },
                    )
                }
            }
        }
    }

    val data = buildJsonArray {
        repeat(arraySize) { i ->
            add(
                buildJsonObject {
                    put("id", i)
                    put("name", "item_$i")
                    put("category_${i % itemCount}", "cat_$i")
                },
            )
        }
    }

    return schema to data
}

/**
 * Generate a schema with many complex properties
 */
private fun generateComplexPropertiesSchema(propertyCount: Int): Pair<JsonObject, JsonObject> {
    val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            repeat(propertyCount) { i ->
                put(
                    "prop_$i",
                    buildJsonObject {
                        put(
                            "type",
                            when (i % 5) {
                            0 -> "string"
                            1 -> "integer"
                            2 -> "boolean"
                            3 -> "array"
                            else -> "object"
                        }
                        )
                        if (i % 5 == 0) {
                            put("minLength", 1)
                            put("maxLength", 50)
                            put("pattern", "^[a-zA-Z0-9_]+$")
                        } else if (i % 5 == 1) {
                            put("minimum", 0)
                            put("maximum", 10000)
                        } else if (i % 5 == 3) {
                            putJsonObject("items") {
                                put("type", "string")
                            }
                            put("minItems", 0)
                            put("maxItems", 10)
                        } else if (i % 5 == 4) {
                            putJsonObject("properties") {
                                put("nested_field", buildJsonObject { put("type", "string") })
                            }
                        }
                    },
                )
            }
        }
        putJsonArray("required") {
            repeat(propertyCount / 10) { i ->
                add("prop_${i * 10}")
            }
        }
    }

    val data = buildJsonObject {
        repeat(propertyCount) { i ->
            when (i % 5) {
                0 -> put("prop_$i", "value_$i")
                1 -> put("prop_$i", i)
                2 -> put("prop_$i", i % 2 == 0)
                3 -> put("prop_$i", buildJsonArray {
                    add("item1")
                    add("item2")
                })
                else -> put("prop_$i", buildJsonObject { put("nested_field", "nested_$i") })
            }
        }
    }

    return schema to data
}

/**
 * Generate a schema with many allOf/anyOf combiners
 */
private fun generateCombinerSchema(combinerCount: Int): Pair<JsonObject, JsonObject> {
    val schema = buildJsonObject {
        putJsonArray("allOf") {
            repeat(combinerCount) { i ->
                add(
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put(
                                "field_$i",
                                buildJsonObject {
                                    putJsonArray("anyOf") {
                                        add(buildJsonObject { put("type", "string") })
                                        add(buildJsonObject { put("type", "integer") })
                                        add(buildJsonObject { put("type", "boolean") })
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    val data = buildJsonObject {
        repeat(combinerCount) { i ->
            when (i % 3) {
                0 -> put("field_$i", "value_$i")
                1 -> put("field_$i", i)
                else -> put("field_$i", i % 2 == 0)
            }
        }
    }

    return schema to data
}

/**
 * Generate a schema with pattern properties
 */
private fun generatePatternPropertiesSchema(patternCount: Int, propertyCount: Int): Pair<JsonObject, JsonObject> {
    val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("patternProperties") {
            repeat(patternCount) { i ->
                put(
                    "^pattern_${i}_.*$",
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("id", buildJsonObject { put("type", "integer") })
                            put("value", buildJsonObject {
                                put("type", "string")
                                put("minLength", 5)
                            })
                            put("metadata", buildJsonObject {
                                put("type", "object")
                                putJsonObject("properties") {
                                    put("created", buildJsonObject { put("type", "string") })
                                    put("modified", buildJsonObject { put("type", "string") })
                                }
                            })
                        }
                    },
                )
            }
        }
    }

    val data = buildJsonObject {
        repeat(propertyCount) { i ->
            val patternIdx = i % patternCount
            put(
                "pattern_${patternIdx}_property_$i",
                buildJsonObject {
                    put("id", i)
                    put("value", "property_value_$i")
                    put("metadata", buildJsonObject {
                        put("created", "2024-01-01")
                        put("modified", "2024-01-02")
                    })
                },
            )
        }
    }

    return schema to data
}
