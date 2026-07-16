package com.ankiminer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.mining.MiningRuntimePermissions
import com.ankiminer.android.ui.navigation.AnkiMinerApp
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.VideoMiningViewModel

class MainActivity : ComponentActivity() {
    private val viewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        VideoMiningViewModel.Factory(
            repository = MiningRepositoryFactory.create(app),
            safBroker = app.safBroker,
        )
    }
    private val setupViewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        SetupViewModel.Factory(
            resources = app.resourceManager,
            settings = app.settingsRepository,
            python = app.pythonRuntimeReadiness,
            admission = app.miningAdmissionState,
            refreshAdmission = app::refreshMiningAdmission,
        )
    }
    private val settingsViewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        SettingsViewModel.Factory(app.settingsRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnkiMinerTheme {
                val miningViewModel: VideoMiningViewModel = viewModel(factory = viewModelFactory)
                val setupViewModel: SetupViewModel = viewModel(factory = setupViewModelFactory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory)
                val app = application as AnkiMinerApplication
                val permissions =
                    MiningRuntimePermissions.requiredFor(android.os.Build.VERSION.SDK_INT)
                        .map { it.permission }
                        .distinct()
                        .toTypedArray()
                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) {
                        setupViewModel.permissionsReturned()
                    }
                AnkiMinerApp(
                    videoViewModel = miningViewModel,
                    setupViewModel = setupViewModel,
                    settingsViewModel = settingsViewModel,
                    onRequestPermissions = {
                        if (permissions.isEmpty()) app.refreshMiningAdmission()
                        else permissionLauncher.launch(permissions)
                    },
                )
            }
        }
    }
}
