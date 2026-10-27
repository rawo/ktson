package org.ktson

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Longest JSON rendering an error message embeds for one value. */
internal const val MAX_PREVIEW_LENGTH = 100

/**
 * This value as compact JSON, exactly as [JsonElement.toString] renders it, but cut at [budget]
 * characters followed by "...". It stops as soon as the budget is reached and walks containers
 * lazily, so a huge or deeply nested value is never fully rendered (or recursed into) just to be
 * truncated afterwards.
 */
internal fun JsonElement.preview(budget: Int = MAX_PREVIEW_LENGTH): String {
    val out = StringBuilder()

    /** Appends [text], or as much as fits followed by "...". Returns false once the budget is spent. */
    fun emit(text: String): Boolean {
        if (out.length + text.length <= budget) {
            out.append(text)
            return true
        }
        out.append(text, 0, budget - out.length).append("...")
        return false
    }

    /** Appends a scalar; a long string is cut before being escaped, so it is never copied whole. */
    fun emitScalar(value: JsonPrimitive): Boolean = when {
        value is JsonNull -> emit("null")
        value.isString && value.content.length > budget - out.length -> emit(JsonPrimitive(value.content.take(budget - out.length)).toString())
        else -> emit(value.toString())
    }

    // One frame per open container: what is left to render in it and how to close it.
    class Frame(val entries: Iterator<Pair<String?, JsonElement>>, val close: String) {
        var first = true
    }

    fun open(value: JsonElement): Frame? = when (value) {
        is JsonObject -> if (emit("{")) Frame(value.entries.asSequence().map { (k, v) -> k to v }.iterator(), "}") else null
        is JsonArray -> if (emit("[")) Frame(value.asSequence().map { null to it }.iterator(), "]") else null
        else -> null
    }

    if (this is JsonPrimitive) {
        emitScalar(this)
        return out.toString()
    }
    val stack = ArrayDeque<Frame>()
    stack.addLast(open(this) ?: return out.toString())
    while (stack.isNotEmpty()) {
        val frame = stack.last()
        if (!frame.entries.hasNext()) {
            stack.removeLast()
            if (!emit(frame.close)) return out.toString()
            continue
        }
        val (key, value) = frame.entries.next()
        if (!frame.first && !emit(",")) return out.toString()
        frame.first = false
        if (key != null && !emit(JsonPrimitive(key).toString() + ":")) return out.toString()
        if (value is JsonPrimitive) {
            if (!emitScalar(value)) return out.toString()
        } else {
            stack.addLast(open(value) ?: return out.toString())
        }
    }
    return out.toString()
}
