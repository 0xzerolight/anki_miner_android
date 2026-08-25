package com.ankiminer.android.ui.mining

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationBlockBox
import com.ankiminer.android.mining.CurationPageContext
import com.ankiminer.android.reading.CurationPageImageDecoder
import com.ankiminer.android.reading.CurationPageImageDecoder.DecodedPageImage
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.ChevronGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

object CurationPageImageTestTags {
    const val SURFACE = "curation_page_image_surface"
    const val COLLAPSE = "curation_page_image_collapse"
    const val PLACEHOLDER = "curation_page_image_placeholder"
    const val CAPTION = "curation_page_image_caption"
}

/** Fit-to-pane transform: (scale, dx, dy) that centers an image inside a pane, aspect kept. */
internal data class PageFitTransform(val scale: Float, val dx: Float, val dy: Float)

/**
 * Desktop parity port of `_PageCanvas.fit_transform` (`page_image_view.py:178-191`): the page is
 * scaled up OR down to fill the pane on its shorter axis (fit-to-pane, not shrink-only), then
 * centered on the other axis. Degenerate pane or image dimensions yield a zero scale so callers
 * skip drawing instead of dividing by zero.
 */
internal fun pageFitTransform(
    paneWidth: Float,
    paneHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): PageFitTransform {
    if (imageWidth <= 0 || imageHeight <= 0 || paneWidth <= 0f || paneHeight <= 0f) {
        return PageFitTransform(0f, 0f, 0f)
    }
    val scale = min(paneWidth / imageWidth, paneHeight / imageHeight)
    val dx = (paneWidth - imageWidth * scale) / 2f
    val dy = (paneHeight - imageHeight * scale) / 2f
    return PageFitTransform(scale, dx, dy)
}

/**
 * Desktop parity port of `_PageCanvas.clamped_box` (`page_image_view.py:193-205`): intersects
 * [box] with the `[0, imageWidth) x [0, imageHeight)` page rect. Real mokuro data has boxes that
 * run slightly outside the page; a box that clamps to empty (fully outside, or degenerate to
 * begin with) returns null so callers draw nothing.
 */
