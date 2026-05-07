package gg.grounds.push.source

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubReleaseFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: GitHubReleaseFetcher

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        fetcher = GitHubReleaseFetcher(
            httpClient = OkHttpClient(),
            apiBase = server.url("/").toString().removeSuffix("/"),
        )
    }

    @AfterEach
    fun teardown() { server.shutdown() }

    private fun jarBytes(payload: String): ByteArray =
        byteArrayOf(0x50, 0x4b, 0x03, 0x04) + payload.toByteArray()

    private fun queueRelease(assetName: String, downloadPath: String) {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [
                    {
                      "name": "$assetName",
                      "url": "${server.url("/$downloadPath")}",
                      "size": 100
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
    }

    private fun queueAssetBinary(payload: ByteArray) {
        val buf = Buffer().apply { write(payload) }
        server.enqueue(MockResponse().setBody(buf))
    }

    private val sampleRef = SourceRef.GitHubRelease(
        raw = "github:groundsgg/plugin-test@v1.0.0",
        owner = "groundsgg",
        repo = "plugin-test",
        tag = "v1.0.0",
        asset = null,
    )

    @Test
    fun `downloads first jar asset when none specified`(@TempDir tmp: File) {
        queueRelease("plugin-test-1.0.0.jar", "asset/abc")
        queueAssetBinary(jarBytes("HELLO"))

        val out = fetcher.fetch(sampleRef, tmp, token = null)
        assertTrue(out.isFile)
        val bytes = out.readBytes()
        assertEquals("HELLO", String(bytes.copyOfRange(4, bytes.size)))
    }

    @Test
    fun `cache hit avoids second network call`(@TempDir tmp: File) {
        queueRelease("plugin-test-1.0.0.jar", "asset/abc")
        queueAssetBinary(jarBytes("HELLO"))

        fetcher.fetch(sampleRef, tmp, null)
        // 2 enqueued + nothing more — second fetch must hit cache.
        fetcher.fetch(sampleRef, tmp, null)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `picks named asset when specified`(@TempDir tmp: File) {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [
                    { "name": "wrong.jar",  "url": "${server.url("/wrong")}",  "size": 1 },
                    { "name": "right.jar",  "url": "${server.url("/right")}",  "size": 1 }
                  ]
                }
                """.trimIndent(),
            ),
        )
        queueAssetBinary(jarBytes("RIGHT"))

        val out = fetcher.fetch(sampleRef.copy(asset = "right.jar"), tmp, null)
        val bytes = out.readBytes()
        assertEquals("RIGHT", String(bytes.copyOfRange(4, bytes.size)))
    }

    @Test
    fun `404 with no token surfaces auth hint`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows<GitHubReleaseFetchException> {
            fetcher.fetch(sampleRef, tmp, null)
        }
        assertTrue(e.message!!.contains("GITHUB_TOKEN"), e.message!!)
    }

    @Test
    fun `404 with token surfaces missing release without auth hint`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows<GitHubReleaseFetchException> {
            fetcher.fetch(sampleRef, tmp, token = "tok")
        }
        assertTrue(e.message!!.contains("not found"), e.message!!)
        assertTrue(!e.message!!.contains("GITHUB_TOKEN"), e.message!!)
    }

    @Test
    fun `unknown asset name fails clearly`(@TempDir tmp: File) {
        queueRelease("plugin-test-1.0.0.jar", "asset/abc")
        val e = assertThrows<GitHubReleaseFetchException> {
            fetcher.fetch(sampleRef.copy(asset = "different.jar"), tmp, null)
        }
        assertTrue(e.message!!.contains("not found"), e.message!!)
        assertTrue(e.message!!.contains("plugin-test-1.0.0.jar"), e.message!!)
    }

    @Test
    fun `release without any jar asset fails`(@TempDir tmp: File) {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [
                    { "name": "release.zip", "url": "${server.url("/zip")}", "size": 1 }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val e = assertThrows<GitHubReleaseFetchException> {
            fetcher.fetch(sampleRef, tmp, null)
        }
        assertTrue(e.message!!.contains("no .jar"), e.message!!)
    }

    @Test
    fun `verifies sha256 sidecar when present`(@TempDir tmp: File) {
        val payload = jarBytes("VERIFIED")
        val expectedSha = sha256Hex(payload)

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [
                    { "name": "plugin.jar",        "url": "${server.url("/jar")}",  "size": ${payload.size} },
                    { "name": "plugin.jar.sha256", "url": "${server.url("/sha")}",  "size": 64 }
                  ]
                }
                """.trimIndent(),
            ),
        )
        queueAssetBinary(payload)
        server.enqueue(MockResponse().setBody("$expectedSha  plugin.jar\n"))

        val out = fetcher.fetch(sampleRef.copy(asset = "plugin.jar"), tmp, null)
        assertTrue(out.isFile)
    }

    @Test
    fun `mismatched sha256 deletes file and throws`(@TempDir tmp: File) {
        val payload = jarBytes("OK")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [
                    { "name": "plugin.jar",        "url": "${server.url("/jar")}",  "size": ${payload.size} },
                    { "name": "plugin.jar.sha256", "url": "${server.url("/sha")}",  "size": 64 }
                  ]
                }
                """.trimIndent(),
            ),
        )
        queueAssetBinary(payload)
        server.enqueue(MockResponse().setBody("0".repeat(64) + "  plugin.jar\n"))

        val e = assertThrows<GitHubReleaseFetchException> {
            fetcher.fetch(sampleRef.copy(asset = "plugin.jar"), tmp, null)
        }
        assertTrue(e.message!!.contains("SHA256 mismatch"), e.message!!)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
