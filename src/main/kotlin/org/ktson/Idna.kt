package org.ktson

/**
 * Internationalized domain names for the `hostname`, `idn-hostname`, `email` and `idn-email` formats: RFC 3492
 * Punycode, the RFC 5890-5893 IDNA label rules (hyphens, code point categories, contextual and Bidi rules) and the
 * UTS 46 mapping. Pure functions over immutable tables; nothing here depends on JSON Schema.
 */
internal object Idna {
    fun isValidHostname(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_HOSTNAME_LENGTH) return false
        if (value.any { it.code > 127 }) return false
        val labels = value.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_LENGTH || !it.matches(REGEX_HOSTNAME_LABEL) }) return false

        // An A-label is only a hostname if its Punycode decodes to a valid U-label
        val decoded = labels.map { label ->
            if (label.startsWith(ACE_PREFIX, ignoreCase = true)) {
                val lower = label.lowercase()
                val uLabel = punycodeDecode(lower.substring(ACE_PREFIX.length)) ?: return false
                if (ACE_PREFIX + punycodeEncode(uLabel) != lower) return false
                uLabel
            } else {
                label
            }
        }
        val bidiDomain = decoded.any { label -> label.codePoints().anyMatch { isRtlCodePoint(it) } }
        return decoded.withIndex().all { (i, uLabel) ->
            if (uLabel == labels[i]) {
                // A plain ASCII label keeps the RFC 1123 rules it already passed, but in a Bidi domain the Bidi rule
                // applies to it as well (RFC 5893 §2), as idn-hostname requires (#82)
                !bidiDomain || satisfiesBidiRule(uLabel.codePoints().toArray())
            } else {
                isValidULabel(uLabel, bidiDomain)
            }
        }
    }

    fun isValidIdnHostname(value: String): Boolean {
        if (value.isEmpty()) return false
        val mapped = idnaMap(value)
        val labels = mapped.split('.', '。')
        if (labels.any { it.isEmpty() }) return false

        val uLabels = labels.map { label ->
            // A label longer than 63 characters cannot be one, whatever it decodes to; checked before any Punycode
            // work, which grows quadratically with the label (#79). Mapping has already dropped ignored characters.
            if (label.length > MAX_LABEL_LENGTH && label.codePointCount(0, label.length) > MAX_LABEL_LENGTH) return false
            if (label.startsWith(ACE_PREFIX)) {
                val uLabel = punycodeDecode(label.substring(ACE_PREFIX.length)) ?: return false
                // A-labels must be canonical: re-encoding has to reproduce the label exactly
                if (ACE_PREFIX + punycodeEncode(uLabel) != label) return false
                uLabel
            } else {
                label
            }
        }
        // Both length limits are defined on the A-label form of each label
        val encodedLengths = uLabels.map { uLabel ->
            val aLabel = if (uLabel.all { it.code < 128 }) uLabel else ACE_PREFIX + punycodeEncode(uLabel)
            if (aLabel.length > MAX_LABEL_LENGTH) return false
            aLabel.length
        }
        if (encodedLengths.sum() + encodedLengths.size - 1 > MAX_HOSTNAME_LENGTH) return false

        val bidiDomain = uLabels.any { label -> label.codePoints().anyMatch { isRtlCodePoint(it) } }
        return uLabels.all { isValidULabel(it, bidiDomain) }
    }

    /**
     * UTS 46 mapping: drop the ignorable code points, then normalise and case-fold, so that
     * fullwidth and uppercase spellings of a name are accepted as the name itself.
     */
    private fun idnaMap(value: String): String {
        val withoutIgnored = buildString {
            value.codePoints().forEach { cp -> if (!isIdnaIgnored(cp)) appendCodePoint(cp) }
        }
        return java.text.Normalizer.normalize(withoutIgnored, java.text.Normalizer.Form.NFKC).lowercase()
    }

    private fun isIdnaIgnored(cp: Int): Boolean = cp == 0x00AD || // SOFT HYPHEN
            cp == 0x034F || // COMBINING GRAPHEME JOINER
            cp == 0x200B || // ZERO WIDTH SPACE
            cp == 0x2060 || // WORD JOINER
            cp == 0xFEFF || // ZERO WIDTH NO-BREAK SPACE
            cp in 0x180B..0x180D || // MONGOLIAN FREE VARIATION SELECTORS
            cp in 0xFE00..0xFE0F || // VARIATION SELECTORS
            cp in 0xE0100..0xE01EF // VARIATION SELECTORS SUPPLEMENT

    /**
     * Validates a Unicode label against the parts of RFC 5892 and RFC 5893 that can be
     * decided without the full IDNA derived-property table: the hyphen rules, the code
     * point categories, the contextual rules of Appendix A, and the Bidi rule.
     */
    private fun isValidULabel(label: String, bidiDomain: Boolean): Boolean {
        if (label.isEmpty()) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
        if (label.length >= 4 && label[2] == '-' && label[3] == '-') return false

        val codePoints = label.codePoints().toArray()
        val firstType = Character.getType(codePoints[0])
        if (firstType == Character.NON_SPACING_MARK.toInt() ||
            firstType == Character.COMBINING_SPACING_MARK.toInt() ||
            firstType == Character.ENCLOSING_MARK.toInt()
        ) {
            return false
        }
        if (codePoints.any { !isAllowedIdnCodePoint(it) }) return false
        if (!satisfiesContextualRules(codePoints)) return false
        return !bidiDomain || satisfiesBidiRule(codePoints)
    }

    private fun isAllowedIdnCodePoint(cp: Int): Boolean {
        if (cp == ZWNJ || cp == ZWJ) return true // CONTEXTJ, decided by the contextual rules
        if (cp in IDNA_CONTEXT_O_CHARS) return true // CONTEXTO, likewise
        if (cp in IDNA_DISALLOWED_CODE_POINTS) return false
        if (cp in IDNA_PVALID_EXCEPTIONS) return true
        if (cp == '-'.code) return true
        return when (Character.getType(cp)) {
            Character.LOWERCASE_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(),
            Character.MODIFIER_LETTER.toInt(),
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            -> true
            else -> false
        }
    }

    /** RFC 5892 Appendix A: the CONTEXTJ and CONTEXTO rules. */
    private fun satisfiesContextualRules(codePoints: IntArray): Boolean {
        var hasArabicIndic = false
        var hasExtendedArabicIndic = false
        codePoints.forEachIndexed { i, cp ->
            val previous = codePoints.getOrNull(i - 1)
            val next = codePoints.getOrNull(i + 1)
            when {
                // ZERO WIDTH JOINER: only after a Virama
                cp == ZWJ -> if (previous == null || !isVirama(previous)) return false
                // ZERO WIDTH NON-JOINER: after a Virama, or inside an Arabic-style joining sequence
                cp == ZWNJ ->
                    if ((previous == null || !isVirama(previous)) && !isJoiningContext(codePoints, i)) return false
                // MIDDLE DOT: only between two 'l's
                cp == 0x00B7 -> if (previous != 'l'.code || next != 'l'.code) return false
                // GREEK LOWER NUMERAL SIGN: must be followed by Greek
                cp == 0x0375 -> if (next == null || Character.UnicodeScript.of(next) != Character.UnicodeScript.GREEK) return false
                // HEBREW GERESH and GERSHAYIM: must be preceded by Hebrew
                cp == 0x05F3 || cp == 0x05F4 ->
                    if (previous == null || Character.UnicodeScript.of(previous) != Character.UnicodeScript.HEBREW) return false
                // KATAKANA MIDDLE DOT: the label must carry Hiragana, Katakana or Han
                cp == 0x30FB ->
                    if (codePoints.none { other ->
                            Character.UnicodeScript.of(other) in KATAKANA_MIDDLE_DOT_SCRIPTS
                        }
                    ) {
                        return false
                    }
                cp in 0x0660..0x0669 -> hasArabicIndic = true
                cp in 0x06F0..0x06F9 -> hasExtendedArabicIndic = true
            }
        }
        // The two Arabic-Indic digit blocks may not be mixed within one label
        return !(hasArabicIndic && hasExtendedArabicIndic)
    }

    /**
     * RFC 5892 rule B for ZERO WIDTH NON-JOINER: a joining or dual-joining character
     * before it and a right-joining or dual-joining one after, ignoring transparent marks.
     */
    private fun isJoiningContext(codePoints: IntArray, index: Int): Boolean {
        var before = index - 1
        while (before >= 0 && isJoiningTransparent(codePoints[before])) before--
        var after = index + 1
        while (after < codePoints.size && isJoiningTransparent(codePoints[after])) after++
        if (before < 0 || after >= codePoints.size) return false
        return isJoiningLetter(codePoints[before]) && isJoiningLetter(codePoints[after])
    }

    private fun isJoiningTransparent(cp: Int): Boolean = Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(cp) == Character.ENCLOSING_MARK.toInt() ||
            Character.getType(cp) == Character.FORMAT.toInt()

    /** Cursive scripts whose letters carry a joining type of L, R or D. */
    private fun isJoiningLetter(cp: Int): Boolean = Character.isLetter(cp) && Character.UnicodeScript.of(cp) in CURSIVE_SCRIPTS

    private fun isVirama(cp: Int): Boolean = cp in VIRAMA_CODE_POINTS

    private fun isRtlCodePoint(cp: Int): Boolean = when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_ARABIC_NUMBER,
            -> true
            else -> false
        }

    /** RFC 5893: every label of a domain that contains any RTL character must satisfy this. */
    private fun satisfiesBidiRule(codePoints: IntArray): Boolean {
        val first = Character.getDirectionality(codePoints[0])
        val rtl = first == Character.DIRECTIONALITY_RIGHT_TO_LEFT || first == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        if (!rtl && first != Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false

        val allowed = if (rtl) BIDI_RTL_ALLOWED else BIDI_LTR_ALLOWED
        val trailing = if (rtl) BIDI_RTL_TRAILING else BIDI_LTR_TRAILING
        if (codePoints.any { Character.getDirectionality(it) !in allowed }) return false

        val last = codePoints.last { Character.getDirectionality(it) != Character.DIRECTIONALITY_NONSPACING_MARK }
        if (Character.getDirectionality(last) !in trailing) return false

        // An RTL label may carry European or Arabic numbers, but not both
        if (rtl) {
            val hasEuropean = codePoints.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_EUROPEAN_NUMBER }
            val hasArabic = codePoints.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_ARABIC_NUMBER }
            if (hasEuropean && hasArabic) return false
        }
        return true
    }

    /** RFC 3492 Punycode decoding. Returns null when the input is not valid Punycode. */
    private fun punycodeDecode(input: String): String? {
        var n = PUNYCODE_INITIAL_N
        var i = 0
        var bias = PUNYCODE_INITIAL_BIAS
        val output = mutableListOf<Int>()

        val lastHyphen = input.lastIndexOf('-')
        if (lastHyphen >= 0) {
            for (c in input.substring(0, lastHyphen)) {
                if (c.code > 127) return null
                output.add(c.code)
            }
        }

        var pos = if (lastHyphen >= 0) lastHyphen + 1 else 0
        if (pos >= input.length) return null
        while (pos < input.length) {
            val previousI = i
            var weight = 1
            var k = PUNYCODE_BASE
            while (true) {
                if (pos >= input.length) return null
                val digit = punycodeDigit(input[pos]) ?: return null
                pos++
                if (digit > (Int.MAX_VALUE - i) / weight) return null
                i += digit * weight
                val t = when {
                    k <= bias -> PUNYCODE_TMIN
                    k >= bias + PUNYCODE_TMAX -> PUNYCODE_TMAX
                    else -> k - bias
                }
                if (digit < t) break
                if (weight > Int.MAX_VALUE / (PUNYCODE_BASE - t)) return null
                weight *= PUNYCODE_BASE - t
                k += PUNYCODE_BASE
            }
            bias = punycodeAdapt(i - previousI, output.size + 1, previousI == 0)
            if (i / (output.size + 1) > Int.MAX_VALUE - n) return null
            n += i / (output.size + 1)
            i %= output.size + 1
            if (n < 0x80) return null // a basic code point may not be encoded in the extended part
            if (!Character.isValidCodePoint(n)) return null
            output.add(i, n)
            i++
        }
        return buildString { output.forEach { appendCodePoint(it) } }
    }

    /** RFC 3492 Punycode encoding of a Unicode label (without the ACE prefix). */
    private fun punycodeEncode(input: String): String {
        val codePoints = input.codePoints().toArray()
        val output = StringBuilder()
        for (cp in codePoints) if (cp < 0x80) output.appendCodePoint(cp)
        val basicCount = output.length
        if (basicCount > 0) output.append('-')

        var handled = basicCount
        var n = PUNYCODE_INITIAL_N
        var delta = 0
        var bias = PUNYCODE_INITIAL_BIAS
        while (handled < codePoints.size) {
            // The smallest code point not yet handled, found without allocating a filtered copy each round (#79)
            var m = Int.MAX_VALUE
            for (cp in codePoints) if (cp in n until m) m = cp
            delta += (m - n) * (handled + 1)
            n = m
            for (cp in codePoints) {
                if (cp < n) delta++
                if (cp == n) {
                    var q = delta
                    var k = PUNYCODE_BASE
                    while (true) {
                        val t = when {
                            k <= bias -> PUNYCODE_TMIN
                            k >= bias + PUNYCODE_TMAX -> PUNYCODE_TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        output.append(punycodeChar(t + (q - t) % (PUNYCODE_BASE - t)))
                        q = (q - t) / (PUNYCODE_BASE - t)
                        k += PUNYCODE_BASE
                    }
                    output.append(punycodeChar(q))
                    bias = punycodeAdapt(delta, handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    private fun punycodeAdapt(delta: Int, numPoints: Int, firstTime: Boolean): Int {
        var d = if (firstTime) delta / PUNYCODE_DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((PUNYCODE_BASE - PUNYCODE_TMIN) * PUNYCODE_TMAX) / 2) {
            d /= PUNYCODE_BASE - PUNYCODE_TMIN
            k += PUNYCODE_BASE
        }
        return k + (PUNYCODE_BASE - PUNYCODE_TMIN + 1) * d / (d + PUNYCODE_SKEW)
    }

    private fun punycodeDigit(c: Char): Int? = when (c) {
            in 'a'..'z' -> c - 'a'
            in 'A'..'Z' -> c - 'A'
            in '0'..'9' -> c - '0' + 26
            else -> null
        }

    private fun punycodeChar(digit: Int): Char = if (digit < 26) 'a' + digit else '0' + (digit - 26)

    private val REGEX_HOSTNAME_LABEL = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?")

    private const val ACE_PREFIX = "xn--"
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_HOSTNAME_LENGTH = 253
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    private const val PUNYCODE_BASE = 36
    private const val PUNYCODE_TMIN = 1
    private const val PUNYCODE_TMAX = 26
    private const val PUNYCODE_SKEW = 38
    private const val PUNYCODE_DAMP = 700
    private const val PUNYCODE_INITIAL_BIAS = 72
    private const val PUNYCODE_INITIAL_N = 128

    // RFC 5892 Section 2.6: PVALID despite their code point category
    private val IDNA_PVALID_EXCEPTIONS =
        setOf(
            0x00DF, // LATIN SMALL LETTER SHARP S
            0x03C2, // GREEK SMALL LETTER FINAL SIGMA
            0x06FD, // ARABIC SIGN SINDHI AMPERSAND
            0x06FE, // ARABIC SIGN SINDHI POSTPOSITION MEN
            0x0F0B, // TIBETAN MARK INTERSYLLABIC TSHEG
            0x3007, // IDEOGRAPHIC NUMBER ZERO
        )

    // CONTEXTO code points: allowed only where their rule in RFC 5892 Appendix A holds
    private val IDNA_CONTEXT_O_CHARS = setOf(0x00B7, 0x0375, 0x05F3, 0x05F4, 0x30FB)

    private val KATAKANA_MIDDLE_DOT_SCRIPTS =
        setOf(
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HAN,
        )

    // Scripts written cursively, whose letters join to their neighbours
    private val CURSIVE_SCRIPTS =
        setOf(
            Character.UnicodeScript.ARABIC,
            Character.UnicodeScript.SYRIAC,
            Character.UnicodeScript.NKO,
            Character.UnicodeScript.MANDAIC,
            Character.UnicodeScript.MANICHAEAN,
            Character.UnicodeScript.PSALTER_PAHLAVI,
            Character.UnicodeScript.HANIFI_ROHINGYA,
            Character.UnicodeScript.SOGDIAN,
            Character.UnicodeScript.ADLAM,
        )

    // Combining class 9 (Virama), which licenses a following ZWJ or ZWNJ
    private val VIRAMA_CODE_POINTS =
        setOf(
            0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D3B, 0x0D3C,
            0x0D4D, 0x0DCA, 0x0E3A, 0x0EBA, 0x0F84, 0x1039, 0x103A, 0x1714, 0x1734, 0x17D2,
            0x1A60, 0x1B44, 0x1BAA, 0x1BAB, 0x1BF2, 0x1BF3, 0x2D7F, 0xA806, 0xA82C, 0xA8C4,
            0xA953, 0xA9C0, 0xAAF6, 0xABED, 0x10A3F, 0x11046, 0x1107F, 0x110B9, 0x111C0,
            0x11235, 0x1134D, 0x11442, 0x114C2, 0x115BF, 0x1163F, 0x116B6, 0x1172B, 0x11839,
            0x119E0, 0x11A34, 0x11A47, 0x11A99, 0x11C3F, 0x11D44, 0x11D45, 0x11D97,
        )

    private val BIDI_RTL_ALLOWED =
        setOf(
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_ARABIC_NUMBER,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
            Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_OTHER_NEUTRALS,
            Character.DIRECTIONALITY_BOUNDARY_NEUTRAL,
            Character.DIRECTIONALITY_NONSPACING_MARK,
        )

    private val BIDI_LTR_ALLOWED =
        setOf(
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
            Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_OTHER_NEUTRALS,
            Character.DIRECTIONALITY_BOUNDARY_NEUTRAL,
            Character.DIRECTIONALITY_NONSPACING_MARK,
        )

    private val BIDI_RTL_TRAILING =
        setOf(
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER,
            Character.DIRECTIONALITY_ARABIC_NUMBER,
        )

    private val BIDI_LTR_TRAILING =
        setOf(
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER,
        )

    // Characters DISALLOWED in IDN labels per RFC 5892
    private val IDNA_DISALLOWED_CODE_POINTS =
        setOf(
            0x0640, // ARABIC TATWEEL
            0x07FA, // NKO LAJANYALAN
            0x302E, // HANGUL SINGLE DOT TONE MARK
            0x302F, // HANGUL DOUBLE DOT TONE MARK
            0x3031, // VERTICAL KANA REPEAT MARK
            0x3032, // VERTICAL KANA REPEAT WITH VOICED ITERATION MARK
            0x3033, // VERTICAL KANA REPEAT MARK UPPER HALF
            0x3034, // VERTICAL KANA REPEAT WITH VOICED ITERATION MARK UPPER HALF
            0x3035, // VERTICAL KANA REPEAT MARK LOWER HALF
            0x303B, // VERTICAL IDEOGRAPHIC ITERATION MARK
        )
}
