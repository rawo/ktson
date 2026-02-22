package org.ktson

import java.net.URI
import java.net.URISyntaxException

/**
 * URI resolution utilities for JSON Schema references
 * Implements RFC 3986 URI resolution
 */
object UriResolver {

    /**
     * Resolves a reference URI against a base URI according to RFC 3986
     *
     * @param baseUri The base URI to resolve against (can be empty)
     * @param refUri The reference URI to resolve
     * @return The resolved absolute URI, or the original refUri if it's already absolute
     *
     * Examples:
     * - resolveUri("https://example.com/schemas/", "person.json") -> "https://example.com/schemas/person.json"
     * - resolveUri("https://example.com/schemas/base.json", "../other.json") -> "https://example.com/other.json"
     * - resolveUri("", "https://example.com/schema.json") -> "https://example.com/schema.json"
     */
    fun resolveUri(baseUri: String, refUri: String): String {
        if (refUri.isEmpty()) {
            return baseUri
        }

        if (baseUri.isEmpty()) {
            return normalizeUri(refUri)
        }

        // Parse and resolve; allow syntax/resolution exceptions to propagate
        return if (isAbsoluteUri(refUri)) {
            normalizeUri(refUri)
        } else {
            val base = URI(baseUri)
            val resolved = base.resolve(refUri)
            normalizeUri(resolved.toString())
        }
    }

    /**
     * Checks if a URI is absolute (has a scheme)
     *
     * @param uri The URI to check
     * @return true if the URI is absolute, false otherwise
     */
    fun isAbsoluteUri(uri: String): Boolean {
        if (uri.isEmpty()) return false
        val parsed = URI(uri)
        return parsed.isAbsolute
    }

    /**
     * Normalizes a URI according to RFC 3986
     * - Removes fragment if present (for cache keys)
     * - Normalizes path (removes /./, resolves /../)
     * - Lowercases scheme and host
     *
     * @param uri The URI to normalize
     * @param removeFragment Whether to remove the fragment (default: false)
     * @return The normalized URI
     */
    fun normalizeUri(uri: String, removeFragment: Boolean = false): String {
        if (uri.isEmpty()) return uri

        val parsed = URI(uri)

        // Build normalized URI
        val scheme = parsed.scheme?.lowercase()
        val authority = parsed.authority?.lowercase()
        val path = normalizePath(parsed.path ?: "")
        val query = parsed.query
        val fragment = if (removeFragment) null else parsed.fragment

        return buildUri(scheme, authority, path, query, fragment)
    }

    /**
     * Normalizes a URI path
     * - Removes /./
     * - Resolves /../
     *
     * @param path The path to normalize
     * @return The normalized path
     */
    private fun normalizePath(path: String): String {
        if (path.isEmpty()) return path

        val segments = path.split("/").toMutableList()
        val normalized = mutableListOf<String>()

        for (segment in segments) {
            when (segment) {
                "", "." -> {
                    // Skip empty and "." segments, but preserve leading/trailing slashes
                    if (normalized.isEmpty() && path.startsWith("/")) {
                        normalized.add("")
                    }
                }
                ".." -> {
                    // Go up one level if possible
                    if (normalized.isNotEmpty() && normalized.last() != "" && normalized.last() != "..") {
                        normalized.removeAt(normalized.size - 1)
                    } else if (!path.startsWith("/")) {
                        // For relative paths, keep ..
                        normalized.add("..")
                    }
                }
                else -> normalized.add(segment)
            }
        }

        if (path.endsWith("/") && normalized.isNotEmpty()) {
            normalized.add("")
        }

        return normalized.joinToString("/")
    }

    /**
     * Builds a URI string from components
     */
    private fun buildUri(
        scheme: String?,
        authority: String?,
        path: String,
        query: String?,
        fragment: String?,
    ): String {
        val sb = StringBuilder()

        scheme?.let {
            sb.append(it).append(":")
        }

        authority?.let {
            sb.append("//").append(it)
        }

        sb.append(path)

        query?.let {
            sb.append("?").append(it)
        }

        fragment?.let {
            sb.append("#").append(it)
        }

        return sb.toString()
    }

    /**
     * Extracts the fragment from a URI
     *
     * @param uri The URI to extract from
     * @return The fragment (without #), or null if no fragment
     */
    fun extractFragment(uri: String): String? {
        if (uri.isEmpty()) return null
        return URI(uri).fragment
    }

    /**
     * Removes the fragment from a URI
     *
     * @param uri The URI to process
     * @return The URI without fragment
     */
    fun removeFragment(uri: String): String = normalizeUri(uri, removeFragment = true)

    /**
     * Parses a URI into components
     *
     * @param uri The URI to parse
     * @return UriComponents containing the parsed URI, or null if parsing fails
     */
    fun parseUri(uri: String): UriComponents {
        require(uri.isNotEmpty()) { "URI must not be empty" }
        val parsed = URI(uri)
        return UriComponents(
            scheme = parsed.scheme,
            authority = parsed.authority,
            path = parsed.path ?: "",
            query = parsed.query,
            fragment = parsed.fragment,
        )
    }

    /**
     * Components of a parsed URI
     */
    data class UriComponents(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    ) {
        /**
         * Returns the URI without fragment
         */
        val withoutFragment: String
            get() = buildUri(scheme, authority, path, query, null)

        /**
         * Returns true if this is an absolute URI
         */
        val isAbsolute: Boolean
            get() = scheme != null
    }
}
