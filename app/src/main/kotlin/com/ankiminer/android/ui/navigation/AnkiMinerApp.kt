package com.ankiminer.android.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.ankiminer.android.mining.runId
import com.ankiminer.android.ui.attribution.AttributionScreen
import com.ankiminer.android.ui.reading.ReadingMiningRoute
import com.ankiminer.android.ui.reading.ReadingMiningTestTags
import com.ankiminer.android.ui.settings.SettingsRoute
import com.ankiminer.android.ui.setup.SetupRoute
import com.ankiminer.android.ui.video.VideoMiningRoute
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.VideoMiningViewModel

private enum class Destination(val route: String, @StringRes val label: Int) {
    VIDEO("video", R.string.nav_video),
    READING("reading", R.string.nav_reading),
    SETUP("setup", R.string.nav_setup),
    SETTINGS("settings", R.string.nav_settings),
    ATTRIBUTION("attribution", R.string.nav_licenses),
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
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val setup by setupViewModel.uiState.collectAsStateWithLifecycle()
    val video by videoViewModel.uiState.collectAsStateWithLifecycle()
    val reading by readingViewModel.uiState.collectAsStateWithLifecycle()
    val buildIdentity = remember { currentTesterBuildIdentity() }
    val diagnostics =
        remember(buildIdentity, setup, video, reading) {
            TesterDiagnosticsBuilder.build(buildIdentity, setup, video, reading)
        }
    var keepCompletedSetupVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(
        notificationRunId,
        video.runState.runId,
        reading.runState.runId,
        setup.firstRunComplete,
    ) {
        if (!setup.firstRunComplete || notificationRunId == null) return@LaunchedEffect
        val destination =
            when (notificationRunId) {
                video.runState.runId -> Destination.VIDEO
                reading.runState.runId -> Destination.READING
                else -> null
            }
        if (destination != null) {
            navController.navigate(destination.route) {
                popUpTo(Destination.SETUP.route) { inclusive = true }
                launchSingleTop = true
            }
        }
        // The process repositories publish their current state synchronously into each ViewModel,
        // so an unmatched ID is stale and must not pin a completed user on the setup route.
        onNotificationRunHandled()
    }

    LaunchedEffect(
        setup.firstRunComplete,
        currentRoute,
        keepCompletedSetupVisible,
        notificationRunId,
    ) {
        if (
            notificationRunId == null &&
                shouldRedirectCompletedSetup(
                    setup.firstRunComplete,
                    currentRoute,
                    keepCompletedSetupVisible,
                )
        ) {
            navController.navigate(Destination.VIDEO.route) {
                popUpTo(Destination.SETUP.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute != Destination.ATTRIBUTION.route) {
                NavigationBar {
                    listOf(
                        Destination.VIDEO,
                        Destination.READING,
                        Destination.SETUP,
                        Destination.SETTINGS,
                    ).forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            enabled =
                                destination !in setOf(Destination.VIDEO, Destination.READING) ||
                                    setup.firstRunComplete,
                            onClick = {
                                if (destination == Destination.SETUP) {
                                    keepCompletedSetupVisible = true
                                }
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Text(
                                    if (currentRoute == destination.route) "●" else "○",
                                    Modifier.clearAndSetSemantics {},
                                )
                            },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.SETUP.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.SETUP.route) {
                SetupRoute(
                    viewModel = setupViewModel,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings,
                    onInstallAnkiDroid = onInstallAnkiDroid,
                    onOpenAnkiDroid = onOpenAnkiDroid,
                    onContinue = { navController.navigate(Destination.VIDEO.route) },
                )
            }
            composable(Destination.VIDEO.route) {
                if (setup.isMiningReady) {
                    VideoMiningRoute(
                        viewModel = videoViewModel,
                        modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
                    )
                } else {
                    MiningReadinessGate(
                        message = stringResource(miningReadinessMessage(setup)),
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenSetup = {
                            keepCompletedSetupVisible = true
                            navController.navigate(Destination.SETUP.route)
                        },
                    )
                }
            }
            composable(Destination.READING.route) {
                if (setup.isMiningReady) {
                    ReadingMiningRoute(
                        viewModel = readingViewModel,
                        modifier = Modifier.testTag(ReadingMiningTestTags.SCREEN),
                    )
                } else {
                    MiningReadinessGate(
                        message = stringResource(miningReadinessMessage(setup)),
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenSetup = {
                            keepCompletedSetupVisible = true
                            navController.navigate(Destination.SETUP.route)
                        },
                    )
                }
            }
            composable(Destination.SETTINGS.route) {
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
                    onAttributions = { navController.navigate(Destination.ATTRIBUTION.route) },
                )
            }
            composable(Destination.ATTRIBUTION.route) {
                AttributionScreen(
                    installedDictionaries = setup.dictionaries,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

internal fun shouldRedirectCompletedSetup(
    firstRunComplete: Boolean,
    currentRoute: String?,
    keepCompletedSetupVisible: Boolean,
): Boolean =
    firstRunComplete &&
        currentRoute == Destination.SETUP.route &&
        !keepCompletedSetupVisible

@Composable
private fun MiningReadinessGate(
    message: String,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSetup: () -> Unit,
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
                OutlinedButton(onClick = onOpenSetup) { Text(stringResource(R.string.open_setup_resources)) }
            }
        }
    }
}

@StringRes
private fun miningReadinessMessage(state: com.ankiminer.android.ui.setup.SetupUiState): Int =
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
