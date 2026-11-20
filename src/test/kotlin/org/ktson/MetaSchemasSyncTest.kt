package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `validateSchema` uses the meta-schemas embedded in [MetaSchemas], while the official suite resolves json-schema.org
 * references from `src/test/resources/schemas/draft`. Both must be the same documents, or a drifted copy would go
 * unnoticed (#113).
 */
class MetaSchemasSyncTest :
    DescribeSpec({
        val resources = File(MetaSchemasSyncTest::class.java.getResource("/schemas/draft")!!.toURI())
            .walk()
            .filter { it.isFile && it.extension == "json" }
            .map { Json.parseToJsonElement(it.readText()) as JsonObject }
            .associateBy { it.stringKeyword(SchemaKeywords.ID)!! }

        it("embeds exactly the meta-schemas in the test resources") {
            MetaSchemas.uris shouldContainExactlyInAnyOrder resources.keys
        }

        resources.forEach { (uri, document) ->
            it("embeds $uri as in the test resources") {
                MetaSchemas.load(uri) shouldBe document
            }
        }

        it("names each draft's meta-schema by its SchemaVersion uri") {
            SchemaVersion.entries.forEach { version -> MetaSchemas.load(version.uri)?.let { (it as JsonObject).stringKeyword(SchemaKeywords.ID) } shouldBe version.uri }
        }
    })
