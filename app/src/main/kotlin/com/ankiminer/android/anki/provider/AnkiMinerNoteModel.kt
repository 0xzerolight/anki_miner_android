package com.ankiminer.android.anki.provider

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/** The first-party note model owned by Anki Miner Android. */
internal object AnkiMinerNoteModel {
    const val MODEL_NAME = "Anki Miner"
    const val DEFAULT_DECK_NAME = "Anki Miner"

    const val TEMPLATE_NAME = "Recognition"

    val FIELD_NAMES: List<String> =
        listOf(
            "Expression",
            "ExpressionFurigana",
            "ExpressionReading",
            "Sentence",
            "SentenceFurigana",
            "SentenceReading",
            "MainDefinition",
            "Glossary",
            "Picture",
            "SentenceAudio",
            "ExpressionAudio",
            "Frequency",
            "FrequencySort",
            "PitchPosition",
            "PitchCategory",
            "PitchGraph",
            "PitchText",
            "Source",
            "IsWordAndSentenceCard",
            "IsClickCard",
            "IsSentenceCard",
            "IsAudioCard",
        )

    val ENGINE_FIELD_MAPPING: Map<String, String> =
        immutableOrderedMap(
            "word" to "Expression",
            "sentence" to "Sentence",
            "definition" to "MainDefinition",
            "glossary" to "Glossary",
            "picture" to "Picture",
            "audio" to "SentenceAudio",
            "expression_furigana" to "ExpressionFurigana",
            "expression_reading" to "ExpressionReading",
            "sentence_furigana" to "SentenceFurigana",
            "sentence_reading" to "SentenceReading",
            "pitch_position" to "PitchPosition",
            "pitch_category" to "PitchCategory",
            "pitch_graph" to "PitchGraph",
            "pitch_text" to "PitchText",
            "frequency" to "Frequency",
            "frequency_sort" to "FrequencySort",
            "source" to "Source",
            "expression_audio" to "ExpressionAudio",
        )

    val CARD_TYPE_MARKER_FIELDS: Map<String, String> =
        immutableOrderedMap(
            "word_and_sentence" to "IsWordAndSentenceCard",
            "click" to "IsClickCard",
            "sentence" to "IsSentenceCard",
            "audio" to "IsAudioCard",
        )

    const val QUESTION_FORMAT =
        """<div class="am-card">
{{#Expression}}
<div class="am-expression">
{{#ExpressionFurigana}}{{furigana:ExpressionFurigana}}{{/ExpressionFurigana}}
{{^ExpressionFurigana}}{{Expression}}{{/ExpressionFurigana}}
</div>
{{/Expression}}
<div class="am-sentence">
{{#SentenceFurigana}}{{furigana:SentenceFurigana}}{{/SentenceFurigana}}
{{^SentenceFurigana}}{{Sentence}}{{/SentenceFurigana}}
</div>
</div>"""

    const val ANSWER_FORMAT =
        """{{FrontSide}}
<hr id="answer">
<div class="am-answer">
{{#Glossary}}<div class="am-definition">{{Glossary}}</div>{{/Glossary}}
{{^Glossary}}{{#MainDefinition}}<div class="am-definition">{{MainDefinition}}</div>{{/MainDefinition}}{{/Glossary}}
{{#Picture}}<div class="am-picture">{{Picture}}</div>{{/Picture}}
{{#ExpressionAudio}}<div class="am-audio">{{ExpressionAudio}}</div>{{/ExpressionAudio}}
{{#SentenceAudio}}<div class="am-audio">{{SentenceAudio}}</div>{{/SentenceAudio}}
<div class="am-meta">
{{#ExpressionReading}}<div class="am-meta-row"><span class="am-label">Expression</span> {{ExpressionReading}}</div>{{/ExpressionReading}}
{{#SentenceReading}}<div class="am-meta-row"><span class="am-label">Sentence</span> {{SentenceReading}}</div>{{/SentenceReading}}
{{#PitchGraph}}<div class="am-pitch">{{PitchGraph}}</div>{{/PitchGraph}}
{{#PitchText}}<div class="am-pitch">{{PitchText}}</div>{{/PitchText}}
{{#PitchPosition}}<div class="am-meta-row"><span class="am-label">Pitch</span> {{PitchPosition}}</div>{{/PitchPosition}}
{{#PitchCategory}}<div class="am-meta-row"><span class="am-label">Pitch type</span> {{PitchCategory}}</div>{{/PitchCategory}}
{{#Frequency}}<div class="am-meta-row"><span class="am-label">Frequency</span> {{Frequency}}</div>{{/Frequency}}
{{#Source}}<div class="am-meta-row"><span class="am-label">Source</span> {{Source}}</div>{{/Source}}
</div>
</div>"""

