package com.ankiminer.android.mining

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningRepositoryFactoryParityTest {
    @Test
    fun debugAndReleaseFactoriesExposeTheSameMethodsIncludingAudio() {
        val debugMethods = factoryMethods("debug")
        val releaseMethods = factoryMethods("release")

        assertEquals(releaseMethods, debugMethods)
        assertTrue("createAudio missing from MiningRepositoryFactory", "createAudio" in debugMethods)
    }

    private fun factoryMethods(sourceSet: String): Set<String> {
        val source =
            File(
                projectRoot(),
                "app/src/$sourceSet/kotlin/com/ankiminer/android/mining/MiningRepositoryFactory.kt",
            ).readText()
        return FACTORY_METHOD.findAll(source).map { it.groupValues[1] }.toSet()
    }

    private fun projectRoot(): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(cursor, "settings.gradle.kts").isFile) {
            cursor = cursor.parentFile ?: error("could not find project root")
        }
        return cursor
    }

    private companion object {
        val FACTORY_METHOD =
            Regex(
                """^\s*fun\s+([A-Za-z][A-Za-z0-9_]*)\s*\(""",
                RegexOption.MULTILINE,
            )
    }
}
