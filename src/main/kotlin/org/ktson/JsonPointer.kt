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
 */
class ReferenceResolver {
    private val schemaCache = mutableMapOf<String, JsonElement>()
    private val recursionDepth = mutableMapOf<String, Int>()
    private val maxRecursionDepth = 100

    /**
     * Resolve a $ref reference
     *
     * @param ref The reference string (e.g., "#/definitions/foo", "http://example.com/schema")
     * @param rootSchema The root schema document
     * @param currentSchema The current schema context
     * @return The resolved schema, or null if not found
     */
    fun resolveRef(ref: String, rootSchema: JsonElement, currentSchema: JsonElement = rootSchema): JsonElement? {
        // Check recursion depth
        val depth = recursionDepth.getOrDefault(ref, 0)
        if (depth >= maxRecursionDepth) {
            return null // Infinite recursion detected
        }

        recursionDepth[ref] = depth + 1

        try {
            return when {
                // Local reference (#/...)
                ref.startsWith("#/") || ref.startsWith("#") -> {
                    JsonPointer.resolve(rootSchema, ref)
                }
                // HTTP/HTTPS reference (not supported in MVP)
                ref.startsWith("http://") || ref.startsWith("https://") -> {
                    // Would need to fetch remote schema
                    null
                }
                // Relative reference
                else -> {
                    // For now, treat as local reference
                    JsonPointer.resolve(rootSchema, "#/$ref")
                }
            }
        } finally {
            recursionDepth[ref] = recursionDepth[ref]!! - 1
            if (recursionDepth[ref]!! == 0) {
                recursionDepth.remove(ref)
            }
        }
    }
}
