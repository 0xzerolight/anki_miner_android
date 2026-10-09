package com.ankiminer.android.ui.mining

import com.ankiminer.android.dictionary.CurationDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurationDefinitionStateTest {
    private val fresh = CurationDefinitionState().forRequest("run:request:0")

    @Test
    fun `first request dispatches and shows loading`() {
        val transition = fresh.request(DefinitionQuery("猫", null))
        assertEquals(DefinitionQuery("猫", null), transition.dispatch)
        assertEquals(CurationDefinition.Loading, transition.state.visible)
    }

    @Test
    fun `a second request while one is in flight queues only the newest`() {
        val first = fresh.request(DefinitionQuery("猫", null)).state
        val second = first.request(DefinitionQuery("犬", null))
        assertNull(second.dispatch)
        val third = second.state.request(DefinitionQuery("鳥", null))
        assertNull(third.dispatch)
        assertEquals(DefinitionQuery("鳥", null), third.state.pendingQuery)
    }

    @Test
    fun `a superseded result is cached but not painted`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val moved = first.state.request(DefinitionQuery("犬", null))
        val landed =
            moved.state.completed(
                generation = first.generation,
                query = DefinitionQuery("猫", null),
                outcome = CurationDefinition.Missing,
            )
        assertEquals(CurationDefinition.Loading, landed.state.visible)
        assertEquals(CurationDefinition.Missing, landed.state.cache[DefinitionQuery("猫", null)])
    }

    @Test
    fun `a cache hit paints without dispatching`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val loaded =
            first.state.completed(first.generation, DefinitionQuery("猫", null), CurationDefinition.Missing).state
        val again = loaded.request(DefinitionQuery("猫", null))
        assertNull(again.dispatch)
        assertEquals(CurationDefinition.Missing, again.state.visible)
    }

    @Test
    fun `an unavailable result is not cached and refocus retries`() {
        val query = DefinitionQuery("猫", null)
        val first = fresh.request(query)
        val unavailable =
            first.state.completed(first.generation, query, CurationDefinition.Unavailable).state

        assertNull(unavailable.cache[query])
        val retried = unavailable.request(query)
        assertEquals(query, retried.dispatch)
        assertEquals(CurationDefinition.Loading, retried.state.visible)
    }

    @Test
    fun `completing drains the newest queued request`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val queued = first.state.request(DefinitionQuery("犬", null))
        val drained =
            queued.state.completed(first.generation, DefinitionQuery("猫", null), CurationDefinition.Missing)
        assertEquals(DefinitionQuery("犬", null), drained.dispatch)
    }

    @Test
    fun `clearing focus hides the pane without forgetting live work`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val cleared = first.state.request(null).state
        assertNull(cleared.visible)
        assertNull(cleared.pendingQuery)
        // The coroutine for 猫 is still running, so a refocus must queue rather than dispatch a
        // second concurrent lookup.
        assertNull(cleared.request(DefinitionQuery("犬", null)).dispatch)
        assertEquals(DefinitionQuery("犬", null), cleared.request(DefinitionQuery("犬", null)).state.pendingQuery)
    }

    @Test
    fun `a landing result after cleared focus drains the queue without painting`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val cleared = first.state.request(null).state
        val queued = cleared.request(DefinitionQuery("犬", null)).state
        val landed = queued.completed(first.generation, DefinitionQuery("猫", null), CurationDefinition.Missing)
        assertEquals(DefinitionQuery("犬", null), landed.dispatch)
    }

    @Test
    fun `a new curation request resets the reducer and the cache`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val loaded =
            first.state.completed(first.generation, DefinitionQuery("猫", null), CurationDefinition.Missing).state
        val next = loaded.forRequest("run:request:1")
        assertNull(next.visible)
        assertNull(next.inFlight)
        assertEquals(emptyMap<DefinitionQuery, CurationDefinition>(), next.cache)
        assertEquals(DefinitionQuery("猫", null), next.request(DefinitionQuery("猫", null)).dispatch)
    }

    @Test
    fun `an abandoned in-flight lookup cannot block the next request`() {
        val abandoned = fresh.request(DefinitionQuery("猫", null)).state
        val next = abandoned.forRequest(null).forRequest("run:request:1")
        assertEquals(DefinitionQuery("犬", null), next.request(DefinitionQuery("犬", null)).dispatch)
    }

    @Test
    fun `the cache evicts least recently used entries`() {
        var state = fresh
        repeat(MAX_DEFINITION_CACHE + 1) { index ->
            val query = DefinitionQuery("word$index", null)
            val requested = state.request(query)
            state = requested.state.completed(requested.generation, query, CurationDefinition.Missing).state
        }
        assertEquals(MAX_DEFINITION_CACHE, state.cache.size)
        assertNull(state.cache[DefinitionQuery("word0", null)])
    }

    @Test
    fun `rows sharing a mined form but scoped to different lemmas are cached separately`() {
        val say = DefinitionQuery("ゆう", "言う", "動詞")
        val tie = DefinitionQuery("ゆう", "結う", "動詞")
        val first = fresh.request(say)
        val loaded = first.state.completed(first.generation, say, CurationDefinition.Missing).state

        val other = loaded.request(tie)

        assertEquals(tie, other.dispatch)
        assertEquals(CurationDefinition.Loading, other.state.visible)
    }

    @Test
    fun `rows differing only in part of speech are cached separately`() {
        val noun = DefinitionQuery("run", null, "noun")
        val verb = DefinitionQuery("run", null, "verb")
        val first = fresh.request(noun)
        val loaded = first.state.completed(first.generation, noun, CurationDefinition.Missing).state

        assertEquals(verb, loaded.request(verb).dispatch)
    }

    @Test
    fun `a queued homograph is not served from its sibling's landed result`() {
        val say = DefinitionQuery("ゆう", "言う")
        val tie = DefinitionQuery("ゆう", "結う")
        val first = fresh.request(say)
        val queued = first.state.request(tie)

        val drained = queued.state.completed(first.generation, say, CurationDefinition.Missing)

        assertEquals(tie, drained.dispatch)
        assertEquals(CurationDefinition.Loading, drained.state.visible)
    }

    @Test
    fun `a result landing after a reset is discarded`() {
        val first = fresh.request(DefinitionQuery("猫", null))
        val reset = first.state.forRequest("run:request:1")
        val landed = reset.completed(first.generation, DefinitionQuery("猫", null), CurationDefinition.Missing)
        assertNull(landed.state.visible)
        assertNull(landed.state.cache[DefinitionQuery("猫", null)])
        assertNull(landed.dispatch)
    }
}
