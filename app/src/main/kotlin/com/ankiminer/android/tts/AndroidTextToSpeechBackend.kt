package com.ankiminer.android.tts

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal data class OfflineJapaneseVoiceCandidate(
    val id: String,
    val languageTag: String,
    val quality: Int,
    val latency: Int,
    val networkRequired: Boolean,
    val installed: Boolean,
)

/** Deterministic selector: exact ja-JP, quality, latency, then stable voice ID. */
internal fun selectOfflineJapaneseVoice(
    candidates: List<OfflineJapaneseVoiceCandidate>,
    trySelect: (String) -> Boolean = { true },
): String? =
    candidates
        .asSequence()
        .filter { candidate ->
            !candidate.networkRequired &&
                candidate.installed &&
                Locale.forLanguageTag(candidate.languageTag).language == Locale.JAPANESE.language
        }
        .sortedWith(
            compareByDescending<OfflineJapaneseVoiceCandidate> {
                it.languageTag.equals(Locale.JAPAN.toLanguageTag(), ignoreCase = true)
            }.thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.id },
        )
        .map { it.id }
        .firstOrNull(trySelect)

/** Factory intended for injection into the process-owned reading repository. */
internal class AndroidSentenceAudioSynthesizerFactory(
    context: Context,
) : SentenceAudioSynthesizerFactory {
    private val appContext = context.applicationContext
    private val cacheBase = appContext.cacheDir.absoluteFile.toPath().normalize().toFile()
    private val cacheRoot: File =
        File(cacheBase, CACHE_DIRECTORY).absoluteFile.toPath().normalize().toFile().also { root ->
            require(root.parentFile == cacheBase)
        }

    override fun open(): SentenceAudioSynthesizer =
        CachedSentenceAudioSynthesizer(
            cacheRoot = cacheRoot,
            backendFactory = AndroidOfflineJapaneseTtsBackendFactory(appContext),
        )

    private companion object {
        const val CACHE_DIRECTORY = "sentence-audio-v1"
    }
}

private class AndroidOfflineJapaneseTtsBackendFactory(
    private val context: Context,
    private val initializationTimeoutMillis: Long = INITIALIZATION_TIMEOUT_MILLIS,
) : OfflineTtsBackendFactory {
    override fun open(cancellationCheck: () -> Boolean): OfflineTtsBackendOpenResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return OfflineTtsBackendOpenResult.Failed("main_thread_forbidden")
        }
        if (cancellationCheck()) return OfflineTtsBackendOpenResult.Cancelled

        val initialized = CountDownLatch(1)
        val initializationStatus = AtomicInteger(Int.MIN_VALUE)
        val textToSpeech =
            try {
                TextToSpeech(context) { status ->
                    initializationStatus.set(status)
                    initialized.countDown()
                }
            } catch (_: RuntimeException) {
                return OfflineTtsBackendOpenResult.Unavailable("tts_engine_unavailable")
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(initializationTimeoutMillis)
        try {
            while (!initialized.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancellationCheck()) {
                    textToSpeech.shutdownQuietly()
                    return OfflineTtsBackendOpenResult.Cancelled
                }
                if (System.nanoTime() >= deadline) {
                    textToSpeech.shutdownQuietly()
                    return OfflineTtsBackendOpenResult.Unavailable("tts_initialization_timeout")
                }
            }
            if (initializationStatus.get() != TextToSpeech.SUCCESS) {
                textToSpeech.shutdownQuietly()
                return OfflineTtsBackendOpenResult.Unavailable("tts_engine_unavailable")
            }
            if (cancellationCheck()) {
                textToSpeech.shutdownQuietly()
                return OfflineTtsBackendOpenResult.Cancelled
            }
            val voices = textToSpeech.voices.orEmpty()
            val candidates =
                voices.map { voice ->
                    OfflineJapaneseVoiceCandidate(
                        id = voice.name,
                        languageTag = voice.locale.toLanguageTag(),
                        quality = voice.quality,
                        latency = voice.latency,
                        networkRequired = voice.isNetworkConnectionRequired,
                        installed =
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in
                                voice.features.orEmpty(),
                    )
                }
            val selectedId =
                selectOfflineJapaneseVoice(candidates) { candidateId ->
                    val candidate = voices.singleOrNull { voice -> voice.name == candidateId }
                    candidate != null && textToSpeech.setVoice(candidate) == TextToSpeech.SUCCESS
                }
            val selected = voices.singleOrNull { voice -> voice.name == selectedId }
            if (selected == null) {
                textToSpeech.shutdownQuietly()
                return OfflineTtsBackendOpenResult.Unavailable("offline_japanese_voice_unavailable")
            }
            val engine = textToSpeech.defaultEngine.orEmpty()
            return OfflineTtsBackendOpenResult.Ready(
                AndroidOfflineJapaneseTtsBackend(
                    textToSpeech = textToSpeech,
                    selectedVoice = selected,
                    voiceIdentity = "$engine\u0000${selected.locale.toLanguageTag()}\u0000${selected.name}",
                ),
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            textToSpeech.shutdownQuietly()
            return OfflineTtsBackendOpenResult.Cancelled
        } catch (_: RuntimeException) {
            textToSpeech.shutdownQuietly()
            return OfflineTtsBackendOpenResult.Unavailable("tts_engine_unavailable")
        }
    }

    private companion object {
        const val INITIALIZATION_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}

