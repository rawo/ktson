package org.ktson

/**
 * JSON Schema keyword constants
 * Contains all standard JSON Schema keywords used in validation
 */
object SchemaKeywords {
    // Schema reference keywords
    const val ID = "\$id"
    const val ANCHOR = "\$anchor"
    const val DYNAMIC_ANCHOR = "\$dynamicAnchor"
    const val RECURSIVE_ANCHOR = "\$recursiveAnchor"
    const val REF = "\$ref"
    const val RECURSIVE_REF = "\$recursiveRef"
    const val DYNAMIC_REF = "\$dynamicRef"
    const val SCHEMA = "\$schema"

    // Type validation
    const val TYPE = "type"
    const val CONST = "const"
    const val ENUM = "enum"

    // Combining schemas
    const val ALL_OF = "allOf"
    const val ANY_OF = "anyOf"
    const val ONE_OF = "oneOf"
    const val NOT = "not"

    // Conditional validation
    const val IF = "if"
    const val THEN = "then"
    const val ELSE = "else"

    // Object validation
    const val PROPERTIES = "properties"
    const val REQUIRED = "required"
    const val ADDITIONAL_PROPERTIES = "additionalProperties"
    const val PATTERN_PROPERTIES = "patternProperties"
    const val MIN_PROPERTIES = "minProperties"
    const val MAX_PROPERTIES = "maxProperties"
    const val PROPERTY_NAMES = "propertyNames"
    const val DEPENDENT_REQUIRED = "dependentRequired"
    const val DEPENDENT_SCHEMAS = "dependentSchemas"

    // Unevaluated keywords (2019-09 and later)
    const val UNEVALUATED_PROPERTIES = "unevaluatedProperties"
    const val UNEVALUATED_ITEMS = "unevaluatedItems"

    // Array validation
    const val ITEMS = "items"
    const val PREFIX_ITEMS = "prefixItems"
    const val ADDITIONAL_ITEMS = "additionalItems"
    const val MIN_ITEMS = "minItems"
    const val MAX_ITEMS = "maxItems"
    const val UNIQUE_ITEMS = "uniqueItems"
    const val CONTAINS = "contains"
    const val MIN_CONTAINS = "minContains"
    const val MAX_CONTAINS = "maxContains"

    // String validation
    const val MIN_LENGTH = "minLength"
    const val MAX_LENGTH = "maxLength"
    const val PATTERN = "pattern"
    const val FORMAT = "format"

    // Numeric validation
    const val MINIMUM = "minimum"
    const val MAXIMUM = "maximum"
    const val EXCLUSIVE_MINIMUM = "exclusiveMinimum"
    const val EXCLUSIVE_MAXIMUM = "exclusiveMaximum"
    const val MULTIPLE_OF = "multipleOf"

    // Type names
    const val TYPE_NULL = "null"
    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_INTEGER = "integer"
    const val TYPE_NUMBER = "number"
    const val TYPE_STRING = "string"
    const val TYPE_ARRAY = "array"
    const val TYPE_OBJECT = "object"
    const val TYPE_UNKNOWN = "unknown"

    // Valid types for JSON Schema type validation
    val VALID_TYPES = setOf(TYPE_STRING, TYPE_NUMBER, TYPE_INTEGER, TYPE_BOOLEAN, TYPE_OBJECT, TYPE_ARRAY, TYPE_NULL)

    // Format types
    const val FORMAT_EMAIL = "email"
    const val FORMAT_URI = "uri"
    const val FORMAT_DATE = "date"
    const val FORMAT_TIME = "time"
    const val FORMAT_DATE_TIME = "date-time"
    const val FORMAT_IPV4 = "ipv4"
    const val FORMAT_IPV6 = "ipv6"
    const val FORMAT_UUID = "uuid"

    // Boolean schemas
    const val SCHEMA_FALSE = "false"
}
