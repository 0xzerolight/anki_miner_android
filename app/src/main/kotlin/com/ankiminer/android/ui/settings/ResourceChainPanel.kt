package com.ankiminer.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.disabledActionContentColor
import com.ankiminer.android.ui.theme.selectedRowContainer

/** Test handles for the resource panel. Ids are the caller's stable row identity. */
internal object ResourcePanelTestTags {
    const val ADD = "resource-panel-add"
    const val REMOVE = "resource-panel-remove"
    const val LIST = "resource-panel-list"
    const val EMPTY = "resource-panel-empty"

    /** The toolbar's, so it is not row-scoped: one panel shows at most one at a time. */
    const val QUIET_ACTION = "resource-panel-quiet-action"

    fun row(id: String): String = "resource-panel-row:$id"

    fun toggle(id: String): String = "resource-panel-toggle:$id"

    fun moveUp(id: String): String = "resource-panel-move-up:$id"

    fun moveDown(id: String): String = "resource-panel-move-down:$id"
}

/**
 * One row of a resource priority list.
 *
 * [id] is both the stable list key and the selection identity, so it has to survive a reorder: a
 * slot id, a source id, a pack id, or the pinned `"jisho"`. [metadata] is joined with `" · "` on the
 * second line, where [warning] follows it in the error color.
 */
internal data class ResourceRowSpec(
    val id: String,
    val title: String,
    val metadata: List<String>,
    val enabled: Boolean,
    val onToggle: ((Boolean) -> Unit)?,
    val warning: String? = null,
    val movable: Boolean = true,
    val removable: Boolean = true,
    /** Rendered by the toolbar while this row is selected, not on the row itself. */
    val quietAction: ResourcePanelAction? = null,
)

/** A labelled action offered by the panel toolbar, the Add menu, or a single row. */
internal data class ResourcePanelAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Desktop-parity priority list for one resource kind: a bordered list of rows over a toolbar.
 *
 * Reorder is by arrow, matching the desktop app. Drag is deliberately absent — it is the desktop's
 * inaccessible path, and on a 320dp touch screen a drag gesture fights the settings list's own
 * scroll. Rows are a plain [Column]: the lists are short (roughly ten rows at most) and the panel
 * already sits inside the settings screen's lazy list, which cannot nest another one.
 *
 * Selection lives here rather than in the caller because it is a view concern with no effect
 * outside the panel: it only decides which row the toolbar's remove and quiet actions address.
 * [onRemove] fires unconfirmed, so callers keep their existing confirmation dialogs.
 */
@Composable
internal fun ResourceChainPanel(
    heading: String?,
    explanation: String,
    rows: List<ResourceRowSpec>,
    emptyMessage: String,
    onMove: (id: String, delta: Int) -> Unit,
    onRemove: (id: String) -> Unit,
    addPrimary: ResourcePanelAction,
    addMenu: List<ResourcePanelAction> = emptyList(),
    extras: List<ResourcePanelAction> = emptyList(),
    busy: Boolean,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val ids = rows.map { it.id }
    LaunchedEffect(ids) {
        if (selectedId !in ids) {
            selectedId = null
        }
    }
    // Reading the selection through the current rows keeps the removed-row frame consistent: the
    // effect above clears the state, but this frame must not still address a row that is gone.
    val selectedRow = rows.firstOrNull { it.id == selectedId }
    val firstMovableId = rows.firstOrNull { it.movable }?.id
    val lastMovableId = rows.lastOrNull { it.movable }?.id

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        // Null when a disclosure header above the panel already carries the title and the counts.
        if (heading != null) {
            Text(
                text = heading,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ResourcePanelTestTags.LIST)
                    .clip(MaterialTheme.shapes.small)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                    ).selectableGroup(),
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = emptyMessage,
                    modifier =
                        Modifier
                            .testTag(ResourcePanelTestTags.EMPTY)
                            .padding(AnkiMinerTokens.Space.group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ResourcePanelRow(
                    row = row,
                    selected = row.id == selectedId,
                    canMoveUp = row.movable && row.id != firstMovableId,
                    canMoveDown = row.movable && row.id != lastMovableId,
                    busy = busy,
                    onSelect = { selectedId = row.id },
                    onMove = onMove,
                )
            }
        }
        ResourcePanelToolbar(
            addPrimary = addPrimary,
            addMenu = addMenu,
            extras = extras,
            busy = busy,
            quietAction = selectedRow?.quietAction,
            removeEnabled = selectedRow != null && selectedRow.removable && !busy,
            onRemove = { selectedRow?.let { onRemove(it.id) } },
        )
        footer?.invoke()
    }
}

/**
 * Two text lines, the enable checkbox, and the move arrows.
 *
 * The second line is composed even when there is nothing to say on it, so every row measures the
 * same height and the arrow columns stay aligned down the list. Non-movable rows hold their arrow
 * slots open with spacers for the same reason.
 */
