package com.ankiminer.android

import android.content.Context
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.BuiltInInstalledTokenizerResourceProvider
import com.chaquo.python.Python
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal object PythonInstrumentationRuntime {
    private const val BOOTSTRAP_TIMEOUT_MS = 60_000L

    /** Wait for the production Application-owned bootstrap; never race Python.start. */
    fun awaitReady(): Python {
        val application =
            ApplicationProvider.getApplicationContext<Context>().applicationContext
                as AnkiMinerApplication
        val readiness =
            runBlocking {
                withTimeout(BOOTSTRAP_TIMEOUT_MS) {
                    application.pythonRuntimeReadiness.first { state ->
                        state is PythonRuntimeReadiness.Ready ||
                            state is PythonRuntimeReadiness.Failed
                    }
                }
            }
        check(readiness is PythonRuntimeReadiness.Ready) {
            "Application-owned Python bootstrap failed"
        }
        check(Python.isStarted()) { "Python readiness completed without a running runtime" }
        check(File(readiness.home).canonicalFile == application.filesDir.canonicalFile) {
            "Application-owned Python home differs from filesDir"
        }
        val resourceReadiness =
            runBlocking {
                withTimeout(BOOTSTRAP_TIMEOUT_MS) {
                    application.resourceStartupReadiness.first { state ->
                        state == ResourceStartupReadiness.READY ||
                            state == ResourceStartupReadiness.FAILED
                    }
                }
            }
        check(resourceReadiness == ResourceStartupReadiness.READY) {
            "Application-owned resource startup recovery failed"
        }
        return Python.getInstance()
    }

    fun stageExternalUniDic(
        expectedHash: String,
        archivePath: String = BuildConfig.TOKENIZER_TEST_UNIDIC_ARCHIVE,
    ): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = File(context.filesDir, "test-assets").apply { mkdirs() }
        val destination = File(parent, "unidic-$expectedHash")
        if (destination.isDirectory) return destination
        val staging = File(parent, ".unidic-$expectedHash.staging")
        staging.deleteRecursively()
        check(staging.mkdirs())
        val prefix = staging.canonicalPath + File.separator
        var entries = 0
        var bytes = 0L
        val names = mutableSetOf<String>()
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat $archivePath",
            )
        ZipInputStream(AutoCloseInputStream(descriptor).buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                check(entry.name.isNotBlank() && names.add(entry.name))
                val target = File(staging, entry.name).canonicalFile
                check(target.path.startsWith(prefix))
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory)
                } else {
                    target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = archive.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                            check(bytes <= 512L * 1024 * 1024)
                        }
                    }
                }
                entries += 1
                check(entries <= 512)
                archive.closeEntry()
            }
        }
        check(entries > 0) { "external UniDic ZIP is missing" }
        check(staging.renameTo(destination))
        return destination
    }

    /** Publish the externally attested tree under the exact production catalog identity. */
    fun publishExternalUniDicForAcceptance(expectedHash: String): File {
        check(expectedHash == BuiltInInstalledTokenizerResourceProvider.TREE_SHA_256)
        val catalog = FrozenResourceCatalog.value.unidic
        check(catalog.install.treeSha256 == expectedHash)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val finalRoot =
            File(
                context.filesDir,
                BuiltInInstalledTokenizerResourceProvider.RESOURCE_RELATIVE_ROOT,
            )
        val finalDicDir =
            File(finalRoot, BuiltInInstalledTokenizerResourceProvider.DICDIR_NAME)
        // Always re-publish from the externally attested archive. A marker-only shortcut could
        // preserve equal-size corruption which production recovery would correctly reject later.
        val extracted = stageExternalUniDic(expectedHash)
        val extractedFiles = extracted.walkTopDown().filter { it.isFile }.toList()
        check(extractedFiles.size.toLong() == catalog.install.fileCount)
        check(extractedFiles.sumOf { it.length() } == catalog.install.sizeBytes)
        val parent = requireNotNull(finalRoot.parentFile).apply { mkdirs() }
        val staging = File(parent, ".${finalRoot.name}.s5-staging")
        staging.deleteRecursively()
        check(staging.mkdirs())
        check(extracted.renameTo(File(staging, BuiltInInstalledTokenizerResourceProvider.DICDIR_NAME)))
        File(staging, BuiltInInstalledTokenizerResourceProvider.COMPLETE_MARKER)
            .writeText(
                BuiltInInstalledTokenizerResourceProvider.COMPLETE_MARKER_CONTENT,
                Charsets.UTF_8,
            )
        File(staging, "install.manifest.json")
            .writeText(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("resourceId", catalog.resourceId)
                    .put("archiveSha256", catalog.archive.sha256)
                    .put("archiveSizeBytes", catalog.archive.sizeBytes)
                    .put("treeSha256", catalog.install.treeSha256)
                    .put("treeSizeBytes", catalog.install.sizeBytes)
                    .put("fileCount", catalog.install.fileCount)
                    .toString() + "\n",
                Charsets.UTF_8,
            )
        finalRoot.deleteRecursively()
        check(staging.renameTo(finalRoot))
        return finalDicDir.also { check(it.isDirectory) }
    }
}
