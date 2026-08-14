package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationSummary
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.resources.InstalledResourceKind
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.theme.ThemePalettes
import com.ankiminer.android.vm.PendingResourceDelete
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nextMovesToFollowingNumericFieldAndDoneClearsFocus() {
        var first by mutableStateOf("")
        var second by mutableStateOf("")
        composeRule.setContent {
            AnkiMinerTheme {
                Column {
                    NumericField(
                        value = first,
                        onChange = { first = it },
                        label = "First value",
                        imeAction = ImeAction.Next,
                    )
                    NumericField(
                        value = second,
                        onChange = { second = it },
                        label = "Final value",
                        imeAction = ImeAction.Done,
                    )
                }
            }
        }

        val firstField = composeRule.onNode(hasText("First value") and hasSetTextAction())
        val finalField = composeRule.onNode(hasText("Final value") and hasSetTextAction())
        firstField.performClick().assertIsFocused()
        firstField.performImeAction()
        finalField.assertIsFocused()
        finalField.performImeAction()
        finalField.assertIsNotFocused()
    }

    @Test
    fun fieldsCarryASupportingLineOnlyWhileInvalid() {
        var error by mutableStateOf<String?>("Complete or clear this number")
        composeRule.setContent {
            AnkiMinerTheme {
                NumericField(
                    value = ".",
                    onChange = {},
                    label = "Audio padding",
                    error = error,
                )
            }
        }

        composeRule.onNodeWithText("Complete or clear this number").assertIsDisplayed()

        // A valid field is one line: no permanent hint underneath it.
        error = null
        composeRule.onNodeWithText("Complete or clear this number").assertDoesNotExist()
    }

    @Test
    fun malformedPastedNumberRemainsVisibleForFieldValidation() {
        var value by mutableStateOf("")
        composeRule.setContent {
            AnkiMinerTheme {
                NumericField(
                    value = value,
                    onChange = { value = it },
                    label = "Workers",
                    integer = true,
                    error =
                        value
                            .takeIf { it.isNotEmpty() && it.toIntOrNull() == null }
                            ?.let { "Enter a whole number" },
                )
            }
        }

        composeRule
            .onNode(hasText("Workers") and hasSetTextAction())
            .performTextReplacement("33 workers")

        composeRule.onNodeWithText("33 workers").assertIsDisplayed()
        composeRule.onNodeWithText("Enter a whole number").assertIsDisplayed()
    }

    @Test
    fun adaptiveChoiceSelectorUsesVerticalRadioRowsBelowCompactBreakpoint() {
        setChoiceSelector(width = 359.dp, fontScale = 1f)

        composeRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).assertCountEquals(3)
        val tops =
            OPTIONS.map { label ->
                composeRule
                    .onNodeWithText(label)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            }
        assertTrue(tops.zipWithNext().all { (first, second) -> second > first })
    }

    @Test
    fun adaptiveChoiceSelectorKeepsWideNormalTextInOneSegmentedRow() {
        setChoiceSelector(width = 480.dp, fontScale = 1f)

        val tops =
            OPTIONS.map { label ->
                composeRule
                    .onNodeWithText(label)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            }
        tops.drop(1).forEach { top -> assertEquals(tops.first(), top, 0.5f) }
    }

    @Test
    fun adaptiveChoiceSelectorStacksAtLargeFontEvenWhenWide() {
        setChoiceSelector(width = 600.dp, fontScale = 1.3f)

        val tops =
            OPTIONS.map { label ->
                composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
            }
        assertTrue(tops.zipWithNext().all { (first, second) -> second > first })
    }

    @Test
    fun pairedResourceActionsStackAndKeepTouchTargetsAtCompactWidth() {
        composeRule.setContent {
            AnkiMinerTheme {
                Box(Modifier.width(359.dp)) {
                    ResourceChainEditor(
                        choices = listOf(ResourceChainSelection("source")),
                        labels = mapOf("source" to "Source"),
                        emptyMessage = "None",
                        onChange = {},
                    )
                }
            }
        }

        val moveUp =
            composeRule
                .onNodeWithText("Up")
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        val moveDown =
            composeRule
                .onNodeWithText("Down")
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(moveDown.top > moveUp.top)
    }

    private fun setChoiceSelector(
        width: Dp,
        fontScale: Float,
    ) {
        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity, fontScale),
            ) {
                AnkiMinerTheme {
                    Box(Modifier.requiredWidth(width)) {
                        AdaptiveChoiceSelector(
                            values = OPTIONS,
                            selected = OPTIONS.first(),
                            label = { it },
                            onSelect = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun resourceDeleteDialogNamesTheSlotAndConfirms() {
        var confirmed = false
        composeRule.setContent {
            AnkiMinerTheme {
                ResourceDeleteDialog(
                    pending =
                        PendingResourceDelete(
                            kind = InstalledResourceKind.PITCH,
                            identity = "kanjium",
                            installedLabel = "Kanjium",
                        ),
                    busy = false,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Remove Kanjium?").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").performClick()

        assertTrue(confirmed)
    }

    @Test
    fun resourceDeleteDialogDismissDeletesNothing() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            AnkiMinerTheme {
                ResourceDeleteDialog(
                    pending =
                        PendingResourceDelete(
                            kind = InstalledResourceKind.PITCH,
                            identity = "kanjium",
                            installedLabel = "Kanjium",
                        ),
                    busy = false,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed)
        assertEquals(false, confirmed)
    }

    @Test
    fun emptyFieldShowsTheInheritedEngineDefaultAndTypingReplacesIt() {
        var padding by mutableStateOf("")
        composeRule.setContent {
            AnkiMinerTheme {
                NumericField(
                    value = padding,
                    onChange = { padding = it },
                    label = "Audio padding",
                    placeholder = inheritedDefault(EngineDefaults.AUDIO_PADDING_SECONDS),
                )
            }
        }

        // Blank field, but the value the engine will actually use is on screen.
        composeRule.onNodeWithText("0.3").assertIsDisplayed()

        composeRule.onNode(hasSetTextAction()).performTextReplacement("0.8")

        // Once the user owns the value the inherited one must not linger beside it.
        composeRule.onNodeWithText("0.8").assertIsDisplayed()
        composeRule.onAllNodesWithText("0.3").assertCountEquals(0)
    }

    @Test
    fun busyAnkiNoteTypeOptionsAreDisabled() {
        setBusyAnkiTarget()

        dropdown("Note type", "First").performClick()

        composeRule.onNodeWithText("Second").assertIsNotEnabled()
    }

    @Test
    fun busyAnkiFieldMappingOptionsAreDisabled() {
        setBusyAnkiTarget(
            noteTypeStatus = NoteTypeSetupStatus.FieldsMissing(listOf("word")),
            fieldMap = mapOf("word" to "Expression", "sentence" to "Marker"),
        )

        // Not the Word dropdown: by the first-field rule it offers only the note type's first
        // field, so it has no second option to assert against.
        dropdown("Sentence", "Marker").performClick()

        composeRule.onNodeWithText("Reading").assertIsNotEnabled()
    }

    @Test
    fun busyAnkiCardTypeOptionsAreDisabled() {
        setBusyAnkiTarget()

        dropdown("Card type", "Not mapped").performClick()

        composeRule.onNodeWithText("Word and sentence").assertIsNotEnabled()
    }

    @Test
    fun busyAnkiCardTypeMarkerOptionsAreDisabled() {
        setBusyAnkiTarget(
            fieldMap = mapOf("word" to "Expression"),
            cardType = CardType.WORD_AND_SENTENCE,
            markerField = "Marker",
        )

        dropdown("Marker field", "Marker").performClick()

        composeRule.onNodeWithText("Reading").assertIsNotEnabled()
    }

    @Test
    fun ankiRecoveryConfirmationSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val remediation =
            AnkiPendingRemediation(
                id = 42L,
                type = AnkiRemediationType.MEDIA_STORED_UNATTACHED,
                summaryReason = AnkiRemediationSummary.MEDIA_STORED_UNATTACHED,
                title = "Unattached media",
                summary = "Stored media needs acknowledgement",
                compactEvidence = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                availableActions =
                    setOf(AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA),
            )
        restorationTester.setContent {
            AnkiMinerTheme {
                AnkiRecoveryCard(
                    state =
                        SetupUiState(
                            resourceStartup = ResourceStartupReadiness.READY,
                            anki = AnkiProviderReadiness.Ready(2, null),
                            ankiRecovery = AnkiRecoveryReadiness.Ready,
                            recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                            remediations = AnkiRemediationInventory(listOf(remediation)),
                        ),
                    onRefresh = {},
                    onReconcile = {},
                    onRetryStaging = {},
                    onAcknowledgeMedia = {},
                    onAcknowledgeUncertainMedia = {},
                    onResolveReview = { _, _ -> },
                )
            }
        }

        composeRule
            .onNodeWithText("I understand this stored media is not attached")
            .performClick()
        composeRule
            .onNodeWithText(
                "This records that the exact stored media is not attached to a verified note. " +
                    "The durable evidence is retained.",
            ).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule
            .onNodeWithText(
                "This records that the exact stored media is not attached to a verified note. " +
                    "The durable evidence is retained.",
            ).assertIsDisplayed()
    }

    @Test
    fun themePickerRowsExposeRadioButtonSemantics() {
        val palette = ThemePalettes.all.first()
        composeRule.setContent {
            AnkiMinerTheme {
                ThemePickerDialog(
                    title = "Choose theme",
                    selectedKey = palette.key,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(ThemePickerTestTags.row(palette.key))
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    private fun setBusyAnkiTarget(
        noteTypeStatus: NoteTypeSetupStatus = NoteTypeSetupStatus.Verified(1L),
        fieldMap: Map<String, String> = emptyMap(),
        cardType: CardType? = null,
        markerField: String? = null,
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                AnkiTargetCard(
                    state =
                        SetupUiState(
                            resourceStartup = ResourceStartupReadiness.READY,
                            anki = AnkiProviderReadiness.Ready(2, null),
                            runtimeWorkKind = RuntimeWorkCoordinator.Kind.MINING,
                            noteTypeStatus = noteTypeStatus,
                            availableNoteTypes =
                                listOf(
                                    ModelSummary(
                                        id = 1L,
                                        name = "First",
                                        fieldNames = listOf("Expression", "Reading", "Marker"),
                                    ),
                                    ModelSummary(
                                        id = 2L,
                                        name = "Second",
                                        fieldNames = listOf("Expression", "Reading", "Marker"),
                                    ),
                                ),
                            noteType = "First",
                            fieldMap = fieldMap,
                            cardType = cardType,
                            cardTypeMarkerField = markerField,
                        ),
                    onSelectNoteType = {},
                    onSetFieldMapping = { _, _ -> },
                    onSelectCardType = {},
                    onSelectCardTypeMarker = {},
                )
            }
        }
    }

    private fun dropdown(
        label: String,
        value: String,
    ) = composeRule.onNode(
        hasText(label) and
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(value),
            ),
    )

    private companion object {
        val OPTIONS = listOf("First", "Second", "Third")
    }
}
