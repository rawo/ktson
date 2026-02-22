package org.ktson

import kotlinx.serialization.json.*
import org.ktson.SchemaKeywords.ADDITIONAL_ITEMS
import org.ktson.SchemaKeywords.ADDITIONAL_PROPERTIES
import org.ktson.SchemaKeywords.ALL_OF
import org.ktson.SchemaKeywords.ANY_OF
import org.ktson.SchemaKeywords.CONST
import org.ktson.SchemaKeywords.CONTAINS
import org.ktson.SchemaKeywords.DEPENDENT_REQUIRED
import org.ktson.SchemaKeywords.DEPENDENT_SCHEMAS
import org.ktson.SchemaKeywords.DYNAMIC_ANCHOR
import org.ktson.SchemaKeywords.DYNAMIC_REF
import org.ktson.SchemaKeywords.ELSE
import org.ktson.SchemaKeywords.ENUM
import org.ktson.SchemaKeywords.EXCLUSIVE_MAXIMUM
import org.ktson.SchemaKeywords.EXCLUSIVE_MINIMUM
import org.ktson.SchemaKeywords.FORMAT
import org.ktson.SchemaKeywords.FORMAT_DATE
import org.ktson.SchemaKeywords.FORMAT_DATE_TIME
import org.ktson.SchemaKeywords.FORMAT_EMAIL
import org.ktson.SchemaKeywords.FORMAT_IPV4
import org.ktson.SchemaKeywords.FORMAT_IPV6
import org.ktson.SchemaKeywords.FORMAT_TIME
import org.ktson.SchemaKeywords.FORMAT_URI
import org.ktson.SchemaKeywords.FORMAT_UUID
import org.ktson.SchemaKeywords.IF
import org.ktson.SchemaKeywords.ITEMS
import org.ktson.SchemaKeywords.MAXIMUM
import org.ktson.SchemaKeywords.MAX_CONTAINS
import org.ktson.SchemaKeywords.MAX_ITEMS
import org.ktson.SchemaKeywords.MAX_LENGTH
import org.ktson.SchemaKeywords.MAX_PROPERTIES
import org.ktson.SchemaKeywords.MINIMUM
import org.ktson.SchemaKeywords.MIN_CONTAINS
import org.ktson.SchemaKeywords.MIN_ITEMS
import org.ktson.SchemaKeywords.MIN_LENGTH
import org.ktson.SchemaKeywords.MIN_PROPERTIES
import org.ktson.SchemaKeywords.MULTIPLE_OF
import org.ktson.SchemaKeywords.NOT
import org.ktson.SchemaKeywords.ONE_OF
import org.ktson.SchemaKeywords.PATTERN
import org.ktson.SchemaKeywords.PATTERN_PROPERTIES
import org.ktson.SchemaKeywords.PREFIX_ITEMS
import org.ktson.SchemaKeywords.PROPERTIES
import org.ktson.SchemaKeywords.PROPERTY_NAMES
import org.ktson.SchemaKeywords.RECURSIVE_REF
import org.ktson.SchemaKeywords.REF
import org.ktson.SchemaKeywords.REQUIRED
import org.ktson.SchemaKeywords.SCHEMA
import org.ktson.SchemaKeywords.SCHEMA_FALSE
import org.ktson.SchemaKeywords.THEN
import org.ktson.SchemaKeywords.TYPE
import org.ktson.SchemaKeywords.TYPE_ARRAY
import org.ktson.SchemaKeywords.TYPE_BOOLEAN
import org.ktson.SchemaKeywords.TYPE_INTEGER
import org.ktson.SchemaKeywords.TYPE_NULL
import org.ktson.SchemaKeywords.TYPE_NUMBER
import org.ktson.SchemaKeywords.TYPE_OBJECT
import org.ktson.SchemaKeywords.TYPE_STRING
import org.ktson.SchemaKeywords.TYPE_UNKNOWN
import org.ktson.SchemaKeywords.UNEVALUATED_PROPERTIES
import org.ktson.SchemaKeywords.UNIQUE_ITEMS

/**
 * Main JSON Schema validator with thread-safe synchronous validation
 */
