package com.ankiminer.android.data.resources

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.PythonInstrumentationRuntime
import com.chaquo.python.Python
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end android.db import over the real Chaquopy runtime: codec-encoded
 * preflight and import requests through `android_bridge.boundary.dispatch`,
 * then the published metadata-only slot on disk. The host pytest suite covers
 * the same pipeline against the engine; this proves the on-device round trip.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDbAudioPackInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture by lazy { File(context.cacheDir, "instrumented-android.db") }
    private val slot by lazy { File(context.filesDir, "audio_packs/$PACK_ID") }

    @After
    fun cleanFixtures() {
        fixture.delete()
        slot.deleteRecursively()
    }

    @Test
    fun androidDbImportsAsMetadataOnlyPack() {
        val python = PythonInstrumentationRuntime.awaitReady()
        writeFixtureDatabase()

        val candidates =
            ResourceBridgeCodec.decodeAudioPackPreflight(
                dispatch(
                    python,
                    ResourceBridgeCodec.encodeAudioPackPreflightRequest(
                        operation = "adb_instr_preflight",
                        sourcePath = fixture.canonicalPath,
                        displayName = "instrumented-android.db",
                    ),
                ),
            )
        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(PACK_ID, candidate.packId)
        assertEquals("", candidate.packPath)
        assertEquals("android_db", candidate.format)

        val imported =
            ResourceBridgeCodec.decodeImportedAudioPack(
                dispatch(
                    python,
                    ResourceBridgeCodec.encodeAudioPackImportRequest(
                        operation = "adb_instr_import",
                        sourcePath = fixture.canonicalPath,
                        packId = candidate.packId,
                        packPath = "",
                        overwrite = true,
                    ),
                ),
            )
        assertEquals(PACK_ID, imported.packId)
        assertEquals("android_db", imported.format)
        assertEquals(1L, imported.entryCount)

        assertTrue(File(slot, "index.sqlite").isFile)
        assertTrue(File(slot, "android.db").isFile)
        assertFalse(File(slot, "content").exists())
    }

    private fun writeFixtureDatabase() {
        fixture.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(fixture, null)
        try {
            db.execSQL(
                "CREATE TABLE entries (id integer PRIMARY KEY NOT NULL, expression text NOT NULL, " +
                    "reading text, source text NOT NULL, speaker text, display text, file text NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE android (id integer PRIMARY KEY NOT NULL, file text NOT NULL, " +
                    "source text NOT NULL, data blob NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO entries (expression, reading, source, file) VALUES (?, ?, ?, ?)",
                arrayOf("猫", "ねこ", "jpod", "media/cat.opus"),
            )
            db.execSQL(
                "INSERT INTO android (file, source, data) VALUES (?, ?, ?)",
                arrayOf("media/cat.opus", "jpod", "fixture opus".toByteArray()),
            )
        } finally {
            db.close()
        }
    }

    private fun dispatch(
        python: Python,
        request: String,
    ): String =
        python.getModule("android_bridge.boundary")
            .callAttr("dispatch", request)
            .toJava(String::class.java)

    private companion object {
        const val PACK_ID = "instrumented-android"
    }
}
