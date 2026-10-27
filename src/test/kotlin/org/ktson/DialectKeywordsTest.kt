package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * A keyword belongs to the dialect that defines it (#43): in the other draft it is an unknown keyword
 * and has no effect. prefixItems and $dynamicRef are 2020-12; $recursiveRef is 2019-09.
 */
class DialectKeywordsTest :
    DescribeSpec({
        fun isValid(schemaJson: String, instanceJson: String, version: SchemaVersion): Boolean = JsonValidator().validate(Json.parseToJsonElement(instanceJson), JsonSchema.fromString(schemaJson, version)).isValid

        val draft2019 = SchemaVersion.DRAFT_2019_09
        val draft2020 = SchemaVersion.DRAFT_2020_12

        describe("prefixItems") {
            it("is ignored in 2019-09") {
                isValid("""{"prefixItems":[false]}""", "[1]", draft2019) shouldBe true
            }

            it("applies in 2020-12") {
                isValid("""{"prefixItems":[false]}""", "[1]", draft2020) shouldBe false
            }

            it("does not mark items as evaluated for unevaluatedItems in 2019-09") {
                isValid("""{"prefixItems":[true],"unevaluatedItems":false}""", "[1]", draft2019) shouldBe false
                isValid("""{"prefixItems":[true],"unevaluatedItems":false}""", "[1]", draft2020) shouldBe true
            }
        }

        describe("\$recursiveRef") {
            it("is ignored in 2020-12") {
                isValid("""{"${'$'}recursiveRef":"https://example.com/missing"}""", "1", draft2020) shouldBe true
            }

            it("applies in 2019-09") {
                isValid("""{"${'$'}recursiveRef":"https://example.com/missing"}""", "1", draft2019) shouldBe false
            }
        }

        describe("\$dynamicRef") {
            it("is ignored in 2019-09") {
                isValid("""{"${'$'}dynamicRef":"https://example.com/missing"}""", "1", draft2019) shouldBe true
            }

            it("applies in 2020-12") {
                isValid("""{"${'$'}dynamicRef":"https://example.com/missing"}""", "1", draft2020) shouldBe false
            }
        }
    })
