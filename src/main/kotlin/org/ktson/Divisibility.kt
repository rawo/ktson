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
    val unit = divisor.unscaledValue()
    val twos = unit.lowestSetBit
    val (fives, rest) = unit.shiftRight(twos).splitFives()
    if (a.mod(rest).signum() != 0) return false
    if (a.lowestSetBit < twos - k) return false
    val missingFives = fives - k
    // a < 2^bitLength < 5^bitLength, so a cannot hold more fives than it has bits
    if (missingFives > a.bitLength()) return false
    return missingFives <= 0 || a.mod(FIVE.pow(missingFives.toInt())).signum() == 0
}

/**
 * Splits this positive number into 5^count · rest, with rest not divisible by five. Dividing by five once per factor
 * made 5^16000 cost 82 MB and 100 ms (#87); dividing by 5, 5^2, 5^4, ... while they divide, then by the same powers
 * from the largest down, takes O(log count) divisions.
 */
private fun BigInteger.splitFives(): Pair<Int, BigInteger> {
    var rest = this
    var count = 0
    val powers = mutableListOf(FIVE)
    while (true) {
        val (quotient, remainder) = rest.divideAndRemainder(powers.last())
        if (remainder.signum() != 0) break
        rest = quotient
        count += 1 shl (powers.size - 1)
        powers.add(powers.last().multiply(powers.last()))
    }
    // Fewer than 2^(powers.size - 1) fives are left: take each power at most once
    for (i in powers.indices.reversed()) {
        val (quotient, remainder) = rest.divideAndRemainder(powers[i])
        if (remainder.signum() == 0) {
            rest = quotient
            count += 1 shl i
        }
    }
    return count to rest
}
