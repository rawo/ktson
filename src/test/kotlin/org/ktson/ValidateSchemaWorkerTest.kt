package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * validateSchema runs the meta-schema check on its own large-stack thread (#38). That thread is an
 * implementation detail (#56): it must not inherit the caller's thread-local context, and the caller owns
 * it until it finishes. Like validate(), the call runs to completion; an interrupt is kept for the caller.
 */
class ValidateSchemaWorkerTest :
    DescribeSpec({
        val workerName = "ktson-validateSchema"

        fun liveWorkers() = Thread.getAllStackTraces().keys.count { it.name == workerName && it.isAlive }

        it("does not copy the caller's inheritable thread-locals into its worker") {
            val copies = AtomicInteger()
            val local = object : InheritableThreadLocal<String>() {
                override fun childValue(parentValue: String?): String? {
                    copies.incrementAndGet()
                    return parentValue
                }
            }
            val caller = Thread {
                local.set("request context")
                JsonValidator().validateSchema(JsonSchema.fromString("{}")).isValid shouldBe true
            }
            caller.start()
            caller.join(10_000)
            copies.get() shouldBe 0
        }

        it("completes for an already interrupted caller and keeps its interrupt status") {
            val outcome = AtomicReference<String>()
            val caller = Thread {
                Thread.currentThread().interrupt()
                val valid = runCatching { JsonValidator().validateSchema(JsonSchema.fromString("""{"type":"string"}""")).isValid }
                outcome.set("$valid interrupted=${Thread.currentThread().isInterrupted}")
            }
            caller.start()
            caller.join(10_000)
            outcome.get() shouldBe "Success(true) interrupted=true"
        }

        it("waits for its worker when interrupted mid-check, so no worker outlives the call") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            // An object whose entries block in the worker, holding the check in flight.
            val blocking = JsonObject(
                object : AbstractMap<String, JsonElement>() {
                    override val entries: Set<Map.Entry<String, JsonElement>>
                        get() {
                            if (Thread.currentThread().name == workerName) {
                                entered.countDown()
                                release.await(10, TimeUnit.SECONDS)
                            }
                            return emptySet()
                        }
                },
            )
            val outcome = AtomicReference<String>()
            val caller = Thread {
                val valid = runCatching { JsonValidator().validateSchema(JsonSchema(blocking)).isValid }
                outcome.set("$valid interrupted=${Thread.currentThread().isInterrupted}")
            }
            try {
                caller.start()
                entered.await(10, TimeUnit.SECONDS) shouldBe true
                caller.interrupt()
                caller.join(500)
                caller.isAlive shouldBe true // still waiting for its worker
            } finally {
                release.countDown()
            }
            caller.join(10_000)
            outcome.get() shouldBe "Success(true) interrupted=true"
            liveWorkers() shouldBe 0
        }
    })
