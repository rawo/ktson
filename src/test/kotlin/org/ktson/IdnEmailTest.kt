package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * idn-email is RFC 6531: the RFC 5321 mailbox grammar with non-ASCII characters allowed (#44). Its
 * local part follows the same dot-atom / quoted-string rules as email; only the domain was checked.
 */
class IdnEmailTest :
    DescribeSpec({
        fun isValid(value: String): Boolean = JsonValidator().validate(JsonPrimitive(value), JsonSchema.fromString("""{"format":"idn-email"}""")).isValid

        describe("idn-email local part") {
            listOf("a b@example.com", "a..b@example.com", "a@b@example.com", ".a@example.com", "a.@example.com", "@example.com", "a(b)@example.com", "\"unterminated@example.com").forEach { value ->
                it("rejects '$value'") {
                    isValid(value) shouldBe false
                }
            }

            listOf("user@example.com", "first.last+tag@example.com", "квіт@пошта.укр", "用户@例子.广告", "θσερ@εχαμπλε.ψομ", "\"quoted user\"@example.com", "\"ünïcödé quoted\"@example.com", "user@[127.0.0.1]").forEach { value ->
                it("accepts '$value'") {
                    isValid(value) shouldBe true
                }
            }
        }

        describe("quoted local part in email and idn-email (#72)") {
            fun isValid(value: String, format: String): Boolean = JsonValidator().validate(JsonPrimitive(value), JsonSchema.fromString("""{"format":"$format"}""")).isValid

            listOf("email", "idn-email").forEach { format ->
                listOf("\"a\tb\"@example.com", "\"a\\\tb\"@example.com", "\"a\u007Fb\"@example.com", "\"a\\\u007Fb\"@example.com", "\"a\\\u00E9\"@example.com").forEach { value ->
                    it("$format rejects ${JsonPrimitive(value)}") { isValid(value, format) shouldBe false }
                }
                listOf("\"a b\"@example.com", "\"a\\\"b\"@example.com", "\"a\\ b\"@example.com", "\"a@b\"@example.com", "\"\"@example.com").forEach { value ->
                    it("$format accepts ${JsonPrimitive(value)}") { isValid(value, format) shouldBe true }
                }
            }

            it("email rejects a non-ASCII character in a quoted local part") { isValid("\"\u00E9\"@example.com", "email") shouldBe false }
            it("idn-email accepts a non-ASCII character in a quoted local part") { isValid("\"\u00E9\"@example.com", "idn-email") shouldBe true }
        }
    })
