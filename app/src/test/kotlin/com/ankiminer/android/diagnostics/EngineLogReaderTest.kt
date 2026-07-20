package com.ankiminer.android.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogReaderTest {
    @Test
    fun missingFileYieldsEmptyTail() {
        val directory = Files.createTempDirectory("engine-log-test").toFile()
        try {
            assertEquals("", EngineLogReader(File(directory, "absent.log")).tail())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun smallFileIsReturnedWhole() {
        val directory = Files.createTempDirectory("engine-log-test").toFile()
        try {
            val log = File(directory, "anki_miner.log")
            log.writeText("WARNING ffmpeg exit code 1: boom\n")

            assertEquals("WARNING ffmpeg exit code 1: boom\n", EngineLogReader(log).tail())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun largeFileIsCappedToItsLastBytes() {
        val directory = Files.createTempDirectory("engine-log-test").toFile()
        try {
            val log = File(directory, "anki_miner.log")
            log.writeText("x".repeat(100) + "\nlast line marker\n")

            val tail = EngineLogReader(log, maxBytes = 32).tail()

            assertTrue(tail.length <= 32)
            assertTrue(tail.contains("last line marker"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidUtf8IsDecodedLeniently() {
        val directory = Files.createTempDirectory("engine-log-test").toFile()
        try {
            val log = File(directory, "anki_miner.log")
            log.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "tail ok\n".toByteArray())

            val tail = EngineLogReader(log).tail()

            assertTrue(tail.endsWith("tail ok\n"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
