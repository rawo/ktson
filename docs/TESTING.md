# Testing Guide for KtSON

## Running Tests

### Run All Tests
```bash
./gradlew test
```

### Run Specific Test Class
```bash
./gradlew test --tests "Draft201909ValidationTest"
./gradlew test --tests "Draft202012ValidationTest"
./gradlew test --tests "EdgeCaseAndThreadSafetyTest"
```

### Run Tests with Detailed Output
```bash
./gradlew test --info
```

### Run Tests in Continuous Mode
```bash
./gradlew test --continuous
```

## Test Structure

### Test Framework
- **Kotest**: Modern Kotlin testing framework
- **kotlinx-coroutines-test**: For testing suspend functions
- **MockK**: For mocking (if needed in future)

### Test Organization

```
src/test/kotlin/com/ktson/
├── Draft201909ValidationTest.kt      # Draft 2019-09 compliance tests
├── Draft202012ValidationTest.kt      # Draft 2020-12 compliance tests
└── EdgeCaseAndThreadSafetyTest.kt    # Edge cases and concurrency tests
```

## Test Categories

### 1. Draft 2019-09 Tests (`Draft201909ValidationTest.kt`)

#### Type Validation
- Tests for all JSON types (string, number, integer, boolean, null, object, array)
- Multiple type support

#### String Validation
- Length constraints (minLength, maxLength)
- Pattern matching (regex)
- Format validation (email, date-time, uuid, etc.)

#### Number Validation
- Range constraints (minimum, maximum)
- Exclusive boundaries (exclusiveMinimum, exclusiveMaximum)
- Multiple of constraint

#### Object Validation
- Property validation
- Required properties
- Additional properties (boolean and schema)
- Pattern properties
- Property count constraints
- Property name validation
- Dependent required and schemas

#### Array Validation
- Item validation (single schema and tuple)
- Array length constraints
- Unique items
- Contains with min/max contains

#### Schema Combiners
- allOf, anyOf, oneOf, not

#### Conditional Validation
- if-then-else schemas

#### Boolean Schemas
- true and false schemas

### 2. Draft 2020-12 Tests (`Draft202012ValidationTest.kt`)

All Draft 2019-09 tests plus:
- **prefixItems** for tuple validation
- Enhanced format validation
- Real-world schema examples (user profiles, API responses)
- Complex schema combinations

### 3. Edge Cases and Thread Safety Tests (`EdgeCaseAndThreadSafetyTest.kt`)

#### Edge Cases
- Empty and null values
- Boundary values
- Deeply nested structures
- Complex regex patterns
- Numeric precision
- Circular references
- Special string formats
- Large datasets

#### Thread Safety
- Concurrent validation (multiple coroutines)
- Different schemas simultaneously
- Validation failures under concurrency
- Concurrent schema validation
- High concurrency stress tests (1000+ operations)
- State isolation verification

## Writing New Tests

### Basic Test Structure

```kotlin
class MyNewTest : DescribeSpec({
    val validator = JsonValidator()
    
    describe("Feature description") {
        it("should validate specific scenario") {
            runTest {
                val schema = JsonSchema.fromString("""{"type": "string"}""")
                val instance = JsonPrimitive("test")
                
                val result = validator.validate(instance, schema)
                result.isValid shouldBe true
            }
        }
    }
})
```

### Testing Invalid Cases

```kotlin
it("should reject invalid input") {
    runTest {
        val schema = JsonSchema.fromString("""{"type": "integer", "minimum": 0}""")
        val instance = JsonPrimitive(-5)
        
        val result = validator.validate(instance, schema)
        result.isInvalid shouldBe true
        
        val errors = result.getErrors()
        errors.size shouldBe 1
        errors[0].message shouldContain "minimum"
    }
}
```

### Testing Error Messages

```kotlin
it("should provide detailed error messages") {
    runTest {
        val schema = JsonSchema.fromString("""
            {
                "type": "object",
                "required": ["name"]
            }
        """.trimIndent())
        
        val instance = buildJsonObject {
            put("age", 30)
        }
        
        val result = validator.validate(instance, schema)
        val errors = result.getErrors()
        
        errors.any { it.message.contains("Required property 'name'") } shouldBe true
        errors.any { it.path.isEmpty() } shouldBe true
    }
}
```

