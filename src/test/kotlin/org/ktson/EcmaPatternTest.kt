package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `pattern` and `patternProperties` use ECMA 262 regular expressions, which Java's engine runs only after
 * translation: their whitespace class, control escapes, end anchor, dot and property aliases differ (#69).
 */
class EcmaPatternTest :
    DescribeSpec({
        SchemaVersion.entries.forEach { version ->
            describe("$version") {
                val validator = JsonValidator()
                fun matches(pattern: String, input: String): Boolean = validator.validate(JsonPrimitive(input), JsonSchema(buildJsonObject { put("pattern", JsonPrimitive(pattern)) }, version)).isValid

                val ecmaWhitespace = listOf('\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿')
                ecmaWhitespace.forEach { c ->
                    val name = "U+%04X".format(c.code)
                    it("\\s matches $name and \\S does not") {
                        matches("^\\s$", c.toString()) shouldBe true
                        matches("^\\S$", c.toString()) shouldBe false
                        matches("^[\\s]$", c.toString()) shouldBe true
                        matches("^[\\S]$", c.toString()) shouldBe false
                        matches("^[^\\S]$", c.toString()) shouldBe true
                    }
                }

                it("\\s does not match U+0085 or U+200B, which are not ECMA 262 whitespace") {
                    matches("^\\s$", "\u0085") shouldBe false
                    matches("^\\s$", "​") shouldBe false
                    matches("^\\S$", "​") shouldBe true
                }

                it("\\S still matches ordinary characters, also in a class with others") {
                    matches("^\\S+$", "abc") shouldBe true
                    matches("^[a\\S]$", "x") shouldBe true
                    matches("^[\\sx]+$", "x  x") shouldBe true
                }

                it("\\c with a lower or upper letter is the control code letter % 32") {
                    matches("^\\cc$", "\u0003") shouldBe true
                    matches("^\\cC$", "\u0003") shouldBe true
                    matches("^[\\cj]$", "\n") shouldBe true
                    matches("^\\cc$", "#") shouldBe false
                }

                it("\$ matches only at the end of the input, not before a final newline") {
                    matches("^abc$", "abc") shouldBe true
                    matches("^abc$", "abc\n") shouldBe false
                    matches("^[$]$", "$") shouldBe true
                    matches("^\\$$", "$") shouldBe true
                }

                it(". matches everything but the four ECMA 262 line terminators") {
                    matches("^.$", "\u0085") shouldBe true
                    listOf('\n', '\r', ' ', ' ').forEach { matches("^.$", it.toString()) shouldBe false }
                    matches("^[.]$", ".") shouldBe true
                    matches("^[.]$", "x") shouldBe false
                }

                it("\\p accepts General_Category aliases") {
                    matches("^\\p{digit}+$", "42৪২") shouldBe true
                    matches("^\\p{digit}+$", "-%#") shouldBe false
                    matches("^\\p{gc=Nd}$", "7") shouldBe true
                    matches("^\\p{General_Category=Decimal_Number}$", "7") shouldBe true
                    matches("^\\p{punct}$", "!") shouldBe true
                    matches("^\\P{cntrl}$", "a") shouldBe true
                }
            }
        }
    })
