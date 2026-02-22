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
})