### Testing Thread Safety

```kotlin
it("should handle concurrent operations") {
    runTest {
        val validator = JsonValidator()
        val schema = JsonSchema.fromString("""{"type": "integer"}""")
        
        // Launch multiple concurrent validations
        val jobs = (1..100).map { i ->
            async {
                validator.validate(JsonPrimitive(i), schema)
            }
        }
        
        val results = jobs.awaitAll()
        results.all { it.isValid } shouldBe true
    }
}
```

## Test Coverage Goals

- **Line coverage**: > 90%
- **Branch coverage**: > 85%
- **All JSON Schema keywords**: 100%
- **All supported versions**: 100%
- **Edge cases**: Comprehensive
- **Concurrency**: Stress tested

## Continuous Integration

### GitHub Actions Example

```yaml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v2
    
    - name: Set up JDK 17
      uses: actions/setup-java@v2
      with:
        java-version: '17'
        distribution: 'adopt'
    
    - name: Run tests
      run: ./gradlew test
    
    - name: Generate test report
      if: always()
      run: ./gradlew testReport
```

## Performance Testing

### Large Dataset Tests

```kotlin
it("should handle large arrays efficiently") {
    runTest {
        val schema = JsonSchema.fromString("""
            {"type": "array", "items": {"type": "integer"}}
        """.trimIndent())
        
        val largeArray = buildJsonArray {
            repeat(10_000) { add(it) }
        }
        
        val startTime = System.currentTimeMillis()
        val result = validator.validate(largeArray, schema)
        val duration = System.currentTimeMillis() - startTime
        
        result.isValid shouldBe true
        duration shouldBeLessThan 1000 // Should complete in < 1 second
    }
}
```

### Concurrency Stress Test

```kotlin
it("should maintain correctness under high concurrency") {
    runTest {
        val validator = JsonValidator()
        val schema = JsonSchema.fromString("""{"type": "integer", "minimum": 0}""")
        
        val results = ConcurrentHashMap<Int, ValidationResult>()
        
        // 1000 concurrent validations
        val jobs = (1..1000).map { i ->
            async {
                val value = if (i % 2 == 0) i else -i
                results[i] = validator.validate(JsonPrimitive(value), schema)
            }
        }
        
        jobs.awaitAll()
        
        // Verify all results
        results.forEach { (i, result) ->
            if (i % 2 == 0) {
                result.isValid shouldBe true
            } else {
                result.isInvalid shouldBe true
            }
        }
    }
}
```

## Debugging Tests

### Enable Detailed Logging

```kotlin
describe("Debug feature") {
    it("should log validation steps") {
        runTest {
            val result = validator.validate(instance, schema)
            
            if (result.isInvalid) {
                result.getErrors().forEach { error ->
                    println("Error at ${error.path}: ${error.message}")
                    println("  Keyword: ${error.keyword}")
                    println("  Schema path: ${error.schemaPath}")
                }
            }
        }
    }
}
```

### Test Isolation

Each test should be independent and not rely on state from other tests. The validator is created fresh for each test to ensure isolation.

## Best Practices

1. **Use descriptive test names**: Clearly describe what is being tested
2. **Test both valid and invalid cases**: Don't just test the happy path
3. **Test edge cases**: Empty values, boundaries, nulls, etc.
4. **Test error messages**: Ensure errors are helpful and accurate
5. **Test concurrency**: Verify thread safety
6. **Use runTest**: Always wrap suspend function tests in `runTest`
7. **Verify error details**: Check paths, keywords, and messages
8. **Keep tests focused**: One concept per test
9. **Use meaningful assertions**: Make test failures easy to diagnose
10. **Document complex tests**: Add comments for non-obvious test logic

## Test Maintenance

- Review and update tests when adding new features
- Keep tests in sync with JSON Schema specifications
- Regularly run full test suite
- Monitor test execution time
- Refactor tests to reduce duplication
- Update edge case tests as bugs are found

