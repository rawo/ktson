package org.ktson

import kotlinx.serialization.json.*

/**
 * JSON Pointer implementation (RFC 6901)
 * Provides navigation within JSON documents using a string notation
 */
object JsonPointer {
    private val ARRAY_INDEX = Regex("0|[1-9][0-9]*")

    /**
     * Resolve a JSON Pointer path in a document
     *
     * @param document The JSON document to navigate
     * @param pointer The JSON Pointer string (e.g., "/foo/bar/0")
     * @return The element at the pointer location, or null if not found
     */
    @JvmStatic
    fun resolve(document: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty() || pointer == "#") {
            return document
        }

        val path = if (pointer.startsWith("#/")) {
            pointer.substring(2)
        } else if (pointer.startsWith("/")) {
            pointer.substring(1)
        } else if (pointer.startsWith("#")) {
            pointer.substring(1)
        } else {
            pointer
        }

        val tokens = path.split("/").map { decodeToken(it) }
        var current: JsonElement = document

        for (token in tokens) {
            current = when (current) {
                is JsonObject -> {
                    current[token] ?: return null
                }
                is JsonArray -> {
                    // RFC 6901 §4: "0" or [1-9][0-9]*; toIntOrNull alone also accepts "01" and "+1"
                    if (!ARRAY_INDEX.matches(token)) return null
                    val index = token.toIntOrNull() ?: return null
                    if (index >= current.size) return null
                    current[index]
                }
                else -> return null
            }
        }

        return current
    }

    /**
     * Decode a JSON Pointer token
     * ~ is encoded as ~0
     * / is encoded as ~1
     */
    internal fun decodeToken(token: String): String = token
            .replace("~1", "/")
            .replace("~0", "~")

    /**
     * Encode a string as a JSON Pointer token
     */
    @JvmStatic
    fun encodeToken(token: String): String = token
            .replace("~", "~0")
            .replace("/", "~1")
}

/**
 * Remote schemas loaded through a validator's schemaLoader, shared by every validation that validator runs.
 * Each distinct absolute URI is loaded once and kept for the validator's lifetime. Thread-safe.
 */
internal class SchemaStore(private val schemaLoader: ((String) -> JsonElement?)?) {
    // Nullable value type only so computeIfAbsent's loader may return null; a ConcurrentHashMap never stores null.
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<String, JsonElement?>()

    /** Loaded schema elements → the URI they were loaded from, for relative $refs in documents without an $id. */
    private val loadedSchemaUris =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<JsonElement, String>())

    /** The schema already loaded from [uri], or null. */
    fun cached(uri: String): JsonElement? = schemaCache[uri]

    /** The URI [schema] was loaded from, or null if it was not loaded by this store. */
    fun uriOf(schema: JsonElement): String? = loadedSchemaUris[schema]

    /**
     * The schema at [uri], loaded on first use. Single flight: concurrent validations needing the same URI
     * wait for one load instead of each calling the loader, and the URI is registered before the schema
     * becomes visible in the cache. A null result is not cached, so a later validation may try again.
     */
    fun load(uri: String): JsonElement? {
        val loader = schemaLoader ?: return null
        return schemaCache.computeIfAbsent(uri) { loader(it)?.also { schema -> loadedSchemaUris[schema] = it } }
    }
}

/**
 * Reference resolver for JSON Schema: handles $ref, $recursiveRef and $dynamicRef for one validation.
 *
 * The absolute URI of a schema with a relative `$id` depends on the base it is evaluated under, and the
 * same element may be validated under different bases ([JsonSchema.baseUri], #37). So the registrations
 * live only as long as one validation; remote schemas come from the validator's shared [store].
 * Not thread-safe: an instance serves one validation on one thread.
 */
internal class ReferenceResolver(private val store: SchemaStore) {
    /**
     * Maps schema elements → their resolved absolute URI, for schemas with a relative $id.
     * Populated by [registerAbsoluteUri].
     */
    private val schemaAbsoluteUris = java.util.IdentityHashMap<JsonElement, String>()

    /**
     * Register the resolved absolute URI for an inline schema element.
     * Call this whenever validateElement enters a new resource root (schema with $id).
     * This enables correct relative URI resolution for nested schemas with relative $id values.
     */
    internal fun registerAbsoluteUri(
        schema: JsonElement,
        absoluteUri: String,
    ) {
        if (absoluteUri.isEmpty()) return
        // Never downgrade: a later search that only knows a relative $id must not replace the absolute
        // URI registered for the same schema (that made resolution depend on evaluation order).
        val replacesAbsolute = schemaAbsoluteUris[schema]?.let { isAbsolute(it) } == true
        if (!replacesAbsolute || isAbsolute(absoluteUri)) schemaAbsoluteUris[schema] = absoluteUri
    }

