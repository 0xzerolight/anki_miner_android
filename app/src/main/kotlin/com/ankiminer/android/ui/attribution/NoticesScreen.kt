package com.ankiminer.android.ui.attribution

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NOTICES_ASSET_DIR = "notices"

/**
 * Renders the third-party license texts bundled under assets/notices/. Files are
 * read off the main thread and shown one card per document so the ~110 KiB of
 * text lays out lazily instead of blocking the UI.
 */
@Composable
internal fun NoticesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val documents by produceState(initialValue = emptyList<NoticeDocument>(), context) {
        value = withContext(Dispatchers.IO) { loadNotices(context) }
    }
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.attribution_back)) }
        }
        item {
            Text(stringResource(R.string.notices_title), style = MaterialTheme.typography.headlineSmall)
        }
        item { Text(stringResource(R.string.notices_intro)) }
        items(documents, key = { it.name }) { document ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(document.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        document.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

private data class NoticeDocument(val name: String, val text: String)

private fun loadNotices(context: Context): List<NoticeDocument> {
    val assets = context.assets
    val names = assets.list(NOTICES_ASSET_DIR)?.sortedWith(NOTICE_ORDER) ?: emptyList()
    return names.map { name ->
        val text = assets.open("$NOTICES_ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        NoticeDocument(name, text)
    }
}

// NOTICE.md is the index and reads first; every other file follows alphabetically.
private val NOTICE_ORDER =
    Comparator<String> { a, b ->
        when {
            a == b -> 0
            a == "NOTICE.md" -> -1
            b == "NOTICE.md" -> 1
            else -> a.compareTo(b)
        }
    }
