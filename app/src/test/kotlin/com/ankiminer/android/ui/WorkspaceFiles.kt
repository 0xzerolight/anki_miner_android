package com.ankiminer.android.ui

import java.io.File

/**
 * Resolves a repository-relative path from a unit test. Gradle runs unit tests with the module
 * directory as the working directory, so the repository root is some way above it.
 */
internal fun locateFromWorkspace(relativePath: String): File {
    var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
    repeat(8) {
        val candidate = File(cursor, relativePath)
        if (candidate.exists()) return candidate
        cursor = cursor.parentFile ?: return@repeat
    }
    error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
}
