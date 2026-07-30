package com.ankiminer.android.reading

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ankiminer.android.media.CancellableProviderIo
import java.io.IOException

/** Process-owned production staging graph shared by startup cleanup and reading runs. */
internal class AndroidReadingSourceStaging(context: Context) {
    private val applicationContext = context.applicationContext
    private val stagingRoot = readingSourceStagingRoot(applicationContext.cacheDir)

    val stager =
        ReadingSourceStager(
            stagingRoot = stagingRoot,
            inputOpener =
                ReadingSourceInputOpener { document, cancellation ->
                    CancellableProviderIo.open(cancellation) { signal ->
                        val descriptor =
                            applicationContext.contentResolver.openFileDescriptor(
                                Uri.parse(document.uri),
                                "r",
                                signal,
                            ) ?: throw IOException("The selected reading source could not be opened")
                        try {
                            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        } catch (failure: Throwable) {
                            try {
                                descriptor.close()
                            } catch (cleanupFailure: Exception) {
                                failure.addSuppressed(cleanupFailure)
                            }
                            throw failure
                        }
                    }
                },
        )

    val janitor = ReadingSourceStageJanitor(stagingRoot)
}