internal fun clampBlockBox(
    box: CurationBlockBox,
    imageWidth: Int,
    imageHeight: Int,
): Rect? {
    if (imageWidth <= 0 || imageHeight <= 0 || box.xMin >= box.xMax || box.yMin >= box.yMax) {
        return null
    }
    val left = box.xMin.coerceIn(0, imageWidth.toLong())
    val top = box.yMin.coerceIn(0, imageHeight.toLong())
    val right = box.xMax.coerceIn(0, imageWidth.toLong())
    val bottom = box.yMax.coerceIn(0, imageHeight.toLong())
    if (left >= right || top >= bottom) return null
    return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

// [entry] is the imageEntry the state was produced for. produceState's underlying MutableState
// is NOT recreated when key1/key2 restart the producer coroutine (only the coroutine restarts),
// so a stale Loaded/Failed from a PREVIOUS entry keeps rendering until the new coroutine catches
// up. Every variant carries its entry so the render side can detect and discard that staleness.
private sealed interface PageDecodeState {
    val entry: String

    data class Loading(override val entry: String) : PageDecodeState

    data class Loaded(override val entry: String, val image: DecodedPageImage) : PageDecodeState

    data class Failed(override val entry: String) : PageDecodeState
}

// Desktop parity (page_image_view.py _HIGHLIGHT_*): warm accent that reads on B/W manga art, low
// alpha fill so the bubble text stays legible under it.
private val HighlightColor = Color(0xFFFF503C)
private const val HIGHLIGHT_FILL_ALPHA = 35f / 255f
private const val HIGHLIGHT_STROKE_WIDTH_PX = 2.5f

// Placeholder/loading aspect while the real page dimensions aren't known yet: a typical portrait
// manga page, so the pane doesn't jump size once the decode resolves.
private const val FALLBACK_ASPECT_RATIO = 3f / 4f

// Caps the pane's image/placeholder region so it cannot starve the candidate list below it: on
// the 320x640@160 CI emulator a full-width portrait page (no cap) leaves the list 0dp tall. This
// also means the box's real aspect ratio stops matching the declared one once a tall page hits
// the cap, which is what makes pageFitTransform's dx/dy letterboxing actually draw (matches the
// video lane's own 16:9-at-full-width player surface, which lands at 180dp on that geometry).
private val PaneContentMaxHeight = 180.dp

// fillMaxWidth MUST run before heightIn/aspectRatio: it locks width to an exact constraint so
// aspectRatio, seeing a capped maxHeight from heightIn, can only give way on height — producing a
// capped box instead of one that silently ignores the cap and grows past it.
private fun Modifier.paneContentSize(aspectRatio: Float): Modifier =
    this
        .fillMaxWidth()
        .heightIn(max = PaneContentMaxHeight)
        .aspectRatio(aspectRatio)

/**
 * Collapsible pane showing the mokuro page a focused curation word came from, with the mokuro
 * text block (speech bubble) containing it highlighted. Fit-to-pane only — no zoom or pan.
 *
 * Stays mounted (showing the "missing" placeholder) when [pageContext] is null so the pane never
 * pops in and out as the focused candidate/sentence changes.
 */
@Composable
fun CurationPageImagePane(
    archivePath: String,
    pageContext: CurationPageContext?,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    decoder: CurationPageImageDecoder,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        if (collapsed) {
            CollapsedPageImageBar(onToggleCollapsed)
        } else {
            Column {
                if (pageContext == null) {
                    PageImagePlaceholder(
                        text = stringResource(R.string.curation_page_image_missing),
                        modifier = Modifier.paneContentSize(FALLBACK_ASPECT_RATIO),
                    )
                } else {
                    PageImageContent(
                        archivePath = archivePath,
                        pageContext = pageContext,
                        decoder = decoder,
                    )
                    Text(
                        text = pageContext.locationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = AnkiMinerTokens.Space.content,
                                    vertical = AnkiMinerTokens.Space.micro,
                                ).testTag(CurationPageImageTestTags.CAPTION),
                    )
                }
                ExpandedPageImageControls(onToggleCollapsed)
            }
        }
    }
}

@Composable
private fun PageImageContent(
    archivePath: String,
    pageContext: CurationPageContext,
    decoder: CurationPageImageDecoder,
) {
    // Two adjacent candidates commonly share (or alternate between) the same page image; the
    // cache spares a re-decode of a ~1280px-long-edge bitmap on every focus flip between them.
    val cache = remember { LruCache<String, DecodedPageImage>(2) }
    val rawState by
        produceState<PageDecodeState>(
            initialValue = PageDecodeState.Loading(pageContext.imageEntry),
            key1 = archivePath,
            key2 = pageContext.imageEntry,
        ) {
            val entry = pageContext.imageEntry
            val cached = cache.get(entry)
            value =
                if (cached != null) {
                    PageDecodeState.Loaded(entry, cached)
                } else {
                    val decoded = withContext(Dispatchers.IO) { decoder.decode(archivePath, entry) }
                    if (decoded != null) {
                        cache.put(entry, decoded)
                        PageDecodeState.Loaded(entry, decoded)
                    } else {
                        PageDecodeState.Failed(entry)
                    }
                }
        }
    // See PageDecodeState's kdoc: rawState can still be Loaded/Failed for the PREVIOUS entry for
    // one frame after pageContext changes (produceState reuses its state holder across producer
    // restarts). Treat anything not for the current entry as Loading so a stale bitmap is never
    // drawn under the new block box.
    val state =
        rawState.takeIf { it.entry == pageContext.imageEntry }
            ?: PageDecodeState.Loading(pageContext.imageEntry)
    when (state) {
        is PageDecodeState.Failed ->
            PageImagePlaceholder(
                text = stringResource(R.string.curation_page_image_error),
                modifier = Modifier.paneContentSize(FALLBACK_ASPECT_RATIO),
            )
        is PageDecodeState.Loading ->
            // Tagged PLACEHOLDER, not SURFACE: SURFACE is reserved for the loaded Canvas so tests
            // asserting it proves a real bitmap decoded and drew, not just that the pane mounted.
            Box(
                modifier =
                    Modifier
                        .paneContentSize(FALLBACK_ASPECT_RATIO)
                        .testTag(CurationPageImageTestTags.PLACEHOLDER),
            )
        is PageDecodeState.Loaded ->
            PageImageCanvas(decoded = state.image, blockBox = pageContext.blockBox)
    }
}

