package com.ankiminer.android.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.TesterDiagnosticsBuilder
import com.ankiminer.android.diagnostics.currentTesterBuildIdentity
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.runId
import com.ankiminer.android.ui.attribution.AttributionScreen
import com.ankiminer.android.ui.attribution.NoticesScreen
import com.ankiminer.android.ui.reading.ReadingMiningRoute
import com.ankiminer.android.ui.reading.ReadingMiningTestTags
import com.ankiminer.android.ui.settings.MessageSnackbarEffect
import com.ankiminer.android.ui.settings.SettingsRoute
import com.ankiminer.android.ui.video.VideoMiningRoute
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.ui.wizard.OnboardingWizard
import com.ankiminer.android.ui.wizard.wizardVisible
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.VideoMiningViewModel

internal enum class AnkiMinerDestination(
    val route: String,
    @param:StringRes val label: Int,
    val showsBottomBar: Boolean,
) {
    VIDEO("video", R.string.nav_video, true),
    READING("reading", R.string.nav_reading, true),
    SETTINGS("settings", R.string.nav_settings, true),
    ATTRIBUTION("attribution", R.string.nav_licenses, false),
    NOTICES("notices", R.string.nav_notices, false),
}

internal fun miningWorkflowVisible(
    setupReady: Boolean,
    runState: MiningRunState,
): Boolean = setupReady || runState != MiningRunState.Idle

/**
 * Production app chrome shared with instrumented UI capture. Legal routes intentionally hide the
 * bottom navigation, matching their production presentation.
 */
