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
    fun `sanitizes audio extensions the engine emits`() {
        assertEquals("mp3", AnkiMediaExtensions.sanitizedExtension("word_ab12cd.mp3", MediaKind.AUDIO))
        assertEquals("opus", AnkiMediaExtensions.sanitizedExtension("word_ab12cd.opus", MediaKind.AUDIO))
    }

    @Test
    fun `sanitizes image extensions the engine emits`() {
        assertEquals("jpg", AnkiMediaExtensions.sanitizedExtension("shot_ab12cd.jpg", MediaKind.IMAGE))
        assertEquals("jpeg", AnkiMediaExtensions.sanitizedExtension("shot_ab12cd.jpeg", MediaKind.IMAGE))
        assertEquals("png", AnkiMediaExtensions.sanitizedExtension("shot_ab12cd.png", MediaKind.IMAGE))
        assertEquals("webp", AnkiMediaExtensions.sanitizedExtension("cover_ab12cd.webp", MediaKind.IMAGE))
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
        assertNull(AnkiMediaExtensions.sanitizedExtension("word.wav", MediaKind.AUDIO))
        assertNull(AnkiMediaExtensions.sanitizedExtension("word.flac", MediaKind.AUDIO))
        assertNull(AnkiMediaExtensions.sanitizedExtension("shot.gif", MediaKind.IMAGE))
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
    fun `every sanitized output is a path-legal extension`() {
        // Guards against the object and the path regex drifting: any extension the sanitizer can
        // return must be nameable, or staging would fail the asset instead of storing it.
        listOf(
            "x.mp3" to MediaKind.AUDIO,
            "x.opus" to MediaKind.AUDIO,
            "x.jpg" to MediaKind.IMAGE,
            "x.jpeg" to MediaKind.IMAGE,
            "x.png" to MediaKind.IMAGE,
            "x.webp" to MediaKind.IMAGE,
        ).forEach { (name, kind) ->
            val extension = requireNotNull(AnkiMediaExtensions.sanitizedExtension(name, kind))
            assertTrue(AnkiMediaExtensions.STAGED_PATH_REGEX.matches("v1/$token.$extension"))
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
