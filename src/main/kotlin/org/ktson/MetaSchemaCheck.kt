package org.ktson

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The machinery behind [JsonValidator.validateSchema]: an iterative nesting check against [maxValidationDepth], then
 * the schema checked against its draft's meta-schema (embedded in [MetaSchemas]) by a separate validator, on a worker
 * thread with a large stack that inherits no thread-locals and is joined uninterruptibly. Owned by one validator and
 * created on its first validateSchema call, so it, and the meta-schema validator it creates, die with that validator.
 */
internal class MetaSchemaCheck(private val maxValidationDepth: Int) {
    fun validateSchema(schema: JsonSchema): ValidationResult {
        // Meta-schema validation recurses several levels per schema level, so very deep schemas are refused
        // up front (measured iteratively), and the check runs on a thread with a large stack: it must not
        // depend on, or overflow, the caller's stack.
        val nesting = nestingDepth(schema.schema)
        if (nesting > maxValidationDepth) {
            return ValidationResult.Invalid(
                listOf(ValidationError("", "Schema is nested $nesting levels deep, more than maxValidationDepth ($maxValidationDepth)", "depth", "")),
            )
        }
        var result: ValidationResult? = null
        var failure: Throwable? = null
        val worker = Thread(
            null,
            {
                result = try {
                    metaSchemaValidator.validate(schema.schema, metaSchemaOf(schema.effectiveVersion))
                } catch (_: StackOverflowError) {
                    ValidationResult.Invalid(listOf(ValidationError("", "Schema is nested too deeply to check against its meta-schema", "depth", "")))
                } catch (t: Throwable) {
                    failure = t
                    null
                }
            },
            "ktson-validateSchema",
            META_SCHEMA_STACK_SIZE,
            // The worker is an implementation detail: it must not see the caller's request context (#56).
            false,
        )
        worker.start()
        joinUninterruptibly(worker)
        failure?.let { throw it }
        return result!!
    }

    /**
     * Waits for [worker] to finish even if the caller is interrupted, then restores the interrupt status.
     * Like validate(), validateSchema runs to completion; abandoning the wait would leave the worker
     * running with nobody owning it (#56). The worker always finishes: the check is bounded by the schema.
     */
    private fun joinUninterruptibly(worker: Thread) {
        var interrupted = false
        while (true) {
            try {
                worker.join()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Checks schemas against the official meta-schemas (embedded in [MetaSchemas]). A separate validator so
     * that its caches stay apart from instance validation; formats are asserted so that, for example, an
     * invalid `pattern` regex is reported. Created on first use.
     */
    private val metaSchemaValidator by lazy {
        JsonValidator(
            enableMetaSchemaValidation = false,
            formatAssertion = true,
            // A schema level costs several levels of meta-schema evaluation (allOf, $ref, $dynamicRef).
            maxValidationDepth = metaSchemaDepth(maxValidationDepth),
            schemaLoader = MetaSchemas::load,
        )
    }

    private val metaSchemas = ConcurrentHashMap<SchemaVersion, JsonSchema>()

    private fun metaSchemaOf(version: SchemaVersion): JsonSchema = metaSchemas.computeIfAbsent(version) { JsonSchema(MetaSchemas.load(MetaSchemas.uriOf(it))!!, it) }

    /** How deeply objects and arrays nest in [element], measured iteratively. */
    private fun nestingDepth(element: JsonElement): Int {
        var deepest = 0
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.addLast(element to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            deepest = maxOf(deepest, depth)
            when (node) {
                is JsonObject -> node.values.forEach { pending.addLast(it to depth + 1) }
                is JsonArray -> node.forEach { pending.addLast(it to depth + 1) }
                else -> Unit
            }
        }
        return deepest
    }

    companion object {
        /** Stack for the meta-schema check: about 13 MB is needed for 1,000 schema levels, so this leaves margin. */
        private const val META_SCHEMA_STACK_SIZE = 64L * 1024 * 1024

        /** Levels of meta-schema evaluation one schema level can take (measured: 5-8). */
        private const val META_SCHEMA_DEPTH_PER_LEVEL = 16

        /**
         * The meta-schema validator's depth budget for schemas nested up to [maxValidationDepth] levels: one level's
         * worth per schema level plus one for the root, which needs room even at 0. Computed in Long and capped,
         * so a large limit cannot wrap negative (#73).
         */
        private fun metaSchemaDepth(maxValidationDepth: Int): Int = ((maxValidationDepth.toLong() + 1) * META_SCHEMA_DEPTH_PER_LEVEL).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