    /** Whether [schema] already has an absolute URI registered (see [registerAbsoluteUri]). */
    internal fun hasAbsoluteUri(schema: JsonElement): Boolean = schemaAbsoluteUris[schema]?.let { isAbsolute(it) } == true

    /**
     * Returns the effective absolute base URI for [schema]:
     *  1. Its registration (handles relative $id), see [registerAbsoluteUri]
     *  2. The URI it was loaded from (handles schemas without $id loaded externally)
     *  3. Its raw $id if that is already absolute
     */
    internal fun getAbsoluteUri(schema: JsonElement): String {
        schemaAbsoluteUris[schema]?.let { return it }
        store.uriOf(schema)?.let { return it }
        val rawId = (schema as? JsonObject)?.stringKeyword(SchemaKeywords.ID) ?: ""
        return if (rawId.isNotEmpty() && UriResolver.isAbsoluteUri(rawId)) rawId else ""
    }

    /**
     * Returns the cached externally-loaded schema root for [uriPart] resolved
     * against [baseSchema], or null if it was not loaded externally (e.g. found
     * in-document). Used by [validateElement] to determine the correct resourceRoot
     * when following URI-based $refs.
     */
    internal fun getLoadedSchemaRoot(
        baseSchema: JsonElement,
        uriPart: String,
    ): JsonElement? {
        val baseId = getAbsoluteUri(baseSchema)
        val absoluteUri =
            if (baseId.isNotEmpty()) {
                try {
                    UriResolver.resolveUri(baseId, uriPart)
                } catch (_: Exception) {
                    uriPart
                }
            } else {
                uriPart
            }
        return store.cached(absoluteUri)
    }

    /**
     * Resolve a $ref reference.
     *
     * Supports:
     *  - Local JSON pointer:  #/path/to/thing
     *  - Local anchor:        #anchorName
     *  - URI + pointer:       http://example.com/schema#/path
     *  - URI + anchor:        http://example.com/schema#anchorName
     *  - Relative URI:        other.json  (matched against $id values in the document)
     *
     * Percent-encoded characters in the fragment are decoded per RFC 3986.
     *
     * @param resourceRoot the schema resource that owns the ref (used for relative URI resolution)
     */
    internal fun resolveRef(
        ref: String,
        rootSchema: JsonElement,
        currentSchema: JsonElement = rootSchema,
        resourceRoot: JsonElement = rootSchema,
    ): JsonElement? {
        val hashIndex = ref.indexOf('#')
        val uriPart = if (hashIndex >= 0) ref.substring(0, hashIndex) else ref
        val fragment = percentDecode(if (hashIndex >= 0) ref.substring(hashIndex + 1) else "")

        return if (uriPart.isEmpty()) {
            resolveLocalRef(rootSchema, fragment)
        } else {
            // A relative reference resolves against the base of the resource that contains it (RFC 3986 §5.1),
            // e.g. $ref "target" inside a resource with $id "b/" means ".../b/target", so try that first; the
            // document root's base is only a fallback for resources without an absolute base.
            val baseId = getAbsoluteUri(resourceRoot)
            val targetSchema =
                (if (isAbsolute(baseId)) runCatching { UriResolver.resolveUri(baseId, uriPart) }.getOrNull()?.let { findSchemaByUri(rootSchema, it) } else null)
                    ?: findSchemaByUri(rootSchema, uriPart)
                    ?: resolveExternalRef(resourceRoot, uriPart)
                    ?: return null
            when {
                fragment.isEmpty() -> targetSchema
                fragment.startsWith("/") -> JsonPointer.resolve(targetSchema, fragment)
                else -> findAnchorInResource(targetSchema, fragment)
            }
        }
    }

    /**
     * Attempt to load an external schema via the schemaLoader.
     * Resolves [uriPart] against [baseSchema]'s effective absolute URI, then:
     *  1. Returns cached result if already loaded
     *  2. Tries to find the URI as an embedded $id within [baseSchema] itself
     *  3. Loads it through the validator's [SchemaStore]
     * Returns the loaded schema root on success; the caller applies any fragment.
     */
    private fun resolveExternalRef(
        baseSchema: JsonElement,
        uriPart: String,
    ): JsonElement? {
        val baseId = getAbsoluteUri(baseSchema)
        val absoluteUri =
            if (baseId.isNotEmpty()) {
                try {
                    UriResolver.resolveUri(baseId, uriPart)
                } catch (_: Exception) {
                    uriPart
                }
            } else {
                uriPart
            }

        store.cached(absoluteUri)?.let { return it }

        // Check if the target URI is embedded within baseSchema (e.g. a nested $id sub-schema)
        if (absoluteUri != uriPart || baseId.isNotEmpty()) {
            findSchemaByUri(baseSchema, absoluteUri)?.let { return it }
        }

        return store.load(absoluteUri)
    }

