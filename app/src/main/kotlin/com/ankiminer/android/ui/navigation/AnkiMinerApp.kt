package com.ankiminer.android.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.AnkiFaultRecorder
import com.ankiminer.android.diagnostics.TesterDiagnosticsBuilder
import com.ankiminer.android.diagnostics.TesterDiagnosticsShareAction
import com.ankiminer.android.diagnostics.currentTesterBuildIdentity
import com.ankiminer.android.mining.runId
import com.ankiminer.android.ui.attribution.AttributionScreen
import com.ankiminer.android.ui.attribution.NoticesScreen
import com.ankiminer.android.ui.reading.ReadingMiningRoute
import com.ankiminer.android.ui.reading.ReadingMiningTestTags
import com.ankiminer.android.ui.settings.KnownWordsManagerRoute
import com.ankiminer.android.ui.settings.MessageSnackbarEffect
import com.ankiminer.android.ui.settings.SettingsCategory
import com.ankiminer.android.ui.settings.SettingsRoute
import com.ankiminer.android.ui.settings.settingsCardIndexFor
import com.ankiminer.android.ui.settings.settingsCategoryFor
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.ScreenTitle
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.ui.theme.tonalActionButtonColors
import com.ankiminer.android.ui.video.VideoMiningRoute
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.ui.wizard.OnboardingWizard
import com.ankiminer.android.ui.wizard.wizardVisible
import com.ankiminer.android.vm.DiagnosticsViewModel
import com.ankiminer.android.vm.MiningReadinessAction
import com.ankiminer.android.vm.NavigationWorkflowState
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.VideoMiningViewModel

internal enum class AnkiMinerDestination(
    val route: String,
    @param:StringRes val label: Int,
    @param:StringRes val appBarTitle: Int,
    @param:DrawableRes val icon: Int?,
    @param:StringRes val contentDescription: Int?,
    val showsBottomBar: Boolean,
) {
    VIDEO(
        "video",
        R.string.nav_video,
        R.string.nav_video,
        R.drawable.ic_nav_video,
        R.string.nav_video_description,
        true,
    ),
    READING(
        "reading",
        R.string.nav_reading,
        R.string.nav_reading,
        R.drawable.ic_nav_reading,
        R.string.nav_reading_description,
        true,
    ),
    SETTINGS(
        "settings",
        R.string.nav_settings,
        R.string.nav_settings,
        R.drawable.ic_nav_settings,
        R.string.nav_settings_description,
        true,
    ),
    KNOWN_WORDS_MANAGER(
        "known-words-manager",
        R.string.b3_known_words_title,
        R.string.b3_known_words_title,
        null,
        null,
        false,
    ),
    ATTRIBUTION(
        "attribution",
        R.string.nav_licenses,
        R.string.attribution_title,
        null,
        null,
        false,
    ),
    NOTICES(
        "notices",
        R.string.nav_notices,
        R.string.notices_title,
        null,
        null,
        false,
    ),
}

internal fun miningWorkflowVisible(
    setupReady: Boolean,
    workflow: NavigationWorkflowState,
): Boolean = setupReady || workflow != NavigationWorkflowState.IDLE

internal fun compactNavigation(
    widthDp: Int,
    fontScale: Float,
): Boolean = widthDp < 360 && fontScale >= 1.3f

internal fun activeWorkflowDestination(
    video: NavigationWorkflowState,
    reading: NavigationWorkflowState,
): AnkiMinerDestination? =
    when {
        video == NavigationWorkflowState.REVIEW -> AnkiMinerDestination.VIDEO
        reading == NavigationWorkflowState.REVIEW -> AnkiMinerDestination.READING
        video == NavigationWorkflowState.RUNNING -> AnkiMinerDestination.VIDEO
        reading == NavigationWorkflowState.RUNNING -> AnkiMinerDestination.READING
        else -> null
    }

/**
 * Production app chrome shared with instrumented UI capture. Legal routes intentionally hide the
 * bottom navigation, matching their production presentation.
 */
