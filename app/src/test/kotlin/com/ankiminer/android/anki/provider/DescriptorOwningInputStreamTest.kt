package com.ankiminer.android.anki.provider

import java.io.FileDescriptor
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DescriptorOwningInputStreamTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `closing the stream closes its descriptor exactly once`() {
        // Android's FileInputStream(FileDescriptor) never closes the descriptor it wraps, so
        // every staged media asset leaked the descriptor Os.open handed back.
        val bytes = "staged media".toByteArray()
        val source = temporary.newFile("source.bin").apply { writeBytes(bytes) }
        RandomAccessFile(source, "r").use { file ->
            val closed = mutableListOf<FileDescriptor>()
            val stream = DescriptorOwningInputStream(file.fd) { closed += it }

            assertArrayEquals(bytes, stream.readBytes())
            assertTrue("descriptor closed while the stream was open", closed.isEmpty())

            stream.close()
            // A second close must not close the number again: the kernel may have reused it.
            stream.close()

            assertEquals(1, closed.size)
            assertSame(file.fd, closed.single())
        }
    }
}
