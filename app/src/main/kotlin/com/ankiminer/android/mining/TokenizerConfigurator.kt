package com.ankiminer.android.mining

import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.TokenizerConfiguration

internal sealed class TokenizerConfigurationFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Required : TokenizerConfigurationFailure("Tokenizer resource is not installed")

    class Dispatch(cause: Throwable) :
        TokenizerConfigurationFailure("Tokenizer setup dispatch failed", cause)

    class Response(cause: Throwable) :
        TokenizerConfigurationFailure("Tokenizer setup response was invalid", cause)

    class Identity :
        TokenizerConfigurationFailure("Tokenizer identity did not match its installed resource")

    class Rejected(val restartRequired: Boolean) :
        TokenizerConfigurationFailure("Tokenizer setup was rejected")

    class Unexpected :
        TokenizerConfigurationFailure("Tokenizer setup returned an invalid response")
}

/** Shared tokenizer wire encoding and strict installed-resource response validation. */
internal class TokenizerConfigurator(
    private val bridge: PyBridge,
    private val provider: InstalledTokenizerResourceProvider,
) {
    fun configureInstalled() {
        val resource = provider.installedResource() ?: throw TokenizerConfigurationFailure.Required()
        configure(resource)
    }

    fun configure(resource: InstalledTokenizerResource) {
        val canonicalDicDir: String
        val raw =
            try {
                canonicalDicDir = resource.dicDir.canonicalPath
                bridge.dispatch(
                    BridgeJsonCodec.encodeTokenizerConfigure(
                        TokenizerConfiguration(
                            dicDir = canonicalDicDir,
                            resourceId = resource.resourceId,
                            treeSha256 = resource.treeSha256,
                            backend = resource.backend,
                        ),
                    ),
                    null,
                )
            } catch (failure: Exception) {
                throw TokenizerConfigurationFailure.Dispatch(failure)
            }
        val decoded =
            try {
                BridgeJsonCodec.decode(raw)
            } catch (failure: RuntimeException) {
                throw TokenizerConfigurationFailure.Response(failure)
            }
        when (decoded) {
            is BridgeMessage.TokenizerReady -> {
                val identity = decoded.identity
                if (
                    identity.dicDir != canonicalDicDir ||
                    identity.resourceId != resource.resourceId ||
                    identity.treeSha256 != resource.treeSha256 ||
                    identity.backend != resource.backend ||
                    identity.fileCount <= 0 ||
                    identity.totalBytes <= 0
                ) {
                    throw TokenizerConfigurationFailure.Identity()
                }
            }
            is BridgeMessage.Error ->
                throw TokenizerConfigurationFailure.Rejected(
                    restartRequired = decoded.code == "tokenizer_restart_required",
                )
            else -> throw TokenizerConfigurationFailure.Unexpected()
        }
    }
}
