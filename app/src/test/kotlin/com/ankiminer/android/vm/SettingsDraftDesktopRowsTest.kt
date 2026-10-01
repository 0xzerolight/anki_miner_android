package com.ankiminer.android.vm

import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows the Word filters, Sentences and Cards & Anki pages gained to mirror desktop. */
class SettingsDraftDesktopRowsTest {
    private fun draft(settings: AppSettings = AppSettings()): SettingsDraft =
        SettingsDraft.from(settings, ResourceManagerState())

    @Test
    fun overridesRoundTripThroughTheDraft() {
        val base =
            AppSettings(
                minFrequencyRank = 1500,
                frequencyKeepUnranked = true,
                knownWordsMatchKanaVariants = false,
                strictCardOrder = true,
                mergeIncompleteCues = true,
                secondarySubtitleEnabled = true,
            )

        val loaded = draft(base)

        assertEquals("1500", loaded.minFrequency)
        assertEquals(base, loaded.toSettings(base))
    }

    @Test
    fun editsReachTheSettings() {
        val base = AppSettings()

        val saved =
            draft(base)
                .copy(
                    minFrequency = "200",
                    frequencyKeepUnranked = true,
                    knownWordsMatchKanaVariants = false,
                    strictCardOrder = true,
                    mergeIncompleteCues = true,
                    secondarySubtitleEnabled = true,
                ).toSettings(base)

        assertEquals(200, saved.minFrequencyRank)
        assertEquals(true, saved.frequencyKeepUnranked)
        assertEquals(false, saved.knownWordsMatchKanaVariants)
        assertEquals(true, saved.strictCardOrder)
        assertEquals(true, saved.mergeIncompleteCues)
        assertTrue(saved.secondarySubtitleEnabled)
    }

    @Test
    fun aNegativeMinimumIsReportedOnItsOwnField() {
        val edited = draft().copy(minFrequency = "-1")

        assertTrue(SettingsFieldKey.MIN_FREQUENCY in edited.validation)
        // The persisted value is kept rather than the broken text.
        assertEquals(null, edited.toPersistableSettings(AppSettings()).minFrequencyRank)
    }

    @Test
    fun raisingTheMinimumPastTheMaximumRaisesTheMaximum() {
        val ordered =
            draft()
                .copy(minFrequency = "8000", maxFrequency = "5000")
                .withOrderedFrequencyBand(FrequencyBandEnd.MIN)

        assertEquals("8000", ordered.minFrequency)
        assertEquals("8000", ordered.maxFrequency)
    }

    @Test
    fun loweringTheMaximumBelowTheMinimumLowersTheMinimum() {
        val ordered =
            draft()
                .copy(minFrequency = "8000", maxFrequency = "5000")
                .withOrderedFrequencyBand(FrequencyBandEnd.MAX)

        assertEquals("5000", ordered.minFrequency)
        assertEquals("5000", ordered.maxFrequency)
    }

    @Test
    fun anOpenEndIsNeverDraggedAlong() {
        // 0 means "no limit" on that end, not rank zero.
        val openMax = draft().copy(minFrequency = "8000", maxFrequency = "0")
        val blankMax = draft().copy(minFrequency = "8000", maxFrequency = "")

        assertSame(openMax, openMax.withOrderedFrequencyBand(FrequencyBandEnd.MIN))
        assertSame(blankMax, blankMax.withOrderedFrequencyBand(FrequencyBandEnd.MIN))
    }

    @Test
    fun anOrderedOrMalformedBandIsLeftAlone() {
        val ordered = draft().copy(minFrequency = "100", maxFrequency = "5000")
        val equal = draft().copy(minFrequency = "5000", maxFrequency = "5000")
        val malformed = draft().copy(minFrequency = "80x", maxFrequency = "5000")

        assertSame(ordered, ordered.withOrderedFrequencyBand(FrequencyBandEnd.MIN))
        assertSame(equal, equal.withOrderedFrequencyBand(FrequencyBandEnd.MAX))
        assertSame(malformed, malformed.withOrderedFrequencyBand(FrequencyBandEnd.MIN))
    }

    @Test
    fun anInvertedBandKeepsTheStoredBand() {
        // An inverted band drops every ranked word, so storage keeps the last ordered one until
        // the field is left and the UI orders the band.
        val base = AppSettings(minFrequencyRank = 1000, maxFrequencyRank = 5000)

        val raisedMin = draft(base).copy(minFrequency = "8000").toPersistableSettings(base)
        val loweredMax = draft(base).copy(maxFrequency = "500").toPersistableSettings(base)

        assertEquals(1000, raisedMin.minFrequencyRank)
        assertEquals(5000, raisedMin.maxFrequencyRank)
        assertEquals(1000, loweredMax.minFrequencyRank)
        assertEquals(5000, loweredMax.maxFrequencyRank)
    }

    @Test
    fun aBrokenEndCannotInvertTheBandThroughItsStoredValue() {
        // The minimum falls back to its stored 1000, which is above the new maximum.
        val base = AppSettings(minFrequencyRank = 1000, maxFrequencyRank = 5000)

        val saved =
            draft(base).copy(minFrequency = "-1", maxFrequency = "500").toPersistableSettings(base)

        assertEquals(1000, saved.minFrequencyRank)
        assertEquals(5000, saved.maxFrequencyRank)
    }

    @Test
    fun anOrderedBandIsStoredAsTyped() {
        val base = AppSettings(minFrequencyRank = 1000, maxFrequencyRank = 5000)

        val saved =
            draft(base).copy(minFrequency = "8000", maxFrequency = "9000").toPersistableSettings(base)

        assertEquals(8000, saved.minFrequencyRank)
        assertEquals(9000, saved.maxFrequencyRank)
    }

    @Test
    fun theBandIsSetWhileEitherEndIsAboveZero() {
        assertFalse(draft().frequencyBandSet)
        assertFalse(draft().copy(minFrequency = "", maxFrequency = "").frequencyBandSet)
        assertTrue(draft().copy(minFrequency = "10").frequencyBandSet)
        assertTrue(draft().copy(maxFrequency = "10").frequencyBandSet)
    }

    @Test
    fun theMinimumDebouncesLikeTheOtherNumericFields() {
        val before = draft()

        assertEquals(
            SettingsWriteCadence.DEBOUNCED,
            settingsWriteCadence(before, before.copy(minFrequency = "12")),
        )
        assertEquals(
            SettingsWriteCadence.IMMEDIATE,
            settingsWriteCadence(before, before.copy(strictCardOrder = true)),
        )
    }
}
