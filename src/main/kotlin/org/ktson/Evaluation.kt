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
}
