package com.ankiminer.android.tokenizer

import java.nio.charset.StandardCharsets

/** Narrow, pointer-free JVM boundary for the S1b tokenizer candidate. */
object MecabNativeTokenizer {
    init {
        System.loadLibrary("anki_miner_mecab")
    }

    /** Parse strict UTF-8 into the versioned AMTK wire buffer. */
    @JvmStatic
    fun tokenize(
        inputUtf8: ByteArray,
        mecabNewArgv: Array<String>,
    ): ByteArray = nativeTokenize(inputUtf8, encodeArgv(mecabNewArgv))

    /** Return copied dictionary-info filenames; native pointers never cross JNI. */
    @JvmStatic
    fun loadedDictionaryFilenames(mecabNewArgv: Array<String>): Array<String> =
        arrayOf(
            nativeDictionaryFilename(encodeArgv(mecabNewArgv)).toString(
                StandardCharsets.UTF_8,
            ),
        )

    private fun encodeArgv(mecabNewArgv: Array<String>): Array<ByteArray> =
        Array(mecabNewArgv.size) { index ->
            mecabNewArgv[index].toByteArray(StandardCharsets.UTF_8)
        }

    private external fun nativeTokenize(
        inputUtf8: ByteArray,
        argvUtf8: Array<ByteArray>,
    ): ByteArray

    private external fun nativeDictionaryFilename(argvUtf8: Array<ByteArray>): ByteArray
}
