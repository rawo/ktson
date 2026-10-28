package org.ktson

import kotlinx.serialization.json.JsonElement

/**
 * The state of one validation: its root schema and the `$id` registrations made while evaluating it.
 * A validator creates one per validate() call, so what it learns about a schema element (the absolute
 * URI of a relative `$id`, for one) can neither leak into a validation of the same element under another
 * base URI (#37) nor accumulate for the validator's lifetime (#40). Remote schemas come from [store].
 */
internal class Evaluation(val rootSchema: JsonElement, store: SchemaStore) {
    val references = ReferenceResolver(store)
    val absoluteLocations = AbsoluteLocations(references)

    /**
     * How many results so far could not be decided: the depth limit was reached, or a regex could not be
     * evaluated. Such a result fails closed, which is right for an assertion but wrong where an applicator
     * turns failure into success (not, the if condition, oneOf, maxContains): those check [leftUndecided] (#51).
     */
    var undecided = 0
        private set

    fun markUndecided() {
        undecided++
    }

    /** Runs [block] and tells whether any result inside it could not be decided. */
    inline fun leftUndecided(block: () -> Unit): Boolean {
        val before = undecided
        block()
        return undecided != before
    }
}