@Composable
internal fun AnkiMinerAppShell(
    currentDestination: AnkiMinerDestination?,
    snackbarHostState: SnackbarHostState,
    onDestinationSelected: (AnkiMinerDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentDestination?.showsBottomBar != false) {
                NavigationBar {
                    listOf(
                        AnkiMinerDestination.VIDEO,
                        AnkiMinerDestination.READING,
                        AnkiMinerDestination.SETTINGS,
                    ).forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination == destination,
                            onClick = { onDestinationSelected(destination) },
                            // Text-only tabs mirror the desktop app; the label lives in the
                            // icon slot because Material3 requires an icon composable.
                            icon = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        content(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        )
    }
}

@Composable
internal fun AnkiMinerApp(
    videoViewModel: VideoMiningViewModel,
    readingViewModel: ReadingMiningViewModel,
    setupViewModel: SetupViewModel,
    settingsViewModel: SettingsViewModel,
    notificationRunId: String?,
    onNotificationRunHandled: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onShareEngineLog: () -> Unit,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val currentDestination =
        AnkiMinerDestination.entries.firstOrNull { destination ->
            destination.route == currentRoute
        }
    val setup by setupViewModel.uiState.collectAsStateWithLifecycle()
    val wizardDismissedForSession by
        setupViewModel.wizardDismissedForSession.collectAsStateWithLifecycle()
    val video by videoViewModel.uiState.collectAsStateWithLifecycle()
    val reading by readingViewModel.uiState.collectAsStateWithLifecycle()
    val buildIdentity = remember { currentTesterBuildIdentity() }
    val diagnostics =
        remember(buildIdentity, setup, video, reading) {
            TesterDiagnosticsBuilder.build(buildIdentity, setup, video, reading)
        }
    var wizardRerunRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(
        notificationRunId,
        video.runState.runId,
        reading.runState.runId,
    ) {
        if (notificationRunId == null) return@LaunchedEffect
        val destination =
            when (notificationRunId) {
                video.runState.runId -> AnkiMinerDestination.VIDEO
                reading.runState.runId -> AnkiMinerDestination.READING
                else -> null
            }
        if (destination != null) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
        // The process repositories publish their current state synchronously into each ViewModel,
        // so an unmatched ID is stale and must be released.
        onNotificationRunHandled()
    }

    if (
        wizardVisible(
            wizardSeen = setup.wizardSeen,
            rerunRequested = wizardRerunRequested,
            sessionDismissed = wizardDismissedForSession,
            completion = setup.wizardCompletion,
        )
    ) {
        LaunchedEffect(setupViewModel) { setupViewModel.refresh() }
        OnboardingWizard(
            state = setup,
            viewModel = setupViewModel,
            onRequestPermissions = onRequestPermissions,
            onOpenAppSettings = onOpenAppSettings,
            onInstallAnkiDroid = onInstallAnkiDroid,
            onOpenAnkiDroid = onOpenAnkiDroid,
            onFinished = {
                wizardRerunRequested = false
                if (setup.wizardSeen != true) setupViewModel.markWizardSeen()
            },
        )
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val settingsErrorResource by settingsViewModel.error.collectAsStateWithLifecycle()
    val settingsError =
        settingsErrorResource?.let {
            stringResource(it.resourceId, *it.formatArguments.toTypedArray())
        }
    MessageSnackbarEffect(settingsError, snackbarHostState, settingsViewModel::dismissError)
    MessageSnackbarEffect(setup.failure?.message, snackbarHostState, setupViewModel::dismissFailure)
    MessageSnackbarEffect(setup.ankiFailure?.message, snackbarHostState, setupViewModel::dismissAnkiFailure)
    MessageSnackbarEffect(
        setup.ankiRecoveryFailure?.message,
        snackbarHostState,
        setupViewModel::dismissAnkiFailure,
    )

    AnkiMinerAppShell(
        currentDestination = currentDestination,
        snackbarHostState = snackbarHostState,
        onDestinationSelected = { destination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) { shellModifier ->
        NavHost(
            navController = navController,
            startDestination = AnkiMinerDestination.VIDEO.route,
            modifier = shellModifier,
        ) {
            composable(AnkiMinerDestination.VIDEO.route) {
                if (miningWorkflowVisible(setup.isMiningReady, video.runState)) {
                    VideoMiningRoute(
                        viewModel = videoViewModel,
                        modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
                    )
                } else {
                    MiningReadinessNotice(
                        message = stringResource(miningReadinessMessage(setup)),
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenSettings = {
                            navController.navigate(AnkiMinerDestination.SETTINGS.route)
                        },
                    )
                }
            }
            composable(AnkiMinerDestination.READING.route) {
                if (miningWorkflowVisible(setup.isMiningReady, reading.runState)) {
                    ReadingMiningRoute(
                        viewModel = readingViewModel,
                        modifier = Modifier.testTag(ReadingMiningTestTags.SCREEN),
                    )
                } else {
                    MiningReadinessNotice(
                        message = stringResource(miningReadinessMessage(setup)),
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenSettings = {
                            navController.navigate(AnkiMinerDestination.SETTINGS.route)
                        },
                    )
                }
            }
            composable(AnkiMinerDestination.SETTINGS.route) {
                SettingsRoute(
                    viewModel = settingsViewModel,
                    setupViewModel = setupViewModel,
                    diagnostics = diagnostics,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings,
                    onInstallAnkiDroid = onInstallAnkiDroid,
                    onOpenAnkiDroid = onOpenAnkiDroid,
                    onOpenSpeechSettings = onOpenSpeechSettings,
                    onShareDiagnostics = onShareDiagnostics,
                    onShareEngineLog = onShareEngineLog,
                    onAttributions = {
                        navController.navigate(AnkiMinerDestination.ATTRIBUTION.route)
                    },
                    onRunSetupWizard = { wizardRerunRequested = true },
                )
            }
            composable(AnkiMinerDestination.ATTRIBUTION.route) {
                AttributionScreen(
                    installedDictionaries = setup.dictionaries,
                    onBack = { navController.popBackStack() },
                    onOpenNotices = {
                        navController.navigate(AnkiMinerDestination.NOTICES.route)
                    },
                )
            }
            composable(AnkiMinerDestination.NOTICES.route) {
                NoticesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun MiningReadinessNotice(
    message: String,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.mining_not_ready),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                Button(onClick = onRequestPermissions) { Text(stringResource(R.string.allow_required_access)) }
                OutlinedButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.open_app_settings))
                }
                OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}

@StringRes
private fun miningReadinessMessage(state: SetupUiState): Int =
    when {
        !state.pythonReady -> R.string.readiness_python_pending
        state.resourceStartup != com.ankiminer.android.data.resources.ResourceStartupReadiness.READY ->
            R.string.readiness_resources_pending
        !state.uniDicInstalled -> R.string.readiness_unidic_required
        !state.ankiReady -> R.string.readiness_anki_required
        !state.targetReady -> R.string.readiness_model_required
        !state.recoveryReady -> R.string.readiness_recovery_required
        state.busy -> R.string.readiness_resource_operation
        else -> R.string.readiness_unknown
    }
