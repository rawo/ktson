// Java sees toOutput as ValidationOutputs.toOutput(result, OutputFormat.Basic.INSTANCE). Renaming
// this facade after release would break Java callers.
@file:JvmName("ValidationOutputs")

package org.ktson

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/*
 * Standard JSON Schema output formats (JSON Schema Core 2020-12 §12, same shape as 2019-09 §10.4).
 *
 * Everything about the standard output lives in this file, so that a later revision of the output
 * section (the next draft reworks it) is a local change. See docs/SPEC_OUTPUT_FORMATS.md.
 *
 * absoluteKeywordLocation comes from ValidationError (filled by AbsoluteLocations during validation)
 * and is omitted when the schema has no absolute URI, which 2020-12 §12.3.3 allows.
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
    /** See [ValidationError.absoluteKeywordLocation]; omitted when the schema has no absolute URI. */
    val absoluteKeywordLocation: String? = null,
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
    /** See [ValidationError.absoluteKeywordLocation]; omitted on the root and when the schema has no absolute URI. */
    val absoluteKeywordLocation: String? = null,
) : ValidationOutput {
    /**
     * The same JSON as the generated serializer (fields in declaration order, nulls omitted), written with an
     * explicit stack: the generated serializer recurses once per level, so a deep result produced on a large-stack
     * thread could overflow the stack of the thread serialising it (#85).
     */
    override fun toJson(): String = buildString {
        // A node whose errors are being written; the next child to write is errors[next]
        class Open(val node: DetailedOutput) {
            var next = 0
        }

        val open = ArrayDeque<Open>()

        fun StringBuilder.field(name: String, value: String) = append(",\"").append(name).append("\":").append(JsonPrimitive(value))

        fun StringBuilder.close(node: DetailedOutput) {
            node.absoluteKeywordLocation?.let { field("absoluteKeywordLocation", it) }
            append('}')
        }

        fun StringBuilder.start(node: DetailedOutput) {
            append("{\"valid\":").append(node.valid)
            field("keywordLocation", node.keywordLocation)
            field("instanceLocation", node.instanceLocation)
            node.error?.let { field("error", it) }
            if (node.errors == null) {
                close(node)
            } else {
                append(",\"errors\":[")
                open.addLast(Open(node))
            }
        }

        start(this@DetailedOutput)
        while (open.isNotEmpty()) {
            val current = open.last()
            val children = current.node.errors!!
            if (current.next < children.size) {
                if (current.next > 0) append(',')
                start(children[current.next++])
            } else {
                open.removeLast()
                append(']')
                close(current.node)
            }
        }
    }
}

/**
 * Selects a standard output format; the type parameter is the output type it produces.
 *
 * There is no `Verbose`: the next output-format draft removes it. Adding a format later adds a
 * subclass here and to [ValidationOutput], which breaks exhaustive `when` expressions over either.
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
                BasicOutput(valid = false, keywordLocation = "", instanceLocation = "", errors = basicUnits(result.validationErrors))
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

/**
 * The error trees [errors] as Basic units, each unit before its causes (depth first, in order). Built iteratively
 * into one list, so a deep tree neither recurses nor copies its descendants once per ancestor (#75).
 */
private fun basicUnits(errors: List<ValidationError>): List<BasicUnit> = buildList {
    val pending = ArrayDeque<ValidationError>()
    errors.asReversed().forEach(pending::addLast)
    while (pending.isNotEmpty()) {
        val error = pending.removeLast()
        add(error.toBasicUnit())
        error.causes.asReversed().forEach(pending::addLast)
    }
}

private fun ValidationError.toBasicUnit(): BasicUnit = BasicUnit(valid = false, keywordLocation = keywordLocationOrRoot(), instanceLocation = instanceLocation, error = message, absoluteKeywordLocation = absoluteKeywordLocation)

private fun ValidationError.toDetailed(): DetailedOutput = DetailedOutput(
    valid = false,
    keywordLocation = keywordLocationOrRoot(),
    instanceLocation = instanceLocation,
    error = message,
    errors = causes.takeIf { it.isNotEmpty() }?.map { it.toDetailed() },
    absoluteKeywordLocation = absoluteKeywordLocation,
)