@Suppress("DEPRECATION")
private class AndroidOfflineJapaneseTtsBackend(
    private val textToSpeech: TextToSpeech,
    selectedVoice: Voice,
    override val voiceIdentity: String,
    private val synthesisTimeoutMillis: Long = SYNTHESIS_TIMEOUT_MILLIS,
) : OfflineTtsBackend {
    override val maxInputUtf16Units: Int =
        minOf(TextToSpeech.getMaxSpeechInputLength(), CachedSentenceAudioSynthesizer.MAX_INPUT_UTF16_UNITS)

    private val closed = AtomicBoolean(false)
    private val selectedVoiceId = selectedVoice.name

    override fun synthesizeToFile(
        sentence: String,
        destination: File,
        cancellationCheck: () -> Boolean,
    ): OfflineTtsSynthesisResult {
        if (closed.get()) return OfflineTtsSynthesisResult.Failed("synthesizer_closed")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return OfflineTtsSynthesisResult.Failed("main_thread_forbidden")
        }
        if (cancellationCheck()) return OfflineTtsSynthesisResult.Cancelled
        if (sentence.length > maxInputUtf16Units) return OfflineTtsSynthesisResult.Failed("invalid_sentence")

        val utteranceId = "tts_${UUID.randomUUID().toString().replace("-", "")}"
        val completion = CountDownLatch(1)
        val status = AtomicReference<SynthesisStatus>(SynthesisStatus.PENDING)
        val listener =
            object : UtteranceProgressListener() {
                override fun onStart(receivedId: String?) = Unit

                override fun onDone(receivedId: String?) {
                    if (receivedId == utteranceId) {
                        status.compareAndSet(SynthesisStatus.PENDING, SynthesisStatus.READY)
                        completion.countDown()
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(receivedId: String?) {
                    fail(receivedId, TextToSpeech.ERROR_SYNTHESIS)
                }

                override fun onError(
                    receivedId: String?,
                    errorCode: Int,
                ) {
                    fail(receivedId, errorCode)
                }

                override fun onStop(
                    receivedId: String?,
                    interrupted: Boolean,
                ) {
                    if (receivedId == utteranceId) {
                        status.compareAndSet(SynthesisStatus.PENDING, SynthesisStatus.STOPPED)
                        completion.countDown()
                    }
                }

                private fun fail(
                    receivedId: String?,
                    errorCode: Int,
                ) {
                    if (receivedId == utteranceId) {
                        status.set(
                            if (errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT) {
                                // An explicitly offline Voice must never be retried through another provider.
                                SynthesisStatus.NETWORK_REJECTED
                            } else {
                                SynthesisStatus.FAILED
                            },
                        )
                        completion.countDown()
                    }
                }
            }
        return try {
            if (textToSpeech.voice?.name != selectedVoiceId) {
                return OfflineTtsSynthesisResult.Failed("offline_voice_changed")
            }
            if (textToSpeech.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS) {
                return OfflineTtsSynthesisResult.Failed()
            }
            // setVoice selected a Voice whose networkRequired contract is false.
            // Do not retry with a different voice or provider on any failure.
            val parameters = Bundle()
            if (
                textToSpeech.synthesizeToFile(sentence, parameters, destination, utteranceId) !=
                TextToSpeech.SUCCESS
            ) {
                return OfflineTtsSynthesisResult.Failed()
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(synthesisTimeoutMillis)
            while (!completion.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancellationCheck()) {
                    textToSpeech.stopQuietly()
                    return OfflineTtsSynthesisResult.Cancelled
                }
                if (destination.length() > CachedSentenceAudioSynthesizer.DEFAULT_MAX_FILE_BYTES) {
                    textToSpeech.stopQuietly()
                    return OfflineTtsSynthesisResult.Failed("audio_output_too_large")
                }
                if (System.nanoTime() >= deadline) {
                    textToSpeech.stopQuietly()
                    return OfflineTtsSynthesisResult.TimedOut
                }
            }
            when (status.get()) {
                SynthesisStatus.READY -> OfflineTtsSynthesisResult.Ready
                SynthesisStatus.STOPPED ->
                    if (cancellationCheck()) OfflineTtsSynthesisResult.Cancelled else OfflineTtsSynthesisResult.Failed()
                SynthesisStatus.NETWORK_REJECTED -> OfflineTtsSynthesisResult.Failed("network_voice_rejected")
                SynthesisStatus.FAILED, SynthesisStatus.PENDING -> OfflineTtsSynthesisResult.Failed()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            textToSpeech.stopQuietly()
            OfflineTtsSynthesisResult.Cancelled
        } catch (_: RuntimeException) {
            textToSpeech.stopQuietly()
            OfflineTtsSynthesisResult.Failed()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        textToSpeech.shutdownQuietly()
    }

    private enum class SynthesisStatus {
        PENDING,
        READY,
        STOPPED,
        NETWORK_REJECTED,
        FAILED,
    }

    private companion object {
        const val SYNTHESIS_TIMEOUT_MILLIS = 45_000L
        const val POLL_MILLIS = 50L
    }
}

private fun TextToSpeech.stopQuietly() {
    try {
        stop()
    } catch (_: RuntimeException) {
        // The engine service may already have died; there is no fallback work.
    }
}

private fun TextToSpeech.shutdownQuietly() {
    stopQuietly()
    try {
        shutdown()
    } catch (_: RuntimeException) {
        // Best-effort unbind after an unavailable or dead engine.
    }
}
