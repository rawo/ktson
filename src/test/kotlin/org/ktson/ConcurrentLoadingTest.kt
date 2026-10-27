package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A validator loads each remote schema once (#41): concurrent validations needing the same URI must
 * not each call schemaLoader, and none may see the schema before its URI is registered.
 */
class ConcurrentLoadingTest :
    DescribeSpec({
        describe("concurrent remote loading") {
            it("calls schemaLoader once for a URI needed by many threads at the same time") {
                val loads = AtomicInteger()
                val validator = JsonValidator(
                    schemaLoader = { uri ->
                        loads.incrementAndGet()
                        Thread.sleep(200) // keep the load in flight while the other threads arrive
                        if (uri == "https://example.com/remote") Json.parseToJsonElement("""{"${'$'}defs":{"n":{"type":"integer"}},"${'$'}ref":"#/${'$'}defs/n"}""") else null
                    },
                )
                val schema = JsonSchema.fromString("""{"${'$'}ref":"https://example.com/remote"}""")
                val threads = 8
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(threads)
                try {
                    val results = (1..threads).map {
                        pool.submit(
                            Callable {
                                start.await()
                                validator.validate(JsonPrimitive(1), schema).isValid
                            },
                        )
                    }
                    start.countDown()
                    results.map { it.get(10, TimeUnit.SECONDS) } shouldContainOnly listOf(true)
                    loads.get() shouldBe 1
                } finally {
                    pool.shutdownNow()
                }
            }

            it("does not cache a load that found nothing, so a later validation tries again") {
                val loads = AtomicInteger()
                val validator = JsonValidator(schemaLoader = { if (loads.incrementAndGet() == 1) null else Json.parseToJsonElement("""{"type":"integer"}""") })
                val schema = JsonSchema.fromString("""{"${'$'}ref":"https://example.com/flaky"}""")
                validator.validate(JsonPrimitive(1), schema).isValid shouldBe false
                validator.validate(JsonPrimitive(1), schema).isValid shouldBe true
                loads.get() shouldBe 2
            }
        }
    })
