package com.ankiminer.android.anki.provider

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.anki.journal.SqliteAnkiMutationStore
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAnkiMediaStagingInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // The staging gate canonicalizes its approved roots (toRealPath resolves the
    // /data/user/0 -> /data/data app-data symlink) to match the canonical source paths the Python
    // engine emits. Build test source paths under the same canonical prefix so acceptance holds
    // whether the device returns the raw or the canonical cache/files form.
    private val canonicalCacheDir: File = context.cacheDir.toPath().toRealPath().toFile()
    private val canonicalFilesDir: File = context.filesDir.toPath().toRealPath().toFile()

    private val stagingRoot: Path
        get() = context.cacheDir.toPath().resolve(ANKI_MEDIA_STAGING_ROOT)

    @Before
    fun clearStagingRoot() {
        removeTree(stagingRoot)
    }

    @After
    fun cleanFiles() {
        removeTree(stagingRoot)
        context.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(TEST_PREFIX) }
            .forEach { file -> removeTree(file.toPath()) }
    }

    @Test
    fun fileProviderIsConfinedAndServesOnlyTheGeneratedPrivateCopy() {
        val platform = AndroidAnkiMediaStagingPlatform(context)
        val provider =
            requireNotNull(
                context.packageManager.resolveContentProvider(
                    platform.authority,
                    PackageManager.GET_META_DATA,
                ),
            )
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)

        val sourceBytes = "provider bytes".toByteArray()
        val source = cacheFile("source.bin").apply { writeBytes(sourceBytes) }
        platform.openSource(source.absolutePath).use { opened ->
            assertArrayEquals(sourceBytes, opened.readBytes())
        }

        val relativePath = "v1/${"a".repeat(64)}.stage"
        val contentUri = platform.contentUriFor(relativePath)
        platform.createDestination(relativePath).use { destination ->
            destination.stream.write(sourceBytes)
            destination.stream.flush()
            destination.sync()
        }

        assertEquals("content", Uri.parse(contentUri).scheme)
        assertEquals(platform.authority, Uri.parse(contentUri).authority)
        context.contentResolver.openInputStream(Uri.parse(contentUri)).use { opened ->
            assertArrayEquals(sourceBytes, requireNotNull(opened).readBytes())
        }
        platform.deleteDestination(relativePath)
        assertFalse(platform.destinationExists(relativePath))

        assertEveryAllowedExtensionSurvivesTheMimeRoundTrip(platform)
    }

    /**
     * AnkiDroid names a stored media file after `getExtensionFromMimeType(getType(uri))` — it accepts
     * no MIME on the insert. An extension whose MIME does not reverse-map lands as `.bin`, which is
     * the whole of Issue #2. This is the only place that observes that round trip, and it runs on
     * API 26 where the platform tables are thinnest: API 26 registers no `opus` extension and no
     * `audio/ogg` type at all, which is why [AnkiMediaFileProvider] fills those gaps itself.
     *
     * Asserted inside the existing test rather than as a new `@Test` so the pinned instrumentation
     * count in `run-api26-instrumentation.sh` stays put.
     *
     * Every extension is measured before anything is asserted, and a failure prints the whole table.
     * Failing at the first bad extension hides the rest, and the only way to observe them is another
     * emulator run — which is what [AnkiMediaExtensions.DEVICE_UNMAPPABLE_EXTENSIONS] has to be
     * populated from.
     */
    private fun assertEveryAllowedExtensionSurvivesTheMimeRoundTrip(platform: AndroidAnkiMediaStagingPlatform) {
        val mimeTypes = MimeTypeMap.getSingleton()

        // The formats v0.1.7 already stored correctly. Pinning them on the thinnest tables proves the
        // provider's table never overrides a lookup the platform can answer. `opus` is deliberately
        // absent: API 26 has no entry for it, which is the gap the provider fills.
        listOf("mp3", "jpg", "jpeg", "png", "webp").forEach { extension ->
            assertNotNull(
                "API 26 is expected to map .$extension itself",
                mimeTypes.getMimeTypeFromExtension(extension),
            )
        }

        AnkiMediaExtensions.DEVICE_UNMAPPABLE_EXTENSIONS.forEach { extension ->
            assertFalse(
                "$extension is device-unmappable and must not be staged under its real name",
                extension in AnkiMediaExtensions.ALLOWED_EXTENSIONS,
            )
        }

        val table =
            AnkiMediaExtensions.ALLOWED_EXTENSIONS.mapIndexed { index, extension ->
                val token = index.toString(16).padStart(64, '0')
                val path = "v1/$token.$extension"
                val uri = Uri.parse(platform.contentUriFor(path))
                platform.createDestination(path).use { destination ->
                    destination.stream.write(sourceBytes(extension))
                    destination.stream.flush()
                    destination.sync()
                }
                try {
                    val stock = mimeTypes.getMimeTypeFromExtension(extension)
                    val resolved = context.contentResolver.getType(uri)
                    val named = resolved?.let(mimeTypes::getExtensionFromMimeType)
                    val fault =
                        when {
                            stock != null && stock != resolved -> "the provider overrode the platform"
                            resolved == null || resolved == OCTET_STREAM_MIME -> "AnkiDroid stores this as .bin"
                            named == null -> "AnkiDroid cannot name a file after this MIME"
                            else -> null
                        }
                    "${if (fault == null) "ok  " else "FAIL"} .$extension" +
                        " stock=$stock resolved=$resolved names=$named" +
                        (fault?.let { " <- $it" } ?: "") to (fault == null)
                } finally {
                    platform.deleteDestination(path)
                }
            }

        if (table.any { !it.second }) {
            fail(
                "staged media extensions AnkiDroid cannot name, on API ${Build.VERSION.SDK_INT}. " +
                    "Park each FAIL row in AnkiMediaExtensions.DEVICE_UNMAPPABLE_EXTENSIONS, or give it " +
                    "an AnkiMediaFileProvider.FILL value that reverse-maps and names the same format:\n" +
                    table.joinToString("\n") { it.first },
            )
        }
    }

    private fun sourceBytes(extension: String) = "staged $extension".toByteArray()

    @Test
    fun sourceOpeningRejectsOutsideFilesSymlinksAndFifosWithoutBlocking() {
        val platform = AndroidAnkiMediaStagingPlatform(context)
        val approved = cacheFile("approved.bin").apply { writeText("approved") }
        val outside = File(canonicalFilesDir, "$TEST_PREFIX-outside.bin").apply { writeText("outside") }
        val symlink = cacheFile("source-link")
        Files.createSymbolicLink(symlink.toPath(), approved.toPath())
        val fifo = cacheFile("source-fifo")
        Os.mkfifo(fifo.absolutePath, 0x180)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                platform.openSource(outside.absolutePath)
            }
            assertThrows(IllegalStateException::class.java) {
                platform.openSource(symlink.absolutePath)
            }
            assertThrows(IllegalStateException::class.java) {
                platform.openSource(fifo.absolutePath)
            }
        } finally {
            outside.delete()
        }
    }

    @Test
    fun openedSourceRemainsBoundToTheVerifiedFileWhenItsPathIsReplaced() {
        val platform = AndroidAnkiMediaStagingPlatform(context)
        val originalBytes = "original inode".toByteArray()
        val replacementBytes = "replacement inode".toByteArray()
        val source = cacheFile("replace-source.bin").apply { writeBytes(originalBytes) }
        val replacement = cacheFile("replace-new.bin").apply { writeBytes(replacementBytes) }

        platform.openSource(source.absolutePath).use { opened ->
            Files.move(
                replacement.toPath(),
                source.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            assertArrayEquals(originalBytes, opened.readBytes())
        }
        assertArrayEquals(replacementBytes, source.readBytes())
    }

    @Test
    fun everyDestinationOperationRejectsASymlinkedVersionDirectory() {
        val platform = AndroidAnkiMediaStagingPlatform(context)
        Files.createDirectory(stagingRoot)
        val outside = cacheFile("outside-directory").apply { mkdirs() }
        Files.createSymbolicLink(stagingRoot.resolve("v1"), outside.toPath())
        val relativePath = "v1/${"b".repeat(64)}.stage"

        assertThrows(IllegalStateException::class.java) {
            platform.contentUriFor(relativePath)
        }
        assertThrows(IllegalStateException::class.java) {
            platform.destinationExists(relativePath)
        }
        assertThrows(IllegalStateException::class.java) {
            platform.deleteDestination(relativePath)
        }
        assertTrue(outside.isDirectory)
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun newStoreAndCoordinatorRecoverAStagedFileAfterRestart() {
        val databaseName = "$TEST_PREFIX-${UUID.randomUUID()}.db"
        val sourceBytes = "restart recovery".toByteArray()
        val source = cacheFile("restart-source.bin").apply { writeBytes(sourceBytes) }
        val platform = NoGrantPlatform(AndroidAnkiMediaStagingPlatform(context))
        val request =
            AnkiMediaStagingRequest(
                runId = "run_${"1".repeat(32)}",
                requestId = "anki_${"2".repeat(32)}",
                assetId = "asset_${"3".repeat(32)}",
                absoluteSourcePath = source.absolutePath,
                expectedSizeBytes = sourceBytes.size.toLong(),
                expectedSha256 = sha256(sourceBytes),
                aggregateRemainingBytes = sourceBytes.size.toLong(),
            )
        try {
            SqliteAnkiMutationStore(context, databaseName, enforceBackgroundThread = false).use { store ->
                AnkiMediaStaging(StoreAnkiMediaStagingJournal(store), platform).stage(request)
                assertEquals(1, store.stagingForRecovery().size)
            }

            SqliteAnkiMutationStore(context, databaseName, enforceBackgroundThread = false).use { reopened ->
                val report = AnkiMediaStaging(StoreAnkiMediaStagingJournal(reopened), platform).recover()
                assertEquals(1, report.cleanedRecords)
                assertEquals(0, report.quarantinedRecords)
                assertTrue(reopened.stagingForRecovery().isEmpty())
                assertTrue(stagingRoot.toFile().walkTopDown().none { it.isFile })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun cacheFile(label: String): File =
        File(canonicalCacheDir, "$TEST_PREFIX-$label")

    private class NoGrantPlatform(
        private val delegate: AnkiMediaStagingPlatform,
    ) : AnkiMediaStagingPlatform by delegate {
        override fun revokeRead(
            packageName: String,
            contentUri: String,
        ) = Unit
    }

    private companion object {
        const val TEST_PREFIX = "anki-media-staging-test"

        /** Stock `FileProvider`'s fallback, and the MIME AnkiDroid names `.bin`. */
        const val OCTET_STREAM_MIME = "application/octet-stream"
    }
}

private fun removeTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