@Composable
internal fun AnkiMinerAppShell(
    currentDestination: AnkiMinerDestination?,
    videoWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
    readingWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
    snackbarHostState: SnackbarHostState,
    onDestinationSelected: (AnkiMinerDestination) -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val compact =
            compactNavigation(
                widthDp = maxWidth.value.toInt(),
                fontScale = LocalDensity.current.fontScale,
            )
        Scaffold(
            topBar = {
                currentDestination?.let { destination ->
                    AppChrome(
                        title = stringResource(destination.appBarTitle),
                        onNavigateBack =
                            if (destination.showsBottomBar) null else onNavigateBack,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (currentDestination?.showsBottomBar == true) {
                    NavigationBar {
                        listOf(
                            AnkiMinerDestination.VIDEO,
                            AnkiMinerDestination.READING,
                            AnkiMinerDestination.SETTINGS,
                        ).forEach { destination ->
                            val workflow =
                                when (destination) {
                                    AnkiMinerDestination.VIDEO -> videoWorkflow
                                    AnkiMinerDestination.READING -> readingWorkflow
                                    else -> NavigationWorkflowState.IDLE
                                }
                            val description =
                                destination.contentDescription?.let { stringResource(it) }
                            val workflowDescription =
                                when (workflow) {
                                    NavigationWorkflowState.IDLE -> null
                                    NavigationWorkflowState.RUNNING ->
                                        stringResource(R.string.nav_workflow_running)
                                    NavigationWorkflowState.REVIEW ->
                                        stringResource(R.string.nav_workflow_review)
                                }
                            NavigationBarItem(
                                selected = currentDestination == destination,
                                onClick = { onDestinationSelected(destination) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            when {
                                                workflowDescription == null -> Unit
                                                compact -> Badge()
                                                else -> {
                                                    Badge {
                                                        Text(
                                                            workflowDescription,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        modifier =
                                            if (workflowDescription == null) {
                                                Modifier
                                            } else {
                                                Modifier.semantics {
                                                    stateDescription = workflowDescription
                                                }
                                            },
                                    ) {
                                        Icon(
                                            painter = painterResource(requireNotNull(destination.icon)),
                                            contentDescription = description.takeIf { compact },
                                        )
                                    }
                                },
                                label =
                                    if (compact) {
                                        null
                                    } else {
                                        {
                                            Text(
                                                text = stringResource(destination.label),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                alwaysShowLabel = !compact,
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
}

@Composable
internal fun AnkiMinerApp(
    videoViewModel: VideoMiningViewModel,
    readingViewModel: ReadingMiningViewModel,
    setupViewModel: SetupViewModel,
    settingsViewModel: SettingsViewModel,
    diagnosticsViewModel: DiagnosticsViewModel,
    notificationRunId: String?,
    onNotificationRunHandled: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onShareDiagnosticsBundle: (uri: String, fileName: String) -> Boolean,
    verboseLogging: Boolean,
    onVerboseLoggingChange: (Boolean) -> Unit,
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
    val videoWorkflow by
        videoViewModel.navigationWorkflowState.collectAsStateWithLifecycle()
    val readingWorkflow by
        readingViewModel.navigationWorkflowState.collectAsStateWithLifecycle()
    val buildIdentity = remember { currentTesterBuildIdentity() }
    val diagnosticsIdentity =
        remember(buildIdentity) {
            TesterDiagnosticsBuilder.identity(buildIdentity)
        }
    val diagnosticsShareAction =
        remember(
            buildIdentity,
            setupViewModel,
            videoViewModel,
            readingViewModel,
            onShareDiagnostics,
        ) {
            TesterDiagnosticsShareAction(
                buildReport = {
                    TesterDiagnosticsBuilder.build(
                        build = buildIdentity,
                        setup = setupViewModel.uiState.value,
                        video = videoViewModel.uiState.value,
                        reading = readingViewModel.uiState.value,
                        lastAnkiFault = AnkiFaultRecorder.lastFault(),
                    ).report
                },
                shareReport = onShareDiagnostics,
            )
        }
    var wizardRerunRequested by rememberSaveable { mutableStateOf(false) }
    var wizardRedirectedToSettings by rememberSaveable { mutableStateOf(false) }
    var requestedSettingsCategory by
        rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var requestedSettingsItemIndex by rememberSaveable { mutableStateOf(2) }

    fun navigateTo(destination: AnkiMinerDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(notificationRunId) {
        if (notificationRunId == null) return@LaunchedEffect
        val videoRunId = videoViewModel.uiState.value.runState.runId
        val readingRunId = readingViewModel.uiState.value.runState.runId
        val destination =
            when (notificationRunId) {
                videoRunId -> AnkiMinerDestination.VIDEO
                readingRunId -> AnkiMinerDestination.READING
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
            sessionDismissed = wizardDismissedForSession || wizardRedirectedToSettings,
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
                wizardRedirectedToSettings = false
                if (setup.wizardSeen != true) setupViewModel.markWizardSeen()
            },
            onCustomizeFields = {
                wizardRerunRequested = false
                wizardRedirectedToSettings = true
                requestedSettingsCategory = SettingsCategory.ANKI
                requestedSettingsItemIndex = 3
                navigateTo(AnkiMinerDestination.SETTINGS)
            },
            onResolveRecovery = {
                wizardRerunRequested = false
                wizardRedirectedToSettings = true
                requestedSettingsCategory = SettingsCategory.ANKI
                requestedSettingsItemIndex = 4
                navigateTo(AnkiMinerDestination.SETTINGS)
            },
        )
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val linkedFailureMessage =
        setup.failure?.message
            ?: setup.ankiRecoveryFailure?.message
            ?: setup.ankiFailure?.message
    val linkedFailureCategory =
        setup.failure?.let { settingsCategoryFor(it.origin) }
            ?: setup.ankiRecoveryFailure?.let { settingsCategoryFor(it.origin) }
            ?: setup.ankiFailure?.let { settingsCategoryFor(it.origin) }
    val linkedFailureItemIndex =
        setup.failure?.let { settingsCardIndexFor(it.origin) }
            ?: setup.ankiRecoveryFailure?.let { settingsCardIndexFor(it.origin) }
            ?: setup.ankiFailure?.let { settingsCardIndexFor(it.origin) }
            ?: 2
    MessageSnackbarEffect(
        message = linkedFailureMessage,
        hostState = snackbarHostState,
        actionLabel = stringResource(R.string.b3_view),
        onAction = {
            requestedSettingsCategory = linkedFailureCategory
            requestedSettingsItemIndex = linkedFailureItemIndex
            navigateTo(AnkiMinerDestination.SETTINGS)
        },
    )

    val activeWorkflowDestination =
        activeWorkflowDestination(
            video = videoWorkflow,
            reading = readingWorkflow,
        )

    AnkiMinerAppShell(
        currentDestination = currentDestination,
        videoWorkflow = videoWorkflow,
        readingWorkflow = readingWorkflow,
        snackbarHostState = snackbarHostState,
        onDestinationSelected = ::navigateTo,
        onNavigateBack = { navController.popBackStack() },
    ) { shellModifier ->
        NavHost(
            navController = navController,
            startDestination = AnkiMinerDestination.VIDEO.route,
            modifier = shellModifier,
            // Left unset, Navigation Compose applies a 700ms cross-fade — by a wide margin the
            // slowest motion in the app, on its most frequent transition.
            enterTransition = { fadeIn(tween(AnkiMinerTokens.Motion.StateMs)) },
            exitTransition = { fadeOut(tween(AnkiMinerTokens.Motion.ExitMs)) },
            popEnterTransition = { fadeIn(tween(AnkiMinerTokens.Motion.StateMs)) },
            popExitTransition = { fadeOut(tween(AnkiMinerTokens.Motion.ExitMs)) },
        ) {
            composable(AnkiMinerDestination.VIDEO.route) {
                if (miningWorkflowVisible(setup.isMiningReady, videoWorkflow)) {
                    VideoMiningRoute(
                        viewModel = videoViewModel,
                        onReturnToActiveRun =
                            activeWorkflowDestination
                                ?.takeIf { it != AnkiMinerDestination.VIDEO }
                                ?.let { destination -> { navigateTo(destination) } },
                        modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
                    )
                } else {
                    MiningReadinessNotice(
                        state = setup,
                        message = stringResource(miningReadinessMessage(setup)),
                        onRequestPermissions = onRequestPermissions,
                        onInstallUniDic = setupViewModel::installUniDic,
                        onInstallAnkiDroid = onInstallAnkiDroid,
                        onOpenAnkiDroid = onOpenAnkiDroid,
                        onCheckAgain = setupViewModel::refresh,
                        onOpenSettings = {
                            navigateTo(AnkiMinerDestination.SETTINGS)
                        },
                    )
                }
            }
            composable(AnkiMinerDestination.READING.route) {
                if (miningWorkflowVisible(setup.isMiningReady, readingWorkflow)) {
                    ReadingMiningRoute(
                        viewModel = readingViewModel,
                        onReturnToActiveRun =
                            activeWorkflowDestination
                                ?.takeIf { it != AnkiMinerDestination.READING }
                                ?.let { destination -> { navigateTo(destination) } },
                        modifier = Modifier.testTag(ReadingMiningTestTags.SCREEN),
                    )
                } else {
                    MiningReadinessNotice(
                        state = setup,
                        message = stringResource(miningReadinessMessage(setup)),
                        onRequestPermissions = onRequestPermissions,
                        onInstallUniDic = setupViewModel::installUniDic,
                        onInstallAnkiDroid = onInstallAnkiDroid,
                        onOpenAnkiDroid = onOpenAnkiDroid,
                        onCheckAgain = setupViewModel::refresh,
                        onOpenSettings = {
                            navigateTo(AnkiMinerDestination.SETTINGS)
                        },
                    )
                }
            }
            composable(AnkiMinerDestination.SETTINGS.route) {
                SettingsRoute(
                    viewModel = settingsViewModel,
                    setupViewModel = setupViewModel,
                    diagnosticsViewModel = diagnosticsViewModel,
                    diagnostics = diagnosticsIdentity,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings,
                    onInstallAnkiDroid = onInstallAnkiDroid,
                    onOpenAnkiDroid = onOpenAnkiDroid,
                    onOpenSpeechSettings = onOpenSpeechSettings,
                    onShareDiagnostics = diagnosticsShareAction::share,
                    onShareDiagnosticsBundle = onShareDiagnosticsBundle,
                    verboseLogging = verboseLogging,
                    onVerboseLoggingChange = onVerboseLoggingChange,
                    onReturnToActiveRun =
                        activeWorkflowDestination?.let { destination ->
                            { navigateTo(destination) }
                        },
                    onAttributions = {
                        navController.navigate(AnkiMinerDestination.ATTRIBUTION.route)
                    },
                    onRunSetupWizard = { wizardRerunRequested = true },
                    onManageKnownWords = {
                        navController.navigate(
                            AnkiMinerDestination.KNOWN_WORDS_MANAGER.route,
                        )
                    },
                    requestedCategory = requestedSettingsCategory,
                    requestedCategoryItemIndex = requestedSettingsItemIndex,
                    onCategoryRequestConsumed = {
                        requestedSettingsCategory = null
                        requestedSettingsItemIndex = 2
                    },
                )
            }
            composable(AnkiMinerDestination.KNOWN_WORDS_MANAGER.route) {
                KnownWordsManagerRoute(setupViewModel)
            }
            composable(AnkiMinerDestination.ATTRIBUTION.route) {
                AttributionScreen(
                    installedDictionaries = setup.dictionaries,
                    onOpenNotices = {
                        navController.navigate(AnkiMinerDestination.NOTICES.route)
                    },
                )
            }
            composable(AnkiMinerDestination.NOTICES.route) {
                NoticesScreen()
            }
        }
    }
}

@Composable
internal fun MiningReadinessNotice(
    state: SetupUiState,
    message: String,
    onRequestPermissions: () -> Unit,
    onInstallUniDic: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onCheckAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(AnkiMinerTokens.Space.content),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.content),
    ) {
        ScreenTitle(stringResource(R.string.mining_not_ready))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group)) {
                Text(message)
                when (state.miningReadinessAction) {
                    MiningReadinessAction.WAIT ->
                        SupportingText(stringResource(R.string.readiness_wait_action))
                    else ->
                        MiningReadinessActions(
                            action = state.miningReadinessAction,
                            onRequestPermissions = onRequestPermissions,
                            onInstallUniDic = onInstallUniDic,
                            onInstallAnkiDroid = onInstallAnkiDroid,
                            onOpenAnkiDroid = onOpenAnkiDroid,
                            onCheckAgain = onCheckAgain,
                            onOpenSettings = onOpenSettings,
                        )
                }
            }
        }
    }
}

@Composable
private fun MiningReadinessActions(
    action: MiningReadinessAction,
    onRequestPermissions: () -> Unit,
    onInstallUniDic: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onCheckAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val actionSpec =
        when (action) {
            MiningReadinessAction.INSTALL_UNIDIC ->
                ReadinessActionSpec(
                    R.string.readiness_install_unidic,
                    onInstallUniDic,
                    opensSettings = false,
                )
            MiningReadinessAction.INSTALL_ANKIDROID ->
                ReadinessActionSpec(
                    R.string.install_or_update_ankidroid,
                    onInstallAnkiDroid,
                    opensSettings = false,
                )
            MiningReadinessAction.OPEN_ANKIDROID ->
                ReadinessActionSpec(
                    R.string.open_ankidroid,
                    onOpenAnkiDroid,
                    opensSettings = false,
                )
            MiningReadinessAction.CONNECT_ANKIDROID ->
                ReadinessActionSpec(
                    R.string.readiness_connect_ankidroid,
                    onRequestPermissions,
                    opensSettings = false,
                )
            MiningReadinessAction.CHOOSE_NOTE_TYPE ->
                ReadinessActionSpec(
                    R.string.readiness_choose_note_type,
                    onOpenSettings,
                    opensSettings = true,
                )
            MiningReadinessAction.RESOLVE_RECOVERY ->
                ReadinessActionSpec(
                    R.string.readiness_resolve_recovery,
                    onOpenSettings,
                    opensSettings = true,
                )
            MiningReadinessAction.CHECK_AGAIN ->
                ReadinessActionSpec(
                    R.string.readiness_check_again,
                    onCheckAgain,
                    opensSettings = false,
                )
            MiningReadinessAction.WAIT -> return
        }
    FilledTonalButton(
        onClick = actionSpec.onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = tonalActionButtonColors(),
    ) {
        Text(stringResource(actionSpec.label))
    }
    if (!actionSpec.opensSettings) {
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedActionButtonColors(),
            border = actionBorder(enabled = true),
        ) {
            Text(stringResource(R.string.open_settings))
        }
    }
}

private data class ReadinessActionSpec(
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
    val opensSettings: Boolean,
)

@StringRes
private fun miningReadinessMessage(state: SetupUiState): Int =
    when {
        state.busy -> R.string.readiness_resource_operation
        !state.pythonReady -> R.string.readiness_python_pending
        state.resourceStartup != com.ankiminer.android.data.resources.ResourceStartupReadiness.READY ->
            R.string.readiness_resources_pending
        !state.uniDicInstalled -> R.string.readiness_unidic_required
        !state.ankiReady -> R.string.readiness_anki_required
        !state.targetReady -> R.string.readiness_model_required
        !state.recoveryReady -> R.string.readiness_recovery_required
        else -> R.string.readiness_unknown
    }
