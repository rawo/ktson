package org.ktson

/**
 * Represents a validation error found during JSON schema validation
 */
data class ValidationError(
    val path: String,
    val message: String,
    val keyword: String? = null,
    val schemaPath: String? = null,
    val causes: List<ValidationError> = emptyList(),
) {
    override fun toString(): String = buildString { appendError(this@ValidationError, indent = 0) }

    private fun StringBuilder.appendError(error: ValidationError, indent: Int) {
        append("  ".repeat(indent))
        append("Validation error at '${error.path}': ${error.message}")
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
