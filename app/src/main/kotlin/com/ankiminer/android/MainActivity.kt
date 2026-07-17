package com.ankiminer.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.mining.MiningRuntimePermissions
import com.ankiminer.android.reading.ReadingRepositoryFactory
import com.ankiminer.android.service.MiningForegroundService
import com.ankiminer.android.ui.navigation.AnkiMinerApp
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.VideoMiningViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val notificationRunId = MutableStateFlow<String?>(null)

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
            ankiSetup = app.ankiSetupManager,
            python = app.pythonRuntimeReadiness,
            admission = app.miningAdmissionState,
            refreshAdmission = app::refreshMiningAdmission,
        )
    }
    private val settingsViewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        SettingsViewModel.Factory(app.settingsRepository, app.resourceManager)
    }
    private val readingViewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        ReadingMiningViewModel.Factory(
            repository = ReadingRepositoryFactory.create(app),
            safBroker = app.safBroker,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRunId.value = MiningForegroundService.openedRunId(intent)
        enableEdgeToEdge()
        setContent {
            AnkiMinerTheme {
                val miningViewModel: VideoMiningViewModel = viewModel(factory = viewModelFactory)
                val readingViewModel: ReadingMiningViewModel =
                    viewModel(factory = readingViewModelFactory)
                val setupViewModel: SetupViewModel = viewModel(factory = setupViewModelFactory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory)
                val openedRunId = notificationRunId.collectAsStateWithLifecycle().value
                val app = application as AnkiMinerApplication
                val permissions =
                    MiningRuntimePermissions.requestableFor(android.os.Build.VERSION.SDK_INT)
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
                    readingViewModel = readingViewModel,
                    setupViewModel = setupViewModel,
                    settingsViewModel = settingsViewModel,
                    notificationRunId = openedRunId,
                    onNotificationRunHandled = { notificationRunId.value = null },
                    onRequestPermissions = {
                        if (permissions.isEmpty()) app.refreshMiningAdmission()
                        else permissionLauncher.launch(permissions)
                    },
                    onOpenAppSettings = ::openAppSettings,
                    onOpenSpeechSettings = ::openSpeechSettings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRunId.value = MiningForegroundService.openedRunId(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permission and provider settings may have changed while this activity was paused.
        (application as AnkiMinerApplication).refreshMiningAdmission()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun openSpeechSettings() {
        val candidates =
            listOf(
                Intent(ACTION_TTS_SETTINGS),
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            )
        candidates.forEach { candidate ->
            try {
                startActivity(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the portable engine-data action, then fall back to this app's settings.
            } catch (_: SecurityException) {
                // An OEM settings activity may exist but reject third-party callers.
            }
        }
        openAppSettings()
    }

    private companion object {
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
    }
}
