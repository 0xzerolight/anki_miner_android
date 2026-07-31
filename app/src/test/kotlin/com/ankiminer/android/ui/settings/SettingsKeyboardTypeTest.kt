package com.ankiminer.android.ui.settings

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsKeyboardTypeTest {
    @Test
    fun negativeNumericFieldsRequestSignedImeTypes() {
        assertEquals(KeyboardType.NumberSigned, numericKeyboardType(integer = true, allowNegative = true))
        assertEquals(KeyboardType.DecimalSigned, numericKeyboardType(integer = false, allowNegative = true))
    }
}
