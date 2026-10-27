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
    })
