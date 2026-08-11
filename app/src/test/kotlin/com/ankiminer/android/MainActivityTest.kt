package com.ankiminer.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun `diagnostics local save copies the staged zip exactly`() {
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00)
        val saved = ByteArrayOutputStream()

        copyDiagnosticsBundle(
            openSource = { ByteArrayInputStream(zip) },
            openDestination = { saved },
        )

        assertArrayEquals(zip, saved.toByteArray())
    }
}
