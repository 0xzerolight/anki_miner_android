package com.ankiminer.android.data.resources

import com.ankiminer.android.engine.BridgeJsonValue
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.json.JsonReadFeature
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/** Strict codec for the resource-only Python protocol and its immutable catalog. */
object ResourceBridgeCodec {
    private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    private val operationId = Regex("[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?")
    private val slotId = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    private val resourceId = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val messageType = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")

    private val factory: JsonFactory =
        JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_DOCUMENT_BYTES.toLong())
                    .maxStringLength(MAX_TEXT_BYTES)
                    .maxNameLength(256)
                    .maxNestingDepth(24)
                    .maxNumberLength(64)
                    .maxTokenCount(200_000)
                    .build(),
            ).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
            .build()

    fun encodeCatalogRequest(): String = encode("resource.catalog.get") {}

    fun encodeCleanupRequest(): String = encode("resource.cleanup") {}

    fun encodeDictionaryListRequest(): String = encode("resource.dictionary.list") {}

    fun encodeUniDicInstallRequest(
        operation: String,
        selectedResourceId: String,
        archivePath: String,
    ): String {
        requireOperationId(operation)
        requireResourceId(selectedResourceId)
        requireAbsolutePath(archivePath)
        return encode("resource.unidic.install") { generator ->
            generator.writeStringField("operationId", operation)
            generator.writeStringField("resourceId", selectedResourceId)
            generator.writeStringField("archivePath", archivePath)
        }
    }

    fun encodeDictionaryImportRequest(
        operation: String,
        sourcePath: String,
        selectedSlotId: String,
        overwrite: Boolean,
        catalogResourceId: String?,
    ): String {
        requireOperationId(operation)
        requireAbsolutePath(sourcePath)
        requireSlotId(selectedSlotId)
        catalogResourceId?.let(::requireResourceId)
        return encode("resource.dictionary.import") { generator ->
            generator.writeStringField("operationId", operation)
            generator.writeStringField("sourcePath", sourcePath)
            generator.writeStringField("slotId", selectedSlotId)
            generator.writeBooleanField("overwrite", overwrite)
            if (catalogResourceId == null) generator.writeNullField("catalogResourceId")
            else generator.writeStringField("catalogResourceId", catalogResourceId)
        }
    }

    fun encodeDictionaryLookupRequest(selectedSlotId: String, term: String): String {
        requireSlotId(selectedSlotId)
        require(term.isNotBlank() && term.toByteArray().size <= 1024)
        return encode("resource.dictionary.lookup") { generator ->
            generator.writeStringField("slotId", selectedSlotId)
            generator.writeStringField("term", term)
        }
    }

    fun encodeCancelRequest(operation: String): String {
        requireOperationId(operation)
        return encode("resource.operation.cancel") { generator ->
            generator.writeStringField("operationId", operation)
        }
    }

    fun decodeCatalog(raw: String): ResourceCatalog {
        val payload = payload(raw, "resource.catalog")
        exact(payload, setOf("schemaVersion", "resources"), "resource catalog")
        val schema = positive(payload.getValue("schemaVersion"), "catalog schema")
        val resources = array(payload.getValue("resources"), "catalog resources").map(::catalogResource)
        val catalog = ResourceCatalog(schema, resources)
        if (catalog != FrozenResourceCatalog.value) {
            throw ResourceBridgeException(
                "resource_catalog_mismatch",
                "Bundled Python resources do not match the Android catalog contract",
            )
        }
        return catalog
    }

    fun decodeInstalledUniDic(raw: String): InstalledUniDic {
        val value = payload(raw, "resource.unidic.installed")
        exact(
            value,
            setOf(
                "resourceId",
                "dicDir",
                "treeSha256",
                "fileCount",
                "sizeBytes",
                "alreadyInstalled",
                "attribution",
            ),
            "installed UniDic",
        )
        return InstalledUniDic(
            requireResourceId(text(value.getValue("resourceId"), "resourceId")),
            requireAbsolutePath(text(value.getValue("dicDir"), "dicDir")),
            requireSha256(text(value.getValue("treeSha256"), "treeSha256")),
            positive(value.getValue("fileCount"), "fileCount"),
            positive(value.getValue("sizeBytes"), "sizeBytes"),
            bool(value.getValue("alreadyInstalled"), "alreadyInstalled"),
            attributions(value.getValue("attribution")),
        ).also { installed ->
            val expected = FrozenResourceCatalog.value.unidic
            if (
                installed.resourceId != expected.resourceId ||
                    installed.treeSha256 != expected.install.treeSha256 ||
                    installed.fileCount != expected.install.fileCount ||
                    installed.sizeBytes != expected.install.sizeBytes ||
                    installed.attribution != expected.attribution
            ) {
                throw ResourceBridgeException("resource_identity_mismatch", "Installed UniDic identity is invalid")
            }
        }
    }

    fun decodeImportedDictionary(raw: String): ImportedDictionary {
        val value = payload(raw, "resource.dictionary.imported")
        exact(
            value,
            setOf(
                "slotId",
                "catalogResourceId",
                "sourceName",
                "sourceRevision",
                "entryCount",
                "skippedMalformed",
                "mediaWarnings",
                "archiveSha256",
                "attribution",
            ),
            "imported dictionary",
        )
        return ImportedDictionary(
            requireSlotId(text(value.getValue("slotId"), "slotId")),
            nullableText(value.getValue("catalogResourceId"), "catalogResourceId")?.let(::requireResourceId),
            boundedText(value.getValue("sourceName"), "sourceName", 4096),
            boundedText(value.getValue("sourceRevision"), "sourceRevision", 4096),
            nonNegative(value.getValue("entryCount"), "entryCount"),
            nonNegative(value.getValue("skippedMalformed"), "skippedMalformed"),
            strings(value.getValue("mediaWarnings"), "mediaWarnings", 4096),
            requireSha256(text(value.getValue("archiveSha256"), "archiveSha256")),
            attributions(value.getValue("attribution"), allowEmpty = true),
        )
    }

    fun decodeDictionaryList(raw: String): List<InstalledDictionary> {
        val payload = payload(raw, "resource.dictionary.listed")
        exact(payload, setOf("dictionaries"), "dictionary list")
        val values = array(payload.getValue("dictionaries"), "dictionaries")
        if (values.size > 128) invalid("Too many installed dictionaries")
        val decoded = values.map(::installedDictionary)
        if (decoded.map { it.slotId }.distinct().size != decoded.size) invalid("Duplicate dictionary slot")
        return decoded
    }

    fun decodeLookup(raw: String): DictionaryLookup {
        val payload = payload(raw, "resource.dictionary.lookup.result")
        exact(payload, setOf("slotId", "term", "html"), "dictionary lookup")
        val html = text(payload.getValue("html"), "lookup html")
        if (html.toByteArray().size > 2 * 1024 * 1024) invalid("Dictionary HTML exceeds its limit")
        return DictionaryLookup(
            requireSlotId(text(payload.getValue("slotId"), "slotId")),
            boundedText(payload.getValue("term"), "term", 1024),
            html,
        )
    }

    fun decodeCancelAccepted(raw: String, expectedOperationId: String): Boolean {
        val payload = payload(raw, "resource.operation.cancel.result")
        exact(payload, setOf("operationId", "accepted"), "resource cancel")
        if (text(payload.getValue("operationId"), "operationId") != expectedOperationId) {
            invalid("Cancellation response belongs to another operation")
        }
        return bool(payload.getValue("accepted"), "accepted")
    }

    fun decodeCleanup(raw: String) {
        val payload = payload(raw, "resource.cleanup.result")
        exact(payload, setOf("clean"), "resource cleanup")
        if (!bool(payload.getValue("clean"), "clean")) invalid("Resource cleanup was not confirmed")
    }

    private fun catalogResource(raw: BridgeJsonValue): CatalogResource {
        val value = objectValue(raw, "catalog resource")
        val kind = text(value["kind"] ?: invalid("Catalog resource kind is missing"), "kind")
        return when (kind) {
            "unidic" -> {
                exact(
                    value,
                    setOf("resourceId", "kind", "displayName", "archive", "install", "attribution"),
                    "UniDic catalog resource",
                )
                UniDicCatalogResource(
                    requireResourceId(text(value.getValue("resourceId"), "resourceId")),
                    boundedText(value.getValue("displayName"), "displayName", 256),
                    archive(value.getValue("archive"), "tar.gz"),
                    unidicInstall(value.getValue("install")),
                    attributions(value.getValue("attribution")),
                )
            }
            "yomitan-dictionary" -> {
                exact(
                    value,
                    setOf("resourceId", "kind", "displayName", "slotId", "archive", "dictionary", "attribution"),
                    "Yomitan catalog resource",
                )
                YomitanCatalogResource(
                    requireResourceId(text(value.getValue("resourceId"), "resourceId")),
                    boundedText(value.getValue("displayName"), "displayName", 256),
                    requireSlotId(text(value.getValue("slotId"), "slotId")),
                    archive(value.getValue("archive"), "zip"),
                    yomitanIdentity(value.getValue("dictionary")),
                    attributions(value.getValue("attribution")),
                )
            }
            else -> invalid("Unsupported resource kind")
        }
    }

    private fun archive(raw: BridgeJsonValue, expectedFormat: String): ResourceArchive {
        val value = objectValue(raw, "archive")
        exact(value, setOf("url", "sha256", "sizeBytes", "format"), "archive")
        val format = text(value.getValue("format"), "archive format")
        if (format != expectedFormat) invalid("Archive format is invalid")
        return ResourceArchive(
            requireHttpsUrl(text(value.getValue("url"), "archive URL")),
            requireSha256(text(value.getValue("sha256"), "archive sha256")),
            positive(value.getValue("sizeBytes"), "archive size"),
            format,
        )
    }

    private fun unidicInstall(raw: BridgeJsonValue): UniDicInstallIdentity {
        val value = objectValue(raw, "UniDic install")
        exact(
            value,
            setOf("memberPrefix", "treeSha256", "fileCount", "sizeBytes", "archiveMemberLimit"),
            "UniDic install",
        )
        return UniDicInstallIdentity(
            boundedText(value.getValue("memberPrefix"), "memberPrefix", 256),
            requireSha256(text(value.getValue("treeSha256"), "treeSha256")),
            positive(value.getValue("fileCount"), "fileCount"),
            positive(value.getValue("sizeBytes"), "sizeBytes"),
            positive(value.getValue("archiveMemberLimit"), "archiveMemberLimit"),
        )
    }

    private fun yomitanIdentity(raw: BridgeJsonValue): YomitanDictionaryIdentity {
        val value = objectValue(raw, "Yomitan identity")
        exact(
            value,
            setOf(
                "title",
                "revision",
                "format",
                "memberCount",
                "uncompressedBytes",
                "archiveMemberLimit",
                "uncompressedBytesLimit",
                "fileBytesLimit",
            ),
            "Yomitan identity",
        )
        return YomitanDictionaryIdentity(
            boundedText(value.getValue("title"), "title", 512),
            boundedText(value.getValue("revision"), "revision", 128),
            positive(value.getValue("format"), "format"),
            positive(value.getValue("memberCount"), "memberCount"),
            positive(value.getValue("uncompressedBytes"), "uncompressedBytes"),
            positive(value.getValue("archiveMemberLimit"), "archiveMemberLimit"),
            positive(value.getValue("uncompressedBytesLimit"), "uncompressedBytesLimit"),
            positive(value.getValue("fileBytesLimit"), "fileBytesLimit"),
        )
    }

    private fun attributions(raw: BridgeJsonValue, allowEmpty: Boolean = false): List<ResourceAttribution> {
        val values = array(raw, "attribution")
        if ((!allowEmpty && values.isEmpty()) || values.size > 32) invalid("Attribution list has an invalid size")
        return values.map { entry ->
            val value = objectValue(entry, "attribution entry")
            exact(value, setOf("name", "copyright", "license", "url"), "attribution entry")
            ResourceAttribution(
                boundedText(value.getValue("name"), "name", 256),
                boundedText(value.getValue("copyright"), "copyright", 512),
                boundedText(value.getValue("license"), "license", 64),
                requireHttpsUrl(text(value.getValue("url"), "attribution URL")),
            )
        }
    }

    private fun installedDictionary(raw: BridgeJsonValue): InstalledDictionary {
        val value = objectValue(raw, "installed dictionary")
        exact(
            value,
            setOf(
                "slotId",
                "sourceName",
                "sourceRevision",
                "format",
                "entryCount",
                "schemaOk",
                "embeddedAttribution",
                "catalogResourceId",
                "attribution",
            ),
            "installed dictionary",
        )
        val embedded = objectValue(value.getValue("embeddedAttribution"), "embedded attribution")
        if (!setOf("author", "attribution", "description").containsAll(embedded.keys)) {
            invalid("Embedded attribution contains unknown fields")
        }
        return InstalledDictionary(
            requireSlotId(text(value.getValue("slotId"), "slotId")),
            boundedText(value.getValue("sourceName"), "sourceName", 4096),
            boundedText(value.getValue("sourceRevision"), "sourceRevision", 4096, allowEmpty = true),
            positive(value.getValue("format"), "format"),
            nonNegative(value.getValue("entryCount"), "entryCount"),
            bool(value.getValue("schemaOk"), "schemaOk"),
            embedded.mapValues { (_, child) -> boundedText(child, "embedded attribution", 64 * 1024) },
            nullableText(value.getValue("catalogResourceId"), "catalogResourceId")?.let(::requireResourceId),
            attributions(value.getValue("attribution"), allowEmpty = true),
        )
    }

    private fun payload(raw: String, expectedType: String): Map<String, BridgeJsonValue> {
        val envelope = decodeEnvelope(raw)
        if (envelope.first == "bridge.error") {
            val error = envelope.second
            if (error.keys !in setOf(setOf("code", "message"), setOf("code", "message", "requestType"))) {
                invalid("Bridge error payload is invalid")
            }
            throw ResourceBridgeException(
                text(error.getValue("code"), "error code"),
                boundedText(error.getValue("message"), "error message", 16 * 1024),
            )
        }
        if (envelope.first != expectedType) invalid("Unexpected resource response type")
        return envelope.second
    }

    private fun decodeEnvelope(raw: String): Pair<String, Map<String, BridgeJsonValue>> {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_DOCUMENT_BYTES) invalid("Resource response is too large")
        try {
            factory.createParser(raw).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) invalid("Bridge envelope must be an object")
                val root = readObject(parser, "bridge envelope")
                if (parser.nextToken() != null) invalid("Bridge response has trailing JSON")
                exact(root, setOf("schemaVersion", "type", "payload"), "bridge envelope")
                if (positive(root.getValue("schemaVersion"), "schemaVersion") != 1L) invalid("Unsupported bridge schema")
                val type = text(root.getValue("type"), "message type")
                if (!messageType.matches(type)) invalid("Invalid message type")
                return type to objectValue(root.getValue("payload"), "bridge payload")
            }
        } catch (failure: ResourceBridgeException) {
            throw failure
        } catch (failure: JsonParseException) {
            throw ResourceBridgeException("invalid_resource_json", "Resource bridge returned malformed JSON")
        } catch (failure: Exception) {
            throw ResourceBridgeException("invalid_resource_json", "Resource bridge returned invalid JSON")
        }
    }

    private fun readObject(parser: JsonParser, context: String): Map<String, BridgeJsonValue> {
        val result = linkedMapOf<String, BridgeJsonValue>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) invalid("$context contains invalid JSON")
            val name = parser.currentName
            parser.nextToken() ?: invalid("$context is incomplete")
            result[name] = readValue(parser, context)
        }
        return result
    }

    private fun readValue(parser: JsonParser, context: String): BridgeJsonValue =
        when (parser.currentToken()) {
            JsonToken.START_OBJECT -> BridgeJsonValue.ObjectValue(readObject(parser, context))
            JsonToken.START_ARRAY -> {
                val values = mutableListOf<BridgeJsonValue>()
                while (parser.nextToken() != JsonToken.END_ARRAY) values += readValue(parser, context)
                BridgeJsonValue.ArrayValue(values)
            }
            JsonToken.VALUE_STRING -> BridgeJsonValue.Text(parser.text)
            JsonToken.VALUE_TRUE -> BridgeJsonValue.Bool(true)
            JsonToken.VALUE_FALSE -> BridgeJsonValue.Bool(false)
            JsonToken.VALUE_NULL -> BridgeJsonValue.Null
            JsonToken.VALUE_NUMBER_INT -> {
                val number = parser.bigIntegerValue
                if (number.bitLength() > 63) invalid("Resource integer is outside signed-64")
                BridgeJsonValue.Integer(number.toLong())
            }
            else -> invalid("$context contains an unsupported JSON value")
        }

    private fun encode(type: String, writer: (JsonGenerator) -> Unit): String {
        val output = ByteArrayOutputStream()
        factory.createGenerator(output).use { generator ->
            generator.writeStartObject()
            generator.writeNumberField("schemaVersion", 1)
            generator.writeStringField("type", type)
            generator.writeObjectFieldStart("payload")
            writer(generator)
            generator.writeEndObject()
            generator.writeEndObject()
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun exact(actual: Map<String, BridgeJsonValue>, expected: Set<String>, context: String) {
        if (actual.keys != expected) invalid("$context has missing or unknown fields")
    }

    private fun objectValue(value: BridgeJsonValue, context: String) =
        (value as? BridgeJsonValue.ObjectValue)?.values ?: invalid("$context must be an object")

    private fun array(value: BridgeJsonValue, context: String) =
        (value as? BridgeJsonValue.ArrayValue)?.values ?: invalid("$context must be an array")

    private fun text(value: BridgeJsonValue, context: String) =
        (value as? BridgeJsonValue.Text)?.value ?: invalid("$context must be text")

    private fun nullableText(value: BridgeJsonValue, context: String) =
        if (value is BridgeJsonValue.Null) null else text(value, context)

    private fun boundedText(
        value: BridgeJsonValue,
        context: String,
        bytes: Int,
        allowEmpty: Boolean = false,
    ): String =
        text(value, context).also {
            if ((!allowEmpty && it.isEmpty()) || it.toByteArray().size > bytes) invalid("$context exceeds its limit")
        }

    private fun bool(value: BridgeJsonValue, context: String) =
        (value as? BridgeJsonValue.Bool)?.value ?: invalid("$context must be a boolean")

    private fun nonNegative(value: BridgeJsonValue, context: String): Long =
        (value as? BridgeJsonValue.Integer)?.value?.also { if (it < 0) invalid("$context must be non-negative") }
            ?: invalid("$context must be an integer")

    private fun positive(value: BridgeJsonValue, context: String): Long =
        nonNegative(value, context).also { if (it == 0L) invalid("$context must be positive") }

    private fun strings(value: BridgeJsonValue, context: String, maxItemBytes: Int): List<String> =
        array(value, context).map { boundedText(it, "$context item", maxItemBytes, allowEmpty = true) }

    private fun requireOperationId(value: String): String = value.also { require(operationId.matches(it)) }

    private fun requireSlotId(value: String): String = value.also { if (!slotId.matches(it)) invalid("Invalid dictionary slot") }

    private fun requireResourceId(value: String): String = value.also { if (!resourceId.matches(it)) invalid("Invalid resource id") }

    private fun requireSha256(value: String): String = value.also { if (!sha256.matches(it)) invalid("Invalid SHA-256") }

    private fun requireAbsolutePath(value: String): String = value.also { if (!it.startsWith('/') || '\u0000' in it) invalid("Invalid absolute path") }

    private fun requireHttpsUrl(value: String): String =
        value.also {
            val uri = try {
                URI(it)
            } catch (_: Exception) {
                invalid("Invalid HTTPS URL")
            }
            if (
                uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                    uri.fragment != null || !uri.isAbsolute
            ) {
                invalid("Invalid HTTPS URL")
            }
        }

    private fun invalid(message: String): Nothing =
        throw ResourceBridgeException("invalid_resource_response", message)
}

/** Kotlin copy of the frozen Python catalog. Equality is checked before network access. */
object FrozenResourceCatalog {
    val value =
        ResourceCatalog(
            schemaVersion = 1,
            resources =
                listOf(
                    UniDicCatalogResource(
                        resourceId = "unidic-lite-1.0.8",
                        displayName = "UniDic Lite 1.0.8",
                        archive =
                            ResourceArchive(
                                url = "https://files.pythonhosted.org/packages/55/2b/8cf7514cb57d028abcef625afa847d60ff1ffbf0049c36b78faa7c35046f/unidic-lite-1.0.8.tar.gz",
                                sha256 = "db9d4572d9fdd4d00a97949d4b0741ec480ee05a7e7e2e32f547500dae27b245",
                                sizeBytes = 47_356_746,
                                format = "tar.gz",
                            ),
                        install =
                            UniDicInstallIdentity(
                                memberPrefix = "unidic-lite-1.0.8/unidic_lite/dicdir/",
                                treeSha256 = "bd942f1b395aa7c56fe20321dc7f021930e29107f6b2949a49f5c56caab55ea7",
                                fileCount = 19,
                                sizeBytes = 260_467_176,
                                archiveMemberLimit = 128,
                            ),
                        attribution =
                            listOf(
                                ResourceAttribution(
                                    "unidic-lite",
                                    "Copyright 2020 Paul McCann",
                                    "MIT",
                                    "https://github.com/polm/unidic-lite",
                                ),
                                ResourceAttribution(
                                    "UniDic 2.1.2",
                                    "Copyright (c) 2011-2017, The UniDic Consortium",
                                    "BSD-3-Clause",
                                    "https://unidic.ninjal.ac.jp/",
                                ),
                            ),
                    ),
                    YomitanCatalogResource(
                        resourceId = "jitendex-2026.07.09.0",
                        displayName = "Jitendex.org 2026-07-09",
                        slotId = "jitendex",
                        archive =
                            ResourceArchive(
                                url = "https://github.com/stephenmk/stephenmk.github.io/releases/download/2026.07.09.0/jitendex-yomitan.zip",
                                sha256 = "807d911114af9d2154d270702972aafb2b6a6c2dc2400afa98db870d035c1a0b",
                                sizeBytes = 38_545_572,
                                format = "zip",
                            ),
                        dictionary =
                            YomitanDictionaryIdentity(
                                title = "Jitendex.org [2026-07-09]",
                                revision = "2026.07.09.0",
                                format = 3,
                                memberCount = 473,
                                uncompressedBytes = 540_565_403,
                                archiveMemberLimit = 4096,
                                uncompressedBytesLimit = 2_147_483_648,
                                fileBytesLimit = 16_777_216,
                            ),
                        attribution =
                            listOf(
                                ResourceAttribution("Jitendex", "Copyright Stephen Kraus 2023-2026", "CC-BY-SA-4.0", "https://jitendex.org/pages/legal.html"),
                                ResourceAttribution("JMdict", "Electronic Dictionary Research and Development Group", "EDRDG-Licence", "https://www.edrdg.org/edrdg/licence.html"),
                                ResourceAttribution("Tatoeba example sentences", "Tatoeba contributors", "CC-BY-2.0-FR", "https://tatoeba.org/en/downloads"),
                                ResourceAttribution("Kanji alive pronunciation audio", "Kanji alive project contributors", "CC-BY-4.0", "https://github.com/kanjialive/kanji-data-media"),
                                ResourceAttribution("JmdictFurigana", "JmdictFurigana contributors", "CC-BY-SA-4.0", "https://github.com/Doublevil/JmdictFurigana"),
                            ),
                    ),
                ),
        )
}
