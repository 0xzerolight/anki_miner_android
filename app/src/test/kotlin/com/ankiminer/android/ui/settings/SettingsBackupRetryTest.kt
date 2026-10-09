package com.ankiminer.android.ui.settings

import com.ankiminer.android.vm.SettingsBackupOperation
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsBackupRetryTest {
    private val onExport: () -> Unit = {}
    private val onImport: () -> Unit = {}

    @Test
    fun `retrying a failed save saves again`() {
        assertSame(onExport, settingsBackupRetry(SettingsBackupOperation.EXPORT, onExport, onImport))
    }

    @Test
    fun `retrying a failed load loads again`() {
        assertSame(onImport, settingsBackupRetry(SettingsBackupOperation.IMPORT, onExport, onImport))
    }
}
