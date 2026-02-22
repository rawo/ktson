package org.ktson

import kotlinx.serialization.json.*

/**
 * JSON Pointer implementation (RFC 6901)
 * Provides navigation within JSON documents using a string notation
 */
object JsonPointer {
    /**
     * Resolve a JSON Pointer path in a document
     *
     * @param document The JSON document to navigate
     * @param pointer The JSON Pointer string (e.g., "/foo/bar/0")
     * @return The element at the pointer location, or null if not found
     */
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

        if (path.isEmpty()) {
            return document
        }

        val tokens = path.split("/").map { decodeToken(it) }
        var current: JsonElement = document

        for (token in tokens) {
            current = when (current) {
                is JsonObject -> {
                    current[token] ?: return null
                }
                is JsonArray -> {
                    val index = token.toIntOrNull() ?: return null
                    if (index < 0 || index >= current.size) return null
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
    private fun decodeToken(token: String): String = token
            .replace("~1", "/")
            .replace("~0", "~")

    /**
     * Encode a string as a JSON Pointer token
     */
    fun encodeToken(token: String): String = token
            .replace("~", "~0")
            .replace("/", "~1")
}

/**
 * Reference resolver for JSON Schema
 * Handles $ref, $recursiveRef, $dynamicRef
 * Thread-safe implementation with optional external schema loading.
 */
class ReferenceResolver(
    private val schemaLoader: ((String) -> JsonElement?)? = null,
) {
    /** Cache of externally-loaded schemas keyed by their absolute URI. */
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<String, JsonElement>()

    /**
     * Maps externally-loaded schema elements → the URI they were loaded from.
     * Used to resolve relative $refs within loaded schemas that have no $id.
     */
    private val loadedSchemaUris =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<JsonElement, String>())

    /**
     * Maps inline schema elements → their resolved absolute URI.
     * Populated by [registerAbsoluteUri] for schemas with relative $id.
     */
    private val schemaAbsoluteUris =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<JsonElement, String>())

    /**
     * Register the resolved absolute URI for an inline schema element.
     * Call this whenever validateElement enters a new resource root (schema with $id).
     * This enables correct relative URI resolution for nested schemas with relative $id values.
     */
    fun registerAbsoluteUri(
        schema: JsonElement,
        absoluteUri: String,
    ) {
        if (absoluteUri.isNotEmpty()) schemaAbsoluteUris[schema] = absoluteUri
    }

    /**
     * Returns the effective absolute base URI for [schema]:
     *  1. Checks the [schemaAbsoluteUris] registration map (handles relative $id)
     *  2. Checks the [loadedSchemaUris] map (handles schemas without $id loaded externally)
     *  3. Falls back to the raw $id if it is already absolute
     */
    internal fun getAbsoluteUri(schema: JsonElement): String {
        schemaAbsoluteUris[schema]?.let { return it }
        loadedSchemaUris[schema]?.let { return it }
        val rawId = (schema as? JsonObject)?.get(SchemaKeywords.ID)?.jsonPrimitive?.contentOrNull ?: ""
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
        return schemaCache[absoluteUri]
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
    fun resolveRef(
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
            val targetSchema = findSchemaByUri(rootSchema, uriPart)
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
     *  3. Invokes the [schemaLoader] to load from an external source
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

        schemaCache[absoluteUri]?.let { return it }

        // Check if the target URI is embedded within baseSchema (e.g. a nested $id sub-schema)
        if (absoluteUri != uriPart || baseId.isNotEmpty()) {
            findSchemaByUri(baseSchema, absoluteUri)?.let { return it }
        }

        val loaded = schemaLoader?.invoke(absoluteUri) ?: return null
        schemaCache[absoluteUri] = loaded
        loadedSchemaUris[loaded] = absoluteUri
        return loaded
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
     * Decode percent-encoded characters in a URI fragment per RFC 3986.
     * JSON Pointer tokens inside URI fragments must be percent-decoded before
     * applying the ~0/~1 escape rules (RFC 6901 §6).
     */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(s[i++])
        }
        return sb.toString()
    }

    /**
     * Search for a schema with a matching $anchor or $dynamicAnchor within a single schema resource.
     * Stops at nested $id boundaries — those are separate resources and are not searched.
     */
    private fun findAnchorInResource(
        schema: JsonElement,
        anchorName: String,
        isRoot: Boolean = true,
    ): JsonElement? {
        if (schema !is JsonObject) return null
        if (!isRoot && schema.containsKey(SchemaKeywords.ID)) return null
        val schemaAnchor = schema[SchemaKeywords.ANCHOR]?.jsonPrimitive?.contentOrNull
        val schemaDynamicAnchor = schema[SchemaKeywords.DYNAMIC_ANCHOR]?.jsonPrimitive?.contentOrNull
        if (schemaAnchor == anchorName || schemaDynamicAnchor == anchorName) return schema
        return schema.values.firstNotNullOfOrNull { value ->
            when (value) {
                is JsonObject -> findAnchorInResource(value, anchorName, false)
                is JsonArray -> value.firstNotNullOfOrNull { findAnchorInResource(it, anchorName, false) }
                else -> null
            }
        }
    }

    /**
     * Search for a schema with a matching $dynamicAnchor within a single schema resource.
     * Only matches $dynamicAnchor (not $anchor) — used for $dynamicRef dynamic scope walking.
     * Stops at nested $id boundaries — those are separate resources and are not searched.
     */
    fun findDynamicAnchorInResource(
        schema: JsonElement,
        anchorName: String,
        isRoot: Boolean = true,
    ): JsonElement? {
        if (schema !is JsonObject) return null
        if (!isRoot && schema.containsKey(SchemaKeywords.ID)) return null
        if (schema[SchemaKeywords.DYNAMIC_ANCHOR]?.jsonPrimitive?.contentOrNull == anchorName) return schema
        return schema.values.firstNotNullOfOrNull { value ->
            when (value) {
                is JsonObject -> findDynamicAnchorInResource(value, anchorName, false)
                is JsonArray -> value.firstNotNullOfOrNull { findDynamicAnchorInResource(it, anchorName, false) }
                else -> null
            }
        }
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
        val rootId = (rootSchema as? JsonObject)?.get(SchemaKeywords.ID)?.jsonPrimitive?.contentOrNull ?: ""
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
        if (element !is JsonObject) return null
        val id = element[SchemaKeywords.ID]?.jsonPrimitive?.contentOrNull
        val currentUri =
            if (id != null) {
                val resolved =
                    try {
                        UriResolver.resolveUri(baseUri, id)
                    } catch (_: Exception) {
                        id
                    }
                // Register the resolved absolute URI so relative $refs within this sub-schema work
                registerAbsoluteUri(element, resolved)
                resolved
            } else {
                baseUri
            }
        if (id != null && (currentUri == resolvedTarget || id == originalTarget)) return element
        return element.values.firstNotNullOfOrNull { value ->
            when (value) {
                is JsonObject -> searchById(value, resolvedTarget, originalTarget, currentUri)
                is JsonArray -> value.firstNotNullOfOrNull { searchById(it, resolvedTarget, originalTarget, currentUri) }
                else -> null
            }
        }
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
