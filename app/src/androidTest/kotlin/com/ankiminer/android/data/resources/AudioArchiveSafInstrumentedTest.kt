package com.ankiminer.android.data.resources

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.debug.S3TestDocumentsProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioArchiveSafInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stagingRoot by lazy { File(context.cacheDir, "audio-saf-staging") }

    @Before
    fun prepareFixtures() {
        stagingRoot.deleteRecursively()
        check(stagingRoot.mkdirs()) { "Could not create $stagingRoot" }
    }

    @After
    fun cleanFixtures() {
        stagingRoot.deleteRecursively()
        fixture(S3TestDocumentsProvider.AUDIO_RAW_FIXTURE_NAME).delete()
        fixture(S3TestDocumentsProvider.AUDIO_TRANSFORMED_FIXTURE_NAME).delete()
        fixture(S3TestDocumentsProvider.AUDIO_ASSET_FIXTURE_NAME).delete()
    }

    @Test
    fun rawDescriptorWinsOverTransformedTypedRepresentation() {
        val raw = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
        fixture(S3TestDocumentsProvider.AUDIO_RAW_FIXTURE_NAME).writeBytes(raw)
        fixture(S3TestDocumentsProvider.AUDIO_TRANSFORMED_FIXTURE_NAME)
            .writeText("<!doctype html>transformed provider preview")

        val result = stage("raw-preferred", "instrumented-audio-raw")

        assertEquals(AudioArchiveReadMode.RAW, result.readMode)
        assertEquals(AudioArchiveContainer.ZIP, result.container)
        assertArrayEquals(raw, result.archive.file.readBytes())
    }

    @Test
    fun assetRepresentationIsUsedWhenRawDescriptorIsUnavailable() {
        val asset = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3, 4)
        fixture(S3TestDocumentsProvider.AUDIO_ASSET_FIXTURE_NAME).writeBytes(asset)

        val result = stage("asset-only", "instrumented-audio-asset")

        assertEquals(AudioArchiveReadMode.ASSET_FALLBACK, result.readMode)
        assertEquals(AudioArchiveContainer.GZIP, result.container)
        assertArrayEquals(asset, result.archive.file.readBytes())
    }

    private fun stage(
        path: String,
        operationId: String,
    ): StagedAudioArchive =
        SafArchiveStager(context.contentResolver, stagingRoot).stageAudioArchive(
            sourceUri =
                Uri.parse("content://${BuildConfig.APPLICATION_ID}.s3.provider/audio/$path")
                    .toString(),
            operationId = operationId,
            cancellation = ResourceCancellationSignal(),
            maximumBytes = 4_096,
        ) { _, _ -> }

    private fun fixture(name: String): File = File(context.cacheDir, name)
}
