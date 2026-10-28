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

        describe("a schemaLoader that throws while other validations wait for it (#65)") {
            it("gives every waiting validation the same exception as the one that ran the loader") {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val validator = JsonValidator(schemaLoader = {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    throw java.io.IOException("loader failed")
                })
                val schema = JsonSchema.fromString("""{"${'$'}ref":"https://example.com/remote"}""")
                fun outcome(): String = runCatching { validator.validate(JsonPrimitive(1), schema).isValid.toString() }
                    .getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                val pool = Executors.newFixedThreadPool(4)
                try {
                    val owner = pool.submit(Callable { outcome() })
                    entered.await(10, TimeUnit.SECONDS) shouldBe true
                    val waiters = (1..3).map { pool.submit(Callable { outcome() }) }
                    Thread.sleep(200) // let the waiters reach the in-flight load
                    release.countDown()
                    (listOf(owner) + waiters).map { it.get(10, TimeUnit.SECONDS) } shouldContainOnly listOf("IOException: loader failed")
                } finally {
                    release.countDown()
                    pool.shutdown()
                }
            }
        }

        describe("a schemaLoader that validates through the same validator (#55)") {
            it("can load another URI, even one whose String hash collides with the URI being loaded") {
                val first = "https://ex.com/Aa"
                val second = "https://ex.com/BB"
                first.hashCode() shouldBe second.hashCode()
                lateinit var validator: JsonValidator
                validator = JsonValidator(schemaLoader = { uri ->
                    if (uri == first) validator.validate(JsonPrimitive(1), JsonSchema.fromString("""{"${'$'}ref":"$second"}""")).isValid shouldBe true
                    Json.parseToJsonElement("""{"type":"integer"}""")
                })
                validator.validate(JsonPrimitive(1), JsonSchema.fromString("""{"${'$'}ref":"$first"}""")).isValid shouldBe true
                validator.validate(JsonPrimitive("x"), JsonSchema.fromString("""{"${'$'}ref":"$second"}""")).isValid shouldBe false
            }

            it("treats a load of the URI it is already loading as unresolvable instead of waiting forever") {
                val uri = "https://ex.com/cycle"
                val nested = mutableListOf<Boolean>()
                lateinit var validator: JsonValidator
                validator = JsonValidator(schemaLoader = { requested ->
                    nested += validator.validate(JsonPrimitive(1), JsonSchema.fromString("""{"${'$'}ref":"$requested"}""")).isValid
                    Json.parseToJsonElement("""{"type":"integer"}""")
                })
                validator.validate(JsonPrimitive(1), JsonSchema.fromString("""{"${'$'}ref":"$uri"}""")).isValid shouldBe true
                nested shouldBe listOf(false)
            }

            it("lets a later validation load a URI whose loader threw") {
                val loads = AtomicInteger()
                val validator = JsonValidator(schemaLoader = {
                    if (loads.incrementAndGet() == 1) error("network down")
                    Json.parseToJsonElement("""{"type":"integer"}""")
                })
                val schema = JsonSchema.fromString("""{"${'$'}ref":"https://example.com/flaky"}""")
                runCatching { validator.validate(JsonPrimitive(1), schema) }.isFailure shouldBe true
                validator.validate(JsonPrimitive(1), schema).isValid shouldBe true
            }
        }
    })
