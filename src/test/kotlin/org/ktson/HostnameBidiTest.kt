package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * In a domain with a right-to-left label (xn--4db is Hebrew ALEF), the RFC 5893 Bidi rule applies to every label,
 * plain ASCII ones included (#82). A valid hostname must also be a valid idn-hostname.
 */
class HostnameBidiTest :
    DescribeSpec({
        val validator = JsonValidator()
        fun isValid(value: String, format: String) = validator.validate(JsonPrimitive(value), JsonSchema.fromString("""{"format":"$format"}""")).isValid

        listOf("xn--4db.1abc", "xn--4db.123", "1abc.xn--4db").forEach { host ->
            it("hostname and email reject $host, whose ASCII label starts with a digit in a Bidi domain") {
                isValid(host, "hostname") shouldBe false
                isValid("local@$host", "email") shouldBe false
            }
        }

        listOf("AbC.xn--4db", "abc.xn--4db", "xn--4db.example", "1abc.example", "123.example").forEach { host ->
            it("hostname accepts $host") { isValid(host, "hostname") shouldBe true }
        }

        it("accepts as idn-hostname every hostname it accepts as hostname") {
            listOf("xn--4db.1abc", "xn--4db.123", "1abc.xn--4db", "AbC.xn--4db", "xn--4db.example", "1abc.example", "xn--bcher-kva.example")
                .filter { isValid(it, "hostname") }
                .forEach { isValid(it, "idn-hostname") shouldBe true }
        }
    })
