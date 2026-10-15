package org.ktson

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Standard JSON Schema output formats (JSON Schema Core 2020-12 §12, same shape as 2019-09 §10.4).
 *
 * Everything about the standard output lives in this file, so that a later revision of the output
 * section (the next draft reworks it) is a local change. See docs/SPEC_OUTPUT_FORMATS.md.
 *
 * Known limitation: absoluteKeywordLocation is never produced. 2020-12 §12.3.3 allows omitting it
 * only when evaluation did not pass through a reference or the schema has no absolute $id, so
 * output for errors reached through $ref in such schemas is not fully compliant.
 */

/**
 * Serialises the concrete output types independently of any caller's Json. With encodeDefaults off,
 * a property equal to its default (null) is omitted. explicitNulls is not used: it is experimental.
 */
private val outputJson = Json { encodeDefaults = false }

/**
 * Standard validation output in one of the formats selected by [OutputFormat].
 *
 * Serialise with [toJson]. Do not encode through this interface polymorphically: that would add a
 * type discriminator, which is not part of the standard output.
 */
sealed interface ValidationOutput {
    val valid: Boolean

    /** Standard JSON for this format. */
    fun toJson(): String
}

/** The `flag` format: only whether validation passed. */
@Serializable
data class FlagOutput(override val valid: Boolean) : ValidationOutput {
    override fun toJson(): String = outputJson.encodeToString(serializer(), this)
}

/**
 * Selects a standard output format; the type parameter is the output type it produces.
 *
 * `Verbose` is not supported yet. Adding it later adds a subclass here and to [ValidationOutput],
 * which breaks exhaustive `when` expressions over either.
 */
sealed class OutputFormat<T : ValidationOutput> {
    internal abstract fun from(result: ValidationResult): T

    /** `{"valid": true|false}` and nothing else. */
    data object Flag : OutputFormat<FlagOutput>() {
        override fun from(result: ValidationResult): FlagOutput = FlagOutput(result.isValid)
    }
}

/** Maps this native result to the standard output [format]. */
fun <T : ValidationOutput> ValidationResult.toOutput(format: OutputFormat<T>): T = format.from(this)
