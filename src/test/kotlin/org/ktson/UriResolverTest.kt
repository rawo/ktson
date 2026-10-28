package org.ktson

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.net.URISyntaxException

class UriResolverTest :
    DescribeSpec({

	describe("isAbsoluteUri") {
		it("detects http and https as absolute") {
			UriResolver.isAbsoluteUri("http://example.com").shouldBeTrue()
			UriResolver.isAbsoluteUri("https://example.com/path").shouldBeTrue()
		}

		it("detects non-scheme strings as relative") {
			UriResolver.isAbsoluteUri("/path/to/resource").shouldBeFalse()
			UriResolver.isAbsoluteUri("path/segment.json").shouldBeFalse()
			UriResolver.isAbsoluteUri("#fragment").shouldBeFalse()
		}

		it("handles non-http schemes as absolute") {
			UriResolver.isAbsoluteUri("mailto:user@example.com").shouldBeTrue()
			UriResolver.isAbsoluteUri("urn:isbn:0451450523").shouldBeTrue()
		}
	}

	describe("normalizeUri") {
		it("lowercases scheme and host") {
			UriResolver.normalizeUri("HTTP://EXAMPLE.COM/Path").shouldBe("http://example.com/Path")
		}

		it("resolves dot segments") {
			UriResolver.normalizeUri("https://example.com/a/b/./c/../d").shouldBe("https://example.com/a/b/d")
		}

		it("preserves trailing slash") {
			UriResolver.normalizeUri("https://example.com/a/b/").shouldBe("https://example.com/a/b/")
		}

		it("keeps query and fragment by default") {
			UriResolver.normalizeUri("https://example.com/a?x=1#frag").shouldBe("https://example.com/a?x=1#frag")
		}

		it("removes fragment when requested") {
			UriResolver.normalizeUri("https://example.com/a?x=1#frag", removeFragment = true).shouldBe("https://example.com/a?x=1")
		}

		it("throws on parse error") {
			shouldThrow<URISyntaxException> { UriResolver.normalizeUri("ht t p://bad uri") }
		}
	}

	describe("removeFragment / extractFragment") {
		it("extracts fragment correctly") {
			UriResolver.extractFragment("https://example.com/a#section").shouldBe("section")
			UriResolver.extractFragment("https://example.com/a").shouldBe(null)
			UriResolver.extractFragment("#only").shouldBe("only")
		}

		it("removes fragment correctly") {
			UriResolver.removeFragment("https://example.com/a#frag").shouldBe("https://example.com/a")
		}
	}

	describe("parseUri") {
		it("parses components of absolute URI") {
			val c = UriResolver.parseUri("https://example.com/a/b?x=1#f")
			c.scheme.shouldBe("https")
			c.authority.shouldBe("example.com")
			c.path.shouldBe("/a/b")
			c.query.shouldBe("x=1")
			c.fragment.shouldBe("f")
			c.isAbsolute.shouldBeTrue()
			c.withoutFragment.shouldBe("https://example.com/a/b?x=1")
		}

		it("throws for invalid URI") {
			shouldThrow<URISyntaxException> { UriResolver.parseUri("ht tps://bad uri") }
		}
	}

	describe("resolveUri") {
		it("returns ref when base is empty") {
			UriResolver.resolveUri("", "https://example.com/x").shouldBe("https://example.com/x")
		}

		it("returns base when ref is empty") {
			UriResolver.resolveUri("https://example.com/base", "").shouldBe("https://example.com/base")
		}

		it("returns absolute ref as-is (normalized)") {
			UriResolver.resolveUri("https://example.com/base", "HTTPS://EXAMPLE.COM/A/../x").shouldBe("https://example.com/x")
		}

		it("resolves relative against base with trailing slash") {
			UriResolver.resolveUri("https://example.com/schemas/", "person.json")
				.shouldBe("https://example.com/schemas/person.json")
		}

		it("resolves relative against base file path") {
			UriResolver.resolveUri("https://example.com/schemas/base.json", "../common/other.json")
				.shouldBe("https://example.com/common/other.json")
		}

		it("resolves root-absolute path against base authority") {
			UriResolver.resolveUri("https://example.com/a/b/c.json", "/shared/defs.json")
				.shouldBe("https://example.com/shared/defs.json")
		}

		it("resolves network-path reference (//host/path)") {
			UriResolver.resolveUri("https://example.com/base", "//cdn.example.org/lib/schema.json")
				.shouldBe("https://cdn.example.org/lib/schema.json")
		}

		it("keeps query and fragment in resolved URI") {
			UriResolver.resolveUri("https://example.com/a/", "b.json?x=1#f")
				.shouldBe("https://example.com/a/b.json?x=1#f")
		}

		it("throws on resolution error") {
			shouldThrow<URISyntaxException> { UriResolver.resolveUri("https://example.com/base", "ht t p://bad uri") }
		}
	}

		describe("JsonSchema baseUri and id extraction") {
			it("extracts \$id when present and sets effectiveBaseUri") {
			val s = JsonSchema.fromString("""{"${'$'}id":"https://example.com/s/a.json"}""")
			s.id.shouldBe("https://example.com/s/a.json")
			s.effectiveBaseUri.shouldBe("https://example.com/s/a.json")
		}

			it("uses provided baseUri when \$id missing") {
			val s = JsonSchema.fromString("""{"type":"object"}""", baseUri = "https://example.com/root/")
			s.id.shouldBe(null)
			s.effectiveBaseUri.shouldBe("https://example.com/root/")
		}

		it("JsonSchema.extractId extracts from element") {
			val schema = JsonSchema.fromString("""{"${'$'}id":"https://h.com/x"}""")
			JsonSchema.extractId(schema.schema).shouldBe("https://h.com/x")
		}
	}

	describe("URI identity is preserved (#33)") {
		it("keeps an opaque URI such as a URN whole") {
			UriResolver.normalizeUri("urn:example:schema").shouldBe("urn:example:schema")
			UriResolver.normalizeUri("URN:uuid:deadbeef-1234#frag").shouldBe("urn:uuid:deadbeef-1234#frag")
		}

		it("keeps percent-encoded reserved characters encoded") {
			UriResolver.normalizeUri("https://example.com/a%2Fb").shouldBe("https://example.com/a%2Fb")
			UriResolver.normalizeUri("https://example.com/a?x=%23").shouldBe("https://example.com/a?x=%23")
			UriResolver.normalizeUri("https://example.com/a#x%20y").shouldBe("https://example.com/a#x%20y")
		}

		it("keeps empty path segments, which are part of the path") {
			UriResolver.normalizeUri("https://example.com/a//b").shouldBe("https://example.com/a//b")
		}

		it("lowercases only the scheme and host, not the user info") {
			UriResolver.normalizeUri("HTTPS://User:Secret@EXAMPLE.com:8080/a").shouldBe("https://User:Secret@example.com:8080/a")
		}

		it("still removes dot segments") {
			UriResolver.normalizeUri("https://example.com/a/./b/../c").shouldBe("https://example.com/a/c")
		}

		it("resolves a fragment-only reference against an opaque base") {
			UriResolver.resolveUri("urn:uuid:deadbeef", "#/${'$'}defs/x").shouldBe("urn:uuid:deadbeef#/${'$'}defs/x")
			UriResolver.resolveUri("urn:uuid:deadbeef#old", "#new").shouldBe("urn:uuid:deadbeef#new")
		}

		it("resolves an absolute reference against an opaque base to itself") {
			UriResolver.resolveUri("urn:uuid:deadbeef", "urn:uuid:other").shouldBe("urn:uuid:other")
			UriResolver.resolveUri("urn:uuid:deadbeef", "https://example.com/x").shouldBe("https://example.com/x")
		}

		it("keeps encoded characters when resolving a relative reference") {
			UriResolver.resolveUri("https://example.com/a/b", "c%2Fd").shouldBe("https://example.com/a/c%2Fd")
		}
	}
	describe("RFC 3986 §5.4 reference resolution examples") {
		val base = "http://a/b/c/d;p?q"
		listOf(
			"g:h" to "g:h", "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/",
			"/g" to "http://a/g", "//g" to "http://g", "?y" to "http://a/b/c/d;p?y", "g?y" to "http://a/b/c/g?y",
			"#s" to "http://a/b/c/d;p?q#s", "g#s" to "http://a/b/c/g#s", "g?y#s" to "http://a/b/c/g?y#s",
			";x" to "http://a/b/c/;x", "g;x" to "http://a/b/c/g;x", "g;x?y#s" to "http://a/b/c/g;x?y#s",
			"" to "http://a/b/c/d;p?q", "." to "http://a/b/c/", "./" to "http://a/b/c/", ".." to "http://a/b/",
			"../" to "http://a/b/", "../g" to "http://a/b/g", "../.." to "http://a/", "../../" to "http://a/",
			"../../g" to "http://a/g",
			// Abnormal examples (§5.4.2) that stay within the path grammar
			"../../../g" to "http://a/g", "../../../../g" to "http://a/g", "/./g" to "http://a/g", "/../g" to "http://a/g",
			"g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g", "g.." to "http://a/b/c/g..", "..g" to "http://a/b/c/..g",
			"./../g" to "http://a/b/g", "./g/." to "http://a/b/c/g/", "g/./h" to "http://a/b/c/g/h", "g/../h" to "http://a/b/c/h",
			"g;x=1/./y" to "http://a/b/c/g;x=1/y", "g;x=1/../y" to "http://a/b/c/y",
			"g?y/./x" to "http://a/b/c/g?y/./x", "g?y/../x" to "http://a/b/c/g?y/../x",
			"g#s/./x" to "http://a/b/c/g#s/./x", "g#s/../x" to "http://a/b/c/g#s/../x",
		).forEach { (reference, expected) ->
			it("resolves '$reference' to $expected") {
				UriResolver.resolveUri(base, reference).shouldBe(expected)
			}
		}

		it("keeps the base path for a query-only reference (#58)") {
			UriResolver.resolveUri("https://ex.com/a/b?old", "?new").shouldBe("https://ex.com/a/b?new")
			UriResolver.resolveUri("https://ex.com/a/b#frag", "?new").shouldBe("https://ex.com/a/b?new")
		}
	}
})
