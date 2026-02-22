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
 * Thread-safe and stateless implementation
 */
class ReferenceResolver {
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
     */
    fun resolveRef(
        ref: String,
        rootSchema: JsonElement,
        currentSchema: JsonElement = rootSchema,
    ): JsonElement? {
        val hashIndex = ref.indexOf('#')
        val uriPart = if (hashIndex >= 0) ref.substring(0, hashIndex) else ref
        val fragment = percentDecode(if (hashIndex >= 0) ref.substring(hashIndex + 1) else "")

        return if (uriPart.isEmpty()) {
            resolveLocalRef(rootSchema, fragment)
        } else {
            val targetSchema = findSchemaByUri(rootSchema, uriPart) ?: return null
            when {
                fragment.isEmpty() -> targetSchema
                fragment.startsWith("/") -> JsonPointer.resolve(targetSchema, fragment)
                else -> findAnchorInResource(targetSchema, fragment)
            }
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
     * Search for a schema with a matching $anchor within a single schema resource.
     * Stops at nested $id boundaries — those are separate resources and are not searched.
     */
    private fun findAnchorInResource(
        schema: JsonElement,
        anchorName: String,
        isRoot: Boolean = true,
    ): JsonElement? {
        if (schema !is JsonObject) return null
        if (!isRoot && schema.containsKey(SchemaKeywords.ID)) return null
        if (schema[SchemaKeywords.ANCHOR]?.jsonPrimitive?.contentOrNull == anchorName) return schema
        return schema.values.firstNotNullOfOrNull { value ->
            when (value) {
                is JsonObject -> findAnchorInResource(value, anchorName, false)
                is JsonArray -> value.firstNotNullOfOrNull { findAnchorInResource(it, anchorName, false) }
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
                try {
                    UriResolver.resolveUri(baseUri, id)
                } catch (_: Exception) {
                    id
                }
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
}
