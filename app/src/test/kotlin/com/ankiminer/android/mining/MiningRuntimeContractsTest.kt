package com.ankiminer.android.mining

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MiningRuntimeContractsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `built in tokenizer identity comes only from trusted catalog and exact marker`() {
        val filesDir = temporaryFolder.newFolder("files")
        val provider = BuiltInInstalledTokenizerResourceProvider(filesDir)
        assertNull(provider.installedResource())

        val resourceRoot =
            File(filesDir, BuiltInInstalledTokenizerResourceProvider.RESOURCE_RELATIVE_ROOT)
        val dicDir = File(resourceRoot, BuiltInInstalledTokenizerResourceProvider.DICDIR_NAME)
        assertTrue(dicDir.mkdirs())
        val marker =
            File(resourceRoot, BuiltInInstalledTokenizerResourceProvider.COMPLETE_MARKER)
        marker.writeText("untrusted self-declared hash", Charsets.UTF_8)
        assertNull(provider.installedResource())

        marker.writeText(
            BuiltInInstalledTokenizerResourceProvider.COMPLETE_MARKER_CONTENT,
            Charsets.UTF_8,
        )
        val installed = requireNotNull(provider.installedResource())
        assertEquals(dicDir, installed.dicDir)
        assertEquals(BuiltInInstalledTokenizerResourceProvider.RESOURCE_ID, installed.resourceId)
        assertEquals(BuiltInInstalledTokenizerResourceProvider.TREE_SHA_256, installed.treeSha256)
    }

    @Test
    fun `coordinator cancellation is sticky and listener registration is removable`() {
        val cancellation = CoordinatorAnkiCancellation()
        val kept = AtomicInteger()
        val removed = AtomicInteger()
        cancellation.invokeOnCancellation { kept.incrementAndGet() }
        cancellation.invokeOnCancellation { removed.incrementAndGet() }.close()

        assertTrue(cancellation.cancel())
        assertFalse(cancellation.cancel())
        assertTrue(cancellation.isCancelled())
        assertEquals(1, kept.get())
        assertEquals(0, removed.get())

        val late = AtomicInteger()
        cancellation.invokeOnCancellation { late.incrementAndGet() }
        assertEquals(1, late.get())
    }
}
