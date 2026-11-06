package org.ktson

/**
 * The ECMA 262 regular expression dialect JSON Schema specifies, on top of Java's engine: the syntax check behind
 * the `regex` format and the translation of ECMA 262 constructs Java reads differently, shared by that format and
 * the `pattern` keywords. Pure functions over immutable tables; compiled patterns are cached by the validator.
 */
internal object EcmaRegex {
    /**
     * The regex format is defined in terms of ECMA 262, which differs from Java both ways:
     * Java accepts constructs ECMA does not (inline flags, `\a`, `\A`) and rejects two it does
     * (the empty character classes, handled by [translateEcmaSyntax]).
     */
    fun isValidEcmaRegex(value: String): Boolean {
        if (!hasOnlyEcmaRegexSyntax(value)) return false
        return try {
            Regex(translatePatternToJava(value))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun translatePatternToJava(pattern: String): String {
        val withJavaCategories = pattern.replace(REGEX_UNICODE_CATEGORY) { match ->
            val flag = match.groupValues[1]
            // General_Category=Nd and gc=Nd name the category itself; Java knows neither prefix
            val name = match.groupValues[2].removePrefix("General_Category=").removePrefix("gc=")
            val javaName = UNICODE_CATEGORY_NAMES[name] ?: name
            "\\$flag{$javaName}"
        }
        return translateEcmaSyntax(withJavaCategories)
    }

    private fun hasOnlyEcmaRegexSyntax(pattern: String): Boolean {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1) ?: return false
                    if (next !in ECMA_REGEX_ESCAPES && next !in '1'..'9') return false
                    i += 2
                }
                c == '[' && !inClass -> {
                    inClass = true
                    i++
                }
                c == ']' && inClass -> {
                    inClass = false
                    i++
                }
                c == '(' && !inClass && pattern.startsWith("(?", i) -> {
                    // Inspected in place: copying the rest of the pattern for each group made the scan quadratic (#80)
                    val kind = pattern.getOrNull(i + 2)
                    val recognised = kind == ':' ||
                        kind == '=' ||
                        kind == '!' ||
                        pattern.startsWith("<=", i + 2) ||
                        pattern.startsWith("<!", i + 2) ||
                        (kind == '<' && pattern.getOrNull(i + 3)?.isLetter() == true)
                    if (!recognised) return false
                    i += 2
                }
                else -> i++
            }
        }
        return true
    }

    /**
     * Rewrites ECMA 262 syntax whose meaning differs in Java's engine (#69):
     * - `[]` (matches nothing) and `[^]` (matches anything) are errors in Java
     * - `\s` / `\S` are ECMA 262 WhiteSpace and LineTerminator (NBSP, BOM, U+2028 ...), not Java's ASCII set
     * - `\c` with a letter of either case is that letter's code modulo 32; Java computes it differently
     * - `$` is the end of input, while Java's also matches before a final line terminator
     * - `.` excludes only the four ECMA 262 line terminators; Java's also excludes U+0085
     */
    private fun translateEcmaSyntax(pattern: String): String {
        val result = StringBuilder(pattern.length)
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            val next = pattern.getOrNull(i + 1)
            when {
                c == '\\' && next == 's' -> {
                    result.append(if (inClass) ECMA_WHITESPACE else "[$ECMA_WHITESPACE]")
                    i += 2
                }
                // Inside a class too: Java unions a nested class, and [^\S] negates the whole union
                c == '\\' && next == 'S' -> {
                    result.append("[^$ECMA_WHITESPACE]")
                    i += 2
                }
                c == '\\' && next == 'c' && pattern.getOrNull(i + 2)?.let { it in 'a'..'z' || it in 'A'..'Z' } == true -> {
                    result.append("\\x%02X".format(pattern[i + 2].code % 32))
                    i += 3
                }
                c == '\\' -> {
                    result.append(c)
                    next?.let { result.append(it) }
                    i += 2
                }
                c == '$' && !inClass -> {
                    result.append("\\z")
                    i++
                }
                c == '.' && !inClass -> {
                    result.append("[^\\n\\r\\u2028\\u2029]")
                    i++
                }
                c == '[' && !inClass && pattern.startsWith("[^]", i) -> {
                    result.append("[\\s\\S]")
                    i += 3
                }
                c == '[' && !inClass && pattern.startsWith("[]", i) -> {
                    result.append("[^\\s\\S]")
                    i += 2
                }
                c == '[' && !inClass -> {
                    inClass = true
                    result.append(c)
                    i++
                }
                c == ']' && inClass -> {
                    inClass = false
                    result.append(c)
                    i++
                }
                else -> {
                    result.append(c)
                    i++
                }
            }
        }
        return result.toString()
    }

    private val REGEX_UNICODE_CATEGORY = Regex("""\\([pP])\{([^}]+)}""")

    // Escapes ECMA 262 recognises; Java additionally accepts \a, \A, \Z, \z, \G, \R, \h, \X ...
    private val ECMA_REGEX_ESCAPES =
        setOf(
            'f', 'n', 'r', 't', 'v', '0', 'x', 'u', 'c',
            'd', 'D', 's', 'S', 'w', 'W', 'b', 'B', 'k', 'p', 'P',
            '^', '$', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|', '/', '-',
        )

    /** ECMA 262 WhiteSpace and LineTerminator code points (what \s matches), in Java class syntax. */
    private const val ECMA_WHITESPACE = """\t\n\x0B\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"""

    // Maps ECMAScript Unicode category long names and aliases to Java short names for \p{} in patterns
    private val UNICODE_CATEGORY_NAMES =
        mapOf(
            "Cased_Letter" to "LC",
            "Combining_Mark" to "M",
            "digit" to "Nd",
            "punct" to "P",
            "cntrl" to "Cc",
            "Letter" to "L",
            "Lowercase_Letter" to "Ll",
            "Uppercase_Letter" to "Lu",
            "Titlecase_Letter" to "Lt",
            "Modifier_Letter" to "Lm",
            "Other_Letter" to "Lo",
            "Mark" to "M",
            "Nonspacing_Mark" to "Mn",
            "Spacing_Mark" to "Mc",
            "Enclosing_Mark" to "Me",
            "Number" to "N",
            "Decimal_Number" to "Nd",
            "Letter_Number" to "Nl",
            "Other_Number" to "No",
            "Punctuation" to "P",
            "Connector_Punctuation" to "Pc",
            "Dash_Punctuation" to "Pd",
            "Open_Punctuation" to "Ps",
            "Close_Punctuation" to "Pe",
            "Initial_Punctuation" to "Pi",
            "Final_Punctuation" to "Pf",
            "Other_Punctuation" to "Po",
            "Symbol" to "S",
            "Math_Symbol" to "Sm",
            "Currency_Symbol" to "Sc",
            "Modifier_Symbol" to "Sk",
            "Other_Symbol" to "So",
            "Separator" to "Z",
            "Space_Separator" to "Zs",
            "Line_Separator" to "Zl",
            "Paragraph_Separator" to "Zp",
            "Other" to "C",
            "Control" to "Cc",
            "Format" to "Cf",
            "Surrogate" to "Cs",
            "Private_Use" to "Co",
            "Unassigned" to "Cn",
        )
}
