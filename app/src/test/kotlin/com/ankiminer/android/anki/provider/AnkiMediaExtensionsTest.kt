package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMediaExtensionsTest {
    private val token = "a".repeat(64)

    @Test
    fun `sanitizes the audio extensions real producers emit`() {
        // Downloaded expression audio (extension chosen from the response Content-Type), local audio
        // packs, and Android offline TTS. Everything past mp3/opus staged as ".stage" until now,
        // which AnkiDroid stores as ".bin".
        listOf("mp3", "opus", "ogg", "oga", "aac", "m4a", "mp4", "wav", "flac", "webm").forEach {
            assertEquals(it, AnkiMediaExtensions.sanitizedExtension("word_ab12cd.$it", MediaKind.AUDIO))
        }
    }

    @Test
    fun `sanitizes the image extensions real producers emit`() {
        // Screenshots are jpg/png, but Yomitan dictionary media also arrives as MediaKind.IMAGE and
        // ships anything in the importer's whitelist — svg pitch-accent graphics most notably.
        listOf("jpg", "jpeg", "png", "webp", "svg", "gif", "bmp", "tif", "tiff", "ico").forEach {
            assertEquals(it, AnkiMediaExtensions.sanitizedExtension("shot_ab12cd.$it", MediaKind.IMAGE))
        }
    }

    @Test
    fun `every allowed extension belongs to exactly one media kind`() {
        // Behavioural partition: AUDIO_EXTENSIONS and IMAGE_EXTENSIONS are private, and asserting the
        // split this way also proves it is total and disjoint. Replaces a hand-listed set that could
        // silently fall behind the allowlist.
        AnkiMediaExtensions.ALLOWED_EXTENSIONS.forEach { extension ->
            val resolved =
                listOfNotNull(
                    AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.AUDIO),
                    AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.IMAGE),
                )
            assertEquals("extension $extension must resolve for exactly one kind", 1, resolved.size)
            assertEquals(extension, resolved.single())
            assertTrue(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.${resolved.single()}"))
        }
    }

    @Test
    fun `always-fallback extensions reach neither the allowlist nor a staged name`() {
        // This is a compile-time compatibility decision, not a claim about the current device.
        // It must sanitize to null for BOTH kinds, or staging rejects instead of using .stage.
        AnkiMediaExtensions.ALWAYS_FALLBACK_EXTENSIONS.forEach { extension ->
            assertFalse(extension in AnkiMediaExtensions.ALLOWED_EXTENSIONS)
            assertNull(AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.AUDIO))
            assertNull(AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.IMAGE))
        }
        assertTrue("avif" in AnkiMediaExtensions.ALWAYS_FALLBACK_EXTENSIONS)
    }

    @Test
    fun `lowercases the suffix before matching`() {
        assertEquals("opus", AnkiMediaExtensions.sanitizedExtension("word.OPUS", MediaKind.AUDIO))
        assertEquals("png", AnkiMediaExtensions.sanitizedExtension("shot.PNG", MediaKind.IMAGE))
    }

    @Test
    fun `rejects an extension that does not match its media kind`() {
        assertNull(AnkiMediaExtensions.sanitizedExtension("shot.jpg", MediaKind.AUDIO))
        assertNull(AnkiMediaExtensions.sanitizedExtension("word.opus", MediaKind.IMAGE))
    }

    @Test
    fun `rejects unknown or missing suffixes`() {
        assertNull(AnkiMediaExtensions.sanitizedExtension("word.txt", MediaKind.AUDIO))
        assertNull(AnkiMediaExtensions.sanitizedExtension("shot.txt", MediaKind.IMAGE))
        assertNull(AnkiMediaExtensions.sanitizedExtension("noextension", MediaKind.AUDIO))
        assertNull(AnkiMediaExtensions.sanitizedExtension("trailingdot.", MediaKind.AUDIO))
        assertNull(AnkiMediaExtensions.sanitizedExtension(".opus", MediaKind.AUDIO))
    }

    @Test
    fun `every allowed extension and the stage fallback produce a matching staged path`() {
        (AnkiMediaExtensions.ALLOWED_EXTENSIONS + AnkiMediaExtensions.STAGE_FALLBACK_EXTENSION).forEach { extension ->
            assertTrue(
                "extension $extension must match STAGED_PATH_REGEX",
                AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.$extension"),
            )
        }
    }

    @Test
    fun `staged path regex rejects an unlisted extension`() {
        assertFalse(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.bin"))
    }

    @Test
    fun `staged path regex stays fully anchored against traversal and lookalikes`() {
        assertFalse(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/${"a".repeat(63)}x.opus"))
        assertFalse(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/../$token.opus"))
        assertFalse(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.opus.stage"))
        assertFalse(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.opus/extra"))
        assertFalse(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/${"A".repeat(64)}.opus"))
    }
}
