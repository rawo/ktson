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
import org.ktson.SchemaKeywords.FORMAT_DURATION
import org.ktson.SchemaKeywords.FORMAT_EMAIL
import org.ktson.SchemaKeywords.FORMAT_HOSTNAME
import org.ktson.SchemaKeywords.FORMAT_IDN_EMAIL
import org.ktson.SchemaKeywords.FORMAT_IDN_HOSTNAME
import org.ktson.SchemaKeywords.FORMAT_IPV4
import org.ktson.SchemaKeywords.FORMAT_IPV6
import org.ktson.SchemaKeywords.FORMAT_IRI
import org.ktson.SchemaKeywords.FORMAT_IRI_REFERENCE
import org.ktson.SchemaKeywords.FORMAT_JSON_POINTER
import org.ktson.SchemaKeywords.FORMAT_REGEX
import org.ktson.SchemaKeywords.FORMAT_RELATIVE_JSON_POINTER
import org.ktson.SchemaKeywords.FORMAT_TIME
import org.ktson.SchemaKeywords.FORMAT_URI
import org.ktson.SchemaKeywords.FORMAT_URI_REFERENCE
import org.ktson.SchemaKeywords.FORMAT_URI_TEMPLATE
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
import org.ktson.SchemaKeywords.UNEVALUATED_ITEMS
import org.ktson.SchemaKeywords.UNEVALUATED_PROPERTIES
import org.ktson.SchemaKeywords.UNIQUE_ITEMS
import java.util.concurrent.ConcurrentHashMap

/**
 * Main JSON Schema validator with thread-safe synchronous validation
 */
