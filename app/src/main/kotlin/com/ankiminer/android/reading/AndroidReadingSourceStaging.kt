package com.ankiminer.android.reading

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Process-owned production staging graph shared by startup cleanup and reading runs. */
internal class AndroidReadingSourceStaging(context: Context) {
    private val applicationContext = context.applicationContext
    private val stagingRoot = File(applicationContext.cacheDir, STAGING_ROOT_NAME)

    val stager =
        ReadingSourceStager(
            stagingRoot = stagingRoot,
            inputOpener =
                ReadingSourceInputOpener { document ->
                    applicationContext.contentResolver.openInputStream(Uri.parse(document.uri))
                        ?: throw IOException("The selected reading source could not be opened")
                },
        )

    val janitor = ReadingSourceStageJanitor(stagingRoot)

    private companion object {
        const val STAGING_ROOT_NAME = "reading-sources-v1"
    }
}
