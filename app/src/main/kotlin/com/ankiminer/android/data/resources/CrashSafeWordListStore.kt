package com.ankiminer.android.data.resources

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

internal fun syncResourceDirectory(directory: File) {
    val descriptor = Os.open(directory.canonicalPath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

/**
 * Crash-safe publication for engine word lists.
 *
 * A validated candidate and prior target never disappear in one step. Startup prefers a published
 * target, otherwise completes a durable candidate, otherwise restores the backup.
 */
internal class CrashSafeWordListStore(
    private val root: File,
    private val move: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    private val syncDirectory: (File) -> Unit = ::syncResourceDirectory,
) {
    fun recover() {
        if (!root.exists()) return
        WordListKind.entries.forEach(::recover)
    }

    fun publish(
        staged: File,
        kind: WordListKind,
    ): File {
        prepareRoot()
        recover(kind)
        val target = target(kind)
        val candidate = candidate(kind)
        val backup = backup(kind)
        candidate.delete()
        backup.delete()
        if (!move(staged, candidate)) throw publishFailure()
        sync(candidate)
        syncDirectory(root)

        if (target.exists() && !move(target, backup)) {
            candidate.delete()
            throw publishFailure()
        }
        if (!move(candidate, target)) {
            val restored = !backup.exists() || move(backup, target)
            if (restored) {
                candidate.delete()
                syncDirectory(root)
            }
            throw publishFailure()
        }
        syncDirectory(root)
        backup.delete()
        syncDirectory(root)
        return target
    }

    fun remove(kind: WordListKind): Boolean {
        val target = target(kind)
        val candidate = candidate(kind)
        val backup = backup(kind)
        val targetExists = target.exists()
        val candidateExists = candidate.exists()
        val backupExists = backup.exists()
        val changed = targetExists || candidateExists || backupExists
        val targetRemoved = !targetExists || target.delete()
        val candidateRemoved = !candidateExists || candidate.delete()
        val backupRemoved = !backupExists || backup.delete()
        if (!targetRemoved || !candidateRemoved || !backupRemoved || !changed) {
            return targetRemoved && candidateRemoved && backupRemoved
        }
        return runCatching { syncDirectory(root) }.isSuccess
    }

    private fun recover(kind: WordListKind) {
        val target = target(kind)
        val candidate = candidate(kind)
        val backup = backup(kind)
        if (target.isFile) {
            candidate.delete()
            backup.delete()
            syncDirectory(root)
            return
        }
        target.delete()
        val hadCandidate = candidate.isFile
        if (hadCandidate && move(candidate, target)) {
            backup.delete()
            syncDirectory(root)
            return
        }
        if (hadCandidate && !backup.isFile) throw publishFailure()
        candidate.delete()
        if (backup.isFile && move(backup, target)) {
            syncDirectory(root)
            return
        }
        if (hadCandidate || backup.exists()) throw publishFailure()
    }

    private fun prepareRoot() {
        if (!root.exists() && !root.mkdirs()) throw publishFailure()
        if (!root.isDirectory) throw publishFailure()
    }

    private fun sync(file: File) {
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    private fun target(kind: WordListKind) = File(root, kind.fileName)

    private fun candidate(kind: WordListKind) = File(root, "${kind.fileName}.candidate")

    private fun backup(kind: WordListKind) = File(root, "${kind.fileName}.backup")

    private fun publishFailure() =
        ResourceDownloadException(
            "import_staging_failed",
            "Could not store the word-list file",
        )
}
