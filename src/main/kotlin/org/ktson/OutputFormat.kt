package org.ktson

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Standard JSON Schema output formats (JSON Schema Core 2020-12 §12, same shape as 2019-09 §10.4).
 *
 * Everything about the standard output lives in this file, so that a later revision of the output
 * section (the next draft reworks it) is a local change. See docs/SPEC_OUTPUT_FORMATS.md.
 *
 * Known limitation: absoluteKeywordLocation is never produced, so units reached through $ref,
 * $dynamicRef or $recursiveRef are not fully compliant. The official output schema requires it for
 * any unit whose keywordLocation passes through $ref or $dynamicRef, whatever the $id; 2020-12
 * §12.3.3 requires it whenever evaluation passed through a reference in a schema with an absolute $id.
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

/** The `basic` format: a root unit with every failing unit in one flat list. */
@Serializable
data class BasicOutput(
    override val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    /** Every failing unit, each parent before its causes; null when valid. */
    val errors: List<BasicUnit>? = null,
) : ValidationOutput {
    override fun toJson(): String = outputJson.encodeToString(serializer(), this)
}

/** One failing unit in [BasicOutput.errors]; never nests. */
@Serializable
data class BasicUnit(
    val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val error: String,
)

/**
 * The `detailed` format: a node of the failure tree. Leaves have no [errors]. An uncollapsed root
 * has no [error] and empty locations; a root with a single child is that child, [error] included.
 */
@Serializable
data class DetailedOutput(
    override val valid: Boolean,
    val keywordLocation: String,
    val instanceLocation: String,
    val error: String? = null,
    val errors: List<DetailedOutput>? = null,
) : ValidationOutput {
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

    /** The root unit plus every failing unit, flattened depth-first. */
    data object Basic : OutputFormat<BasicOutput>() {
        override fun from(result: ValidationResult): BasicOutput = when (result) {
            is ValidationResult.Valid -> BasicOutput(valid = true, keywordLocation = "", instanceLocation = "")
            is ValidationResult.Invalid ->
                BasicOutput(valid = false, keywordLocation = "", instanceLocation = "", errors = result.validationErrors.flatMap { it.toBasicUnits() })
        }
    }

    /**
     * The failure hierarchy. A root with exactly one child collapses into that child; inner nodes
     * never collapse, since each carries its own keyword location and message.
     */
    data object Detailed : OutputFormat<DetailedOutput>() {
        override fun from(result: ValidationResult): DetailedOutput = when (result) {
            is ValidationResult.Valid -> DetailedOutput(valid = true, keywordLocation = "", instanceLocation = "")
            is ValidationResult.Invalid -> {
                val children = result.validationErrors.map { it.toDetailed() }
                children.singleOrNull() ?: DetailedOutput(valid = false, keywordLocation = "", instanceLocation = "", errors = children)
            }
        }
    }
}

/** Maps this native result to the standard output [format]. */
fun <T : ValidationOutput> ValidationResult.toOutput(format: OutputFormat<T>): T = format.from(this)

/** The validator always sets schemaPath; only a hand-built error can lack one, and maps to the root. */
private fun ValidationError.keywordLocationOrRoot(): String = schemaPath ?: ""

private fun ValidationError.toBasicUnits(): List<BasicUnit> = listOf(BasicUnit(valid = false, keywordLocation = keywordLocationOrRoot(), instanceLocation = instanceLocation, error = message)) +
        causes.flatMap { it.toBasicUnits() }

private fun ValidationError.toDetailed(): DetailedOutput = DetailedOutput(
    valid = false,
    keywordLocation = keywordLocationOrRoot(),
    instanceLocation = instanceLocation,
    error = message,
    errors = causes.takeIf { it.isNotEmpty() }?.map { it.toDetailed() },
)
