package com.ankiminer.android.diagnostics.log

import android.util.Log

/**
 * Mirrors records to logcat under one fixed tag.
 *
 * This is the ONLY file in `app/src/main/kotlin` allowed to import `android.util.Log`. The unit test
 * build has neither Robolectric nor `unitTests.returnDefaultValues`, so the stub `android.jar`
 * throws "Method d in android.util.Log not mocked" for any call from a JVM-tested class; a grep gate
 * enforces the boundary.
 *
 * The component stays inside the record as `c=<wireName>` rather than becoming the tag, so
 * `adb logcat -s AnkiMiner` catches everything while on-disk `grep 'c=mining'` still works. The
 * priority is re-derived from the level character the renderer already wrote, which keeps
 * `adb logcat AnkiMiner:E` useful without widening the sink contract beyond a rendered line.
 */
internal class LogcatSink(private val tag: String = TAG) : LogSink {
    override fun write(rendered: String) {
        Log.println(priorityOf(rendered), tag, rendered)
    }

    private fun priorityOf(rendered: String): Int =
        when (rendered.getOrNull(TIMESTAMP_LENGTH + 1)) {
            LogLevel.DEBUG.code -> Log.DEBUG
            LogLevel.WARN.code -> Log.WARN
            LogLevel.ERROR.code -> Log.ERROR
            else -> Log.INFO
        }

    private companion object {
        const val TAG = "AnkiMiner"
    }
}