class JsonValidator(
    private val enableMetaSchemaValidation: Boolean = true,
    // Draft 2020-12 uses annotation by default
    private val formatAssertion: Boolean = true,
    private val maxValidationDepth: Int = 1000,
) {
    private val referenceResolver = ReferenceResolver()

    /**
     * Validates a JSON instance against a JSON schema
     * Thread-safe synchronous implementation
     */
    fun validate(instance: JsonElement, schema: JsonSchema): ValidationResult = validateInternal(instance, schema, "")

    /**
     * Validates a JSON instance from string against a schema from string
     */
    fun validate(instanceJson: String, schemaJson: String, schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12): ValidationResult {
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
    private fun validateInternal(instance: JsonElement, schema: JsonSchema, path: String): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        validateElement(instance, schema.schema, path, errors, schema.effectiveVersion, schema.schema, depth = 0, resourceRoot = schema.schema, dynamicScope = emptyList())

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
        rootSchema: JsonElement,
        depth: Int = 0,
        resourceRoot: JsonElement = rootSchema,
        dynamicScope: List<JsonElement> = emptyList(),
    ) {
        // Check depth limit to prevent stack overflow
        if (depth > maxValidationDepth) {
            errors.add(
                ValidationError(
                    path,
                    "Maximum validation depth ($maxValidationDepth) exceeded. This may indicate circular schema references or extremely deep nesting.",
                    "depth",
                ),
            )
            return
        }

        // Update resource root and dynamic scope when entering a new schema resource (one with $id)
        val effectiveResourceRoot = if (schemaElement is JsonObject && schemaElement.containsKey(SchemaKeywords.ID)) schemaElement else resourceRoot
        val effectiveScope = if (schemaElement is JsonObject && schemaElement.containsKey(SchemaKeywords.ID)) dynamicScope + schemaElement else dynamicScope

        when (schemaElement) {
            is JsonObject -> {
                // $ref, $recursiveRef, $dynamicRef are evaluated alongside sibling keywords
                // (Draft 2019-09+ removed the Draft 7 rule that $ref short-circuits siblings)
                val ref = schemaElement[REF]?.jsonPrimitive?.contentOrNull
                if (ref != null) {
                    val refHashIndex = ref.indexOf('#')
                    val refUriPart = if (refHashIndex >= 0) ref.substring(0, refHashIndex) else ref
                    // For local refs, resolve against effectiveResourceRoot (current $id boundary);
                    // for URI-based refs, search the whole document
                    val resolvedSchema =
                        if (refUriPart.isEmpty()) {
                            referenceResolver.resolveRef(ref, effectiveResourceRoot, schemaElement)
                        } else {
                            referenceResolver.resolveRef(ref, rootSchema, schemaElement)
                        }
                    if (resolvedSchema != null) {
                        validateElement(instance, resolvedSchema, path, errors, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve reference: $ref", REF))
                    }
                }

                // Check for $recursiveRef (2019-09) — dynamic scoping behavior
                val recursiveRef = schemaElement[RECURSIVE_REF]?.jsonPrimitive?.contentOrNull
                if (recursiveRef != null) {
                    val resolved = referenceResolver.resolveRef(recursiveRef, effectiveResourceRoot, schemaElement)
                    if (resolved != null) {
                        // If resolved schema has $recursiveAnchor: true, use outermost in dynamic scope with that flag
                        val target = if (resolved is JsonObject && resolved[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true) {
                            effectiveScope.firstOrNull { schema ->
                                schema is JsonObject && schema[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true
                            } ?: resolved
                        } else {
                            resolved
                        }
                        validateElement(instance, target, path, errors, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve recursive reference: $recursiveRef", RECURSIVE_REF))
                    }
                }

                // Check for $dynamicRef (2020-12)
                val dynamicRef = schemaElement[DYNAMIC_REF]?.jsonPrimitive?.contentOrNull
                if (dynamicRef != null) {
                    val hashIndex = dynamicRef.indexOf('#')
                    val uriPart = if (hashIndex >= 0) dynamicRef.substring(0, hashIndex) else dynamicRef
                    val fragment = if (hashIndex >= 0) dynamicRef.substring(hashIndex + 1) else ""
                    val isPlainAnchorFragment = fragment.isNotEmpty() && !fragment.startsWith("/")

                    // For local refs resolve against effectiveResourceRoot; for URI-based search whole document
                    val initialTarget =
                        if (uriPart.isEmpty()) {
                            referenceResolver.resolveRef(dynamicRef, effectiveResourceRoot, schemaElement)
                        } else {
                            referenceResolver.resolveRef(dynamicRef, rootSchema, schemaElement)
                        }

                    if (initialTarget != null) {
                        val target =
                            if (isPlainAnchorFragment && initialTarget is JsonObject &&
                                initialTarget[DYNAMIC_ANCHOR]?.jsonPrimitive?.contentOrNull == fragment
                            ) {
                                // Dynamic resolution: walk scope from outermost, find first resource with $dynamicAnchor
                                effectiveScope.firstNotNullOfOrNull { scopeSchema ->
                                    referenceResolver.findDynamicAnchorInResource(scopeSchema, fragment)
                                } ?: initialTarget
                            } else {
                                initialTarget
                            }
                        validateElement(instance, target, path, errors, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve dynamic reference: $dynamicRef", DYNAMIC_REF))
                    }
                }

                validateAgainstObjectSchema(instance, schemaElement, path, errors, version, rootSchema, depth, effectiveResourceRoot, effectiveScope)
            }
            is JsonPrimitive -> {
                // Boolean schema
                if (schemaElement.isString) return
                val boolValue = schemaElement.booleanOrNull
                if (boolValue == false) {
                    errors.add(ValidationError(path, "Schema is false, no instance is valid", SCHEMA_FALSE))
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        // Type validation
        schema[TYPE]?.let { typeSchema ->
            validateType(instance, typeSchema, path, errors)
        }

        // Const validation
        schema[CONST]?.let { constValue ->
            if (!jsonEquals(instance, constValue)) {
                errors.add(ValidationError(path, "Value must be const: $constValue", CONST))
            }
        }

        // Enum validation
        schema[ENUM]?.jsonArray?.let { enumValues ->
            if (enumValues.none { jsonEquals(it, instance) }) {
                errors.add(ValidationError(path, "Value must be one of: $enumValues", ENUM))
            }
        }

        when (instance) {
            is JsonObject -> validateObject(instance, schema, path, errors, version, rootSchema, depth, resourceRoot, dynamicScope)
            is JsonArray -> validateArray(instance, schema, path, errors, version, rootSchema, depth, resourceRoot, dynamicScope)
            is JsonPrimitive -> validatePrimitive(instance, schema, path, errors, version)
        }

        // Combined schemas
        schema[ALL_OF]?.jsonArray?.let { validateAllOf(instance, it, path, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }
        schema[ANY_OF]?.jsonArray?.let { validateAnyOf(instance, it, path, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }
        schema[ONE_OF]?.jsonArray?.let { validateOneOf(instance, it, path, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }
        schema[NOT]?.let { validateNot(instance, it, path, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }

        // Conditional schemas (2019-09 and later)
        schema[IF]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            validateElement(instance, ifSchema, path, ifErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)

            if (ifErrors.isEmpty()) {
                // If validation passed, validate against "then"
                schema[THEN]?.let { thenSchema ->
                    validateElement(instance, thenSchema, path, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                }
            } else {
                // If validation failed, validate against "else"
                schema[ELSE]?.let { elseSchema ->
                    validateElement(instance, elseSchema, path, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                }
            }
        }

        // Unevaluated properties (2019-09 and later)
        if (instance is JsonObject) {
            schema[UNEVALUATED_PROPERTIES]?.let { unevalPropsSchema ->
                val evaluated = collectEvaluatedProperties(instance, schema, path, version, rootSchema, depth, resourceRoot, dynamicScope)
                instance.keys.forEach { propName ->
                    if (propName !in evaluated) {
                        val propPath = if (path.isEmpty()) propName else "$path.$propName"
                        when {
                            unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == false ->
                                errors.add(ValidationError(path, "Unevaluated property '$propName' is not allowed", UNEVALUATED_PROPERTIES))
                            unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == true -> {}
                            else ->
                                validateElement(instance[propName]!!, unevalPropsSchema, propPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates type keyword
     */
    private fun validateType(instance: JsonElement, typeSchema: JsonElement, path: String, errors: MutableList<ValidationError>) {
        val types = when (typeSchema) {
            is JsonPrimitive -> listOf(typeSchema.content)
            is JsonArray -> typeSchema.map { it.jsonPrimitive.content }
            else -> return
        }

        val instanceType = getJsonType(instance)
        // In JSON Schema, "number" type accepts both integers and floats
        val isValid = if (TYPE_NUMBER in types && instanceType == TYPE_INTEGER) {
            true
        } else {
            instanceType in types
        }

        if (!isValid) {
            errors.add(ValidationError(path, "Expected type(s): ${types.joinToString()}, but got: $instanceType", TYPE))
        }
    }

    /**
     * Gets the JSON type name of an element
     */
    private fun getJsonType(element: JsonElement): String = when (element) {
        is JsonNull -> TYPE_NULL
        is JsonPrimitive if element.isString -> TYPE_STRING
        is JsonPrimitive if element.booleanOrNull != null -> TYPE_BOOLEAN
        is JsonPrimitive -> {
            val content = element.content
            // Check if it's a number
            val doubleValue = element.doubleOrNull
            if (doubleValue != null) {
                // If it's a whole number (no fractional part), it's an integer
                if (doubleValue == kotlin.math.floor(doubleValue) && doubleValue.isFinite()) {
                    TYPE_INTEGER
                } else {
                    TYPE_NUMBER
                }
            } else {
                // Fallback to string parsing
                if (content.contains('.') || content.contains('e', ignoreCase = true)) {
                    TYPE_NUMBER
                } else {
                    TYPE_INTEGER
                }
            }
        }

        is JsonObject -> TYPE_OBJECT
        is JsonArray -> TYPE_ARRAY
        else -> TYPE_UNKNOWN
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        // Properties validation
        schema[PROPERTIES]?.jsonObject?.let { properties ->
            properties.forEach { (propName, propSchema) ->
                instance[propName]?.let { propValue ->
                    val propPath = if (path.isEmpty()) propName else "$path.$propName"
                    validateElement(propValue, propSchema, propPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                }
            }
        }

        // Required properties
        schema[REQUIRED]?.jsonArray?.let { required ->
            required.forEach { requiredProp ->
                val propName = requiredProp.jsonPrimitive.content
                if (propName !in instance) {
                    errors.add(ValidationError(path, "Required property '$propName' is missing", REQUIRED))
                }
            }
        }

        // Additional properties
        schema[ADDITIONAL_PROPERTIES]?.let { additionalPropsSchema ->
            val definedProps = schema[PROPERTIES]?.jsonObject?.keys ?: emptySet()
            val patternProps = schema[PATTERN_PROPERTIES]?.jsonObject?.keys ?: emptySet()

            instance.keys.forEach { propName ->
                if (propName !in definedProps && !matchesAnyPattern(propName, patternProps)) {
                    val propPath = if (path.isEmpty()) propName else "$path.$propName"
                    when (additionalPropsSchema) {
                        is JsonPrimitive -> {
                            if (additionalPropsSchema.booleanOrNull == false) {
                                errors.add(ValidationError(path, "Additional property '$propName' is not allowed", ADDITIONAL_PROPERTIES))
                            }
                        }
                        else -> {
                            validateElement(instance[propName]!!, additionalPropsSchema, propPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Pattern properties
        schema[PATTERN_PROPERTIES]?.jsonObject?.let { patternProps ->
            patternProps.forEach { (pattern, propSchema) ->
                instance.keys.forEach { propName ->
                    if (Regex(pattern).containsMatchIn(propName)) {
                        val propPath = if (path.isEmpty()) propName else "$path.$propName"
                        validateElement(instance[propName]!!, propSchema, propPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    }
                }
            }
        }

        // Min/Max properties (support decimal values per spec)
        schema[MIN_PROPERTIES]?.jsonPrimitive?.let { minPropsValue ->
            val minProps = minPropsValue.doubleOrNull?.toInt() ?: minPropsValue.intOrNull ?: 0
            if (instance.size < minProps) {
                errors.add(ValidationError(path, "Object has ${instance.size} properties, minimum is $minProps", MIN_PROPERTIES))
            }
        }

        schema[MAX_PROPERTIES]?.jsonPrimitive?.let { maxPropsValue ->
            val maxProps = maxPropsValue.doubleOrNull?.toInt() ?: maxPropsValue.intOrNull ?: Int.MAX_VALUE
            if (instance.size > maxProps) {
                errors.add(ValidationError(path, "Object has ${instance.size} properties, maximum is $maxProps", MAX_PROPERTIES))
            }
        }

        // Property names (2019-09 and later)
        schema[PROPERTY_NAMES]?.let { propNamesSchema ->
            instance.keys.forEach { propName ->
                val propNameElement = JsonPrimitive(propName)
                validateElement(propNameElement, propNamesSchema, "$path.<propertyName>", errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            }
        }

        // Dependent required (2019-09 and later)
        schema[DEPENDENT_REQUIRED]?.jsonObject?.let { depRequired ->
            depRequired.forEach { (propName, requiredProps) ->
                if (propName in instance) {
                    requiredProps.jsonArray.forEach { reqProp ->
                        val reqPropName = reqProp.jsonPrimitive.content
                        if (reqPropName !in instance) {
                            errors.add(ValidationError(path, "Property '$propName' requires property '$reqPropName'", DEPENDENT_REQUIRED))
                        }
                    }
                }
            }
        }

        // Dependent schemas (2019-09 and later)
        schema[DEPENDENT_SCHEMAS]?.jsonObject?.let { depSchemas ->
            depSchemas.forEach { (propName, depSchema) ->
                if (propName in instance) {
                    validateElement(instance, depSchema, path, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        // Prefix items (2020-12)
        val hasPrefixItems = schema.containsKey(PREFIX_ITEMS)

        if (hasPrefixItems) {
            schema[PREFIX_ITEMS]?.jsonArray?.let { prefixItems ->
                prefixItems.forEachIndexed { index, itemSchema ->
                    if (index < instance.size) {
                        val itemPath = "$path[$index]"
                        validateElement(instance[index], itemSchema, itemPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    }
                }

                // In 2020-12, if both prefixItems and items exist, items applies to remaining items
                schema[ITEMS]?.let { itemsSchema ->
                    for (index in prefixItems.size until instance.size) {
                        val itemPath = "$path[$index]"
                        validateElement(instance[index], itemsSchema, itemPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    }
                }
            }
        } else {
            // Items validation (only when prefixItems is not present)
            schema[ITEMS]?.let { itemsSchema ->
                when (itemsSchema) {
                    is JsonObject, is JsonPrimitive -> {
                        // Single schema for all items
                        instance.forEachIndexed { index, item ->
                            val itemPath = "$path[$index]"
                            validateElement(item, itemsSchema, itemPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                    is JsonArray -> {
                        // Tuple validation (deprecated in 2020-12 but still supported)
                        itemsSchema.forEachIndexed { index, itemSchema ->
                            if (index < instance.size) {
                                val itemPath = "$path[$index]"
                                validateElement(instance[index], itemSchema, itemPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                            }
                        }
                    }
                }
            }
        }

        // Additional items (for tuple validation)
        if (schema.containsKey(ITEMS) && schema[ITEMS] is JsonArray) {
            val itemsCount = (schema[ITEMS] as JsonArray).size
            schema[ADDITIONAL_ITEMS]?.let { additionalItemsSchema ->
                for (index in itemsCount until instance.size) {
                    val itemPath = "$path[$index]"
                    when (additionalItemsSchema) {
                        is JsonPrimitive -> {
                            if (additionalItemsSchema.booleanOrNull == false) {
                                errors.add(ValidationError(itemPath, "Additional items are not allowed", ADDITIONAL_ITEMS))
                            }
                        }
                        else -> {
                            validateElement(instance[index], additionalItemsSchema, itemPath, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Contains
        schema[CONTAINS]?.let { containsSchema ->
            val matchingIndices = mutableListOf<Int>()
            instance.forEachIndexed { index, item ->
                val itemErrors = mutableListOf<ValidationError>()
                validateElement(item, containsSchema, "$path[$index]", itemErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                if (itemErrors.isEmpty()) {
                    matchingIndices.add(index)
                }
            }

            // Min/Max contains (2019-09 and later) - handle decimal values
            val minContains = schema[MIN_CONTAINS]?.jsonPrimitive?.let {
                it.doubleOrNull?.toInt() ?: it.intOrNull ?: 1
            } ?: 1

            val maxContains = schema[MAX_CONTAINS]?.jsonPrimitive?.let {
                it.doubleOrNull?.toInt() ?: it.intOrNull
            }

            // Special case: if minContains is 0, contains is always valid
            if (minContains == 0) {
                // Check maxContains only
                maxContains?.let { max ->
                    if (matchingIndices.size > max) {
                        errors.add(
                            ValidationError(path, "Array contains ${matchingIndices.size} matching items, maximum is $max", MAX_CONTAINS),
                        )
                    }
                }
            } else {
                // Normal case: minContains >= 1
                if (matchingIndices.size < minContains) {
                    errors.add(
                        ValidationError(
                            path,
                            "Array contains ${matchingIndices.size} matching items, minimum is $minContains",
                            if (schema.containsKey(MIN_CONTAINS)) MIN_CONTAINS else CONTAINS,
                        ),
                    )
                }

                maxContains?.let { max ->
                    if (matchingIndices.size > max) {
                        errors.add(
                            ValidationError(path, "Array contains ${matchingIndices.size} matching items, maximum is $max", MAX_CONTAINS),
                        )
                    }
                }
            }
        }

        // Min/Max items (support decimal values per spec)
        schema[MIN_ITEMS]?.jsonPrimitive?.let { minItemsValue ->
            val minItems = minItemsValue.doubleOrNull?.toInt() ?: minItemsValue.intOrNull ?: 0
            if (instance.size < minItems) {
                errors.add(ValidationError(path, "Array has ${instance.size} items, minimum is $minItems", MIN_ITEMS))
            }
        }

        schema[MAX_ITEMS]?.jsonPrimitive?.let { maxItemsValue ->
            val maxItems = maxItemsValue.doubleOrNull?.toInt() ?: maxItemsValue.intOrNull ?: Int.MAX_VALUE
            if (instance.size > maxItems) {
                errors.add(ValidationError(path, "Array has ${instance.size} items, maximum is $maxItems", MAX_ITEMS))
            }
        }

        // Unique items
        schema[UNIQUE_ITEMS]?.jsonPrimitive?.booleanOrNull?.let { uniqueItems ->
            if (uniqueItems) {
                // Check for duplicates using jsonEquals for numeric equivalence
                for (i in instance.indices) {
                    for (j in (i + 1) until instance.size) {
                        if (jsonEquals(instance[i], instance[j])) {
                            errors.add(ValidationError(path, "Array items must be unique", UNIQUE_ITEMS))
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
        version: SchemaVersion,
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
        version: SchemaVersion,
    ) {
        // Min/Max length (support decimal values per spec)
        // Note: JSON Schema counts Unicode codepoints, not UTF-16 code units
        schema[MIN_LENGTH]?.jsonPrimitive?.let { minLengthValue ->
            val minLength = minLengthValue.doubleOrNull?.toInt() ?: minLengthValue.intOrNull ?: 0
            val length = value.codepointLength()
            if (length < minLength) {
                errors.add(ValidationError(path, "String length is $length codepoints, minimum is $minLength", MIN_LENGTH))
            }
        }

        schema[MAX_LENGTH]?.jsonPrimitive?.let { maxLengthValue ->
            val maxLength = maxLengthValue.doubleOrNull?.toInt() ?: maxLengthValue.intOrNull ?: Int.MAX_VALUE
            val length = value.codepointLength()
            if (length > maxLength) {
                errors.add(ValidationError(path, "String length is $length codepoints, maximum is $maxLength", MAX_LENGTH))
            }
        }

        // Pattern
        schema[PATTERN]?.jsonPrimitive?.contentOrNull?.let { pattern ->
            if (!Regex(pattern).containsMatchIn(value)) {
                errors.add(ValidationError(path, "String does not match pattern: $pattern", PATTERN))
            }
        }

        // Format (basic validation)
        // In 2020-12, format is an annotation by default unless formatAssertion is enabled
        schema[FORMAT]?.jsonPrimitive?.contentOrNull?.let { format ->
            if (formatAssertion || version == SchemaVersion.DRAFT_2019_09) {
                validateFormat(value, format, path, errors)
            }
        }
    }

    /**
     * Validates format keyword (basic implementation)
     */
    private fun validateFormat(value: String, format: String, path: String, errors: MutableList<ValidationError>) {
        val valid = when (format) {
            FORMAT_EMAIL -> value.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}$"))
            FORMAT_URI -> value.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"))
            FORMAT_DATE -> value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))
            FORMAT_TIME -> value.matches(Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$"))
            FORMAT_DATE_TIME -> value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$"))
            FORMAT_IPV4 -> value.matches(Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"))
            FORMAT_IPV6 -> value.matches(Regex("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"))
            FORMAT_UUID -> value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
            else -> true // Unknown formats are ignored
        }

        if (!valid) {
            errors.add(ValidationError(path, "String does not match format: $format", FORMAT))
        }
    }

    /**
     * Validates a number value
     */
    private fun validateNumber(instance: JsonPrimitive, schema: JsonObject, path: String, errors: MutableList<ValidationError>) {
        val number = instance.doubleOrNull ?: return

        // Minimum
        schema[MINIMUM]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
            if (number < minimum) {
                errors.add(ValidationError(path, "Number $number is less than minimum $minimum", MINIMUM))
            }
        }

        // Maximum
        schema[MAXIMUM]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
            if (number > maximum) {
                errors.add(ValidationError(path, "Number $number is greater than maximum $maximum", MAXIMUM))
            }
        }

        // Exclusive minimum
        schema[EXCLUSIVE_MINIMUM]?.let { exclusiveMin ->
            when (exclusiveMin) {
                is JsonPrimitive -> {
                    if (exclusiveMin.booleanOrNull == true) {
                        // Draft 4 style with separate minimum
                        schema[MINIMUM]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
                            if (number <= minimum) {
                                errors.add(ValidationError(path, "Number $number must be greater than $minimum", EXCLUSIVE_MINIMUM))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        exclusiveMin.doubleOrNull?.let { minimum ->
                            if (number <= minimum) {
                                errors.add(ValidationError(path, "Number $number must be greater than $minimum", EXCLUSIVE_MINIMUM))
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        // Exclusive maximum
        schema[EXCLUSIVE_MAXIMUM]?.let { exclusiveMax ->
            when (exclusiveMax) {
                is JsonPrimitive -> {
                    if (exclusiveMax.booleanOrNull == true) {
                        // Draft 4 style with separate maximum
                        schema[MAXIMUM]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
                            if (number >= maximum) {
                                errors.add(ValidationError(path, "Number $number must be less than $maximum", EXCLUSIVE_MAXIMUM))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        exclusiveMax.doubleOrNull?.let { maximum ->
                            if (number >= maximum) {
                                errors.add(ValidationError(path, "Number $number must be less than $maximum", EXCLUSIVE_MAXIMUM))
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        // Multiple of
        schema[MULTIPLE_OF]?.jsonPrimitive?.doubleOrNull?.let { multipleOf ->
            if (multipleOf > 0) {
                val quotient = number / multipleOf
                // Handle infinity case (division by very small number)
                if (!quotient.isFinite()) {
                    errors.add(ValidationError(path, "Number $number is not a multiple of $multipleOf", MULTIPLE_OF))
                } else {
                    val rounded = kotlin.math.round(quotient)
                    val diff = kotlin.math.abs(quotient - rounded)
                    // Use relative epsilon for better floating point comparison
                    val epsilon = kotlin.math.max(1e-10, kotlin.math.abs(quotient) * 1e-10)
                    if (diff > epsilon) {
                        errors.add(ValidationError(path, "Number $number is not a multiple of $multipleOf", MULTIPLE_OF))
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        schemas.forEach { schema ->
            validateElement(instance, schema, path, errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val allTempErrors = mutableListOf<ValidationError>()
        val anyValid = schemas.any { schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            allTempErrors.addAll(tempErrors)
            tempErrors.isEmpty()
        }

        if (!anyValid) {
            // Check if any schema hit depth limit - if so, propagate that error
            val depthError = allTempErrors.firstOrNull { it.keyword == "depth" }
            if (depthError != null) {
                errors.add(depthError)
            } else {
                errors.add(ValidationError(path, "Instance does not match any of the schemas", ANY_OF))
            }
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val allTempErrors = mutableListOf<ValidationError>()
        val validCount = schemas.count { schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            allTempErrors.addAll(tempErrors)
            tempErrors.isEmpty()
        }

        when (validCount) {
            0 -> {
                // Check if any schema hit depth limit - if so, propagate that error
                val depthError = allTempErrors.firstOrNull { it.keyword == "depth" }
                if (depthError != null) {
                    errors.add(depthError)
                } else {
                    errors.add(ValidationError(path, "Instance does not match any of the oneOf schemas", ONE_OF))
                }
            }
            1 -> {} // Valid
            else -> errors.add(ValidationError(path, "Instance matches more than one oneOf schema", ONE_OF))
        }
    }

    /**
     * Collects the set of property names "evaluated" by a schema and its applicators.
     * Used to determine which properties are subject to unevaluatedProperties.
     * Annotations flow from: properties, patternProperties, additionalProperties,
     * allOf (all branches), anyOf/oneOf (valid branches only), if/then/else, $ref, $dynamicRef.
     * Annotations do NOT flow from: not, failed sub-schemas.
     */
    private fun collectEvaluatedProperties(
        instance: JsonObject,
        schemaElement: JsonElement,
        path: String,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ): Set<String> {
        if (schemaElement is JsonPrimitive) {
            // Boolean schemas have no property-evaluating keywords — they evaluate nothing
            return emptySet()
        }
        if (schemaElement !is JsonObject) return emptySet()

        val effectiveResourceRoot =
            if (schemaElement.containsKey(SchemaKeywords.ID)) schemaElement else resourceRoot
        val effectiveScope =
            if (schemaElement.containsKey(SchemaKeywords.ID)) dynamicScope + schemaElement else dynamicScope

        val evaluated = mutableSetOf<String>()

        // properties: all instance properties that appear in the properties schema
        schemaElement[PROPERTIES]?.jsonObject?.let { props ->
            evaluated.addAll(instance.keys.filter { it in props })
        }

        // patternProperties: all instance properties matching any pattern
        schemaElement[PATTERN_PROPERTIES]?.jsonObject?.let { patternProps ->
            patternProps.keys.forEach { pattern ->
                evaluated.addAll(instance.keys.filter { Regex(pattern).containsMatchIn(it) })
            }
        }

        // additionalProperties: evaluates properties not in properties/patternProperties (unless false)
        schemaElement[ADDITIONAL_PROPERTIES]?.let { addlPropsSchema ->
            val isFalse = addlPropsSchema is JsonPrimitive && addlPropsSchema.booleanOrNull == false
            if (!isFalse) {
                val definedProps = schemaElement[PROPERTIES]?.jsonObject?.keys ?: emptySet()
                val patternPropPatterns = schemaElement[PATTERN_PROPERTIES]?.jsonObject?.keys ?: emptySet()
                evaluated.addAll(instance.keys.filter { it !in definedProps && !matchesAnyPattern(it, patternPropPatterns) })
            }
        }

        // $ref — resolve and collect from the referenced schema
        schemaElement[REF]?.jsonPrimitive?.contentOrNull?.let { ref ->
            val refHashIndex = ref.indexOf('#')
            val refUriPart = if (refHashIndex >= 0) ref.substring(0, refHashIndex) else ref
            val resolved =
                if (refUriPart.isEmpty()) {
                    referenceResolver.resolveRef(ref, effectiveResourceRoot, schemaElement)
                } else {
                    referenceResolver.resolveRef(ref, rootSchema, schemaElement)
                }
            resolved?.let {
                evaluated.addAll(collectEvaluatedProperties(instance, it, path, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // $recursiveRef — resolve with dynamic scope, collect from target
        schemaElement[RECURSIVE_REF]?.jsonPrimitive?.contentOrNull?.let { recursiveRef ->
            val resolved = referenceResolver.resolveRef(recursiveRef, effectiveResourceRoot, schemaElement)
            if (resolved != null) {
                val target =
                    if (resolved is JsonObject && resolved[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true) {
                        effectiveScope.firstOrNull { s ->
                            s is JsonObject && s[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true
                        } ?: resolved
                    } else {
                        resolved
                    }
                evaluated.addAll(collectEvaluatedProperties(instance, target, path, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // $dynamicRef — resolve with dynamic anchor scope, collect from target
        schemaElement[SchemaKeywords.DYNAMIC_REF]?.jsonPrimitive?.contentOrNull?.let { dynamicRef ->
            val hashIndex = dynamicRef.indexOf('#')
            val uriPart = if (hashIndex >= 0) dynamicRef.substring(0, hashIndex) else dynamicRef
            val fragment = if (hashIndex >= 0) dynamicRef.substring(hashIndex + 1) else ""
            val isPlainAnchorFragment = fragment.isNotEmpty() && !fragment.startsWith("/")
            val initialTarget =
                if (uriPart.isEmpty()) {
                    referenceResolver.resolveRef(dynamicRef, effectiveResourceRoot, schemaElement)
                } else {
                    referenceResolver.resolveRef(dynamicRef, rootSchema, schemaElement)
                }
            if (initialTarget != null) {
                val target =
                    if (isPlainAnchorFragment && initialTarget is JsonObject &&
                        initialTarget[DYNAMIC_ANCHOR]?.jsonPrimitive?.contentOrNull == fragment
                    ) {
                        effectiveScope.firstNotNullOfOrNull { scopeSchema ->
                            referenceResolver.findDynamicAnchorInResource(scopeSchema, fragment)
                        } ?: initialTarget
                    } else {
                        initialTarget
                    }
                evaluated.addAll(collectEvaluatedProperties(instance, target, path, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // allOf: annotations from all branches (they must all be valid for the schema to be valid)
        schemaElement[ALL_OF]?.jsonArray?.forEach { subSchema ->
            evaluated.addAll(collectEvaluatedProperties(instance, subSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
        }

        // anyOf: annotations only from valid branches
        schemaElement[ANY_OF]?.jsonArray?.forEach { subSchema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, subSchema, path, tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (tempErrors.isEmpty()) {
                evaluated.addAll(collectEvaluatedProperties(instance, subSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
            }
        }

        // oneOf: annotations only from the single valid branch
        schemaElement[ONE_OF]?.jsonArray?.let { schemas ->
            val validBranch =
                schemas.firstOrNull { subSchema ->
                    val tempErrors = mutableListOf<ValidationError>()
                    validateElement(instance, subSchema, path, tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    tempErrors.isEmpty()
                }
            validBranch?.let {
                evaluated.addAll(collectEvaluatedProperties(instance, it, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
            }
        }

        // if/then/else: when if passes, collect from the if schema AND then; when if fails, collect from else
        schemaElement[IF]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            validateElement(instance, ifSchema, path, ifErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (ifErrors.isEmpty()) {
                // if passed: its own property annotations also contribute
                evaluated.addAll(collectEvaluatedProperties(instance, ifSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                schemaElement[THEN]?.let { thenSchema ->
                    evaluated.addAll(collectEvaluatedProperties(instance, thenSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                }
            } else {
                schemaElement[ELSE]?.let { elseSchema ->
                    evaluated.addAll(collectEvaluatedProperties(instance, elseSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                }
            }
        }

        // dependentSchemas: collect from applicable sub-schemas
        schemaElement[DEPENDENT_SCHEMAS]?.jsonObject?.let { depSchemas ->
            depSchemas.forEach { (propName, depSchema) ->
                if (propName in instance) {
                    evaluated.addAll(collectEvaluatedProperties(instance, depSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                }
            }
        }

        // unevaluatedProperties in a sub-schema is itself an annotation source:
        // properties it evaluates (passing true, or passing a sub-schema) are considered evaluated
        schemaElement[UNEVALUATED_PROPERTIES]?.let { unevalPropsSchema ->
            val yetUnevaluated = instance.keys.filter { it !in evaluated }
            when {
                unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == true ->
                    evaluated.addAll(yetUnevaluated)
                unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == false -> {}
                else ->
                    yetUnevaluated.forEach { propName ->
                        val tempErrors = mutableListOf<ValidationError>()
                        validateElement(instance[propName]!!, unevalPropsSchema, if (path.isEmpty()) propName else "$path.$propName", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        if (tempErrors.isEmpty()) evaluated.add(propName)
                    }
            }
        }

        return evaluated
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
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val tempErrors = mutableListOf<ValidationError>()
        validateElement(instance, schema, path, tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)

        // Check if schema hit depth limit - if so, propagate that error
        val depthError = tempErrors.firstOrNull { it.keyword == "depth" }
        if (depthError != null) {
            errors.add(depthError)
        } else if (tempErrors.isEmpty()) {
            errors.add(ValidationError(path, "Instance matches the not schema but should not", NOT))
        }
    }

    /**
     * Checks if a property name matches any pattern
     */
    private fun matchesAnyPattern(propName: String, patterns: Set<String>): Boolean = patterns.any { Regex(it).containsMatchIn(propName) }

    /**
     * Validates the structure of a schema itself
     */
    private fun validateSchemaStructure(schema: JsonElement, path: String, errors: MutableList<ValidationError>) {
        when (schema) {
            is JsonObject -> {
                // Check for invalid combinations
                val validTypes = SchemaKeywords.VALID_TYPES
                if (schema.containsKey(TYPE)) {
                    when (val type = schema[TYPE]) {
                        is JsonPrimitive -> {
                            if (!type.isString) {
                                errors.add(ValidationError(path, "type must be a string or array of strings", SCHEMA))
                            } else if (type.content !in validTypes) {
                                errors.add(ValidationError(path, "type value '${type.content}' is not a valid JSON type", SCHEMA))
                            }
                        }
                        is JsonArray -> {
                            type.forEach { typeElement ->
                                if (typeElement !is JsonPrimitive || !typeElement.isString) {
                                    errors.add(ValidationError(path, "type array must contain only strings", SCHEMA))
                                } else if (typeElement.content !in validTypes) {
                                    errors.add(
                                        ValidationError(path, "type value '${typeElement.content}' is not a valid JSON type", SCHEMA),
                                    )
                                }
                            }
                        }
                        else -> {
                            errors.add(ValidationError(path, "type must be a string or array", SCHEMA))
                        }
                    }
                }

                // Validate $schema if present
                schema[SCHEMA]?.let { schemaUri ->
                    if (schemaUri !is JsonPrimitive || !schemaUri.isString) {
                        errors.add(ValidationError(path, "$SCHEMA must be a string", SCHEMA))
                    }
                }

                // Recursively validate nested schemas
                schema.forEach { (key, value) ->
                    val newPath = if (path.isEmpty()) key else "$path.$key"
                    when (key) {
                        PROPERTIES, PATTERN_PROPERTIES, DEPENDENT_SCHEMAS -> {
                            if (value is JsonObject) {
                                value.forEach { (propName, propSchema) ->
                                    validateSchemaStructure(propSchema, "$newPath.$propName", errors)
                                }
                            }
                        }
                        ITEMS, ADDITIONAL_PROPERTIES, ADDITIONAL_ITEMS, CONTAINS, PROPERTY_NAMES,
                        IF, THEN, ELSE, NOT,
                        -> {
                            validateSchemaStructure(value, newPath, errors)
                        }
                        ALL_OF, ANY_OF, ONE_OF, PREFIX_ITEMS -> {
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
                    errors.add(ValidationError(path, "Schema must be an object or boolean", SCHEMA))
                }
            }
            else -> {
                errors.add(ValidationError(path, "Schema must be an object or boolean", SCHEMA))
            }
        }
    }
}
