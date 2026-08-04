package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMediaExtensionsTest {
    private val token = "a".repeat(64)

    /** Most tests here are about the extension partition, so the device answer is held wide open. */
    private val anyDevice = { _: String -> true }
    private val noDevice = { _: String -> false }

    @Test
    fun `sanitizes the audio extensions real producers emit`() {
        // Downloaded expression audio (extension chosen from the response Content-Type), local audio
        // packs, and Android offline TTS. Everything past mp3/opus staged as ".stage" until now,
        // which AnkiDroid stores as ".bin".
        listOf("mp3", "opus", "ogg", "oga", "aac", "m4a", "mp4", "wav", "flac", "webm").forEach {
            assertEquals(it, AnkiMediaExtensions.sanitizedExtension("word_ab12cd.$it", MediaKind.AUDIO, anyDevice))
        }
    }

    @Test
    fun `sanitizes the image extensions real producers emit`() {
        // Screenshots are jpg/png, but Yomitan dictionary media also arrives as MediaKind.IMAGE and
        // ships anything in the importer's whitelist — svg pitch-accent graphics most notably.
        listOf("jpg", "jpeg", "png", "webp", "avif", "svg", "gif", "bmp", "tif", "tiff", "ico").forEach {
            assertEquals(it, AnkiMediaExtensions.sanitizedExtension("shot_ab12cd.$it", MediaKind.IMAGE, anyDevice))
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
                    AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.AUDIO, anyDevice),
                    AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.IMAGE, anyDevice),
                )
            assertEquals("extension $extension must resolve for exactly one kind", 1, resolved.size)
            assertEquals(extension, resolved.single())
            assertTrue(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.${resolved.single()}"))
        }
    }

    @Test
    fun `device-conditional extensions stay in the allowlist and follow the device`() {
        // They must reach ALLOWED_EXTENSIONS, or AnkiMediaStaging.validateRequest would reject a
        // capable device's request outright instead of naming the copy. On an incapable device the
        // sanitizer returns null for BOTH kinds so staging degrades to ".stage".
        AnkiMediaExtensions.DEVICE_CONDITIONAL_EXTENSIONS.forEach { extension ->
            assertTrue(extension in AnkiMediaExtensions.ALLOWED_EXTENSIONS)
            assertNull(AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.AUDIO, noDevice))
            assertNull(AnkiMediaExtensions.sanitizedExtension("x.$extension", MediaKind.IMAGE, noDevice))
        }
        // Measured 2026-08-03: null both ways on API 26, image/avif -> avif on API 36.
        assertTrue("avif" in AnkiMediaExtensions.DEVICE_CONDITIONAL_EXTENSIONS)
    }

    @Test
    fun `an unconditional extension ignores the device answer`() {
        // webp carries every animated screenshot on a device that cannot name avif; evicting it on a
        // false answer would degrade those to .bin everywhere.
        assertEquals("webp", AnkiMediaExtensions.sanitizedExtension("shot.webp", MediaKind.IMAGE, noDevice))
        assertEquals("opus", AnkiMediaExtensions.sanitizedExtension("word.opus", MediaKind.AUDIO, noDevice))
    }

    @Test
    fun `an unknown suffix is rejected before the platform table is consulted`() {
        var asked = false
        assertNull(
            AnkiMediaExtensions.sanitizedExtension("note.txt", MediaKind.IMAGE) {
                asked = true
                true
            },
        )
        assertFalse(asked)
    }

    @Test
    fun `lowercases the suffix before matching`() {
        assertEquals("opus", AnkiMediaExtensions.sanitizedExtension("word.OPUS", MediaKind.AUDIO, anyDevice))
        assertEquals("png", AnkiMediaExtensions.sanitizedExtension("shot.PNG", MediaKind.IMAGE, anyDevice))
    }

    @Test
    fun `rejects an extension that does not match its media kind`() {
        assertNull(AnkiMediaExtensions.sanitizedExtension("shot.jpg", MediaKind.AUDIO, anyDevice))
        assertNull(AnkiMediaExtensions.sanitizedExtension("word.opus", MediaKind.IMAGE, anyDevice))
    }

    @Test
    fun `rejects unknown or missing suffixes`() {
        assertNull(AnkiMediaExtensions.sanitizedExtension("word.txt", MediaKind.AUDIO, anyDevice))
        assertNull(AnkiMediaExtensions.sanitizedExtension("shot.txt", MediaKind.IMAGE, anyDevice))
        assertNull(AnkiMediaExtensions.sanitizedExtension("noextension", MediaKind.AUDIO, anyDevice))
        assertNull(AnkiMediaExtensions.sanitizedExtension("trailingdot.", MediaKind.AUDIO, anyDevice))
        assertNull(AnkiMediaExtensions.sanitizedExtension(".opus", MediaKind.AUDIO, anyDevice))
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
