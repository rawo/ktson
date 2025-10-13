# Synchronous API Summary

## Current API (Version 1.0.0)

KtSON uses a **synchronous, thread-safe** validation API.

### Core Functions

```kotlin
class JsonValidator(
    private val enableMetaSchemaValidation: Boolean = true,
    private val formatAssertion: Boolean = true
) {
    // Validate JSON instance against schema
    fun validate(instance: JsonElement, schema: JsonSchema): ValidationResult
    
    // Validate from JSON strings
    fun validate(
        instanceJson: String,
        schemaJson: String,
        schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12
    ): ValidationResult
    
    // Validate that a schema is valid
    fun validateSchema(schema: JsonSchema): ValidationResult
}
```

### Thread Safety

✅ **Thread-safe** - Can be safely called from multiple threads concurrently  
✅ **No blocking** - Pure synchronous execution, no mutex or locks  
✅ **Stateless** - Each validation is independent  
✅ **Lock-free caching** - Uses `ConcurrentHashMap` internally  

### Usage Examples

#### Simple Validation
```kotlin
val validator = JsonValidator()
val schema = JsonSchema.fromString("""{"type": "string"}""")
val result = validator.validate(JsonPrimitive("hello"), schema)
println("Valid: ${result.isValid}")
```

#### Concurrent Validation
```kotlin
import kotlin.concurrent.thread

val validator = JsonValidator()
val schema = JsonSchema.fromString("""{"type": "integer"}""")

// Safe to use from multiple threads
val threads = (1..100).map { i ->
    thread {
        val result = validator.validate(JsonPrimitive(i), schema)
        println("Thread $i: ${result.isValid}")
    }
}

threads.forEach { it.join() }
```

#### In Coroutine Context
```kotlin
suspend fun validateInCoroutine() {
    val validator = JsonValidator()
    val schema = JsonSchema.fromString("""{"type": "string"}""")
    
    // No need for suspend, but works fine in coroutine context
    val result = validator.validate(JsonPrimitive("test"), schema)
    
    // Or explicitly dispatch to another context
    val result2 = withContext(Dispatchers.IO) {
        validator.validate(JsonPrimitive("test2"), schema)
    }
}
```

## Design Rationale

### Why Synchronous?

1. **CPU-Bound Work**: JSON validation is pure computation - no I/O or async operations
2. **No Waiting**: There's nothing to "wait for" that would benefit from async
3. **Simpler API**: No need for `suspend`, `runBlocking`, or coroutine context
4. **Universal Compatibility**: Works in any Kotlin context (Android, server, CLI, etc.)

### Performance Characteristics

- **Fast**: Direct function calls, no coroutine overhead
- **Predictable**: Synchronous execution, easy to reason about
- **Scalable**: Thread-safe design supports high concurrency
- **Memory Efficient**: Stateless validation, minimal allocations

## Comparison with Other Libraries

Most JSON Schema validators use synchronous APIs:

| Library | Language | API Style | Thread-Safe |
|---------|----------|-----------|-------------|
| **KtSON** | Kotlin | Sync | ✅ Yes |
| ajv | JavaScript | Sync | ✅ Yes |
| jsonschema (Python) | Python | Sync | ✅ Yes |
| json-schema-validator (Java) | Java | Sync | ✅ Yes |

This aligns with industry best practices for validation libraries.

## Migration from Async (Pre-1.0)

If upgrading from an earlier async version:

**Old:**
```kotlin
suspend fun myFunction() {
    val result = validator.validate(instance, schema)
}
```

**New:**
```kotlin
fun myFunction() {
    // Just remove 'suspend' - that's it!
    val result = validator.validate(instance, schema)
}
```

## Future Considerations

The synchronous API is stable and will remain the primary API. If async features are needed in the future (e.g., remote schema loading), they would be added as:

1. **Separate functions**: e.g., `validateAsync()` alongside `validate()`
2. **Explicit async features**: Only where actual async operations exist
3. **Optional**: Sync API remains the default

## Summary

✅ **Simple**: Direct function calls, no coroutine complexity  
✅ **Fast**: No async overhead  
✅ **Thread-Safe**: Lock-free concurrent access  
✅ **Standard**: Follows industry best practices  
✅ **Flexible**: Works in any Kotlin context  

For questions or issues, see: https://github.com/yourusername/ktson/issues
