package com.ankiminer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.video.VideoMiningRoute
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.vm.VideoMiningViewModel

class MainActivity : ComponentActivity() {
    private val viewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        VideoMiningViewModel.Factory(
            repository = MiningRepositoryFactory.create(),
            safBroker = app.safBroker,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnkiMinerTheme {
                val miningViewModel: VideoMiningViewModel = viewModel(factory = viewModelFactory)
                VideoMiningRoute(
                    viewModel = miningViewModel,
                    modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
                )
            }
        }
    }
}
