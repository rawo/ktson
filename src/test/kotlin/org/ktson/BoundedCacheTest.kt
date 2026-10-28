package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundedCacheTest :
    DescribeSpec({
        describe("BoundedCache") {
            it("computes a value once and returns the cached instance afterwards") {
                val cache = BoundedCache<String, Any>(maxSize = 4)
                var computed = 0
                val first = cache.getOrPut("k") {
                    computed++
                    Any()
                }
                cache.getOrPut("k") {
                    computed++
                    Any()
                } shouldBeSameInstanceAs first
                computed shouldBe 1
            }

            it("does not cache a null result, so the next call computes again") {
                val cache = BoundedCache<String, String>(maxSize = 4)
                var computed = 0
                cache.getOrPutIfNotNull("k") {
                    computed++
                    null
                } shouldBe null
                cache.getOrPutIfNotNull("k") {
                    computed++
                    "v"
                } shouldBe "v"
                cache.getOrPutIfNotNull("k") {
                    computed++
                    "other"
                } shouldBe "v"
                computed shouldBe 2
            }

            it("never holds more than maxSize entries") {
                val cache = BoundedCache<Int, Int>(maxSize = 8)
                repeat(1_000) { i -> cache.getOrPut(i) { it * 2 } shouldBe i * 2 }
                cache.size shouldBeLessThanOrEqual 8
            }

            it("stays bounded under concurrent use") {
                val cache = BoundedCache<Int, Int>(maxSize = 16)
                val threads = 8
                val pool = Executors.newFixedThreadPool(threads)
                repeat(threads) { t -> pool.submit { repeat(10_000) { i -> cache.getOrPut(t * 10_000 + i) { it } } } }
                pool.shutdown()
                pool.awaitTermination(30, TimeUnit.SECONDS) shouldBe true
                cache.size shouldBeLessThanOrEqual 16
            }
        }
    })
