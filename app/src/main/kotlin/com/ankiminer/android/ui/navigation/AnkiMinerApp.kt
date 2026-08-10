package com.ankiminer.android.ui.navigation

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ankiminer.android.R
import com.ankiminer.android.data.update.UpdateCheckUiState
import com.ankiminer.android.diagnostics.TesterDiagnosticsBuilder
import com.ankiminer.android.diagnostics.currentTesterBuildIdentity
import com.ankiminer.android.mining.MiningRunKind
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.ui.attribution.AttributionScreen
import com.ankiminer.android.ui.attribution.NoticesScreen
import com.ankiminer.android.ui.audio.AudioMiningRoute
import com.ankiminer.android.ui.mining.TimingPreviewState
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
import com.ankiminer.android.ui.video.TimingPreviewOverlay
import com.ankiminer.android.ui.video.VideoMiningRoute
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.ui.wizard.OnboardingWizard
import com.ankiminer.android.ui.wizard.wizardVisible
import com.ankiminer.android.vm.DiagnosticsViewModel
import com.ankiminer.android.vm.MediaMiningViewModel
import com.ankiminer.android.vm.MiningReadinessAction
import com.ankiminer.android.vm.NavigationWorkflowState
import com.ankiminer.android.vm.ReadingMiningViewModel
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

internal const val APP_TOP_BAR_TEST_TAG = "app-top-bar"

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
    AUDIO(
        "audio",
        R.string.nav_audio,
        R.string.nav_audio,
        R.drawable.ic_nav_audio,
        R.string.nav_audio_description,
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
    hasRetainedRun: Boolean = false,
): Boolean = setupReady || workflow != NavigationWorkflowState.IDLE || hasRetainedRun

internal fun compactNavigation(
    widthDp: Int,
    fontScale: Float,
): Boolean = widthDp < 360 && fontScale >= 1.3f

/**
 * Compact bar height for ordinary font scales; null keeps the stock M3 80dp bar so large
 * text is never clipped. The value must still leave every item a >=48dp touch target.
 */
internal fun compactBottomBarHeight(fontScale: Float): Dp? = 64.dp.takeIf { fontScale < 1.3f }

internal fun activeWorkflowDestination(
    video: NavigationWorkflowState,
    audio: NavigationWorkflowState,
    reading: NavigationWorkflowState,
): AnkiMinerDestination? =
    when {
        video == NavigationWorkflowState.REVIEW -> AnkiMinerDestination.VIDEO
        audio == NavigationWorkflowState.REVIEW -> AnkiMinerDestination.AUDIO
        reading == NavigationWorkflowState.REVIEW -> AnkiMinerDestination.READING
        video == NavigationWorkflowState.RUNNING -> AnkiMinerDestination.VIDEO
        audio == NavigationWorkflowState.RUNNING -> AnkiMinerDestination.AUDIO
        reading == NavigationWorkflowState.RUNNING -> AnkiMinerDestination.READING
        else -> null
    }

internal fun activeTimingPreviewOwner(
    video: TimingPreviewState?,
    audio: TimingPreviewState?,
): AnkiMinerDestination? =
    when {
        video != null -> AnkiMinerDestination.VIDEO
        audio != null -> AnkiMinerDestination.AUDIO
        else -> null
    }

internal fun notificationRunDestination(
    notificationRunId: String,
    video: MiningRunState,
    audio: MiningRunState,
    reading: MiningRunState,
): AnkiMinerDestination? {
    val foregroundKind = MiningRunKind.fromForegroundRunId(notificationRunId)
    return when {
        notificationRunId == video.runId -> AnkiMinerDestination.VIDEO
        notificationRunId == audio.runId -> AnkiMinerDestination.AUDIO
        notificationRunId == reading.runId -> AnkiMinerDestination.READING
        foregroundKind == MiningRunKind.VIDEO && video.isActive() ->
            AnkiMinerDestination.VIDEO
        foregroundKind == MiningRunKind.AUDIO && audio.isActive() ->
            AnkiMinerDestination.AUDIO
        foregroundKind == MiningRunKind.READING && reading.isActive() ->
            AnkiMinerDestination.READING
        else -> null
    }
}

