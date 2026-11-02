package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * DetailedOutput.toJson writes the nested tree without recursion, so serialising a deep result cannot overflow
 * the stack (#85), and produces exactly what the generated serializer does.
 */
class DetailedOutputJsonTest :
    DescribeSpec({
        val reference = Json { encodeDefaults = false }
        fun generated(output: DetailedOutput): String = reference.encodeToString(DetailedOutput.serializer(), output)

        val hostile = "quote \" backslash \\ newline \n tab \t control \u0001 non-ASCII żółw ✓  "
        fun leaf(n: Int, uri: String? = null) = DetailedOutput(false, "/k$n", "/i$n", "leaf $n $hostile", absoluteKeywordLocation = uri)

        listOf(
            "a valid root" to DetailedOutput(true, "", ""),
            "a leaf with every field" to leaf(1, "https://example.com/s#/k1"),
            "a node with an empty errors list" to DetailedOutput(false, "", "", errors = emptyList()),
            "a branching tree" to DetailedOutput(
                false,
                "",
                "",
                errors = listOf(
                    DetailedOutput(false, "/a", "/x", "a", listOf(leaf(1), leaf(2, "urn:x#/k2")), "urn:x#/a"),
                    leaf(3),
                    DetailedOutput(false, "/b", "", null, listOf(DetailedOutput(false, "/c", "", "c", listOf(leaf(4))))),
                ),
            ),
        ).forEach { (name, output) ->
            it("writes the same JSON as the generated serializer for $name") {
                output.toJson() shouldBe generated(output)
            }
        }

        it("writes a 100,000-level tree without recursing") {
            val depth = 100_000
            val deepest = leaf(0, "urn:x#/deep")
            var node = deepest
            repeat(depth) { node = DetailedOutput(false, "/allOf/0", "", "branch", listOf(node), "urn:x#/n") }
            // Every level writes the same prefix before its child and the same suffix after it
            val prefix = """{"valid":false,"keywordLocation":"/allOf/0","instanceLocation":"","error":"branch","errors":["""
            val suffix = """],"absoluteKeywordLocation":"urn:x#/n"}"""
            node.toJson() shouldBe prefix.repeat(depth) + generated(deepest) + suffix.repeat(depth)
        }
    })
