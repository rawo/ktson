package org.ktson

import org.ktson.SchemaKeywords.FORMAT_DATE
import org.ktson.SchemaKeywords.FORMAT_DATE_TIME
import org.ktson.SchemaKeywords.FORMAT_DURATION
import org.ktson.SchemaKeywords.FORMAT_EMAIL
import org.ktson.SchemaKeywords.FORMAT_HOSTNAME
import org.ktson.SchemaKeywords.FORMAT_IDN_EMAIL
import org.ktson.SchemaKeywords.FORMAT_IDN_HOSTNAME
import org.ktson.SchemaKeywords.FORMAT_IPV4
import org.ktson.SchemaKeywords.FORMAT_IPV6
import org.ktson.SchemaKeywords.FORMAT_IRI
import org.ktson.SchemaKeywords.FORMAT_IRI_REFERENCE
import org.ktson.SchemaKeywords.FORMAT_JSON_POINTER
import org.ktson.SchemaKeywords.FORMAT_REGEX
import org.ktson.SchemaKeywords.FORMAT_RELATIVE_JSON_POINTER
import org.ktson.SchemaKeywords.FORMAT_TIME
import org.ktson.SchemaKeywords.FORMAT_URI
import org.ktson.SchemaKeywords.FORMAT_URI_REFERENCE
import org.ktson.SchemaKeywords.FORMAT_URI_TEMPLATE
import org.ktson.SchemaKeywords.FORMAT_UUID

/**
 * The format vocabulary: the grammars behind the `format` keyword, one check per format name. Pure functions over
 * immutable tables; whether a failed check is an error (`formatAssertion`) and the error itself stay with the
 * validator. IDNA, the ECMA 262 regex dialect and the URI grammar live in [Idna], [EcmaRegex] and [UriSyntax].
 */
internal object Formats {
    /** Whether [value] is valid for [format]; unknown formats are ignored, so they are always valid. */
    fun isValid(format: String, value: String): Boolean = when (format) {
        FORMAT_EMAIL -> isValidEmail(value)
        FORMAT_URI -> UriSyntax.isValidUriLike(value, requireScheme = true, allowUcs = false)
        FORMAT_DATE -> isValidDate(value)
        FORMAT_TIME -> isValidTime(value)
        FORMAT_DATE_TIME -> isValidDateTime(value)
        FORMAT_IPV4 -> UriSyntax.isValidIPv4(value)
        FORMAT_IPV6 -> UriSyntax.isValidIPv6(value)
        FORMAT_UUID -> value.matches(REGEX_UUID)
        FORMAT_HOSTNAME -> Idna.isValidHostname(value)
        FORMAT_IDN_EMAIL -> isValidIdnEmail(value)
        FORMAT_IRI -> UriSyntax.isValidUriLike(value, requireScheme = true, allowUcs = true)
        FORMAT_IRI_REFERENCE -> UriSyntax.isValidUriLike(value, requireScheme = false, allowUcs = true)
        FORMAT_IDN_HOSTNAME -> Idna.isValidIdnHostname(value)
        FORMAT_JSON_POINTER -> value.isEmpty() || (value.startsWith("/") && !value.contains(REGEX_JSON_POINTER_INVALID_TILDE))
        FORMAT_RELATIVE_JSON_POINTER -> isValidRelativeJsonPointer(value)
        FORMAT_URI_REFERENCE -> UriSyntax.isValidUriLike(value, requireScheme = false, allowUcs = false)
        FORMAT_URI_TEMPLATE -> isValidUriTemplate(value)
        FORMAT_DURATION -> isValidDuration(value)
        FORMAT_REGEX -> EcmaRegex.isValidEcmaRegex(value)
        else -> true // Unknown formats are ignored
    }

