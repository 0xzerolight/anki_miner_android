package com.ankiminer.android.vm

import com.ankiminer.android.media.SafDocument
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDocumentAcceptanceTest {
    @Test
    fun `an audio MIME type is accepted when the display name is a track title`() {
        assertTrue(isAcceptedAudioDocument(document("Bohemian Rhapsody", "audio/mpeg")))
        assertTrue(isAcceptedAudioDocument(document("Mr. Brightside", "audio/mpeg")))
    }

    @Test
    fun `the MIME type comparison ignores case`() {
        assertTrue(isAcceptedAudioDocument(document("Chapter 1", "Audio/MP4")))
    }

    @Test
    fun `an octet-stream document is judged by its extension`() {
        assertTrue(isAcceptedAudioDocument(document("book.M4B", "application/octet-stream")))
        assertFalse(isAcceptedAudioDocument(document("book", "application/octet-stream")))
    }

    @Test
    fun `a document without a type is judged by its extension`() {
        assertTrue(isAcceptedAudioDocument(document("track.flac", null)))
        assertFalse(isAcceptedAudioDocument(document("notes.txt", null)))
    }

    @Test
    fun `a video is still rejected`() {
        assertFalse(isAcceptedAudioDocument(document("episode.mkv", "video/x-matroska")))
    }

    private fun document(
        displayName: String,
        mimeType: String?,
    ) = SafDocument("content://test/audio", displayName, mimeType, sizeBytes = null)
}
