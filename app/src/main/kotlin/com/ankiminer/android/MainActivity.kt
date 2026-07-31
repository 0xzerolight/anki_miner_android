package com.ankiminer.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.anki.provider.ANKIDROID_PACKAGE
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.diagnostics.EngineLogReader
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.mining.MiningRuntimePermissions
import com.ankiminer.android.reading.ReadingRepositoryFactory
import com.ankiminer.android.service.MiningForegroundService
import com.ankiminer.android.ui.navigation.AnkiMinerApp
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.theme.LaunchNeutral
import com.ankiminer.android.ui.theme.SystemBarIconAppearance
import com.ankiminer.android.ui.theme.systemBarIconAppearance
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.VideoMiningViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val notificationRunId = MutableStateFlow<String?>(null)

    private val viewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        VideoMiningViewModel.Factory(
            repository = MiningRepositoryFactory.create(app),
            safBroker = app.safBroker,
            runtimeWorkState = app.runtimeWorkState,
            selectionInventory = app.safSelectionInventory,
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
            strings = app.stringResourceResolver,
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
            selectionInventory = app.safSelectionInventory,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRunId.value =
            savedInstanceState?.getString(PENDING_NOTIFICATION_RUN_ID)
                ?: MiningForegroundService.consumeOpenedRunId(intent)
        setContent {
            val app = application as AnkiMinerApplication
            val nullableSettings =
                remember(app) {
                    app.settingsRepository.settings.map<AppSettings, AppSettings?> { it }
                }
            val appSettings =
                nullableSettings
                    .collectAsStateWithLifecycle(initialValue = null)
                    .value
            if (appSettings == null) {
                LaunchedEffect(Unit) {
                    val launchStyle = SystemBarStyle.dark(LaunchNeutral.toArgb())
                    enableEdgeToEdge(
                        statusBarStyle = launchStyle,
                        navigationBarStyle = launchStyle,
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LaunchNeutral),
                )
                return@setContent
            }
            val darkTheme = appSettings.theme == ThemeMode.DARK
            val iconAppearance = systemBarIconAppearance(darkTheme)
            LaunchedEffect(iconAppearance) {
                val systemBarStyle =
                    // AndroidX style names describe bar backgrounds; icon tones are inverse.
                    when (iconAppearance) {
                        SystemBarIconAppearance.LIGHT -> SystemBarStyle.dark(Color.TRANSPARENT)
                        SystemBarIconAppearance.DARK ->
                            SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                    }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle,
                )
            }
            AnkiMinerTheme(darkTheme = darkTheme) {
                val miningViewModel: VideoMiningViewModel = viewModel(factory = viewModelFactory)
                val readingViewModel: ReadingMiningViewModel =
                    viewModel(factory = readingViewModelFactory)
                val setupViewModel: SetupViewModel = viewModel(factory = setupViewModelFactory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory)
                val openedRunId = notificationRunId.collectAsStateWithLifecycle().value
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

    override fun onSaveInstanceState(outState: Bundle) {
        notificationRunId.value?.let { runId ->
            outState.putString(PENDING_NOTIFICATION_RUN_ID, runId)
        }
        super.onSaveInstanceState(outState)
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
        const val PENDING_NOTIFICATION_RUN_ID = "pending_notification_run_id"
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
        const val ANKIDROID_RELEASES_URL =
            "https://github.com/ankidroid/Anki-Android/releases"
    }
}
