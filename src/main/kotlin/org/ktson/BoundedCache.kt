package org.ktson

import java.util.concurrent.ConcurrentHashMap

/**
 * A thread-safe cache holding at most [maxSize] entries, for values derived from schema content
 * (compiled patterns, parsed numeric limits). Without a bound, a long-lived validator fed ever new
 * schemas would keep one entry per distinct pattern or limit forever (#40).
 *
 * When full, an arbitrary entry is evicted to make room. Eviction only costs a recomputation, so no
 * recency bookkeeping (and no lock on reads) is spent on choosing a better victim.
 */
internal class BoundedCache<K : Any, V : Any>(private val maxSize: Int) {
    init {
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    private val entries = ConcurrentHashMap<K, V>()

    val size: Int get() = entries.size

    /**
     * The value cached for [key], or the result of [compute], which is then cached. Concurrent callers
     * may compute the same key more than once; the first value inserted wins.
     */
    fun getOrPut(key: K, compute: (K) -> V): V = entries[key] ?: insert(key, compute(key))

    /** Like [getOrPut], but a null result of [compute] is returned and not cached, so the next call computes again. */
    fun getOrPutIfNotNull(key: K, compute: (K) -> V?): V? = entries[key] ?: compute(key)?.let { insert(key, it) }

    private fun insert(key: K, value: V): V {
        val cached = entries.putIfAbsent(key, value) ?: value
        // Trim after inserting: every insert is followed by its own trim, so once concurrent callers
        // finish the cache is back within maxSize. The new entry itself may be the one evicted.
        while (entries.size > maxSize) {
            val victim = entries.keys.firstOrNull() ?: break
            entries.remove(victim)
        }
        return cached
    }
}
