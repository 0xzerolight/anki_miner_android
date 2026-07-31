package com.ankiminer.android.tts

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface OfflineTtsBackendOpenResult {
    data class Ready(
        val backend: OfflineTtsBackend,
    ) : OfflineTtsBackendOpenResult

    data class Unavailable(
        val errorCode: String,
    ) : OfflineTtsBackendOpenResult

    data class Failed(
        val errorCode: String,
    ) : OfflineTtsBackendOpenResult

    data object Cancelled : OfflineTtsBackendOpenResult
}

internal fun interface OfflineTtsBackendFactory {
    fun open(cancellationCheck: () -> Boolean): OfflineTtsBackendOpenResult
}

internal interface OfflineTtsBackend : AutoCloseable {
    /** Stable engine-and-voice identity. It participates in every cache key. */
    val voiceIdentity: String

    val maxInputUtf16Units: Int

    fun synthesizeToFile(
        sentence: String,
        destination: File,
        cancellationCheck: () -> Boolean,
    ): OfflineTtsSynthesisResult
}

internal sealed interface OfflineTtsSynthesisResult {
    data object Ready : OfflineTtsSynthesisResult

    data object Cancelled : OfflineTtsSynthesisResult

    data object TimedOut : OfflineTtsSynthesisResult

    data class Failed(
        val errorCode: String = "synthesis_failed",
    ) : OfflineTtsSynthesisResult
}

/**
 * Run-owned sentence synthesizer with a process-wide serialized, content-addressed cache.
 *
 * The backend may be an Android service, but it can write only to a same-directory temporary
 * path. A non-empty, size-bounded result is fsynced and atomically renamed before Python sees it.
 */