class JsonValidator(
    private val enableMetaSchemaValidation: Boolean = true,
    // Draft 2020-12 uses annotation by default
    private val formatAssertion: Boolean = true,
    private val maxValidationDepth: Int = 1000,
    private val schemaLoader: ((String) -> JsonElement?)? = null,
) {
    private val referenceResolver = ReferenceResolver(schemaLoader)
    private val patternCache = ConcurrentHashMap<String, Regex>()

    private fun compiledPattern(pattern: String): Regex = patternCache.computeIfAbsent(pattern) { Regex(translatePatternToJava(it)) }

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
        validateSchemaStructure(schema.schema, "", "", errors)

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
        validateElement(instance, schema.schema, path, "", errors, schema.effectiveVersion, schema.schema, depth = 0, resourceRoot = schema.schema, dynamicScope = emptyList())

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validates a JSON element against a schema element
     */

    /** A string value as it appears in a message, shortened so a long instance cannot flood the output. */
    private fun quoted(value: String): String = if (value.length > MAX_MESSAGE_VALUE_LENGTH) "\"${value.take(MAX_MESSAGE_VALUE_LENGTH)}...\"" else "\"$value\""

    /** A numeric keyword's value as written in the schema, so messages do not show 10 as 10.0. */
    private fun JsonObject.numberText(keyword: String): String = this[keyword]?.jsonPrimitive?.content ?: "?"

    /** Keyword location of [keyword] within the schema currently being applied (RFC 6901). */
    private fun at(schemaPath: String, keyword: String): String = "$schemaPath/$keyword"

    /** Keyword location of a sub-schema reached through [keyword] and [token], e.g. properties/name. */
    private fun at(schemaPath: String, keyword: String, token: String): String = "$schemaPath/$keyword/${JsonPointer.encodeToken(token)}"

    private fun validateElement(
        instance: JsonElement,
        schemaElement: JsonElement,
        path: String,
        schemaPath: String,
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
                    schemaPath,
                ),
            )
            return
        }

        // Update resource root and dynamic scope when entering a new schema resource (one with $id)
        val effectiveResourceRoot = if (schemaElement is JsonObject && schemaElement.containsKey(SchemaKeywords.ID)) schemaElement else resourceRoot
        val effectiveScope = if (schemaElement is JsonObject && schemaElement.containsKey(SchemaKeywords.ID)) dynamicScope + schemaElement else dynamicScope

        // Register the absolute URI for new resource roots so relative $id and missing-$id schemas
        // resolve subsequent relative $refs correctly (e.g. base URI change, nested remote refs).
        if (schemaElement is JsonObject && schemaElement.containsKey(SchemaKeywords.ID)) {
            val rawId = schemaElement[SchemaKeywords.ID]?.jsonPrimitive?.contentOrNull ?: ""
            if (rawId.isNotEmpty()) {
                val parentUri = referenceResolver.getAbsoluteUri(resourceRoot)
                val absoluteId =
                    if (parentUri.isNotEmpty()) {
                        try {
                            UriResolver.resolveUri(parentUri, rawId)
                        } catch (_: Exception) {
                            rawId
                        }
                    } else {
                        rawId
                    }
                referenceResolver.registerAbsoluteUri(schemaElement, absoluteId)
            }
        }

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
                            referenceResolver.resolveRef(ref, effectiveResourceRoot, schemaElement, effectiveResourceRoot)
                        } else {
                            referenceResolver.resolveRef(ref, rootSchema, schemaElement, effectiveResourceRoot)
                        }
                    if (resolvedSchema != null) {
                        // For URI-based refs, use the target schema resource as the new resourceRoot
                        // so local refs within it (#/..., #anchor) resolve against the correct document.
                        val newResourceRoot =
                            if (refUriPart.isNotEmpty()) {
                                referenceResolver.resolveUriToResource(refUriPart, rootSchema, effectiveResourceRoot)
                                    ?: effectiveResourceRoot
                            } else {
                                effectiveResourceRoot
                            }
                        // When crossing into a new resource (different $id boundary) via a URI-based $ref,
                        // register that resource in the dynamic scope so $dynamicRef can find its anchors.
                        // This handles cases where the ref points to a sub-schema of the resource (not the root),
                        // which would otherwise never trigger the $id-based scope update in validateElement.
                        val refScope =
                            if (refUriPart.isNotEmpty() &&
                                newResourceRoot !== effectiveResourceRoot &&
                                newResourceRoot is JsonObject &&
                                newResourceRoot.containsKey(SchemaKeywords.ID) &&
                                !effectiveScope.contains(newResourceRoot)
                            ) {
                                effectiveScope + newResourceRoot
                            } else {
                                effectiveScope
                            }
                        validateElement(instance, resolvedSchema, path, at(schemaPath, REF), errors, version, rootSchema, depth + 1, newResourceRoot, refScope)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve reference: $ref", REF, at(schemaPath, REF)))
                    }
                }

                // Check for $recursiveRef (2019-09) — dynamic scoping behavior
                val recursiveRef = schemaElement[RECURSIVE_REF]?.jsonPrimitive?.contentOrNull
                if (recursiveRef != null) {
                    val resolved = referenceResolver.resolveRef(recursiveRef, effectiveResourceRoot, schemaElement, effectiveResourceRoot)
                    if (resolved != null) {
                        // If resolved schema has $recursiveAnchor: true, use outermost in dynamic scope with that flag
                        val target = if (resolved is JsonObject && resolved[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true) {
                            effectiveScope.firstOrNull { schema ->
                                schema is JsonObject && schema[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true
                            } ?: resolved
                        } else {
                            resolved
                        }
                        validateElement(instance, target, path, at(schemaPath, RECURSIVE_REF), errors, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve recursive reference: $recursiveRef", RECURSIVE_REF, at(schemaPath, RECURSIVE_REF)))
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
                            referenceResolver.resolveRef(dynamicRef, effectiveResourceRoot, schemaElement, effectiveResourceRoot)
                        } else {
                            referenceResolver.resolveRef(dynamicRef, rootSchema, schemaElement, effectiveResourceRoot)
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
                        validateElement(instance, target, path, at(schemaPath, DYNAMIC_REF), errors, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope)
                    } else {
                        errors.add(ValidationError(path, "Could not resolve dynamic reference: $dynamicRef", DYNAMIC_REF, at(schemaPath, DYNAMIC_REF)))
                    }
                }

                validateAgainstObjectSchema(instance, schemaElement, path, schemaPath, errors, version, rootSchema, depth, effectiveResourceRoot, effectiveScope)
            }
            is JsonPrimitive -> {
                // Boolean schema
                if (schemaElement.isString) return
                val boolValue = schemaElement.booleanOrNull
                if (boolValue == false) {
                    errors.add(ValidationError(path, "Schema is false, no instance is valid", SCHEMA_FALSE, at(schemaPath, SCHEMA_FALSE)))
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
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        // Type validation
        schema[TYPE]?.let { typeSchema ->
            validateType(instance, typeSchema, path, schemaPath, errors)
        }

        // Const validation
        schema[CONST]?.let { constValue ->
            if (!jsonEquals(instance, constValue)) {
                errors.add(ValidationError(path, "Value must be const: $constValue, but was: $instance", CONST, at(schemaPath, CONST)))
            }
        }

        // Enum validation
        schema[ENUM]?.jsonArray?.let { enumValues ->
            if (enumValues.none { jsonEquals(it, instance) }) {
                errors.add(ValidationError(path, "Value $instance is not one of: $enumValues", ENUM, at(schemaPath, ENUM)))
            }
        }

        when (instance) {
            is JsonObject -> validateObject(instance, schema, path, schemaPath, errors, version, rootSchema, depth, resourceRoot, dynamicScope)
            is JsonArray -> validateArray(instance, schema, path, schemaPath, errors, version, rootSchema, depth, resourceRoot, dynamicScope)
            is JsonPrimitive -> validatePrimitive(instance, schema, path, schemaPath, errors, version)
        }

        // Combined schemas
        schema[ALL_OF]?.jsonArray?.let { validateAllOf(instance, it, path, schemaPath, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }
        schema[ANY_OF]?.jsonArray?.let { validateAnyOf(instance, it, path, schemaPath, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }
        schema[ONE_OF]?.jsonArray?.let { validateOneOf(instance, it, path, schemaPath, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }
        schema[NOT]?.let { validateNot(instance, it, path, schemaPath, errors, version, rootSchema, depth, resourceRoot, dynamicScope) }

        // Conditional schemas (2019-09 and later)
        schema[IF]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            validateElement(instance, ifSchema, path, at(schemaPath, IF), ifErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)

            if (ifErrors.isEmpty()) {
                // If validation passed, validate against "then"
                schema[THEN]?.let { thenSchema ->
                    validateElement(instance, thenSchema, path, at(schemaPath, THEN), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                }
            } else {
                // If validation failed, validate against "else"
                schema[ELSE]?.let { elseSchema ->
                    validateElement(instance, elseSchema, path, at(schemaPath, ELSE), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
                                errors.add(ValidationError(path, "Unevaluated property '$propName' is not allowed", UNEVALUATED_PROPERTIES, at(schemaPath, UNEVALUATED_PROPERTIES)))
                            unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == true -> {}
                            else ->
                                validateElement(instance[propName]!!, unevalPropsSchema, propPath, at(schemaPath, UNEVALUATED_PROPERTIES), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Unevaluated items (2019-09 and later)
        if (instance is JsonArray) {
            schema[UNEVALUATED_ITEMS]?.let { unevalItemsSchema ->
                val evaluated = collectEvaluatedIndices(instance, schema, path, version, rootSchema, depth, resourceRoot, dynamicScope)
                instance.indices.forEach { index ->
                    if (index !in evaluated) {
                        val itemPath = "$path[$index]"
                        when {
                            unevalItemsSchema is JsonPrimitive && unevalItemsSchema.booleanOrNull == false ->
                                errors.add(ValidationError(path, "Unevaluated item at index $index is not allowed", UNEVALUATED_ITEMS, at(schemaPath, UNEVALUATED_ITEMS)))
                            unevalItemsSchema is JsonPrimitive && unevalItemsSchema.booleanOrNull == true -> {}
                            else ->
                                validateElement(instance[index], unevalItemsSchema, itemPath, at(schemaPath, UNEVALUATED_ITEMS), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates type keyword
     */
    private fun validateType(instance: JsonElement, typeSchema: JsonElement, path: String, schemaPath: String, errors: MutableList<ValidationError>) {
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
            errors.add(ValidationError(path, "Expected type(s): ${types.joinToString()}, but got: $instanceType", TYPE, at(schemaPath, TYPE)))
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
        schemaPath: String,
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
                    validateElement(propValue, propSchema, propPath, at(schemaPath, PROPERTIES, propName), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                }
            }
        }

        // Required properties
        schema[REQUIRED]?.jsonArray?.let { required ->
            required.forEach { requiredProp ->
                val propName = requiredProp.jsonPrimitive.content
                if (propName !in instance) {
                    errors.add(ValidationError(path, "Required property '$propName' is missing", REQUIRED, at(schemaPath, REQUIRED)))
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
                                errors.add(ValidationError(path, "Additional property '$propName' is not allowed", ADDITIONAL_PROPERTIES, at(schemaPath, ADDITIONAL_PROPERTIES)))
                            }
                        }
                        else -> {
                            validateElement(instance[propName]!!, additionalPropsSchema, propPath, at(schemaPath, ADDITIONAL_PROPERTIES), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Pattern properties
        schema[PATTERN_PROPERTIES]?.jsonObject?.let { patternProps ->
            patternProps.forEach { (pattern, propSchema) ->
                instance.keys.forEach { propName ->
                    if (compiledPattern(pattern).containsMatchIn(propName)) {
                        val propPath = if (path.isEmpty()) propName else "$path.$propName"
                        validateElement(instance[propName]!!, propSchema, propPath, at(schemaPath, PATTERN_PROPERTIES, pattern), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    }
                }
            }
        }

        // Min/Max properties (support decimal values per spec)
        schema[MIN_PROPERTIES]?.jsonPrimitive?.let { minPropsValue ->
            val minProps = minPropsValue.doubleOrNull?.toInt() ?: minPropsValue.intOrNull ?: 0
            if (instance.size < minProps) {
                errors.add(ValidationError(path, "Object has ${instance.size} properties, minimum is $minProps", MIN_PROPERTIES, at(schemaPath, MIN_PROPERTIES)))
            }
        }

        schema[MAX_PROPERTIES]?.jsonPrimitive?.let { maxPropsValue ->
            val maxProps = maxPropsValue.doubleOrNull?.toInt() ?: maxPropsValue.intOrNull ?: Int.MAX_VALUE
            if (instance.size > maxProps) {
                errors.add(ValidationError(path, "Object has ${instance.size} properties, maximum is $maxProps", MAX_PROPERTIES, at(schemaPath, MAX_PROPERTIES)))
            }
        }

        // Property names (2019-09 and later)
        schema[PROPERTY_NAMES]?.let { propNamesSchema ->
            instance.keys.forEach { propName ->
                val propNameElement = JsonPrimitive(propName)
                validateElement(propNameElement, propNamesSchema, "$path.<propertyName>", at(schemaPath, PROPERTY_NAMES), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            }
        }

        // Dependent required (2019-09 and later)
        schema[DEPENDENT_REQUIRED]?.jsonObject?.let { depRequired ->
            depRequired.forEach { (propName, requiredProps) ->
                if (propName in instance) {
                    requiredProps.jsonArray.forEach { reqProp ->
                        val reqPropName = reqProp.jsonPrimitive.content
                        if (reqPropName !in instance) {
                            errors.add(ValidationError(path, "Property '$propName' requires property '$reqPropName'", DEPENDENT_REQUIRED, at(schemaPath, DEPENDENT_REQUIRED)))
                        }
                    }
                }
            }
        }

        // Dependent schemas (2019-09 and later)
        schema[DEPENDENT_SCHEMAS]?.jsonObject?.let { depSchemas ->
            depSchemas.forEach { (propName, depSchema) ->
                if (propName in instance) {
                    validateElement(instance, depSchema, path, at(schemaPath, DEPENDENT_SCHEMAS, propName), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
        schemaPath: String,
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
                        validateElement(instance[index], itemSchema, itemPath, at(schemaPath, PREFIX_ITEMS, index.toString()), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    }
                }

                // In 2020-12, if both prefixItems and items exist, items applies to remaining items
                schema[ITEMS]?.let { itemsSchema ->
                    for (index in prefixItems.size until instance.size) {
                        val itemPath = "$path[$index]"
                        validateElement(instance[index], itemsSchema, itemPath, at(schemaPath, ITEMS), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
                            validateElement(item, itemsSchema, itemPath, at(schemaPath, ITEMS), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                    is JsonArray -> {
                        // Tuple validation (deprecated in 2020-12 but still supported)
                        itemsSchema.forEachIndexed { index, itemSchema ->
                            if (index < instance.size) {
                                val itemPath = "$path[$index]"
                                validateElement(instance[index], itemSchema, itemPath, at(schemaPath, ITEMS, index.toString()), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
                                errors.add(ValidationError(itemPath, "Additional items are not allowed", ADDITIONAL_ITEMS, at(schemaPath, ADDITIONAL_ITEMS)))
                            }
                        }
                        else -> {
                            validateElement(instance[index], additionalItemsSchema, itemPath, at(schemaPath, ADDITIONAL_ITEMS), errors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
                validateElement(item, containsSchema, "$path[$index]", at(schemaPath, CONTAINS), itemErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
                            ValidationError(path, "Array contains ${matchingIndices.size} matching items, maximum is $max", MAX_CONTAINS, at(schemaPath, MAX_CONTAINS)),
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
                            at(schemaPath, if (schema.containsKey(MIN_CONTAINS)) MIN_CONTAINS else CONTAINS),
                        ),
                    )
                }

                maxContains?.let { max ->
                    if (matchingIndices.size > max) {
                        errors.add(
                            ValidationError(path, "Array contains ${matchingIndices.size} matching items, maximum is $max", MAX_CONTAINS, at(schemaPath, MAX_CONTAINS)),
                        )
                    }
                }
            }
        }

        // Min/Max items (support decimal values per spec)
        schema[MIN_ITEMS]?.jsonPrimitive?.let { minItemsValue ->
            val minItems = minItemsValue.doubleOrNull?.toInt() ?: minItemsValue.intOrNull ?: 0
            if (instance.size < minItems) {
                errors.add(ValidationError(path, "Array has ${instance.size} items, minimum is $minItems", MIN_ITEMS, at(schemaPath, MIN_ITEMS)))
            }
        }

        schema[MAX_ITEMS]?.jsonPrimitive?.let { maxItemsValue ->
            val maxItems = maxItemsValue.doubleOrNull?.toInt() ?: maxItemsValue.intOrNull ?: Int.MAX_VALUE
            if (instance.size > maxItems) {
                errors.add(ValidationError(path, "Array has ${instance.size} items, maximum is $maxItems", MAX_ITEMS, at(schemaPath, MAX_ITEMS)))
            }
        }

        // Unique items
        schema[UNIQUE_ITEMS]?.jsonPrimitive?.booleanOrNull?.let { uniqueItems ->
            if (uniqueItems) {
                // Check for duplicates using jsonEquals for numeric equivalence
                for (i in instance.indices) {
                    for (j in (i + 1) until instance.size) {
                        if (jsonEquals(instance[i], instance[j])) {
                            errors.add(ValidationError(path, "Array items must be unique", UNIQUE_ITEMS, at(schemaPath, UNIQUE_ITEMS)))
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
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
    ) {
        when {
            instance.isString -> validateString(instance.content, schema, path, schemaPath, errors, version)
            else -> validateNumber(instance, schema, path, schemaPath, errors)
        }
    }

    /**
     * Validates a string value
     */
    private fun validateString(
        value: String,
        schema: JsonObject,
        path: String,
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
    ) {
        // Min/Max length (support decimal values per spec)
        // Note: JSON Schema counts Unicode codepoints, not UTF-16 code units
        val codepointLength: Int by lazy { value.codepointLength() }

        schema[MIN_LENGTH]?.jsonPrimitive?.let { minLengthValue ->
            val minLength = minLengthValue.doubleOrNull?.toInt() ?: minLengthValue.intOrNull ?: 0
            if (codepointLength < minLength) {
                errors.add(ValidationError(path, "String ${quoted(value)} is $codepointLength codepoints long, minimum is $minLength", MIN_LENGTH, at(schemaPath, MIN_LENGTH)))
            }
        }

        schema[MAX_LENGTH]?.jsonPrimitive?.let { maxLengthValue ->
            val maxLength = maxLengthValue.doubleOrNull?.toInt() ?: maxLengthValue.intOrNull ?: Int.MAX_VALUE
            if (codepointLength > maxLength) {
                errors.add(ValidationError(path, "String ${quoted(value)} is $codepointLength codepoints long, maximum is $maxLength", MAX_LENGTH, at(schemaPath, MAX_LENGTH)))
            }
        }

        // Pattern
        schema[PATTERN]?.jsonPrimitive?.contentOrNull?.let { pattern ->
            if (!compiledPattern(pattern).containsMatchIn(value)) {
                errors.add(ValidationError(path, "String ${quoted(value)} does not match pattern: $pattern", PATTERN, at(schemaPath, PATTERN)))
            }
        }

        // Format (basic validation)
        // In 2020-12, format is an annotation by default unless formatAssertion is enabled
        schema[FORMAT]?.jsonPrimitive?.contentOrNull?.let { format ->
            if (formatAssertion || version == SchemaVersion.DRAFT_2019_09) {
                validateFormat(value, format, path, schemaPath, errors)
            }
        }
    }

    /**
     * Validates format keyword (basic implementation)
     */
    private fun validateFormat(value: String, format: String, path: String, schemaPath: String, errors: MutableList<ValidationError>) {
        val valid = when (format) {
            FORMAT_EMAIL -> isValidEmail(value)
            FORMAT_URI -> isValidUriLike(value, requireScheme = true, allowUcs = false)
            FORMAT_DATE -> isValidDate(value)
            FORMAT_TIME -> isValidTime(value)
            FORMAT_DATE_TIME -> isValidDateTime(value)
            FORMAT_IPV4 -> isValidIPv4(value)
            FORMAT_IPV6 -> isValidIPv6(value)
            FORMAT_UUID -> value.matches(REGEX_UUID)
            FORMAT_HOSTNAME -> isValidHostname(value)
            FORMAT_IDN_EMAIL -> isValidIdnEmail(value)
            FORMAT_IRI -> isValidUriLike(value, requireScheme = true, allowUcs = true)
            FORMAT_IRI_REFERENCE -> isValidUriLike(value, requireScheme = false, allowUcs = true)
            FORMAT_IDN_HOSTNAME -> isValidIdnHostname(value)
            FORMAT_JSON_POINTER -> value.isEmpty() || (value.startsWith("/") && !value.contains(REGEX_JSON_POINTER_INVALID_TILDE))
            FORMAT_RELATIVE_JSON_POINTER -> isValidRelativeJsonPointer(value)
            FORMAT_URI_REFERENCE -> isValidUriLike(value, requireScheme = false, allowUcs = false)
            FORMAT_URI_TEMPLATE -> isValidUriTemplate(value)
            FORMAT_DURATION -> isValidDuration(value)
            FORMAT_REGEX -> isValidEcmaRegex(value)
            else -> true // Unknown formats are ignored
        }

        if (!valid) {
            errors.add(ValidationError(path, "String ${quoted(value)} does not match format: $format", FORMAT, at(schemaPath, FORMAT)))
        }
    }

    private fun isValidDate(value: String): Boolean {
        if (!value.matches(REGEX_DATE)) return false
        val parts = value.split('-')
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val day = parts[2].toInt()
        if (month < 1 || month > 12) return false
        val maxDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            else -> return false
        }
        return day in 1..maxDay
    }

    private fun isValidTime(value: String): Boolean {
        if (!value.matches(REGEX_TIME)) return false
        val hour = value.substring(0, 2).toInt()
        val minute = value.substring(3, 5).toInt()
        val second = value.substring(6, 8).toInt()
        if (hour > 23 || minute > 59 || second > 60) return false

        // Locate timezone start (after optional fractional seconds)
        var i = 8
        while (i < value.length && (value[i] == '.' || value[i].isDigit())) i++
        val tzStr = value.substring(i)

        val offsetSign: Int
        val offsetHour: Int
        val offsetMinute: Int
        if (tzStr.equals("Z", ignoreCase = true)) {
            offsetSign = 1
            offsetHour = 0
            offsetMinute = 0
        } else {
            offsetSign = if (tzStr[0] == '+') 1 else -1
            offsetHour = tzStr.substring(1, 3).toInt()
            offsetMinute = tzStr.substring(4, 6).toInt()
            if (offsetHour > 23 || offsetMinute > 59) return false
        }

        if (second == 60) {
            // Leap second valid only when UTC time is 23:59
            val localMinutes = hour * 60 + minute
            val offsetTotalMinutes = offsetHour * 60 + offsetMinute
            val utcMinutes = (localMinutes - offsetSign * offsetTotalMinutes).mod(1440)
            if (utcMinutes != 23 * 60 + 59) return false
        }

        return true
    }

    private fun isValidDateTime(value: String): Boolean {
        if (!value.matches(REGEX_DATE_TIME)) return false
        val tIdx = value.indexOfFirst { it == 'T' || it == 't' }
        return isValidDate(value.substring(0, tIdx)) && isValidTime(value.substring(tIdx + 1))
    }

    /**
     * RFC 2673 dotted-quad. Leading zeros are rejected: they read as octal in
     * most resolvers, so "192.168.0.01" is not the same address as written.
     */
    private fun isValidIPv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') &&
                part.toInt() <= 255
        }
    }

    private fun isValidIPv6(value: String): Boolean {
        if (value.isEmpty()) return false
        val hasMixed = '.' in value
        if (hasMixed) {
            val lastColon = value.lastIndexOf(':')
            if (lastColon < 0) return false
            val ipv4Part = value.substring(lastColon + 1)
            if (!isValidIPv4(ipv4Part)) return false
            // Replace IPv4 tail with two placeholder hex groups and validate as pure IPv6
            return isValidPureIPv6Part(value.substring(0, lastColon) + ":0:0")
        }
        return isValidPureIPv6Part(value)
    }

    private fun isValidPureIPv6Part(s: String): Boolean {
        if (":::" in s) return false
        val hasCompress = "::" in s
        if (hasCompress && s.windowed(2).count { it == "::" } > 1) return false
        return if (hasCompress) {
            val sides = s.split("::")
            val left = if (sides[0].isEmpty()) emptyList() else sides[0].split(":")
            val right = if (sides[1].isEmpty()) emptyList() else sides[1].split(":")
            left.size + right.size < 8 && (left + right).all { isValidIPv6Group(it) }
        } else {
            val groups = s.split(":")
            groups.size == 8 && groups.all { isValidIPv6Group(it) }
        }
    }

    private fun isValidIPv6Group(s: String): Boolean = s.length in 1..4 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun isValidEmail(value: String): Boolean {
        if (value.isEmpty()) return false
        return if (value.startsWith('"')) {
            val closeQuote = findClosingQuote(value) ?: return false
            if (closeQuote + 1 >= value.length || value[closeQuote + 1] != '@') return false
            val domain = value.substring(closeQuote + 2)
            isValidEmailLocalQuoted(value.substring(1, closeQuote)) && domain.isNotEmpty() && isValidEmailDomain(domain)
        } else {
            val atIdx = value.lastIndexOf('@')
            if (atIdx <= 0) return false
            val local = value.substring(0, atIdx)
            val domain = value.substring(atIdx + 1)
            domain.isNotEmpty() && isValidEmailLocalUnquoted(local) && isValidEmailDomain(domain)
        }
    }

    /** Index of the quote closing a quoted local part, honouring backslash escapes. */
    private fun findClosingQuote(value: String): Int? {
        var i = 1
        while (i < value.length) {
            when (value[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun isValidEmailLocalQuoted(local: String): Boolean {
        var i = 0
        while (i < local.length) {
            val c = local[i]
            if (c == '\\') {
                // quoted-pair may only escape an ASCII VCHAR or WSP
                val escaped = local.getOrNull(i + 1) ?: return false
                if (escaped.code > 127 || (escaped.code < 0x20 && escaped != '\t')) return false
                i += 2
            } else {
                if (c == '"' || c.code == 0x7F || (c.code < 0x20 && c != '\t')) return false
                i++
            }
        }
        return true
    }

    private fun isValidEmailLocalUnquoted(local: String): Boolean {
        if (local.startsWith('.') || local.endsWith('.')) return false
        if (local.contains("..")) return false
        return local.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in EMAIL_LOCAL_SPECIAL_CHARS }
    }

    private fun isValidEmailDomain(domain: String): Boolean {
        if (domain.startsWith('[') && domain.endsWith(']')) {
            val inner = domain.substring(1, domain.length - 1)
            return if (inner.startsWith("IPv6:", ignoreCase = true)) isValidIPv6(inner.substring(5)) else isValidIPv4(inner)
        }
        return isValidHostname(domain)
    }

    /**
     * Shared RFC 3986 / RFC 3987 validation behind the uri, uri-reference, iri and
     * iri-reference formats.
     *
     * @param requireScheme true for the absolute forms (uri, iri)
     * @param allowUcs true for the IRI forms, which additionally allow non-ASCII characters
     */
    private fun isValidUriLike(value: String, requireScheme: Boolean, allowUcs: Boolean): Boolean {
        val schemeEnd = schemeLength(value)
        if (schemeEnd == null && requireScheme) return false
        val rest = if (schemeEnd != null) value.substring(schemeEnd + 1) else value

        val authority: String?
        val afterAuthority: String
        if (rest.startsWith("//")) {
            val body = rest.substring(2)
            val end = body.indexOfFirst { it == '/' || it == '?' || it == '#' }.takeIf { it >= 0 } ?: body.length
            authority = body.substring(0, end)
            afterAuthority = body.substring(end)
        } else {
            authority = null
            afterAuthority = rest
        }

        if (authority != null && !isValidUriAuthority(authority, allowUcs)) return false
        // Square brackets delimit an IP-literal host and are illegal anywhere else
        if (!isValidUriCharacters(afterAuthority, allowUcs)) return false

        // A relative-path reference whose first segment contains a colon would be
        // read as a scheme, so RFC 3986 forbids it
        if (schemeEnd == null && authority == null && !value.startsWith("/")) {
            val path = value.substringBefore('#').substringBefore('?')
            if (':' in path.substringBefore('/')) return false
        }
        return true
    }

    /** Length of the leading `scheme:` if the value starts with a valid scheme, else null. */
    private fun schemeLength(value: String): Int? {
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        val scheme = value.substring(0, colon)
        if (scheme[0] !in 'a'..'z' && scheme[0] !in 'A'..'Z') return null
        if (scheme.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '+' && it != '-' && it != '.' }) return null
        return colon
    }

    /** Characters legal outside an authority, with percent-encoding required to be complete triplets. */
    private fun isValidUriCharacters(value: String, allowUcs: Boolean): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' -> {
                    if (i + 2 >= value.length || !isHexChar(value[i + 1]) || !isHexChar(value[i + 2])) return false
                    i += 3
                }
                c.code > 127 -> {
                    if (!allowUcs) return false
                    i++
                }
                c in URI_FORBIDDEN_CHARS || c.code <= 0x20 || c.code == 0x7F -> return false
                else -> i++
            }
        }
        return true
    }

    private fun isValidUriAuthority(authority: String, allowUcs: Boolean): Boolean {
        // userinfo may not contain an unencoded at-sign, so at most one may appear
        if (authority.count { it == '@' } > 1) return false
        val userinfo = authority.substringBeforeLast('@', "")
        if ('[' in userinfo || ']' in userinfo) return false
        if (!isValidUriCharacters(userinfo, allowUcs)) return false

        val hostAndPort = authority.substringAfterLast('@')
        val port: String?
        if (hostAndPort.startsWith('[')) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return false
            val host = hostAndPort.substring(1, close)
            if (!isValidIPv6(host) && !isValidIpvFuture(host)) return false
            val after = hostAndPort.substring(close + 1)
            port = when {
                after.isEmpty() -> null
                after.startsWith(':') -> after.substring(1)
                else -> return false
            }
        } else {
            val colon = hostAndPort.lastIndexOf(':')
            val host = if (colon >= 0) hostAndPort.substring(0, colon) else hostAndPort
            port = if (colon >= 0) hostAndPort.substring(colon + 1) else null
            // a reg-name cannot contain a colon; an IPv6 host must be bracketed
            if ('[' in host || ']' in host || ':' in host) return false
            if (!isValidUriCharacters(host, allowUcs)) return false
        }
        return port == null || port.all { it in '0'..'9' }
    }

    private fun isValidIpvFuture(host: String): Boolean = host.length >= 3 &&
            (host[0] == 'v' || host[0] == 'V') &&
            host.drop(1).substringBefore('.').let { it.isNotEmpty() && it.all { c -> isHexChar(c) } } &&
            '.' in host.drop(1)

    private fun isHexChar(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isValidHostname(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_HOSTNAME_LENGTH) return false
        if (value.any { it.code > 127 }) return false
        val labels = value.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_LENGTH || !it.matches(REGEX_HOSTNAME_LABEL) }) return false

        // An A-label is only a hostname if its Punycode decodes to a valid U-label
        val decoded = labels.map { label ->
            if (label.startsWith(ACE_PREFIX, ignoreCase = true)) {
                val lower = label.lowercase()
                val uLabel = punycodeDecode(lower.substring(ACE_PREFIX.length)) ?: return false
                if (ACE_PREFIX + punycodeEncode(uLabel) != lower) return false
                uLabel
            } else {
                label
            }
        }
        val bidiDomain = decoded.any { label -> label.codePoints().anyMatch { isRtlCodePoint(it) } }
        return decoded.withIndex().all { (i, uLabel) ->
            uLabel == labels[i] || isValidULabel(uLabel, bidiDomain)
        }
    }

    private fun isValidIdnHostname(value: String): Boolean {
        if (value.isEmpty()) return false
        val mapped = idnaMap(value)
        val labels = mapped.split('.', '。')
        if (labels.any { it.isEmpty() }) return false

        val uLabels = labels.map { label ->
            if (label.startsWith(ACE_PREFIX)) {
                val uLabel = punycodeDecode(label.substring(ACE_PREFIX.length)) ?: return false
                // A-labels must be canonical: re-encoding has to reproduce the label exactly
                if (ACE_PREFIX + punycodeEncode(uLabel) != label) return false
                uLabel
            } else {
                label
            }
        }
        // Both length limits are defined on the A-label form of each label
        val encodedLengths = uLabels.map { uLabel ->
            val aLabel = if (uLabel.all { it.code < 128 }) uLabel else ACE_PREFIX + punycodeEncode(uLabel)
            if (aLabel.length > MAX_LABEL_LENGTH) return false
            aLabel.length
        }
        if (encodedLengths.sum() + encodedLengths.size - 1 > MAX_HOSTNAME_LENGTH) return false

        val bidiDomain = uLabels.any { label -> label.codePoints().anyMatch { isRtlCodePoint(it) } }
        return uLabels.all { isValidULabel(it, bidiDomain) }
    }

    /**
     * UTS 46 mapping: drop the ignorable code points, then normalise and case-fold, so that
     * fullwidth and uppercase spellings of a name are accepted as the name itself.
     */
    private fun idnaMap(value: String): String {
        val withoutIgnored = buildString {
            value.codePoints().forEach { cp -> if (!isIdnaIgnored(cp)) appendCodePoint(cp) }
        }
        return java.text.Normalizer.normalize(withoutIgnored, java.text.Normalizer.Form.NFKC).lowercase()
    }

    private fun isIdnaIgnored(cp: Int): Boolean = cp == 0x00AD || // SOFT HYPHEN
            cp == 0x034F || // COMBINING GRAPHEME JOINER
            cp == 0x200B || // ZERO WIDTH SPACE
            cp == 0x2060 || // WORD JOINER
            cp == 0xFEFF || // ZERO WIDTH NO-BREAK SPACE
            cp in 0x180B..0x180D || // MONGOLIAN FREE VARIATION SELECTORS
            cp in 0xFE00..0xFE0F || // VARIATION SELECTORS
            cp in 0xE0100..0xE01EF // VARIATION SELECTORS SUPPLEMENT

    /**
     * Validates a Unicode label against the parts of RFC 5892 and RFC 5893 that can be
     * decided without the full IDNA derived-property table: the hyphen rules, the code
     * point categories, the contextual rules of Appendix A, and the Bidi rule.
     */
    private fun isValidULabel(label: String, bidiDomain: Boolean): Boolean {
        if (label.isEmpty()) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
        if (label.length >= 4 && label[2] == '-' && label[3] == '-') return false

        val codePoints = label.codePoints().toArray()
        val firstType = Character.getType(codePoints[0])
        if (firstType == Character.NON_SPACING_MARK.toInt() ||
            firstType == Character.COMBINING_SPACING_MARK.toInt() ||
            firstType == Character.ENCLOSING_MARK.toInt()
        ) {
            return false
        }
        if (codePoints.any { !isAllowedIdnCodePoint(it) }) return false
        if (!satisfiesContextualRules(codePoints)) return false
        return !bidiDomain || satisfiesBidiRule(codePoints)
    }

    private fun isAllowedIdnCodePoint(cp: Int): Boolean {
        if (cp == ZWNJ || cp == ZWJ) return true // CONTEXTJ, decided by the contextual rules
        if (cp in IDNA_CONTEXT_O_CHARS) return true // CONTEXTO, likewise
        if (cp in IDNA_DISALLOWED_CODE_POINTS) return false
        if (cp in IDNA_PVALID_EXCEPTIONS) return true
        if (cp == '-'.code) return true
        return when (Character.getType(cp)) {
            Character.LOWERCASE_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(),
            Character.MODIFIER_LETTER.toInt(),
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            -> true
            else -> false
        }
    }

    /** RFC 5892 Appendix A: the CONTEXTJ and CONTEXTO rules. */
    private fun satisfiesContextualRules(codePoints: IntArray): Boolean {
        var hasArabicIndic = false
        var hasExtendedArabicIndic = false
        codePoints.forEachIndexed { i, cp ->
            val previous = codePoints.getOrNull(i - 1)
            val next = codePoints.getOrNull(i + 1)
            when {
                // ZERO WIDTH JOINER: only after a Virama
                cp == ZWJ -> if (previous == null || !isVirama(previous)) return false
                // ZERO WIDTH NON-JOINER: after a Virama, or inside an Arabic-style joining sequence
                cp == ZWNJ ->
                    if ((previous == null || !isVirama(previous)) && !isJoiningContext(codePoints, i)) return false
                // MIDDLE DOT: only between two 'l's
                cp == 0x00B7 -> if (previous != 'l'.code || next != 'l'.code) return false
                // GREEK LOWER NUMERAL SIGN: must be followed by Greek
                cp == 0x0375 -> if (next == null || Character.UnicodeScript.of(next) != Character.UnicodeScript.GREEK) return false
                // HEBREW GERESH and GERSHAYIM: must be preceded by Hebrew
                cp == 0x05F3 || cp == 0x05F4 ->
                    if (previous == null || Character.UnicodeScript.of(previous) != Character.UnicodeScript.HEBREW) return false
                // KATAKANA MIDDLE DOT: the label must carry Hiragana, Katakana or Han
                cp == 0x30FB ->
                    if (codePoints.none { other ->
                            Character.UnicodeScript.of(other) in KATAKANA_MIDDLE_DOT_SCRIPTS
                        }
                    ) {
                        return false
                    }
                cp in 0x0660..0x0669 -> hasArabicIndic = true
                cp in 0x06F0..0x06F9 -> hasExtendedArabicIndic = true
            }
        }
        // The two Arabic-Indic digit blocks may not be mixed within one label
        return !(hasArabicIndic && hasExtendedArabicIndic)
    }

    /**
     * RFC 5892 rule B for ZERO WIDTH NON-JOINER: a joining or dual-joining character
     * before it and a right-joining or dual-joining one after, ignoring transparent marks.
     */
    private fun isJoiningContext(codePoints: IntArray, index: Int): Boolean {
        var before = index - 1
        while (before >= 0 && isJoiningTransparent(codePoints[before])) before--
        var after = index + 1
        while (after < codePoints.size && isJoiningTransparent(codePoints[after])) after++
        if (before < 0 || after >= codePoints.size) return false
        return isJoiningLetter(codePoints[before]) && isJoiningLetter(codePoints[after])
    }

    private fun isJoiningTransparent(cp: Int): Boolean = Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(cp) == Character.ENCLOSING_MARK.toInt() ||
            Character.getType(cp) == Character.FORMAT.toInt()

    /** Cursive scripts whose letters carry a joining type of L, R or D. */
    private fun isJoiningLetter(cp: Int): Boolean = Character.isLetter(cp) && Character.UnicodeScript.of(cp) in CURSIVE_SCRIPTS

    private fun isVirama(cp: Int): Boolean = cp in VIRAMA_CODE_POINTS

    private fun isRtlCodePoint(cp: Int): Boolean = when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_ARABIC_NUMBER,
            -> true
            else -> false
        }

    /** RFC 5893: every label of a domain that contains any RTL character must satisfy this. */
    private fun satisfiesBidiRule(codePoints: IntArray): Boolean {
        val first = Character.getDirectionality(codePoints[0])
        val rtl = first == Character.DIRECTIONALITY_RIGHT_TO_LEFT || first == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        if (!rtl && first != Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false

        val allowed = if (rtl) BIDI_RTL_ALLOWED else BIDI_LTR_ALLOWED
        val trailing = if (rtl) BIDI_RTL_TRAILING else BIDI_LTR_TRAILING
        if (codePoints.any { Character.getDirectionality(it) !in allowed }) return false

        val last = codePoints.last { Character.getDirectionality(it) != Character.DIRECTIONALITY_NONSPACING_MARK }
        if (Character.getDirectionality(last) !in trailing) return false

        // An RTL label may carry European or Arabic numbers, but not both
        if (rtl) {
            val hasEuropean = codePoints.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_EUROPEAN_NUMBER }
            val hasArabic = codePoints.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_ARABIC_NUMBER }
            if (hasEuropean && hasArabic) return false
        }
        return true
    }

    /** RFC 3492 Punycode decoding. Returns null when the input is not valid Punycode. */
    private fun punycodeDecode(input: String): String? {
        var n = PUNYCODE_INITIAL_N
        var i = 0
        var bias = PUNYCODE_INITIAL_BIAS
        val output = mutableListOf<Int>()

        val lastHyphen = input.lastIndexOf('-')
        if (lastHyphen >= 0) {
            for (c in input.substring(0, lastHyphen)) {
                if (c.code > 127) return null
                output.add(c.code)
            }
        }

        var pos = if (lastHyphen >= 0) lastHyphen + 1 else 0
        if (pos >= input.length) return null
        while (pos < input.length) {
            val previousI = i
            var weight = 1
            var k = PUNYCODE_BASE
            while (true) {
                if (pos >= input.length) return null
                val digit = punycodeDigit(input[pos]) ?: return null
                pos++
                if (digit > (Int.MAX_VALUE - i) / weight) return null
                i += digit * weight
                val t = when {
                    k <= bias -> PUNYCODE_TMIN
                    k >= bias + PUNYCODE_TMAX -> PUNYCODE_TMAX
                    else -> k - bias
                }
                if (digit < t) break
                if (weight > Int.MAX_VALUE / (PUNYCODE_BASE - t)) return null
                weight *= PUNYCODE_BASE - t
                k += PUNYCODE_BASE
            }
            bias = punycodeAdapt(i - previousI, output.size + 1, previousI == 0)
            if (i / (output.size + 1) > Int.MAX_VALUE - n) return null
            n += i / (output.size + 1)
            i %= output.size + 1
            if (n < 0x80) return null // a basic code point may not be encoded in the extended part
            if (!Character.isValidCodePoint(n)) return null
            output.add(i, n)
            i++
        }
        return buildString { output.forEach { appendCodePoint(it) } }
    }

    /** RFC 3492 Punycode encoding of a Unicode label (without the ACE prefix). */
    private fun punycodeEncode(input: String): String {
        val codePoints = input.codePoints().toArray()
        val output = StringBuilder()
        codePoints.filter { it < 0x80 }.forEach { output.appendCodePoint(it) }
        val basicCount = output.length
        if (basicCount > 0) output.append('-')

        var handled = basicCount
        var n = PUNYCODE_INITIAL_N
        var delta = 0
        var bias = PUNYCODE_INITIAL_BIAS
        while (handled < codePoints.size) {
            val m = codePoints.filter { it >= n }.min()
            delta += (m - n) * (handled + 1)
            n = m
            for (cp in codePoints) {
                if (cp < n) delta++
                if (cp == n) {
                    var q = delta
                    var k = PUNYCODE_BASE
                    while (true) {
                        val t = when {
                            k <= bias -> PUNYCODE_TMIN
                            k >= bias + PUNYCODE_TMAX -> PUNYCODE_TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        output.append(punycodeChar(t + (q - t) % (PUNYCODE_BASE - t)))
                        q = (q - t) / (PUNYCODE_BASE - t)
                        k += PUNYCODE_BASE
                    }
                    output.append(punycodeChar(q))
                    bias = punycodeAdapt(delta, handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    private fun punycodeAdapt(delta: Int, numPoints: Int, firstTime: Boolean): Int {
        var d = if (firstTime) delta / PUNYCODE_DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((PUNYCODE_BASE - PUNYCODE_TMIN) * PUNYCODE_TMAX) / 2) {
            d /= PUNYCODE_BASE - PUNYCODE_TMIN
            k += PUNYCODE_BASE
        }
        return k + (PUNYCODE_BASE - PUNYCODE_TMIN + 1) * d / (d + PUNYCODE_SKEW)
    }

    private fun punycodeDigit(c: Char): Int? = when (c) {
            in 'a'..'z' -> c - 'a'
            in 'A'..'Z' -> c - 'A'
            in '0'..'9' -> c - '0' + 26
            else -> null
        }

    private fun punycodeChar(digit: Int): Char = if (digit < 26) 'a' + digit else '0' + (digit - 26)

    private fun isValidIdnEmail(value: String): Boolean {
        val atIdx = value.lastIndexOf('@')
        if (atIdx <= 0 || atIdx >= value.length - 1) return false
        val domain = value.substring(atIdx + 1)
        return if (domain.startsWith('[') && domain.endsWith(']')) isValidEmailDomain(domain) else isValidIdnHostname(domain)
    }

    /**
     * The regex format is defined in terms of ECMA 262, which differs from Java both ways:
     * Java accepts constructs ECMA does not (inline flags, `\a`, `\A`) and rejects two it does
     * (the empty character classes, handled by [translateEmptyCharacterClasses]).
     */
    private fun isValidEcmaRegex(value: String): Boolean {
        if (!hasOnlyEcmaRegexSyntax(value)) return false
        return try {
            Regex(translatePatternToJava(value))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasOnlyEcmaRegexSyntax(pattern: String): Boolean {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1) ?: return false
                    if (next !in ECMA_REGEX_ESCAPES && next !in '1'..'9') return false
                    i += 2
                }
                c == '[' && !inClass -> {
                    inClass = true
                    i++
                }
                c == ']' && inClass -> {
                    inClass = false
                    i++
                }
                c == '(' && !inClass && pattern.startsWith("(?", i) -> {
                    val rest = pattern.substring(i + 2)
                    val recognised = rest.startsWith(":") ||
                        rest.startsWith("=") ||
                        rest.startsWith("!") ||
                        rest.startsWith("<=") ||
                        rest.startsWith("<!") ||
                        (rest.startsWith("<") && rest.getOrNull(1)?.isLetter() == true)
                    if (!recognised) return false
                    i += 2
                }
                else -> i++
            }
        }
        return true
    }

    private fun isValidRelativeJsonPointer(value: String): Boolean {
        if (value.isEmpty()) return false
        var i = 0
        when {
            value[i] == '0' -> i = 1
            // Non-ASCII digits are not part of the grammar, so isDigit() is too permissive here
            value[i] in '1'..'9' -> {
                while (i < value.length && value[i] in '0'..'9') i++
            }
            else -> return false
        }
        val suffix = value.substring(i)
        return when {
            suffix == "#" -> true
            suffix.isEmpty() -> true
            suffix.startsWith("/") -> !suffix.contains(REGEX_JSON_POINTER_INVALID_TILDE)
            else -> false
        }
    }

    /** RFC 6570 URI Template: a sequence of literals and brace-delimited expressions. */
    private fun isValidUriTemplate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '{' -> {
                    val close = value.indexOf('}', i)
                    if (close < 0) return false
                    if (!isValidUriTemplateExpression(value.substring(i + 1, close))) return false
                    i = close + 1
                }
                c == '}' -> return false
                c == '%' -> {
                    if (i + 2 >= value.length || !isHexChar(value[i + 1]) || !isHexChar(value[i + 2])) return false
                    i += 3
                }
                c.code <= 0x20 || c.code == 0x7F || c in URI_TEMPLATE_FORBIDDEN_LITERALS -> return false
                else -> i++
            }
        }
        return true
    }

    private fun isValidUriTemplateExpression(body: String): Boolean {
        if (body.isEmpty()) return false
        val varList = if (body[0] in URI_TEMPLATE_OPERATORS || body[0] in URI_TEMPLATE_RESERVED_OPERATORS) {
            // op-reserve is reserved for future extensions and cannot be used yet
            if (body[0] in URI_TEMPLATE_RESERVED_OPERATORS) return false
            body.substring(1)
        } else {
            body
        }
        return varList.isNotEmpty() && varList.split(',').all { isValidUriTemplateVarspec(it) }
    }

    private fun isValidUriTemplateVarspec(spec: String): Boolean {
        if (spec.isEmpty()) return false
        val colon = spec.indexOf(':')
        val name = when {
            colon >= 0 -> {
                // prefix modifier: 1-4 digits, 1-9999, no leading zero
                val maxLength = spec.substring(colon + 1)
                if (maxLength.isEmpty() || maxLength.length > 4) return false
                if (maxLength[0] !in '1'..'9' || maxLength.any { it !in '0'..'9' }) return false
                spec.substring(0, colon)
            }
            spec.endsWith('*') -> spec.dropLast(1)
            else -> spec
        }
        return isValidUriTemplateVarname(name)
    }

    private fun isValidUriTemplateVarname(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith('.') || name.endsWith('.') || ".." in name) return false
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                c == '%' -> {
                    if (i + 2 >= name.length || !isHexChar(name[i + 1]) || !isHexChar(name[i + 2])) return false
                    i += 3
                }
                c == '.' || c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> i++
                else -> return false
            }
        }
        return true
    }

    private fun isValidDuration(value: String): Boolean {
        if (!value.startsWith("P")) return false
        if (value.length == 1) return false
        if (value.any { it.code > 127 }) return false
        // Weeks-only: P<n>W (cannot be combined with other units)
        if (REGEX_DURATION_WEEKS.matches(value)) return true
        if ('W' in value) return false
        val rest = value.substring(1)
        val tIdx = rest.indexOf('T')
        val datePart = if (tIdx >= 0) rest.substring(0, tIdx) else rest
        val timePart = if (tIdx >= 0) rest.substring(tIdx + 1) else null
        if (timePart != null && timePart.isEmpty()) return false
        val hasDate = if (datePart.isNotEmpty()) {
            if (!REGEX_DURATION_DATE.matches(datePart)) return false
            true
        } else {
            false
        }
        val hasTime = if (timePart != null) {
            if (!REGEX_DURATION_TIME.matches(timePart)) return false
            true
        } else {
            false
        }
        return hasDate || hasTime
    }

    /**
     * Validates a number value
     */
    private fun validateNumber(instance: JsonPrimitive, schema: JsonObject, path: String, schemaPath: String, errors: MutableList<ValidationError>) {
        val number = instance.doubleOrNull ?: return
        // Messages quote the literals as written; only the comparisons go through Double
        val shown = instance.content

        // Minimum
        schema[MINIMUM]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
            if (number < minimum) {
                errors.add(ValidationError(path, "Number $shown is less than minimum ${schema.numberText(MINIMUM)}", MINIMUM, at(schemaPath, MINIMUM)))
            }
        }

        // Maximum
        schema[MAXIMUM]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
            if (number > maximum) {
                errors.add(ValidationError(path, "Number $shown is greater than maximum ${schema.numberText(MAXIMUM)}", MAXIMUM, at(schemaPath, MAXIMUM)))
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
                                errors.add(ValidationError(path, "Number $shown must be greater than ${schema.numberText(MINIMUM)}", EXCLUSIVE_MINIMUM, at(schemaPath, EXCLUSIVE_MINIMUM)))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        exclusiveMin.doubleOrNull?.let { minimum ->
                            if (number <= minimum) {
                                errors.add(ValidationError(path, "Number $shown must be greater than ${exclusiveMin.content}", EXCLUSIVE_MINIMUM, at(schemaPath, EXCLUSIVE_MINIMUM)))
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
                                errors.add(ValidationError(path, "Number $shown must be less than ${schema.numberText(MAXIMUM)}", EXCLUSIVE_MAXIMUM, at(schemaPath, EXCLUSIVE_MAXIMUM)))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        exclusiveMax.doubleOrNull?.let { maximum ->
                            if (number >= maximum) {
                                errors.add(ValidationError(path, "Number $shown must be less than ${exclusiveMax.content}", EXCLUSIVE_MAXIMUM, at(schemaPath, EXCLUSIVE_MAXIMUM)))
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
                    errors.add(ValidationError(path, "Number $shown is not a multiple of ${schema.numberText(MULTIPLE_OF)}", MULTIPLE_OF, at(schemaPath, MULTIPLE_OF)))
                } else {
                    val rounded = kotlin.math.round(quotient)
                    val diff = kotlin.math.abs(quotient - rounded)
                    // Use relative epsilon for better floating point comparison
                    val epsilon = kotlin.math.max(1e-10, kotlin.math.abs(quotient) * 1e-10)
                    if (diff > epsilon) {
                        errors.add(ValidationError(path, "Number $shown is not a multiple of ${schema.numberText(MULTIPLE_OF)}", MULTIPLE_OF, at(schemaPath, MULTIPLE_OF)))
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
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        schemas.forEachIndexed { index, schema ->
            val branchErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, at(schemaPath, ALL_OF, index.toString()), branchErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (branchErrors.isNotEmpty()) {
                val depthError = branchErrors.firstOrNull { it.keyword == "depth" }
                if (depthError != null) {
                    errors.add(depthError)
                } else {
                    errors.add(
                        ValidationError(
                            path,
                            "Instance does not match allOf schema at index $index",
                            ALL_OF,
                            schemaPath = at(schemaPath, ALL_OF, index.toString()),
                            causes = branchErrors,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Validates anyOf combiner
     */
    private fun validateAnyOf(
        instance: JsonElement,
        schemas: JsonArray,
        path: String,
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val branchErrors = mutableListOf<ValidationError>()
        val anyValid = schemas.mapIndexed { index, schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, at(schemaPath, ANY_OF, index.toString()), tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (tempErrors.isNotEmpty()) {
                branchErrors.add(
                    ValidationError(path, "Branch $index failed", ANY_OF, at(schemaPath, ANY_OF, index.toString()), causes = tempErrors),
                )
            }
            tempErrors.isEmpty()
        }.any { it }

        if (!anyValid) {
            val depthError = branchErrors.flatMap { it.causes }.firstOrNull { it.keyword == "depth" }
            if (depthError != null) {
                errors.add(depthError)
            } else {
                errors.add(
                    ValidationError(
                        path,
                        "Instance does not match any of the ${schemas.size} anyOf schemas",
                        ANY_OF,
                        schemaPath = at(schemaPath, ANY_OF),
                        causes = branchErrors,
                    ),
                )
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
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val branchErrors = mutableListOf<ValidationError>()
        val matchingBranches = schemas.mapIndexedNotNull { index, schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, at(schemaPath, ONE_OF, index.toString()), tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (tempErrors.isNotEmpty()) {
                branchErrors.add(
                    ValidationError(path, "Branch $index failed", ONE_OF, at(schemaPath, ONE_OF, index.toString()), causes = tempErrors),
                )
            }
            if (tempErrors.isEmpty()) index else null
        }

        when (matchingBranches.size) {
            0 -> {
                val depthError = branchErrors.flatMap { it.causes }.firstOrNull { it.keyword == "depth" }
                if (depthError != null) {
                    errors.add(depthError)
                } else {
                    errors.add(
                        ValidationError(
                            path,
                            "Instance does not match any of the ${schemas.size} oneOf schemas",
                            ONE_OF,
                            schemaPath = at(schemaPath, ONE_OF),
                            causes = branchErrors,
                        ),
                    )
                }
            }
            1 -> {} // Valid
            else -> errors.add(
                ValidationError(
                    path,
                    "Instance matches ${matchingBranches.size} of ${schemas.size} oneOf schemas (branches ${matchingBranches.joinToString(", ")}), expected exactly 1",
                    ONE_OF,
                    schemaPath = at(schemaPath, ONE_OF),
                ),
            )
        }
    }

    /**
     * Collects the set of array indices "evaluated" by a schema and its applicators.
     * Used to determine which items are subject to unevaluatedItems.
     * Annotation sources: prefixItems, items, additionalItems, contains (matching items when valid),
     * allOf (all branches), anyOf/oneOf (valid branches only), if/then/else, $ref, $dynamicRef,
     * and nested unevaluatedItems sub-schemas.
     */
    private fun collectEvaluatedIndices(
        instance: JsonArray,
        schemaElement: JsonElement,
        path: String,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ): Set<Int> {
        if (schemaElement is JsonPrimitive) return emptySet()
        if (schemaElement !is JsonObject) return emptySet()

        val effectiveResourceRoot = if (schemaElement.containsKey(SchemaKeywords.ID)) schemaElement else resourceRoot
        val effectiveScope = if (schemaElement.containsKey(SchemaKeywords.ID)) dynamicScope + schemaElement else dynamicScope

        val evaluated = mutableSetOf<Int>()

        // prefixItems: evaluates indices 0 until min(prefixItems.size, instance.size)
        schemaElement[PREFIX_ITEMS]?.jsonArray?.let { prefixItems ->
            for (i in 0 until minOf(prefixItems.size, instance.size)) evaluated.add(i)
        }

        // items:
        //  - as single schema without prefixItems: evaluates ALL indices
        //  - as single schema with prefixItems: evaluates remaining indices after prefixItems
        //  - as array (tuple, 2019-09): evaluates indices 0..tuple.size-1
        schemaElement[ITEMS]?.let { itemsSchema ->
            when {
                itemsSchema is JsonArray -> {
                    for (i in 0 until minOf(itemsSchema.size, instance.size)) evaluated.add(i)
                }
                schemaElement.containsKey(PREFIX_ITEMS) -> {
                    val prefixCount = schemaElement[PREFIX_ITEMS]!!.jsonArray.size
                    for (i in prefixCount until instance.size) evaluated.add(i)
                }
                else -> {
                    for (i in instance.indices) evaluated.add(i)
                }
            }
        }

        // additionalItems (2019-09): evaluates indices beyond the items tuple (unless false)
        if (schemaElement.containsKey(ITEMS) && schemaElement[ITEMS] is JsonArray) {
            schemaElement[ADDITIONAL_ITEMS]?.let { addlItemsSchema ->
                val isFalse = addlItemsSchema is JsonPrimitive && addlItemsSchema.booleanOrNull == false
                if (!isFalse) {
                    val tupleSize = (schemaElement[ITEMS] as JsonArray).size
                    for (i in tupleSize until instance.size) evaluated.add(i)
                }
            }
        }

        // contains: evaluates matching indices, but ONLY when contains validates
        schemaElement[CONTAINS]?.let { containsSchema ->
            val minContains = schemaElement[SchemaKeywords.MIN_CONTAINS]?.jsonPrimitive?.let {
                it.doubleOrNull?.toInt() ?: it.intOrNull ?: 1
            } ?: 1

            val matchingIndices = mutableListOf<Int>()
            instance.forEachIndexed { index, item ->
                val tempErrors = mutableListOf<ValidationError>()
                validateElement(item, containsSchema, "$path[$index]", "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                if (tempErrors.isEmpty()) matchingIndices.add(index)
            }
            // Annotation only produced when contains validates (minContains satisfied)
            if (matchingIndices.size >= minContains) {
                evaluated.addAll(matchingIndices)
            }
        }

        // $ref
        schemaElement[REF]?.jsonPrimitive?.contentOrNull?.let { ref ->
            val refHashIndex = ref.indexOf('#')
            val refUriPart = if (refHashIndex >= 0) ref.substring(0, refHashIndex) else ref
            val resolved = if (refUriPart.isEmpty()) {
                referenceResolver.resolveRef(ref, effectiveResourceRoot, schemaElement)
            } else {
                referenceResolver.resolveRef(ref, rootSchema, schemaElement)
            }
            resolved?.let {
                evaluated.addAll(collectEvaluatedIndices(instance, it, path, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // $recursiveRef
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
                evaluated.addAll(collectEvaluatedIndices(instance, target, path, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // $dynamicRef
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
                evaluated.addAll(collectEvaluatedIndices(instance, target, path, version, rootSchema, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // allOf: all branches always contribute
        schemaElement[ALL_OF]?.jsonArray?.forEach { subSchema ->
            evaluated.addAll(collectEvaluatedIndices(instance, subSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
        }

        // anyOf: only valid branches contribute
        schemaElement[ANY_OF]?.jsonArray?.forEach { subSchema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateElement(instance, subSchema, path, "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (tempErrors.isEmpty()) {
                evaluated.addAll(collectEvaluatedIndices(instance, subSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
            }
        }

        // oneOf: only the single valid branch contributes
        schemaElement[ONE_OF]?.jsonArray?.let { schemas ->
            val validBranch =
                schemas.firstOrNull { subSchema ->
                    val tempErrors = mutableListOf<ValidationError>()
                    validateElement(instance, subSchema, path, "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    tempErrors.isEmpty()
                }
            validBranch?.let {
                evaluated.addAll(collectEvaluatedIndices(instance, it, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
            }
        }

        // if/then/else: when if passes collect from if AND then; when fails collect from else
        schemaElement[IF]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            validateElement(instance, ifSchema, path, "", ifErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (ifErrors.isEmpty()) {
                evaluated.addAll(collectEvaluatedIndices(instance, ifSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                schemaElement[THEN]?.let { thenSchema ->
                    evaluated.addAll(collectEvaluatedIndices(instance, thenSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                }
            } else {
                schemaElement[ELSE]?.let { elseSchema ->
                    evaluated.addAll(collectEvaluatedIndices(instance, elseSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
                }
            }
        }

        // unevaluatedItems in a sub-schema is itself an annotation source
        schemaElement[UNEVALUATED_ITEMS]?.let { unevalItemsSchema ->
            val yetUnevaluated = instance.indices.filter { it !in evaluated }
            when {
                unevalItemsSchema is JsonPrimitive && unevalItemsSchema.booleanOrNull == true ->
                    evaluated.addAll(yetUnevaluated)
                unevalItemsSchema is JsonPrimitive && unevalItemsSchema.booleanOrNull == false -> {}
                else ->
                    yetUnevaluated.forEach { index ->
                        val tempErrors = mutableListOf<ValidationError>()
                        validateElement(instance[index], unevalItemsSchema, "$path[$index]", "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                        if (tempErrors.isEmpty()) evaluated.add(index)
                    }
            }
        }

        return evaluated
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
                evaluated.addAll(instance.keys.filter { compiledPattern(pattern).containsMatchIn(it) })
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
            validateElement(instance, subSchema, path, "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
            if (tempErrors.isEmpty()) {
                evaluated.addAll(collectEvaluatedProperties(instance, subSchema, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
            }
        }

        // oneOf: annotations only from the single valid branch
        schemaElement[ONE_OF]?.jsonArray?.let { schemas ->
            val validBranch =
                schemas.firstOrNull { subSchema ->
                    val tempErrors = mutableListOf<ValidationError>()
                    validateElement(instance, subSchema, path, "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
                    tempErrors.isEmpty()
                }
            validBranch?.let {
                evaluated.addAll(collectEvaluatedProperties(instance, it, path, version, rootSchema, depth + 1, resourceRoot, dynamicScope))
            }
        }

        // if/then/else: when if passes, collect from the if schema AND then; when if fails, collect from else
        schemaElement[IF]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            validateElement(instance, ifSchema, path, "", ifErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
                        validateElement(instance[propName]!!, unevalPropsSchema, if (path.isEmpty()) propName else "$path.$propName", "", tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)
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
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        rootSchema: JsonElement,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val tempErrors = mutableListOf<ValidationError>()
        validateElement(instance, schema, path, at(schemaPath, NOT), tempErrors, version, rootSchema, depth + 1, resourceRoot, dynamicScope)

        // Check if schema hit depth limit - if so, propagate that error
        val depthError = tempErrors.firstOrNull { it.keyword == "depth" }
        if (depthError != null) {
            errors.add(depthError)
        } else if (tempErrors.isEmpty()) {
            errors.add(ValidationError(path, "Instance matches the not schema but should not", NOT, at(schemaPath, NOT)))
        }
    }

    /**
     * Checks if a property name matches any pattern
     */
    private fun matchesAnyPattern(propName: String, patterns: Set<String>): Boolean = patterns.any { compiledPattern(it).containsMatchIn(propName) }

    /**
     * Validates the structure of a schema itself
     */
    private fun validateSchemaStructure(schema: JsonElement, path: String, schemaPath: String, errors: MutableList<ValidationError>) {
        when (schema) {
            is JsonObject -> {
                // Check for invalid combinations
                val validTypes = SchemaKeywords.VALID_TYPES
                if (schema.containsKey(TYPE)) {
                    when (val type = schema[TYPE]) {
                        is JsonPrimitive -> {
                            if (!type.isString) {
                                errors.add(ValidationError(path, "type must be a string or array of strings", SCHEMA, schemaPath))
                            } else if (type.content !in validTypes) {
                                errors.add(ValidationError(path, "type value '${type.content}' is not a valid JSON type", SCHEMA, schemaPath))
                            }
                        }
                        is JsonArray -> {
                            type.forEach { typeElement ->
                                if (typeElement !is JsonPrimitive || !typeElement.isString) {
                                    errors.add(ValidationError(path, "type array must contain only strings", SCHEMA, schemaPath))
                                } else if (typeElement.content !in validTypes) {
                                    errors.add(
                                        ValidationError(path, "type value '${typeElement.content}' is not a valid JSON type", SCHEMA, schemaPath),
                                    )
                                }
                            }
                        }
                        else -> {
                            errors.add(ValidationError(path, "type must be a string or array", SCHEMA, schemaPath))
                        }
                    }
                }

                // Validate $schema if present
                schema[SCHEMA]?.let { schemaUri ->
                    if (schemaUri !is JsonPrimitive || !schemaUri.isString) {
                        errors.add(ValidationError(path, "$SCHEMA must be a string", SCHEMA, schemaPath))
                    }
                }

                // Recursively validate nested schemas
                schema.forEach { (key, value) ->
                    val newPath = if (path.isEmpty()) key else "$path.$key"
                    when (key) {
                        PROPERTIES, PATTERN_PROPERTIES, DEPENDENT_SCHEMAS -> {
                            if (value is JsonObject) {
                                value.forEach { (propName, propSchema) ->
                                    validateSchemaStructure(propSchema, "$newPath.$propName", at(schemaPath, PROPERTIES, propName), errors)
                                }
                            }
                        }
                        ITEMS, ADDITIONAL_PROPERTIES, ADDITIONAL_ITEMS, CONTAINS, PROPERTY_NAMES,
                        IF, THEN, ELSE, NOT,
                        -> {
                            validateSchemaStructure(value, newPath, "$schemaPath/${JsonPointer.encodeToken(key)}", errors)
                        }
                        ALL_OF, ANY_OF, ONE_OF, PREFIX_ITEMS -> {
                            if (value is JsonArray) {
                                value.forEachIndexed { index, subSchema ->
                                    validateSchemaStructure(subSchema, "$newPath[$index]", "$schemaPath/$index", errors)
                                }
                            }
                        }
                    }
                }
            }
            is JsonPrimitive -> {
                // Boolean schemas are valid
                if (schema.booleanOrNull == null && !schema.isString) {
                    errors.add(ValidationError(path, "Schema must be an object or boolean", SCHEMA, schemaPath))
                }
            }
            else -> {
                errors.add(ValidationError(path, "Schema must be an object or boolean", SCHEMA, schemaPath))
            }
        }
    }

    private fun translatePatternToJava(pattern: String): String {
        val withJavaCategories = pattern.replace(REGEX_UNICODE_CATEGORY) { match ->
            val flag = match.groupValues[1]
            val name = match.groupValues[2]
            val javaName = UNICODE_CATEGORY_NAMES[name] ?: name
            "\\$flag{$javaName}"
        }
        return translateEmptyCharacterClasses(withJavaCategories)
    }

    /** `[]` (matches nothing) and `[^]` (matches anything) are valid ECMA 262 but errors in Java. */
    private fun translateEmptyCharacterClasses(pattern: String): String {
        val result = StringBuilder(pattern.length)
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    result.append(c)
                    pattern.getOrNull(i + 1)?.let { result.append(it) }
                    i += 2
                }
                c == '[' && !inClass && pattern.startsWith("[^]", i) -> {
                    result.append("[\\s\\S]")
                    i += 3
                }
                c == '[' && !inClass && pattern.startsWith("[]", i) -> {
                    result.append("[^\\s\\S]")
                    i += 2
                }
                c == '[' && !inClass -> {
                    inClass = true
                    result.append(c)
                    i++
                }
                c == ']' && inClass -> {
                    inClass = false
                    result.append(c)
                    i++
                }
                else -> {
                    result.append(c)
                    i++
                }
            }
        }
        return result.toString()
    }

    companion object {
        private val REGEX_UNICODE_CATEGORY = Regex("""\\([pP])\{([^}]+)}""")
        private val EMAIL_LOCAL_SPECIAL_CHARS = setOf('.', '!', '#', '$', '%', '&', '\'', '*', '+', '-', '/', '=', '?', '^', '_', '`', '{', '|', '}', '~')
        private val REGEX_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val REGEX_TIME = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$")
        private val REGEX_DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$")
private val REGEX_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val REGEX_JSON_POINTER_INVALID_TILDE = Regex("~(?![01])")
        private val REGEX_HOSTNAME_LABEL = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?")
        private val REGEX_DURATION_WEEKS = Regex("^P\\d+W$")

        // RFC 3339 Appendix A fixes the order too: a unit may only be followed by the next
        // smaller one, so P1Y2D (year then day) and PT1H2S (hour then second) are not durations
        private val REGEX_DURATION_DATE = Regex("^(\\d+Y(\\d+M(\\d+D)?)?|\\d+M(\\d+D)?|\\d+D)$")
        private val REGEX_DURATION_TIME = Regex("^(\\d+H(\\d+M(\\d+S)?)?|\\d+M(\\d+S)?|\\d+S)$")

        // Illegal outside an authority component; brackets only delimit an IP-literal host
        private val URI_FORBIDDEN_CHARS = setOf(' ', '"', '<', '>', '{', '}', '^', '`', '|', '\\', '[', ']')

        private val URI_TEMPLATE_FORBIDDEN_LITERALS = setOf(' ', '"', '<', '>', '^', '`', '|', '\\')
        private val URI_TEMPLATE_OPERATORS = setOf('+', '#', '.', '/', ';', '?', '&')
        private val URI_TEMPLATE_RESERVED_OPERATORS = setOf('=', ',', '!', '@', '|')

        // Escapes ECMA 262 recognises; Java additionally accepts \a, \A, \Z, \z, \G, \R, \h, \X ...
        private val ECMA_REGEX_ESCAPES =
            setOf(
                'f', 'n', 'r', 't', 'v', '0', 'x', 'u', 'c',
                'd', 'D', 's', 'S', 'w', 'W', 'b', 'B', 'k', 'p', 'P',
                '^', '$', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|', '/', '-',
            )

        // Maps ECMAScript Unicode category long names to Java short names for \p{} in patterns
        private val UNICODE_CATEGORY_NAMES =
            mapOf(
                "Letter" to "L",
                "Lowercase_Letter" to "Ll",
                "Uppercase_Letter" to "Lu",
                "Titlecase_Letter" to "Lt",
                "Modifier_Letter" to "Lm",
                "Other_Letter" to "Lo",
                "Mark" to "M",
                "Nonspacing_Mark" to "Mn",
                "Spacing_Mark" to "Mc",
                "Enclosing_Mark" to "Me",
                "Number" to "N",
                "Decimal_Number" to "Nd",
                "Letter_Number" to "Nl",
                "Other_Number" to "No",
                "Punctuation" to "P",
                "Connector_Punctuation" to "Pc",
                "Dash_Punctuation" to "Pd",
                "Open_Punctuation" to "Ps",
                "Close_Punctuation" to "Pe",
                "Initial_Punctuation" to "Pi",
                "Final_Punctuation" to "Pf",
                "Other_Punctuation" to "Po",
                "Symbol" to "S",
                "Math_Symbol" to "Sm",
                "Currency_Symbol" to "Sc",
                "Modifier_Symbol" to "Sk",
                "Other_Symbol" to "So",
                "Separator" to "Z",
                "Space_Separator" to "Zs",
                "Line_Separator" to "Zl",
                "Paragraph_Separator" to "Zp",
                "Other" to "C",
                "Control" to "Cc",
                "Format" to "Cf",
                "Surrogate" to "Cs",
                "Private_Use" to "Co",
                "Unassigned" to "Cn",
            )

        private const val MAX_MESSAGE_VALUE_LENGTH = 40

        private const val ACE_PREFIX = "xn--"
        private const val MAX_LABEL_LENGTH = 63
        private const val MAX_HOSTNAME_LENGTH = 253
        private const val ZWNJ = 0x200C
        private const val ZWJ = 0x200D

        private const val PUNYCODE_BASE = 36
        private const val PUNYCODE_TMIN = 1
        private const val PUNYCODE_TMAX = 26
        private const val PUNYCODE_SKEW = 38
        private const val PUNYCODE_DAMP = 700
        private const val PUNYCODE_INITIAL_BIAS = 72
        private const val PUNYCODE_INITIAL_N = 128

        // RFC 5892 Section 2.6: PVALID despite their code point category
        private val IDNA_PVALID_EXCEPTIONS =
            setOf(
                0x00DF, // LATIN SMALL LETTER SHARP S
                0x03C2, // GREEK SMALL LETTER FINAL SIGMA
                0x06FD, // ARABIC SIGN SINDHI AMPERSAND
                0x06FE, // ARABIC SIGN SINDHI POSTPOSITION MEN
                0x0F0B, // TIBETAN MARK INTERSYLLABIC TSHEG
                0x3007, // IDEOGRAPHIC NUMBER ZERO
            )

        // CONTEXTO code points: allowed only where their rule in RFC 5892 Appendix A holds
        private val IDNA_CONTEXT_O_CHARS = setOf(0x00B7, 0x0375, 0x05F3, 0x05F4, 0x30FB)

        private val KATAKANA_MIDDLE_DOT_SCRIPTS =
            setOf(
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HAN,
            )

        // Scripts written cursively, whose letters join to their neighbours
        private val CURSIVE_SCRIPTS =
            setOf(
                Character.UnicodeScript.ARABIC,
                Character.UnicodeScript.SYRIAC,
                Character.UnicodeScript.NKO,
                Character.UnicodeScript.MANDAIC,
                Character.UnicodeScript.MANICHAEAN,
                Character.UnicodeScript.PSALTER_PAHLAVI,
                Character.UnicodeScript.HANIFI_ROHINGYA,
                Character.UnicodeScript.SOGDIAN,
                Character.UnicodeScript.ADLAM,
            )

        // Combining class 9 (Virama), which licenses a following ZWJ or ZWNJ
        private val VIRAMA_CODE_POINTS =
            setOf(
                0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D3B, 0x0D3C,
                0x0D4D, 0x0DCA, 0x0E3A, 0x0EBA, 0x0F84, 0x1039, 0x103A, 0x1714, 0x1734, 0x17D2,
                0x1A60, 0x1B44, 0x1BAA, 0x1BAB, 0x1BF2, 0x1BF3, 0x2D7F, 0xA806, 0xA82C, 0xA8C4,
                0xA953, 0xA9C0, 0xAAF6, 0xABED, 0x10A3F, 0x11046, 0x1107F, 0x110B9, 0x111C0,
                0x11235, 0x1134D, 0x11442, 0x114C2, 0x115BF, 0x1163F, 0x116B6, 0x1172B, 0x11839,
                0x119E0, 0x11A34, 0x11A47, 0x11A99, 0x11C3F, 0x11D44, 0x11D45, 0x11D97,
            )

        private val BIDI_RTL_ALLOWED =
            setOf(
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_ARABIC_NUMBER,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
                Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
                Character.DIRECTIONALITY_OTHER_NEUTRALS,
                Character.DIRECTIONALITY_BOUNDARY_NEUTRAL,
                Character.DIRECTIONALITY_NONSPACING_MARK,
            )

        private val BIDI_LTR_ALLOWED =
            setOf(
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
                Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
                Character.DIRECTIONALITY_OTHER_NEUTRALS,
                Character.DIRECTIONALITY_BOUNDARY_NEUTRAL,
                Character.DIRECTIONALITY_NONSPACING_MARK,
            )

        private val BIDI_RTL_TRAILING =
            setOf(
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER,
                Character.DIRECTIONALITY_ARABIC_NUMBER,
            )

        private val BIDI_LTR_TRAILING =
            setOf(
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER,
            )

        // Characters DISALLOWED in IDN labels per RFC 5892
        private val IDNA_DISALLOWED_CODE_POINTS =
            setOf(
                0x0640, // ARABIC TATWEEL
                0x07FA, // NKO LAJANYALAN
                0x302E, // HANGUL SINGLE DOT TONE MARK
                0x302F, // HANGUL DOUBLE DOT TONE MARK
                0x3031, // VERTICAL KANA REPEAT MARK
                0x3032, // VERTICAL KANA REPEAT WITH VOICED ITERATION MARK
                0x3033, // VERTICAL KANA REPEAT MARK UPPER HALF
                0x3034, // VERTICAL KANA REPEAT WITH VOICED ITERATION MARK UPPER HALF
                0x3035, // VERTICAL KANA REPEAT MARK LOWER HALF
                0x303B, // VERTICAL IDEOGRAPHIC ITERATION MARK
            )
    }
}
