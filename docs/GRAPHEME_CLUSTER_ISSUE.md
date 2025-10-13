# Unicode Grapheme Cluster Issue - Detailed Explanation

## The Problem

The KtSON validator is failing 4 tests related to Unicode grapheme clusters in `minLength` and `maxLength` validation.

## Example Test Cases

### Test 1: minLength Failure

**Schema:**
```json
{
  "$schema": "https://json-schema.org/draft/2019-09/schema",
  "minLength": 2
}
```

**Test Data:** `"💩"` (single pile-of-poo emoji, U+1F4A9)

**Expected:** INVALID (1 codepoint < minLength of 2)  
**Our Result:** VALID ❌ (incorrectly counts as 2)

### Test 2: maxLength Failure

**Schema:**
```json
{
  "$schema": "https://json-schema.org/draft/2019-09/schema",
  "maxLength": 2
}
```

**Test Data:** `"💩💩"` (two pile-of-poo emojis)

**Expected:** VALID (2 codepoints <= maxLength of 2)  
**Our Result:** INVALID ❌ (incorrectly counts as 4)

## Root Cause

The issue is how string length is counted. There are three different ways to measure string length:

### 1. UTF-16 Code Units (What Kotlin Uses)
- Kotlin/JVM strings use UTF-16 encoding internally
- Characters outside the BMP (Basic Multilingual Plane) require **surrogate pairs**
- The emoji 💩 (U+1F4A9) is represented as TWO UTF-16 code units: `\uD83D\uDCA9`
- `"💩".length` in Kotlin returns **2** (not 1!)

### 2. Unicode Codepoints (What JSON Schema Requires)
- According to the JSON Schema specification, `minLength` and `maxLength` should count **Unicode codepoints**
- The emoji 💩 is ONE codepoint: U+1F4A9
- `"💩"` should have length **1** for JSON Schema purposes

### 3. Grapheme Clusters (User-Perceived Characters)
- Even more complex: some "characters" are made of multiple codepoints
- Example: "é" can be:
  - Single codepoint: U+00E9 (precomposed)
  - Two codepoints: U+0065 (e) + U+0301 (combining acute accent)
- Emojis with skin tones: "👨🏻" is multiple codepoints but ONE grapheme cluster

## Current Implementation

```kotlin
// In validateString() method
schema["minLength"]?.jsonPrimitive?.intOrNull?.let { minLength ->
    if (string.length < minLength) {  // ❌ Uses UTF-16 code units
        errors.add(ValidationError(path, "String is too short", "minLength"))
    }
}

schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { maxLength ->
    if (string.length > maxLength) {  // ❌ Uses UTF-16 code units
        errors.add(ValidationError(path, "String is too long", "maxLength"))
    }
}
```

## The Specific Failures

### minLength Test: "💩" with minLength: 2

| Measurement | Value | Result |
|-------------|-------|--------|
| UTF-16 code units (Kotlin `.length`) | 2 | PASSES (2 >= 2) ❌ |
| Unicode codepoints (correct) | 1 | FAILS (1 < 2) ✅ |
| Expected by JSON Schema | 1 | Should be INVALID |

**Our validator incorrectly validates this as VALID**

### maxLength Test: "💩💩" with maxLength: 2

| Measurement | Value | Result |
|-------------|-------|--------|
| UTF-16 code units (Kotlin `.length`) | 4 | FAILS (4 > 2) ❌ |
| Unicode codepoints (correct) | 2 | PASSES (2 <= 2) ✅ |
| Expected by JSON Schema | 2 | Should be VALID |

**Our validator incorrectly validates this as INVALID**

## How to Fix

Replace Kotlin's `.length` with a codepoint counter:

