package com.ankiminer.android

import android.content.ActivityNotFoundException
import android.content.ClipData
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.anki.provider.ANKIDROID_PACKAGE
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.diagnostics.AnkiFaultRecorder
import com.ankiminer.android.diagnostics.TesterDiagnosticsBuilder
import com.ankiminer.android.diagnostics.currentTesterBuildIdentity
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningRepositoryFactory
import com.ankiminer.android.mining.MiningRunUndoManagerFactory
import com.ankiminer.android.mining.MiningRuntimePermissions
import com.ankiminer.android.mining.ankiPermissionPermanentlyDenied
import com.ankiminer.android.mining.notificationPermissionDue
import com.ankiminer.android.reading.ReadingRepositoryFactory
import com.ankiminer.android.service.MiningCompletionNotifier
import com.ankiminer.android.service.MiningForegroundService
import com.ankiminer.android.ui.mining.LocalMiningContentStyle
import com.ankiminer.android.ui.mining.MiningContentStyle
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
import com.ankiminer.android.vm.NavigationWorkflowState
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Settings the shell paints with. `null` means "not read yet" and holds the launch placeholder; a
 * store which cannot be read resolves to the fresh-store default instead, so Settings, setup, and
 * diagnostics stay reachable rather than the app sitting on the placeholder forever. Every
 * settings write still goes through the strict flow.
 */
internal fun AppSettingsRepository.appShellSettings(): Flow<AppSettings> =
    settingsOrNull.map { it ?: AppSettings() }

/**
 * The audio packs the mining language's runs consult. Another language's pack is never in its
 * chain, so it must not raise the Video and Audio tabs' word-audio warnings either.
 */
internal fun activeLanguageAudioPacks(
    settings: Flow<AppSettings>,
    resources: Flow<ResourceManagerState>,
): Flow<List<InstalledAudioPack>> =
    combine(settings, resources) { current, state -> state.slotsFor(current.language).audioPacks }

private fun AnkiMinerApplication.activeLanguageAudioPacks(): Flow<List<InstalledAudioPack>> =
    activeLanguageAudioPacks(settingsRepository.settings, resourceManager.state)

