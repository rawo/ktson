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

    describe("uri-reference format") {
        it("absolute URI is valid") { runTest { valid("http://foo.bar/?baz=qux#quux", "uri-reference") } }
        it("protocol-relative reference is valid") { runTest { valid("//foo.bar/?baz=qux#quux", "uri-reference") } }
        it("root-relative path is valid") { runTest { valid("/abc", "uri-reference") } }
        it("relative path is valid") { runTest { valid("abc", "uri-reference") } }
        it("fragment-only is valid") { runTest { valid("#fragment", "uri-reference") } }
        it("empty string is valid") { runTest { valid("", "uri-reference") } }

        it("backslash UNC path is invalid") { runTest { invalid("\\\\WINDOWS\\fileshare", "uri-reference") } }
        it("backslash in fragment is invalid") { runTest { invalid("#frag\\ment", "uri-reference") } }
        it("backslash in path is invalid") { runTest { invalid("https://example.org/foobar\\.txt", "uri-reference") } }
        it("non-ASCII character is invalid") { runTest { invalid("/foobar®.txt", "uri-reference") } }
    }

    describe("hostname format: A-labels") {
        it("plain hostname is valid") { runTest { valid("www.example.com", "hostname") } }
        it("a well-formed A-label is valid") { runTest { valid("xn--nxasmq6b.example", "hostname") } }

        it("undecodable Punycode is invalid") { runTest { invalid("xn--X", "hostname") } }
        it("non-canonical Punycode is invalid") { runTest { invalid("xn---9uc", "hostname") } }
        it("an A-label decoding to a disallowed code point is invalid") { runTest { invalid("xn--7a", "hostname") } }
        it("an A-label decoding to a leading combining mark is invalid") { runTest { invalid("xn--hello-txk", "hostname") } }
        it("an A-label decoding to a Bidi violation is invalid") { runTest { invalid("xn--0ca24w", "hostname") } }
        it("a decoded U-label with -- in the third and fourth position is invalid") {
            runTest { invalid("XN--aa---o47jg78q", "hostname") }
        }
        it("a name longer than 253 characters is invalid") {
            runTest { invalid(List(4) { "a".repeat(63) }.joinToString(".") + ".com", "hostname") }
        }
    }

    describe("idn-hostname format: mapping and length") {
        it("fullwidth characters are mapped before validation") { runTest { valid("\uFF41".repeat(63), "idn-hostname") } }
        it("a mapped ACE prefix is decoded as an A-label") { runTest { valid("\uFF58\uFF4E--nxasmq6b", "idn-hostname") } }
        it("an ignorable code point is dropped by the mapping") { runTest { valid("a\u200Bb", "idn-hostname") } }
        it("an ideographic full stop separates labels") { runTest { valid("\u03C0\u03B1\u03C1\u03AC\u03B4\u03B5\u03B9\u03B3\u03BC\u03B1\u3002com", "idn-hostname") } }
        it("a label whose A-label exceeds 63 characters is invalid") { runTest { invalid("\u03B1".repeat(64), "idn-hostname") } }
    }

    describe("idn-hostname format: contextual rules") {
        it("ZERO WIDTH JOINER after a Virama is valid") { runTest { valid("\u0915\u094D\u200D\u0937", "idn-hostname") } }
        it("ZERO WIDTH JOINER without a Virama is invalid") { runTest { invalid("\u0915\u200D\u0937", "idn-hostname") } }
        it("ZERO WIDTH NON-JOINER in a joining context is valid") { runTest { valid("\u0628\u064A\u200C\u0628\u064A", "idn-hostname") } }
        it("ZERO WIDTH NON-JOINER outside any context is invalid") { runTest { invalid("\u0915\u094D\u200C\u0937x\u200Cy", "idn-hostname") } }
        it("MIDDLE DOT between two l characters is valid") { runTest { valid("l\u00B7l", "idn-hostname") } }
        it("MIDDLE DOT without a preceding l is invalid") { runTest { invalid("a\u00B7l", "idn-hostname") } }
        it("GREEK KERAIA followed by Greek is valid") { runTest { valid("\u03B1\u0375\u03B2", "idn-hostname") } }
        it("GREEK KERAIA not followed by Greek is invalid") { runTest { invalid("\u03B1\u0375S", "idn-hostname") } }
        it("HEBREW GERESH after Hebrew is valid") { runTest { valid("\u05D1\u05F3\u05D2", "idn-hostname") } }
        it("HEBREW GERESH not preceded by Hebrew is invalid") { runTest { invalid("A\u05F3\u05D1", "idn-hostname") } }
        it("KATAKANA MIDDLE DOT alongside Katakana is valid") { runTest { valid("\u30A2\u30FB\u30A4", "idn-hostname") } }
        it("KATAKANA MIDDLE DOT without Japanese characters is invalid") { runTest { invalid("def\u30FBabc", "idn-hostname") } }
        it("one Arabic-Indic digit block is valid") { runTest { valid("\u0628\u0660\u0628", "idn-hostname") } }
        it("mixed Arabic-Indic digit blocks are invalid") { runTest { invalid("\u0628\u0660\u06F0", "idn-hostname") } }
    }

    describe("idn-hostname format: Bidi rule") {
        it("a right-to-left label is valid") { runTest { valid("\u05D0\u05D1", "idn-hostname") } }
        it("digits before a right-to-left letter are invalid") { runTest { invalid("0\u0627", "idn-hostname") } }
        it("a left-to-right label containing a right-to-left letter is invalid") { runTest { invalid("a\u05D0", "idn-hostname") } }
        it("a right-to-left label mixing both digit types is invalid") { runTest { invalid("\u05D00\u0660", "idn-hostname") } }
        it("a digit-first label in a Bidi domain is invalid") { runTest { invalid("0a.\u05D0", "idn-hostname") } }
        it("a label of only Arabic-Indic digits is invalid") { runTest { invalid("\u0660\u0661", "idn-hostname") } }
        it("digits are unrestricted outside a Bidi domain") { runTest { valid("1host", "idn-hostname") } }
    }

    describe("iri and iri-reference formats") {
        it("a non-ASCII IRI is valid") { runTest { valid("http://\u0192\u00F8\u00F8.\u00DF\u00E5r/", "iri") } }
        it("a relative IRI reference is valid") { runTest { valid("/abc", "iri-reference") } }
        it("a lone percent sign is invalid") { runTest { invalid("http://\u0192\u00F8\u00F8.\u00DF\u00E5r/%", "iri") } }
        it("an incomplete percent triplet is invalid") { runTest { invalid("/%A", "iri-reference") } }
        it("non-hex percent encoding is invalid") { runTest { invalid("/%6G", "iri-reference") } }
        it("a trailing newline is invalid") { runTest { invalid("/abc\n", "iri-reference") } }
        it("an embedded IPv4 with a leading zero is invalid") { runTest { invalid("//[::ffff:192.168.0.01]/p", "iri-reference") } }
    }

    describe("uri format: authority rules") {
        it("an IPv6 host is valid") { runTest { valid("http://[::1]/p", "uri") } }
        it("an embedded IPv4 with a leading zero is invalid") { runTest { invalid("http://[::ffff:01.2.3.4]", "uri") } }
        it("square brackets in a path are invalid") { runTest { invalid("http:/[::1]", "uri") } }
        it("a trailing newline is invalid") { runTest { invalid("http://foo.bar/\n", "uri") } }
        it("an unbracketed IPv6 host is invalid") { runTest { invalid("http://2001:0db8:85a3::8a2e:0370:7334", "uri") } }
        it("a non-numeric port is invalid") { runTest { invalid("//example.com:abc/p", "uri-reference") } }
        it("more than one at-sign in the authority is invalid") { runTest { invalid("//a@b@example.com/", "uri-reference") } }
        it("a colon in the first segment of a relative path is invalid") { runTest { invalid("1:b", "uri-reference") } }
    }

    describe("uri-template format: RFC 6570 grammar") {
        it("an expression with a prefix modifier is valid") { runTest { valid("{term:1}", "uri-template") } }
        it("a four-digit prefix is valid") { runTest { valid("{v:1000}", "uri-template") } }
        it("the explode modifier is valid") { runTest { valid("{var*}", "uri-template") } }
        it("a dotted variable name is valid") { runTest { valid("{a.b}", "uri-template") } }
        it("a percent-encoded variable name is valid") { runTest { valid("{%41}", "uri-template") } }
        it("expansion operators are valid") { runTest { valid("{+var}{#var}{.var}{/var}{;var}{?var}{&var}", "uri-template") } }

        it("an empty expression is invalid") { runTest { invalid("{}", "uri-template") } }
        it("an empty varspec in the list is invalid") { runTest { invalid("{a,,b}", "uri-template") } }
        it("a trailing comma is invalid") { runTest { invalid("{a,}", "uri-template") } }
        it("a zero prefix length is invalid") { runTest { invalid("{v:0}", "uri-template") } }
        it("a leading zero in the prefix length is invalid") { runTest { invalid("{v:01}", "uri-template") } }
        it("a five-digit prefix length is invalid") { runTest { invalid("{v:10000}", "uri-template") } }
        it("combining prefix and explode is invalid") { runTest { invalid("{var:1*}", "uri-template") } }
        it("a double dot in a variable name is invalid") { runTest { invalid("{a..b}", "uri-template") } }
        it("a default value in a varspec is invalid") { runTest { invalid("{var=def}", "uri-template") } }
        it("a reserved operator is invalid") { runTest { invalid("{,+var}", "uri-template") } }
        it("an incomplete percent triplet in a literal is invalid") { runTest { invalid("a%4", "uri-template") } }
        it("a space in a literal is invalid") { runTest { invalid("a b", "uri-template") } }
        it("a delete character in a literal is invalid") { runTest { invalid("a\u007Fb", "uri-template") } }
    }

    describe("regex format: ECMA 262 syntax") {
        it("a named group is valid") { runTest { valid("(?<name>x)", "regex") } }
        it("a named backreference is valid") { runTest { valid("(?<n>a)\\k<n>", "regex") } }
        it("a variable-width lookbehind is valid") { runTest { valid("(?<=a+)b", "regex") } }
        it("an empty character class is valid") { runTest { valid("[]", "regex") } }
        it("a negated empty character class is valid") { runTest { valid("[^]", "regex") } }
        it("a control escape is valid") { runTest { valid("\\cA", "regex") } }

        it("a Java-only escape is invalid") { runTest { invalid("\\a", "regex") } }
        it("a single inline flag is invalid") { runTest { invalid("(?i)abc", "regex") } }
        it("multiple inline flags are invalid") { runTest { invalid("(?ims)abc", "regex") } }
        it("a Python named group is invalid") { runTest { invalid("(?P<name>x)", "regex") } }
        it("an inline comment group is invalid") { runTest { invalid("(?#comment)a", "regex") } }
        it("an unclosed group is invalid") { runTest { invalid("^(abc]", "regex") } }
    }

    describe("format edge cases fixed alongside the IDNA work") {
        it("a duration may not skip a component") { runTest { invalid("P1Y2D", "duration") } }
        it("a time duration may not skip a component") { runTest { invalid("PT1H2S", "duration") } }
        it("nested duration components are valid") { runTest { valid("P1Y2M3DT4H5M6S", "duration") } }
        it("a leading zero in an IPv4 octet is invalid") { runTest { invalid("192.168.0.01", "ipv4") } }
        it("a leading zero in an embedded IPv4 octet is invalid") { runTest { invalid("::ffff:192.168.0.01", "ipv6") } }
        it("a non-ASCII digit in a relative pointer prefix is invalid") { runTest { invalid("\u0661/foo", "relative-json-pointer") } }
        it("an escaped double quote in a quoted local part is valid") { runTest { valid("\"\\\"\"@iana.org", "email") } }
        it("a non-ASCII character in a quoted pair is invalid") { runTest { invalid("\"test\\©\"@iana.org", "email") } }
        it("a lowercase IPv6 tag in an address literal is valid") { runTest { valid("a@[ipv6:::1]", "email") } }
        it("an idn-email domain must be a valid hostname") { runTest { invalid("\u03B4@example..com", "idn-email") } }
    }
})
