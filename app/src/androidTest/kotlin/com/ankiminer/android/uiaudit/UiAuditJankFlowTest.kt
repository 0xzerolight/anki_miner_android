package com.ankiminer.android.uiaudit

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.player.FakeCurationPreviewPlayer
import com.ankiminer.android.ui.navigation.AnkiMinerAppShell
import com.ankiminer.android.ui.navigation.AnkiMinerDestination
import com.ankiminer.android.ui.reading.ReadingMiningScreen
import com.ankiminer.android.ui.reading.ReadingMiningTestTags
import com.ankiminer.android.ui.settings.SettingsCardIndexRecorder
import com.ankiminer.android.ui.settings.SettingsCategory
import com.ankiminer.android.ui.settings.rememberSettingsCategoryListStates
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.video.VideoMiningScreen
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.ui.wizard.OnboardingWizardCallbacks
import com.ankiminer.android.ui.wizard.OnboardingWizardContent
import com.ankiminer.android.ui.wizard.WizardStep
import com.ankiminer.android.vm.NavigationWorkflowState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UiAuditJankFlowTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)

    @Before
    fun requireUiAuditArgument() {
        assumeTrue(
            "UI audit capture is opt-in; pass -e uiAudit true",
            InstrumentationRegistry.getArguments().getString(UI_AUDIT_ARGUMENT) == "true",
        )
    }

    @Test
    fun curationList200CandidatesScrollsBottomThenTop() {
        runRealTimeFlow("curation-200") { onComplete ->
            val listState = rememberLazyListState()
            AuditShell(
                destination = AnkiMinerDestination.VIDEO,
                videoWorkflow = NavigationWorkflowState.REVIEW,
            ) {
                VideoMiningScreen(
                    state =
                        videoAuditState(
                            auditState = MiningAuditState.CURATION,
                            candidateCount = 200,
                        ),
                    onPickVideo = {},
                    onPickSubtitle = {},
                    onClearVideo = {},
                    onClearSubtitle = {},
                    onDismissDocumentError = {},
                    onDismissCommandError = {},
                    onStart = {},
                    onFocusCandidate = {},
                    onSetCandidateSelected = { _, _ -> },
                    onMarkCandidateKnown = { _, _ -> },
                    onSetSelectionForVisible = { _, _ -> },
                    onSetSelectionForPage = {},
                    onReconcileFocus = { _, _ -> },
                    onSelectSentence = { _, _ -> },
                    onConfirmCuration = {},
                    onCancel = {},
                    onRetry = {},
                    onReset = {},
                    playerFactory = { FakeCurationPreviewPlayer() },
                    modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
                    listState = listState,
                )
            }
            MarkedScrollFlow("curation-200", listState, onComplete)
        }
    }

    @Test
    fun settingsFullScrollsDownThenUp() {
        runRealTimeFlow("settings-full") { onComplete ->
            var selectedCategory by remember { mutableStateOf(SettingsCategory.ANKI) }
            val listStates = rememberSettingsCategoryListStates()
            val recorder = remember { SettingsCardIndexRecorder() }
            AuditShell(AnkiMinerDestination.SETTINGS) {
                UiAuditFullSettingsFixture(
                    selectedCategory = selectedCategory,
                    listStates = listStates,
                    recorder = recorder,
                )
            }
            FullSettingsScrollFlow(
                listStates = listStates,
                recorder = recorder,
                onSelectedCategory = { selectedCategory = it },
                onComplete = onComplete,
            )
        }
    }

    @Test
    fun readingResultsLongListScrollsDownThenUp() {
        runRealTimeFlow("reading-results") { onComplete ->
            val listState = rememberLazyListState()
            AuditShell(AnkiMinerDestination.READING) {
                ReadingMiningScreen(
                    state =
                        readingAuditState(
                            auditState = MiningAuditState.RESULTS,
                            longResult = true,
                        ),
                    onPickSource = {},
                    onPickArchive = {},
                    onClearSource = {},
                    onClearArchive = {},
                    onSeriesNameChanged = {},
                    onDismissDocumentError = {},
                    onDismissCommandError = {},
                    onStart = {},
                    onFocusCandidate = {},
                    onSetCandidateSelected = { _, _ -> },
                    onMarkCandidateKnown = { _, _ -> },
                    onSetSelectionForVisible = { _, _ -> },
                    onSetSelectionForPage = {},
                    onReconcileFocus = { _, _ -> },
                    onSelectSentence = { _, _ -> },
                    onConfirmCuration = {},
                    onCancel = {},
                    onRetry = {},
                    onReset = {},
                    onSourceModeChanged = {},
                    onPastedTextChanged = {},
                    onClearPastedText = {},
                    modifier = Modifier.testTag(ReadingMiningTestTags.SCREEN),
                    listState = listState,
                )
            }
            MarkedScrollFlow("reading-results", listState, onComplete)
        }
    }

    @Test
    fun wizardStepsThroughEveryScreen() {
        runRealTimeFlow("wizard-step-through") { onComplete ->
            var step by remember { mutableStateOf(WizardStep.WELCOME) }
            val scrollState = rememberScrollState()
            OnboardingWizardContent(
                state = setupAuditState(),
                step = step,
                callbacks =
                    OnboardingWizardCallbacks(
                        onStep = { step = it },
                    ),
                scrollState = scrollState,
            )
            LaunchedEffect(Unit) {
                withFrameNanos { }
                Log.i(LOG_TAG, "START wizard-step-through")
                var failure: Throwable? = null
                try {
                    WizardStep.entries.drop(1).forEach { next ->
                        scrollState.auditWizardScroll(DOWN_DISTANCE)
                        step = next
                        withFrameNanos { }
                        scrollState.auditWizardScroll(-DOWN_DISTANCE)
                    }
                } catch (caught: Throwable) {
                    failure = caught
                } finally {
                    Log.i(LOG_TAG, "END wizard-step-through")
                    onComplete(failure)
                }
            }
        }
    }

    private fun runRealTimeFlow(
        name: String,
        content: @Composable (onComplete: (Throwable?) -> Unit) -> Unit,
    ) {
        val completed = CountDownLatch(1)
        val flowFailure = AtomicReference<Throwable?>()
        activityRule.scenario.onActivity { activity ->
            // No Compose test rule: Activity-owned recomposer uses real Choreographer frames, so
            // external gfxinfo sampling observes the full 5–8 second flow.
            activity.setContent {
                AnkiMinerTheme {
                    content { failure ->
                        flowFailure.set(failure)
                        completed.countDown()
                    }
                }
            }
        }
        assertTrue(
            "$name did not finish within ${FLOW_TIMEOUT_SECONDS}s",
            completed.await(FLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        flowFailure.get()?.let { failure ->
            throw AssertionError("$name failed", failure)
        }
    }

    @Composable
    private fun AuditShell(
        destination: AnkiMinerDestination,
        videoWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
        readingWorkflow: NavigationWorkflowState = NavigationWorkflowState.IDLE,
        content: @Composable () -> Unit,
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        AnkiMinerAppShell(
            currentDestination = destination,
            videoWorkflow = videoWorkflow,
            readingWorkflow = readingWorkflow,
            snackbarHostState = snackbarHostState,
            onDestinationSelected = {},
        ) { shellModifier ->
            androidx.compose.foundation.layout.Box(
                modifier = shellModifier,
            ) {
                content()
            }
        }
    }

    @Composable
    private fun MarkedScrollFlow(
        name: String,
        scrollState: ScrollableState,
        onComplete: (Throwable?) -> Unit,
    ) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            Log.i(LOG_TAG, "START $name")
            var failure: Throwable? = null
            try {
                scrollState.auditScroll(DOWN_DISTANCE, HALF_FLOW_MILLIS)
                scrollState.auditScroll(-DOWN_DISTANCE, HALF_FLOW_MILLIS)
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                Log.i(LOG_TAG, "END $name")
                onComplete(failure)
            }
        }
    }

    @Composable
    private fun FullSettingsScrollFlow(
        listStates: Map<SettingsCategory, LazyListState>,
        recorder: SettingsCardIndexRecorder,
        onSelectedCategory: (SettingsCategory) -> Unit,
        onComplete: (Throwable?) -> Unit,
    ) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            Log.i(LOG_TAG, "START settings-full")
            var failure: Throwable? = null
            try {
                SettingsCategory.entries.forEach { category ->
                    onSelectedCategory(category)
                    recorder.awaitCards(
                        category = category,
                        expectedKeys = FULL_SETTINGS_CARD_KEYS.getValue(category),
                    )
                    withFrameNanos { }
                    listStates.getValue(category).apply {
                        auditScroll(DOWN_DISTANCE, SETTINGS_CATEGORY_HALF_FLOW_MILLIS)
                        auditScroll(-DOWN_DISTANCE, SETTINGS_CATEGORY_HALF_FLOW_MILLIS)
                    }
                }
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                Log.i(LOG_TAG, "END settings-full")
                onComplete(failure)
            }
        }
    }

    private suspend fun SettingsCardIndexRecorder.awaitCards(
        category: SettingsCategory,
        expectedKeys: List<String>,
    ) {
        withTimeout(SETTINGS_CATEGORY_COMPOSITION_TIMEOUT_MILLIS) {
            snapshotFlow {
                expectedKeys.all { key -> indexOf(category, key) != null }
            }.first { allComposed -> allComposed }
        }
    }

    private suspend fun ScrollableState.auditWizardScroll(distance: Float) {
        val startedAtMillis = SystemClock.uptimeMillis()
        try {
            auditScroll(distance, WIZARD_HALF_STEP_MILLIS)
        } catch (_: CancellationException) {
            // MutatorMutex reports a competing step-reset scroll as cancellation. Preserve true
            // coroutine cancellation, but let this real-time audit continue after that segment.
            currentCoroutineContext().ensureActive()
            val elapsedMillis = SystemClock.uptimeMillis() - startedAtMillis
            val remainingMillis = WIZARD_HALF_STEP_MILLIS.toLong() - elapsedMillis
            if (remainingMillis > 0) delay(remainingMillis)
        }
    }

    private suspend fun ScrollableState.auditScroll(
        distance: Float,
        durationMillis: Int,
    ) {
        animateScrollBy(
            value = distance,
            animationSpec =
                tween(
                    durationMillis = durationMillis,
                    easing = LinearEasing,
                ),
        )
    }

    private companion object {
        const val UI_AUDIT_ARGUMENT = "uiAudit"
        const val LOG_TAG = "UiAuditFlow"
        const val DOWN_DISTANCE = 1_000_000f
        const val HALF_FLOW_MILLIS = 2_800
        const val SETTINGS_CATEGORY_HALF_FLOW_MILLIS = 350
        const val SETTINGS_CATEGORY_COMPOSITION_TIMEOUT_MILLIS = 2_000L
        const val WIZARD_HALF_STEP_MILLIS = 450
        const val FLOW_TIMEOUT_SECONDS = 15L

        val FULL_SETTINGS_CARD_KEYS =
            mapOf(
                SettingsCategory.ANKI to listOf("anki-deck-options", "anki-target"),
                SettingsCategory.MEDIA to listOf("media-options", "subtitle-text"),
                SettingsCategory.DICTIONARIES to
                    listOf("dictionary-sources", "pitch-sources"),
                SettingsCategory.AUDIO to listOf("audio-sources"),
                SettingsCategory.FREQUENCY to listOf("frequency-sources"),
                SettingsCategory.FILTERING to listOf("filtering-options", "word-lists"),
                SettingsCategory.UI to listOf("ui-options"),
                SettingsCategory.DIAGNOSTICS to
                    listOf("diagnostic-runtime", "attributions"),
            )
    }
}