class MainActivity : ComponentActivity() {
    private val notificationRunId = MutableStateFlow<String?>(null)
    private val sharedText = MutableStateFlow<String?>(null)

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
            audioPaddingSeconds =
                app.settingsRepository.settings.map { it.audioPaddingSeconds },
            fieldMap = app.settingsRepository.settings.map { it.fieldMap },
            audioPacks = app.activeLanguageAudioPacks(),
            timingPreviewOpener = app.timingPreviewLoader,
            undoManager = MiningRunUndoManagerFactory.create(app),
            audioTrackProbeOpener = app.audioTrackProbeLoader,
            secondarySubtitleEnabled =
                app.settingsRepository.settings.map { it.secondarySubtitleEnabled },
            deckName = app.settingsRepository.settings.map { it.deckName ?: AnkiMinerNoteModel.DEFAULT_DECK_NAME },
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
            audioPaddingSeconds =
                app.settingsRepository.settings.map { it.audioPaddingSeconds },
            fieldMap = app.settingsRepository.settings.map { it.fieldMap },
            audioPacks = app.activeLanguageAudioPacks(),
            timingPreviewOpener = app.timingPreviewLoader,
            undoManager = MiningRunUndoManagerFactory.create(app),
            audioTrackProbeOpener = app.audioTrackProbeLoader,
            deckName = app.settingsRepository.settings.map { it.deckName ?: AnkiMinerNoteModel.DEFAULT_DECK_NAME },
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
            languageProfileSource = app.languageProfileSource,
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
            app.languageProfileSource,
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
            undoManager = MiningRunUndoManagerFactory.create(app),
            fieldMap = app.settingsRepository.settings.map { it.fieldMap },
            audioPacks = app.activeLanguageAudioPacks(),
            deckName = app.settingsRepository.settings.map { it.deckName ?: AnkiMinerNoteModel.DEFAULT_DECK_NAME },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as AnkiMinerApplication).startMiningCompletionNotices()
        notificationRunId.value =
            savedInstanceState?.getString(PENDING_NOTIFICATION_RUN_ID)
                ?: consumeOpenedRunId(intent)
        if (savedInstanceState == null) sharedText.value = consumeSharedText(intent)
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
                // Connect AnkiDroid asks for AnkiDroid's permission and nothing else; the
                // notification permission is asked on its own, when a job first needs it.
                val ankiPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (
                            ankiPermissionPermanentlyDenied(
                                granted = granted,
                                showRationale =
                                    shouldShowRequestPermissionRationale(MiningRuntimePermissions.ANKIDROID_DATABASE),
                            )
                        ) {
                            setupViewModel.permissionBlocked()
                        }
                        setupViewModel.permissionsReturned()
                    }
                val notificationPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {
                        setupViewModel.permissionsReturned()
                    }
                val notificationsReady =
                    setupViewModel.uiState.collectAsStateWithLifecycle().value.notificationReady
                val resourceJobStarts = app.foregroundJobStarts.collectAsStateWithLifecycle().value
                val miningStarting =
                    listOf(
                        videoMiningViewModel.navigationWorkflowState.collectAsStateWithLifecycle().value,
                        audioMiningViewModel.navigationWorkflowState.collectAsStateWithLifecycle().value,
                        readingViewModel.navigationWorkflowState.collectAsStateWithLifecycle().value,
                    ).any { it == NavigationWorkflowState.RUNNING }
                val foregroundJobStarting = resourceJobStarts > 0 || miningStarting
                LaunchedEffect(foregroundJobStarting) {
                    val sdkInt = android.os.Build.VERSION.SDK_INT
                    val permission = MiningRuntimePermissions.notificationPermissionFor(sdkInt)
                    if (
                        permission != null &&
                        notificationPermissionDue(
                            sdkInt = sdkInt,
                            notificationsReady = notificationsReady,
                            foregroundJobStarting = foregroundJobStarting,
                            alreadyAskedThisProcess = notificationPermissionAsked.get(),
                        )
                    ) {
                        notificationPermissionAsked.set(true)
                        notificationPermissionLauncher.launch(permission)
                    }
                }
                val languageProfiles =
                    settingsViewModel.languageProfiles.collectAsStateWithLifecycle().value
                val contentStyle =
                    remember(settings.language, languageProfiles) {
                        MiningContentStyle.forLanguage(settings.language, languageProfiles)
                    }
                CompositionLocalProvider(LocalMiningContentStyle provides contentStyle) {
                    AnkiMinerApp(
                        videoViewModel = videoMiningViewModel,
                        audioViewModel = audioMiningViewModel,
                        readingViewModel = readingViewModel,
                        setupViewModel = setupViewModel,
                        settingsViewModel = settingsViewModel,
                        diagnosticsViewModel = diagnosticsViewModel,
                        notificationRunId = openedRunId,
                        onNotificationRunHandled = { notificationRunId.value = null },
                        sharedText = sharedText.collectAsStateWithLifecycle().value,
                        onSharedTextHandled = { sharedText.value = null },
                        onRequestPermissions = {
                            ankiPermissionLauncher.launch(MiningRuntimePermissions.ANKIDROID_DATABASE)
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRunId.value = consumeOpenedRunId(intent)
        consumeSharedText(intent)?.let { sharedText.value = it }
    }

    /** Reads, then removes, handed-over text so Activity recreation cannot replay it. */
    private fun consumeSharedText(intent: Intent?): String? {
        intent ?: return null
        val text =
            sharedTextFrom(
                intent.action,
                intent.type,
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            ) ?: return null
        intent.action = null
        intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        intent.removeExtra(Intent.EXTRA_TEXT)
        return text
    }

    /** A run opened from either the progress notification or the completion notice. */
    private fun consumeOpenedRunId(intent: Intent?): String? =
        MiningForegroundService.consumeOpenedRunId(intent)
            ?: MiningCompletionNotifier.consumeOpenedRunId(intent)

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

    private fun shareDiagnosticsBundle(
        uri: String,
        fileName: String,
    ): Boolean {
        val attachment = Uri.parse(uri)
        val subject = getString(R.string.diagnostics_bundle_share_subject)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_STREAM, attachment)
                clipData = ClipData.newUri(contentResolver, fileName, attachment)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return try {
            startActivity(Intent.createChooser(send, subject))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        /** Process-wide: the notification permission is asked at most once per process. */
        val notificationPermissionAsked = AtomicBoolean(false)

        const val PENDING_NOTIFICATION_RUN_ID = "pending_notification_run_id"
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
        const val ANKIDROID_RELEASES_URL =
            "https://github.com/ankidroid/Anki-Android/releases"
    }
}
