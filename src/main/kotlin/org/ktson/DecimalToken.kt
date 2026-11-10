package org.ktson

/**
 * A decimal number token as sign, significant digits and exponent: [text] from [digitsStart] until [digitsEnd] times
 * ten to [exponent], with no leading or trailing zeros in the digits (none at all for zero). Two tokens have the same
 * value exactly when these agree, so comparing them is linear in the digits; converting to BigDecimal is quadratic (#107).
 */
internal class DecimalToken private constructor(
    private val negative: Boolean,
    private val text: String,
    private val digitsStart: Int,
    private val digitsEnd: Int,
    private val exponent: Long,
) {
    private val isZero get() = digitsStart == digitsEnd

    fun sameValue(other: DecimalToken): Boolean {
        if (isZero || other.isZero) return isZero && other.isZero
        val length = digitsEnd - digitsStart
        return negative == other.negative &&
            exponent == other.exponent &&
            length == other.digitsEnd - other.digitsStart &&
            text.regionMatches(digitsStart, other.text, other.digitsStart, length)
    }

    companion object {
        /** Exponent digits beyond this overflow a Long, which BigDecimal rejects. */
        private const val MAX_EXPONENT_DIGITS = 18

        /**
         * Reads [text] in the grammar `toBigDecimalOrNull()` accepts: an optional sign, digits with at most one point
         * (at least one digit), and an optional exponent. Null for anything else, and for a number BigDecimal rejects
         * because its scale (fraction digits minus exponent) is outside Int range, so equality stays what it was.
         */
        fun parse(text: String): DecimalToken? {
            var i = 0
            val negative = text.startsWith('-')
            if (negative || text.startsWith('+')) i++
            val mantissaStart = i
            var point = -1
            while (i < text.length && (text[i] in '0'..'9' || (text[i] == '.' && point < 0))) {
                if (text[i] == '.') point = i
                i++
            }
            val mantissaEnd = i
            val fractionDigits = if (point < 0) 0 else mantissaEnd - point - 1
            if (mantissaEnd - mantissaStart - (if (point < 0) 0 else 1) == 0) return null

            var exponent = 0L
            if (i < text.length) {
                if (text[i] != 'e' && text[i] != 'E') return null
                i++
                val exponentNegative = i < text.length && text[i] == '-'
                if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
                if (i == text.length) return null
                while (i < text.length && text[i] == '0') i++
                val significantStart = i
                while (i < text.length) {
                    if (text[i] !in '0'..'9') return null
                    i++
                }
                if (i - significantStart > MAX_EXPONENT_DIGITS) return null
                if (i > significantStart) exponent = text.substring(significantStart, i).toLong()
                if (exponentNegative) exponent = -exponent
            }
            val scale = fractionDigits - exponent
            if (scale !in Int.MIN_VALUE..Int.MAX_VALUE) return null

            // Significant digits: skip leading zeros (and the point), drop trailing zeros into the exponent
            var start = mantissaStart
            while (start < mantissaEnd && (text[start] == '0' || text[start] == '.')) start++
            var end = mantissaEnd
            var dropped = 0L
            while (end > start && (text[end - 1] == '0' || text[end - 1] == '.')) {
                if (text[end - 1] == '0') dropped++
                end--
            }
            if (start == end) return DecimalToken(negative, text, 0, 0, 0)
            // The digits may still contain the point, as in 1.5: keep only digits in the compared text
            val digits = if (point in start until end) text.substring(start, point) + text.substring(point + 1, end) else null
            val adjusted = exponent - fractionDigits + dropped
            return if (digits != null) DecimalToken(negative, digits, 0, digits.length, adjusted) else DecimalToken(negative, text, start, end, adjusted)
        }
    }
}
