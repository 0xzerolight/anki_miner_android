package com.ankiminer.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.diagnostics.AnkiFaultRecorder
import com.ankiminer.android.diagnostics.TesterDiagnosticsBuilder
import com.ankiminer.android.diagnostics.currentTesterBuildIdentity
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.mining.MiningRuntimePermissions
import com.ankiminer.android.reading.ReadingRepositoryFactory
import com.ankiminer.android.service.MiningForegroundService
import com.ankiminer.android.ui.navigation.AnkiMinerApp
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.theme.LaunchNeutral
import com.ankiminer.android.ui.theme.SystemBarIconAppearance
import com.ankiminer.android.ui.theme.ThemeSlots
import com.ankiminer.android.ui.theme.color
import com.ankiminer.android.ui.theme.resolveTheme
import com.ankiminer.android.ui.theme.systemBarIconAppearance
import com.ankiminer.android.vm.DiagnosticsViewModel
import com.ankiminer.android.vm.MediaMiningViewModel
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupViewModel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DIAGNOSTICS_MIME_TYPE = "application/zip"

/**
 * Settings the shell paints with. `null` means "not read yet" and holds the launch placeholder; a
 * store which cannot be read resolves to the fresh-store default instead, so Settings, setup, and
 * diagnostics stay reachable rather than the app sitting on the placeholder forever. Every
 * settings write still goes through the strict flow.
 */
internal fun AppSettingsRepository.appShellSettings(): Flow<AppSettings> =
    settingsOrNull.map { it ?: AppSettings() }

/** Keeps SAF local-save available even when no installed app accepts the ZIP send intent. */
internal fun diagnosticsDeliveryChooserIntent(
    resolver: ContentResolver,
    attachment: Uri,
    fileName: String,
    subject: String,
): Intent {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = DIAGNOSTICS_MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, attachment)
            clipData = ClipData.newUri(resolver, fileName, attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val save =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = DIAGNOSTICS_MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
    return Intent.createChooser(send, subject).apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(save))
    }
}

internal fun copyDiagnosticsBundle(
    openSource: () -> InputStream?,
    openDestination: () -> OutputStream?,
) {
    val source = openSource() ?: throw IOException("Diagnostics source returned no stream")
    source.use { input ->
        val destination =
            openDestination() ?: throw IOException("Diagnostics destination returned no stream")
        destination.use { output -> input.copyTo(output) }
    }
}

class MainActivity : ComponentActivity() {
    private val notificationRunId = MutableStateFlow<String?>(null)
    private var pendingDiagnosticsAttachment: Uri? = null
    private val diagnosticsDeliveryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val source = pendingDiagnosticsAttachment
            pendingDiagnosticsAttachment = null
            val resultData = result.data
            val destination = resultData?.data
            val hasWriteGrant =
                resultData != null &&
                    resultData.flags.and(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
            if (
                result.resultCode == RESULT_OK &&
                    source != null &&
                    destination != null &&
                    hasWriteGrant
            ) {
                saveDiagnosticsBundle(source, destination)
            }
        }

