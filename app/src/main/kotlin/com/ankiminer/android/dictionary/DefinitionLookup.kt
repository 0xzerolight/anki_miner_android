package com.ankiminer.android.dictionary

import com.ankiminer.android.diagnostics.log.LogContext
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.DefinitionEntry
import com.ankiminer.android.engine.PyBridge
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class DefinitionResult(
    val term: String,
    val matchedTerm: String,
    val entries: List<DefinitionEntry>,
)

/** What the curation row shows for the focused word. */
sealed interface CurationDefinition {
    /** A lookup is in flight. */
    data object Loading : CurationDefinition

    /** Hits, in chain order. [matchedTerm] differs from the request on a lemma retry. */
    data class Loaded(
        val matchedTerm: String,
        val entries: List<DefinitionEntry>,
    ) : CurationDefinition

    /** A real answer: no offline dictionary has this term. */
    data object Missing : CurationDefinition

    /** The lookup failed -- no dictionary installed, finished run, bridge error. */
    data object Unavailable : CurationDefinition
}

/**
 * On-demand offline definition lookup for the curation screen.
 *
 * Not a [com.ankiminer.android.data.resources.ResourceManager] operation: `runOperation` takes the
 * exclusive [com.ankiminer.android.data.RuntimeWorkCoordinator] lease, and mining holds it for the
 * whole run, so a resource-lane lookup can never run while the user is curating. The resource
 * *executor* is reused precisely because that same lease makes it provably idle then.
 *
 * Failure is a [Result], never a throw: the preview degrades; curation continues.
 */
fun interface DefinitionLookupService {
    suspend fun define(
        runId: String,
        term: String,
        fallbackTerm: String?,
    ): Result<DefinitionResult>
}

class BridgeDefinitionLookupService(
    private val bridge: PyBridge,
    private val executor: Executor,
) : DefinitionLookupService {
    override suspend fun define(
        runId: String,
        term: String,
        fallbackTerm: String?,
    ): Result<DefinitionResult> =
        suspendCancellableCoroutine { continuation ->
            executor.execute {
                LogContext.withRunId(runId) {
                    if (!continuation.isActive) return@withRunId
                    val outcome =
                        runCatching {
                            val raw =
                                bridge.dispatch(
                                    BridgeJsonCodec.encodeDictionaryDefineRequest(
                                        runId,
                                        term,
                                        fallbackTerm,
                                    ),
                                    null,
                                )
                            val message =
                                BridgeJsonCodec.decode(
                                    raw,
                                    expectedRunId = runId,
                                )
                            check(message is BridgeMessage.DictionaryDefineResult) {
                                "Unexpected reply to dictionary.define"
                            }
                            // Refuse mismatched echoes at the seam so stale data cannot be painted
                            // under another word even if the caller's generation check regresses.
                            check(message.term == term) {
                                "dictionary.define echoed another term"
                            }
                            check(message.matchedTerm == term || message.matchedTerm == fallbackTerm) {
                                "dictionary.define matched a term outside the query"
                            }
                            DefinitionResult(message.term, message.matchedTerm, message.entries)
                        }
                    if (continuation.isActive) continuation.resume(outcome)
                }
            }
        }
}