@Composable
private fun PageImageCanvas(
    decoded: DecodedPageImage,
    blockBox: CurationBlockBox,
) {
    val imageBitmap = remember(decoded.bitmap) { decoded.bitmap.asImageBitmap() }
    // Block boxes are in ORIGINAL-page pixel coords; the decoded bitmap may be downsampled, so
    // rescale by the actual decoded/original ratio before fitting to the pane.
    val scaleToBitmap = decoded.bitmap.width / decoded.originalWidth.toFloat()
    val scaledBox =
        remember(blockBox, scaleToBitmap) {
            CurationBlockBox(
                xMin = (blockBox.xMin * scaleToBitmap).toLong(),
                yMin = (blockBox.yMin * scaleToBitmap).toLong(),
                xMax = (blockBox.xMax * scaleToBitmap).toLong(),
                yMax = (blockBox.yMax * scaleToBitmap).toLong(),
            )
        }
    val clampedBox =
        remember(scaledBox, decoded.bitmap) {
            clampBlockBox(scaledBox, decoded.bitmap.width, decoded.bitmap.height)
        }
    Canvas(
        modifier =
            Modifier
                .paneContentSize(decoded.bitmap.width / decoded.bitmap.height.toFloat())
                .testTag(CurationPageImageTestTags.SURFACE),
    ) {
        val transform =
            pageFitTransform(size.width, size.height, decoded.bitmap.width, decoded.bitmap.height)
        if (transform.scale <= 0f) return@Canvas
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(transform.dx.roundToInt(), transform.dy.roundToInt()),
            dstSize =
                IntSize(
                    (decoded.bitmap.width * transform.scale).roundToInt(),
                    (decoded.bitmap.height * transform.scale).roundToInt(),
                ),
        )
        if (clampedBox != null) {
            val highlightTopLeft =
                Offset(
                    transform.dx + clampedBox.left * transform.scale,
                    transform.dy + clampedBox.top * transform.scale,
                )
            val highlightSize =
                Size(
                    clampedBox.width * transform.scale,
                    clampedBox.height * transform.scale,
                )
            drawRect(
                color = HighlightColor.copy(alpha = HIGHLIGHT_FILL_ALPHA),
                topLeft = highlightTopLeft,
                size = highlightSize,
            )
            drawRect(
                color = HighlightColor,
                topLeft = highlightTopLeft,
                size = highlightSize,
                style = Stroke(width = HIGHLIGHT_STROKE_WIDTH_PX),
            )
        }
    }
}

@Composable
private fun PageImagePlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.testTag(CurationPageImageTestTags.PLACEHOLDER),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(AnkiMinerTokens.Space.content),
        )
    }
}

@Composable
private fun ExpandedPageImageControls(onToggleCollapsed: () -> Unit) {
    val collapseDescription = stringResource(R.string.curation_page_collapse_description)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(
            onClick = onToggleCollapsed,
            modifier =
                Modifier
                    .testTag(CurationPageImageTestTags.COLLAPSE)
                    .semantics { contentDescription = collapseDescription },
        ) {
            ChevronGlyph(pointsUp = true)
        }
    }
}

@Composable
private fun CollapsedPageImageBar(onToggleCollapsed: () -> Unit) {
    val expandDescription = stringResource(R.string.curation_page_expand_description)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onToggleCollapsed,
            modifier =
                Modifier
                    .testTag(CurationPageImageTestTags.COLLAPSE)
                    .semantics { contentDescription = expandDescription },
        ) {
            ChevronGlyph(pointsUp = false)
        }
    }
}
