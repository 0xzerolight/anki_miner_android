package com.ankiminer.android.mining

import android.content.Context
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiProviderCallbacks
import com.ankiminer.android.anki.provider.CancellationRegistration
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafJobFileOwner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

internal data class InstalledTokenizerResource(
    val dicDir: File,
    val resourceId: String,
    val treeSha256: String,
    val backend: String = "s1a",
) {
    init {
        require(dicDir.isAbsolute)
        require(resourceId.isNotBlank())
        require(TREE_SHA_256.matches(treeSha256))
        require(backend == "s1a")
    }

    private companion object {
        val TREE_SHA_256 = Regex("[0-9a-f]{64}")
    }
}

/** A cheap read of an installer-verified, immutable resource-catalog entry. */
internal fun interface InstalledTokenizerResourceProvider {
    fun installedResource(): InstalledTokenizerResource?
}

/** Trusted catalog identity for the settled UniDic Lite resource; Python re-hashes the full tree. */
internal class BuiltInInstalledTokenizerResourceProvider(
    filesDir: File,
) : InstalledTokenizerResourceProvider {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val resourceRoot = File(filesDir, RESOURCE_RELATIVE_ROOT)

    override fun installedResource(): InstalledTokenizerResource? {
        val marker = File(resourceRoot, COMPLETE_MARKER)
        val dicDir = File(resourceRoot, DICDIR_NAME)
        if (
            !marker.isFile ||
            marker.length() !in 1..MAX_MARKER_BYTES ||
            !dicDir.isDirectory ||
            Files.isSymbolicLink(resourceRoot.toPath()) ||
            Files.isSymbolicLink(marker.toPath()) ||
            Files.isSymbolicLink(dicDir.toPath())
        ) {
            return null
        }
        val complete =
            try {
                marker.readText(Charsets.UTF_8) == COMPLETE_MARKER_CONTENT
            } catch (_: Exception) {
                false
            }
        if (!complete) return null
        return InstalledTokenizerResource(
            dicDir = dicDir,
            resourceId = RESOURCE_ID,
            treeSha256 = TREE_SHA_256,
        )
    }

    internal companion object {
        const val RESOURCE_ID = "unidic-lite-1.0.8"
        const val TREE_SHA_256 = "bd942f1b395aa7c56fe20321dc7f021930e29107f6b2949a49f5c56caab55ea7"
        const val RESOURCE_RELATIVE_ROOT = "resources/tokenizer/$RESOURCE_ID"
        const val DICDIR_NAME = "dicdir"
        const val COMPLETE_MARKER = "install.complete"
        const val COMPLETE_MARKER_CONTENT =
            "anki-miner-tokenizer-v1\nresourceId=$RESOURCE_ID\ntreeSha256=$TREE_SHA_256\n"
        private const val MAX_MARKER_BYTES = 256L
    }
}

internal data class MiningRuntimePaths(
    val cacheDir: File,
    val nativeLibraryDir: File,
) {
    init {
        require(cacheDir.isAbsolute)
        require(nativeLibraryDir.isAbsolute)
    }
}

internal interface MiningInputOwner : AutoCloseable {
    fun openVideo(source: MiningSource): String

    fun materializeSubtitle(source: MiningSource): String
}

internal fun interface MiningInputOwnerFactory {
    fun create(): MiningInputOwner
}

internal class AndroidMiningInputOwnerFactory(context: Context) : MiningInputOwnerFactory {
    private val applicationContext = context.applicationContext

    override fun create(): MiningInputOwner =
        object : MiningInputOwner {
            private val owner = SafJobFileOwner(applicationContext)

            override fun openVideo(source: MiningSource): String =
                owner.openVideoUri(source.uri).path

            override fun materializeSubtitle(source: MiningSource): String =
                owner.materializeSubtitleUri(source.uri, source.displayName).path

            override fun close() = owner.close()
        }
}

internal fun interface MiningTaskExecutor {
    fun execute(task: () -> Unit)
}

internal fun Executor.asMiningTaskExecutor(): MiningTaskExecutor =
    MiningTaskExecutor { task -> execute(task) }

internal fun interface SourceGrantReleaser {
    fun release(uri: String)
}

internal class SafSourceGrantReleaser(
    private val broker: SafBroker,
) : SourceGrantReleaser {
    override fun release(uri: String) {
        kotlinx.coroutines.runBlocking { broker.releaseReadAccess(uri) }
    }
}

internal interface CoordinatorAnkiCallbacks {
    fun registerRun(
        runId: String,
        cancellation: AnkiCancellation,
    ): Boolean

    fun verifyTarget(rawRequest: String): String

    fun scanFirstFields(rawRequest: String): String

    fun storeMedia(rawRequest: String): String

    fun createNotes(rawRequest: String): String

    fun releaseRunState(rawRequest: String): String

    fun releaseRunStateFallback(runId: String): ReleaseState
}

internal class ProviderCoordinatorAnkiCallbacks(
    private val delegate: AnkiProviderCallbacks,
) : CoordinatorAnkiCallbacks {
    override fun registerRun(
        runId: String,
        cancellation: AnkiCancellation,
    ): Boolean = delegate.registerRun(runId, cancellation)

    override fun verifyTarget(rawRequest: String): String = delegate.ankiVerifyTarget(rawRequest)

    override fun scanFirstFields(rawRequest: String): String = delegate.ankiScanFirstFields(rawRequest)

    override fun storeMedia(rawRequest: String): String = delegate.ankiStoreMedia(rawRequest)

    override fun createNotes(rawRequest: String): String = delegate.ankiCreateNotes(rawRequest)

    override fun releaseRunState(rawRequest: String): String = delegate.ankiReleaseRunState(rawRequest)

    override fun releaseRunStateFallback(runId: String): ReleaseState =
        delegate.releaseRunStateFallback(runId)
}

/** Thread-safe cancellation shared by Python job control and Anki provider operations. */
internal class CoordinatorAnkiCancellation : AnkiCancellation {
    private val monitor = Any()
    private var cancelled = false
    private var nextRegistration = 1L
    private val listeners = linkedMapOf<Long, () -> Unit>()

    override fun isCancelled(): Boolean = synchronized(monitor) { cancelled }

    override fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration {
        val registration: Long?
        synchronized(monitor) {
            if (cancelled) {
                registration = null
            } else {
                registration = nextRegistration++
                listeners[registration] = listener
            }
        }
        if (registration == null) listenerSafely(listener)
        return CancellationRegistration {
            if (registration != null) synchronized(monitor) { listeners.remove(registration) }
        }
    }

    fun cancel(): Boolean {
        val callbacks =
            synchronized(monitor) {
                if (cancelled) return false
                cancelled = true
                listeners.values.toList().also { listeners.clear() }
            }
        callbacks.forEach(::listenerSafely)
        return true
    }

    private fun listenerSafely(listener: () -> Unit) {
        try {
            listener()
        } catch (_: RuntimeException) {
            // Cancellation remains sticky even if a provider listener has already torn down.
        }
    }
}
