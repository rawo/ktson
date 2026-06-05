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

    describe("date format") {
        it("valid date") { runTest { valid("1963-06-19", "date") } }
        it("31 days in January is valid") { runTest { valid("2020-01-31", "date") } }
        it("28 days in non-leap February is valid") { runTest { valid("2021-02-28", "date") } }
        it("29 days in leap February is valid") { runTest { valid("2020-02-29", "date") } }
        it("30 days in April is valid") { runTest { valid("2020-04-30", "date") } }
        it("31 days in December is valid") { runTest { valid("2020-12-31", "date") } }

        it("32 days in January is invalid") { runTest { invalid("2020-01-32", "date") } }
        it("29 days in non-leap February is invalid") { runTest { invalid("2021-02-29", "date") } }
        it("30 days in leap February is invalid") { runTest { invalid("2020-02-30", "date") } }
        it("31 days in April is invalid") { runTest { invalid("2020-04-31", "date") } }
        it("month 13 is invalid") { runTest { invalid("2020-13-01", "date") } }
        it("month 00 is invalid") { runTest { invalid("2020-00-01", "date") } }
        it("day 00 is invalid") { runTest { invalid("2020-01-00", "date") } }
        it("non-padded month is invalid") { runTest { invalid("1998-1-20", "date") } }
        it("non-padded day is invalid") { runTest { invalid("1998-01-1", "date") } }
        it("slash-separated is invalid") { runTest { invalid("06/19/1963", "date") } }
        it("date-time string is invalid") { runTest { invalid("2020-11-28T23:55:45Z", "date") } }
    }

    describe("time format") {
        it("valid time with Z") { runTest { valid("08:30:06Z", "time") } }
        it("valid time with positive offset") { runTest { valid("08:30:06+00:20", "time") } }
        it("valid time with negative offset") { runTest { valid("08:30:06-08:00", "time") } }
        it("valid time with fractional seconds") { runTest { valid("23:20:50.52Z", "time") } }
        it("lowercase z is valid") { runTest { valid("08:30:06z", "time") } }
        it("valid leap second Zulu") { runTest { valid("23:59:60Z", "time") } }
        it("valid leap second zero offset") { runTest { valid("23:59:60+00:00", "time") } }
        it("valid leap second positive offset") { runTest { valid("01:29:60+01:30", "time") } }
        it("valid leap second negative offset") { runTest { valid("15:59:60-08:00", "time") } }

        it("no timezone is invalid") { runTest { invalid("12:00:00", "time") } }
        it("hour 24 is invalid") { runTest { invalid("24:00:00Z", "time") } }
        it("minute 60 is invalid") { runTest { invalid("00:60:00Z", "time") } }
        it("second 61 is invalid") { runTest { invalid("00:00:61Z", "time") } }
        it("invalid leap second wrong hour") { runTest { invalid("22:59:60Z", "time") } }
        it("invalid leap second wrong minute") { runTest { invalid("23:58:60Z", "time") } }
        it("offset hour 24 is invalid") { runTest { invalid("01:02:03+24:00", "time") } }
        it("offset minute 60 is invalid") { runTest { invalid("01:02:03+00:60", "time") } }
        it("Z and numeric offset together is invalid") { runTest { invalid("01:02:03Z+00:30", "time") } }
        it("non-padded time is invalid") { runTest { invalid("8:3:6Z", "time") } }
        it("date-time string is invalid for time format") { runTest { invalid("2020-11-28T23:55:45Z", "time") } }
    }

    describe("date-time format") {
        it("valid date-time with Z") { runTest { valid("1963-06-19T08:30:06Z", "date-time") } }
        it("valid date-time with fractional seconds") { runTest { valid("1963-06-19T08:30:06.283185Z", "date-time") } }
        it("valid date-time with plus offset") { runTest { valid("1937-01-01T12:00:27.87+00:20", "date-time") } }
        it("valid date-time with minus offset") { runTest { valid("1990-12-31T15:59:50.123-08:00", "date-time") } }
        it("valid date-time with leap second UTC") { runTest { valid("1998-12-31T23:59:60Z", "date-time") } }
        it("valid date-time with leap second minus offset") { runTest { valid("1998-12-31T15:59:60.123-08:00", "date-time") } }
        it("case-insensitive T and Z") { runTest { valid("1963-06-19t08:30:06.283185z", "date-time") } }

        it("past leap second is invalid") { runTest { invalid("1998-12-31T23:59:61Z", "date-time") } }
        it("leap second on wrong minute is invalid") { runTest { invalid("1998-12-31T23:58:60Z", "date-time") } }
        it("leap second on wrong hour is invalid") { runTest { invalid("1998-12-31T22:59:60Z", "date-time") } }
        it("invalid day in date-time is invalid") { runTest { invalid("1990-02-31T15:59:59.123-08:00", "date-time") } }
        it("invalid offset in date-time is invalid") { runTest { invalid("1990-12-31T15:59:59-24:00", "date-time") } }
        it("hour 24 in date-time is invalid") { runTest { invalid("1990-12-31T24:00:00Z", "date-time") } }
        it("Z after numeric offset is invalid") { runTest { invalid("1963-06-19T08:30:06.28123+01:00Z", "date-time") } }
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

    describe("ipv6 format") {
        it("full 8-group address is valid") { runTest { valid("1:2:3:4:5:6:7:8", "ipv6") } }
        it("loopback ::1 is valid") { runTest { valid("::1", "ipv6") } }
        it("all-zeros :: is valid") { runTest { valid("::", "ipv6") } }
        it("trailing double colon is valid") { runTest { valid("d6::", "ipv6") } }
        it("leading double colon with groups is valid") { runTest { valid("::42:ff:1", "ipv6") } }
        it("double colon in middle is valid") { runTest { valid("1:d6::42", "ipv6") } }
        it("trailing 4 hex is valid") { runTest { valid("::abef", "ipv6") } }
        it("mixed format with IPv4 tail is valid") { runTest { valid("1::d6:192.168.0.1", "ipv6") } }
        it("mixed format with double colons between sections is valid") { runTest { valid("1:2::192.168.0.1", "ipv6") } }
        it("IPv4-mapped address is valid") { runTest { valid("::ffff:192.168.0.1", "ipv6") } }
        it("long valid mixed ipv6 is valid") { runTest { valid("1000:1000:1000:1000:1000:1000:255.255.255.255", "ipv6") } }

        it("5 hex digits in group is invalid") { runTest { invalid("12345::", "ipv6") } }
        it("trailing 5 hex symbols is invalid") { runTest { invalid("::abcef", "ipv6") } }
        it("too many groups is invalid") { runTest { invalid("1:1:1:1:1:1:1:1:1:1:1:1:1:1:1:1", "ipv6") } }
        it("illegal characters is invalid") { runTest { invalid("::laptop", "ipv6") } }
        it("missing leading octet is invalid") { runTest { invalid(":2:3:4:5:6:7:8", "ipv6") } }
        it("missing trailing octet is invalid") { runTest { invalid("1:2:3:4:5:6:7:", "ipv6") } }
        it("two double colons is invalid") { runTest { invalid("1::d6::42", "ipv6") } }
        it("triple colon is invalid") { runTest { invalid("1:2:3:4:5:::8", "ipv6") } }
        it("insufficient octets without double colons is invalid") { runTest { invalid("1:2:3:4:5:6:7", "ipv6") } }
        it("IPv4 address is not IPv6") { runTest { invalid("127.0.0.1", "ipv6") } }
        it("IPv4 segment must have 4 octets is invalid") { runTest { invalid("1:2:3:4:1.2.3", "ipv6") } }
        it("netmask is not part of IPv6 is invalid") { runTest { invalid("fe80::/64", "ipv6") } }
        it("IPv4 octet out of range is invalid") { runTest { invalid("1::2:192.168.256.1", "ipv6") } }
        it("hex octet in IPv4 is invalid") { runTest { invalid("1::2:192.168.ff.1", "ipv6") } }
        it("leading whitespace is invalid") { runTest { invalid("  ::1", "ipv6") } }
        it("trailing whitespace is invalid") { runTest { invalid("::1  ", "ipv6") } }
    }

    describe("email format") {
        it("standard email is valid") { runTest { valid("joe.bloggs@example.com", "email") } }
        it("tilde in local part is valid") { runTest { valid("te~st@example.com", "email") } }
        it("tilde before local part is valid") { runTest { valid("~test@example.com", "email") } }
        it("tilde after local part is valid") { runTest { valid("test~@example.com", "email") } }
        it("quoted string with space is valid") { runTest { valid("\"joe bloggs\"@example.com", "email") } }
        it("quoted string with double dot is valid") { runTest { valid("\"joe..bloggs\"@example.com", "email") } }
        it("quoted string with at-sign is valid") { runTest { valid("\"joe@bloggs\"@example.com", "email") } }
        it("IPv4 address literal domain is valid") { runTest { valid("joe.bloggs@[127.0.0.1]", "email") } }
        it("IPv6 address literal domain is valid") { runTest { valid("joe.bloggs@[IPv6:::1]", "email") } }
        it("two separated dots in local part are valid") { runTest { valid("te.s.t@example.com", "email") } }

        it("no at-sign is invalid") { runTest { invalid("2962", "email") } }
        it("leading dot in local part is invalid") { runTest { invalid(".test@example.com", "email") } }
        it("trailing dot in local part is invalid") { runTest { invalid("test.@example.com", "email") } }
        it("consecutive dots in local part are invalid") { runTest { invalid("te..st@example.com", "email") } }
        it("invalid char in domain is invalid") { runTest { invalid("joe.bloggs@invalid=domain.com", "email") } }
        it("invalid IPv4 literal domain is invalid") { runTest { invalid("joe.bloggs@[127.0.0.300]", "email") } }
        it("two emails is invalid") { runTest { invalid("user1@oceania.org, user2@oceania.org", "email") } }
        it("no local part is invalid") { runTest { invalid("@example.com", "email") } }
        it("no domain is invalid") { runTest { invalid("joe.bloggs@", "email") } }
        it("unquoted space in local part is invalid") { runTest { invalid("joe bloggs@example.com", "email") } }
    }

    describe("uri format") {
        it("simple http URI is valid") { runTest { valid("http://example.com", "uri") } }
        it("https URI with path is valid") { runTest { valid("https://example.com/path/to/resource", "uri") } }
        it("URI with query and fragment is valid") { runTest { valid("https://example.com/path?q=1#frag", "uri") } }
        it("URI with port is valid") { runTest { valid("http://example.com:8080/", "uri") } }
        it("URI with userinfo is valid") { runTest { valid("http://user@example.com/", "uri") } }
        it("URI with percent-encoded char is valid") { runTest { valid("http://example.com/path%20with%20spaces", "uri") } }
        it("urn scheme is valid") { runTest { valid("urn:isbn:0451450523", "uri") } }
        it("ftp scheme is valid") { runTest { valid("ftp://ftp.example.com/file.txt", "uri") } }
        it("URI with IPv6 host is valid") { runTest { valid("http://[::1]/", "uri") } }

        it("relative URI is invalid") { runTest { invalid("/relative/path", "uri") } }
        it("no scheme is invalid") { runTest { invalid("example.com", "uri") } }
        it("space in URI is invalid") { runTest { invalid("http://example.com/path with spaces", "uri") } }
        it("backslash in URI is invalid") { runTest { invalid("http://example.com/path\\file", "uri") } }
        it("non-ASCII char is invalid") { runTest { invalid("http://example.com/pàth", "uri") } }
        it("incomplete percent-encoding is invalid") { runTest { invalid("http://example.com/path%2", "uri") } }
        it("non-hex percent-encoding is invalid") { runTest { invalid("http://example.com/path%GG", "uri") } }
        it("numeric scheme start is invalid") { runTest { invalid("1http://example.com", "uri") } }
    }
})
