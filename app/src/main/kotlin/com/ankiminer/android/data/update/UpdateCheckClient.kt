package com.ankiminer.android.data.update

import com.ankiminer.android.data.resources.DownloadConnectionFactory
import com.ankiminer.android.data.settings.readAtMost
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.json.JsonReadFeature
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal data class AvailableUpdate(
    val version: String,
    val releasePageUrl: String,
)

internal fun interface UpdateCheckClient {
    /** The newest published release strictly newer than [currentVersion], or null. */
    fun latest(currentVersion: String): AvailableUpdate?
}

internal class GitHubUpdateCheckClient(
    connections: DownloadConnectionFactory,
) : UpdateCheckClient {
    private val connections =
        connections.withRequestProperty("Accept", "application/vnd.github+json")

    override fun latest(currentVersion: String): AvailableUpdate? {
        val connection = connections.open(LATEST_RELEASE_URL, 0L)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body =
                connection.inputStream.use { input ->
                    input.readAtMost(MAX_BODY_BYTES + 1)
                }
            if (body.size > MAX_BODY_BYTES) return null
            val release = parseRelease(body) ?: return null
            val version = release.tagName.removePrefix("v")
            if (!validReleasePage(release.htmlUrl)) return null
            if (!VersionCompare.isNewer(version, currentVersion)) return null
            return AvailableUpdate(version, release.htmlUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(body: ByteArray): GitHubRelease? =
        JSON_FACTORY.createParser(body).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return null
            var tagName: String? = null
            var htmlUrl: String? = null
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken != JsonToken.FIELD_NAME) return null
                val fieldName = parser.currentName()
                val valueToken = parser.nextToken() ?: return null
                when (fieldName) {
                    "tag_name" -> {
                        if (valueToken != JsonToken.VALUE_STRING) return null
                        tagName = parser.text
                    }
                    "html_url" -> {
                        if (valueToken != JsonToken.VALUE_STRING) return null
                        htmlUrl = parser.text
                    }
                    else -> parser.skipChildren()
                }
            }
            if (parser.nextToken() != null) return null
            GitHubRelease(
                tagName = tagName ?: return null,
                htmlUrl = htmlUrl ?: return null,
            )
        }

    private fun validReleasePage(value: String): Boolean {
        val uri =
            try {
                URI(value)
            } catch (failure: URISyntaxException) {
                AppLog.ignored(
                    LogComponent.SETTINGS,
                    "update.release-url",
                    "malformed untrusted release URL rejected",
                    failure,
                )
                return false
            }
        return uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.host?.lowercase(Locale.ROOT) in ALLOWED_RELEASE_HOSTS
    }

    private data class GitHubRelease(
        val tagName: String,
        val htmlUrl: String,
    )

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/0xzerolight/anki_miner_android/releases/latest"
        const val MAX_BODY_BYTES = 256 * 1024
        val ALLOWED_RELEASE_HOSTS = setOf("github.com", "www.github.com")
        val JSON_FACTORY: JsonFactory =
            JsonFactoryBuilder()
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(16)
                        .maxDocumentLength(MAX_BODY_BYTES.toLong())
                        .maxStringLength(MAX_BODY_BYTES)
                        .build(),
                )
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
                .build()
    }
}
