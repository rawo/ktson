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
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<String, JsonElement>()

    /** Loads in progress, so concurrent callers needing the same URI wait for one load instead of each calling the loader. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Load>()

    /** One load of one URI, run by [owner]; others wait on [done] and then read [result]. */
    private class Load(val owner: Thread) {
        val done = java.util.concurrent.CountDownLatch(1)

        @Volatile var result: JsonElement? = null
    }

    /** The schema already loaded from [uri], or null. */
    fun cached(uri: String): JsonElement? = schemaCache[uri]

    /**
     * The schema at [uri], loaded on first use. Single flight: concurrent validations needing the same URI
     * wait for one load instead of each calling the loader. The store keeps no URI per schema element: a
     * loader may return one parsed object for several URIs, so the base a document is evaluated under
     * travels with each reference instead (ResolvedReference, #54). A null result (or a loader that throws) is not cached, so a later
     * validation may try again.
     *
     * The loader runs outside any map operation, so it may itself validate through the same validator and
     * load other URIs (#55). If it needs the URI it is loading, that URI is unresolvable for the nested
     * validation: waiting for itself would never end.
     */
    fun load(uri: String): JsonElement? {
        schemaCache[uri]?.let { return it }
        val loader = schemaLoader ?: return null
        val load = Load(Thread.currentThread())
        val running = inFlight.putIfAbsent(uri, load)
        if (running != null) {
            if (running.owner === Thread.currentThread()) return null
            awaitUninterruptibly(running.done)
            return running.result
        }
        try {
            // A load that finished between the cache miss above and claiming the URI has already cached it.
            val loaded = schemaCache[uri] ?: loader(uri)?.let { schema -> schemaCache.putIfAbsent(uri, schema) ?: schema }
            load.result = loaded
            return loaded
        } finally {
            inFlight.remove(uri, load)
            load.done.countDown()
        }
    }

    /** Waits for [latch] without abandoning the wait on interrupt; the interrupt status is restored afterwards. */
    private fun awaitUninterruptibly(latch: java.util.concurrent.CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/** Where a reference leads: [target], inside [resource], whose absolute base is [base] (null: keep the current one). */
internal data class ResolvedReference(val target: JsonElement, val resource: JsonElement, val base: String?)

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
     *  1. Its registration: a relative $id resolved in place (see [registerAbsoluteUri]), or the base a
     *     reference hop reached it under (see [withBase]), which covers loaded documents
     *  2. Its raw $id if that is already absolute
     */
    internal fun getAbsoluteUri(schema: JsonElement): String {
        schemaAbsoluteUris[schema]?.let { return it }
        val rawId = (schema as? JsonObject)?.stringKeyword(SchemaKeywords.ID) ?: ""
        return if (rawId.isNotEmpty() && UriResolver.isAbsoluteUri(rawId)) rawId else ""
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
     * Percent-encoded characters in the fragment are decoded per RFC 3986. The target, the resource that
     * holds it and that resource's base are found together, so they cannot disagree (#52).
     *
     * @param rootSchema the document searched for a fragment-only reference, or for the URI part
     * @param resourceRoot the schema resource that owns the ref (used for relative URI resolution)
     */
    internal fun resolveRef(
        ref: String,
        rootSchema: JsonElement,
        resourceRoot: JsonElement = rootSchema,
    ): ResolvedReference? {
        val hashIndex = ref.indexOf('#')
        val uriPart = if (hashIndex >= 0) ref.substring(0, hashIndex) else ref
        val fragment = percentDecode(if (hashIndex >= 0) ref.substring(hashIndex + 1) else "")

        if (uriPart.isEmpty()) return resolveLocalRef(rootSchema, fragment)?.let { ResolvedReference(it, resourceRoot, null) }
        val located = locateResource(uriPart, rootSchema, resourceRoot) ?: return null
        val target = when {
            fragment.isEmpty() -> located.resource
            fragment.startsWith("/") -> JsonPointer.resolve(located.resource, fragment)
            else -> findAnchorInResource(located.resource, fragment)
        } ?: return null
        return located.copy(target = target)
    }

    /**
     * The resource [uriPart] identifies, with its absolute base:
     *  1. A relative reference resolves against the base of the resource that contains it (RFC 3986 §5.1),
     *     e.g. "target" inside a resource with $id "b/" means ".../b/target", so that is searched first; the
     *     document root's base is only a fallback for resources without an absolute base
     *  2. A schema already loaded from that URI, or one embedded in [resourceRoot]
     *  3. A load through the validator's [SchemaStore]
     * A loaded document's base is its own $id, resolved against the URI it was retrieved from, else that URI.
     */
    private fun locateResource(
        uriPart: String,
        rootSchema: JsonElement,
        resourceRoot: JsonElement,
    ): ResolvedReference? {
        fun inDocument(resource: JsonElement) = ResolvedReference(resource, resource, getAbsoluteUri(resource).takeIf { isAbsolute(it) })

        val baseId = getAbsoluteUri(resourceRoot)
        if (isAbsolute(baseId)) {
            runCatching { UriResolver.resolveUri(baseId, uriPart) }.getOrNull()?.let { findSchemaByUri(rootSchema, it) }?.let { return inDocument(it) }
        }
        findSchemaByUri(rootSchema, uriPart)?.let { return inDocument(it) }

        val absoluteUri = if (baseId.isNotEmpty()) runCatching { UriResolver.resolveUri(baseId, uriPart) }.getOrDefault(uriPart) else uriPart
        store.cached(absoluteUri)?.let { return loaded(it, absoluteUri) }
        // The target URI may be embedded within resourceRoot (e.g. a nested $id sub-schema)
        if (absoluteUri != uriPart || baseId.isNotEmpty()) {
            findSchemaByUri(resourceRoot, absoluteUri)?.let { return inDocument(it) }
        }
        return store.load(absoluteUri)?.let { loaded(it, absoluteUri) }
    }

    /** A document retrieved from [retrievalUri]: its own $id, resolved against that URI, else the URI is its base. */
    private fun loaded(document: JsonElement, retrievalUri: String): ResolvedReference {
        val id = (document as? JsonObject)?.stringKeyword(SchemaKeywords.ID)
        val canonical = id?.let { runCatching { UriResolver.resolveUri(retrievalUri, it) }.getOrNull()?.substringBefore('#') }
        return ResolvedReference(document, document, canonical?.takeIf { isAbsolute(it) } ?: retrievalUri.takeIf { isAbsolute(it) })
    }

    /**
     * Runs [block] with [resource]'s base set to [base], then restores what was registered before. A
     * reference hop knows the base its resource was reached under (#52); the same element may be reached
     * under other bases elsewhere (#54), so the registration lasts only as long as the hop.
     */
    internal fun <T> withBase(resource: JsonElement, base: String?, block: () -> T): T {
        if (base == null) return block()
        val previous = schemaAbsoluteUris.put(resource, base)
        try {
            return block()
        } finally {
            if (previous == null) schemaAbsoluteUris.remove(resource) else schemaAbsoluteUris[resource] = previous
        }
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
