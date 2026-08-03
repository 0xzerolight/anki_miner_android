package com.ankiminer.android.engine

import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.ProcessingResult

sealed interface BridgeJsonValue {
    data object Null : BridgeJsonValue

    data class Bool(val value: Boolean) : BridgeJsonValue

    data class Integer(val value: Long) : BridgeJsonValue

    data class Decimal(val value: Double) : BridgeJsonValue {
        init {
            require(value.isFinite())
        }
    }

    data class Text(val value: String) : BridgeJsonValue

    data class ArrayValue(val values: List<BridgeJsonValue>) : BridgeJsonValue

    data class ObjectValue(val values: Map<String, BridgeJsonValue>) : BridgeJsonValue
}

data class MiningConfigSnapshot(
    val settings: Map<String, BridgeJsonValue>,
    val androidTtsEnabled: Boolean? = null,
)

data class VideoMiningWireRequest(
    val videoPath: String,
    val subtitlePath: String,
    val episodeName: String,
    val seriesName: String,
    val sourceLabel: String?,
    val audioTrackOverride: Long?,
    val cacheDir: String,
    val nativeLibraryDir: String,
    val configSnapshot: MiningConfigSnapshot,
)

enum class ReadingMiningSourceKind(
    val wireName: String,
) {
    TXT("txt"),
    EPUB("epub"),
    SUBTITLE("subtitle"),
    MOKURO("mokuro"),
}

data class ReadingMiningWireRequest(
    val sourceKind: ReadingMiningSourceKind,
    val sourcePath: String,
    val imageArchivePath: String?,
    val seriesName: String?,
    val cacheDir: String,
    val nativeLibraryDir: String,
    val configSnapshot: MiningConfigSnapshot,
)

data class TokenizerConfiguration(
    val dicDir: String,
    val resourceId: String,
    val treeSha256: String,
    val backend: String = "s1a",
)

data class TokenizerIdentity(
    val dicDir: String,
    val resourceId: String,
    val treeSha256: String,
    val backend: String,
    val fileCount: Long,
    val totalBytes: Long,
)

data class DefinitionEntry(
    val source: String,
    val html: String,
)

data class SubtitleCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
)

data class TerminalError(
    val code: String,
    val message: String,
    /** Opaque key joining this terminal to the Python traceback in the exported log. */
    val faultId: String? = null,
)

enum class MiningOutcome(val wireName: String) {
    SUCCESS("success"),
    CANCELLED("cancelled"),
    FAILED("failed"),
}

enum class PresenterMessageKind(val wireName: String) {
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error"),
}

enum class ValidationSeverity {
    ERROR,
    WARNING,
}

data class ValidationIssue(
    val component: String,
    val severity: ValidationSeverity,
    val message: String,
)

data class ValidationResult(
    val ankiconnectOk: Boolean,
    val ffmpegOk: Boolean,
    val deckExists: Boolean,
    val noteTypeExists: Boolean,
    val issues: List<ValidationIssue>,
    val ffprobeOk: Boolean,
)

sealed interface PresenterEvent {
    val runId: String

    data class Message(
        override val runId: String,
        val kind: PresenterMessageKind,
        val message: String,
    ) : PresenterEvent

    data class Validation(
        override val runId: String,
        val result: ValidationResult,
    ) : PresenterEvent

    data class Processing(
        override val runId: String,
        val result: ProcessingResult,
    ) : PresenterEvent
}

sealed interface BridgeMessage {
    data class BootstrapInitialize(val filesDir: String) : BridgeMessage

    data class BootstrapReady(val home: String) : BridgeMessage

    data class TokenizerConfigure(val configuration: TokenizerConfiguration) : BridgeMessage

    data class TokenizerReady(val identity: TokenizerIdentity) : BridgeMessage

    data class DictionaryDefineRequest(
        val runId: String,
        val term: String,
        val fallbackTerm: String?,
    ) : BridgeMessage

    data class DictionaryDefineResult(
        val runId: String,
        val term: String,
        val matchedTerm: String,
        val entries: List<DefinitionEntry>,
    ) : BridgeMessage

    data class SubtitleCuesRequest(
        val runId: String?,
        val subtitlePath: String,
    ) : BridgeMessage

    data class SubtitleCuesResult(
        val runId: String?,
        val subtitlePath: String,
        val cues: List<SubtitleCue>,
    ) : BridgeMessage

    data class Error(
        val code: String,
        val message: String,
        val requestType: String?,
        /** Opaque key joining this failure to the Python traceback in the exported log. */
        val faultId: String? = null,
    ) : BridgeMessage

    data class VideoRun(val request: VideoMiningWireRequest) : BridgeMessage

    data class ReadingRun(val request: ReadingMiningWireRequest) : BridgeMessage

    data class JobRegistrationRequest(val runId: String) : BridgeMessage

    data class JobRegistrationAccepted(val runId: String) : BridgeMessage

    data class ProgressStart(
        val runId: String,
        val total: Long,
        val description: String,
    ) : BridgeMessage

    data class ProgressUpdate(
        val runId: String,
        val current: Long,
        val description: String,
    ) : BridgeMessage

    data class ProgressStage(
        val runId: String,
        val index: Int,
        val total: Int,
        val name: String,
    ) : BridgeMessage

    data class ProgressComplete(val runId: String) : BridgeMessage

    data class ProgressError(
        val runId: String,
        val description: String,
        val message: String,
    ) : BridgeMessage

    data class Presenter(val event: PresenterEvent) : BridgeMessage

    data class CurationNeeded(val request: CurationRequest) : BridgeMessage

    data class CurationResponse(
        val runId: String,
        val requestId: String,
        val selection: List<com.ankiminer.android.mining.CurationSelection>?,
        val knownCandidateIds: List<String> = emptyList(),
    ) : BridgeMessage

    data class CurationPageResponse(
        val runId: String,
        val requestId: String,
        val pageIndex: Long,
        val selection: List<com.ankiminer.android.mining.CurationSelection>?,
        val knownCandidateIds: List<String> = emptyList(),
    ) : BridgeMessage

    data class CurationAccepted(
        val runId: String,
        val requestId: String,
    ) : BridgeMessage

    data class CurationPageAccepted(
        val runId: String,
        val requestId: String,
        val pageIndex: Long,
        val finalPage: Boolean,
    ) : BridgeMessage

    data class JobCancel(val runId: String) : BridgeMessage

    data class JobCancelled(
        val runId: String,
        val newlyCancelled: Boolean,
    ) : BridgeMessage

    data class DiagnosticsLogLevelSet(val level: String) : BridgeMessage

    data class DiagnosticsLogLevelApplied(val level: String) : BridgeMessage

    /** `rawEnvelope` is retained byte-for-byte for callback/return reconciliation. */
    data class Terminal(
        val runId: String,
        val outcome: MiningOutcome,
        val result: ProcessingResult?,
        val error: TerminalError?,
        val rawEnvelope: String,
    ) : BridgeMessage
}

enum class BridgeProtocolCategory {
    INVALID_JSON,
    INPUT_TOO_LARGE,
    DUPLICATE_JSON_KEY,
    INVALID_UTF8,
    NUMERIC_TOKEN_TOO_LONG,
    INTEGER_OUT_OF_RANGE,
    NON_FINITE_NUMBER,
    INVALID_ENVELOPE,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_MESSAGE_TYPE,
    UNSUPPORTED_MESSAGE_TYPE,
    INVALID_PAYLOAD,
    INVALID_VALUE,
    STALE_RUN,
    STALE_REQUEST,
}

class BridgeProtocolException(
    val category: BridgeProtocolCategory,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