    /** Resolve a reference that has no URI part — only a fragment. */
    private fun resolveLocalRef(
        rootSchema: JsonElement,
        fragment: String,
    ): JsonElement? = when {
            fragment.isEmpty() -> rootSchema
            fragment.startsWith("/") -> JsonPointer.resolve(rootSchema, fragment)
            // Plain anchor name: search the root resource, not crossing $id boundaries
            else -> findAnchorInResource(rootSchema, fragment)
        }

    /**
     * Search for a schema with a matching $anchor or $dynamicAnchor within a single schema resource.
     * Stops at nested $id boundaries — those are separate resources and are not searched.
     */
    private fun findAnchorInResource(
        schema: JsonElement,
        anchorName: String,
    ): JsonElement? {
        // Iterative depth-first search in document order, so deeply nested schemas cannot overflow the stack (#31)
        val pending = ArrayDeque<SearchNode>()
        (schema as? JsonObject)?.let { pending.addLast(SearchNode(it, isRoot = true, isSchema = true, baseUri = "")) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.isSchema) {
                if (!node.isRoot && node.element.stringKeyword(SchemaKeywords.ID) != null) continue
                if (node.element.stringKeyword(SchemaKeywords.ANCHOR) == anchorName || node.element.stringKeyword(SchemaKeywords.DYNAMIC_ANCHOR) == anchorName) return node.element
            }
            node.element.pushSubschemas(node.isSchema, pending, node.baseUri)
        }
        return null
    }

    /**
     * Search for a schema with a matching $dynamicAnchor within a single schema resource.
     * Only matches $dynamicAnchor (not $anchor) — used for $dynamicRef dynamic scope walking.
     * Stops at nested $id boundaries — those are separate resources and are not searched.
     */
    internal fun findDynamicAnchorInResource(
        schema: JsonElement,
        anchorName: String,
        isRoot: Boolean = true,
    ): JsonElement? = findDynamicAnchor(schema, anchorName, isRoot, isSchema = true)

    private fun findDynamicAnchor(
        schema: JsonElement,
        anchorName: String,
        isRoot: Boolean,
        isSchema: Boolean,
    ): JsonElement? {
        // Iterative depth-first search in document order, so deeply nested schemas cannot overflow the stack (#31)
        val pending = ArrayDeque<SearchNode>()
        (schema as? JsonObject)?.let { pending.addLast(SearchNode(it, isRoot, isSchema, baseUri = "")) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.isSchema) {
                if (!node.isRoot && node.element.stringKeyword(SchemaKeywords.ID) != null) continue
                if (node.element.stringKeyword(SchemaKeywords.DYNAMIC_ANCHOR) == anchorName) return node.element
            }
            node.element.pushSubschemas(node.isSchema, pending, node.baseUri)
        }
        return null
    }

    /**
     * Find a schema within the document whose $id matches the target URI.
     * Relative $id values are resolved against their parent base URI so that
     * e.g. $id "nested.json" under a root with $id "http://example.com/root"
     * is found when the target is "http://example.com/nested.json".
     */
    private fun findSchemaByUri(
        rootSchema: JsonElement,
        targetUri: String,
    ): JsonElement? {
        // Start from the root's registered absolute URI (it may come from JsonSchema.baseUri), not its raw $id.
        val rootId = getAbsoluteUri(rootSchema).ifEmpty { (rootSchema as? JsonObject)?.stringKeyword(SchemaKeywords.ID) ?: "" }
        val resolvedTarget =
            if (rootId.isNotEmpty()) {
                try {
                    UriResolver.resolveUri(rootId, targetUri)
                } catch (_: Exception) {
                    targetUri
                }
            } else {
                targetUri
            }
        return searchById(rootSchema, resolvedTarget, targetUri, rootId)
    }

    private fun searchById(
        element: JsonElement,
        resolvedTarget: String,
        originalTarget: String,
        baseUri: String,
    ): JsonElement? {
        // Iterative depth-first search in document order, so deeply nested schemas cannot overflow the stack (#31)
        val pending = ArrayDeque<SearchNode>()
        (element as? JsonObject)?.let { pending.addLast(SearchNode(it, isRoot = true, isSchema = true, baseUri = baseUri)) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            // Only a schema has an $id; a map of named schemas (under properties, $defs, ...) does not.
            val id = if (node.isSchema) node.element.stringKeyword(SchemaKeywords.ID) else null
            val currentUri =
                when {
                    id == null -> node.baseUri
                    // Already known: use it. A search that starts at a resource gets that resource's own
                    // URI as baseUri, so resolving its relative $id again would nest it once per search.
                    hasAbsoluteUri(node.element) -> getAbsoluteUri(node.element)
                    else -> {
                        val resolved =
                            try {
                                UriResolver.resolveUri(node.baseUri, id)
                            } catch (_: Exception) {
                                id
                            }
                        // Register the resolved absolute URI so relative $refs within this sub-schema work
                        registerAbsoluteUri(node.element, resolved)
                        resolved
                    }
                }
            // Compare resolved URIs. A raw $id match is accepted only when there is no absolute URI to compare:
            // two resources may share a relative $id under different bases (a/target and b/target).
            if (id != null && (currentUri == resolvedTarget || (id == originalTarget && !isAbsolute(currentUri)))) return node.element
            // A root without $id is still identified by the base it was registered with, such as
            // JsonSchema.baseUri, so an absolute reference to that URI finds it (#53).
            if (id == null && node.isRoot && isAbsolute(currentUri) && currentUri == resolvedTarget) return node.element
            node.element.pushSubschemas(node.isSchema, pending, currentUri)
        }
        return null
    }

    /**
     * Returns the target schema resource for a URI-based [uriPart] (without applying any fragment).
     * Searches [rootSchema] first, then tries external loading via [resourceRoot].
     * Used by [validateElement] to determine the correct [resourceRoot] when following URI-based $refs.
     */
    internal fun resolveUriToResource(
        uriPart: String,
        rootSchema: JsonElement,
        resourceRoot: JsonElement,
    ): JsonElement? = findSchemaByUri(rootSchema, uriPart) ?: resolveExternalRef(resourceRoot, uriPart)
}

