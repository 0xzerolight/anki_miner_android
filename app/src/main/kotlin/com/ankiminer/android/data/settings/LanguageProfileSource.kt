package com.ankiminer.android.data.settings

import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.PyBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The vendored mining languages, as `language.profiles` reports them, in registry order.
 *
 * Asked fresh each time rather than cached: a profile's unavailable reason is computed when the
 * bridge answers, so a language pack installed since the last call reads as available. A failure is
 * a [Result], never a throw; the settings screen then offers only the language already active.
 */
fun interface LanguageProfileSource {
    suspend fun profiles(): Result<List<LanguageProfileInfo>>
}

/** Off the main thread, as every bridge dispatch must be; the call waits for Python to start. */
class BridgeLanguageProfileSource(
    private val bridge: PyBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LanguageProfileSource {
    override suspend fun profiles(): Result<List<LanguageProfileInfo>> =
        withContext(dispatcher) {
            runCatching {
                val reply = BridgeJsonCodec.decode(bridge.dispatch(BridgeJsonCodec.encodeLanguageProfilesRequest(), null))
                check(reply is BridgeMessage.LanguageProfilesResult) { "Unexpected reply to language.profiles" }
                reply.profiles
            }
        }
}
