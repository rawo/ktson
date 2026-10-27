package org.ktson

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Fills [ValidationError.absoluteKeywordLocation]: the canonical URI of the schema resource holding
 * the failing keyword plus a JSON Pointer within that resource (JSON Schema 2020-12 §12.3.3).
 *
 * The evaluation path ([ValidationError.schemaPath]) and the absolute location agree until the first
 * reference hop. So after each `$ref` / `$dynamicRef` / `$recursiveRef` hop the validator calls
 * [stamp], which locates the hop's target in its resource and stamps the hop's new errors with that
 * location plus the rest of their evaluation path. Errors a deeper hop already stamped are kept, so
 * the innermost hop wins. [finish] then stamps what never crossed a reference against the root.
 *
 * It only runs when a hop produced errors, so valid instances pay nothing. Where a location cannot be
 * determined, the field stays null rather than wrong.
 */
internal class AbsoluteLocations(private val referenceResolver: ReferenceResolver) {
    /**
     * Stamps the errors from index [from] on that lack an absolute location: the location of [target] (found in the
     * first of [candidates] that contains it) plus the rest of each error's evaluation path after [hopPath].
     *
     * Inside a resource without an absolute URI only the root's location is still useful, so it is
     * recorded as a bare `#pointer` and completed by [finish] once the root URI (which may come from
     * [JsonSchema.baseUri]) is known.
     */
    fun stamp(
        errors: MutableList<ValidationError>,
        from: Int,
        hopPath: String,
        target: JsonElement,
        candidates: List<JsonElement>,
        rootSchema: JsonElement,
    ) {
        if (errors.size == from) return
        val found = locate(target, candidates) ?: return
        val location = when {
            found.uri != null -> "${found.uri}#${encodeFragment(found.pointer)}"
            found.resource === rootSchema -> "#${encodeFragment(found.pointer)}"
            else -> return
        }
        for (i in from until errors.size) errors[i] = errors[i].stamped(hopPath, location)
    }

    /** Completes every location in [errors] against the root schema's absolute URI, or clears it when there is none. */
    fun finish(errors: List<ValidationError>, schema: JsonSchema): List<ValidationError> {
        val rootUri = schema.absoluteBaseUri()
        return errors.map { it.finished(rootUri) }
    }

    private fun ValidationError.stamped(hopPath: String, location: String): ValidationError {
        val stampedCauses = causes.map { it.stamped(hopPath, location) }
        val own = absoluteKeywordLocation ?: schemaPath
            ?.let { path -> path.removePrefix(hopPath).takeIf { path.startsWith(hopPath) && (it.isEmpty() || it.startsWith("/")) } }
            ?.takeUnless { crossesReference(it) }
            ?.let { location + encodeFragment(it) }
        return copy(causes = stampedCauses, absoluteKeywordLocation = own)
    }

    private fun ValidationError.finished(rootUri: String?): ValidationError {
        val own = when {
            absoluteKeywordLocation == null -> schemaPath?.takeUnless { crossesReference(it) }?.let { path -> rootUri?.let { "$it#${encodeFragment(path)}" } }
            absoluteKeywordLocation.startsWith("#") -> rootUri?.let { it + absoluteKeywordLocation }
            else -> absoluteKeywordLocation
        }
        return copy(causes = causes.map { it.finished(rootUri) }, absoluteKeywordLocation = own)
    }

    /**
     * Whether [path] passes *through* a reference keyword (one that is not its last token). The
     * location of such an error depends on a hop that could not be located, so it must stay unknown.
     */
    private fun crossesReference(path: String): Boolean {
        // The path starts at a schema, so its first token is a keyword. Read it in order: after a keyword
        // whose value maps names to schemas comes a name, after an array keyword an index, then a keyword
        // again. Only a reference keyword in keyword position is a hop; "$ref" can also be a property name.
        val tokens = path.split('/').drop(1)
        var index = 0
        while (index < tokens.size) {
            val keyword = tokens[index]
            if (keyword in REFERENCE_KEYWORDS && index < tokens.lastIndex) return true
            val next = tokens.getOrNull(index + 1)
            index += when {
                keyword in NAMED_SUBSCHEMA_KEYWORDS -> 2
                keyword in ARRAY_SUBSCHEMA_KEYWORDS && next != null && next.all { it.isDigit() } -> 2
                else -> 1
            }
        }
        return false
    }