    private val viewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        MediaMiningViewModel.Factory(
            repository = MiningRepositoryFactory.create(app),
            safBroker = app.safBroker,
            lane = MiningLane.VIDEO,
            definitionLookup = app.definitionLookupService,
            cueLookup = app.subtitleCueLookupService,
            runtimeWorkState = app.runtimeWorkState,
            selectionInventory = app.safSelectionInventory,
            effectiveSubtitleOffset =
                app.settingsRepository.settings.map { it.subtitleOffsetSeconds },
            fieldMap = app.settingsRepository.settings.map { it.fieldMap },
            audioPacks = app.resourceManager.state.map { it.audioPacks },
            timingPreviewOpener = app.timingPreviewLoader,
        )
    }
    private val audioViewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        MediaMiningViewModel.Factory(
            repository = MiningRepositoryFactory.createAudio(app),
            safBroker = app.safBroker,
            lane = MiningLane.AUDIO,
            definitionLookup = app.definitionLookupService,
            cueLookup = app.subtitleCueLookupService,
            runtimeWorkState = app.runtimeWorkState,
            selectionInventory = app.safSelectionInventory,
            effectiveSubtitleOffset =
                app.settingsRepository.settings.map { it.subtitleOffsetSeconds },
            fieldMap = app.settingsRepository.settings.map { it.fieldMap },
            audioPacks = app.resourceManager.state.map { it.audioPacks },
            timingPreviewOpener = app.timingPreviewLoader,
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
        SettingsViewModel.Factory(
            app.settingsRepository,
            app.resourceManager,
            app.settingsDocumentReader,
            app.resourceDocumentWriter,
            BuildConfig.VERSION_NAME,
        )
    }
    private val readingViewModelFactory by lazy {
        val app = application as AnkiMinerApplication
        ReadingMiningViewModel.Factory(
            repository = ReadingRepositoryFactory.create(app),
            safBroker = app.safBroker,
            definitionLookup = app.definitionLookupService,
            runtimeWorkState = app.runtimeWorkState,
            selectionInventory = app.safSelectionInventory,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDiagnosticsAttachment =
            savedInstanceState
                ?.getString(PENDING_DIAGNOSTICS_ATTACHMENT)
                ?.let(Uri::parse)
        notificationRunId.value =
            savedInstanceState?.getString(PENDING_NOTIFICATION_RUN_ID)
                ?: MiningForegroundService.consumeOpenedRunId(intent)
        setContent {
            val app = application as AnkiMinerApplication
            val shellSettings = remember(app) { app.settingsRepository.appShellSettings() }
            val settings =
                shellSettings
                    .collectAsStateWithLifecycle(initialValue = null)
                    .value
            if (settings == null) {
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
            val verboseLogging =
                app.diagnosticsSettings.verboseLogging
                    .collectAsStateWithLifecycle(initialValue = false)
                    .value
            val updateCheck =
                app.updateCheckCoordinator.uiState
                    .collectAsStateWithLifecycle()
                    .value
            val resolved = resolveTheme(settings, isSystemInDarkTheme())
            // Dynamic schemes match the palette's light/dark choice, so its page decides bar icons.
            val iconAppearance =
                systemBarIconAppearance(resolved.palette.color(ThemeSlots.BACKGROUND))
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
            AnkiMinerTheme(palette = resolved.palette, dynamicColor = resolved.dynamicColor) {
                val videoMiningViewModel: MediaMiningViewModel =
                    viewModel(
                        key = MiningLane.VIDEO.savedStateKeyPrefix,
                        factory = viewModelFactory,
                    )
                val audioMiningViewModel: MediaMiningViewModel =
                    viewModel(
                        key = MiningLane.AUDIO.savedStateKeyPrefix,
                        factory = audioViewModelFactory,
                    )
                val readingViewModel: ReadingMiningViewModel =
                    viewModel(factory = readingViewModelFactory)
                val setupViewModel: SetupViewModel = viewModel(factory = setupViewModelFactory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory)
                val diagnosticsBuild = remember { currentTesterBuildIdentity() }
                val diagnosticsViewModelFactory =
                    remember(
                        app,
                        setupViewModel,
                        videoMiningViewModel,
                        audioMiningViewModel,
                        readingViewModel,
                    ) {
                        DiagnosticsViewModel.Factory(
                            app.createDiagnosticsExporter {
                                TesterDiagnosticsBuilder.build(
                                    build = diagnosticsBuild,
                                    setup = setupViewModel.uiState.value,
                                    video = videoMiningViewModel.uiState.value,
                                    audio = audioMiningViewModel.uiState.value,
                                    reading = readingViewModel.uiState.value,
                                    lastAnkiFault = AnkiFaultRecorder.lastFault(),
                                ).report
                            },
                        )
                    }
                val diagnosticsViewModel: DiagnosticsViewModel =
                    viewModel(factory = diagnosticsViewModelFactory)
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
                    videoViewModel = videoMiningViewModel,
                    audioViewModel = audioMiningViewModel,
                    readingViewModel = readingViewModel,
                    setupViewModel = setupViewModel,
                    settingsViewModel = settingsViewModel,
                    diagnosticsViewModel = diagnosticsViewModel,
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
                    onShareDiagnosticsBundle = ::shareDiagnosticsBundle,
                    verboseLogging = verboseLogging,
                    onVerboseLoggingChange = app::setVerboseLogging,
                    updateCheck = updateCheck,
                    onUpdateCheckEnabledChange = app::setUpdateCheckEnabled,
                    onCheckForUpdates = app::checkForUpdates,
                    onSkipUpdate = app::skipAvailableUpdate,
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
        pendingDiagnosticsAttachment?.let { attachment ->
            outState.putString(PENDING_DIAGNOSTICS_ATTACHMENT, attachment.toString())
        }
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

    private fun shareDiagnosticsBundle(
        uri: String,
        fileName: String,
    ): Boolean {
        val attachment = Uri.parse(uri)
        val subject = getString(R.string.diagnostics_bundle_share_subject)
        val chooser =
            diagnosticsDeliveryChooserIntent(
                resolver = contentResolver,
                attachment = attachment,
                fileName = fileName,
                subject = subject,
            )
        pendingDiagnosticsAttachment = attachment
        return try {
            diagnosticsDeliveryLauncher.launch(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            pendingDiagnosticsAttachment = null
            false
        } catch (_: SecurityException) {
            pendingDiagnosticsAttachment = null
            false
        }
    }

    private fun saveDiagnosticsBundle(
        source: Uri,
        destination: Uri,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                copyDiagnosticsBundle(
                    openSource = { contentResolver.openInputStream(source) },
                    openDestination = { contentResolver.openOutputStream(destination, "wt") },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                AppLog.w(
                    LogComponent.DIAG,
                    "bundle.save",
                    failure,
                    "outcome" to "fail",
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.diagnostics_action_unavailable,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private companion object {
        const val PENDING_NOTIFICATION_RUN_ID = "pending_notification_run_id"
        const val PENDING_DIAGNOSTICS_ATTACHMENT = "pending_diagnostics_attachment"
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
        const val ANKIDROID_RELEASES_URL =
            "https://github.com/ankidroid/Anki-Android/releases"
    }
}
