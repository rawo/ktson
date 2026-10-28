package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.lang.ref.Reference
import java.lang.ref.WeakReference

/**
 * KtSON keeps no state outside a JsonValidator instance: every cache (remote schemas, compiled
 * patterns, numeric limits) is an instance field, and $id registrations last for one validation.
 * So when an application drops a validator, everything it built becomes collectible — the guarantee
 * a long-running service with one validator per schema relies on. A mutable companion object, singleton or top-level value
 * would silently break that; these tests catch it.
 */
class ValidatorLifecycleTest :
    DescribeSpec({
        describe("a dropped validator") {
            it("is collected together with its schema, its loaded remote schemas and its caches") {
                val refs = validateWithEveryCacheAndDrop()
                awaitCollected(refs.all) shouldBe true
            }
        }

        describe("a validator that is still referenced") {
            it("does not keep a schema with \$id it has validated once the application drops that schema") {
                // \$id registrations belong to one validation (#37), so they cannot pin the schemas a
                // long-lived validator has seen.
                val validator = JsonValidator()
                val schemaRef = validateAndDropSchema(validator)
                awaitCollected(listOf(schemaRef)) shouldBe true
                Reference.reachabilityFence(validator)
            }

            it("bounds its pattern and numeric-limit caches however many distinct schemas it validates (#40)") {
                val validator = JsonValidator()
                repeat(5_000) { i ->
                    validator.validate(Json.parseToJsonElement("\"a\""), JsonSchema.fromString("""{"pattern":"^a{0,$i}${'$'}"}"""))
                    validator.validate(Json.parseToJsonElement("1"), JsonSchema.fromString("""{"maximum":$i.5}"""))
                }
                cacheSize(validator, "patternCache") shouldBeLessThanOrEqual 1_024
                cacheSize(validator, "numericLimitCache") shouldBeLessThanOrEqual 1_024
            }
        }
    })

private const val REMOTE_URI = "https://example.com/lifecycle-remote"

/** Weak references to everything a validation built; the strong ones die with the helper's frame. */
private class LifecycleRefs(
    val validator: WeakReference<JsonValidator>,
    val schema: WeakReference<JsonSchema>,
    val schemaElement: WeakReference<JsonElement>,
    val remote: WeakReference<JsonElement>,
) {
    val all: List<WeakReference<*>> get() = listOf(validator, schema, schemaElement, remote)
}

/**
 * Validates an instance that fills every cache — `$id` and relative `$id` registrations, a remote
 * `$ref` through the schemaLoader, a compiled `pattern` and decimal limits — then returns only weak
 * references.
 */
private fun validateWithEveryCacheAndDrop(): LifecycleRefs {
    val remote = Json.parseToJsonElement("""{"${'$'}defs":{"p":{"minimum":0.5}}}""")
    val validator = JsonValidator(schemaLoader = { uri -> if (uri == REMOTE_URI) remote else null })
    val schema = JsonSchema.fromString(
        """
        {"${'$'}id":"https://example.com/lifecycle",
         "properties":{
           "name":{"pattern":"^[a-z]+${'$'}"},
           "n":{"${'$'}ref":"$REMOTE_URI#/${'$'}defs/p"},
           "item":{"${'$'}ref":"item"}
         },
         "${'$'}defs":{"item":{"${'$'}id":"item","maximum":10.25}}}
        """,
    )
    val result = validator.validate(Json.parseToJsonElement("""{"name":"ABC","n":0.1,"item":11}"""), schema)
    // Three failures, one per cache-filling path, so the caches really were populated.
    result.getErrors().map { it.keyword } shouldBe listOf("pattern", "minimum", "maximum")
    return LifecycleRefs(WeakReference(validator), WeakReference(schema), WeakReference(schema.schema), WeakReference(remote))
}

/** Validates a schema with `$id` through [validator] and returns only a weak reference to the schema. */
private fun validateAndDropSchema(validator: JsonValidator): WeakReference<JsonElement> {
    val schema = JsonSchema.fromString("""{"${'$'}id":"https://example.com/kept","type":"string"}""")
    validator.validate(Json.parseToJsonElement("1"), schema).isInvalid shouldBe true
    return WeakReference(schema.schema)
}

/** The number of entries in the validator's private cache [field]. */
private fun cacheSize(validator: JsonValidator, field: String): Int {
    val cache = JsonValidator::class.java.getDeclaredField(field).apply { isAccessible = true }.get(validator)
    return (cache as BoundedCache<*, *>).size
}

/** Requests garbage collection, with allocation pressure, until every reference is cleared or time runs out. */
private fun awaitCollected(refs: List<WeakReference<*>>, timeoutMillis: Long = 10_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (refs.any { it.get() != null } && System.currentTimeMillis() < deadline) {
        System.gc()
        @Suppress("UNUSED_VARIABLE")
        val pressure = ByteArray(4 * 1024 * 1024)
        Thread.sleep(20)
    }
    return refs.all { it.get() == null }
}
