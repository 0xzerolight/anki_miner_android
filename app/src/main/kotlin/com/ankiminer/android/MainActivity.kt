package com.ankiminer.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.anki.provider.ANKIDROID_PACKAGE
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.diagnostics.EngineLogReader
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.mining.MiningRuntimePermissions
import com.ankiminer.android.reading.ReadingRepositoryFactory
import com.ankiminer.android.service.MiningForegroundService
import com.ankiminer.android.ui.navigation.AnkiMinerApp
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            runtimeWorkState = app.runtimeWorkState,
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
            runtimeWorkState = app.runtimeWorkState,
            refreshExternalReadiness = app::refreshExternalReadiness,
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
            runtimeWorkState = app.runtimeWorkState,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRunId.value = MiningForegroundService.consumeOpenedRunId(intent)
        enableEdgeToEdge()
        setContent {
            val appSettings =
                (application as AnkiMinerApplication)
                    .settingsRepository
                    .settings
                    .collectAsStateWithLifecycle(initialValue = AppSettings())
                    .value
            AnkiMinerTheme(darkTheme = appSettings.theme == ThemeMode.DARK) {
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
                        if (permissions.isEmpty()) app.refreshExternalReadiness()
                        else permissionLauncher.launch(permissions)
                    },
                    onOpenAppSettings = ::openAppSettings,
                    onInstallAnkiDroid = ::installAnkiDroid,
                    onOpenAnkiDroid = ::openAnkiDroid,
                    onOpenSpeechSettings = ::openSpeechSettings,
                    onShareDiagnostics = ::shareDiagnostics,
                    onShareEngineLog = ::shareEngineLog,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRunId.value = MiningForegroundService.consumeOpenedRunId(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permission, package, provider, and model state may have changed while paused.
        (application as AnkiMinerApplication).refreshExternalReadiness()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun installAnkiDroid() {
        val opened =
            startFirstAvailable(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ANKIDROID_PACKAGE")),
                Intent(Intent.ACTION_VIEW, Uri.parse(ANKIDROID_RELEASES_URL)),
            )
        if (!opened) {
            Toast.makeText(this, R.string.ankidroid_action_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAnkiDroid() {
        val launch = packageManager.getLaunchIntentForPackage(ANKIDROID_PACKAGE)
        if (launch != null && startFirstAvailable(launch)) return
        installAnkiDroid()
    }

    private fun startFirstAvailable(vararg candidates: Intent): Boolean {
        candidates.forEach { candidate ->
            try {
                startActivity(candidate)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next official destination.
            } catch (_: SecurityException) {
                // An OEM handler may exist but reject third-party callers.
            }
        }
        return false
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

    private fun shareEngineLog() {
        lifecycleScope.launch {
            val tail =
                withContext(Dispatchers.IO) {
                    EngineLogReader(File(filesDir, "anki_miner.log")).tail()
                }
            if (tail.isBlank()) {
                Toast.makeText(this@MainActivity, R.string.engine_log_empty, Toast.LENGTH_LONG).show()
                return@launch
            }
            shareText(getString(R.string.engine_log_share_subject), tail)
        }
    }

    private fun shareDiagnostics(report: String) {
        shareText(getString(R.string.diagnostics_share_subject), report)
    }

    private fun shareText(subject: String, text: String) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
        try {
            startActivity(Intent.createChooser(send, subject))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.diagnostics_action_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.diagnostics_action_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
        const val ANKIDROID_RELEASES_URL =
            "https://github.com/ankidroid/Anki-Android/releases"
    }
}
