package org.ktson

import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Main JSON Schema validator with thread-safe synchronous validation
 */
class JsonValidator(
    private val enableMetaSchemaValidation: Boolean = true,
    private val formatAssertion: Boolean = true // Draft 2020-12 uses annotation by default
) {
    private val schemaCache = ConcurrentHashMap<String, JsonSchema>()
    private val referenceResolver = ReferenceResolver()
    
    /**
     * Validates a JSON instance against a JSON schema
     * Thread-safe synchronous implementation
     */
    fun validate(
        instance: JsonElement,
        schema: JsonSchema
    ): ValidationResult {
        return validateInternal(instance, schema, "")
    }
    
    /**
     * Validates a JSON instance from string against a schema from string
     */
    fun validate(
        instanceJson: String,
        schemaJson: String,
        schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12
    ): ValidationResult {
        val instance = Json.parseToJsonElement(instanceJson)
        val schema = JsonSchema.fromString(schemaJson, schemaVersion)
        return validate(instance, schema)
    }
    
    /**
     * Validates that a JSON schema is valid according to its meta-schema
     */
    fun validateSchema(schema: JsonSchema): ValidationResult {
        if (!enableMetaSchemaValidation) {
            return ValidationResult.Valid
        }
        
        // Basic schema validation
        val errors = mutableListOf<ValidationError>()
        validateSchemaStructure(schema.schema, "", errors)
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    /**
     * Internal validation implementation
     */
    private fun validateInternal(
        instance: JsonElement,
        schema: JsonSchema,
        path: String
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        validateElement(instance, schema.schema, path, errors, schema.effectiveVersion, schema.schema)
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    /**
     * Validates a JSON element against a schema element
     */
    private fun validateElement(
        instance: JsonElement,
        schemaElement: JsonElement,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        when (schemaElement) {
            is JsonObject -> {
                // Check for $ref first
                val ref = schemaElement["\$ref"]?.jsonPrimitive?.contentOrNull
                if (ref != null) {
                    val resolvedSchema = referenceResolver.resolveRef(ref, rootSchema, schemaElement)
                    if (resolvedSchema != null) {
                        validateElement(instance, resolvedSchema, path, errors, version, rootSchema)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve reference: $ref", "\$ref"))
                    }
                    return
                }
                
                // Check for $recursiveRef (2019-09)
                val recursiveRef = schemaElement["\$recursiveRef"]?.jsonPrimitive?.contentOrNull
                if (recursiveRef != null) {
                    // For basic implementation, treat $recursiveRef like $ref
                    // A full implementation would need to track $recursiveAnchor and dynamic scope
                    val resolvedSchema = referenceResolver.resolveRef(recursiveRef, rootSchema, schemaElement)
                    if (resolvedSchema != null) {
                        validateElement(instance, resolvedSchema, path, errors, version, rootSchema)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve recursive reference: $recursiveRef", "\$recursiveRef"))
                    }
                    return
                }
                
                // Check for $dynamicRef (2020-12)
                val dynamicRef = schemaElement["\$dynamicRef"]?.jsonPrimitive?.contentOrNull
                if (dynamicRef != null) {
                    // For basic implementation, treat $dynamicRef like $ref
                    // A full implementation would need to track $dynamicAnchor and dynamic scope
                    val resolvedSchema = referenceResolver.resolveRef(dynamicRef, rootSchema, schemaElement)
                    if (resolvedSchema != null) {
                        validateElement(instance, resolvedSchema, path, errors, version, rootSchema)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve dynamic reference: $dynamicRef", "\$dynamicRef"))
                    }
                    return
                }
                
                validateAgainstObjectSchema(instance, schemaElement, path, errors, version, rootSchema)
            }
            is JsonPrimitive -> {
                // Boolean schema
                if (schemaElement.isString) return
                val boolValue = schemaElement.booleanOrNull
                if (boolValue == false) {
                    errors.add(ValidationError(path, "Schema is false, no instance is valid", "false"))
                }
                // true schema allows everything
            }
            else -> {} // Arrays and nulls are ignored as schemas
        }
    }
    
    /**
     * Validates against an object schema (keyword-based)
     */
    private fun validateAgainstObjectSchema(
        instance: JsonElement,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        // Type validation
        schema["type"]?.let { typeSchema ->
            validateType(instance, typeSchema, path, errors)
        }
        
        // Const validation
        schema["const"]?.let { constValue ->
            if (!jsonEquals(instance, constValue)) {
                errors.add(ValidationError(path, "Value must be const: $constValue", "const"))
            }
        }
        
        // Enum validation
        schema["enum"]?.jsonArray?.let { enumValues ->
            if (enumValues.none { jsonEquals(it, instance) }) {
                errors.add(ValidationError(path, "Value must be one of: $enumValues", "enum"))
            }
        }
        
        when (instance) {
            is JsonObject -> validateObject(instance, schema, path, errors, version, rootSchema)
            is JsonArray -> validateArray(instance, schema, path, errors, version, rootSchema)
            is JsonPrimitive -> validatePrimitive(instance, schema, path, errors, version)
            else -> {}
        }
        
        // Combined schemas
        schema["allOf"]?.jsonArray?.let { validateAllOf(instance, it, path, errors, version, rootSchema) }
        schema["anyOf"]?.jsonArray?.let { validateAnyOf(instance, it, path, errors, version, rootSchema) }
        schema["oneOf"]?.jsonArray?.let { validateOneOf(instance, it, path, errors, version, rootSchema) }
        schema["not"]?.let { validateNot(instance, it, path, errors, version, rootSchema) }
        
        // Conditional schemas (2019-09 and later)
        schema["if"]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            validateElement(instance, ifSchema, path, ifErrors, version, rootSchema)
            
            if (ifErrors.isEmpty()) {
                // If validation passed, validate against "then"
                schema["then"]?.let { thenSchema ->
                    validateElement(instance, thenSchema, path, errors, version, rootSchema)
                }
            } else {
                // If validation failed, validate against "else"
                schema["else"]?.let { elseSchema ->
                    validateElement(instance, elseSchema, path, errors, version, rootSchema)
                }
            }
        }
    }
    
    /**
     * Validates type keyword
     */
    private fun validateType(
        instance: JsonElement,
        typeSchema: JsonElement,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        val types = when (typeSchema) {
            is JsonPrimitive -> listOf(typeSchema.content)
            is JsonArray -> typeSchema.map { it.jsonPrimitive.content }
            else -> return
        }
        
        val instanceType = getJsonType(instance)
        // In JSON Schema, "number" type accepts both integers and floats
        val isValid = if ("number" in types && instanceType == "integer") {
            true
        } else {
            instanceType in types
        }
        
        if (!isValid) {
            errors.add(ValidationError(path, "Expected type(s): ${types.joinToString()}, but got: $instanceType", "type"))
        }
    }
    
    /**
     * Gets the JSON type name of an element
     */
    private fun getJsonType(element: JsonElement): String = when {
        element is JsonNull -> "null"
        element is JsonPrimitive && element.isString -> "string"
        element is JsonPrimitive && element.booleanOrNull != null -> "boolean"
        element is JsonPrimitive -> {
            val content = element.content
            // Check if it's a number
            val doubleValue = element.doubleOrNull
            if (doubleValue != null) {
                // If it's a whole number (no fractional part), it's an integer
                if (doubleValue == kotlin.math.floor(doubleValue) && doubleValue.isFinite()) {
                    "integer"
                } else {
                    "number"
                }
            } else {
                // Fallback to string parsing
                if (content.contains('.') || content.contains('e', ignoreCase = true)) {
                    "number"
                } else {
                    "integer"
                }
            }
        }
        element is JsonObject -> "object"
        element is JsonArray -> "array"
        else -> "unknown"
    }
    
    /**
     * Compares two JSON elements for equality, considering numeric equivalence.
     * In JSON Schema, 1.0 and 1 are considered equal, as are 0.0 and 0.
     */
    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        // If they're directly equal, return true
        if (a == b) return true
        
        // Check numeric equivalence for primitives
        if (a is JsonPrimitive && b is JsonPrimitive) {
            val aNum = a.doubleOrNull
            val bNum = b.doubleOrNull
            
            // If both are numbers, compare their numeric values
            if (aNum != null && bNum != null) {
                return aNum == bNum
            }
        }
        
        // Check arrays recursively
        if (a is JsonArray && b is JsonArray) {
            if (a.size != b.size) return false
            return a.indices.all { jsonEquals(a[it], b[it]) }
        }
        
        // Check objects recursively
        if (a is JsonObject && b is JsonObject) {
            if (a.size != b.size) return false
            return a.keys.all { key ->
                b.containsKey(key) && jsonEquals(a[key]!!, b[key]!!)
            }
        }
        
        return false
    }
    
    /**
     * Validates an object instance
     */
    private fun validateObject(
        instance: JsonObject,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        // Properties validation
        schema["properties"]?.jsonObject?.let { properties ->
            properties.forEach { (propName, propSchema) ->
                instance[propName]?.let { propValue ->
                    val propPath = if (path.isEmpty()) propName else "$path.$propName"
                    validateElement(propValue, propSchema, propPath, errors, version, rootSchema)
                }
            }
        }
        
        // Required properties
        schema["required"]?.jsonArray?.let { required ->
            required.forEach { requiredProp ->
                val propName = requiredProp.jsonPrimitive.content
                if (propName !in instance) {
                    errors.add(ValidationError(path, "Required property '$propName' is missing", "required"))
                }
            }
        }
        
        // Additional properties
        schema["additionalProperties"]?.let { additionalPropsSchema ->
            val definedProps = schema["properties"]?.jsonObject?.keys ?: emptySet()
            val patternProps = schema["patternProperties"]?.jsonObject?.keys ?: emptySet()
            
            instance.keys.forEach { propName ->
                if (propName !in definedProps && !matchesAnyPattern(propName, patternProps)) {
                    val propPath = if (path.isEmpty()) propName else "$path.$propName"
                    when (additionalPropsSchema) {
                        is JsonPrimitive -> {
                            if (additionalPropsSchema.booleanOrNull == false) {
                                errors.add(ValidationError(path, "Additional property '$propName' is not allowed", "additionalProperties"))
                            }
                        }
                        else -> {
                            validateElement(instance[propName]!!, additionalPropsSchema, propPath, errors, version, rootSchema)
                        }
                    }
                }
            }
        }
        
        // Pattern properties
        schema["patternProperties"]?.jsonObject?.let { patternProps ->
            patternProps.forEach { (pattern, propSchema) ->
                instance.keys.forEach { propName ->
                    if (Regex(pattern).containsMatchIn(propName)) {
                        val propPath = if (path.isEmpty()) propName else "$path.$propName"
                        validateElement(instance[propName]!!, propSchema, propPath, errors, version, rootSchema)
                    }
                }
            }
        }
        
        // Min/Max properties (support decimal values per spec)
        schema["minProperties"]?.jsonPrimitive?.let { minPropsValue ->
            val minProps = minPropsValue.doubleOrNull?.toInt() ?: minPropsValue.intOrNull ?: 0
            if (instance.size < minProps) {
                errors.add(ValidationError(path, "Object has ${instance.size} properties, minimum is $minProps", "minProperties"))
            }
        }
        
        schema["maxProperties"]?.jsonPrimitive?.let { maxPropsValue ->
            val maxProps = maxPropsValue.doubleOrNull?.toInt() ?: maxPropsValue.intOrNull ?: Int.MAX_VALUE
            if (instance.size > maxProps) {
                errors.add(ValidationError(path, "Object has ${instance.size} properties, maximum is $maxProps", "maxProperties"))
            }
        }
        
        // Property names (2019-09 and later)
        schema["propertyNames"]?.let { propNamesSchema ->
            instance.keys.forEach { propName ->
                val propNameElement = JsonPrimitive(propName)
                validateElement(propNameElement, propNamesSchema, "$path.<propertyName>", errors, version, rootSchema)
            }
        }
        
        // Dependent required (2019-09 and later)
        schema["dependentRequired"]?.jsonObject?.let { depRequired ->
            depRequired.forEach { (propName, requiredProps) ->
                if (propName in instance) {
                    requiredProps.jsonArray.forEach { reqProp ->
                        val reqPropName = reqProp.jsonPrimitive.content
                        if (reqPropName !in instance) {
                            errors.add(ValidationError(path, "Property '$propName' requires property '$reqPropName'", "dependentRequired"))
                        }
                    }
                }
            }
        }
        
        // Dependent schemas (2019-09 and later)
        schema["dependentSchemas"]?.jsonObject?.let { depSchemas ->
            depSchemas.forEach { (propName, depSchema) ->
                if (propName in instance) {
                    validateElement(instance, depSchema, path, errors, version, rootSchema)
                }
            }
        }
    }
    
    /**
     * Validates an array instance
     */
    private fun validateArray(
        instance: JsonArray,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        // Prefix items (2020-12)
        val hasPrefixItems = schema.containsKey("prefixItems")
        
        if (hasPrefixItems) {
            schema["prefixItems"]?.jsonArray?.let { prefixItems ->
                prefixItems.forEachIndexed { index, itemSchema ->
                    if (index < instance.size) {
                        val itemPath = "$path[$index]"
                        validateElement(instance[index], itemSchema, itemPath, errors, version, rootSchema)
                    }
                }
                
                // In 2020-12, if both prefixItems and items exist, items applies to remaining items
                schema["items"]?.let { itemsSchema ->
                    for (index in prefixItems.size until instance.size) {
                        val itemPath = "$path[$index]"
                        validateElement(instance[index], itemsSchema, itemPath, errors, version, rootSchema)
                    }
                }
            }
        } else {
            // Items validation (only when prefixItems is not present)
            schema["items"]?.let { itemsSchema ->
                when (itemsSchema) {
                    is JsonObject, is JsonPrimitive -> {
                        // Single schema for all items
                        instance.forEachIndexed { index, item ->
                            val itemPath = "$path[$index]"
                            validateElement(item, itemsSchema, itemPath, errors, version, rootSchema)
                        }
                    }
                    is JsonArray -> {
                        // Tuple validation (deprecated in 2020-12 but still supported)
                        itemsSchema.forEachIndexed { index, itemSchema ->
                            if (index < instance.size) {
                                val itemPath = "$path[$index]"
                                validateElement(instance[index], itemSchema, itemPath, errors, version, rootSchema)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        
        // Additional items (for tuple validation)
        if (schema.containsKey("items") && schema["items"] is JsonArray) {
            val itemsCount = (schema["items"] as JsonArray).size
            schema["additionalItems"]?.let { additionalItemsSchema ->
                for (index in itemsCount until instance.size) {
                    val itemPath = "$path[$index]"
                    when (additionalItemsSchema) {
                        is JsonPrimitive -> {
                            if (additionalItemsSchema.booleanOrNull == false) {
                                errors.add(ValidationError(itemPath, "Additional items are not allowed", "additionalItems"))
                            }
                        }
                        else -> {
                            validateElement(instance[index], additionalItemsSchema, itemPath, errors, version, rootSchema)
                        }
                    }
                }
            }
        }
        
        // Contains
        schema["contains"]?.let { containsSchema ->
            val matchingIndices = mutableListOf<Int>()
            instance.forEachIndexed { index, item ->
                val itemErrors = mutableListOf<ValidationError>()
                validateElement(item, containsSchema, "$path[$index]", itemErrors, version, rootSchema)
                if (itemErrors.isEmpty()) {
                    matchingIndices.add(index)
                }
            }
            
            // Min/Max contains (2019-09 and later) - handle decimal values
            val minContains = schema["minContains"]?.jsonPrimitive?.let { 
                it.doubleOrNull?.toInt() ?: it.intOrNull ?: 1
            } ?: 1
            
            val maxContains = schema["maxContains"]?.jsonPrimitive?.let {
                it.doubleOrNull?.toInt() ?: it.intOrNull
            }
            
            // Special case: if minContains is 0, contains is always valid
            if (minContains == 0) {
                // Check maxContains only
                maxContains?.let { max ->
                    if (matchingIndices.size > max) {
                        errors.add(ValidationError(path, "Array contains ${matchingIndices.size} matching items, maximum is $max", "maxContains"))
                    }
                }
            } else {
                // Normal case: minContains >= 1
                if (matchingIndices.size < minContains) {
                    errors.add(ValidationError(path, "Array contains ${matchingIndices.size} matching items, minimum is $minContains", if (schema.containsKey("minContains")) "minContains" else "contains"))
                }
                
                maxContains?.let { max ->
                    if (matchingIndices.size > max) {
                        errors.add(ValidationError(path, "Array contains ${matchingIndices.size} matching items, maximum is $max", "maxContains"))
                    }
                }
            }
        }
        
        // Min/Max items (support decimal values per spec)
        schema["minItems"]?.jsonPrimitive?.let { minItemsValue ->
            val minItems = minItemsValue.doubleOrNull?.toInt() ?: minItemsValue.intOrNull ?: 0
            if (instance.size < minItems) {
                errors.add(ValidationError(path, "Array has ${instance.size} items, minimum is $minItems", "minItems"))
            }
        }
        
        schema["maxItems"]?.jsonPrimitive?.let { maxItemsValue ->
            val maxItems = maxItemsValue.doubleOrNull?.toInt() ?: maxItemsValue.intOrNull ?: Int.MAX_VALUE
            if (instance.size > maxItems) {
                errors.add(ValidationError(path, "Array has ${instance.size} items, maximum is $maxItems", "maxItems"))
            }
        }
        
        // Unique items
        schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull?.let { uniqueItems ->
            if (uniqueItems) {
                // Check for duplicates using jsonEquals for numeric equivalence
                for (i in instance.indices) {
                    for (j in (i + 1) until instance.size) {
                        if (jsonEquals(instance[i], instance[j])) {
                            errors.add(ValidationError(path, "Array items must be unique", "uniqueItems"))
                            return@let
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Helper function to count Unicode codepoints (not UTF-16 code units).
     * JSON Schema requires counting codepoints for minLength/maxLength.
     * Example: "💩" has 1 codepoint but 2 UTF-16 code units.
     */
    private fun String.codepointLength(): Int = this.codePointCount(0, this.length)
    
    /**
     * Validates a primitive instance
     */
    private fun validatePrimitive(
        instance: JsonPrimitive,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion
    ) {
        when {
            instance.isString -> validateString(instance.content, schema, path, errors, version)
            else -> validateNumber(instance, schema, path, errors)
        }
    }
    
    /**
     * Validates a string value
     */
    private fun validateString(
        value: String,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion
    ) {
        // Min/Max length (support decimal values per spec)
        // Note: JSON Schema counts Unicode codepoints, not UTF-16 code units
        schema["minLength"]?.jsonPrimitive?.let { minLengthValue ->
            val minLength = minLengthValue.doubleOrNull?.toInt() ?: minLengthValue.intOrNull ?: 0
            val length = value.codepointLength()
            if (length < minLength) {
                errors.add(ValidationError(path, "String length is $length codepoints, minimum is $minLength", "minLength"))
            }
        }
        
        schema["maxLength"]?.jsonPrimitive?.let { maxLengthValue ->
            val maxLength = maxLengthValue.doubleOrNull?.toInt() ?: maxLengthValue.intOrNull ?: Int.MAX_VALUE
            val length = value.codepointLength()
            if (length > maxLength) {
                errors.add(ValidationError(path, "String length is $length codepoints, maximum is $maxLength", "maxLength"))
            }
        }
        
        // Pattern
        schema["pattern"]?.jsonPrimitive?.contentOrNull?.let { pattern ->
            if (!Regex(pattern).containsMatchIn(value)) {
                errors.add(ValidationError(path, "String does not match pattern: $pattern", "pattern"))
            }
        }
        
        // Format (basic validation)
        // In 2020-12, format is an annotation by default unless formatAssertion is enabled
        schema["format"]?.jsonPrimitive?.contentOrNull?.let { format ->
            if (formatAssertion || version == SchemaVersion.DRAFT_2019_09) {
                validateFormat(value, format, path, errors)
            }
        }
    }
    
    /**
     * Validates format keyword (basic implementation)
     */
    private fun validateFormat(
        value: String,
        format: String,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        val valid = when (format) {
            "email" -> value.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}$"))
            "uri" -> value.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"))
            "date" -> value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))
            "time" -> value.matches(Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$"))
            "date-time" -> value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$"))
            "ipv4" -> value.matches(Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"))
            "ipv6" -> value.matches(Regex("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"))
            "uuid" -> value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
            else -> true // Unknown formats are ignored
        }
        
        if (!valid) {
            errors.add(ValidationError(path, "String does not match format: $format", "format"))
        }
    }
    
    /**
     * Validates a number value
     */
    private fun validateNumber(
        instance: JsonPrimitive,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        val number = instance.doubleOrNull ?: return
        
        // Minimum
        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
            if (number < minimum) {
                errors.add(ValidationError(path, "Number $number is less than minimum $minimum", "minimum"))
            }
        }
        
        // Maximum
        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
            if (number > maximum) {
                errors.add(ValidationError(path, "Number $number is greater than maximum $maximum", "maximum"))
            }
        }
        
        // Exclusive minimum
        schema["exclusiveMinimum"]?.let { exclusiveMin ->
            when (exclusiveMin) {
                is JsonPrimitive -> {
                    if (exclusiveMin.booleanOrNull == true) {
                        // Draft 4 style with separate minimum
                        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
                            if (number <= minimum) {
                                errors.add(ValidationError(path, "Number $number must be greater than $minimum", "exclusiveMinimum"))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        exclusiveMin.doubleOrNull?.let { minimum ->
                            if (number <= minimum) {
                                errors.add(ValidationError(path, "Number $number must be greater than $minimum", "exclusiveMinimum"))
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        
        // Exclusive maximum
        schema["exclusiveMaximum"]?.let { exclusiveMax ->
            when (exclusiveMax) {
                is JsonPrimitive -> {
                    if (exclusiveMax.booleanOrNull == true) {
                        // Draft 4 style with separate maximum
                        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
                            if (number >= maximum) {
                                errors.add(ValidationError(path, "Number $number must be less than $maximum", "exclusiveMaximum"))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        exclusiveMax.doubleOrNull?.let { maximum ->
                            if (number >= maximum) {
                                errors.add(ValidationError(path, "Number $number must be less than $maximum", "exclusiveMaximum"))
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        
        // Multiple of
        schema["multipleOf"]?.jsonPrimitive?.doubleOrNull?.let { multipleOf ->
            if (multipleOf > 0) {
                val quotient = number / multipleOf
                // Handle infinity case (division by very small number)
                if (!quotient.isFinite()) {
                    errors.add(ValidationError(path, "Number $number is not a multiple of $multipleOf", "multipleOf"))
                } else {
                    val rounded = kotlin.math.round(quotient)
                    val diff = kotlin.math.abs(quotient - rounded)
                    // Use relative epsilon for better floating point comparison
                    val epsilon = kotlin.math.max(1e-10, kotlin.math.abs(quotient) * 1e-10)
                    if (diff > epsilon) {
                        errors.add(ValidationError(path, "Number $number is not a multiple of $multipleOf", "multipleOf"))
                    }
                }
            }
        }
    }
    
    /**
     * Validates allOf combiner
     */
    private fun validateAllOf(
        instance: JsonElement,
        schemas: JsonArray,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        schemas.forEach { schema ->
            validateElement(instance, schema, path, errors, version, rootSchema)
        }
    }
    
    /**
     * Validates anyOf combiner
     */
    private fun validateAnyOf(
        instance: JsonElement,
        schemas: JsonArray,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        val anyValid = schemas.any { schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, tempErrors, version, rootSchema)
            tempErrors.isEmpty()
        }
        
        if (!anyValid) {
            errors.add(ValidationError(path, "Instance does not match any of the schemas", "anyOf"))
        }
    }
    
    /**
     * Validates oneOf combiner
     */
    private fun validateOneOf(
        instance: JsonElement,
        schemas: JsonArray,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        val validCount = schemas.count { schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, tempErrors, version, rootSchema)
            tempErrors.isEmpty()
        }
        
        when (validCount) {
            0 -> errors.add(ValidationError(path, "Instance does not match any of the oneOf schemas", "oneOf"))
            1 -> {} // Valid
            else -> errors.add(ValidationError(path, "Instance matches more than one oneOf schema", "oneOf"))
        }
    }
    
    /**
     * Validates not combiner
     */
    private fun validateNot(
        instance: JsonElement,
        schema: JsonElement,
        path: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement
    ) {
        val tempErrors = mutableListOf<ValidationError>()
        validateElement(instance, schema, path, tempErrors, version, rootSchema)
        
        if (tempErrors.isEmpty()) {
            errors.add(ValidationError(path, "Instance matches the not schema but should not", "not"))
        }
    }
    
    /**
     * Checks if a property name matches any pattern
     */
    private fun matchesAnyPattern(propName: String, patterns: Set<String>): Boolean {
        return patterns.any { Regex(it).containsMatchIn(propName) }
    }
    
    /**
     * Validates the structure of a schema itself
     */
    private fun validateSchemaStructure(
        schema: JsonElement,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        when (schema) {
            is JsonObject -> {
                // Check for invalid combinations
                val validTypes = setOf("string", "number", "integer", "boolean", "object", "array", "null")
                if (schema.containsKey("type")) {
                    val type = schema["type"]
                    when (type) {
                        is JsonPrimitive -> {
                            if (!type.isString) {
                                errors.add(ValidationError(path, "type must be a string or array of strings", "\$schema"))
                            } else if (type.content !in validTypes) {
                                errors.add(ValidationError(path, "type value '${type.content}' is not a valid JSON type", "\$schema"))
                            }
                        }
                        is JsonArray -> {
                            type.forEach { typeElement ->
                                if (typeElement !is JsonPrimitive || !typeElement.isString) {
                                    errors.add(ValidationError(path, "type array must contain only strings", "\$schema"))
                                } else if (typeElement.content !in validTypes) {
                                    errors.add(ValidationError(path, "type value '${typeElement.content}' is not a valid JSON type", "\$schema"))
                                }
                            }
                        }
                        else -> {
                            errors.add(ValidationError(path, "type must be a string or array", "\$schema"))
                        }
                    }
                }
                
                // Validate $schema if present
                schema["\$schema"]?.let { schemaUri ->
                    if (schemaUri !is JsonPrimitive || !schemaUri.isString) {
                        errors.add(ValidationError(path, "\$schema must be a string", "\$schema"))
                    }
                }
                
                // Recursively validate nested schemas
                schema.forEach { (key, value) ->
                    val newPath = if (path.isEmpty()) key else "$path.$key"
                    when (key) {
                        "properties", "patternProperties", "dependentSchemas" -> {
                            if (value is JsonObject) {
                                value.forEach { (propName, propSchema) ->
                                    validateSchemaStructure(propSchema, "$newPath.$propName", errors)
                                }
                            }
                        }
                        "items", "additionalProperties", "additionalItems", "contains", "propertyNames",
                        "if", "then", "else", "not" -> {
                            validateSchemaStructure(value, newPath, errors)
                        }
                        "allOf", "anyOf", "oneOf", "prefixItems" -> {
                            if (value is JsonArray) {
                                value.forEachIndexed { index, subSchema ->
                                    validateSchemaStructure(subSchema, "$newPath[$index]", errors)
                                }
                            }
                        }
                    }
                }
            }
            is JsonPrimitive -> {
                // Boolean schemas are valid
                if (schema.booleanOrNull == null && !schema.isString) {
                    errors.add(ValidationError(path, "Schema must be an object or boolean", "\$schema"))
                }
            }
            else -> {
                errors.add(ValidationError(path, "Schema must be an object or boolean", "\$schema"))
            }
        }
    }
}

