package com.ankiminer.android.reading

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `decode` itself is instrumented-only: the JVM unit test build's `android.jar` provides a stub
 * `BitmapFactory` that throws on every call. These tests cover the two pure helpers instead —
 * [readBoundedZipEntry]'s zip-lookup and cap-enforcement, and [computeInSampleSize]'s rounding.
 */
class CurationPageImageDecoderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `entry present round-trips its exact bytes`() {
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val archive = buildZip("page1.png" to content)

        val result = readBoundedZipEntry(archive, "page1.png", byteCap = 1024)

        assertArrayEquals(content, result)
    }

    @Test
    fun `missing entry returns null`() {
        val archive = buildZip("page1.png" to byteArrayOf(1, 2, 3))

        assertNull(readBoundedZipEntry(archive, "page2.png", byteCap = 1024))
    }

    @Test
    fun `traversal-shaped entry name is a plain lookup miss`() {
        val archive = buildZip("page1.png" to byteArrayOf(1, 2, 3))

        assertNull(readBoundedZipEntry(archive, "../../page1.png", byteCap = 1024))
    }

    @Test
    fun `declared size over cap is rejected`() {
        val content = ByteArray(200) { it.toByte() }
        val archive = buildZip("page1.png" to content)

        assertNull(readBoundedZipEntry(archive, "page1.png", byteCap = 50))
    }

    @Test
    fun `unrepresentable declared size sentinel is rejected without reading`() {
        // `ZipEntry.getSize()` masks its raw 32-bit field as unsigned, so the on-disk bytes for
        // -1 (the zip64 "see the extra field" sentinel, here left unbacked by real zip64 data)
        // surface as a huge positive value rather than a negative one on this JDK. Either shape
        // must land in the same `entry.size < 0 || entry.size > byteCap` guard.
        val content = byteArrayOf(1, 2, 3)
        val archive = buildZipWithPatchedSize("page1.png", content, declaredSize = -1)

        assertNull(readBoundedZipEntry(archive, "page1.png", byteCap = 1024))
    }

    @Test
    fun `lying declared size aborts once the actual read passes the cap`() {
        val content = ByteArray(5_000) { (it % 251).toByte() }
        val archive = buildZipWithPatchedSize("page1.png", content, declaredSize = 10)

        assertNull(readBoundedZipEntry(archive, "page1.png", byteCap = 100))
    }

    @Test
    fun `computeInSampleSize matches the desktop rounding table`() {
        assertEquals(2, computeInSampleSize(1500, 2100, maxLongEdge = 1280))
        assertEquals(1, computeInSampleSize(800, 600, maxLongEdge = 1280))
        assertEquals(4, computeInSampleSize(4000, 4000, maxLongEdge = 1280))
    }

    @Test
    fun `an Error thrown mid-read is contained as null, not propagated`() {
        // decode()'s own OutOfMemoryError path (BitmapFactory) needs a real Android runtime and
        // is not reachable from a JVM unit test; this proves the same containment at the byte-read
        // boundary instead — an Error, not just an Exception, thrown out of the InputStream must
        // still surface as `null`.
        val explodingInput =
            object : InputStream() {
                override fun read(): Int = error("single-byte read must not be used by readBounded")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = throw OutOfMemoryError("simulated mid-read allocation failure")
            }

        val result = readBoundedContained(explodingInput, declaredSize = 10, byteCap = 1024)

        assertNull(result)
    }

    private fun buildZip(vararg members: Pair<String, ByteArray>): File {
        val file = temporary.newFile("archive-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            members.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * Builds a normal deflated-entry zip, then overwrites the central directory's declared
     * uncompressed-size field in place. The inflater does not stop at the declared size — it
     * stops at the end of the deflate stream — so this reliably produces a "lying" entry: what
     * [java.util.zip.ZipEntry.getSize] reports differs from what actually decompresses.
     */
    private fun buildZipWithPatchedSize(
        name: String,
        content: ByteArray,
        declaredSize: Int,
    ): File {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        val archive = bytes.toByteArray()
        val centralDirectoryOffset = findCentralDirectorySignature(archive)
        patchLittleEndianU32(archive, centralDirectoryOffset + UNCOMPRESSED_SIZE_FIELD_OFFSET, declaredSize)
        val file = temporary.newFile("patched-${System.nanoTime()}.zip")
        file.writeBytes(archive)
        return file
    }

    private fun findCentralDirectorySignature(archive: ByteArray): Int {
        for (index in 0..archive.size - CENTRAL_DIRECTORY_SIGNATURE.size) {
            var matches = true
            for (offset in CENTRAL_DIRECTORY_SIGNATURE.indices) {
                if (archive[index + offset] != CENTRAL_DIRECTORY_SIGNATURE[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        error("central directory signature not found in test fixture archive")
    }

    private fun patchLittleEndianU32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private companion object {
        val CENTRAL_DIRECTORY_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        const val UNCOMPRESSED_SIZE_FIELD_OFFSET = 24
    }
}
