package org.ktson

import java.math.BigDecimal
import java.math.BigInteger

private val FIVE: BigInteger = BigInteger.valueOf(5)

/**
 * Whether this number is an integer multiple of [divisor] (which must be positive), exactly.
 *
 * BigDecimal.remainder expands the quotient: "1e100000" against 3 allocates tens of megabytes, and
 * "1e2147483647" throws ArithmeticException (#70). Instead, with trailing zeros stripped, this is a·10^-s
 * and the divisor b·10^-t, so the quotient is (a / b)·10^k with k = t - s:
 * - k < 0: a has no factor 10 left to absorb 10^-k, so only zero is a multiple
 * - k >= 0: with b = 2^p·5^q·r (r coprime to 10), 10^k supplies min(k, p) twos and min(k, q) fives, so the
 *   quotient is an integer iff r divides a and a supplies the remaining twos and fives
 * The work is bounded by the digits of the numbers as written, never by their exponents.
 */
internal fun BigDecimal.isMultipleOf(divisor: BigDecimal): Boolean {
    require(divisor.signum() > 0) { "divisor must be positive" }
    if (signum() == 0) return true
    val value = stripTrailingZeros()
    val unit = divisor.stripTrailingZeros()
    val k = unit.scale().toLong() - value.scale().toLong()
    if (k < 0) return false

    val a = value.unscaledValue().abs()
    var rest = unit.unscaledValue()
    val twos = rest.lowestSetBit
    rest = rest.shiftRight(twos)
    var fives = 0
    while (rest.mod(FIVE).signum() == 0) {
        rest = rest.divide(FIVE)
        fives++
    }
    if (a.mod(rest).signum() != 0) return false
    val missingTwos = (twos - k).coerceAtLeast(0)
    val missingFives = (fives - k).coerceAtLeast(0).toInt()
    return a.lowestSetBit >= missingTwos && (missingFives == 0 || a.mod(FIVE.pow(missingFives)).signum() == 0)
}
