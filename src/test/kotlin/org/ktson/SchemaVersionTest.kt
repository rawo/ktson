package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** `$schema` selects a dialect only when it names a supported meta-schema exactly (#81). */
class SchemaVersionTest :
    DescribeSpec({
        describe("SchemaVersion.fromUri") {
            SchemaVersion.entries.forEach { version ->
                it("recognises ${version.uri}, with or without an empty fragment") {
                    SchemaVersion.fromUri(version.uri) shouldBe version
                    SchemaVersion.fromUri("${version.uri}#") shouldBe version
                }
            }

            listOf(
                "https://json-schema.org/draft/2019-09/schema-custom",
                "https://json-schema.org/draft/2019-09/schema/other",
                "https://json-schema.org/draft/2019-09/schemas",
                "https://json-schema.org/draft/2020-12/schema?x=1",
                "https://json-schema.org/draft/2020-12/schema#/x",
                "https://example.com/custom",
                "",
            ).forEach { uri ->
                it("does not recognise '$uri'") { SchemaVersion.fromUri(uri) shouldBe null }
            }

            it("returns null for no \$schema") { SchemaVersion.fromUri(null) shouldBe null }
        }

        it("keeps the caller's default draft for an unrecognised \$schema") {
            val schema = JsonSchema.fromString(
                """{"${'$'}schema":"https://json-schema.org/draft/2019-09/schema-custom","prefixItems":[false]}""",
                SchemaVersion.DRAFT_2020_12,
            )
            schema.effectiveVersion shouldBe SchemaVersion.DRAFT_2020_12
            JsonValidator().validate(JsonArray(listOf(JsonPrimitive(1))), schema).isValid shouldBe false
        }
    })
