package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * JVM-level proof for the canonical-path media-staging gate (bug #6). AnkiDroid media storage
 * failed for every asset ("N media file(s) could not be stored in Anki") because the Kotlin
 * approved-source roots were built without resolving the /data/user/0 -> /data/data app-data
 * symlink, so the canonical (symlink-resolved) source paths the Python engine emits never matched.
 *
 * These tests drive the extracted, Context/Os-free decision functions against a real symlinked
 * directory layout that mirrors Android app storage, so the regression is provable on the host JVM
 * without an emulator.
 */
class AndroidAnkiMediaSourceGateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `canonical roots accept engine-emitted sources under every approved root`() {
        val layout = symlinkedAppStorage()
        val cacheRoot = layout.symlinkedCache.toRealPath()
        val filesRoot = layout.symlinkedFiles.toRealPath()
        val stagingRoot = cacheRoot.resolve(ANKI_MEDIA_STAGING_ROOT).normalize()
        val approvedSourceRoots = approvedMediaSourceRoots(cacheRoot, filesRoot)

        // One nested source under each approved root (cache screenshots, dictionary media, local
        // audio packs), addressed by the canonical path the engine emits after path.resolve().
        approvedSourceRoots.forEachIndexed { index, approvedRoot ->
            val source = approvedRoot.resolve("nested/asset-$index.bin")
            Files.createDirectories(source.parent)
            Files.createFile(source)

            val approved =
                approveMediaSource(
                    source.toString(),
                    cacheRoot,
                    filesRoot,
                    stagingRoot,
                    approvedSourceRoots,
                )

            assertEquals(source, approved)
        }
    }

    @Test
    fun `raw uncanonicalized roots reject the canonical engine source (pre-fix failure)`() {
        val layout = symlinkedAppStorage()
        // The pre-fix platform built its roots from cacheDir/filesDir with toAbsolutePath().normalize()
        // only, which preserves the /data/user/0 symlink instead of resolving it to /data/data.
        val rawCacheRoot = layout.symlinkedCache.toAbsolutePath().normalize()
        val rawFilesRoot = layout.symlinkedFiles.toAbsolutePath().normalize()
        val rawStagingRoot = rawCacheRoot.resolve(ANKI_MEDIA_STAGING_ROOT).normalize()
        val rawApprovedSourceRoots = approvedMediaSourceRoots(rawCacheRoot, rawFilesRoot)

        // The engine addresses the very same file by its canonical (symlink-resolved) path.
        val canonicalCache = layout.symlinkedCache.toRealPath()
        val source = canonicalCache.resolve("nested/shot.jpg")
        Files.createDirectories(source.parent)
        Files.createFile(source)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                approveMediaSource(
                    source.toString(),
                    rawCacheRoot,
                    rawFilesRoot,
                    rawStagingRoot,
                    rawApprovedSourceRoots,
                )
            }
        assertTrue(error.message.orEmpty().contains("outside approved app storage"))
    }

    @Test
    fun `a symlinked final component under a canonical root is rejected`() {
        val layout = symlinkedAppStorage()
        val cacheRoot = layout.symlinkedCache.toRealPath()
        val filesRoot = layout.symlinkedFiles.toRealPath()
        val stagingRoot = cacheRoot.resolve(ANKI_MEDIA_STAGING_ROOT).normalize()
        val approvedSourceRoots = approvedMediaSourceRoots(cacheRoot, filesRoot)

        val target = Files.createFile(cacheRoot.resolve("target.bin"))
        val link = Files.createSymbolicLink(cacheRoot.resolve("link.bin"), target)

        assertThrows(IllegalStateException::class.java) {
            approveMediaSource(link.toString(), cacheRoot, filesRoot, stagingRoot, approvedSourceRoots)
        }
    }

    @Test
    fun `a source outside every approved root is rejected`() {
        val layout = symlinkedAppStorage()
        val cacheRoot = layout.symlinkedCache.toRealPath()
        val filesRoot = layout.symlinkedFiles.toRealPath()
        val stagingRoot = cacheRoot.resolve(ANKI_MEDIA_STAGING_ROOT).normalize()
        val approvedSourceRoots = approvedMediaSourceRoots(cacheRoot, filesRoot)

        // Directly under filesRoot, which is the parent of the approved dicts/audio roots but is
        // itself never approved.
        val outside = Files.createFile(filesRoot.resolve("outside.bin"))

        assertThrows(IllegalArgumentException::class.java) {
            approveMediaSource(outside.toString(), cacheRoot, filesRoot, stagingRoot, approvedSourceRoots)
        }
    }

    /**
     * Builds a real app-storage layout under `real/` and exposes it through a `link -> real` parent
     * symlink, mirroring Android's /data/user/0 -> /data/data app-data symlink. The returned
     * cache/files paths traverse the symlink, exactly as Context.getCacheDir()/getFilesDir() do on
     * the affected devices.
     */
    private fun symlinkedAppStorage(): AppStorage {
        val root = temporaryFolder.newFolder().toPath()
        val real = Files.createDirectory(root.resolve("real"))
        Files.createDirectory(real.resolve("cache"))
        Files.createDirectory(real.resolve("files"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        return AppStorage(
            symlinkedCache = link.resolve("cache"),
            symlinkedFiles = link.resolve("files"),
        )
    }

    private data class AppStorage(
        val symlinkedCache: Path,
        val symlinkedFiles: Path,
    )
}
