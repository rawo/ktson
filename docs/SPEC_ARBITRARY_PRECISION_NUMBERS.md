# Spec: arbitrary-precision number handling

**Status**: proposed, not implemented
**Kind**: correctness defect, not an enhancement
**Depends on**: nothing

## Motivation

Every numeric comparison in the validator goes through `Double`. JSON itself places no limit on
the magnitude or precision of a number, so any value needing more than 53 bits of mantissa is
compared as a rounded approximation. The result is **silent false negatives**: instances that
violate a schema are reported valid.

These were each reproduced against the current implementation:

| Schema | Instance | Correct | KtSON says |
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

The official suite does not catch any of it, because these cases live in `optional/bignum.json`,
which the runner skips.

## Current behaviour

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

Replace `Double` with `java.math.BigDecimal`, built from the token text that kotlinx-serialization
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

1. **Fast path**: if both operands parse as `Long` (no `.`, `e` or `E` in either token and within
   `Long` range), compare as `Long`. This covers the overwhelming majority of real schemas at no
   precision cost, and only genuinely wide or fractional values reach `BigDecimal`.
2. Cache the parsed limit per schema object if profiling shows the schema side dominating.

`PerformanceTest` must be run before and after; treat a regression beyond ~10% on the numeric
benchmarks as a blocker for the naive version and a reason to implement the fast path first.

## Test plan

- **Un-skip `optional/bignum.json`** in `OfficialTestSuiteRunner` — 9 assertions per draft that
  directly cover this. This is the acceptance criterion.
- Unit tests for each row of the table in *Motivation*.
- `1.0` vs `1` equality for `const`, `enum` and `uniqueItems` must keep passing (the existing suite
  covers this; do not regress it while making 2^53+1 distinct).
- `PerformanceTest` before/after comparison recorded in the PR.

## Effort

Medium. The change itself is mechanical and touches roughly 15 sites in one file, but it alters
comparison semantics across four keyword families, so the whole suite is the safety net — and the
performance question needs an answer, not an assumption.

## References

- JSON Schema Core 2020-12 §4.2.1 (instance equality is mathematical, not representational)
- `JSON-Schema-Test-Suite/tests/draft2020-12/optional/bignum.json`
- `src/main/kotlin/org/ktson/JsonValidator.kt` — `validateNumber`, `validateType`, the equality helper
