package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class FormatValidationTest :
    DescribeSpec({
    val validator = JsonValidator(formatAssertion = true)

    fun schema(format: String) = JsonSchema.fromString("""{"format": "$format"}""", SchemaVersion.DRAFT_2020_12)

    fun valid(value: String, format: String) = validator.validate(JsonPrimitive(value), schema(format)).isValid shouldBe true
    fun invalid(value: String, format: String) = validator.validate(JsonPrimitive(value), schema(format)).isValid shouldBe false

    describe("json-pointer format") {
        it("empty string is valid") { runTest { valid("", "json-pointer") } }
        it("root pointer is valid") { runTest { valid("/", "json-pointer") } }
        it("simple path is valid") { runTest { valid("/foo", "json-pointer") } }
        it("nested path is valid") { runTest { valid("/foo/bar", "json-pointer") } }
        it("path with index is valid") { runTest { valid("/foo/0", "json-pointer") } }
        it("escaped tilde-zero is valid") { runTest { valid("/a~0b", "json-pointer") } }
        it("escaped tilde-one is valid") { runTest { valid("/a~1b", "json-pointer") } }
        it("multiple escapes are valid") { runTest { valid("/~1~0~0~1~1", "json-pointer") } }
        it("percent-encoded segment is valid") { runTest { valid("/c%d", "json-pointer") } }
        it("path with dash is valid") { runTest { valid("/foo/-", "json-pointer") } }
        it("empty segment is valid") { runTest { valid("/foo//bar", "json-pointer") } }
        it("trailing slash is valid") { runTest { valid("/foo/bar/", "json-pointer") } }

        it("URI fragment with hash is invalid") { runTest { invalid("#", "json-pointer") } }
        it("URI fragment with hash-slash is invalid") { runTest { invalid("#/", "json-pointer") } }
        it("unescaped tilde is invalid") { runTest { invalid("/foo/bar~", "json-pointer") } }
        it("tilde-two is invalid") { runTest { invalid("/~2", "json-pointer") } }
        it("tilde-minus is invalid") { runTest { invalid("/~-1", "json-pointer") } }
        it("double tilde is invalid") { runTest { invalid("/~~", "json-pointer") } }
        it("no leading slash is invalid") { runTest { invalid("a", "json-pointer") } }
        it("non-slash start is invalid") { runTest { invalid("a/a", "json-pointer") } }
    }

    describe("relative-json-pointer format") {
        it("upwards pointer is valid") { runTest { valid("1", "relative-json-pointer") } }
        it("downwards pointer is valid") { runTest { valid("0/foo/bar", "relative-json-pointer") } }
        it("up and down with array index is valid") { runTest { valid("2/0/baz/1/zip", "relative-json-pointer") } }
        it("name reference is valid") { runTest { valid("0#", "relative-json-pointer") } }
        it("multi-digit prefix is valid") { runTest { valid("120/foo/bar", "relative-json-pointer") } }
        it("zero alone is valid") { runTest { valid("0", "relative-json-pointer") } }

        it("empty string is invalid") { runTest { invalid("", "relative-json-pointer") } }
        it("JSON pointer (starts with slash) is invalid") { runTest { invalid("/foo/bar", "relative-json-pointer") } }
        it("negative prefix is invalid") { runTest { invalid("-1/foo/bar", "relative-json-pointer") } }
        it("explicit positive prefix is invalid") { runTest { invalid("+1/foo/bar", "relative-json-pointer") } }
        it("double hash is invalid") { runTest { invalid("0##", "relative-json-pointer") } }
        it("leading zero followed by digits with slash is invalid") { runTest { invalid("01/a", "relative-json-pointer") } }
        it("leading zero followed by digits with hash is invalid") { runTest { invalid("01#", "relative-json-pointer") } }
    }

    describe("uri-reference format") {
        it("absolute URI is valid") { runTest { valid("http://foo.bar/?baz=qux#quux", "uri-reference") } }
        it("protocol-relative URI is valid") { runTest { valid("//foo.bar/?baz=qux#quux", "uri-reference") } }
        it("absolute path is valid") { runTest { valid("/abc", "uri-reference") } }
        it("relative path is valid") { runTest { valid("abc", "uri-reference") } }
        it("fragment-only is valid") { runTest { valid("#fragment", "uri-reference") } }
        it("empty string is valid") { runTest { valid("", "uri-reference") } }

        it("backslash path is invalid") { runTest { invalid("\\\\WINDOWS\\fileshare", "uri-reference") } }
        it("fragment with backslash is invalid") { runTest { invalid("#frag\\ment", "uri-reference") } }
    }

    describe("uri-template format") {
        it("valid template with expressions") { runTest { valid("http://example.com/{term:1}/{term}", "uri-template") } }
        it("template without variables is valid") { runTest { valid("http://example.com/dictionary", "uri-template") } }
        it("relative template is valid") { runTest { valid("dictionary/{term:1}/{term}", "uri-template") } }
        it("empty string is valid") { runTest { valid("", "uri-template") } }
        it("single expression is valid") { runTest { valid("{var}", "uri-template") } }
        it("multiple expressions are valid") { runTest { valid("{a}/{b}", "uri-template") } }

        it("unclosed brace is invalid") { runTest { invalid("http://example.com/{term:1}/{term", "uri-template") } }
        it("extra closing brace is invalid") { runTest { invalid("http://example.com/}", "uri-template") } }
        it("nested braces are invalid") { runTest { invalid("{{nested}}", "uri-template") } }
    }

    describe("duration format") {
        it("full date-time duration is valid") { runTest { valid("P4DT12H30M5S", "duration") } }
        it("years only is valid") { runTest { valid("P4Y", "duration") } }
        it("months only is valid") { runTest { valid("P1M", "duration") } }
        it("days only is valid") { runTest { valid("P0D", "duration") } }
        it("weeks only is valid") { runTest { valid("P2W", "duration") } }
        it("time seconds only is valid") { runTest { valid("PT0S", "duration") } }
        it("time minutes only is valid") { runTest { valid("PT1M", "duration") } }
        it("time hours only is valid") { runTest { valid("PT36H", "duration") } }
        it("date and time is valid") { runTest { valid("P1DT12H", "duration") } }

        it("must start with P") { runTest { invalid("4DT12H30M5S", "duration") } }
        it("P alone is invalid") { runTest { invalid("P", "duration") } }
        it("PT alone is invalid") { runTest { invalid("PT", "duration") } }
        it("P with trailing T is invalid") { runTest { invalid("P1YT", "duration") } }
        it("time unit in date position is invalid") { runTest { invalid("PT1D", "duration") } }
        it("date unit after time separator is invalid") { runTest { invalid("P2S", "duration") } }
        it("out-of-order date elements is invalid") { runTest { invalid("P2D1Y", "duration") } }
        it("missing T before time elements is invalid") { runTest { invalid("P1D2H", "duration") } }
        it("weeks combined with other units is invalid") { runTest { invalid("P1Y2W", "duration") } }
        it("non-ASCII digits are invalid") { runTest { invalid("P\u09E8Y", "duration") } }
        it("digit without unit is invalid") { runTest { invalid("P1", "duration") } }
    }

    describe("idn-hostname format") {
        it("ASCII hostname is valid") { runTest { valid("hostname", "idn-hostname") } }
        it("hostname with hyphen is valid") { runTest { valid("host-name", "idn-hostname") } }
        it("hostname with digits is valid") { runTest { valid("h0stn4me", "idn-hostname") } }
        it("hostname starting with digit is valid") { runTest { valid("1host", "idn-hostname") } }
        it("multi-label with dot is valid") { runTest { valid("a.b", "idn-hostname") } }
        it("Korean hostname is valid") { runTest { valid("실례.테스트", "idn-hostname") } }
        it("valid Chinese Punycode is valid") { runTest { valid("xn--ihqwcrb4cv8a8dqg056pqjye", "idn-hostname") } }

        it("empty string is invalid") { runTest { invalid("", "idn-hostname") } }
        it("starts with hyphen is invalid") { runTest { invalid("-hello", "idn-hostname") } }
        it("ends with hyphen is invalid") { runTest { invalid("hello-", "idn-hostname") } }
        it("single dot is invalid") { runTest { invalid(".", "idn-hostname") } }
        it("label with disallowed char U+302E is invalid") { runTest { invalid("실\u302E례.테스트", "idn-hostname") } }
        it("starts with nonspacing mark is invalid") { runTest { invalid("\u0300hello", "idn-hostname") } }
        it("starts with spacing combining mark is invalid") { runTest { invalid("\u0903hello", "idn-hostname") } }
        it("starts with enclosing mark is invalid") { runTest { invalid("\u0488hello", "idn-hostname") } }
        it("contains Arabic tatweel is invalid") { runTest { invalid("\u0640\u07FA", "idn-hostname") } }
    }
})
