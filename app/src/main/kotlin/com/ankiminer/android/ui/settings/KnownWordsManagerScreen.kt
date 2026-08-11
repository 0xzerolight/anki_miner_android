package com.ankiminer.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.ui.theme.AdaptiveActionGroup
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.exitActionButtonColors
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

internal object KnownWordsManagerTestTags {
    const val LIST = "known-words-manager-list"

    fun remove(word: String): String = "known-words-remove:$word"
}

internal data class KnownWordsManagerCallbacks(
    val onSearchChanged: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onReset: (KnownWordsResetScope) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissFailure: () -> Unit = {},
)

internal enum class KnownWordsListContent {
    NONE,
    LOADING,
    EMPTY,
    WORDS,
}

internal data class KnownWordsListPresentation(
    val content: KnownWordsListContent,
    val showProgress: Boolean,
    val showLoadMore: Boolean,
)

internal fun knownWordsListPresentation(
    page: KnownWordsPage?,
    operationActive: Boolean,
    failureVisible: Boolean,
): KnownWordsListPresentation {
    val content =
        when {
            page == null && operationActive -> KnownWordsListContent.LOADING
            page == null && failureVisible -> KnownWordsListContent.NONE
            page == null -> KnownWordsListContent.LOADING
            page.words.isEmpty() && operationActive -> KnownWordsListContent.LOADING
            page.words.isEmpty() -> KnownWordsListContent.EMPTY
            else -> KnownWordsListContent.WORDS
        }
    return KnownWordsListPresentation(
        content = content,
        showProgress =
            content == KnownWordsListContent.LOADING ||
                (content == KnownWordsListContent.WORDS && operationActive),
        showLoadMore =
            content == KnownWordsListContent.WORDS &&
                !operationActive &&
                page?.hasMore == true,
    )
}

@Composable
internal fun KnownWordsManagerRoute(
    setupViewModel: SetupViewModel,
    modifier: Modifier = Modifier,
) {
    val state by setupViewModel.uiState.collectAsStateWithLifecycle()
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setupViewModel.onKnownWordsPicked(uri?.toString())
        }
    val exportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            uri?.let { setupViewModel.exportKnownWords(it.toString()) }
        }
    LaunchedEffect(setupViewModel) { setupViewModel.searchKnownWords() }
    KnownWordsManagerScreen(
        state = state,
        callbacks =
            KnownWordsManagerCallbacks(
                onSearchChanged = setupViewModel::setKnownWordsSearch,
                onSearch = setupViewModel::searchKnownWords,
                onLoadMore = setupViewModel::loadMoreKnownWords,
                onRemove = setupViewModel::removeKnownWord,
                onImport = {
                    if (setupViewModel.beginKnownWordsPicker()) {
                        importPicker.launch(KNOWN_WORDS_MIME_TYPES)
                    }
                },
                onExport = { exportPicker.launch("known_words.txt") },
                onReset = setupViewModel::resetKnownWords,
                onCancel = setupViewModel::cancelOperation,
                onRetry = setupViewModel::retryResourceFailure,
                onDismissFailure = setupViewModel::dismissFailure,
            ),
        modifier = modifier,
    )
}

