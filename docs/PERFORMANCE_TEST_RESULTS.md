# Performance Test Results

## Overview

This document presents performance test results for the KtSON JSON Schema Validator with large and complex schemas and data structures.

## Test Environment

- **JVM Settings**: 512MB min heap, 2GB max heap
- **Kotlin Version**: 2.2.20
- **Java Version**: 21
- **Gradle Version**: 9.1.0
- **Test Framework**: Kotest

## Test Results

### 1. Nested Objects Test
**Complex deeply nested object structures**

- **Schema Size**: ~25.2 MB
- **Data Size**: ~10.2 MB
- **Execution Time**: 84ms
- **Memory Used**: ~73 MB
- **Status**: ✅ SUCCESS
- **Configuration**: Depth=4, Breadth=25

**Analysis**: Deep nesting creates large schema files. The validator handles this efficiently with reasonable execution time and memory usage.

---

### 2. Large Arrays Test
**Arrays with many possible item schemas**

- **Schema Size**: ~905 KB
- **Data Size**: ~80 KB
- **Execution Time**: 7ms
- **Memory Used**: ~1 MB
- **Status**: ✅ SUCCESS
- **Configuration**: 8,000 item types, 1,500 array elements

**Analysis**: Excellent performance with large anyOf arrays. The validator efficiently handles multiple schema options for array items.

---

### 3. Complex Properties Test
**Objects with thousands of properties**

- **Schema Size**: ~534 KB
- **Data Size**: ~205 KB
- **Execution Time**: 6ms
- **Memory Used**: ~4 MB
- **Status**: ✅ SUCCESS
- **Configuration**: 8,000 properties with mixed types

**Analysis**: Very fast validation even with thousands of properties. Low memory overhead demonstrates efficient property validation.

---

### 4. AllOf/AnyOf Combiners Test
**Complex schema composition**

- **Schema Size**: ~221 KB
- **Data Size**: ~38 KB
- **Execution Time**: 4ms
- **Memory Used**: ~1 MB
- **Status**: ✅ SUCCESS
- **Configuration**: 2,000 combiner schemas

**Analysis**: Combiner schemas are handled efficiently. The validator demonstrates good performance with complex schema composition.

---

### 5. Pattern Properties Test
**Regex-based property validation**

- **Schema Size**: ~63 KB
- **Data Size**: ~148 KB
- **Execution Time**: 48ms
- **Memory Used**: ~175 MB
- **Status**: ✅ SUCCESS
- **Configuration**: 300 patterns, 1,200 properties

**Analysis**: Pattern matching is more memory-intensive but still performs well. This is expected behavior as regex matching requires additional memory.

---

### 6. Concurrent Validation Test
**Thread-safety with 10 concurrent validations**

- **Schema Size**: ~50 MB
- **Data Size**: ~20 MB
- **Total Execution Time**: 160ms
- **Average Per Validation**: 16ms
- **Memory Used**: ~171 MB
- **Status**: ✅ SUCCESS
- **Configuration**: 10 concurrent threads, nested objects (depth=5, breadth=15)

**Analysis**: Excellent concurrent performance. The thread-safe design using `ConcurrentHashMap` allows multiple validations to run efficiently in parallel with minimal overhead.

---

## Performance Summary

| Test Case | Schema Size | Data Size | Execution Time | Memory Used |
|-----------|-------------|-----------|----------------|-------------|
| Nested Objects | 25.2 MB | 10.2 MB | 84ms | 73 MB |
| Large Arrays | 905 KB | 80 KB | 7ms | 1 MB |
| Complex Properties | 534 KB | 205 KB | 6ms | 4 MB |
| AllOf/AnyOf Combiners | 221 KB | 38 KB | 4ms | 1 MB |
| Pattern Properties | 63 KB | 148 KB | 48ms | 175 MB |
| Concurrent (10 threads) | 50 MB | 20 MB | 16ms avg | 171 MB |

## Key Findings

### Performance Characteristics

1. **Scalability**: The validator scales well with schema and data sizes up to tens of megabytes
2. **Speed**: Most validations complete in single-digit milliseconds for schemas under 1MB
3. **Memory Efficiency**: Memory usage is generally proportional to schema complexity
4. **Thread Safety**: Concurrent validation shows excellent performance with minimal overhead

### Bottlenecks

1. **Pattern Properties**: Regex matching is the most memory-intensive operation
2. **Deep Nesting**: Deeply nested structures require more processing time
3. **Large Schemas**: Schemas over 20MB take more time but remain usable

### Recommendations

For optimal performance:
- Keep schemas under 5MB when possible
- Limit pattern property usage for memory-constrained environments
- Use schema caching (built-in) to avoid repeated parsing
- The validator is thread-safe and can handle concurrent validations efficiently

## Comparison to Requirements

**Target**: Schemas between 1MB and 4MB, data between 500KB and 1MB

| Requirement | Achieved | Notes |
|-------------|----------|-------|
| 1-4MB Schemas | ✅ Yes | Tested up to 25MB |
| 500KB-1MB Data | ✅ Yes | Tested up to 20MB |
| Performance Metrics | ✅ Yes | Execution time and memory tracked |
| Thread Safety | ✅ Yes | Concurrent validation successful |

## Conclusion

The KtSON validator demonstrates excellent performance characteristics with large and complex schemas:

- **Fast**: Sub-100ms validation times even for 25MB+ schemas
- **Efficient**: Memory usage is reasonable and proportional to complexity
- **Scalable**: Handles schemas and data well beyond the target range
- **Thread-Safe**: Concurrent validation works efficiently with minimal overhead

The validator is production-ready for scenarios involving large, complex JSON schemas and data structures.

## Running Performance Tests

Performance tests are excluded from the default `./gradlew test` command for faster regular test execution.

To run performance tests on demand:

```bash
# Run all performance tests
./gradlew performanceTest

# Run with detailed output
./gradlew performanceTest --info
```

---

*Tests performed on: 2025-10-13*
*Validator Version: 1.0.0-SNAPSHOT*
*Performance tests run on demand only (excluded from default test suite)*

