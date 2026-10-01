package com.ankiminer.android.service

import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiningCompletionNoticeTest {
    private val running = MiningRunState.Running("run", MiningProgress(0, 0, "Working"))
    private val result =
        ProcessingResult(
            3,
            2,
            2,
            emptyList(),
            1.0,
            80.0,
            listOf(1L, 2L),
            "v.mkv",
            "s.srt",
            listOf("猫"),
            AnkiWriteState.NOTE_WRITE_CONFIRMED,
            false,
        )

    @Test
    fun aRunThatEndsWhileBackgroundedPostsItsOutcome() {
        assertEquals(
            MiningCompletionNotice("run", MiningCompletionKind.NOTES_ADDED, 2),
            miningCompletionNotice(running, MiningRunState.Success("run", result), appInForeground = false),
        )
        assertEquals(
            MiningCompletionKind.STOPPED,
            miningCompletionNotice(
                running,
                MiningRunState.Failed("run", MiningFailure("x", false), null),
                appInForeground = false,
            )?.kind,
        )
    }

    @Test
    fun noNoticeWhenWatchingCancellingOrRestoring() {
        assertNull(miningCompletionNotice(running, MiningRunState.Success("run", result), appInForeground = true))
        assertNull(miningCompletionNotice(running, MiningRunState.Cancelled("run", null), appInForeground = false))
        assertNull(
            miningCompletionNotice(
                MiningRunState.Idle,
                MiningRunState.Failed("run", MiningFailure("x", false), null),
                appInForeground = false,
            ),
        )
        assertNull(
            miningCompletionNotice(
                MiningRunState.Success("run", result),
                MiningRunState.Idle,
                appInForeground = false,
            ),
        )
    }
}
