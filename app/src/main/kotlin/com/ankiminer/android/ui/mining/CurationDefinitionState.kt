package com.ankiminer.android.ui.mining

import com.ankiminer.android.dictionary.CurationDefinition

/** Terms retained per curation request. A page holds at most 100 candidates. */
internal const val MAX_DEFINITION_CACHE = 64

internal data class DefinitionQuery(
    val term: String,
    val fallbackTerm: String?,
)

/**
 * Preview bookkeeping for the focused curation row.
 *
 * Reproduces the three desktop guarantees a debounce alone cannot give: one request in flight with
 * only the NEWEST queued behind it, so a fast scroll never builds a backlog of dead lookups; a
 * generation stamp, so a result landing after the user moved on is cached but never painted; and a
 * cached miss is a real answer, which is why "unresolved" is `null` rather than falsiness.
 *
 * [cacheKey] scopes everything to one curation request. A page advance, a new run or the end of
 * curation goes through [forRequest], which drops the in-flight marker too — otherwise a lookup
 * abandoned mid-flight would leave the reducer permanently convinced one was still running.
 */
internal data class CurationDefinitionState(
    val cacheKey: String? = null,
    val generation: Long = 0,
    val inFlight: DefinitionQuery? = null,
    val pendingQuery: DefinitionQuery? = null,
    val cache: Map<String, CurationDefinition> = emptyMap(),
    val cacheOrder: List<String> = emptyList(),
    val visible: CurationDefinition? = null,
)

internal data class CurationDefinitionTransition(
    val state: CurationDefinitionState,
    val dispatch: DefinitionQuery?,
    val generation: Long,
)

/** Rebinds to a curation request identity, resetting everything when it changes. */
internal fun CurationDefinitionState.forRequest(cacheKey: String?): CurationDefinitionState =
    if (cacheKey == this.cacheKey) {
        this
    } else {
        // The generation keeps counting up so a result dispatched under the old key can never be
        // mistaken for a current one.
        CurationDefinitionState(cacheKey = cacheKey, generation = generation + 1)
    }

/** Focus moved to [query], or away from every row when it is null. */
internal fun CurationDefinitionState.request(
    query: DefinitionQuery?,
): CurationDefinitionTransition {
    // Bump on EVERY request, cache hit included: a newer request must supersede whatever is in
    // flight, or a slower earlier miss would repaint over the row the user is now looking at.
    val next = generation + 1
    if (query == null) {
        // inFlight is deliberately NOT cleared: the coroutine is still running. Forgetting it here
        // would let a refocus dispatch a second concurrent lookup, breaking the one-in-flight
        // invariant this type exists to hold. Only [forRequest] retires live work, and only
        // because it also cancels the job that owns it.
        return CurationDefinitionTransition(
            state = copy(generation = next, pendingQuery = null, visible = null),
            dispatch = null,
            generation = next,
        )
    }
    cache[query.term]?.let { cached ->
        return CurationDefinitionTransition(
            state = copy(generation = next, pendingQuery = null, visible = cached).touch(query.term),
            dispatch = null,
            generation = next,
        )
    }
    if (inFlight != null) {
        return CurationDefinitionTransition(
            state = copy(generation = next, pendingQuery = query, visible = CurationDefinition.Loading),
            dispatch = null,
            generation = next,
        )
    }
    return CurationDefinitionTransition(
        state =
            copy(
                generation = next,
                inFlight = query,
                pendingQuery = null,
                visible = CurationDefinition.Loading,
            ),
        dispatch = query,
        generation = next,
    )
}

/** A dispatched lookup landed. [generation] is the stamp it was dispatched under. */
internal fun CurationDefinitionState.completed(
    generation: Long,
    query: DefinitionQuery,
    outcome: CurationDefinition,
): CurationDefinitionTransition {
    // A result whose in-flight marker is gone belongs to a superseded curation request: it must
    // not be cached, because its terms were resolved against a different run's dictionaries.
    if (inFlight != query) {
        return CurationDefinitionTransition(this, dispatch = null, generation = this.generation)
    }
    // Cache even a superseded result: it was a correct answer for its term, and scrolling back to
    // that row must not re-query.
    val cached = store(query.term, outcome)
    val current = if (generation == this.generation) outcome else cached.visible
    val queued = cached.pendingQuery
    if (queued == null) {
        return CurationDefinitionTransition(
            state = cached.copy(inFlight = null, visible = current),
            dispatch = null,
            generation = this.generation,
        )
    }
    cached.cache[queued.term]?.let { hit ->
        return CurationDefinitionTransition(
            state = cached.copy(inFlight = null, pendingQuery = null, visible = hit).touch(queued.term),
            dispatch = null,
            generation = this.generation,
        )
    }
    return CurationDefinitionTransition(
        state = cached.copy(inFlight = queued, pendingQuery = null, visible = CurationDefinition.Loading),
        dispatch = queued,
        generation = this.generation,
    )
}

private fun CurationDefinitionState.store(
    term: String,
    outcome: CurationDefinition,
): CurationDefinitionState {
    val order = cacheOrder.filterNot { it == term } + term
    val entries = cache + (term to outcome)
    if (order.size <= MAX_DEFINITION_CACHE) return copy(cache = entries, cacheOrder = order)
    val evicted = order.first()
    return copy(cache = entries - evicted, cacheOrder = order.drop(1))
}

private fun CurationDefinitionState.touch(term: String): CurationDefinitionState =
    copy(cacheOrder = cacheOrder.filterNot { it == term } + term)