/**
 * Makes the shell inert while [AnkiMinerAppShell]'s overlay owns the window.
 *
 * The overlay is a plain sibling that covers the shell visually and nothing more, so each input
 * route has to be closed on its own terms.
 *
 * `clearAndSetSemantics` takes the subtree out of the accessibility tree. Accessibility traversal
 * and accessibility actions never touch the pointer pipeline, so covering the screen does not stop
 * them; removing the nodes does, because they then get no virtual view id to traverse to or act on.
 *
 * `onEnter { cancelFocusChange() }` is what stops focus search, and it needs the `focusGroup()`
 * below it as the boundary to fire at. `canFocus = false` cannot do this job on its own, in either
 * arrangement. With the group: `focusGroup()` contributes its own focus target, and a focus target
 * resolves its properties by walking ancestors only as far as the *first* focus target above it, so
 * a `canFocus = false` sitting above the group never reaches the `NavigationBarItem`s inside it.
 * Without the group: a deactivated node is skipped as a focus candidate but is still traversed
 * *through* to its children — precisely what makes focus groups work at all — and the propagation
 * would in any case stop at the first group inside the content, which every scrollable contributes.
 * `canFocus = false` is kept only to deactivate the shell root itself.
 *
 * `onExit` is deliberately left at its default. Refusing exit would trap focus that was already
 * inside the shell when the overlay appeared, locking a hardware-keyboard user out of the overlay
 * entirely — strictly worse than the leak being fixed. The wizard pulls focus to its heading with
 * an explicit `FocusRequester.requestFocus`, a direct grant rather than a focus search and so
 * unaffected by `onEnter`; `onEnter` then keeps search from coming back in.
 *
 * The pointer pass must be `Initial`. Compose hit-tests children before their ancestors, so an
 * ancestor consuming on the default `Main` pass reacts after a `NavigationBarItem` has already
 * handled the tap. Consuming while the event tunnels down means `clickable`'s
 * `awaitFirstDown(requireUnconsumed = true)` declines it.
 */
private fun Modifier.inertBehindOverlay(): Modifier =
    clearAndSetSemantics { }
        .focusProperties {
            canFocus = false
            onEnter = { cancelFocusChange() }
        }
        .focusGroup()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }

/**
 * Production app chrome shared with instrumented UI capture. Legal routes intentionally hide the
 * bottom navigation, matching their production presentation.
 *
 * A non-null [overlay] owns the window: it renders above the shell and the shell behind it goes
 * inert. Passing null renders no overlay, which is why this is a nullable lambda rather than a
 * lambda plus a flag — the two can never disagree.
 */
