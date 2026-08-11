package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.DiagnosticsExportException
import com.ankiminer.android.diagnostics.DiagnosticsExportFailure
import com.ankiminer.android.diagnostics.DiagnosticsExportStep
import com.ankiminer.android.diagnostics.DiagnosticsExporter
import com.ankiminer.android.diagnostics.StagedBundle
import com.ankiminer.android.localization.LocalizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal sealed interface DiagnosticsExportState {
    data object Idle : DiagnosticsExportState

    data class Working(val step: DiagnosticsExportStep) : DiagnosticsExportState

    data class Ready(
        val bundle: StagedBundle,
    ) : DiagnosticsExportState

    data class Failed(val message: LocalizedStringResource) : DiagnosticsExportState
}

internal class DiagnosticsViewModel(
    private val exporter: DiagnosticsExporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val exportMutex = Mutex()
    private val mutableState = MutableStateFlow<DiagnosticsExportState>(DiagnosticsExportState.Idle)
    val state: StateFlow<DiagnosticsExportState> = mutableState.asStateFlow()

    private var pendingBundle: StagedBundle? = null

    fun export() {
        launchExclusive {
            pendingBundle?.let { bundle ->
                // The staged ZIP lives in cacheDir, which the platform reclaims and the janitor
                // prunes, so the handle kept for a second delivery attempt may name a file that is
                // no longer there. Republishing it made export and Retry fail identically for the
                // rest of this ViewModel's life.
                if (withContext(ioDispatcher) { exporter.isStaged(bundle) }) {
                    mutableState.value = DiagnosticsExportState.Ready(bundle)
                    return@launchExclusive
                }
                pendingBundle = null
            }
            mutableState.value = DiagnosticsExportState.Working(DiagnosticsExportStep.PREPARING)
            try {
                val bundle =
                    exporter.buildBundle { step ->
                        mutableState.value = DiagnosticsExportState.Working(step)
                    }
                pendingBundle = bundle
                mutableState.value = DiagnosticsExportState.Ready(bundle)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiagnosticsExportException) {
                publishFailure(failure.kind)
            } catch (_: Throwable) {
                publishFailure(DiagnosticsExportFailure.BUILD)
            }
        }
    }

    fun deliverShare(launch: (uri: String, fileName: String) -> Boolean) {
        val ready = mutableState.value as? DiagnosticsExportState.Ready ?: return
        launchExclusive {
            try {
                val uri = withContext(ioDispatcher) {
                    exporter.shareUriFor(ready.bundle)
                }
                if (!launch(uri, ready.bundle.file.name)) {
                    throw DiagnosticsExportException(DiagnosticsExportFailure.SHARE)
                }
                // The chooser target may read after this process dies, so janitorial age/count
                // policy owns shared-file cleanup instead of this ViewModel.
                pendingBundle = null
                mutableState.value = DiagnosticsExportState.Idle
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DiagnosticsExportException) {
                publishFailure(failure.kind)
            } catch (_: Throwable) {
                publishFailure(DiagnosticsExportFailure.SHARE)
            }
        }
    }

    fun retry() {
        export()
    }

    fun dismissFailure() {
        if (mutableState.value is DiagnosticsExportState.Failed) {
            mutableState.value = DiagnosticsExportState.Idle
        }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!exportMutex.tryLock()) return@launch
            try {
                block()
            } finally {
                exportMutex.unlock()
            }
        }
    }

    /**
     * A [DiagnosticsExportFailure.BUNDLE] failure is the exporter having already proven the staged
     * handle unusable, so it is dropped here rather than offered again to the next export.
     */
    private fun publishFailure(kind: DiagnosticsExportFailure) {
        if (kind == DiagnosticsExportFailure.BUNDLE) pendingBundle = null
        mutableState.value = DiagnosticsExportState.Failed(failureMessage(kind))
    }

    private fun failureMessage(kind: DiagnosticsExportFailure): LocalizedStringResource =
        LocalizedStringResource(
            when (kind) {
                DiagnosticsExportFailure.BUILD -> R.string.diagnostics_bundle_build_failed
                DiagnosticsExportFailure.STORAGE -> R.string.resource_failure_storage
                DiagnosticsExportFailure.BUNDLE -> R.string.diagnostics_bundle_invalid
                DiagnosticsExportFailure.SHARE -> R.string.diagnostics_action_unavailable
            },
        )

    internal class Factory(
        private val exporter: DiagnosticsExporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T = DiagnosticsViewModel(exporter) as T
    }
}