@Composable
internal fun KnownWordsManagerScreen(
    state: SetupUiState,
    callbacks: KnownWordsManagerCallbacks = KnownWordsManagerCallbacks(),
    modifier: Modifier = Modifier,
) {
    var pendingResetName by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingReset =
        pendingResetName?.let { saved ->
            KnownWordsResetScope.entries.firstOrNull { it.name == saved }
        }
    pendingReset?.let { scope ->
        AlertDialog(
            onDismissRequest = { pendingResetName = null },
            title = {
                Text(
                    stringResource(
                        if (scope == KnownWordsResetScope.USER) {
                            R.string.known_words_reset_user
                        } else {
                            R.string.known_words_rebuild_cache
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (scope == KnownWordsResetScope.USER) {
                            R.string.known_words_reset_user_confirmation
                        } else {
                            R.string.known_words_rebuild_cache_confirmation
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingResetName = null
                        callbacks.onReset(scope)
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetName = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = AnkiMinerTokens.Space.content, vertical = AnkiMinerTokens.Space.group),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            Text(
                stringResource(
                    if (state.knownWords.schemaOk) {
                        R.string.known_words_inventory
                    } else {
                        R.string.known_words_inventory_invalid
                    },
                    state.knownWords.totalCount,
                    state.knownWords.userCount,
                    state.knownWords.ankiCount,
                    state.knownWords.minedCount,
                ),
            )
            OutlinedTextField(
                value = state.knownWordsSearch,
                onValueChange = callbacks.onSearchChanged,
                label = { Text(stringResource(R.string.known_words_search)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = callbacks.onSearch,
                enabled = !state.busy,
                colors = forwardButtonColors(),
            ) { Text(stringResource(R.string.known_words_search_action)) }
            state.failure
                ?.takeIf { it.origin == ResourceFailureOrigin.KNOWN_WORDS }
                ?.let { failure ->
                    InlineFailureContainer(
                        message = failure.message,
                        actionLabel =
                            stringResource(
                                if (failure.retry.action == ResourceFailureAction.CHOOSE_ANOTHER) {
                                    R.string.b3_choose_another
                                } else {
                                    R.string.b3_retry
                                },
                            ),
                        onAction =
                            when (knownWordsFailureTarget(failure)) {
                                KnownWordsFailureTarget.IMPORT -> callbacks.onImport
                                KnownWordsFailureTarget.EXPORT -> callbacks.onExport
                                null -> callbacks.onRetry
                            },
                        onDismiss = callbacks.onDismissFailure,
                    )
                }
        }

        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(KnownWordsManagerTestTags.LIST),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
        ) {
            val page = state.knownWordsPage
            val operation = state.operation
            val presentation =
                knownWordsListPresentation(
                    page = page,
                    operationActive = operation != null,
                    failureVisible =
                        state.failure?.origin == ResourceFailureOrigin.KNOWN_WORDS,
                )
            when (presentation.content) {
                KnownWordsListContent.NONE -> Unit
                KnownWordsListContent.LOADING -> {
                    item(key = "loading", contentType = "footer") {
                        if (operation == null) {
                            CircularProgressIndicator()
                        } else {
                            ResourceOperationCard(operation, callbacks.onCancel)
                        }
                    }
                }

                KnownWordsListContent.EMPTY -> {
                    item(key = "empty", contentType = "footer") {
                        Text(stringResource(R.string.b3_known_words_empty))
                    }
                }

                KnownWordsListContent.WORDS -> {
                    items(
                        items = requireNotNull(page).words,
                        key = { word -> word },
                        contentType = { "word" },
                    ) { word ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(word, Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { callbacks.onRemove(word) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag(KnownWordsManagerTestTags.remove(word)),
                                colors = exitActionButtonColors(isError = true),
                                border = actionBorder(enabled = !state.busy),
                            ) {
                                Text(stringResource(R.string.known_words_remove))
                            }
                        }
                    }
                    if (presentation.showProgress) {
                        item(key = "load-more-progress", contentType = "footer") {
                            CircularProgressIndicator()
                        }
                    } else if (presentation.showLoadMore) {
                        item(key = "load-more", contentType = "footer") {
                            OutlinedButton(
                                onClick = callbacks.onLoadMore,
                                enabled = !state.busy,
                                colors = outlinedActionButtonColors(),
                                border = actionBorder(enabled = !state.busy),
                            ) {
                                Text(stringResource(R.string.known_words_load_more))
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier.padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            AdaptiveActionGroup(
                primary = { actionModifier ->
                    OutlinedButton(
                        onClick = callbacks.onExport,
                        enabled = !state.busy,
                        modifier = actionModifier,
                        colors = outlinedActionButtonColors(),
                        border = actionBorder(enabled = !state.busy),
                    ) { Text(stringResource(R.string.known_words_export)) }
                },
                secondary = { actionModifier ->
                    OutlinedButton(
                        onClick = { pendingResetName = KnownWordsResetScope.USER.name },
                        enabled = !state.busy && state.knownWords.userCount > 0,
                        modifier = actionModifier,
                        colors = outlinedActionButtonColors(),
                        border =
                            actionBorder(
                                enabled = !state.busy && state.knownWords.userCount > 0,
                            ),
                    ) { Text(stringResource(R.string.known_words_reset_user)) }
                },
            )
            OutlinedButton(
                onClick = { pendingResetName = KnownWordsResetScope.CACHE.name },
                enabled =
                    !state.busy &&
                        state.knownWords.ankiCount + state.knownWords.minedCount > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedActionButtonColors(),
                border =
                    actionBorder(
                        enabled =
                            !state.busy &&
                                state.knownWords.ankiCount + state.knownWords.minedCount > 0,
                    ),
            ) { Text(stringResource(R.string.known_words_rebuild_cache)) }
        }
    }
}