    const val CSS =
        """.card {
  box-sizing: border-box;
  max-width: 52rem;
  margin: 0 auto;
  padding: 1.25rem;
  background: #fff;
  color: #202124;
  font-family: sans-serif;
  font-size: 20px;
  line-height: 1.55;
  text-align: left;
}
.am-card { width: 100%; }
.am-expression { font-size: 2em; text-align: center; }
.am-sentence { margin-top: 0.8rem; font-size: 1.35em; text-align: center; }
.am-answer { width: 100%; }
.am-definition { margin-top: 1rem; }
.am-picture { margin: 1rem auto; text-align: center; }
.am-picture img { max-width: 100%; max-height: 45vh; object-fit: contain; }
.am-audio { margin-top: 0.75rem; text-align: center; }
.am-meta { margin-top: 1.25rem; color: #5f6368; font-size: 0.82em; }
.am-meta-row { margin-top: 0.35rem; }
.am-label { font-weight: 600; }
.am-pitch { margin-top: 0.6rem; text-align: center; }
rt { font-size: 0.55em; }
.nightMode.card, .night_mode.card { background: #202124; color: #f1f3f4; }
.nightMode.card .am-meta, .night_mode.card .am-meta { color: #bdc1c6; }
@media (max-width: 480px) {
  .card { padding: 0.8rem; font-size: 18px; }
}"""

    const val TEMPLATE_ORDINAL = 0
    const val TEMPLATE_COUNT = 1
    const val SORT_FIELD_INDEX = 0
    const val DEFAULT_DECK_ID = 1L
    const val MODEL_TYPE = 0

    /**
     * Digest persisted by the provisioning journal. It covers every value Anki Miner writes.
     * Provider-owned read-only LaTeX defaults are deliberately outside this contract.
     */
    val CONTRACT_SHA256: String =
        digestStrings(
            listOf(
                MODEL_NAME,
                DEFAULT_DECK_NAME,
                TEMPLATE_NAME,
                QUESTION_FORMAT,
                ANSWER_FORMAT,
                CSS,
                TEMPLATE_ORDINAL.toString(),
                TEMPLATE_COUNT.toString(),
                SORT_FIELD_INDEX.toString(),
                DEFAULT_DECK_ID.toString(),
                MODEL_TYPE.toString(),
            ) +
                FIELD_NAMES +
                ENGINE_FIELD_MAPPING.flatMap { (key, value) -> listOf(key, value) } +
                CARD_TYPE_MARKER_FIELDS.flatMap { (key, value) -> listOf(key, value) },
        )

    fun matchesControlledBase(snapshot: ModelSnapshot): Boolean =
        snapshot.name == MODEL_NAME &&
            snapshot.type == MODEL_TYPE &&
            snapshot.fieldNames == FIELD_NAMES &&
            snapshot.cardCount == TEMPLATE_COUNT &&
            snapshot.sortFieldIndex == SORT_FIELD_INDEX &&
            snapshot.css == CSS &&
            snapshot.templates.size == TEMPLATE_COUNT

    fun matchesExactly(snapshot: ModelSnapshot): Boolean {
        if (!matchesControlledBase(snapshot)) return false
        val template = snapshot.templates.single()
        return template.modelId == snapshot.id &&
            template.ordinal == TEMPLATE_ORDINAL &&
            template.name == TEMPLATE_NAME &&
            template.questionFormat == QUESTION_FORMAT &&
            template.answerFormat == ANSWER_FORMAT
    }

    /** Full provider snapshot digest used to detect edits between recovery attempts. */
    fun snapshotSha256(snapshot: ModelSnapshot): String =
        digestStrings(
            buildList {
                add(snapshot.id.toString())
                add(snapshot.name)
                add(snapshot.type.toString())
                addAll(snapshot.fieldNames)
                add(snapshot.cardCount.toString())
                add(snapshot.sortFieldIndex.toString())
                add(snapshot.effectiveDefaultDeckId.toString())
                add(snapshot.css)
                addNullable(snapshot.latexPre)
                addNullable(snapshot.latexPost)
                snapshot.templates.forEach { template ->
                    add(template.modelId.toString())
                    add(template.ordinal.toString())
                    add(template.name)
                    add(template.questionFormat)
                    add(template.answerFormat)
                    addNullable(template.browserQuestionFormat)
                    addNullable(template.browserAnswerFormat)
                }
            },
        )

    private fun MutableList<String>.addNullable(value: String?) {
        add(if (value == null) NULL_VALUE else "$PRESENT_VALUE$value")
    }

    private fun immutableOrderedMap(vararg entries: Pair<String, String>): Map<String, String> =
        Collections.unmodifiableMap(linkedMapOf(*entries))

    private fun digestStrings(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private const val NULL_VALUE = "0"
    private const val PRESENT_VALUE = "1"
}
