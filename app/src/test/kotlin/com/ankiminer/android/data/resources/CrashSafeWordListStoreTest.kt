package com.ankiminer.android.data.resources

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashSafeWordListStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `successful removal syncs the changed directory before returning`() {
        val root = temporary.newFolder("word-lists")
        val kind = WordListKind.BLACKLIST
        listOf(
            kind.fileName,
            "${kind.fileName}.candidate",
            "${kind.fileName}.backup",
        ).forEach { name -> File(root, name).writeText(name) }
        val synced = mutableListOf<File>()
        val store = CrashSafeWordListStore(root, syncDirectory = { synced += it })

        val removed = store.remove(kind)

        assertTrue(removed)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertEquals(listOf(root), synced)
    }

    @Test
    fun `directory sync failure makes removal fail`() {
        val root = temporary.newFolder("sync-failure")
        val kind = WordListKind.WHITELIST
        File(root, kind.fileName).writeText("word\n")
        val store =
            CrashSafeWordListStore(
                root,
                syncDirectory = { throw IOException("injected directory sync failure") },
            )

        assertFalse(store.remove(kind))
        assertFalse(File(root, kind.fileName).exists())
    }
}
