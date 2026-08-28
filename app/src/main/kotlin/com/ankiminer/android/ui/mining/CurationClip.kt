package com.ankiminer.android.ui.mining

import androidx.compose.runtime.Immutable
import com.ankiminer.android.mining.CurationClipWindow
import com.ankiminer.android.mining.MAX_CLIP_SECONDS
import com.ankiminer.android.mining.MIN_CLIP_SECONDS
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Resolution of one handle step, and the readout's precision. */
const val CLIP_TICK_SECONDS = 0.1

/** How far past the default window the handles can travel, either side. */
const val CLIP_MARGIN_SECONDS = 3.0

// Tick counts derived from the named second bounds rather than re-stated as literals - the same
// pattern MiningModels.kt uses for CurationClipWindow's own bounds.
private val MIN_CLIP_TICKS = Math.round(MIN_CLIP_SECONDS * 10.0)
private val MAX_CLIP_TICKS = Math.round(MAX_CLIP_SECONDS * 10.0)
private val MARGIN_TICKS = Math.round(CLIP_MARGIN_SECONDS * 10.0)

private fun toTicks(seconds: Double): Long = (seconds / CLIP_TICK_SECONDS).roundToLong()

// Divide rather than multiply by CLIP_TICK_SECONDS: 3 * 0.1 is 0.30000000000000004, and the
// readout, the wire and the equality check that decides `overridden` all want the exact tenth.
private fun toSeconds(ticks: Long): Double = ticks / 10.0

/**
 * A clip window in absolute seconds on the source timeline, deliberately unvalidated.
 *
 * The slider seeds and drags this freely. [CurationClipWindow] enforces the 0.2..30s tick-grid
 * bounds that cross the wire to the engine, but a default window seeded straight from a subtitle
 * line can be longer than that ceiling - the over-long line is not an edit the user made, so
 * seeding must not throw. A later step builds a [CurationClipWindow] from a *coerced* instance of
 * this type at the moment a window commits, which cannot throw because coercion already
 * guarantees legality.
 */
@Immutable
data class ClipWindowSeconds(
    val startSeconds: Double,
    val endSeconds: Double,
) {
    val lengthSeconds: Double get() = endSeconds - startSeconds
}

/**
 * The clip window for one candidate, plus the travel its handles move within.
 *
 * Travel is derived from the *default* window rather than the current one - unlike the desktop
 * strip, which re-seats travel around whatever is showing. Travel that moves while the user is
 * dragging moves the target under their finger; a fixed travel holds still. [window] is always
 * within `[travelStartSeconds, travelEndSeconds]` - [clipWindowUiState] enforces this itself by
 * dropping a stored override the recomputed travel no longer contains (audio padding can change
 * under a stored override, since it is a Settings value, not a per-run one), so nothing here
 * depends on callers clearing the override on every path that could move travel.
 */
@Immutable
data class ClipWindowUiState(
    val window: ClipWindowSeconds,
    val travelStartSeconds: Double,
    val travelEndSeconds: Double,
    val overridden: Boolean,
)

/**
 * The window ffmpeg would cut with no override: the line widened by `audio_padding` each side.
 *
 * Mirrors desktop `AudioClipEditor.set_word`. Applies no MIN/MAX - an over-long default belongs
 * to the line, not to an edit the user made.
 */
fun defaultClipWindow(
    startTime: Double,
    endTime: Double,
    audioPaddingSeconds: Double,
): ClipWindowSeconds =
    ClipWindowSeconds(
        startSeconds = toSeconds(toTicks(max(0.0, startTime - audioPaddingSeconds))),
        endSeconds = toSeconds(toTicks(endTime + audioPaddingSeconds)),
    )

/**
 * Null when the line's own timings are not finite (corrupt subtitle timing).
 *
 * A stored [override] that no longer fits inside the recomputed travel - e.g. `audio_padding`
 * changed in Settings since the override was made, shifting both the default window and the
 * travel underneath it - is dropped; the default seeds instead, same as if the user had never
 * trimmed. Anything else would return a `window` outside its own travel, which in the slider
 * means handles that do not match the numbers beside them.
 */
fun clipWindowUiState(
    startTime: Double,
    endTime: Double,
    audioPaddingSeconds: Double,
    override: CurationClipWindow?,
): ClipWindowUiState? {
    if (!startTime.isFinite() || !endTime.isFinite() || !audioPaddingSeconds.isFinite()) return null
    val default = defaultClipWindow(startTime, endTime, audioPaddingSeconds)
    val travelLoTicks = max(0L, toTicks(default.startSeconds) - MARGIN_TICKS)
    val travelHiTicks = toTicks(default.endSeconds) + MARGIN_TICKS
    val overrideWindow = override?.let { ClipWindowSeconds(it.startSeconds, it.endSeconds) }
    val overrideInTravel =
        overrideWindow != null &&
            toTicks(overrideWindow.startSeconds) >= travelLoTicks &&
            toTicks(overrideWindow.endSeconds) <= travelHiTicks
    val effectiveOverride = overrideWindow.takeIf { overrideInTravel }
    return ClipWindowUiState(
        window = effectiveOverride ?: default,
        travelStartSeconds = toSeconds(travelLoTicks),
        travelEndSeconds = toSeconds(travelHiTicks),
        overridden = effectiveOverride != null && effectiveOverride != default,
    )
}

/**
 * Returns a legal window after the user moved one handle.
 *
 * The handle the user moved keeps its position wherever possible and the other one is pushed,
 * rather than the moved handle being snapped back. Direct port of desktop
 * `audio_clip_editor.coerce`.
 */
fun coerceClipWindow(
    start: Double,
    end: Double,
    travelStart: Double,
    travelEnd: Double,
    movedStart: Boolean,
): ClipWindowSeconds {
    val lo = toTicks(travelStart)
    val hi = toTicks(travelEnd)
    var inTicks = toTicks(start)
    var outTicks = toTicks(end)
    if (movedStart) {
        inTicks = max(lo, min(inTicks, hi - MIN_CLIP_TICKS))
        outTicks = min(hi, max(outTicks, inTicks + MIN_CLIP_TICKS))
        outTicks = min(outTicks, inTicks + MAX_CLIP_TICKS)
        // outTicks is now within [inTicks + MIN_CLIP_TICKS, inTicks + MAX_CLIP_TICKS], so the
        // window is already legal - no further clamp on inTicks can change it.
    } else {
        outTicks = min(hi, max(outTicks, lo + MIN_CLIP_TICKS))
        inTicks = max(lo, min(inTicks, outTicks - MIN_CLIP_TICKS))
        inTicks = max(inTicks, outTicks - MAX_CLIP_TICKS)
        // inTicks is now within [outTicks - MAX_CLIP_TICKS, outTicks - MIN_CLIP_TICKS], so the
        // window is already legal - no further clamp on outTicks can change it.
    }
    return ClipWindowSeconds(toSeconds(inTicks), toSeconds(outTicks))
}
