package org.ktson

/**
 * Represents a validation error found during JSON schema validation
 */
data class ValidationError(
    val path: String,
    val message: String,
    val keyword: String? = null,
    val schemaPath: String? = null
) {
    override fun toString(): String {
        return buildString {
            append("Validation error at '$path': $message")
            keyword?.let { append(" (keyword: $it)") }
            schemaPath?.let { append(" (schema path: $it)") }
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

