package com.ankiminer.android.ui.mining

import androidx.compose.runtime.Immutable
import com.ankiminer.android.engine.SubtitleCue

/**
 * Pure timing-workbench state. A/B deliberately compares the working offset with unshifted cues,
 * not with the global offset which seeded [initialOffset].
 */
@Immutable
data class TimingPreviewState(
    val initialOffset: Double,
    val workingOffset: Double,
    val previewingUnshifted: Boolean,
    val cues: List<SubtitleCue>,
    val selectedCueIndex: Int?,
) {
    init {
        require(initialOffset.isFinite())
        require(workingOffset.isFinite())
        require(selectedCueIndex == null || selectedCueIndex in cues.indices)
    }

    val previewOffset: Double
        get() = if (previewingUnshifted) 0.0 else workingOffset

    fun nudge(delta: Double): TimingPreviewState =
        copy(
            workingOffset = workingOffset + delta,
            previewingUnshifted = false,
        )

    fun setWorking(value: Double): TimingPreviewState = copy(workingOffset = value)

    fun toggleUnshifted(): TimingPreviewState =
        copy(previewingUnshifted = !previewingUnshifted)

    fun selectCue(index: Int): TimingPreviewState = copy(selectedCueIndex = index)

    companion object {
        const val NUDGE_SECONDS = 0.1
    }
}
