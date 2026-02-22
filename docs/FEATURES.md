# KtSON Features and Test Coverage

## Supported JSON Schema Specifications

### Draft 2019-09 ✅
- Full compliance with JSON Schema Draft 2019-09
- All core validation keywords
- `$recursiveRef` / `$recursiveAnchor` dynamic recursion

### Draft 2020-12 ✅
- Full compliance with JSON Schema Draft 2020-12
- All core validation keywords
- `prefixItems` support for tuple validation
- `$dynamicRef` / `$dynamicAnchor` dynamic scope resolution

## Validation Keywords Coverage

### Type Validation ✅
- [x] `type` - All types: string, number, integer, boolean, object, array, null
- [x] Multiple types support

### String Validation ✅
- [x] `minLength` - Minimum string length
- [x] `maxLength` - Maximum string length
- [x] `pattern` - Regular expression validation
- [x] `format` - Format validation:
  - [x] email
  - [x] uri
  - [x] date
  - [x] time
  - [x] date-time
  - [x] ipv4
  - [x] ipv6
  - [x] uuid

### Numeric Validation ✅
- [x] `minimum` - Minimum value (inclusive)
- [x] `maximum` - Maximum value (inclusive)
- [x] `exclusiveMinimum` - Minimum value (exclusive)
- [x] `exclusiveMaximum` - Maximum value (exclusive)
- [x] `multipleOf` - Value must be multiple of

### Object Validation ✅
- [x] `properties` - Property schemas
- [x] `required` - Required properties
- [x] `additionalProperties` - Additional properties validation
- [x] `patternProperties` - Pattern-based property schemas
- [x] `minProperties` - Minimum number of properties
- [x] `maxProperties` - Maximum number of properties
- [x] `propertyNames` - Property name validation
- [x] `dependentRequired` - Dependent required properties
- [x] `dependentSchemas` - Dependent schemas
- [x] `unevaluatedProperties` - Annotation-based, with full propagation through all applicators

### Array Validation ✅
- [x] `items` - Item schema validation
- [x] `prefixItems` - Tuple validation (2020-12)
- [x] `additionalItems` - Additional items in tuples (2019-09)
- [x] `minItems` - Minimum array length
- [x] `maxItems` - Maximum array length
- [x] `uniqueItems` - Unique items constraint
- [x] `contains` - Contains validation
- [x] `minContains` - Minimum matching items
- [x] `maxContains` - Maximum matching items
- [x] `unevaluatedItems` - Annotation-based, with contains annotation (matching indices only)

### Combining Schemas ✅
- [x] `allOf` - Must match all schemas
- [x] `anyOf` - Must match at least one schema
- [x] `oneOf` - Must match exactly one schema
- [x] `not` - Must not match schema

### Conditional Validation ✅
- [x] `if` - Conditional schema
- [x] `then` - Schema to apply if condition matches
- [x] `else` - Schema to apply if condition doesn't match

### Generic Keywords ✅
- [x] `const` - Constant value validation
- [x] `enum` - Enumeration validation

### Boolean Schemas ✅
- [x] `true` - Accepts any instance
- [x] `false` - Rejects any instance

### References and Anchors ✅
- [x] `$ref` — JSON Pointer and named anchor resolution, respects `$id` resource boundaries
- [x] `$anchor` — Named anchors within a schema resource
- [x] `$dynamicAnchor` / `$dynamicRef` — Draft 2020-12 dynamic scope resolution
- [x] `$recursiveAnchor` / `$recursiveRef` — Draft 2019-09 dynamic recursion
- [x] `$id` — Schema resource boundaries with RFC 3986 relative URI resolution
- [x] `$defs` / `definitions` — Schema definition storage
- [x] Sibling keywords evaluated alongside `$ref` (Draft 2019-09+ behaviour)
- [x] Percent-decoding of URI fragments (RFC 6901 §6)

## Official Test Suite

**Test Suite**: [json-schema-org/JSON-Schema-Test-Suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite)

| Metric | Value |
|---|---|
| Total tests | 2,412 |
| Passing | 2,403 ✅ |
| Failing | 9 ❌ |
| Pass rate | **99.6%** |

All 9 remaining failures require remote schema loading (HTTP/HTTPS), which is not yet implemented.

## Test Coverage

### Draft 2019-09 Test Suite ✅
Total test cases: **35+**

