package org.ktson

import java.math.BigDecimal
import java.math.BigInteger

private val FIVE: BigInteger = BigInteger.valueOf(5)

/**
 * Whether this number is an integer multiple of [divisor] (which must be positive), exactly.
 *
 * BigDecimal.remainder expands the quotient: "1e100000" against 3 allocates tens of megabytes, and
 * "1e2147483647" throws ArithmeticException (#70). Instead, this is a·10^-s and the divisor b·10^-t, so the
 * quotient is (a / b)·10^k with k = t - s. With b = 2^p·5^q·r (r coprime to 10), it is an integer iff r divides a
 * and a has at least p - k twos and q - k fives.
 * Trailing zeros are not stripped: stripping "100e2147483647" needs a scale below Int.MIN_VALUE and throws (#86).
 * The work is bounded by the digits of the numbers as written, never by their exponents.
 */
internal fun BigDecimal.isMultipleOf(divisor: BigDecimal): Boolean {
    require(divisor.signum() > 0) { "divisor must be positive" }
    if (signum() == 0) return true
    val k = divisor.scale().toLong() - scale().toLong()

    val a = unscaledValue().abs()
    var rest = divisor.unscaledValue()
    val twos = rest.lowestSetBit
    rest = rest.shiftRight(twos)
    var fives = 0
    while (rest.mod(FIVE).signum() == 0) {
        rest = rest.divide(FIVE)
        fives++
    }
    if (a.mod(rest).signum() != 0) return false
    if (a.lowestSetBit < twos - k) return false
    val missingFives = fives - k
    // a < 2^bitLength < 5^bitLength, so a cannot hold more fives than it has bits
    if (missingFives > a.bitLength()) return false
    return missingFives <= 0 || a.mod(FIVE.pow(missingFives.toInt())).signum() == 0
}