@Composable
private fun ResourcePanelRow(
    row: ResourceRowSpec,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onMove: (id: String, delta: Int) -> Unit,
) {
    val warningColor = MaterialTheme.colorScheme.error
    val enableLabel = stringResource(R.string.resource_panel_enable, row.title)
    val detail =
        buildAnnotatedString {
            append(row.metadata.joinToString(MetadataSeparator))
            row.warning?.let { warning ->
                if (length > 0) {
                    append(MetadataSeparator)
                }
                withStyle(SpanStyle(color = warningColor)) { append(warning) }
            }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(ResourcePanelTestTags.row(row.id))
                // Fill before selectable so the press ripple draws over it, not under it.
                .background(
                    if (selected) MaterialTheme.colorScheme.selectedRowContainer() else Color.Transparent,
                ).selectable(
                    selected = selected,
                    // The list is a selectableGroup with exactly one selected row, which is the
                    // shape TalkBack announces as a radio choice.
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .padding(
                    horizontal = AnkiMinerTokens.Space.related,
                    vertical = AnkiMinerTokens.Space.line,
                ),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.micro),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            checked = row.enabled,
            onCheckedChange = row.onToggle,
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(ResourcePanelTestTags.toggle(row.id))
                    .semantics { contentDescription = enableLabel },
            enabled = row.onToggle != null && !busy,
        )
        if (row.movable) {
            SquareSlot {
                MoveButton(
                    rotationDegrees = MoveUpRotation,
                    description = stringResource(R.string.resource_panel_move_up, row.title),
                    testTag = ResourcePanelTestTags.moveUp(row.id),
                    enabled = canMoveUp && !busy,
                    onClick = { onMove(row.id, -1) },
                )
            }
            SquareSlot {
                MoveButton(
                    rotationDegrees = MoveDownRotation,
                    description = stringResource(R.string.resource_panel_move_down, row.title),
                    testTag = ResourcePanelTestTags.moveDown(row.id),
                    enabled = canMoveDown && !busy,
                    onClick = { onMove(row.id, 1) },
                )
            }
        } else {
            Spacer(Modifier.size(AnkiMinerTokens.Layout.minTouchTarget))
            Spacer(Modifier.size(AnkiMinerTokens.Layout.minTouchTarget))
        }
    }
}

/**
 * Add, any extra actions, and the two actions the current selection addresses: its quiet action
 * and remove.
 *
 * The quiet action lives here rather than on the row because the row has exactly one flexible
 * child - the name - and a button beside it left three characters of a dictionary title on a
 * 360dp screen. Absent rather than disabled when the selection offers none: it sits left of the
 * spacer, so neither Add nor remove moves when it appears.
 */
@Composable
private fun ResourcePanelToolbar(
    addPrimary: ResourcePanelAction,
    addMenu: List<ResourcePanelAction>,
    extras: List<ResourcePanelAction>,
    busy: Boolean,
    quietAction: ResourcePanelAction?,
    removeEnabled: Boolean,
    onRemove: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            // Filled rather than the wrapper taxonomy's tonal "utility" import button: desktop D41
            // gives each panel exactly one filled accent, and Add is it.
            PrimaryActionButton(
                onClick = {
                    if (addMenu.isEmpty()) addPrimary.onClick() else menuOpen = true
                },
                modifier = Modifier.testTag(ResourcePanelTestTags.ADD),
                enabled = addPrimary.enabled && !busy,
            ) { Text(addPrimary.label, maxLines = 1) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                addMenu.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            menuOpen = false
                            option.onClick()
                        },
                        enabled = option.enabled && !busy,
                    )
                }
            }
        }
        extras.forEach { action ->
            SecondaryActionButton(
                onClick = action.onClick,
                enabled = action.enabled && !busy,
            ) { Text(action.label, maxLines = 1) }
        }
        quietAction?.let { action ->
            SecondaryActionButton(
                onClick = action.onClick,
                modifier = Modifier.testTag(ResourcePanelTestTags.QUIET_ACTION),
                enabled = action.enabled && !busy,
            ) { Text(action.label, maxLines = 1) }
        }
        Spacer(Modifier.weight(1f))
        SquareSlot {
            OutlinedIconButton(
                onClick = onRemove,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .size(SquareButtonSize)
                        .testTag(ResourcePanelTestTags.REMOVE),
                enabled = removeEnabled,
                shape = MaterialTheme.shapes.small,
                colors =
                    IconButtonDefaults.outlinedIconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = disabledActionContentColor(),
                    ),
                border = removeBorder(removeEnabled),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear),
                    contentDescription = stringResource(R.string.resource_panel_remove_selected),
                )
            }
        }
    }
}

/**
 * Reorder arrow. The shared back-arrow vector rotated a quarter turn: a drawn glyph holds its size
 * inside the fixed button no matter how far the user has scaled their font, which a text arrow
 * would not. The button carries the accessible name, so the icon itself is decorative.
 */
@Composable
private fun MoveButton(
    rotationDegrees: Float,
    description: String,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(SquareButtonSize)
                .testTag(testTag)
                .semantics { contentDescription = description },
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors =
            IconButtonDefaults.outlinedIconButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = disabledActionContentColor(),
            ),
        border = actionBorder(enabled = enabled),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_back),
            contentDescription = null,
            modifier = Modifier.rotate(rotationDegrees),
        )
    }
}

/**
 * Fixed-width slot so the arrow column lines up whether it holds a button or a spacer, independent
 * of the touch-target expansion the buttons apply to themselves.
 */
@Composable
private fun SquareSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(AnkiMinerTokens.Layout.minTouchTarget),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Error-colored peer of `actionBorder`, kept on the same disabled contrast token. */
@Composable
private fun removeBorder(enabled: Boolean): BorderStroke =
    if (enabled) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    } else {
        actionBorder(enabled = false)
    }

private const val MetadataSeparator = " · "

/** `ic_nav_back` points left; a clockwise quarter turn points it up, anticlockwise points it down. */
private const val MoveUpRotation = 90f
private const val MoveDownRotation = -90f
private val SquareButtonSize = 40.dp
