package org.ktson

/**
 * Represents a validation error found during JSON schema validation
 */
data class ValidationError(
    /** RFC 6901 JSON Pointer to the instance being validated; the root is the empty string. */
    val instanceLocation: String,
    val message: String,
    val keyword: String? = null,
    val schemaPath: String? = null,
    val causes: List<ValidationError> = emptyList(),
    /**
     * Canonical URI of the schema resource holding the failing keyword, plus a JSON Pointer within
     * that resource, e.g. `https://example.com/root#/$defs/p/minimum` (JSON Schema 2020-12 §12.3.3).
     * Unlike [schemaPath], it follows `$ref`, `$dynamicRef` and `$recursiveRef` to where the keyword
     * is written. Null when the schema has no absolute URI (no absolute `$id` and no base URI).
     */
    val absoluteKeywordLocation: String? = null,
) {
    /** The constructor before [absoluteKeywordLocation] was added, kept for Java and compiled callers. */
    constructor(
        instanceLocation: String,
        message: String,
        keyword: String?,
        schemaPath: String?,
        causes: List<ValidationError>,
    ) : this(instanceLocation, message, keyword, schemaPath, causes, null)

    /**
     * Legacy dotted/bracket notation. This projection is ambiguous for numeric object keys,
     * empty keys, and keys containing dots or brackets. Use [instanceLocation] for navigation.
     */
    @Deprecated("Use instanceLocation for an unambiguous JSON Pointer", ReplaceWith("instanceLocation"))
    val path: String
        get() = buildString {
            instanceLocation.split('/').drop(1).forEachIndexed { index, encodedToken ->
                val token = JsonPointer.decodeToken(encodedToken)
                if (token == "0" || (token.firstOrNull() in '1'..'9' && token.all { it in '0'..'9' })) {
                    append("[$token]")
                } else {
                    if (index > 0) append('.')
                    append(token)
                }
            }
        }

    override fun toString(): String = buildString { appendError(this@ValidationError, indent = 0) }

    private fun StringBuilder.appendError(error: ValidationError, indent: Int) {
        append("  ".repeat(indent))
        append("Validation error at '${error.instanceLocation}': ${error.message}")
        error.keyword?.let { append(" (keyword: $it)") }
        error.schemaPath?.let { append(" (schema path: $it)") }
        error.causes.forEach { cause ->
            append("\n")
            appendError(cause, indent + 1)
        }
    }
}

/**
 * Result of a validation operation
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val validationErrors: List<ValidationError>) : ValidationResult()

    val isValid: Boolean get() = this is Valid
    val isInvalid: Boolean get() = this is Invalid

    fun getErrors(): List<ValidationError> = when (this) {
        is Valid -> emptyList()
        is Invalid -> validationErrors
    }
}
