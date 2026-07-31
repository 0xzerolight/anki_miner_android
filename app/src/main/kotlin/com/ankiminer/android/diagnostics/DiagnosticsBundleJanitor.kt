package com.ankiminer.android.diagnostics

import java.io.File
import java.time.Duration
import java.time.Instant

internal class DiagnosticsBundleJanitor(
    private val root: File,
    private val now: () -> Instant = Instant::now,
) {
    fun clean(): List<File> {
        if (!root.exists()) return emptyList()
        if (!root.isDirectory) return emptyList()
        val removed = mutableListOf<File>()
        val expiry = now().minus(MAX_AGE).toEpochMilli()
        val entries = root.listFiles().orEmpty()
        val temporary =
            entries.filter { file ->
                (file.isDirectory && CAPTURE_NAME.matches(file.name)) ||
                    (file.isFile && BUILDING_NAME.matches(file.name))
            }
        temporary.filter { it.lastModified() < expiry }.forEach { artifact ->
            val deleted =
                if (artifact.isDirectory) {
                    artifact.deleteRecursively()
                } else {
                    artifact.delete()
                }
            if (deleted) removed += artifact
        }
        val owned =
            entries
                .filter { file -> file.isFile && OWNED_NAME.matches(file.name) }

        owned.filter { it.lastModified() < expiry }.forEach { file ->
            if (file.delete()) removed += file
        }

        owned.asSequence()
            .filter(File::exists)
            .sortedWith(
                compareByDescending<File>(File::lastModified)
                    .thenByDescending(File::getName),
            ).drop(MAX_FILES)
            .forEach { file ->
                if (file.delete()) removed += file
            }
        return removed
    }

    internal companion object {
        const val DIRECTORY_NAME = "diagnostics-bundles"
        const val MAX_FILES = 8
        val MAX_AGE: Duration = Duration.ofHours(24)
        val OWNED_NAME =
            Regex(
                "^anki-miner-diagnostics-\\d{8}T\\d{6}Z-" +
                    "[A-Za-z0-9._-]+-[A-Za-z0-9._-]{1,7}\\.zip$",
            )
        private val CAPTURE_NAME = Regex("^\\.capture-\\d+$")
        private val BUILDING_NAME = Regex("^\\.building-\\d+\\.zip$")
    }
}
