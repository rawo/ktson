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
        if (isAbsoluteUri(refUri)) return normalizeUri(refUri)
        // A fragment-only reference keeps the whole base and replaces its fragment (RFC 3986 §5.2.2), for any
        // base: java.net.URI.resolve returns it unchanged for an opaque base such as a URN.
        if (refUri.startsWith("#")) return normalizeUri(baseUri, removeFragment = true) + refUri
        val base = URI(baseUri)
        // An opaque base has no "/" in its path, so a relative path replaces it entirely (RFC 3986 §5.2.3).
        if (base.isOpaque) return normalizeUri("${base.scheme}:$refUri")
        return normalizeUri(base.resolve(refUri).toString())
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
     * Normalizes a URI according to RFC 3986 §6.2.2 without changing what it identifies:
     * - lowercases the scheme and the host (not the user info)
     * - removes "." and ".." path segments (§5.2.4), keeping empty segments ("a//b")
     * - keeps percent-encoded characters encoded ("a%2Fb" is not "a/b")
     * - keeps opaque URIs such as URNs whole, apart from the scheme's case
     * - removes the fragment if requested (for cache keys)
     *
     * @param uri The URI to normalize
     * @param removeFragment Whether to remove the fragment (default: false)
     * @return The normalized URI
     */
    fun normalizeUri(uri: String, removeFragment: Boolean = false): String {
        if (uri.isEmpty()) return uri

        val parsed = URI(uri)
        val fragment = if (removeFragment) null else parsed.rawFragment

        if (parsed.isOpaque) {
            return buildString {
                append(parsed.scheme.lowercase()).append(':').append(parsed.rawSchemeSpecificPart)
                fragment?.let { append('#').append(it) }
            }
        }

        val scheme = parsed.scheme?.lowercase()
        val authority = parsed.rawAuthority?.let { normalizeAuthority(parsed, it) }
        val rawPath = parsed.rawPath ?: ""
        // Dot segments are removed from absolute paths; a relative reference keeps its leading ".." for resolution.
        val path = if (scheme != null || authority != null || rawPath.startsWith("/")) removeDotSegments(rawPath) else rawPath
        return buildUri(scheme, authority, path, parsed.rawQuery, fragment)
    }

    /** Lowercases the host only; user info and port are kept as written. A registry-based authority is left alone. */
    private fun normalizeAuthority(parsed: URI, rawAuthority: String): String {
        val host = parsed.host ?: return rawAuthority
        return buildString {
            parsed.rawUserInfo?.let { append(it).append('@') }
            append(host.lowercase())
            if (parsed.port != -1) append(':').append(parsed.port)
        }
    }

    /** RFC 3986 §5.2.4 remove_dot_segments: "." and ".." are removed, other segments (empty ones too) are kept. */
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()

        fun removeLastSegment() {
            val slash = output.lastIndexOf("/")
            output.setLength(if (slash >= 0) slash else 0)
        }

        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.substring(3)
                input.startsWith("./") -> input = input.substring(2)
                input.startsWith("/./") -> input = input.substring(2)
                input == "/." -> input = "/"
                input.startsWith("/../") -> {
                    input = input.substring(3)
                    removeLastSegment()
                }
                input == "/.." -> {
                    input = "/"
                    removeLastSegment()
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    val next = input.indexOf('/', if (input.startsWith("/")) 1 else 0)
                    val segment = if (next == -1) input else input.substring(0, next)
                    output.append(segment)
                    input = input.substring(segment.length)
                }
            }
        }
        return output.toString()
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
