package com.ankiminer.android.data.update

import com.ankiminer.android.data.resources.HttpsDownloadConnectionFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckClientTest {
    @Test
    fun `a newer tag becomes an available update`() {
        val result =
            latest(
                body =
                    """{"tag_name":"v0.5.0","html_url":"https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.5.0"}""",
            )

        assertEquals(
            AvailableUpdate(
                version = "0.5.0",
                releasePageUrl =
                    "https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.5.0",
            ),
            result,
        )
    }

    @Test
    fun `the installed version is not an update`() {
        val result =
            latest(
                body =
                    """{"tag_name":"v0.4.1","html_url":"https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.4.1"}""",
            )

        assertNull(result)
    }

    @Test
    fun `an older tag is not an update`() {
        val result =
            latest(
                body =
                    """{"tag_name":"v0.4.0","html_url":"https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.4.0"}""",
            )

        assertNull(result)
    }

    @Test
    fun `a release page on another host is refused`() {
        val result =
            latest(
                body =
                    """{"tag_name":"v0.5.0","html_url":"https://evil.example/releases"}""",
            )

        assertNull(result)
    }

    @Test
    fun `a non-https release page is refused`() {
        val result =
            latest(
                body =
                    """{"tag_name":"v0.5.0","html_url":"http://github.com/x"}""",
            )

        assertNull(result)
    }

    @Test
    fun `a non-200 response yields nothing`() {
        val result = latest(body = "forbidden", status = 403)

        assertNull(result)
    }

    @Test
    fun `an oversized body is abandoned rather than buffered`() {
        val result = latest(body = "x".repeat(300 * 1024))

        assertNull(result)
    }

    @Test
    fun `a body that is not an object yields nothing`() {
        val result = latest(body = "[]")

        assertNull(result)
    }

    private fun latest(
        body: String,
        status: Int = HttpURLConnection.HTTP_OK,
        currentVersion: String = "0.4.1",
    ): AvailableUpdate? {
        val connection = FakeConnection(status, ByteArrayInputStream(body.toByteArray()))
        var openedUrl: String? = null
        val client =
            GitHubUpdateCheckClient(
                HttpsDownloadConnectionFactory(
                    connectionOpener = { url ->
                        openedUrl = url.toString()
                        connection
                    },
                ),
            )

        val result = client.latest(currentVersion)

        assertEquals(LATEST_RELEASE_URL, openedUrl)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertNull(connection.getRequestProperty("Range"))
        assertTrue(connection.disconnected)
        return result
    }

    private class FakeConnection(
        private val status: Int,
        private val body: InputStream,
    ) : HttpURLConnection(URL("https://api.github.com/releases/latest")) {
        private val requestProperties = mutableMapOf<String, String?>()
        private var responseRead = false
        var disconnected = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun setRequestProperty(
            key: String?,
            value: String?,
        ) {
            check(!responseRead) { "request property set after response access" }
            if (key != null) requestProperties[key] = value
        }

        override fun getRequestProperty(key: String?): String? = requestProperties[key]

        override fun getResponseCode(): Int {
            responseRead = true
            return status
        }

        override fun getInputStream(): InputStream = body
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/0xzerolight/anki_miner_android/releases/latest"
    }
}
