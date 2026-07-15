package com.ankiminer.android.tokenizer

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MecabNativeTokenizerInstrumentedTest {
    @Test
    fun nativeBoundaryRejectsIncompleteArgvWithoutExposingHandles() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                MecabNativeTokenizer.tokenize(
                    "猫".toByteArray(Charsets.UTF_8),
                    arrayOf("anki_miner", "-C"),
                )
            }

        assertTrue(error.message.orEmpty().contains("mecab_new argv"))
    }
}
