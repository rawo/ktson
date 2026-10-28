package org.ktson

import java.net.URISyntaxException

/**
 * URI resolution utilities for JSON Schema references
 * Implements RFC 3986 URI resolution. URIs are parsed with [UriSyntax], the grammar the uri and iri formats
 * check, not java.net.URI: its host grammar is narrower (it rejects IPvFuture hosts such as "[v1.example]"),
 * so a schema could pass validateSchema and then fail resolution (#71).
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
        // An empty reference is the base itself, minus any fragment the base carries (RFC 3986 §5.2.2, #74)
        if (refUri.isEmpty()) {
            return baseUri.substringBefore('#')
        }

        if (baseUri.isEmpty()) {
            return normalizeUri(refUri)
        }

        val base = parse(baseUri)
        val ref = parse(refUri)
        if (ref.scheme != null) return normalizeUri(refUri)
        return normalizeUri(resolveComponents(base, ref).toString())
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

    /**
     * [uri] split into its components, or a URISyntaxException if it is not an RFC 3986 URI reference
     * (non-ASCII characters are allowed, as in IRIs).
     */
    private fun parse(uri: String): UriParts {
        if (!UriSyntax.isValidUriLike(uri, requireScheme = false, allowUcs = true)) throw URISyntaxException(uri, "Not an RFC 3986 URI reference")
        return UriParts.of(uri)
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
        return parse(uri).scheme != null
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

        val parts = parse(uri)
        val scheme = parts.scheme?.lowercase()
        val fragment = if (removeFragment) null else parts.fragment
        // An opaque URI (a scheme and no "//" authority or "/" path, such as a URN) is kept whole
        if (scheme != null && parts.authority == null && !parts.path.startsWith("/")) return buildUri(scheme, null, parts.path, parts.query, fragment)

        val authority = parts.authority?.let { normalizeAuthority(it) }
        // Dot segments are removed from absolute paths; a relative reference keeps its leading ".." for resolution.
        val path = if (scheme != null || authority != null || parts.path.startsWith("/")) removeDotSegments(parts.path) else parts.path
        return buildUri(scheme, authority, path, parts.query, fragment)
    }

    /** Lowercases the host only (an IP literal included); user info and port are kept as written. */
    private fun normalizeAuthority(authority: String): String {
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostAndPort = authority.substring(at + 1)
        val hostEnd = if (hostAndPort.startsWith('[')) hostAndPort.indexOf(']') + 1 else hostAndPort.lastIndexOf(':').takeIf { it >= 0 } ?: hostAndPort.length
        return userInfo + hostAndPort.substring(0, hostEnd).lowercase() + hostAndPort.substring(hostEnd)
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
     * @return The fragment (without #, percent-decoded), or null if no fragment
     */
    fun extractFragment(uri: String): String? {
        if (uri.isEmpty()) return null
        return parse(uri).fragment?.let { percentDecode(it) }
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
        val parts = parse(uri)
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
