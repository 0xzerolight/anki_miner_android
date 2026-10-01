package com.ankiminer.android.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.ankiminer.android.data.settings.ANDROID_TTS_AUDIO_KIND
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * True when this run's word audio may be spoken by the device voice: the snapshot maps the
 * `expression_audio` field and its chain holds an enabled `android_tts` entry. The bridge builds the
 * voice into the chain on exactly that condition, so a run that answers true needs a synthesizer
 * behind its `synthesizeSentenceAudio` callback.
 */
internal fun MiningConfigSnapshot.usesDeviceVoice(): Boolean {
    val fields = settings["anki_fields"] as? BridgeJsonValue.ObjectValue ?: return false
    val mapped = fields.values["expression_audio"] as? BridgeJsonValue.Text ?: return false
    if (mapped.value.isEmpty()) return false
    val chain = settings["expression_audio_chain"] as? BridgeJsonValue.ArrayValue ?: return false
    return chain.values.any { entry ->
        val values = (entry as? BridgeJsonValue.ObjectValue)?.values ?: return@any false
        values["kind"] == BridgeJsonValue.Text(ANDROID_TTS_AUDIO_KIND) &&
            values["enabled"] != BridgeJsonValue.Bool(false)
    }
}

/** What the Word audio card says about the device voice for the mining language. */
internal enum class DeviceVoiceStatus {
    /** An installed offline voice speaks the language: word audio will be spoken. */
    AVAILABLE,

    /** The engine knows the language but its offline voice is not downloaded. */
    MISSING_DATA,

    /** No voice for the language, or no usable text-to-speech engine at all. */
    UNSUPPORTED,
}

/**
 * Folds `TextToSpeech.isLanguageAvailable` and whether an offline voice is installed into one
 * status. A language the engine reports available but only speaks with a network or undownloaded
 * voice is [DeviceVoiceStatus.MISSING_DATA]: the run speaks offline only, exactly as
 * [selectOfflineVoice] chooses.
 */
internal fun deviceVoiceStatus(
    languageAvailability: Int,
    hasOfflineVoice: Boolean,
): DeviceVoiceStatus =
    when {
        languageAvailability >= TextToSpeech.LANG_AVAILABLE ->
            if (hasOfflineVoice) DeviceVoiceStatus.AVAILABLE else DeviceVoiceStatus.MISSING_DATA
        languageAvailability == TextToSpeech.LANG_MISSING_DATA -> DeviceVoiceStatus.MISSING_DATA
        else -> DeviceVoiceStatus.UNSUPPORTED
    }

/**
 * Asks the default text-to-speech engine whether it can speak [language] (a BCP-47 tag), off the
 * main thread. An engine that will not start in time counts as [DeviceVoiceStatus.UNSUPPORTED].
 */
internal suspend fun probeDeviceVoice(
    context: Context,
    language: String,
): DeviceVoiceStatus =
    withContext(Dispatchers.IO) {
        val initialized = CompletableDeferred<Int>()
        val textToSpeech =
            try {
                TextToSpeech(context.applicationContext) { status -> initialized.complete(status) }
            } catch (failure: RuntimeException) {
                AppLog.w(LogComponent.SETTINGS, "deviceVoice.probe", failure, "outcome" to "fail")
                return@withContext DeviceVoiceStatus.UNSUPPORTED
            }
        try {
            val status = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) { initialized.await() }
            if (status != TextToSpeech.SUCCESS) {
                DeviceVoiceStatus.UNSUPPORTED
            } else {
                val candidates = textToSpeech.voices.orEmpty().map(Voice::offlineVoiceCandidate)
                deviceVoiceStatus(
                    textToSpeech.isLanguageAvailable(Locale.forLanguageTag(language)),
                    hasOfflineVoice = selectOfflineVoice(candidates, language) != null,
                )
            }
        } catch (failure: RuntimeException) {
            // The engine service died mid-probe; the card says no voice rather than guessing.
            AppLog.w(LogComponent.SETTINGS, "deviceVoice.probe", failure, "outcome" to "fail")
            DeviceVoiceStatus.UNSUPPORTED
        } finally {
            try {
                textToSpeech.shutdown()
            } catch (failure: RuntimeException) {
                AppLog.w(LogComponent.SETTINGS, "deviceVoice.shutdown", failure, "outcome" to "fail")
            }
        }
    }

private const val PROBE_TIMEOUT_MILLIS = 10_000L
