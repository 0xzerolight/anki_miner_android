package com.ankiminer.android.data.settings

import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.LanguageProfileInfo
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.StringWriter

/**
 * The committed `language.profiles` contract fixture (ja, he, ar, as the runtime lane answers),
 * decoded through the real codec so these tests switch with the profiles the bridge sends.
 */
internal object LanguageProfileFixtures {
    val all: List<LanguageProfileInfo> by lazy {
        val message = contractMessage("language profiles result")
        (BridgeJsonCodec.decode(message) as BridgeMessage.LanguageProfilesResult).profiles
    }

    val japanese: LanguageProfileInfo get() = all.single { it.code == "ja" }

    val hebrew: LanguageProfileInfo get() = all.single { it.code == "he" }

    val arabic: LanguageProfileInfo get() = all.single { it.code == "ar" }

    private fun contractMessage(name: String): String {
        val input =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/mining_protocol_v1.json"))
        JsonFactory().createParser(input).use { parser ->
            check(parser.nextToken() == JsonToken.START_OBJECT)
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val section = parser.currentName()
                parser.nextToken()
                if (section != "valid") {
                    parser.skipChildren()
                    continue
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    var caseName: String? = null
                    var message: String? = null
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        val field = parser.currentName()
                        parser.nextToken()
                        when (field) {
                            "name" -> caseName = parser.text
                            "message" ->
                                message =
                                    StringWriter().also { output ->
                                        JsonFactory().createGenerator(output).use {
                                            it.copyCurrentStructure(parser)
                                        }
                                    }.toString()
                            else -> parser.skipChildren()
                        }
                    }
                    if (caseName == name) return checkNotNull(message)
                }
            }
        }
        error("Contract fixture not found: $name")
    }
}