@Composable
internal fun AnkiMinerAppShell(
    currentDestination: AnkiMinerDestination?,
    videoWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
    audioWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
    readingWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
    snackbarHostState: SnackbarHostState,
    onDestinationSelected: (AnkiMinerDestination) -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    overlay: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val compact =
            compactNavigation(
                widthDp = maxWidth.value.toInt(),
                fontScale = LocalDensity.current.fontScale,
            )
        Scaffold(
            modifier = if (overlay == null) Modifier else Modifier.inertBehindOverlay(),
            topBar = {
                // Tab destinations carry no bar: its title only repeated the highlighted tab,
                // and every mining phase keeps its own focused heading as the TalkBack anchor.
                currentDestination
                    ?.takeUnless { it.showsBottomBar }
                    ?.let { destination ->
                        AppChrome(
                            title = stringResource(destination.appBarTitle),
                            modifier = Modifier.testTag(APP_TOP_BAR_TEST_TAG),
                            onNavigateBack = onNavigateBack,
                        )
                    }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (currentDestination?.showsBottomBar == true) {
                    val compactHeight = compactBottomBarHeight(LocalDensity.current.fontScale)
                    val barModifier =
                        if (compactHeight == null) {
                            Modifier
                        } else {
                            // The bar consumes the gesture/nav-bar inset internally, so the
                            // fixed height must include it or items get squeezed.
                            val bottomInset =
                                WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                            Modifier.height(compactHeight + bottomInset)
                        }
                    NavigationBar(modifier = barModifier) {
                        listOf(
                            AnkiMinerDestination.VIDEO,
                            AnkiMinerDestination.AUDIO,
                            AnkiMinerDestination.READING,
                            AnkiMinerDestination.SETTINGS,
                        ).forEach { destination ->
                            val workflow =
                                when (destination) {
                                    AnkiMinerDestination.VIDEO -> videoWorkflow
                                    AnkiMinerDestination.AUDIO -> audioWorkflow
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
        overlay?.invoke()
    }
}

@Composable
internal fun AnkiMinerApp(
    videoViewModel: MediaMiningViewModel,
    audioViewModel: MediaMiningViewModel,
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
    onShareDiagnosticsBundle: (uri: String, fileName: String) -> Boolean,
    verboseLogging: Boolean,
    onVerboseLoggingChange: (Boolean) -> Unit,
    updateCheck: UpdateCheckUiState,
    onUpdateCheckEnabledChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onSkipUpdate: () -> Unit,
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
    val audioWorkflow by
        audioViewModel.navigationWorkflowState.collectAsStateWithLifecycle()
    val readingWorkflow by
        readingViewModel.navigationWorkflowState.collectAsStateWithLifecycle()
    val videoRunState by videoViewModel.uiState.collectAsStateWithLifecycle()
    val audioRunState by audioViewModel.uiState.collectAsStateWithLifecycle()
    val videoTimingPreview by videoViewModel.timingPreviewState.collectAsStateWithLifecycle()
    val audioTimingPreview by audioViewModel.timingPreviewState.collectAsStateWithLifecycle()
    val readingRunState by readingViewModel.uiState.collectAsStateWithLifecycle()
    val buildIdentity = remember { currentTesterBuildIdentity() }
    val diagnosticsIdentity =
        remember(buildIdentity) {
            TesterDiagnosticsBuilder.identity(buildIdentity)
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
        val videoRun = videoViewModel.uiState.value.runState
        val audioRun = audioViewModel.uiState.value.runState
        val readingRun = readingViewModel.uiState.value.runState
        val destination =
            notificationRunDestination(
                notificationRunId = notificationRunId,
                video = videoRun,
                audio = audioRun,
                reading = readingRun,
            )
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

    val wizardIsVisible =
        wizardVisible(
            wizardSeen = setup.wizardSeen,
            rerunRequested = wizardRerunRequested,
            sessionDismissed = wizardDismissedForSession || wizardRedirectedToSettings,
            completion = setup.wizardCompletion,
        )
    if (wizardIsVisible) {
        LaunchedEffect(setupViewModel) { setupViewModel.refresh() }
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
            audio = audioWorkflow,
            reading = readingWorkflow,
        )
    val timingPreviewOwner =
        activeTimingPreviewOwner(
            video = videoTimingPreview,
            audio = audioTimingPreview,
        )

    AnkiMinerAppShell(
        currentDestination = currentDestination,
        videoWorkflow = videoWorkflow,
        audioWorkflow = audioWorkflow,
        readingWorkflow = readingWorkflow,
        snackbarHostState = snackbarHostState,
        onDestinationSelected = ::navigateTo,
        onNavigateBack = { navController.popBackStack() },
        overlay =
            when {
                wizardIsVisible -> {
                    {
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
                    }
                }
                timingPreviewOwner == AnkiMinerDestination.VIDEO -> {
                    {
                        TimingPreviewOverlay(
                            state = requireNotNull(videoTimingPreview),
                            videoUri =
                                Uri.parse(
                                    requireNotNull(videoRunState.video.document).uri,
                                ),
                            onSelectCue = videoViewModel::selectTimingPreviewCue,
                            onNudge = videoViewModel::nudgeTimingPreview,
                            onSetWorking = videoViewModel::setTimingPreviewWorkingOffset,
                            onToggleUnshifted = videoViewModel::toggleTimingPreviewUnshifted,
                            onApply = videoViewModel::applyTimingPreview,
                            onCancel = videoViewModel::closeTimingPreview,
                        )
                    }
                }
                timingPreviewOwner == AnkiMinerDestination.AUDIO -> {
                    {
                        TimingPreviewOverlay(
                            state = requireNotNull(audioTimingPreview),
                            videoUri =
                                Uri.parse(
                                    requireNotNull(audioRunState.video.document).uri,
                                ),
                            onSelectCue = audioViewModel::selectTimingPreviewCue,
                            onNudge = audioViewModel::nudgeTimingPreview,
                            onSetWorking = audioViewModel::setTimingPreviewWorkingOffset,
                            onToggleUnshifted = audioViewModel::toggleTimingPreviewUnshifted,
                            onApply = audioViewModel::applyTimingPreview,
                            onCancel = audioViewModel::closeTimingPreview,
                            audioOnly = true,
                        )
                    }
                }
                else -> null
            },
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
                if (
                    miningWorkflowVisible(
                        setupReady = setup.isMiningReady,
                        workflow = videoWorkflow,
                        hasRetainedRun = videoRunState.runState.isTerminal,
                    )
                ) {
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
            composable(AnkiMinerDestination.AUDIO.route) {
                if (
                    miningWorkflowVisible(
                        setupReady = setup.isMiningReady,
                        workflow = audioWorkflow,
                        hasRetainedRun = audioRunState.runState.isTerminal,
                    )
                ) {
                    AudioMiningRoute(
                        viewModel = audioViewModel,
                        onReturnToActiveRun =
                            activeWorkflowDestination
                                ?.takeIf { it != AnkiMinerDestination.AUDIO }
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
                if (
                    miningWorkflowVisible(
                        setupReady = setup.isMiningReady,
                        workflow = readingWorkflow,
                        hasRetainedRun = readingRunState.runState.isTerminal,
                    )
                ) {
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
                    onShareDiagnosticsBundle = onShareDiagnosticsBundle,
                    verboseLogging = verboseLogging,
                    onVerboseLoggingChange = onVerboseLoggingChange,
                    updateCheck = updateCheck,
                    onUpdateCheckEnabledChange = onUpdateCheckEnabledChange,
                    onCheckForUpdates = onCheckForUpdates,
                    onSkipUpdate = onSkipUpdate,
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

private fun MiningRunState.isActive(): Boolean = this != MiningRunState.Idle && !isTerminal

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
