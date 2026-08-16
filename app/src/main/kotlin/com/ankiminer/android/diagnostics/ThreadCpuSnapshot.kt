package com.ankiminer.android.diagnostics

import java.io.File

/**
 * Per-thread CPU time for this process, read from `/proc/self/task`.
 *
 * The bundle records that Android killed the app for excessive CPU use, and nothing in it says
 * what was busy: `exit-reasons.txt` names the kill but no thread, and the logcat entry only ever
 * covers the session in which the tester pressed Export, because an unprivileged app reads its
 * own UID's entries out of a ring that has long since turned over. This is the missing half. It
 * is a snapshot of cumulative time, not a rate, so it answers "which thread has burned the CPU
 * this process spent" rather than "what is busy right now" -- which is the question a report
 * filed after the fact actually poses.
 */
internal object ThreadCpuSnapshot {
    /** Enough to cover every named pool plus the engine thread; bounds the entry's size. */
    private const val MAX_THREADS = 48

    /** `utime` is field 14 of `/proc/<tid>/stat`, counting the two before `comm` closes. */
    private const val UTIME_OFFSET_AFTER_COMM = 11
    private const val STIME_OFFSET_AFTER_COMM = 12

    fun read(
        taskRoot: File,
        clockTicksPerSecond: Long,
    ): String {
        if (clockTicksPerSecond <= 0) return "Thread CPU times are unavailable: unusable clock tick rate.\n"
        val directories =
            taskRoot.listFiles()?.filter(File::isDirectory)
                ?: return "Thread CPU times are unavailable: /proc/self/task could not be listed.\n"
        val threads =
            directories
                .mapNotNull { directory -> readThread(directory, clockTicksPerSecond) }
                .sortedWith(compareByDescending<ThreadCpu> { it.cpuMillis }.thenBy { it.tid })
        if (threads.isEmpty()) return "Thread CPU times are unavailable: no thread reported usable stats.\n"
        return buildString {
            appendLine("threads.total=${threads.size}")
            appendLine("threads.reported=${minOf(threads.size, MAX_THREADS)}")
            appendLine("threads.clock_ticks_per_second=$clockTicksPerSecond")
            threads.take(MAX_THREADS).forEachIndexed { index, thread ->
                appendLine("thread[$index].tid=${thread.tid}")
                appendLine("thread[$index].name=${thread.name}")
                appendLine("thread[$index].cpu_ms=${thread.cpuMillis}")
                appendLine("thread[$index].utime_ms=${thread.userMillis}")
                appendLine("thread[$index].stime_ms=${thread.systemMillis}")
            }
        }
    }

    private fun readThread(
        directory: File,
        clockTicksPerSecond: Long,
    ): ThreadCpu? {
        val tid = directory.name.toLongOrNull() ?: return null
        val stat =
            try {
                File(directory, "stat").readText()
            // A thread that exits between the listing and this read is the expected race, and one
            // record per vanished thread would bury the snapshot this entry exists to carry.
            // instrumentation: silent — a thread that exited mid-scan contributes nothing
            } catch (_: Throwable) {
                return null
            }
        return parseStat(tid, stat, clockTicksPerSecond)
    }

    /**
     * `comm` is the thread name in parentheses and may itself contain spaces and parentheses, so
     * the split point is the *last* `)` rather than a field count from the left.
     */
    internal fun parseStat(
        tid: Long,
        stat: String,
        clockTicksPerSecond: Long,
    ): ThreadCpu? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val name = stat.substring(open + 1, close)
        val fields = stat.substring(close + 1).trim().split(' ').filter(String::isNotEmpty)
        val user = fields.getOrNull(UTIME_OFFSET_AFTER_COMM)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(STIME_OFFSET_AFTER_COMM)?.toLongOrNull() ?: return null
        if (user < 0 || system < 0) return null
        val userMillis = user * 1_000 / clockTicksPerSecond
        val systemMillis = system * 1_000 / clockTicksPerSecond
        return ThreadCpu(
            tid = tid,
            name = name.ifBlank { "unknown" },
            userMillis = userMillis,
            systemMillis = systemMillis,
        )
    }

    internal data class ThreadCpu(
        val tid: Long,
        val name: String,
        val userMillis: Long,
        val systemMillis: Long,
    ) {
        val cpuMillis: Long
            get() = userMillis + systemMillis
    }
}
