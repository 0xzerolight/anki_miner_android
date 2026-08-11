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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal data class AvailableUpdate(
    val version: String,
    val releasePageUrl: String,
)

internal sealed interface UpdateCheckResult {
    data class Available(val update: AvailableUpdate) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    data object Failure : UpdateCheckResult
}

internal fun interface UpdateCheckClient {
    /** Result of observing the newest published release relative to [currentVersion]. */
    fun latest(currentVersion: String): UpdateCheckResult
}

internal class GitHubUpdateCheckClient(
    connections: DownloadConnectionFactory,
) : UpdateCheckClient {
    private val connections =
        connections.withRequestProperty("Accept", "application/vnd.github+json")

    override fun latest(currentVersion: String): UpdateCheckResult {
        val connection = connections.open(LATEST_RELEASE_URL, 0L)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return UpdateCheckResult.Failure
            }
            val body =
                connection.inputStream.use { input ->
                    input.readAtMost(MAX_BODY_BYTES + 1)
                }
            if (body.size > MAX_BODY_BYTES) return UpdateCheckResult.Failure
            val release =
                try {
                    parseRelease(body)
                } catch (failure: IOException) {
                    AppLog.ignored(
                        LogComponent.SETTINGS,
                        "update.response-json",
                        "malformed update response rejected",
                        failure,
                    )
                    return UpdateCheckResult.Failure
                } ?: return UpdateCheckResult.Failure
            val version = release.tagName.removePrefix("v")
            if (!validVersion(version) || !validVersion(currentVersion)) {
                return UpdateCheckResult.Failure
            }
            if (!validReleasePage(release.htmlUrl)) return UpdateCheckResult.Failure
            if (!VersionCompare.isNewer(version, currentVersion)) {
                return UpdateCheckResult.UpToDate
            }
            return UpdateCheckResult.Available(AvailableUpdate(version, release.htmlUrl))
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

    private fun validVersion(value: String): Boolean = RELEASE_VERSION.matches(value)

    private data class GitHubRelease(
        val tagName: String,
        val htmlUrl: String,
    )

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/0xzerolight/anki_miner_android/releases/latest"
        const val MAX_BODY_BYTES = 256 * 1024
        val ALLOWED_RELEASE_HOSTS = setOf("github.com", "www.github.com")
        val RELEASE_VERSION = Regex("""[0-9]{1,9}(?:\.[0-9]{1,9}){0,7}""")
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