internal class CachedSentenceAudioSynthesizer(
    cacheRoot: File,
    private val backendFactory: OfflineTtsBackendFactory,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val cacheBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
) : SentenceAudioSynthesizer {
    private val root = cacheRoot.absoluteFile.normalize()
    private val closed = AtomicBoolean(false)
    private val pinnedFiles = linkedSetOf<File>()
    private var backendState: OfflineTtsBackendOpenResult? = null

    init {
        require(cacheBudgetBytes > 0)
        require(maxFileBytes in 1..cacheBudgetBytes)
        require(reserveBytes >= 0)
    }

    override fun synthesize(
        sentence: String,
        cancellationCheck: () -> Boolean,
    ): SentenceAudioSynthesis =
        synchronized(CACHE_LOCK) {
            if (closed.get()) return@synchronized SentenceAudioSynthesis.failed("synthesizer_closed")
            if (cancellationCheck()) return@synchronized SentenceAudioSynthesis.cancelled()
            if (!isValidUnicodeScalarText(sentence) || sentence.isEmpty() || sentence.length > MAX_INPUT_UTF16_UNITS) {
                return@synchronized SentenceAudioSynthesis.failed("invalid_sentence")
            }
            val utf8 = sentence.toByteArray(Charsets.UTF_8)
            if (utf8.size > SentenceAudioBridgeCodec.MAX_SENTENCE_UTF8_BYTES) {
                return@synchronized SentenceAudioSynthesis.failed("invalid_sentence")
            }
            if (!prepareRoot()) return@synchronized SentenceAudioSynthesis.failed("cache_unavailable")

            val backend =
                when (val opened = backendState ?: backendFactory.open(cancellationCheck).also { backendState = it }) {
                    is OfflineTtsBackendOpenResult.Ready -> opened.backend
                    is OfflineTtsBackendOpenResult.Unavailable ->
                        return@synchronized SentenceAudioSynthesis.unavailable(opened.errorCode)
                    is OfflineTtsBackendOpenResult.Failed ->
                        return@synchronized SentenceAudioSynthesis.failed(opened.errorCode)
                    OfflineTtsBackendOpenResult.Cancelled ->
                        return@synchronized SentenceAudioSynthesis.cancelled()
                }
            if (sentence.length > backend.maxInputUtf16Units) {
                return@synchronized SentenceAudioSynthesis.failed("invalid_sentence")
            }
            if (cancellationCheck()) return@synchronized SentenceAudioSynthesis.cancelled()

            val target = File(root, cacheFilename(backend.voiceIdentity, utf8))
            if (validPublishedFile(target)) {
                target.setLastModified(System.currentTimeMillis())
                pinnedFiles += target
                return@synchronized SentenceAudioSynthesis.ready(target)
            }
            if (target.exists() && !target.delete()) {
                return@synchronized SentenceAudioSynthesis.failed("cache_unavailable")
            }
            if (!pruneTo(cacheBudgetBytes - maxFileBytes, preserve = pinnedFiles)) {
                return@synchronized SentenceAudioSynthesis.failed("cache_unavailable")
            }
            if (availableBytes(root) < maxFileBytes + reserveBytes) {
                return@synchronized SentenceAudioSynthesis.failed("cache_full")
            }

            val temporary =
                File(root, ".${target.nameWithoutExtension}.${UUID.randomUUID()}.part.wav")
            try {
                val result = backend.synthesizeToFile(sentence, temporary, cancellationCheck)
                if (cancellationCheck() || result == OfflineTtsSynthesisResult.Cancelled) {
                    return@synchronized SentenceAudioSynthesis.cancelled()
                }
                when (result) {
                    OfflineTtsSynthesisResult.Ready -> Unit
                    OfflineTtsSynthesisResult.TimedOut ->
                        return@synchronized SentenceAudioSynthesis.failed("synthesis_timeout")
                    is OfflineTtsSynthesisResult.Failed ->
                        return@synchronized SentenceAudioSynthesis.failed(result.errorCode)
                    OfflineTtsSynthesisResult.Cancelled -> error("handled above")
                }
                if (!validTemporaryFile(temporary)) {
                    return@synchronized SentenceAudioSynthesis.failed("invalid_audio_output")
                }
                FileOutputStream(temporary, true).use { stream -> stream.fd.sync() }
                if (!publishAtomically(temporary, target) || !validPublishedFile(target)) {
                    return@synchronized SentenceAudioSynthesis.failed("cache_publish_failed")
                }
                target.setLastModified(System.currentTimeMillis())
                pinnedFiles += target
                if (!pruneTo(cacheBudgetBytes, preserve = pinnedFiles)) {
                    pinnedFiles -= target
                    target.delete()
                    return@synchronized SentenceAudioSynthesis.failed("cache_unavailable")
                }
                SentenceAudioSynthesis.ready(target)
            } catch (_: IOException) {
                SentenceAudioSynthesis.failed("cache_unavailable")
            } catch (_: RuntimeException) {
                SentenceAudioSynthesis.failed("synthesis_failed")
            } finally {
                temporary.delete()
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(CACHE_LOCK) {
            try {
                (backendState as? OfflineTtsBackendOpenResult.Ready)?.backend?.close()
            } catch (_: RuntimeException) {
                // Optional engine teardown must not mask run finalization.
            }
            backendState = null
            pinnedFiles.clear()
            try {
                cleanupPartials()
                pruneTo(cacheBudgetBytes, preserve = emptySet())
            } catch (_: SecurityException) {
                // App cache cleanup is best effort after the backend is closed.
            }
        }
    }

    private fun prepareRoot(): Boolean {
        return try {
            if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) return false
            cleanupPartials()
            pruneTo(cacheBudgetBytes, preserve = pinnedFiles)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun cleanupPartials() {
        root.listFiles()
            ?.filter { it.isFile && it.name.startsWith(".$CACHE_PREFIX") && it.name.contains(".part.") }
            ?.forEach(File::delete)
    }

    private fun pruneTo(
        limit: Long,
        preserve: Set<File>,
    ): Boolean {
        val owned =
            root.listFiles()
                ?.filter { it.isFile && PUBLISHED_FILENAME.matches(it.name) }
                .orEmpty()
        for (candidate in owned) {
            if (candidate.length() !in 1..maxFileBytes && !candidate.delete()) return false
        }
        val candidates =
            owned.filter(File::exists)
                .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        var total = candidates.sumOf { file -> file.length().coerceAtLeast(0L) }
        for (candidate in candidates) {
            if (total <= limit) break
            if (candidate in preserve) continue
            val length = candidate.length().coerceAtLeast(0L)
            if (candidate.delete()) total -= length
        }
        return total <= limit
    }

    private fun validTemporaryFile(file: File): Boolean =
        file.isFile && file.length() in 1..maxFileBytes && file.parentFile == root

    private fun validPublishedFile(file: File): Boolean =
        file.isFile && file.length() in 1..maxFileBytes && PUBLISHED_FILENAME.matches(file.name) && file.parentFile == root

    private fun publishAtomically(
        temporary: File,
        target: File,
    ): Boolean =
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.renameTo(target)
        } catch (_: IOException) {
            false
        }

    private fun cacheFilename(
        voiceIdentity: String,
        sentenceUtf8: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(voiceIdentity.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(sentenceUtf8)
        val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$CACHE_PREFIX$hex.wav"
    }

    private fun isValidUnicodeScalarText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current == '\u0000' -> return false
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun File.normalize(): File = toPath().normalize().toFile()

    companion object {
        const val DEFAULT_CACHE_BUDGET_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_MAX_FILE_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_RESERVE_BYTES = 64L * 1024L * 1024L
        const val MAX_INPUT_UTF16_UNITS = 4_000
        private const val CACHE_DOMAIN = "anki-miner-android-sentence-tts-v1"
        private const val CACHE_PREFIX = "android_tts_v1_"
        private val PUBLISHED_FILENAME = Regex("${CACHE_PREFIX}[0-9a-f]{64}\\.wav")
        private val CACHE_LOCK = Any()
    }
}
