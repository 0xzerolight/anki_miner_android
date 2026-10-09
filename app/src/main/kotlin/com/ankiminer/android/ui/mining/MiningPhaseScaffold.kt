package com.ankiminer.android.ui.mining

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton

internal const val SETUP_PHASE = "setup"
internal const val CURATING_PHASE = "curating"

/** What the sticky bar under the inputs offers outside curation. */
internal sealed interface MiningBottomBarState {
    data class Mine(
        val enabled: Boolean,
        val testTag: String,
        val onMine: () -> Unit,
    ) : MiningBottomBarState

    data class Progress(
        val progress: MiningProgress?,
        val canCancel: Boolean,
        val cancelPending: Boolean,
        val progressTestTag: String,
        val cancelTestTag: String,
        val onCancel: () -> Unit,
    ) : MiningBottomBarState
}

/**
 * One layout for Video, Audio and Reading (desktop D1/A04): inputs stay on screen through every
 * phase but curation; the sticky bar holds Mine, or progress and Cancel, or curation's actions.
 */
@Composable
internal fun <S> MiningPhaseScaffold(
    state: S,
    phaseKey: String,
    label: String,
    phaseTitle: @Composable (S) -> String,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(target: S, paneHeight: Dp) -> Unit,
) {
    val phaseTarget = remember(phaseKey) { MiningPhaseTarget(key = phaseKey, initialState = state) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = bottomBar,
    ) { scaffoldPadding ->
        AnimatedContent(
            targetState = phaseTarget,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (
                    fadeIn(tween(durationMillis = 150)) togetherWith
                        fadeOut(tween(durationMillis = 90))
                ) using null
            },
            contentKey = { target -> target.key },
            label = label,
        ) { target ->
            val targetState = if (target === phaseTarget) state else target.initialState
            val title = phaseTitle(targetState)
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                        .consumeWindowInsets(scaffoldPadding),
            ) {
                val paneHeight = maxHeight
                Column(modifier = Modifier.fillMaxSize().semantics { paneTitle = title }) {
                    content(targetState, paneHeight)
                }
            }
        }
    }
}

@Composable
internal fun MiningBottomBar(
    state: MiningBottomBarState,
    commandErrorMessage: String?,
    onDismissCommandError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = AnkiMinerTokens.Space.content,
                    vertical = AnkiMinerTokens.Space.related,
                ),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            commandErrorMessage?.let { message ->
                MiningFailureCard(
                    message = message,
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = onDismissCommandError,
                        ),
                )
            }
            when (state) {
                is MiningBottomBarState.Mine ->
                    PrimaryActionButton(
                        onClick = state.onMine,
                        enabled = state.enabled,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(state.testTag),
                    ) {
                        Text(stringResource(R.string.start_mining))
                    }
                is MiningBottomBarState.Progress ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiningProgressPanel(
                            progress = state.progress,
                            testTag = state.progressTestTag,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.canCancel) {
                            MiningCancelButton(
                                cancelPending = state.cancelPending,
                                testTag = state.cancelTestTag,
                                onCancel = state.onCancel,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
internal fun MiningCancelButton(
    cancelPending: Boolean,
    testTag: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryActionButton(
        onClick = onCancel,
        enabled = !cancelPending,
        modifier = modifier.testTag(testTag),
    ) {
        if (cancelPending) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.cancelling))
            }
        } else {
            Text(stringResource(R.string.cancel_mining))
        }
    }
}

/**
 * Whether a scroll keyed on [key] should run. The first composition only records the key: a
 * fresh list already sits at the top, and a recreated one (rotation, a return from another tab)
 * restored its position along with the saved key, so scrolling then would throw the user back.
 */
internal fun scrollKeyChanged(
    appliedKey: String?,
    key: String,
): Boolean = appliedKey != null && appliedKey != key

/**
 * Scrolls the list whenever [transitionKey] moves to a new run or phase: back to the top, or, with
 * [revealEnd], to the end, where a finished run's result line sits under the inputs. On a short
 * screen the inputs alone fill the view, and the top would hide how the run went.
 */
@Composable
internal fun ResetMiningScrollOnTransition(
    transitionKey: String,
    listState: LazyListState,
    revealEnd: Boolean = false,
) {
    var appliedKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(transitionKey) {
        if (scrollKeyChanged(appliedKey, transitionKey)) {
            listState.scrollToItem(0)
            if (revealEnd) {
                // scrollToItem remeasured the list for this state, so the count includes the result.
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex > 0) listState.scrollToItem(lastIndex)
            }
        }
        appliedKey = transitionKey
    }
}

/** A run that ended with a result line rather than a failure banner, which stays at the top. */
internal val MiningRunState.endsWithResultLine: Boolean
    get() = this is MiningRunState.Success || this is MiningRunState.Cancelled

/** A failed run's cause, above the inputs it asks the user to change. */
internal fun LazyListScope.miningFailureBannerItem(
    message: String,
    key: String,
) {
    item(key = key, contentType = "header") {
        MiningFailureCard(message = message)
    }
}
