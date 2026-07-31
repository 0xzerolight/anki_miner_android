package com.ankiminer.android.ui.settings

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsKeyboardTypeTest {
    @Test
    fun negativeNumericFieldsRequestAKeyboardThatCanTypeAMinus() {
        // Number and Decimal have no minus key, so a signed field must not use them.
        assertEquals(KeyboardType.Phone, numericKeyboardType(integer = true, allowNegative = true))
        assertEquals(KeyboardType.Phone, numericKeyboardType(integer = false, allowNegative = true))
    }

    @Test
    fun unsignedNumericFieldsKeepTheirNumericKeyboards() {
        assertEquals(KeyboardType.Number, numericKeyboardType(integer = true, allowNegative = false))
        assertEquals(KeyboardType.Decimal, numericKeyboardType(integer = false, allowNegative = false))
    }
}
