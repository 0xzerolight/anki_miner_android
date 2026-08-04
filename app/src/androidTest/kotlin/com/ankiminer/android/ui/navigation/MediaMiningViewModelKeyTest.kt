package com.ankiminer.android.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.dictionary.DefinitionLookupService
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.FakeMiningRepository
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.vm.MediaMiningViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaMiningViewModelKeyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explicitLaneKeysCreateDistinctMediaMiningViewModels() {
        lateinit var videoViewModel: MediaMiningViewModel
        lateinit var audioViewModel: MediaMiningViewModel

        composeRule.setContent {
            val videoFactory = remember { factory(MiningLane.VIDEO) }
            val audioFactory = remember { factory(MiningLane.AUDIO) }
            videoViewModel =
                viewModel(
                    key = MiningLane.VIDEO.savedStateKeyPrefix,
                    factory = videoFactory,
                )
            audioViewModel =
                viewModel(
                    key = MiningLane.AUDIO.savedStateKeyPrefix,
                    factory = audioFactory,
                )
        }

        composeRule.runOnIdle {
            assertTrue(videoViewModel !== audioViewModel)
            assertEquals(MiningLane.VIDEO, videoViewModel.lane)
            assertEquals(MiningLane.AUDIO, audioViewModel.lane)
        }
    }

    private fun factory(lane: MiningLane) =
        MediaMiningViewModel.Factory(
            repository = FakeMiningRepository(),
            safBroker = NoOpSafBroker,
            lane = lane,
            definitionLookup =
                DefinitionLookupService { _, _, _ ->
                    Result.failure(UnsupportedOperationException("unused by key test"))
                },
            savedStateHandleFactory = { SavedStateHandle() },
        )

    private object NoOpSafBroker : SafBroker {
        override suspend fun retainReadAccess(uri: String): SafDocument =
            error("unused by key test")

        override suspend fun releaseReadAccess(uri: String) = Unit

        override fun releaseReadAccessEventually(uri: String) = Unit
    }
}
