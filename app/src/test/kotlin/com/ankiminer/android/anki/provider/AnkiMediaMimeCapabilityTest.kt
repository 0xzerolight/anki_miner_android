package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device-conditional half of the staged-extension decision. Measured on 2026-08-03: API 26
 * cannot name a `.avif` file, API 36 can, so the same build has to reach both answers.
 */
class AnkiMediaMimeCapabilityTest {
    private class FakeCapability(private val nameable: Set<String>) : AnkiMediaMimeCapability {
        override fun canNameFilesFor(extension: String): Boolean = extension in nameable
    }

    private val capable = FakeCapability(AnkiMediaExtensions.ALLOWED_EXTENSIONS.toSet())
    private val incapable = FakeCapability(emptySet())

    @Test
    fun `a device that names avif keeps the real extension`() {
        assertEquals(
            "avif",
            AnkiMediaExtensions.sanitizedExtension("shot.avif", MediaKind.IMAGE, capable),
        )
    }

    @Test
    fun `a device that cannot name avif falls back to the neutral suffix`() {
        assertNull(AnkiMediaExtensions.sanitizedExtension("shot.avif", MediaKind.IMAGE, incapable))
    }

    @Test
    fun `capability is consulted only for device-conditional extensions`() {
        // webp is unconditional: an empty capability must not evict it, or animated screenshots
        // would degrade to .bin on every device.
        assertEquals(
            "webp",
            AnkiMediaExtensions.sanitizedExtension("shot.webp", MediaKind.IMAGE, incapable),
        )
        assertEquals(
            "opus",
            AnkiMediaExtensions.sanitizedExtension("word.opus", MediaKind.AUDIO, incapable),
        )
    }

    @Test
    fun `an unknown suffix is rejected before the platform table is consulted`() {
        var asked = false
        val recording =
            object : AnkiMediaMimeCapability {
                override fun canNameFilesFor(extension: String): Boolean {
                    asked = true
                    return true
                }
            }
        assertNull(AnkiMediaExtensions.sanitizedExtension("note.txt", MediaKind.IMAGE, recording))
        assertEquals(false, asked)
    }
}
