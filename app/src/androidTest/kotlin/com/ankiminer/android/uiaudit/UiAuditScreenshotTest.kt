package com.ankiminer.android.uiaudit

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.player.FakeCurationPreviewPlayer
import com.ankiminer.android.player.PreviewFailure
import com.ankiminer.android.ui.attribution.AttributionScreen
import com.ankiminer.android.ui.attribution.NoticesScreen
import com.ankiminer.android.ui.navigation.AnkiMinerAppShell
import com.ankiminer.android.ui.navigation.AnkiMinerDestination
import com.ankiminer.android.ui.navigation.MiningReadinessNotice
import com.ankiminer.android.ui.reading.ReadingMiningScreen
import com.ankiminer.android.ui.reading.ReadingMiningTestTags
import com.ankiminer.android.ui.settings.MessageSnackbarEffect
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.theme.ThemePalette
import com.ankiminer.android.ui.theme.ThemePalettes
import com.ankiminer.android.ui.video.VideoMiningScreen
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.ui.wizard.OnboardingWizardCallbacks
import com.ankiminer.android.ui.wizard.OnboardingWizardContent
import com.ankiminer.android.ui.wizard.WizardStep
import com.ankiminer.android.vm.NavigationWorkflowState
import java.io.File
import java.io.FileOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UiAuditScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun requireUiAuditArgument() {
        assumeTrue(
            "UI audit capture is opt-in; pass -e uiAudit true",
            InstrumentationRegistry.getArguments().getString(UI_AUDIT_ARGUMENT) == "true",
        )
    }

    @Test
    fun captureVideoStatesAcrossThemeAndFontScaleMatrix() {
        captureMatrix(
            MiningAuditState.entries.map { state ->
                CaptureTarget(
                    screen = "video",
                    state = state.fileName,
                    destination = AnkiMinerDestination.VIDEO,
                ) {
                    AuditVideoMiningScreen(state = state)
                }
            } +
                CaptureTarget(
                    screen = "video",
                    state = "curation-preview-failure",
                    destination = AnkiMinerDestination.VIDEO,
                ) {
                    AuditVideoMiningScreen(
                        state = MiningAuditState.CURATION,
                        playerFactory = {
                            FakeCurationPreviewPlayer().apply {
                                failureOnBind =
                                    PreviewFailure.VideoTrackUnsupported("av01.0.05M.08")
                            }
                        },
                    )
                },
        )
    }

    @Composable
    private fun AuditVideoMiningScreen(
        state: MiningAuditState,
        playerFactory: (android.content.Context) -> FakeCurationPreviewPlayer = {
            FakeCurationPreviewPlayer()
        },
    ) {
        VideoMiningScreen(
            state = videoAuditState(state),
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
            playerFactory = playerFactory,
            modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
        )
    }

    @Test
    fun captureReadingStatesAcrossThemeAndFontScaleMatrix() {
        captureMatrix(
            MiningAuditState.entries.map { state ->
                CaptureTarget(
                    screen = "reading",
                    state = state.fileName,
                    destination = AnkiMinerDestination.READING,
                ) {
                    ReadingMiningScreen(
                        state = readingAuditState(state),
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
                    )
                }
            },
        )
    }

    @Test
    fun captureSettingsStatesAcrossThemeAndFontScaleMatrix() {
        val snackbarMessage = "Dictionary archive contains an oversized file"
        val snackbarAction =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .getString(R.string.b3_view)
        captureMatrix(
            listOf(
                SettingsAuditState.TOP,
                SettingsAuditState.ANKI,
                SettingsAuditState.RESOURCES,
                SettingsAuditState.ERROR_SNACKBAR,
                SettingsAuditState.MEDIA,
            ).map { state ->
                CaptureTarget(
                    screen = "settings",
                    state = state.fileName,
                    destination = AnkiMinerDestination.SETTINGS,
                    snackbarMessage =
                        snackbarMessage.takeIf { state == SettingsAuditState.ERROR_SNACKBAR },
                    waitForText =
                        snackbarAction.takeIf { state == SettingsAuditState.ERROR_SNACKBAR },
                ) {
                    UiAuditSettingsFixture(focus = state)
                }
            },
        )
    }

    @Test
    fun captureReadinessActionsAcrossThemeAndFontScaleMatrix() {
        captureMatrix(
            listOf(
                CaptureTarget(
                    screen = "readiness",
                    state = "unidic-missing",
                    destination = AnkiMinerDestination.VIDEO,
                ) {
                    MiningReadinessNotice(
                        state = setupAuditState().copy(uniDicInstalled = false),
                        message = stringResource(R.string.readiness_unidic_required),
                        onRequestPermissions = {},
                        onInstallUniDic = {},
                        onInstallAnkiDroid = {},
                        onOpenAnkiDroid = {},
                        onCheckAgain = {},
                        onOpenSettings = {},
                        onImportDictionary = {},
                    )
                },
                CaptureTarget(
                    screen = "readiness",
                    state = "note-type-missing",
                    destination = AnkiMinerDestination.VIDEO,
                ) {
                    MiningReadinessNotice(
                        state =
                            setupAuditState().copy(
                                noteTypeStatus = NoteTypeSetupStatus.NotSelected,
                            ),
                        message = stringResource(R.string.readiness_model_required),
                        onRequestPermissions = {},
                        onInstallUniDic = {},
                        onInstallAnkiDroid = {},
                        onOpenAnkiDroid = {},
                        onCheckAgain = {},
                        onOpenSettings = {},
                        onImportDictionary = {},
                    )
                },
            ),
        )
    }

    @Test
    fun captureEveryWizardStepAcrossThemeAndFontScaleMatrix() {
        captureMatrix(
            WizardStep.entries.map { step ->
                CaptureTarget(
                    screen = "wizard",
                    state = step.name.lowercase().replace('_', '-'),
                    destination = null,
                ) {
                    OnboardingWizardContent(
                        state = setupAuditState(),
                        step = step,
                        callbacks = OnboardingWizardCallbacks(),
                    )
                }
            },
        )
    }

    @Test
    fun captureAttributionAndNoticesAcrossThemeAndFontScaleMatrix() {
        captureMatrix(
            listOf(
                CaptureTarget(
                    screen = "attribution",
                    state = "populated",
                    destination = AnkiMinerDestination.ATTRIBUTION,
                ) {
                    AttributionScreen(
                        installedDictionaries = attributionAuditDictionaries(),
                        onOpenNotices = {},
                    )
                },
                CaptureTarget(
                    screen = "notices",
                    state = "bundled",
                    destination = AnkiMinerDestination.NOTICES,
                    waitForLazyListKey = "block:NOTICE.md:0",
                    lazyListTag = NOTICES_LIST_TAG,
                ) {
                    NoticesScreen(
                        modifier = Modifier.testTag(NOTICES_LIST_TAG),
                    )
                },
            ),
        )
    }

    private fun captureMatrix(targets: List<CaptureTarget>) {
        var request by
            mutableStateOf(
                CaptureRequest(
                    target = targets.first(),
                    palette = ThemePalettes.Light,
                    fontScale = FONT_SCALES.first(),
                ),
            )
        composeRule.setContent {
            CaptureFrame(request)
        }

        targets.forEach { target ->
            THEMES.forEach { palette ->
                FONT_SCALES.forEach { fontScale ->
                    composeRule.runOnIdle {
                        request =
                            CaptureRequest(
                                target = target,
                                palette = palette,
                                fontScale = fontScale,
                            )
                    }
                    composeRule.waitForIdle()
                    target.waitForText?.let { expected ->
                        composeRule.waitUntil(timeoutMillis = 10_000) {
                            composeRule
                                .onAllNodesWithText(expected, substring = true)
                                .fetchSemanticsNodes()
                                .isNotEmpty()
                        }
                    }
                    target.waitForLazyListKey?.let { expectedKey ->
                        val listTag =
                            checkNotNull(target.lazyListTag) {
                                "A lazy-list wait requires a container tag"
                            }
                        composeRule.waitUntil(timeoutMillis = 10_000) {
                            try {
                                composeRule
                                    .onNodeWithTag(listTag)
                                    .performScrollToKey(expectedKey)
                                true
                            } catch (_: AssertionError) {
                                false
                            } catch (_: IllegalArgumentException) {
                                // Async-parsed lazy content (e.g. notice blocks) may not have
                                // produced the key yet.
                                false
                            }
                        }
                        composeRule.onNodeWithTag(listTag).performScrollToIndex(0)
                        composeRule.waitForIdle()
                    }
                    writeCapture(request)
                }
            }
        }
    }

    private fun writeCapture(request: CaptureRequest) {
        val image = composeRule.onRoot(useUnmergedTree = true).captureToImage()
        val file = File(outputDirectory(), request.fileName)
        FileOutputStream(file).use { output ->
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "PNG encoder rejected ${file.absolutePath}"
            }
        }
    }

    private fun outputDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalRoot =
            checkNotNull(context.getExternalFilesDir(null)) {
                "External files directory is unavailable"
            }
        return File(externalRoot, "ui-audit").also { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Could not create ${directory.absolutePath}"
            }
        }
    }

    @Composable
    private fun CaptureFrame(request: CaptureRequest) {
        val baseDensity = LocalDensity.current.density
        key(request.target.screen, request.target.state, request.palette.key, request.fontScale) {
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity, request.fontScale),
            ) {
                AnkiMinerTheme(palette = request.palette) {
                    val target = request.target
                    if (target.destination == null) {
                        target.content()
                    } else {
                        val snackbarHostState = remember { SnackbarHostState() }
                        MessageSnackbarEffect(
                            message = target.snackbarMessage,
                            hostState = snackbarHostState,
                            actionLabel = stringResource(R.string.b3_view),
                        )
                        AnkiMinerAppShell(
                            currentDestination = target.destination,
                            videoWorkflow = target.videoWorkflow,
                            readingWorkflow = target.readingWorkflow,
                            snackbarHostState = snackbarHostState,
                            onDestinationSelected = {},
                        ) { shellModifier ->
                            Box(shellModifier.fillMaxSize()) {
                                target.content()
                            }
                        }
                    }
                }
            }
        }
    }

    private data class CaptureRequest(
        val target: CaptureTarget,
        val palette: ThemePalette,
        val fontScale: Float,
    ) {
        val fileName: String
            get() {
                val theme = palette.key
                val scale =
                    when (fontScale) {
                        1.0f -> 100
                        1.3f -> 130
                        2.0f -> 200
                        else -> error("Unsupported UI audit font scale: $fontScale")
                    }
                return "${target.screen}__${target.state}__${theme}__fs$scale.png"
            }
    }

    private data class CaptureTarget(
        val screen: String,
        val state: String,
        val destination: AnkiMinerDestination?,
        val snackbarMessage: String? = null,
        val waitForText: String? = null,
        val waitForLazyListKey: String? = null,
        val lazyListTag: String? = null,
        val content: @Composable () -> Unit,
    ) {
        val videoWorkflow: NavigationWorkflowState
            get() =
                if (screen == "video") {
                    when (state) {
                        MiningAuditState.CURATION.fileName -> NavigationWorkflowState.REVIEW
                        MiningAuditState.RUNNING.fileName -> NavigationWorkflowState.RUNNING
                        else -> NavigationWorkflowState.IDLE
                    }
                } else {
                    NavigationWorkflowState.IDLE
                }

        val readingWorkflow: NavigationWorkflowState
            get() =
                if (screen == "reading") {
                    when (state) {
                        MiningAuditState.CURATION.fileName -> NavigationWorkflowState.REVIEW
                        MiningAuditState.RUNNING.fileName -> NavigationWorkflowState.RUNNING
                        else -> NavigationWorkflowState.IDLE
                    }
                } else {
                    NavigationWorkflowState.IDLE
                }
    }

    private companion object {
        const val UI_AUDIT_ARGUMENT = "uiAudit"
        const val NOTICES_LIST_TAG = "ui_audit_notices_list"
        // The capture matrix stays at two palettes on purpose. Adding all 29 would multiply every
        // screen-and-state capture by 29 for no extra layout coverage, since a palette changes colour
        // but never measurement.
        private val THEMES = listOf(ThemePalettes.Light, ThemePalettes.Dark)
        val FONT_SCALES = listOf(1.0f, 1.3f, 2.0f)
    }
}