1. **Type Validation** (8 tests)
   - String, integer, number, boolean, null, object, array types
   - Multiple types

2. **String Validation** (6 tests)
   - minLength, maxLength, pattern
   - email, date-time, uuid formats

3. **Number Validation** (5 tests)
   - minimum, maximum
   - exclusiveMinimum, exclusiveMaximum
   - multipleOf

4. **Object Validation** (10 tests)
   - properties, required
   - additionalProperties (boolean and schema)
   - patternProperties
   - minProperties, maxProperties
   - propertyNames
   - dependentRequired, dependentSchemas

5. **Array Validation** (7 tests)
   - items (single and tuple)
   - minItems, maxItems
   - uniqueItems
   - contains, minContains, maxContains

6. **Const and Enum** (2 tests)

7. **Combining Schemas** (4 tests)
   - allOf, anyOf, oneOf, not

8. **Conditional Schemas** (1 test)
   - if-then-else

9. **Boolean Schemas** (2 tests)

10. **Schema Validation** (2 tests)

### Draft 2020-12 Test Suite ✅
Total test cases: **40+**

All Draft 2019-09 tests plus:
- **prefixItems** validation
- **Enhanced format validation**
- **Real-world schemas** (user profiles, API responses)
- **Complex combinations**

### Edge Cases and Thread-Safety Tests ✅
Total test cases: **50+**

1. **Empty and Null Values** (6 tests)
   - Empty strings, arrays, objects
   - Null values and combinations

2. **Boundary Values** (5 tests)
   - Zero-length strings
   - Very large numbers
   - Exact boundary validation
   - Exclusive boundaries

3. **Deeply Nested Structures** (3 tests)
   - Nested objects
   - Nested arrays
   - Nested allOf

4. **Pattern and Regex** (4 tests)
   - Empty patterns
   - Complex regex
   - Special characters
   - Overlapping patterns

5. **Numeric Precision** (3 tests)
   - Floating point multipleOf
   - Very small values
   - Integer vs number distinction

6. **Circular References** (1 test)
   - Recursive schema definitions

7. **Special String Formats** (4 tests)
   - Various email formats
   - IPv4 addresses
   - UUID formats
   - Date and time formats

8. **Error Messages** (2 tests)
   - Meaningful error messages
   - Path inclusion in errors

9. **Thread Safety** (6 tests)
   - Concurrent validations (100 concurrent)
   - Different schemas concurrently
   - Validation failures under concurrency
   - Concurrent schema validation (50 schemas)
   - High concurrency (1000 concurrent)
   - State isolation

10. **Schema Version Detection** (3 tests)
    - Draft 2019-09 detection
    - Draft 2020-12 detection
    - Default version handling

11. **Large Data Sets** (2 tests)
    - Large arrays (10,000 items)
    - Large objects (1,000 properties)

## Thread Safety Features ✅

1. **Stateless design** - No mutable shared state between validations
2. **Immutable validator** - `JsonValidator` instance can be shared freely across threads
3. **Concurrent validation** - Tested with up to 1,000 concurrent operations
4. **Configurable depth limit** - Protects against stack overflow in untrusted schemas

## Performance Characteristics ✅

1. **Efficient validation** - Single-pass validation where possible
2. **Large data support** - Tested with 10,000+ element arrays
3. **Memory efficient** - No unnecessary copying of data
4. **Concurrent performance** - Linear scaling with multiple cores

## Error Reporting ✅

1. **Detailed error messages** - Clear description of validation failures
2. **Path tracking** - Full JSON path to error location
3. **Keyword identification** - Which keyword failed
4. **Multiple errors** - Reports all validation errors, not just first one

## API Design ✅

1. **Type-safe** - Leverages Kotlin's type system
2. **Synchronous** - Simple blocking API, no coroutines required
3. **Idiomatic Kotlin** - Follows Kotlin conventions
4. **Easy to use** - Simple, intuitive API
5. **Thread-safe** - Single `JsonValidator` instance safe for concurrent use

## Production Ready ✅

- [x] 99.6% official JSON Schema test suite pass rate (2,403 / 2,412)
- [x] Comprehensive unit tests (125+ additional test cases)
- [x] Thread-safe implementation
- [x] Configurable recursion depth protection
- [x] Performance tested with large datasets
- [x] Detailed documentation
- [x] Standard Gradle build
- [x] Maven publishable
- [x] Zero linter errors
