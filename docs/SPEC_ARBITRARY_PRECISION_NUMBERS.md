# Spec: arbitrary-precision number handling

**Status**: implemented in 3.0.0
**Kind**: correctness defect, not an enhancement
**Depends on**: nothing

## Motivation

Previously, numeric comparisons in the validator went through `Double`. JSON itself places no limit on
the magnitude or precision of a number, so any value needing more than 53 bits of mantissa is
compared as a rounded approximation. The result is **silent false negatives**: instances that
violate a schema are reported valid.

These were each reproduced against the previous implementation and are now regression tests:

| Schema | Instance | Correct | Previous verdict |
|---|---|---|---|
| `{"minimum": 9007199254740993}` | `9007199254740992` | invalid | **valid** |
| `{"maximum": 9007199254740992}` | `9007199254740993` | invalid | **valid** |
| `{"const": 9007199254740993}` | `9007199254740992` | invalid | **valid** |
| `{"enum": [9007199254740993]}` | `9007199254740992` | invalid | **valid** |
| `{"type": "integer"}` | `1e400` | valid | **invalid** |

The first four all collapse to 2^53 as doubles. The last overflows to `Double.POSITIVE_INFINITY`,
whose fractional-part check fails, so a legitimate big integer is rejected as a non-integer.

This is not hypothetical territory: 64-bit identifiers (snowflake IDs, Twitter/Discord IDs, database
bigints) exceed 2^53 routinely, and a validator that quietly passes a wrong ID is worse than one
that has no numeric support at all.

The official runner now includes `optional/bignum.json` for both drafts (18 assertions total).

## Previous behaviour

`JsonValidator.kt`, the sites that matter:

| Line (approx.) | Code | Used for |
|---|---|---|
| 1661 | `val number = instance.doubleOrNull ?: return` | all numeric keywords |
| 1666–1730 | `schema[MINIMUM]?.jsonPrimitive?.doubleOrNull` etc. | minimum, maximum, exclusive*, multipleOf |
| 457 | `element.doubleOrNull` + fractional-part test | `type: integer` |
| 490–491 | `a.doubleOrNull` / `b.doubleOrNull` | `const`, `enum`, `uniqueItems` equality |

Note that messages already print the literals as written, so only the comparisons are affected —
the display side needs no change.

## Design

The implementation replaces `Double` with `java.math.BigDecimal`, built from the token text that kotlinx-serialization
preserves on `JsonPrimitive.content`.

```kotlin
/** The instance's numeric value at full precision, or null when it is not a number. */
private fun JsonPrimitive.decimalOrNull(): BigDecimal? =
    if (isString) null else content.toBigDecimalOrNull()
```

Then:

- **Bounds** — `value < limit` becomes `value.compareTo(limit) < 0`. Note `BigDecimal.equals`
  distinguishes `1.0` from `1`; every comparison must use `compareTo`, never `==`.
- **`type: integer`** — `value.stripTrailingZeros().scale() <= 0`, which is exact for any magnitude
  and needs no infinity special case.
- **`multipleOf`** — `value.remainder(multipleOf).compareTo(BigDecimal.ZERO) == 0`. This also fixes
  the classic `0.0075 / 0.0001` style rounding, which currently happens to land correctly by luck.
- **Equality for `const`/`enum`/`uniqueItems`** — compare numerics with `compareTo`, so `1.0` and
  `1` are the same number, as JSON Schema requires, while `9007199254740992` and
  `9007199254740993` stay distinct.

`toBigDecimalOrNull()` returns null for `Infinity`/`NaN` tokens, which are not valid JSON anyway.

### Performance

`BigDecimal` is materially slower than `Double`, and numeric keywords sit on the hot path. Two
mitigations, in order of preference:

1. **Implemented fast path**: if both operands parse as `Long` (no `.`, `e` or `E` in either token and within
   `Long` range), compare as `Long`. This covers the overwhelming majority of real schemas at no
   precision cost, and only genuinely wide or fractional values reach `BigDecimal`.
2. **Implemented schema-limit cache**: decimal schema limits are cached by literal in a
   `ConcurrentHashMap`. Instance values are not cached, so an input stream of distinct numbers
   cannot grow the cache. Like the existing pattern cache, it lives for the validator's lifetime.

The naive all-`BigDecimal` approach was avoided because the original plan treated regressions
beyond roughly 10% as a reason to implement the integer fast path first. Exact decimal arithmetic
still has a measurable cost; the benchmark below records that tradeoff explicitly.

### Benchmark comparison

Compared with commit `8977ff1` in an isolated temporary checkout, using the same `PerformanceTest`
on the same machine (Gradle 9.1.0, Java 21 toolchain). Each numeric case validates 20,000 pre-parsed
values, warms up five times, then reports the median of seven samples of three validations each.
These are local timing observations, not enforced timing assertions or a JMH study.

| Numeric workload | Previous `Double` | Exact arithmetic with cache | Change |
|---|---:|---:|---:|
| Integer bounds and `multipleOf` | 4.25 ms | 2.98 ms | 30% faster |
| Decimal bounds and `multipleOf` | 4.21 ms | 5.36 ms | 27% slower |
| Integer bounds beyond `Long` | 11.77 ms | 10.54 ms | 10% faster |

The table uses the final verification run. An earlier cached run measured 2.72 ms, 7.27 ms and
9.88 ms respectively; before caching, the decimal and wide-integer cases measured 7.60 ms and
14.42 ms. The decimal workload remains slower because it uses exact remainder arithmetic instead of a
floating-point tolerance. All six existing large-data and concurrency performance checks also
pass. Individual short timings vary substantially between runs; correctness is asserted, timing
is reported for comparison.

### Representational limits

Precision is limited by `BigDecimal` and JVM memory, rather than IEEE 754's 53-bit mantissa.
`BigDecimal` uses a signed 32-bit scale; tokens whose exponents exceed that representable range
are outside this implementation's numeric support. The existing cardinality keywords
(`minLength`, `maxItems`, etc.) still convert their limits to `Int`; this change covers numeric
values, comparisons and equality, not those collection-size limits.

## Verification

- **Enabled `optional/bignum.json`** in `OfficialTestSuiteRunner` — all 9 assertions per draft pass.
- `NumericPrecisionTest`: 43 regression cases per draft plus one concurrency test per draft,
  covering every motivation example, signed and wide integers, fractions, exponents, exact
  multiples, nested equality, and numeric strings distinct from numbers (88 tests total).
- `1.0` vs `1` equality for `const`, `enum` and `uniqueItems` must keep passing (the existing suite
  covers this; do not regress it while making 2^53+1 distinct).
- `PerformanceTest`: three numeric benchmarks plus all six existing performance checks pass.
- Full standard suite: 4,314 official assertions and 593 custom tests; ktlint check passes.

## Effort

Medium. The change itself is mechanical and touches roughly 15 sites in one file, but it alters
comparison semantics across four keyword families, so the whole suite is the safety net — and the
performance question needs an answer, not an assumption.

## References

- JSON Schema Core 2020-12 §4.2.1 (instance equality is mathematical, not representational)
- `JSON-Schema-Test-Suite/tests/draft2020-12/optional/bignum.json`
- `src/main/kotlin/org/ktson/JsonValidator.kt` — `validateNumber`, `validateType`, the equality helper
