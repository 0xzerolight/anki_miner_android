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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.MAX_KNOWN_WORDS_MUTATION
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.ui.theme.AdaptiveActionGroup
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

internal object KnownWordsManagerTestTags {
    const val LIST = "known-words-manager-list"
    const val REMOVE_SELECTED = "known-words-remove-selected"

    fun select(word: String): String = "known-words-select:$word"
}

internal data class KnownWordsManagerCallbacks(
    val onSearchChanged: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRemove: (List<String>) -> Unit = {},
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

/**
 * The selection after tapping [word].
 *
 * Deselection always succeeds; selection stops at [limit] because the bridge rejects a larger
 * batch outright, and a refused tap beats a mutation that fails on dispatch.
 */
internal fun toggleKnownWordSelection(
    selected: Set<String>,
    word: String,
    limit: Int = MAX_KNOWN_WORDS_MUTATION,
): Set<String> =
    when {
        word in selected -> selected - word
        selected.size >= limit -> selected
        else -> selected + word
    }

private val knownWordSelectionSaver: Saver<Set<String>, ArrayList<String>> =
    Saver(save = { ArrayList(it) }, restore = { it.toSet() })

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
                onRemove = setupViewModel::removeKnownWords,
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
    var selectedWords by
        rememberSaveable(stateSaver = knownWordSelectionSaver) { mutableStateOf(emptySet<String>()) }
    val pageWords = state.knownWordsPage?.words.orEmpty()
    // A word the current page no longer lists can be neither shown nor removed, so it must not
    // linger in the selection across a search, a removal, or a reset.
    LaunchedEffect(pageWords) { selectedWords = selectedWords intersect pageWords.toSet() }
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
            PrimaryActionButton(
                onClick = callbacks.onSearch,
                enabled = !state.busy,
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
                        val selectWordLabel = stringResource(R.string.known_words_select_word, word)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(word, Modifier.weight(1f))
                            Checkbox(
                                checked = word in selectedWords,
                                onCheckedChange = {
                                    selectedWords = toggleKnownWordSelection(selectedWords, word)
                                },
                                enabled = !state.busy,
                                modifier =
                                    Modifier
                                        .testTag(KnownWordsManagerTestTags.select(word))
                                        .semantics { contentDescription = selectWordLabel },
                            )
                        }
                    }
                    if (presentation.showProgress) {
                        item(key = "load-more-progress", contentType = "footer") {
                            CircularProgressIndicator()
                        }
                    } else if (presentation.showLoadMore) {
                        item(key = "load-more", contentType = "footer") {
                            SecondaryActionButton(
                                onClick = callbacks.onLoadMore,
                                enabled = !state.busy,
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
            SecondaryActionButton(
                onClick = {
                    val batch = selectedWords.toList()
                    selectedWords = emptySet()
                    callbacks.onRemove(batch)
                },
                enabled = !state.busy && selectedWords.isNotEmpty(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(KnownWordsManagerTestTags.REMOVE_SELECTED),
            ) {
                Text(stringResource(R.string.known_words_remove_selected, selectedWords.size))
            }
            AdaptiveActionGroup(
                primary = { actionModifier ->
                    SecondaryActionButton(
                        onClick = callbacks.onExport,
                        enabled = !state.busy,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.known_words_export)) }
                },
                secondary = { actionModifier ->
                    SecondaryActionButton(
                        onClick = { pendingResetName = KnownWordsResetScope.USER.name },
                        enabled = !state.busy && state.knownWords.userCount > 0,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.known_words_reset_user)) }
                },
            )
            SecondaryActionButton(
                onClick = { pendingResetName = KnownWordsResetScope.CACHE.name },
                enabled =
                    !state.busy &&
                        state.knownWords.ankiCount + state.knownWords.minedCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.known_words_rebuild_cache)) }
        }
    }
}
