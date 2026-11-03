package org.ktson

/**
 * RFC 3986 / RFC 3987 grammar shared by the `uri`, `uri-reference`, `iri`, `iri-reference`, `ipv4` and `ipv6`
 * formats and by [UriResolver], so a URI the format checks accept is one the resolver can parse (#71).
 */
internal object UriSyntax {
    /**
     * RFC 2673 dotted-quad. Leading zeros are rejected: they read as octal in
     * most resolvers, so "192.168.0.01" is not the same address as written.
     */
    fun isValidIPv4(value: String): Boolean {
        // "255.255.255.255": reject longer strings before splitting them (#88)
        if (value.length > 15) return false
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') &&
                part.toInt() <= 255
        }
    }

    fun isValidIPv6(value: String): Boolean {
        // The longest form is six full groups and an IPv4 tail, "ffff:...:ffff:255.255.255.255"; reject longer
        // strings before copying and splitting them (#88)
        if (value.isEmpty() || value.length > 45) return false
        val hasMixed = '.' in value
        if (hasMixed) {
            val lastColon = value.lastIndexOf(':')
            if (lastColon < 0) return false
            val ipv4Part = value.substring(lastColon + 1)
            if (!isValidIPv4(ipv4Part)) return false
            // Replace IPv4 tail with two placeholder hex groups and validate as pure IPv6
            return isValidPureIPv6Part(value.substring(0, lastColon) + ":0:0")
        }
        return isValidPureIPv6Part(value)
    }

    private fun isValidPureIPv6Part(s: String): Boolean {
        if (":::" in s) return false
        val hasCompress = "::" in s
        if (hasCompress && s.indexOf("::") != s.lastIndexOf("::")) return false
        return if (hasCompress) {
            val sides = s.split("::")
            val left = if (sides[0].isEmpty()) emptyList() else sides[0].split(":")
            val right = if (sides[1].isEmpty()) emptyList() else sides[1].split(":")
            left.size + right.size < 8 && (left + right).all { isValidIPv6Group(it) }
        } else {
            val groups = s.split(":")
            groups.size == 8 && groups.all { isValidIPv6Group(it) }
        }
    }

    private fun isValidIPv6Group(s: String): Boolean = s.length in 1..4 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * Shared RFC 3986 / RFC 3987 validation behind the uri, uri-reference, iri and
     * iri-reference formats.
     *
     * @param requireScheme true for the absolute forms (uri, iri)
     * @param allowUcs true for the IRI forms, which additionally allow non-ASCII characters
     */
    fun isValidUriLike(value: String, requireScheme: Boolean, allowUcs: Boolean): Boolean {
        val schemeEnd = schemeLength(value)
        if (schemeEnd == null && requireScheme) return false
        val rest = if (schemeEnd != null) value.substring(schemeEnd + 1) else value

        val authority: String?
        val afterAuthority: String
        if (rest.startsWith("//")) {
            val body = rest.substring(2)
            val end = body.indexOfFirst { it == '/' || it == '?' || it == '#' }.takeIf { it >= 0 } ?: body.length
            authority = body.substring(0, end)
            afterAuthority = body.substring(end)
        } else {
            authority = null
            afterAuthority = rest
        }

        val nonAscii = if (allowUcs) NonAscii.UCSCHAR else NonAscii.FORBIDDEN
        if (authority != null && !isValidUriAuthority(authority, nonAscii)) return false
        // "#" starts the fragment, and a fragment cannot contain another one (RFC 3986 §3.5)
        val fragmentStart = afterAuthority.indexOf('#').takeIf { it >= 0 } ?: afterAuthority.length
        if (afterAuthority.indexOf('#', fragmentStart + 1) >= 0) return false
        val beforeFragment = afterAuthority.substring(0, fragmentStart)
        val queryStart = beforeFragment.indexOf('?').takeIf { it >= 0 } ?: beforeFragment.length
        // Square brackets delimit an IP-literal host and are illegal anywhere else. RFC 3987 admits private-use
        // characters only in the query (#76).
        val queryNonAscii = if (allowUcs) NonAscii.UCSCHAR_OR_IPRIVATE else NonAscii.FORBIDDEN
        if (!isValidUriCharacters(beforeFragment.substring(0, queryStart), nonAscii)) return false
        if (!isValidUriCharacters(beforeFragment.substring(queryStart), queryNonAscii)) return false
        if (!isValidUriCharacters(afterAuthority.substring(fragmentStart), nonAscii)) return false

        // A relative-path reference whose first segment contains a colon would be
        // read as a scheme, so RFC 3986 forbids it
        if (schemeEnd == null && authority == null && !value.startsWith("/")) {
            val path = value.substringBefore('#').substringBefore('?')
            if (':' in path.substringBefore('/')) return false
        }
        return true
    }

    /** Length of the leading `scheme:` if the value starts with a valid scheme, else null. */
    fun schemeLength(value: String): Int? {
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        val scheme = value.substring(0, colon)
        if (scheme[0] !in 'a'..'z' && scheme[0] !in 'A'..'Z') return null
        if (scheme.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '+' && it != '-' && it != '.' }) return null
        return colon
    }

    /** Which non-ASCII characters a URI component admits. */
    private enum class NonAscii {
        /** A URI (RFC 3986): none. */
        FORBIDDEN,

        /** An IRI component (RFC 3987 iunreserved): ucschar. */
        UCSCHAR,

        /** An IRI query (RFC 3987 iquery): ucschar and iprivate. */
        UCSCHAR_OR_IPRIVATE,
        ;

        fun admits(codePoint: Int): Boolean = when (this) {
            FORBIDDEN -> false
            UCSCHAR -> isUcschar(codePoint)
            UCSCHAR_OR_IPRIVATE -> isUcschar(codePoint) || isIprivate(codePoint)
        }
    }

    /**
     * RFC 3987 ucschar: %xA0-D7FF / %xF900-FDCF / %xFDF0-FFEF, then planes 1-13 and plane 14 from E1000, each
     * without its last two code points. So C1 controls, surrogates, private use and noncharacters are excluded.
     */
    fun isUcschar(codePoint: Int): Boolean = when (codePoint) {
        in 0xA0..0xD7FF, in 0xF900..0xFDCF, in 0xFDF0..0xFFEF -> true
        in 0x10000..0xDFFFD, in 0xE1000..0xEFFFD -> (codePoint and 0xFFFF) <= 0xFFFD
        else -> false
    }

    /** RFC 3987 iprivate: %xE000-F8FF / %xF0000-FFFFD / %x100000-10FFFD. */
    fun isIprivate(codePoint: Int): Boolean = codePoint in 0xE000..0xF8FF || (codePoint >= 0xF0000 && (codePoint and 0xFFFF) <= 0xFFFD)

    /**
     * Characters legal outside an authority, with percent-encoding required to be complete triplets. Read by
     * code point, so an unpaired surrogate is seen as itself and rejected.
     */
    private fun isValidUriCharacters(value: String, nonAscii: NonAscii): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' -> {
                    if (i + 2 >= value.length || !isHexChar(value[i + 1]) || !isHexChar(value[i + 2])) return false
                    i += 3
                }
                c.code > 127 -> {
                    val codePoint = value.codePointAt(i)
                    if (!nonAscii.admits(codePoint)) return false
                    i += Character.charCount(codePoint)
                }
                c in URI_FORBIDDEN_CHARS || c.code <= 0x20 || c.code == 0x7F -> return false
                else -> i++
            }
        }
        return true
    }

    private fun isValidUriAuthority(authority: String, nonAscii: NonAscii): Boolean {
        // userinfo may not contain an unencoded at-sign, so at most one may appear
        if (authority.count { it == '@' } > 1) return false
        val userinfo = authority.substringBeforeLast('@', "")
        if ('[' in userinfo || ']' in userinfo) return false
        if (!isValidUriCharacters(userinfo, nonAscii)) return false

        val hostAndPort = authority.substringAfterLast('@')
        val port: String?
        if (hostAndPort.startsWith('[')) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return false
            val host = hostAndPort.substring(1, close)
            if (!isValidIPv6(host) && !isValidIpvFuture(host)) return false
            val after = hostAndPort.substring(close + 1)
            port = when {
                after.isEmpty() -> null
                after.startsWith(':') -> after.substring(1)
                else -> return false
            }
        } else {
            val colon = hostAndPort.lastIndexOf(':')
            val host = if (colon >= 0) hostAndPort.substring(0, colon) else hostAndPort
            port = if (colon >= 0) hostAndPort.substring(colon + 1) else null
            // a reg-name cannot contain a colon; an IPv6 host must be bracketed
            if ('[' in host || ']' in host || ':' in host) return false
            if (!isValidUriCharacters(host, nonAscii)) return false
        }
        return port == null || port.all { it in '0'..'9' }
    }

    /** IPvFuture = "v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" ) (RFC 3986 §3.2.2). */
    private fun isValidIpvFuture(host: String): Boolean {
        if (host.isEmpty() || (host[0] != 'v' && host[0] != 'V')) return false
        val version = host.drop(1).substringBefore('.', "")
        val address = host.drop(1).substringAfter('.', "")
        return version.isNotEmpty() && version.all { isHexChar(it) } &&
            address.isNotEmpty() && address.all { (it.code < 128 && it.isLetterOrDigit()) || it in IPV_FUTURE_SYMBOLS }
    }

    fun isHexChar(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // Illegal outside an authority component; brackets only delimit an IP-literal host
    private val URI_FORBIDDEN_CHARS = setOf(' ', '"', '<', '>', '{', '}', '^', '`', '|', '\\', '[', ']')

    /** The non-alphanumeric characters an IPvFuture address may use: unreserved, sub-delims and ":". */
    private const val IPV_FUTURE_SYMBOLS = "-._~!$&'()*+,;=:"
}