    private fun isValidDate(value: String): Boolean {
        if (!value.matches(REGEX_DATE)) return false
        val parts = value.split('-')
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val day = parts[2].toInt()
        if (month < 1 || month > 12) return false
        val maxDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            else -> return false
        }
        return day in 1..maxDay
    }

    private fun isValidTime(value: String): Boolean {
        if (!value.matches(REGEX_TIME)) return false
        val hour = value.substring(0, 2).toInt()
        val minute = value.substring(3, 5).toInt()
        val second = value.substring(6, 8).toInt()
        if (hour > 23 || minute > 59 || second > 60) return false

        // Locate timezone start (after optional fractional seconds)
        var i = 8
        while (i < value.length && (value[i] == '.' || value[i].isDigit())) i++
        val tzStr = value.substring(i)

        val offsetSign: Int
        val offsetHour: Int
        val offsetMinute: Int
        if (tzStr.equals("Z", ignoreCase = true)) {
            offsetSign = 1
            offsetHour = 0
            offsetMinute = 0
        } else {
            offsetSign = if (tzStr[0] == '+') 1 else -1
            offsetHour = tzStr.substring(1, 3).toInt()
            offsetMinute = tzStr.substring(4, 6).toInt()
            if (offsetHour > 23 || offsetMinute > 59) return false
        }

        if (second == 60) {
            // Leap second valid only when UTC time is 23:59
            val localMinutes = hour * 60 + minute
            val offsetTotalMinutes = offsetHour * 60 + offsetMinute
            val utcMinutes = (localMinutes - offsetSign * offsetTotalMinutes).mod(1440)
            if (utcMinutes != 23 * 60 + 59) return false
        }

        return true
    }

    private fun isValidDateTime(value: String): Boolean {
        if (!value.matches(REGEX_DATE_TIME)) return false
        val tIdx = value.indexOfFirst { it == 'T' || it == 't' }
        return isValidDate(value.substring(0, tIdx)) && isValidTime(value.substring(tIdx + 1))
    }

    private fun isValidEmail(value: String): Boolean {
        if (value.isEmpty()) return false
        return if (value.startsWith('"')) {
            val closeQuote = findClosingQuote(value) ?: return false
            if (closeQuote + 1 >= value.length || value[closeQuote + 1] != '@') return false
            val domain = value.substring(closeQuote + 2)
            isValidEmailLocalQuoted(value.substring(1, closeQuote), allowNonAscii = false) && domain.isNotEmpty() && isValidEmailDomain(domain)
        } else {
            val atIdx = value.lastIndexOf('@')
            if (atIdx <= 0) return false
            val local = value.substring(0, atIdx)
            val domain = value.substring(atIdx + 1)
            domain.isNotEmpty() && isValidEmailLocalUnquoted(local) && isValidEmailDomain(domain)
        }
    }

    /** Index of the quote closing a quoted local part, honouring backslash escapes. */
    private fun findClosingQuote(value: String): Int? {
        var i = 1
        while (i < value.length) {
            when (value[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    /**
     * The content of an RFC 5321 Quoted-string: qtextSMTP (ASCII graphic characters and space, except `"` and
     * backslash) and quoted-pairSMTP (a backslash and an ASCII graphic character or space). Neither admits a tab. With
     * [allowNonAscii], RFC 6531's extension for idn-email also admits non-ASCII qtext, but not escaped (#72).
     */
    private fun isValidEmailLocalQuoted(local: String, allowNonAscii: Boolean): Boolean {
        var i = 0
        while (i < local.length) {
            val c = local[i]
            if (c == '\\') {
                val escaped = local.getOrNull(i + 1) ?: return false
                if (escaped.code !in 0x20..0x7E) return false
                i += 2
            } else {
                if (c == '"' || !(c.code in 0x20..0x7E || (allowNonAscii && c.code > 0x7F))) return false
                i++
            }
        }
        return true
    }

    /** RFC 5321 Dot-string; with [allowNonAscii] the RFC 6531 extension that also admits non-ASCII characters. */
    private fun isValidEmailLocalUnquoted(local: String, allowNonAscii: Boolean = false): Boolean {
        if (local.isEmpty() || local.startsWith('.') || local.endsWith('.')) return false
        if (local.contains("..")) return false
        return local.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in EMAIL_LOCAL_SPECIAL_CHARS || (allowNonAscii && it.code > 127) }
    }

    private fun isValidEmailDomain(domain: String): Boolean {
        if (domain.startsWith('[') && domain.endsWith(']')) {
            val inner = domain.substring(1, domain.length - 1)
            return if (inner.startsWith("IPv6:", ignoreCase = true)) UriSyntax.isValidIPv6(inner.substring(5)) else UriSyntax.isValidIPv4(inner)
        }
        return Idna.isValidHostname(domain)
    }

    /**
     * RFC 6531 Mailbox: the RFC 5321 grammar of [isValidEmail] with non-ASCII characters allowed in the
     * local part (a quoted local part already admits them), and an internationalised domain.
     */
    private fun isValidIdnEmail(value: String): Boolean {
        if (value.isEmpty()) return false
        val (localIsValid, domain) = if (value.startsWith('"')) {
            val closeQuote = findClosingQuote(value) ?: return false
            if (closeQuote + 1 >= value.length || value[closeQuote + 1] != '@') return false
            isValidEmailLocalQuoted(value.substring(1, closeQuote), allowNonAscii = true) to value.substring(closeQuote + 2)
        } else {
            val atIdx = value.lastIndexOf('@')
            if (atIdx <= 0) return false
            isValidEmailLocalUnquoted(value.substring(0, atIdx), allowNonAscii = true) to value.substring(atIdx + 1)
        }
        if (!localIsValid || domain.isEmpty()) return false
        return if (domain.startsWith('[') && domain.endsWith(']')) isValidEmailDomain(domain) else Idna.isValidIdnHostname(domain)
    }

    private fun isValidRelativeJsonPointer(value: String): Boolean {
        if (value.isEmpty()) return false
        var i = 0
        when {
            value[i] == '0' -> i = 1
            // Non-ASCII digits are not part of the grammar, so isDigit() is too permissive here
            value[i] in '1'..'9' -> {
                while (i < value.length && value[i] in '0'..'9') i++
            }
            else -> return false
        }
        val suffix = value.substring(i)
        return when {
            suffix == "#" -> true
            suffix.isEmpty() -> true
            suffix.startsWith("/") -> !suffix.contains(REGEX_JSON_POINTER_INVALID_TILDE)
            else -> false
        }
    }

    /** RFC 6570 URI Template: a sequence of literals and brace-delimited expressions. */
    private fun isValidUriTemplate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '{' -> {
                    val close = value.indexOf('}', i)
                    if (close < 0) return false
                    if (!isValidUriTemplateExpression(value.substring(i + 1, close))) return false
                    i = close + 1
                }
                c == '}' -> return false
                c == '%' -> {
                    if (i + 2 >= value.length || !UriSyntax.isHexChar(value[i + 1]) || !UriSyntax.isHexChar(value[i + 2])) return false
                    i += 3
                }
                // RFC 6570 literals beyond ASCII are ucschar or iprivate, read by code point so that an unpaired
                // surrogate is rejected (#83)
                c.code > 0x7F -> {
                    val codePoint = value.codePointAt(i)
                    if (!UriSyntax.isUcschar(codePoint) && !UriSyntax.isIprivate(codePoint)) return false
                    i += Character.charCount(codePoint)
                }
                c.code <= 0x20 || c.code == 0x7F || c in URI_TEMPLATE_FORBIDDEN_LITERALS -> return false
                else -> i++
            }
        }
        return true
    }

    private fun isValidUriTemplateExpression(body: String): Boolean {
        if (body.isEmpty()) return false
        val varList = if (body[0] in URI_TEMPLATE_OPERATORS || body[0] in URI_TEMPLATE_RESERVED_OPERATORS) {
            // op-reserve is reserved for future extensions and cannot be used yet
            if (body[0] in URI_TEMPLATE_RESERVED_OPERATORS) return false
            body.substring(1)
        } else {
            body
        }
        return varList.isNotEmpty() && varList.split(',').all { isValidUriTemplateVarspec(it) }
    }

    private fun isValidUriTemplateVarspec(spec: String): Boolean {
        if (spec.isEmpty()) return false
        val colon = spec.indexOf(':')
        val name = when {
            colon >= 0 -> {
                // prefix modifier: 1-4 digits, 1-9999, no leading zero
                val maxLength = spec.substring(colon + 1)
                if (maxLength.isEmpty() || maxLength.length > 4) return false
                if (maxLength[0] !in '1'..'9' || maxLength.any { it !in '0'..'9' }) return false
                spec.substring(0, colon)
            }
            spec.endsWith('*') -> spec.dropLast(1)
            else -> spec
        }
        return isValidUriTemplateVarname(name)
    }

    private fun isValidUriTemplateVarname(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith('.') || name.endsWith('.') || ".." in name) return false
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                c == '%' -> {
                    if (i + 2 >= name.length || !UriSyntax.isHexChar(name[i + 1]) || !UriSyntax.isHexChar(name[i + 2])) return false
                    i += 3
                }
                c == '.' || c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> i++
                else -> return false
            }
        }
        return true
    }

    private fun isValidDuration(value: String): Boolean {
        if (!value.startsWith("P")) return false
        if (value.length == 1) return false
        if (value.any { it.code > 127 }) return false
        // Weeks-only: P<n>W (cannot be combined with other units)
        if (REGEX_DURATION_WEEKS.matches(value)) return true
        if ('W' in value) return false
        val rest = value.substring(1)
        val tIdx = rest.indexOf('T')
        val datePart = if (tIdx >= 0) rest.substring(0, tIdx) else rest
        val timePart = if (tIdx >= 0) rest.substring(tIdx + 1) else null
        if (timePart != null && timePart.isEmpty()) return false
        val hasDate = if (datePart.isNotEmpty()) {
            if (!REGEX_DURATION_DATE.matches(datePart)) return false
            true
        } else {
            false
        }
        val hasTime = if (timePart != null) {
            if (!REGEX_DURATION_TIME.matches(timePart)) return false
            true
        } else {
            false
        }
        return hasDate || hasTime
    }

    private val EMAIL_LOCAL_SPECIAL_CHARS = setOf('.', '!', '#', '$', '%', '&', '\'', '*', '+', '-', '/', '=', '?', '^', '_', '`', '{', '|', '}', '~')
    private val REGEX_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val REGEX_TIME = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$")
    private val REGEX_DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$")
    private val REGEX_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val REGEX_JSON_POINTER_INVALID_TILDE = Regex("~(?![01])")
    private val REGEX_DURATION_WEEKS = Regex("^P\\d+W$")

    // RFC 3339 Appendix A fixes the order too: a unit may only be followed by the next
    // smaller one, so P1Y2D (year then day) and PT1H2S (hour then second) are not durations
    private val REGEX_DURATION_DATE = Regex("^(\\d+Y(\\d+M(\\d+D)?)?|\\d+M(\\d+D)?|\\d+D)$")
    private val REGEX_DURATION_TIME = Regex("^(\\d+H(\\d+M(\\d+S)?)?|\\d+M(\\d+S)?|\\d+S)$")

    // ASCII characters RFC 6570 excludes from literals, besides controls and the delimiters { } % handled apart.
    // Not the apostrophe: Errata ID 6937 (verified) corrects %x26 / %x28-3B to %x26-3B, as the RFC's own '{var}'
    // example needs, and the official suite tests it.
    private val URI_TEMPLATE_FORBIDDEN_LITERALS = setOf(' ', '"', '<', '>', '^', '`', '|', '\\')
    private val URI_TEMPLATE_OPERATORS = setOf('+', '#', '.', '/', ';', '?', '&')
    private val URI_TEMPLATE_RESERVED_OPERATORS = setOf('=', ',', '!', '@', '|')
}
