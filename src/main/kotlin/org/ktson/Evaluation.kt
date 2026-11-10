package org.ktson

import kotlinx.serialization.json.JsonElement
import java.util.IdentityHashMap

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

    /**
     * The instances whose unevaluated* keywords are being evaluated, innermost last. Instances only descend along
     * the call stack, so the last one is the only one a branch verdict can be useful to.
     */
    private val collectors = ArrayList<JsonElement>()

    /** Branch verdicts per instance in [collectors]; dropped when the instance leaves the stack, so memory stays bounded. */
    private val verdicts = IdentityHashMap<JsonElement, HashMap<Application, Boolean>>()

    /**
     * What each schema applied at an instance in [collectors] evaluated (property names or item indices), kept and
     * dropped with [verdicts]. Every level of nested unevaluated* collects its subtree, so without them each level
     * walked all the levels below it again (#106).
     */
    private val evaluatedSets = IdentityHashMap<JsonElement, HashMap<Application, Set<*>>>()

    /**
     * Starts the evaluation of a schema with unevaluated* against [instance]. Until the matching [leaveCollector],
     * the verdicts of anyOf/oneOf branches and if conditions at [instance] are remembered, so the collection of
     * evaluated properties and items does not validate them again: at every nesting level that doubled the work (#30).
     */
    fun enterCollector(instance: JsonElement) {
        collectors.add(instance)
    }

    fun leaveCollector(instance: JsonElement) {
        collectors.removeAt(collectors.lastIndex)
        if (collectors.lastOrNull() !== instance) {
            verdicts.remove(instance)
            evaluatedSets.remove(instance)
        }
    }

    /** Whether a verdict at [instance] can be needed by a collection pass, and is worth remembering. */
    fun collects(instance: JsonElement): Boolean = collectors.isNotEmpty() && collectors.last() === instance

    fun knownVerdict(application: Application): Boolean? = verdicts[application.instance]?.get(application)

    /**
     * Remembers a decided verdict at the instance [collects] accepts. Only decided verdicts may be passed: an undecided
     * one must be reached, and marked, again.
     */
    fun rememberVerdict(application: Application, valid: Boolean) {
        verdicts.getOrPut(application.instance) { HashMap() }[application] = valid
    }

    /** The members [application] evaluated, if collected before: property names at an object, indices at an array. */
    @Suppress("UNCHECKED_CAST")
    fun <T> knownEvaluated(application: Application): Set<T>? = evaluatedSets[application.instance]?.get(application) as Set<T>?

    /**
     * Remembers what [application] evaluated, at the instance [collects] accepts. As for [rememberVerdict], only a
     * collection with no undecided result inside may be passed.
     */
    fun rememberEvaluated(application: Application, evaluated: Set<*>) {
        evaluatedSets.getOrPut(application.instance) { HashMap() }[application] = evaluated
    }
}

/**
 * A schema applied to an instance node, with everything else its verdict depends on: the resource it is evaluated
 * in and that resource's base, the dynamic scope (`$dynamicRef`, `$recursiveRef`) and the depth (the depth limit).
 * Elements are compared by identity: equal JSON at two locations is two applications.
 */
internal class Application(
    val schema: JsonElement,
    val instance: JsonElement,
    val resourceRoot: JsonElement,
    val base: String,
    val dynamicScope: List<JsonElement>,
    val depth: Int,
) {
    override fun equals(other: Any?): Boolean = other is Application &&
        schema === other.schema &&
        instance === other.instance &&
        resourceRoot === other.resourceRoot &&
        depth == other.depth &&
        base == other.base &&
        dynamicScope.size == other.dynamicScope.size &&
        dynamicScope.indices.all { dynamicScope[it] === other.dynamicScope[it] }

    override fun hashCode(): Int {
        var hash = System.identityHashCode(schema)
        hash = 31 * hash + System.identityHashCode(instance)
        hash = 31 * hash + System.identityHashCode(resourceRoot)
        hash = 31 * hash + depth
        hash = 31 * hash + base.hashCode()
        for (resource in dynamicScope) hash = 31 * hash + System.identityHashCode(resource)
        return hash
    }
}