/**
 * The string value of [keyword] (`$id`, `$anchor`, `$dynamicAnchor`), or null. Searches walk every
 * object, including `properties` maps, where a key like "$id" names a property whose value is a
 * schema: such a value is not an identifier and must neither throw nor count as one.
 */
internal fun JsonObject.stringKeyword(keyword: String): String? = (this[keyword] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Decode percent-encoded characters in a URI fragment per RFC 3986.
 * JSON Pointer tokens inside URI fragments must be percent-decoded before
 * applying the ~0/~1 escape rules (RFC 6901 §6). An anchor name is compared decoded too, so
 * "#%6Eode" names anchor "node" (#60).
 */
internal fun percentDecode(s: String): String {
    if (!s.contains('%')) return s
    // %XX escapes are UTF-8 bytes (RFC 3986 §2.5): collect them and decode together, so that
    // "%C3%A9" becomes "é" rather than two Latin-1 characters.
    val bytes = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val isEscape = s[i] == '%' && i + 2 < s.length && s[i + 1].isHexDigit() && s[i + 2].isHexDigit()
        val code = if (isEscape) s.substring(i + 1, i + 3).toInt(16) else null
        if (code != null) {
            bytes.write(code)
            i += 3
        } else {
            val codePointLength = Character.charCount(s.codePointAt(i))
            bytes.write(s.substring(i, i + codePointLength).toByteArray(Charsets.UTF_8))
            i += codePointLength
        }
    }
    return bytes.toString(Charsets.UTF_8)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun isAbsolute(uri: String): Boolean = runCatching { UriResolver.isAbsoluteUri(uri) }.getOrDefault(false)

/**
 * The values of this object that can contain schemas, each paired with whether it is itself a schema
 * (false for a map of named schemas such as the value of `properties`). For a schema, data keywords
 * (`const`, `enum`, `default`, `examples`) are skipped: a "$id" or "$anchor" inside them is data. For a map
 * of named schemas every value is a schema, whatever its name ("default" can be a property).
 */
internal fun JsonObject.subschemaValues(isSchema: Boolean): Sequence<Pair<JsonElement, Boolean>> = if (isSchema) {
        entries.asSequence()
            .filter { it.key !in SchemaKeywords.DATA_KEYWORDS }
            .map { (keyword, value) -> value to (keyword !in SchemaKeywords.NAMED_SUBSCHEMA_KEYWORDS) }
    } else {
        values.asSequence().map { it to true }
    }

/** One object waiting in an iterative schema search, with its position and the base URI in effect there. */
private class SearchNode(val element: JsonObject, val isRoot: Boolean, val isSchema: Boolean, val baseUri: String)

/**
 * Pushes the objects below this one that can hold schemas (see [subschemaValues]) onto [pending], in
 * reverse, so popping visits them in document order: the first match is the same as a recursive search's.
 */
private fun JsonObject.pushSubschemas(isSchema: Boolean, pending: ArrayDeque<SearchNode>, baseUri: String) {
    subschemaValues(isSchema).toList().asReversed().forEach { (value, childIsSchema) ->
        when (value) {
            is JsonObject -> pending.addLast(SearchNode(value, isRoot = false, isSchema = childIsSchema, baseUri = baseUri))
            is JsonArray -> value.asReversed().forEach { item -> if (item is JsonObject) pending.addLast(SearchNode(item, isRoot = false, isSchema = childIsSchema, baseUri = baseUri)) }
            else -> Unit
        }
    }
}
