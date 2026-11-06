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
import java.math.BigDecimal

/**
 * Main JSON Schema validator with thread-safe synchronous validation
 */
class JsonValidator @JvmOverloads constructor(
    private val enableMetaSchemaValidation: Boolean = true,
    // Draft 2020-12 uses annotation by default
    private val formatAssertion: Boolean = true,
    private val maxValidationDepth: Int = 1000,
    private val schemaLoader: ((String) -> JsonElement?)? = null,
) {
    init {
        require(maxValidationDepth >= 0) { "maxValidationDepth must not be negative, was $maxValidationDepth" }
    }

    private val schemaStore = SchemaStore(schemaLoader)
    private val patternCache = BoundedCache<String, Regex>(MAX_CACHED_PATTERNS)
    private val numericLimitCache = BoundedCache<String, BigDecimal>(MAX_CACHED_NUMERIC_LIMITS)

    private fun compiledPattern(pattern: String): Regex = patternCache.getOrPut(pattern) { Regex(EcmaRegex.translatePatternToJava(it)) }

    /**
     * Whether [pattern] matches somewhere in [input], or null when the regex engine could not decide:
     * Java's engine recurses once per repetition of a group such as `(a|b)*`, so a long input can exhaust
     * the stack even when it matches (#32). The overflow is confined to this call, so it is caught here
     * and callers fail closed instead of letting validate() throw. The undecided result is recorded in
     * [evaluation], so applicators that invert failures do not invert this one (#51).
     */
    private fun patternMatches(pattern: String, input: String, evaluation: Evaluation): Boolean? = try {
        compiledPattern(pattern).containsMatchIn(input)
    } catch (_: StackOverflowError) {
        evaluation.markUndecided()
        null
    }

    /**
     * Validates a JSON instance against a JSON schema
     * Thread-safe synchronous implementation
     */
    fun validate(instance: JsonElement, schema: JsonSchema): ValidationResult = validateInternal(instance, schema, "")

    /**
     * Validates a JSON instance against a JSON schema and returns standard output in [format],
     * for example `validate(instance, schema, OutputFormat.Basic)`.
     */
    fun <T : ValidationOutput> validate(instance: JsonElement, schema: JsonSchema, format: OutputFormat<T>): T = validate(instance, schema).toOutput(format)

    /**
     * Validates a JSON instance from string against a schema from string
     */
    @JvmOverloads
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

        return metaSchemaCheck.validateSchema(schema)
    }

    /**
     * The validateSchema machinery, created on the first validateSchema call with meta-schema validation enabled;
     * it and its meta-schema validator die with this validator.
     */
    private val metaSchemaCheck by lazy { MetaSchemaCheck(maxValidationDepth) }

    /**
     * Internal validation implementation
     */
    private fun validateInternal(instance: JsonElement, schema: JsonSchema, path: String): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val evaluation = Evaluation(schema.schema, schemaStore)
        // Relative references resolve against the root's absolute base, which may come from JsonSchema.baseUri.
        schema.absoluteBaseUri()?.let { evaluation.references.registerAbsoluteUri(schema.schema, it) }
        try {
            validateElement(instance, schema.schema, path, "", errors, schema.effectiveVersion, evaluation, depth = 0, resourceRoot = schema.schema, dynamicScope = listOf(schema.schema))
        } catch (_: StackOverflowError) {
            // Whether maxValidationDepth fits depends on the thread's stack size: on a small stack a recursive
            // schema can exhaust it first (#50). Fail closed with a depth error instead of throwing. The shared
            // caches touched on the way are updated atomically (putIfAbsent, synchronized maps) and an in-flight
            // load is released in a finally block, so none is left half-written by the unwinding; the
            // evaluation's own state is discarded with it.
            errors.add(
                ValidationError(path, "Validation ran out of stack before reaching maxValidationDepth ($maxValidationDepth); use a lower limit or a larger thread stack", "depth", ""),
            )
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(evaluation.absoluteLocations.finish(errors, schema))
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

    /**
     * The dynamic scope after entering [resource]; unchanged if it is already in scope. Compared by identity:
     * two equal schemas in different places are different resources, and equality would compare them deeply.
     */
    private fun List<JsonElement>.entering(resource: JsonElement): List<JsonElement> = if (any { it === resource }) this else this + resource

    /**
     * Whether [schema] starts a schema resource: an object with `$id`, whatever the value's type. The verdict pass
     * ([validateElement]) and both `unevaluated*` collectors enter resources through [resourceRootAt] and [scopeAt],
     * which share this one test, so they always agree on the resource root and dynamic scope (guideline 8, #90).
     */
    private fun startsResource(schema: JsonElement): Boolean = schema is JsonObject && schema.containsKey(SchemaKeywords.ID)

    /** The resource root in effect at [schema]: [schema] itself when it [startsResource], else [resourceRoot]. */
    private fun resourceRootAt(schema: JsonElement, resourceRoot: JsonElement): JsonElement = if (startsResource(schema)) schema else resourceRoot

    /** The dynamic scope in effect at [schema]: [dynamicScope] [entering] it when it [startsResource], else unchanged. */
    private fun scopeAt(schema: JsonElement, dynamicScope: List<JsonElement>): List<JsonElement> = if (startsResource(schema)) dynamicScope.entering(schema) else dynamicScope

    /** Whether this is a JSON integer token: an optional minus and digits, with no fraction or exponent. */
    private fun String.isIntegerToken(): Boolean {
        val start = if (startsWith('-')) 1 else 0
        return length > start && (start until length).all { this[it] in '0'..'9' }
    }

    /** Child instance location for an object member or array index (RFC 6901). */
    private fun childOf(path: String, token: String): String = "$path/${JsonPointer.encodeToken(token)}"

    private fun validateElement(
        instance: JsonElement,
        schemaElement: JsonElement,
        path: String,
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        evaluation: Evaluation,
        depth: Int = 0,
        resourceRoot: JsonElement = evaluation.rootSchema,
        dynamicScope: List<JsonElement> = emptyList(),
    ) {
        // Check depth limit to prevent stack overflow
        if (depth > maxValidationDepth) {
            evaluation.markUndecided()
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
        val effectiveResourceRoot = resourceRootAt(schemaElement, resourceRoot)
        val effectiveScope = scopeAt(schemaElement, dynamicScope)

        // Register the absolute URI for new resource roots so relative $id and missing-$id schemas
        // resolve subsequent relative $refs correctly (e.g. base URI change, nested remote refs).
        if (schemaElement is JsonObject && startsResource(schemaElement)) {
            val rawId = schemaElement.stringKeyword(SchemaKeywords.ID) ?: ""
            // A resource's absolute URI is fixed by where it sits in its document. Once registered, keep
            // it: re-resolving its relative $id against itself (it can be its own resourceRoot) would
            // nest it again, e.g. "nested/n.json" -> ".../nested/nested/n.json".
            if (rawId.isNotEmpty() && !evaluation.references.hasAbsoluteUri(schemaElement)) {
                val parentUri = evaluation.references.getAbsoluteUri(resourceRoot)
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
                evaluation.references.registerAbsoluteUri(schemaElement, absoluteId)
            }
        }

        when (schemaElement) {
            is JsonObject -> {
                // Branch verdicts at this instance are remembered for the collection pass of unevaluated* (#30)
                val collects = (instance is JsonObject && schemaElement.containsKey(UNEVALUATED_PROPERTIES)) ||
                    (instance is JsonArray && schemaElement.containsKey(UNEVALUATED_ITEMS))
                if (collects) evaluation.enterCollector(instance)
                try {
                    validateObjectSchemaElement(instance, schemaElement, path, schemaPath, errors, version, evaluation, depth, effectiveResourceRoot, effectiveScope)
                } finally {
                    if (collects) evaluation.leaveCollector(instance)
                }
            }
            is JsonPrimitive -> {
                // Boolean schema
                if (schemaElement.isString) return
                val boolValue = schemaElement.booleanOrNull
                if (boolValue == false) {
                    // The failing location is the boolean schema itself; it has no "false" member to point at.
                    errors.add(ValidationError(path, "Schema is false, no instance is valid", SCHEMA_FALSE, schemaPath))
                }
                // true schema allows everything
            }
            else -> {} // Arrays and nulls are ignored as schemas
        }
    }

    /** Validates against an object schema: its reference keywords, then its other keywords. */
    private fun validateObjectSchemaElement(
        instance: JsonElement,
        schemaElement: JsonObject,
        path: String,
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        evaluation: Evaluation,
        depth: Int,
        effectiveResourceRoot: JsonElement,
        effectiveScope: List<JsonElement>,
    ) {
        // $ref, $recursiveRef, $dynamicRef are evaluated alongside sibling keywords
        // (Draft 2019-09+ removed the Draft 7 rule that $ref short-circuits siblings)
        resolveReferences(schemaElement, evaluation, effectiveResourceRoot, effectiveScope, version).forEach { hop ->
            val hopPath = at(schemaPath, hop.keyword)
            val target = hop.target
            if (target != null) {
                // The resource keeps the base it was reached under while the hop is evaluated and stamped
                evaluation.references.withBase(hop.resourceRoot, hop.resourceBase) {
                    val before = errors.size
                    validateElement(instance, target, path, hopPath, errors, version, evaluation, depth + 1, hop.resourceRoot, hop.dynamicScope)
                    val candidates = listOf(hop.resourceRoot, effectiveResourceRoot) + hop.dynamicScope.asReversed() + evaluation.rootSchema
                    evaluation.absoluteLocations.stamp(errors, before, hopPath, target, candidates, evaluation.rootSchema)
                }
            } else {
                errors.add(ValidationError(path, "Could not resolve ${hop.description}: ${hop.reference}", hop.keyword, hopPath))
            }
        }

        validateAgainstObjectSchema(instance, schemaElement, path, schemaPath, errors, version, evaluation, depth, effectiveResourceRoot, effectiveScope)
    }

    /**
     * Validates an anyOf/oneOf branch or an if condition into [errors], which must start empty, and remembers a
     * decided verdict for the collection of evaluated properties and items (#30). Returns whether it was undecided.
     */
    private fun validateBranch(
        instance: JsonElement,
        schema: JsonElement,
        path: String,
        schemaPath: String,
        errors: MutableList<ValidationError>,
        version: SchemaVersion,
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ): Boolean {
        val undecided = evaluation.leftUndecided {
            validateElement(instance, schema, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope)
        }
        if (!undecided && evaluation.collects(instance)) {
            evaluation.rememberVerdict(application(instance, schema, evaluation, depth, resourceRoot, dynamicScope), errors.isEmpty())
        }
        return undecided
    }

    /** Whether a branch is valid, for the collection pass: the verdict validation already reached, if it has one (#30). */
    private fun isValidBranch(
        instance: JsonElement,
        schema: JsonElement,
        path: String,
        version: SchemaVersion,
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ): Boolean {
        evaluation.knownVerdict(application(instance, schema, evaluation, depth, resourceRoot, dynamicScope))?.let { return it }
        val errors = mutableListOf<ValidationError>()
        validateBranch(instance, schema, path, "", errors, version, evaluation, depth, resourceRoot, dynamicScope)
        return errors.isEmpty()
    }

    private fun application(
        instance: JsonElement,
        schema: JsonElement,
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) = Application(schema, instance, resourceRoot, evaluation.references.getAbsoluteUri(resourceRoot), dynamicScope, depth)

    /** Where one reference keyword of a schema leads; [target] is null when it cannot be resolved. */
    private class ReferenceHop(
        val keyword: String,
        val reference: String,
        val description: String,
        val target: JsonElement?,
        val resourceRoot: JsonElement,
        val dynamicScope: List<JsonElement>,
        /** The absolute base [resourceRoot] was reached under, or null to keep the one it already has. */
        val resourceBase: String? = null,
    )

    /**
     * Resolves the `$ref`, `$recursiveRef` and `$dynamicRef` of [schemaElement], in that order, with the
     * resource root and dynamic scope each target must be evaluated in. Validation and the collection
     * of evaluated properties/items both use this, so they always agree on where a reference leads.
     */
    private fun resolveReferences(
        schemaElement: JsonObject,
        evaluation: Evaluation,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
        version: SchemaVersion,
    ): List<ReferenceHop> {
        val hops = ArrayList<ReferenceHop>(1)

        /**
         * A hop entering [targetResource]: a new resource joins the dynamic scope for $dynamicRef, whether its URI
         * comes from $id or from where it was retrieved (#64).
         */
        fun hop(keyword: String, reference: String, description: String, target: JsonElement?, targetResource: JsonElement, targetBase: String? = null): ReferenceHop {
            val scope = if (targetResource !== resourceRoot && targetResource is JsonObject) dynamicScope.entering(targetResource) else dynamicScope
            return ReferenceHop(keyword, reference, description, target, targetResource, scope, targetBase)
        }

        schemaElement[REF]?.jsonPrimitive?.contentOrNull?.let { ref ->
            val refUriPart = ref.substringBefore('#')
            // Local refs resolve within the current $id boundary; URI-based refs search the whole document
            val resolved = evaluation.references.resolveRef(ref, if (refUriPart.isEmpty()) resourceRoot else evaluation.rootSchema, resourceRoot)
            hops += hop(REF, ref, "reference", resolved?.target, resolved?.resource ?: resourceRoot, resolved?.base)
        }

        // $recursiveRef (2019-09): a target with $recursiveAnchor: true yields to the outermost such schema in
        // scope, which is itself a resource root, so the target is evaluated in its own resource
        // $recursiveRef exists only in 2019-09; in 2020-12 it is an unknown keyword and is ignored.
        schemaElement[RECURSIVE_REF]?.takeIf { version == SchemaVersion.DRAFT_2019_09 }?.jsonPrimitive?.contentOrNull?.let { recursiveRef ->
            val resolved = evaluation.references.resolveRef(recursiveRef, resourceRoot, resourceRoot)?.target
            val override =
                if (resolved is JsonObject && resolved[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true) {
                    dynamicScope.firstOrNull { it is JsonObject && it[SchemaKeywords.RECURSIVE_ANCHOR]?.jsonPrimitive?.booleanOrNull == true }
                } else {
                    null
                }
            hops += hop(RECURSIVE_REF, recursiveRef, "recursive reference", override ?: resolved, override ?: resourceRoot)
        }

        // $dynamicRef (2020-12): a plain-anchor target with a matching $dynamicAnchor yields to the outermost
        // resource in scope that declares that dynamic anchor, and is evaluated in that resource; otherwise
        // the target is evaluated in the resource its URI leads to, as for $ref
        // $dynamicRef exists only in 2020-12; in 2019-09 it is an unknown keyword and is ignored.
        schemaElement[DYNAMIC_REF]?.takeIf { version == SchemaVersion.DRAFT_2020_12 }?.jsonPrimitive?.contentOrNull?.let { dynamicRef ->
            val uriPart = dynamicRef.substringBefore('#')
            // Decoded as resolveRef decodes it, so every spelling of an anchor gets the same dynamic behavior (#60)
            val fragment = if ('#' in dynamicRef) percentDecode(dynamicRef.substringAfter('#')) else ""
            val initial = evaluation.references.resolveRef(dynamicRef, if (uriPart.isEmpty()) resourceRoot else evaluation.rootSchema, resourceRoot)
            val initialTarget = initial?.target
            val isPlainAnchorFragment = fragment.isNotEmpty() && !fragment.startsWith("/")
            val override =
                if (isPlainAnchorFragment && initialTarget is JsonObject && initialTarget.stringKeyword(DYNAMIC_ANCHOR) == fragment) {
                    dynamicScope.firstNotNullOfOrNull { scopeResource -> evaluation.references.findDynamicAnchorInResource(scopeResource, fragment)?.let { it to scopeResource } }
                } else {
                    null
                }
            val hopResult = if (override != null) {
                hop(DYNAMIC_REF, dynamicRef, "dynamic reference", override.first, override.second)
            } else {
                hop(DYNAMIC_REF, dynamicRef, "dynamic reference", initialTarget, initial?.resource ?: resourceRoot, initial?.base)
            }
            hops += hopResult
        }

        return hops
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
        evaluation: Evaluation,
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
                errors.add(ValidationError(path, "Value must be const: ${constValue.preview()}, but was: ${instance.preview()}", CONST, at(schemaPath, CONST)))
            }
        }

        // Enum validation
        schema[ENUM]?.jsonArray?.let { enumValues ->
            if (enumValues.none { jsonEquals(it, instance) }) {
                errors.add(ValidationError(path, "Value ${instance.preview()} is not one of: ${enumValues.preview(2 * MAX_PREVIEW_LENGTH)}", ENUM, at(schemaPath, ENUM)))
            }
        }

        when (instance) {
            is JsonObject -> validateObject(instance, schema, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope)
            is JsonArray -> validateArray(instance, schema, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope)
            is JsonPrimitive -> validatePrimitive(instance, schema, path, schemaPath, errors, version, evaluation)
        }

        // Combined schemas
        schema[ALL_OF]?.jsonArray?.let { validateAllOf(instance, it, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope) }
        schema[ANY_OF]?.jsonArray?.let { validateAnyOf(instance, it, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope) }
        schema[ONE_OF]?.jsonArray?.let { validateOneOf(instance, it, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope) }
        schema[NOT]?.let { validateNot(instance, it, path, schemaPath, errors, version, evaluation, depth, resourceRoot, dynamicScope) }

        // Conditional schemas (2019-09 and later)
        schema[IF]?.let { ifSchema ->
            val ifErrors = mutableListOf<ValidationError>()
            val conditionUndecided = validateBranch(instance, ifSchema, path, at(schemaPath, IF), ifErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)

            if (ifErrors.isNotEmpty() && conditionUndecided) {
                // Neither branch can be chosen when the condition's failure may not be real
                if (schema.containsKey(THEN) || schema.containsKey(ELSE)) errors.add(undecidedError(path, IF, schemaPath, ifErrors))
            } else if (ifErrors.isEmpty()) {
                // If validation passed, validate against "then"
                schema[THEN]?.let { thenSchema ->
                    validateElement(instance, thenSchema, path, at(schemaPath, THEN), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                }
            } else {
                // If validation failed, validate against "else"
                schema[ELSE]?.let { elseSchema ->
                    validateElement(instance, elseSchema, path, at(schemaPath, ELSE), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                }
            }
        }

        // Unevaluated properties (2019-09 and later)
        if (instance is JsonObject) {
            schema[UNEVALUATED_PROPERTIES]?.let { unevalPropsSchema ->
                val evaluated = collectEvaluatedProperties(instance, schema, path, version, evaluation, depth, resourceRoot, dynamicScope)
                instance.keys.forEach { propName ->
                    if (propName !in evaluated) {
                        val propPath = childOf(path, propName)
                        when {
                            unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == false ->
                                errors.add(ValidationError(path, "Unevaluated property '$propName' is not allowed", UNEVALUATED_PROPERTIES, at(schemaPath, UNEVALUATED_PROPERTIES)))
                            unevalPropsSchema is JsonPrimitive && unevalPropsSchema.booleanOrNull == true -> {}
                            else ->
                                validateElement(instance[propName]!!, unevalPropsSchema, propPath, at(schemaPath, UNEVALUATED_PROPERTIES), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Unevaluated items (2019-09 and later)
        if (instance is JsonArray) {
            schema[UNEVALUATED_ITEMS]?.let { unevalItemsSchema ->
                val evaluated = collectEvaluatedIndices(instance, schema, path, version, evaluation, depth, resourceRoot, dynamicScope)
                instance.indices.forEach { index ->
                    if (index !in evaluated) {
                        val itemPath = childOf(path, index.toString())
                        when {
                            unevalItemsSchema is JsonPrimitive && unevalItemsSchema.booleanOrNull == false ->
                                errors.add(ValidationError(path, "Unevaluated item at index $index is not allowed", UNEVALUATED_ITEMS, at(schemaPath, UNEVALUATED_ITEMS)))
                            unevalItemsSchema is JsonPrimitive && unevalItemsSchema.booleanOrNull == true -> {}
                            else ->
                                validateElement(instance[index], unevalItemsSchema, itemPath, at(schemaPath, UNEVALUATED_ITEMS), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
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
     * Compares two numeric JSON primitives at full precision.
     * Uses Long fast-path when both operands fit in 64 bits with no fractional or exponent parts.
     * Returns null if either primitive is not a valid number.
     */
    private fun compareNumerics(a: JsonPrimitive, b: JsonPrimitive): Int? {
        if (a is JsonNull || a.isString || a.booleanOrNull != null) return null
        if (b is JsonNull || b.isString || b.booleanOrNull != null) return null

        val aLong = a.content.toLongOrNull()
        val bLong = b.content.toLongOrNull()
        if (aLong != null && bLong != null) {
            return aLong.compareTo(bLong)
        }

        val aDec = a.content.toBigDecimalOrNull() ?: return null
        val bDec = b.content.toBigDecimalOrNull() ?: return null
        return aDec.compareTo(bDec)
    }

    /**
     * Gets the JSON type name of an element
     */
    private fun getJsonType(element: JsonElement): String = when (element) {
        is JsonNull -> TYPE_NULL
        is JsonPrimitive if element.isString -> TYPE_STRING
        is JsonPrimitive if element.booleanOrNull != null -> TYPE_BOOLEAN
        is JsonPrimitive -> {
            // A token of digits alone is an integer, however long: no conversion needed (#84)
            if (element.content.isIntegerToken() || element.content.toLongOrNull() != null) {
                TYPE_INTEGER
            } else {
                val dec = element.content.toBigDecimalOrNull()
                if (dec != null) {
                    // A nonpositive scale is an integer already; stripping one can overflow the scale (#86)
                    if (dec.scale() <= 0 || dec.stripTrailingZeros().scale() <= 0) {
                        TYPE_INTEGER
                    } else {
                        TYPE_NUMBER
                    }
                } else {
                    TYPE_UNKNOWN
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

    /**
     * JSON Schema equality: structural, with numbers compared by value (1 equals 1.0). Iterative, so
     * arbitrarily deep instance values cannot overflow the stack (#31); JsonElement.equals recurses.
     */
    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        val pending = ArrayDeque<Pair<JsonElement, JsonElement>>()
        pending.addLast(a to b)
        while (pending.isNotEmpty()) {
            val (x, y) = pending.removeLast()
            if (x === y) continue
            when {
                x is JsonPrimitive && y is JsonPrimitive -> {
                    if (x == y) continue
                    if (compareNumerics(x, y) != 0) return false
                }
                x is JsonArray && y is JsonArray -> {
                    if (x.size != y.size) return false
                    for (index in x.indices.reversed()) pending.addLast(x[index] to y[index])
                }
                x is JsonObject && y is JsonObject -> {
                    if (x.size != y.size) return false
                    for ((key, value) in x) pending.addLast(value to (y[key] ?: return false))
                }
                else -> return false
            }
        }
        return true
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        // Properties validation
        schema[PROPERTIES]?.jsonObject?.let { properties ->
            properties.forEach { (propName, propSchema) ->
                instance[propName]?.let { propValue ->
                    val propPath = childOf(path, propName)
                    validateElement(propValue, propSchema, propPath, at(schemaPath, PROPERTIES, propName), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
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
                if (propName !in definedProps && !matchesAnyPattern(propName, patternProps, evaluation)) {
                    val propPath = childOf(path, propName)
                    when (additionalPropsSchema) {
                        is JsonPrimitive -> {
                            if (additionalPropsSchema.booleanOrNull == false) {
                                errors.add(ValidationError(path, "Additional property '$propName' is not allowed", ADDITIONAL_PROPERTIES, at(schemaPath, ADDITIONAL_PROPERTIES)))
                            }
                        }
                        else -> {
                            validateElement(instance[propName]!!, additionalPropsSchema, propPath, at(schemaPath, ADDITIONAL_PROPERTIES), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Pattern properties
        schema[PATTERN_PROPERTIES]?.jsonObject?.let { patternProps ->
            patternProps.forEach { (pattern, propSchema) ->
                instance.keys.forEach { propName ->
                    when (patternMatches(pattern, propName, evaluation)) {
                        true -> {
                            val propPath = childOf(path, propName)
                            validateElement(instance[propName]!!, propSchema, propPath, at(schemaPath, PATTERN_PROPERTIES, pattern), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                        }
                        false -> Unit
                        null -> errors.add(
                            ValidationError(path, "Property name ${quoted(propName)} could not be checked against pattern: $pattern", PATTERN_PROPERTIES, at(schemaPath, PATTERN_PROPERTIES, pattern)),
                        )
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
                val nameErrors = mutableListOf<ValidationError>()
                validateElement(propNameElement, propNamesSchema, path, at(schemaPath, PROPERTY_NAMES), nameErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                errors.addAll(nameErrors.map { it.copy(message = "Property name ${quoted(propName)}: ${it.message}") })
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
                    validateElement(instance, depSchema, path, at(schemaPath, DEPENDENT_SCHEMAS, propName), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        // Prefix items (2020-12 only; in 2019-09 prefixItems is an unknown keyword)
        val hasPrefixItems = version == SchemaVersion.DRAFT_2020_12 && schema.containsKey(PREFIX_ITEMS)

        if (hasPrefixItems) {
            schema[PREFIX_ITEMS]?.jsonArray?.let { prefixItems ->
                prefixItems.forEachIndexed { index, itemSchema ->
                    if (index < instance.size) {
                        val itemPath = childOf(path, index.toString())
                        validateElement(instance[index], itemSchema, itemPath, at(schemaPath, PREFIX_ITEMS, index.toString()), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                    }
                }

                // In 2020-12, if both prefixItems and items exist, items applies to remaining items
                schema[ITEMS]?.let { itemsSchema ->
                    for (index in prefixItems.size until instance.size) {
                        val itemPath = childOf(path, index.toString())
                        validateElement(instance[index], itemsSchema, itemPath, at(schemaPath, ITEMS), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
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
                            val itemPath = childOf(path, index.toString())
                            validateElement(item, itemsSchema, itemPath, at(schemaPath, ITEMS), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                    is JsonArray -> {
                        // Tuple validation (deprecated in 2020-12 but still supported)
                        itemsSchema.forEachIndexed { index, itemSchema ->
                            if (index < instance.size) {
                                val itemPath = childOf(path, index.toString())
                                validateElement(instance[index], itemSchema, itemPath, at(schemaPath, ITEMS, index.toString()), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
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
                    val itemPath = childOf(path, index.toString())
                    when (additionalItemsSchema) {
                        is JsonPrimitive -> {
                            if (additionalItemsSchema.booleanOrNull == false) {
                                errors.add(ValidationError(itemPath, "Additional items are not allowed", ADDITIONAL_ITEMS, at(schemaPath, ADDITIONAL_ITEMS)))
                            }
                        }
                        else -> {
                            validateElement(instance[index], additionalItemsSchema, itemPath, at(schemaPath, ADDITIONAL_ITEMS), errors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                        }
                    }
                }
            }
        }

        // Contains
        schema[CONTAINS]?.let { containsSchema ->
            // Min/Max contains (2019-09 and later) - handle decimal values
            val minContains = schema[MIN_CONTAINS]?.jsonPrimitive?.let {
                it.doubleOrNull?.toInt() ?: it.intOrNull ?: 1
            } ?: 1

            val maxContains = schema[MAX_CONTAINS]?.jsonPrimitive?.let {
                it.doubleOrNull?.toInt() ?: it.intOrNull
            }

            // Without maxContains, the assertion holds once minContains items match: later items cannot change it, and
            // unevaluatedItems learns which items matched from collectEvaluatedIndices (#89)
            if (maxContains == null && minContains <= 0) return@let
            var matches = 0
            // Items that failed contains in a way that could not be decided: they might match after all
            val undecidedItemErrors = mutableListOf<ValidationError>()
            var undecidedItems = 0
            for ((index, item) in instance.withIndex()) {
                if (maxContains == null && matches >= minContains) break
                val itemErrors = mutableListOf<ValidationError>()
                val undecided = evaluation.leftUndecided {
                    validateElement(item, containsSchema, childOf(path, index.toString()), at(schemaPath, CONTAINS), itemErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
                }
                if (itemErrors.isEmpty()) {
                    matches++
                } else if (undecided) {
                    undecidedItems++
                    undecidedItemErrors.addAll(itemErrors)
                }
            }

            // minContains 0 makes contains always valid
            if (minContains > 0 && matches < minContains) {
                errors.add(
                    ValidationError(
                        path,
                        "Array contains $matches matching items, minimum is $minContains",
                        if (schema.containsKey(MIN_CONTAINS)) MIN_CONTAINS else CONTAINS,
                        at(schemaPath, if (schema.containsKey(MIN_CONTAINS)) MIN_CONTAINS else CONTAINS),
                    ),
                )
            }

            // maxContains counts matches, so an undecided item that might match can push the count past it
            maxContains?.let { max ->
                when {
                    matches > max -> errors.add(
                        ValidationError(path, "Array contains $matches matching items, maximum is $max", MAX_CONTAINS, at(schemaPath, MAX_CONTAINS)),
                    )
                    matches + undecidedItems > max -> errors.add(undecidedError(path, MAX_CONTAINS, schemaPath, undecidedItemErrors))
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
        evaluation: Evaluation,
    ) {
        when {
            instance.isString -> validateString(instance.content, schema, path, schemaPath, errors, version, evaluation)
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
        evaluation: Evaluation,
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
            when (patternMatches(pattern, value, evaluation)) {
                true -> Unit
                false -> errors.add(ValidationError(path, "String ${quoted(value)} does not match pattern: $pattern", PATTERN, at(schemaPath, PATTERN)))
                null -> errors.add(ValidationError(path, "String ${quoted(value)} could not be checked against pattern: $pattern", PATTERN, at(schemaPath, PATTERN)))
            }
        }

        // Format asserts only when formatAssertion is on, in every draft: both drafts make it an annotation
        // by default, and a caller who turns assertion off has opted out (#61).
        if (formatAssertion) {
            schema[FORMAT]?.jsonPrimitive?.contentOrNull?.let { format -> validateFormat(value, format, path, schemaPath, errors) }
        }
    }

    /**
     * Reports a `format` error when [Formats.isValid] rejects [value].
     */
    private fun validateFormat(value: String, format: String, path: String, schemaPath: String, errors: MutableList<ValidationError>) {
        if (!Formats.isValid(format, value)) {
            errors.add(ValidationError(path, "String ${quoted(value)} does not match format: $format", FORMAT, at(schemaPath, FORMAT)))
        }
    }

    private inline fun compareToLimit(
        instanceLong: Long?,
        getInstanceDec: () -> BigDecimal,
        limitPrimitive: JsonPrimitive,
    ): Int? {
        if (limitPrimitive is JsonNull || limitPrimitive.isString || limitPrimitive.booleanOrNull != null) return null
        val limitLong = limitPrimitive.content.toLongOrNull()
        if (instanceLong != null && limitLong != null) {
            return instanceLong.compareTo(limitLong)
        }
        val limitDec = decimalLimit(limitPrimitive) ?: return null
        return getInstanceDec().compareTo(limitDec)
    }

    /** Cache schema limits only; instance values must not accumulate in a shared cache. */
    private fun decimalLimit(primitive: JsonPrimitive): BigDecimal? {
        val literal = primitive.content
        return numericLimitCache.getOrPutIfNotNull(literal) { it.toBigDecimalOrNull() }
    }

    /**
     * Validates a number value
     */
    private fun validateNumber(instance: JsonPrimitive, schema: JsonObject, path: String, schemaPath: String, errors: MutableList<ValidationError>) {
        if (instance is JsonNull || instance.isString || instance.booleanOrNull != null) return
        // Converting a long number costs time quadratic in its digits; skip it when no keyword compares values (#84)
        if (SchemaKeywords.NUMERIC_KEYWORDS.none { it in schema }) return
        val instanceLong = instance.content.toLongOrNull()
        val instanceDec = if (instanceLong == null) instance.content.toBigDecimalOrNull() ?: return else null
        var cachedInstanceDec: BigDecimal? = instanceDec
        fun getInstanceDec(): BigDecimal = cachedInstanceDec ?: BigDecimal.valueOf(instanceLong!!).also { cachedInstanceDec = it }

        val shown = instance.content

        // Minimum
        (schema[MINIMUM] as? JsonPrimitive)?.let { minPrim ->
            val cmp = compareToLimit(instanceLong, ::getInstanceDec, minPrim)
            if (cmp != null && cmp < 0) {
                errors.add(ValidationError(path, "Number $shown is less than minimum ${schema.numberText(MINIMUM)}", MINIMUM, at(schemaPath, MINIMUM)))
            }
        }

        // Maximum
        (schema[MAXIMUM] as? JsonPrimitive)?.let { maxPrim ->
            val cmp = compareToLimit(instanceLong, ::getInstanceDec, maxPrim)
            if (cmp != null && cmp > 0) {
                errors.add(ValidationError(path, "Number $shown is greater than maximum ${schema.numberText(MAXIMUM)}", MAXIMUM, at(schemaPath, MAXIMUM)))
            }
        }

        // Exclusive minimum
        schema[EXCLUSIVE_MINIMUM]?.let { exclusiveMin ->
            when (exclusiveMin) {
                is JsonPrimitive -> {
                    if (exclusiveMin.booleanOrNull == true) {
                        // Draft 4 style with separate minimum
                        (schema[MINIMUM] as? JsonPrimitive)?.let { minPrim ->
                            val cmp = compareToLimit(instanceLong, ::getInstanceDec, minPrim)
                            if (cmp != null && cmp <= 0) {
                                errors.add(ValidationError(path, "Number $shown must be greater than ${schema.numberText(MINIMUM)}", EXCLUSIVE_MINIMUM, at(schemaPath, EXCLUSIVE_MINIMUM)))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        val cmp = compareToLimit(instanceLong, ::getInstanceDec, exclusiveMin)
                        if (cmp != null && cmp <= 0) {
                            errors.add(ValidationError(path, "Number $shown must be greater than ${exclusiveMin.content}", EXCLUSIVE_MINIMUM, at(schemaPath, EXCLUSIVE_MINIMUM)))
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
                        (schema[MAXIMUM] as? JsonPrimitive)?.let { maxPrim ->
                            val cmp = compareToLimit(instanceLong, ::getInstanceDec, maxPrim)
                            if (cmp != null && cmp >= 0) {
                                errors.add(ValidationError(path, "Number $shown must be less than ${schema.numberText(MAXIMUM)}", EXCLUSIVE_MAXIMUM, at(schemaPath, EXCLUSIVE_MAXIMUM)))
                            }
                        }
                    } else {
                        // Draft 2019-09+ style with value
                        val cmp = compareToLimit(instanceLong, ::getInstanceDec, exclusiveMax)
                        if (cmp != null && cmp >= 0) {
                            errors.add(ValidationError(path, "Number $shown must be less than ${exclusiveMax.content}", EXCLUSIVE_MAXIMUM, at(schemaPath, EXCLUSIVE_MAXIMUM)))
                        }
                    }
                }
                else -> {}
            }
        }

        // Multiple of
        (schema[MULTIPLE_OF] as? JsonPrimitive)?.let { multPrim ->
            if (multPrim !is JsonNull && !multPrim.isString && multPrim.booleanOrNull == null) {
                val multLong = multPrim.content.toLongOrNull()
                val isMultiple = if (multLong != null && multLong > 0 && instanceLong != null) {
                    instanceLong % multLong == 0L
                } else {
                    val multDec = decimalLimit(multPrim)
                    multDec == null || multDec.signum() <= 0 || getInstanceDec().isMultipleOf(multDec)
                }
                if (!isMultiple) {
                    errors.add(ValidationError(path, "Number $shown is not a multiple of ${schema.numberText(MULTIPLE_OF)}", MULTIPLE_OF, at(schemaPath, MULTIPLE_OF)))
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        schemas.forEachIndexed { index, schema ->
            val branchErrors = mutableListOf<ValidationError>()
            validateElement(instance, schema, path, at(schemaPath, ALL_OF, index.toString()), branchErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val branchErrors = mutableListOf<ValidationError>()
        schemas.forEachIndexed { index, schema ->
            val tempErrors = mutableListOf<ValidationError>()
            validateBranch(instance, schema, path, at(schemaPath, ANY_OF, index.toString()), tempErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
            // One valid branch decides anyOf: the other branches' errors would be discarded, and their annotations are
            // gathered by collectEvaluated*() when unevaluated* needs them, so they are not evaluated here (#78)
            if (tempErrors.isEmpty()) return
            branchErrors.add(
                ValidationError(path, "Branch $index failed", ANY_OF, at(schemaPath, ANY_OF, index.toString()), causes = tempErrors),
            )
        }

        val depthError = branchErrors.flatMap { it.causes }.firstOrNull { it.keyword == "depth" }
        errors.add(
            depthError ?: ValidationError(
                path,
                "Instance does not match any of the ${schemas.size} anyOf schemas",
                ANY_OF,
                schemaPath = at(schemaPath, ANY_OF),
                causes = branchErrors,
            ),
        )
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val branchErrors = mutableListOf<ValidationError>()
        val undecidedBranchErrors = mutableListOf<ValidationError>()
        val matchingBranches = schemas.mapIndexedNotNull { index, schema ->
            val tempErrors = mutableListOf<ValidationError>()
            val undecided = validateBranch(instance, schema, path, at(schemaPath, ONE_OF, index.toString()), tempErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
            if (tempErrors.isNotEmpty()) {
                val branchError = ValidationError(path, "Branch $index failed", ONE_OF, at(schemaPath, ONE_OF, index.toString()), causes = tempErrors)
                branchErrors.add(branchError)
                if (undecided) undecidedBranchErrors.add(branchError)
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
            // Valid only if no other branch might match as well
            1 -> if (undecidedBranchErrors.isNotEmpty()) errors.add(undecidedError(path, ONE_OF, schemaPath, undecidedBranchErrors))
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ): Set<Int> {
        // Same limit as validateElement, which has already reported the depth error (#29)
        if (depth > maxValidationDepth) return emptySet()
        if (schemaElement is JsonPrimitive) return emptySet()
        if (schemaElement !is JsonObject) return emptySet()

        val effectiveResourceRoot = resourceRootAt(schemaElement, resourceRoot)
        val effectiveScope = scopeAt(schemaElement, dynamicScope)

        val evaluated = mutableSetOf<Int>()

        // prefixItems (2020-12 only): evaluates indices 0 until min(prefixItems.size, instance.size)
        val prefixItemsSchema = if (version == SchemaVersion.DRAFT_2020_12) schemaElement[PREFIX_ITEMS] else null
        prefixItemsSchema?.jsonArray?.let { prefixItems ->
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
                prefixItemsSchema != null -> {
                    val prefixCount = prefixItemsSchema.jsonArray.size
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
                validateElement(item, containsSchema, childOf(path, index.toString()), "", tempErrors, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)
                if (tempErrors.isEmpty()) matchingIndices.add(index)
            }
            // Annotation only produced when contains validates (minContains satisfied)
            if (matchingIndices.size >= minContains) {
                evaluated.addAll(matchingIndices)
            }
        }

        // $ref, $recursiveRef, $dynamicRef: collect from the target, in the target's resource and dynamic
        // scope, exactly as validateElement evaluates it (resolveReferences is shared with it)
        resolveReferences(schemaElement, evaluation, effectiveResourceRoot, effectiveScope, version).forEach { hop ->
            hop.target?.let { target ->
                evaluation.references.withBase(hop.resourceRoot, hop.resourceBase) {
                    evaluated.addAll(collectEvaluatedIndices(instance, target, path, version, evaluation, depth + 1, hop.resourceRoot, hop.dynamicScope))
                }
            }
        }

        // allOf: all branches always contribute
        schemaElement[ALL_OF]?.jsonArray?.forEach { subSchema ->
            evaluated.addAll(collectEvaluatedIndices(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
        }

        // anyOf: only valid branches contribute
        schemaElement[ANY_OF]?.jsonArray?.forEach { subSchema ->
            if (isValidBranch(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)) {
                evaluated.addAll(collectEvaluatedIndices(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // oneOf: only the single valid branch contributes
        schemaElement[ONE_OF]?.jsonArray?.let { schemas ->
            val validBranch =
                schemas.firstOrNull { subSchema ->
                    isValidBranch(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)
                }
            validBranch?.let {
                evaluated.addAll(collectEvaluatedIndices(instance, it, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // if/then/else: when if passes collect from if AND then; when fails collect from else
        schemaElement[IF]?.let { ifSchema ->
            if (isValidBranch(instance, ifSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)) {
                evaluated.addAll(collectEvaluatedIndices(instance, ifSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
                schemaElement[THEN]?.let { thenSchema ->
                    evaluated.addAll(collectEvaluatedIndices(instance, thenSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
                }
            } else {
                schemaElement[ELSE]?.let { elseSchema ->
                    evaluated.addAll(collectEvaluatedIndices(instance, elseSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
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
                        validateElement(instance[index], unevalItemsSchema, childOf(path, index.toString()), "", tempErrors, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ): Set<String> {
        // Same limit as validateElement, which has already reported the depth error (#29)
        if (depth > maxValidationDepth) return emptySet()
        if (schemaElement is JsonPrimitive) {
            // Boolean schemas have no property-evaluating keywords — they evaluate nothing
            return emptySet()
        }
        if (schemaElement !is JsonObject) return emptySet()

        val effectiveResourceRoot = resourceRootAt(schemaElement, resourceRoot)
        val effectiveScope = scopeAt(schemaElement, dynamicScope)

        val evaluated = mutableSetOf<String>()

        // properties: all instance properties that appear in the properties schema
        schemaElement[PROPERTIES]?.jsonObject?.let { props ->
            evaluated.addAll(instance.keys.filter { it in props })
        }

        // patternProperties: all instance properties matching any pattern
        schemaElement[PATTERN_PROPERTIES]?.jsonObject?.let { patternProps ->
            patternProps.keys.forEach { pattern ->
                evaluated.addAll(instance.keys.filter { patternMatches(pattern, it, evaluation) == true })
            }
        }

        // additionalProperties: evaluates properties not in properties/patternProperties (unless false)
        schemaElement[ADDITIONAL_PROPERTIES]?.let { addlPropsSchema ->
            val isFalse = addlPropsSchema is JsonPrimitive && addlPropsSchema.booleanOrNull == false
            if (!isFalse) {
                val definedProps = schemaElement[PROPERTIES]?.jsonObject?.keys ?: emptySet()
                val patternPropPatterns = schemaElement[PATTERN_PROPERTIES]?.jsonObject?.keys ?: emptySet()
                evaluated.addAll(instance.keys.filter { it !in definedProps && !matchesAnyPattern(it, patternPropPatterns, evaluation) })
            }
        }

        // $ref, $recursiveRef, $dynamicRef: collect from the target, in the target's resource and dynamic
        // scope, exactly as validateElement evaluates it (resolveReferences is shared with it)
        resolveReferences(schemaElement, evaluation, effectiveResourceRoot, effectiveScope, version).forEach { hop ->
            hop.target?.let { target ->
                evaluation.references.withBase(hop.resourceRoot, hop.resourceBase) {
                    evaluated.addAll(collectEvaluatedProperties(instance, target, path, version, evaluation, depth + 1, hop.resourceRoot, hop.dynamicScope))
                }
            }
        }

        // allOf: annotations from all branches (they must all be valid for the schema to be valid)
        schemaElement[ALL_OF]?.jsonArray?.forEach { subSchema ->
            evaluated.addAll(collectEvaluatedProperties(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
        }

        // anyOf: annotations only from valid branches
        schemaElement[ANY_OF]?.jsonArray?.forEach { subSchema ->
            if (isValidBranch(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)) {
                evaluated.addAll(collectEvaluatedProperties(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // oneOf: annotations only from the single valid branch
        schemaElement[ONE_OF]?.jsonArray?.let { schemas ->
            val validBranch =
                schemas.firstOrNull { subSchema ->
                    isValidBranch(instance, subSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)
                }
            validBranch?.let {
                evaluated.addAll(collectEvaluatedProperties(instance, it, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
            }
        }

        // if/then/else: when if passes, collect from the if schema AND then; when if fails, collect from else
        schemaElement[IF]?.let { ifSchema ->
            if (isValidBranch(instance, ifSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)) {
                // if passed: its own property annotations also contribute
                evaluated.addAll(collectEvaluatedProperties(instance, ifSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
                schemaElement[THEN]?.let { thenSchema ->
                    evaluated.addAll(collectEvaluatedProperties(instance, thenSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
                }
            } else {
                schemaElement[ELSE]?.let { elseSchema ->
                    evaluated.addAll(collectEvaluatedProperties(instance, elseSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
                }
            }
        }

        // dependentSchemas: collect from applicable sub-schemas
        schemaElement[DEPENDENT_SCHEMAS]?.jsonObject?.let { depSchemas ->
            depSchemas.forEach { (propName, depSchema) ->
                if (propName in instance) {
                    evaluated.addAll(collectEvaluatedProperties(instance, depSchema, path, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope))
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
                        validateElement(instance[propName]!!, unevalPropsSchema, childOf(path, propName), "", tempErrors, version, evaluation, depth + 1, effectiveResourceRoot, effectiveScope)
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
        evaluation: Evaluation,
        depth: Int,
        resourceRoot: JsonElement,
        dynamicScope: List<JsonElement>,
    ) {
        val tempErrors = mutableListOf<ValidationError>()
        val undecided = evaluation.leftUndecided {
            validateElement(instance, schema, path, at(schemaPath, NOT), tempErrors, version, evaluation, depth + 1, resourceRoot, dynamicScope)
        }
        when {
            tempErrors.isEmpty() -> errors.add(ValidationError(path, "Instance matches the not schema but should not", NOT, at(schemaPath, NOT)))
            // A failure that may not be real must not be inverted into success
            undecided -> errors.add(undecidedError(path, NOT, schemaPath, tempErrors))
        }
    }

    /**
     * The error for an applicator that cannot decide because its subschema's result could not be decided
     * (#51): the depth error itself when there is one, as the combinators report it, else an error for
     * [keyword] with the undecided evaluation among its [causes].
     */
    private fun undecidedError(path: String, keyword: String, schemaPath: String, causes: List<ValidationError>): ValidationError {
        val pending = ArrayDeque(causes)
        while (pending.isNotEmpty()) {
            val error = pending.removeFirst()
            if (error.keyword == "depth") return error
            pending.addAll(error.causes)
        }
        return ValidationError(path, "Could not decide $keyword: a subschema could not be fully evaluated", keyword, at(schemaPath, keyword), causes)
    }

    /** A pattern that could not be evaluated counts as not matching, so additional/unevaluated checks fail closed. */
    private fun matchesAnyPattern(propName: String, patterns: Set<String>, evaluation: Evaluation): Boolean = patterns.any { patternMatches(it, propName, evaluation) == true }

    companion object {
        private const val MAX_MESSAGE_VALUE_LENGTH = 40

        /** Compiled patterns and parsed numeric limits one validator keeps; far more than any one schema uses (#40). */
        private const val MAX_CACHED_PATTERNS = 1024
        private const val MAX_CACHED_NUMERIC_LIMITS = 1024
    }
}
