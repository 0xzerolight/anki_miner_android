package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownWordsImportFormatTest {
    @Test
    fun `wire values are unique and resolvable`() {
        val wireValues = KnownWordsImportFormat.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.distinct().size)
        KnownWordsImportFormat.entries.forEach {
            assertEquals(it, KnownWordsImportFormat.forWireValue(it.wireValue))
        }
    }

    @Test
    fun `an unknown wire value resolves to nothing`() {
        assertNull(KnownWordsImportFormat.forWireValue("kitsun"))
        assertNull(KnownWordsImportFormat.forWireValue(""))
    }
}
