package com.ankiminer.android.reading

import android.content.Context
import android.net.Uri
import java.io.IOException

/** Process-owned production staging graph shared by startup cleanup and reading runs. */
internal class AndroidReadingSourceStaging(context: Context) {
    private val applicationContext = context.applicationContext
    private val stagingRoot = readingSourceStagingRoot(applicationContext.cacheDir)

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
}
