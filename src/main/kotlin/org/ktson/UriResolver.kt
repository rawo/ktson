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

        // Parse both first, so syntax errors propagate as URISyntaxException
        URI(baseUri)
        if (isAbsoluteUri(refUri)) return normalizeUri(refUri)
        return normalizeUri(resolveComponents(UriParts.of(baseUri), UriParts.of(refUri)).toString())
    }

    /**
     * RFC 3986 §5.2.2 transform of a relative [ref] against [base], on raw components. java.net.URI.resolve
     * follows RFC 2396 and differs: it drops the last segment for a query-only reference (#58), collapses
     * empty segments ("a//b/" + "c" gave "a/b/c", #68) and leaves an opaque base unchanged.
     */
    private fun resolveComponents(base: UriParts, ref: UriParts): UriParts = when {
        ref.authority != null -> UriParts(base.scheme, ref.authority, removeDotSegments(ref.path), ref.query, ref.fragment)
        ref.path.isEmpty() -> UriParts(base.scheme, base.authority, base.path, ref.query ?: base.query, ref.fragment)
        ref.path.startsWith("/") -> UriParts(base.scheme, base.authority, removeDotSegments(ref.path), ref.query, ref.fragment)
        else -> UriParts(base.scheme, base.authority, removeDotSegments(mergePaths(base, ref.path)), ref.query, ref.fragment)
    }

    /** RFC 3986 §5.2.3: the reference path appended to the base path up to and including its last "/". */
    private fun mergePaths(base: UriParts, refPath: String): String = when {
        base.authority != null && base.path.isEmpty() -> "/$refPath"
        '/' in base.path -> base.path.substring(0, base.path.lastIndexOf('/') + 1) + refPath
        else -> refPath
    }

    /** The five raw components of a URI reference, split as RFC 3986 Appendix B does; nothing is decoded. */
    private data class UriParts(val scheme: String?, val authority: String?, val path: String, val query: String?, val fragment: String?) {
        override fun toString(): String = buildUri(scheme, authority, path, query, fragment)

        companion object {
            private val PARTS = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?")

            fun of(uri: String): UriParts {
                val groups = PARTS.find(uri)!!.groups
                return UriParts(groups[2]?.value, groups[4]?.value, groups[5]?.value ?: "", groups[7]?.value, groups[9]?.value)
            }
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
     * Parses a URI into its RFC 3986 components, as written: percent-encoding is kept, since decoding
     * "%2F" or "%23" would turn data into a delimiter, and an opaque URI's body ("example:schema" in
     * "urn:example:schema") is its path (#67).
     *
     * @param uri The URI to parse
     * @return the components
     * @throws URISyntaxException if [uri] is not a valid URI reference
     */
    fun parseUri(uri: String): UriComponents {
        require(uri.isNotEmpty()) { "URI must not be empty" }
        URI(uri) // validates the syntax
        val parts = UriParts.of(uri)
        return UriComponents(scheme = parts.scheme, authority = parts.authority, path = parts.path, query = parts.query, fragment = parts.fragment)
    }

    /**
     * Components of a parsed URI (RFC 3986 §3), as written: nothing is percent-decoded.
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