    private class Found(val uri: String?, val resource: JsonElement, val pointer: String)

    /** Finds [target] by identity in the first candidate containing it, inside its innermost schema resource. */
    private fun locate(target: JsonElement, candidates: List<JsonElement>): Found? {
        val searched = Collections.newSetFromMap(IdentityHashMap<JsonElement, Boolean>())
        for (candidate in candidates) {
            if (searched.add(candidate)) findIn(candidate, target)?.let { return it }
        }
        return null
    }

    /** One step of the search: [node] sits at [pointer] inside [resource], whose URI is [uri]. */
    private class Frame(val node: JsonElement, val uri: String?, val resource: JsonElement, val pointer: String)

    /** Iterative depth-first search, so deeply nested schemas cannot overflow the stack. */
    private fun findIn(start: JsonElement, target: JsonElement): Found? {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(start, resourceUri(start), start, ""))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (frame.node === target) return Found(frame.uri, frame.resource, frame.pointer)
            when (val node = frame.node) {
                is JsonObject -> node.forEach { (key, child) -> stack.addLast(childFrame(frame, child, "${frame.pointer}/${JsonPointer.encodeToken(key)}")) }
                is JsonArray -> node.forEachIndexed { index, child -> stack.addLast(childFrame(frame, child, "${frame.pointer}/$index")) }
                else -> Unit
            }
        }
        return null
    }

    /** The frame for [child] at [pointer]; a child with its own `$id` starts a new resource at pointer "". */
    private fun childFrame(parent: Frame, child: JsonElement, pointer: String): Frame {
        // A `$id` key holding an object is a property named "$id" (e.g. under `properties`), not an identifier.
        val childId = ((child as? JsonObject)?.get(SchemaKeywords.ID) as? JsonPrimitive)?.contentOrNull ?: return Frame(child, parent.uri, parent.resource, pointer)
        val uri = resourceUri(child) ?: parent.uri?.let { runCatching { UriResolver.resolveUri(it, childId) }.getOrNull() }
        return Frame(child, uri?.substringBefore('#')?.takeIf { isAbsolute(it) }, child, "")
    }

    private fun resourceUri(resource: JsonElement): String? = referenceResolver.getAbsoluteUri(resource).substringBefore('#').takeIf { isAbsolute(it) }

    /** An unparsable URI counts as not absolute: filling this optional field must never fail a validation. */
    private fun isAbsolute(uri: String): Boolean = uri.isNotEmpty() && runCatching { UriResolver.isAbsoluteUri(uri) }.getOrDefault(false)

    /**
     * A JSON Pointer as a URI fragment (RFC 6901 §6): characters the fragment grammar does not allow,
     * `%` included, become UTF-8 percent-escapes, e.g. "c%d" -> "c%25d", " " -> "%20", "é" -> "%C3%A9".
     */
    private fun encodeFragment(pointer: String): String {
        if (pointer.all { it in FRAGMENT_SAFE }) return pointer
        return buildString {
            for (byte in pointer.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char in FRAGMENT_SAFE) append(char) else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    private companion object {
        /** RFC 3986 fragment characters: unreserved, sub-delims, ":", "@", "/" and "?". */
        val FRAGMENT_SAFE: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-._~!$&'()*+,;=:@/?".toList()).toSet()

        val REFERENCE_KEYWORDS = setOf(SchemaKeywords.REF, SchemaKeywords.DYNAMIC_REF, SchemaKeywords.RECURSIVE_REF)

        /** Keywords whose value maps names to subschemas: the token after them is a name, not a keyword. */
        val NAMED_SUBSCHEMA_KEYWORDS = setOf(
            SchemaKeywords.PROPERTIES,
            SchemaKeywords.PATTERN_PROPERTIES,
            SchemaKeywords.DEFS,
            SchemaKeywords.DEFINITIONS,
            SchemaKeywords.DEPENDENT_SCHEMAS,
        )

        /** Keywords whose value may be an array of subschemas: a numeric token after them is an index. */
        val ARRAY_SUBSCHEMA_KEYWORDS = setOf(
            SchemaKeywords.ALL_OF,
            SchemaKeywords.ANY_OF,
            SchemaKeywords.ONE_OF,
            SchemaKeywords.PREFIX_ITEMS,
            SchemaKeywords.ITEMS,
        )
    }
}