```kotlin
// Helper function to count Unicode codepoints (not UTF-16 code units)
private fun String.codepointLength(): Int = this.codePointCount(0, this.length)

// Usage in validateString()
schema["minLength"]?.jsonPrimitive?.intOrNull?.let { minLength ->
    if (string.codepointLength() < minLength) {  // ✅ Counts codepoints
        errors.add(ValidationError(path, "String is too short", "minLength"))
    }
}

schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { maxLength ->
    if (string.codepointLength() > maxLength) {  // ✅ Counts codepoints
        errors.add(ValidationError(path, "String is too long", "maxLength"))
    }
}
```

## Technical Details

### UTF-16 Surrogate Pairs
- Unicode codepoints U+0000 to U+FFFF fit in one 16-bit code unit
- Codepoints U+10000 to U+10FFFF require two 16-bit code units (surrogate pair)
- High surrogate: U+D800 to U+DBFF
- Low surrogate: U+DC00 to U+DFFF

### The 💩 Emoji Breakdown
```
Unicode:  U+1F4A9
UTF-16:   \uD83D\uDCA9  (high surrogate + low surrogate)
Kotlin:   "💩".length returns 2
Correct:  1 codepoint
```

### Why This Matters
Many emojis, non-Latin scripts, and rare characters use codepoints outside the BMP:
- Emojis: 😀 (U+1F600), 🎉 (U+1F389), 💯 (U+1F4AF)
- Ancient scripts: 𐍈 (Gothic), 𒀀 (Cuneiform)
- Math symbols: 𝕏 (Double-struck X), 𝛼 (Mathematical Alpha)

## Impact

- **Severity**: Low - affects only strings with non-BMP characters
- **Frequency**: Rare in most applications (unless heavily using emojis/special Unicode)
- **Correctness**: Fails to comply with JSON Schema specification
- **Workaround**: None (requires code fix)

## Solution Complexity

**Easy Fix:** ✅
- Replace `.length` with `.codePointCount(0, length)`
- ~2 lines of code change
- No external dependencies needed

This is much simpler than grapheme cluster counting, which would require a full Unicode segmentation algorithm.

## Related Standards

- **JSON Schema Spec**: Uses "Unicode code points" for string length
- **Unicode Standard**: Defines codepoints, code units, and grapheme clusters
- **RFC 3629 (UTF-8)**: Defines Unicode encoding
- **Java/Kotlin String API**: Uses UTF-16 code units internally

## Conclusion

The issue is **not actually about grapheme clusters** - it's about the simpler problem of counting Unicode codepoints vs. UTF-16 code units. The fix is straightforward: use `String.codePointCount()` instead of `String.length`.

The test names mention "graphemes" but actually test codepoint counting, since each emoji in the test is a single codepoint (even though it's represented as two UTF-16 code units in the JVM).

---

**Status**: Known issue, easy fix available
**Tests Affected**: 4 (2 in Draft 2019-09, 2 in Draft 2020-12)
**Fix Complexity**: Very simple (2-line change)

## Quick Reference

### The Issue in One Sentence
Kotlin's `String.length` counts UTF-16 code units (2 for emoji 💩), but JSON Schema requires counting Unicode codepoints (1 for emoji 💩).

### The Fix in One Line
Replace `value.length` with `value.codePointCount(0, value.length)` in minLength/maxLength validation.

### Test It Yourself
```kotlin
val emoji = "💩"
println(emoji.length)                      // Prints: 2 (UTF-16 code units)
println(emoji.codePointCount(0, emoji.length))  // Prints: 1 (Unicode codepoints)
```

### Why It Matters
- ✅ Most text works fine (ASCII, Latin, CJK in BMP)
- ❌ Fails for emojis, rare Unicode characters (outside BMP)
- 📊 Affects 4 tests out of 2,376 (0.17% of test suite)
- 🔧 Very easy to fix (2-line code change)

### Status
- **Priority**: Low (rare edge case)
- **Effort**: Minimal (5 minutes to fix)
- **Risk**: None (simple, well-tested API)
- **Recommendation**: Fix when convenient

