package com.ankiminer.android.diagnostics

import com.ankiminer.android.data.resources.ResourceDocumentWriter
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class DiagnosticsExportStep {
    PREPARING,
    BUILDING,
    COPYING,
}

internal enum class DiagnosticsExportFailure {
    BUILD,
    DESTINATION,
    BUNDLE,
    DOCUMENT,
    COPY,
    SHARE,
}

internal class DiagnosticsExportException(
    val kind: DiagnosticsExportFailure,
    cause: Throwable? = null,
) : IOException(kind.name, cause)

internal interface DiagnosticsExporter {
    suspend fun buildBundle(onStep: (DiagnosticsExportStep) -> Unit): StagedBundle

    suspend fun copyToDocument(
        bundle: StagedBundle,
        uri: String,
    )

    fun shareUriFor(bundle: StagedBundle): String

    fun discard(bundle: StagedBundle)

    /**
     * Whether [bundle] still names the file that was staged for it.
     *
     * Staged bundles live in `cacheDir`, which Android reclaims under pressure and which
     * [DiagnosticsBundleJanitor] prunes on every app start, so a handle held across a failed
     * delivery can be dead long before its holder is. Touches the filesystem: call it off the main
     * thread.
     */
    fun isStaged(bundle: StagedBundle): Boolean
}

internal class AndroidDiagnosticsExporter(
    private val stagingRoot: File,
    private val documentWriter: ResourceDocumentWriter,
    private val stageBundle: suspend () -> StagedBundle,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticsExporter {
    override suspend fun buildBundle(onStep: (DiagnosticsExportStep) -> Unit): StagedBundle =
        withContext(ioDispatcher) {
            // No RuntimeWorkCoordinator lease: the most valuable bundle is the one captured while mining is stuck.
            onStep(DiagnosticsExportStep.PREPARING)
            AppLog.i(LogComponent.DIAG, "bundle.build", "outcome" to "ok", "at" to "start")
            try {
                onStep(DiagnosticsExportStep.BUILDING)
                stageBundle().also { bundle ->
                    requireValidBundle(bundle)
                    AppLog.i(
                        LogComponent.DIAG,
                        "bundle.build",
                        "outcome" to "ok",
                        "bytes" to bundle.sizeBytes,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                val mapped =
                    failure as? DiagnosticsExportException
                        ?: DiagnosticsExportException(DiagnosticsExportFailure.BUILD, failure)
                AppLog.e(
                    LogComponent.DIAG,
                    "bundle.build",
                    mapped,
                    "outcome" to "fail",
                    "reason" to mapped.kind.name,
                )
                throw mapped
            }
        }

    override suspend fun copyToDocument(
        bundle: StagedBundle,
        uri: String,
    ) {
        withContext(ioDispatcher) {
            try {
                validateDestination(uri)
                val source = requireValidBundle(bundle)
                documentWriter.open(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                } ?: throw DiagnosticsExportException(DiagnosticsExportFailure.DOCUMENT)
                AppLog.i(
                    LogComponent.DIAG,
                    "bundle.copy",
                    "outcome" to "ok",
                    "bytes" to bundle.sizeBytes,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiagnosticsExportException) {
                logCopyFailure(failure)
                throw failure
            } catch (failure: Throwable) {
                val mapped = DiagnosticsExportException(DiagnosticsExportFailure.COPY, failure)
                logCopyFailure(mapped)
                throw mapped
            }
        }
    }

    override fun shareUriFor(bundle: StagedBundle): String {
        requireValidBundle(bundle)
        val uri = parseUri(bundle.uri, DiagnosticsExportFailure.BUNDLE)
        if (uri.scheme != CONTENT_SCHEME) {
            throw DiagnosticsExportException(DiagnosticsExportFailure.BUNDLE)
        }
        AppLog.i(LogComponent.DIAG, "bundle.share", "outcome" to "ok", "state" to "ready")
        return bundle.uri
    }

    override fun discard(bundle: StagedBundle) {
        val root = canonicalOrNull(stagingRoot) ?: return
        val raw = bundle.file
        val source = canonicalOrNull(raw) ?: return
        if (
            source.toPath().startsWith(root.toPath()) &&
            source != root &&
            !Files.isSymbolicLink(raw.toPath())
        ) {
            source.delete()
        }
    }

    private fun validateDestination(uri: String) {
        val parsed = parseUri(uri, DiagnosticsExportFailure.DESTINATION)
        if (parsed.scheme != CONTENT_SCHEME) {
            throw DiagnosticsExportException(DiagnosticsExportFailure.DESTINATION)
        }
    }

    override fun isStaged(bundle: StagedBundle): Boolean = stagedFileOrNull(bundle) != null

    private fun requireValidBundle(bundle: StagedBundle): File =
        stagedFileOrNull(bundle)
            ?: throw DiagnosticsExportException(DiagnosticsExportFailure.BUNDLE)

    /** The one validation, so a liveness check and a delivery can never disagree about a handle. */
    private fun stagedFileOrNull(bundle: StagedBundle): File? {
        val root = canonicalOrNull(stagingRoot) ?: return null
        val raw = bundle.file
        val source = canonicalOrNull(raw) ?: return null
        if (
            !source.toPath().startsWith(root.toPath()) ||
            source == root ||
            !source.isFile ||
            Files.isSymbolicLink(raw.toPath()) ||
            bundle.sizeBytes <= 0L ||
            source.length() != bundle.sizeBytes ||
            source.length() > MAX_STAGED_BUNDLE_BYTES
        ) {
            return null
        }
        return source
    }

    private fun parseUri(
        value: String,
        failure: DiagnosticsExportFailure,
    ): URI =
        try {
            URI(value)
        } catch (invalid: Exception) {
            throw DiagnosticsExportException(failure, invalid)
        }

    private fun canonicalOrNull(file: File): File? =
        try {
            file.canonicalFile
        } catch (_: IOException) {
            null
        }

    private fun logCopyFailure(failure: DiagnosticsExportException) {
        AppLog.e(
            LogComponent.DIAG,
            "bundle.copy",
            failure,
            "outcome" to "fail",
            "reason" to failure.kind.name,
        )
    }

    private companion object {
        const val CONTENT_SCHEME = "content"
        const val MAX_STAGED_BUNDLE_BYTES = DiagnosticsBundleSpec.TOTAL_BUDGET_BYTES + 1024L * 1024
    }
}
