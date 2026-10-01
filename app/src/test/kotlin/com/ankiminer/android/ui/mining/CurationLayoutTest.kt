package com.ankiminer.android.ui.mining

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationLayoutTest {
    @Test
    fun mediaTakesAboutAThirdOfAPhonePane() {
        assertEquals(140f, curationMediaMaxHeight(400.dp).value, 0.01f)
    }

    @Test
    fun aShortLandscapePaneStillGetsAViewableFrame() {
        assertEquals(96f, curationMediaMaxHeight(180.dp).value, 0.01f)
    }

    @Test
    fun theDefinitionNeverTakesMoreThanHalfThePane() {
        assertEquals(200f, curationDefinitionMaxHeight(400.dp).value, 0.01f)
        assertEquals(260f, curationDefinitionMaxHeight(914.dp).value, 0.01f)
        assertEquals(96f, curationDefinitionMaxHeight(150.dp).value, 0.01f)
    }

    @Test
    fun toolsStartOpenOnlyForALongPageOnATallWindow() {
        assertTrue(curationToolsStartExpanded(candidateCount = 100, windowHeightDp = 914))
        assertFalse(curationToolsStartExpanded(candidateCount = 10, windowHeightDp = 914))
        assertFalse(curationToolsStartExpanded(candidateCount = 100, windowHeightDp = 640))
    }
}
