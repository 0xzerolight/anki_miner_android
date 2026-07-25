package com.ankiminer.android.ui.attribution

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NOTICES_ASSET_DIR = "notices"

/**
 * Reads and parses bundled notices off-main. Every block is its own lazy item and selection
 * container, avoiding both eager layout and one screen-reader node per license file.
 */
@Composable
internal fun NoticesScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val documents by produceState(initialValue = emptyList<NoticeDocument>(), context) {
        value = withContext(Dispatchers.IO) { loadNotices(context) }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.content),
    ) {
        item(key = "notices:documents", contentType = "section-heading") {
            SectionTitle(stringResource(R.string.notices_documents_heading))
        }
        documents.forEach { document ->
            item(
                key = "document:${document.name}",
                contentType = "document-heading",
            ) {
                Text(
                    text = document.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(
                count = document.blocks.size,
                key = { index -> "block:${document.name}:$index" },
                contentType = { index -> document.blocks[index].contentType },
            ) { index ->
                NoticeBlockItem(document.blocks[index])
            }
        }
    }
}

@Composable
private fun NoticeBlockItem(block: NoticeBlock) {
    val textModifier =
        if (block is NoticeBlock.Heading) {
            Modifier.semantics { heading() }
        } else {
            Modifier
        }
    val text =
        when (block) {
            is NoticeBlock.Bullet -> "• ${block.text}"
            else -> block.text
        }
    if (block is NoticeBlock.Code) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.small,
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.padding(AnkiMinerTokens.Space.group),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                )
            }
        }
    } else {
        SelectionContainer {
            Text(
                text = text,
                modifier = textModifier.fillMaxWidth(),
                style =
                    when (block) {
                        is NoticeBlock.Heading ->
                            if (block.level <= 2) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            }
                        else -> MaterialTheme.typography.bodySmall
                    },
            )
        }
    }
}

private val NoticeBlock.contentType: String
    get() =
        when (this) {
            is NoticeBlock.Heading -> "block-heading"
            is NoticeBlock.Paragraph -> "paragraph"
            is NoticeBlock.Bullet -> "bullet"
            is NoticeBlock.Code -> "code"
        }

private data class NoticeDocument(
    val name: String,
    val blocks: List<NoticeBlock>,
)

private fun loadNotices(context: Context): List<NoticeDocument> {
    val assets = context.assets
    val names = assets.list(NOTICES_ASSET_DIR)?.sortedWith(NOTICE_ORDER) ?: emptyList()
    return names.map { name ->
        val text = assets.open("$NOTICES_ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        NoticeDocument(name, parseNoticeBlocks(text))
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
