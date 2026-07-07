package gg.grounds.push.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroundsForgeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GroundsForgeClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = GroundsForgeClient(server.url("/").toString().removeSuffix("/"), "test-token")
    }

    @AfterEach
    fun teardown() { server.shutdown() }

    private fun fakeJar(tmp: File): File {
        val f = File(tmp, "app.jar")
        // PK\x03\x04 header makes it pass server-side ZIP magic check (not enforced here)
        f.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(100))
        return f
    }

    @Test
    fun `createPush 202 parses response`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))
        val r = client.createPush("""{"name":"x","type":"gamemode","baseImage":"minestom"}""", "dev", fakeJar(tmp))
        assertEquals("p1", r.pushId)
        assertFalse(r.reused)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/pushes", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `project scope is appended to push endpoints`(@TempDir tmp: File) {
        val scopedClient = GroundsForgeClient(
            server.url("/").toString().removeSuffix("/"),
            "test-token",
            projectId = "project-1",
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"id":"p1","status":"building","target":"dev","baseImage":"paper","imageTag":null,"failureReason":null,"createdAt":"2026-04-24T10:00:00Z","updatedAt":"2026-04-24T10:00:01Z"}"""
        ))
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))

        scopedClient.createPush("{}", "dev", fakeJar(tmp))
        scopedClient.getPush("p1")
        scopedClient.retryPush("p1")

        assertEquals("/v1/pushes?projectId=project-1", server.takeRequest().path)
        assertEquals("/v1/pushes/p1?projectId=project-1", server.takeRequest().path)
        assertEquals("/v1/pushes/p1/retry?projectId=project-1", server.takeRequest().path)
    }

    @Test
    fun `project scope is appended after force query`(@TempDir tmp: File) {
        val scopedClient = GroundsForgeClient(
            server.url("/").toString().removeSuffix("/"),
            "test-token",
            projectId = "project-1",
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))

        scopedClient.createPush("{}", "dev", fakeJar(tmp), force = true)

        assertEquals("/v1/pushes?force=true&projectId=project-1", server.takeRequest().path)
    }

    @Test
    fun `createPush sends application gzip when uploading tar gz bundle`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))
        val bundle = File(tmp, "bundle.tar.gz")
        // gzip header magic — server-side magic-byte check is the
        // contract under test; the multipart Content-Type is
        // cosmetic and inspected separately below.
        bundle.writeBytes(byteArrayOf(0x1f, 0x8b.toByte()) + ByteArray(50))
        client.createPush("{}", "dev", bundle)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(
            body.contains("Content-Type: application/gzip"),
            "expected application/gzip part in multipart body, got:\n$body",
        )
    }

    @Test
    fun `createPush sends effective plugin sources when provided`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))
        val effectivePluginSourcesJson = """[{"id":"plugin-chat","effective":"local"}]"""

        client.createPush("{}", "dev", fakeJar(tmp), effectivePluginSourcesJson = effectivePluginSourcesJson)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("""name="effectivePluginSources""""), body)
        assertTrue(body.contains(effectivePluginSourcesJson), body)
    }

    @Test
    fun `createPush sends selected flavor when provided`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))

        client.createPush("{}", "dev", fakeJar(tmp), flavor = "velocity")

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("""name="flavor""""), body)
        assertTrue(body.contains("velocity"), body)
    }

    @Test
    fun `client normalizes trailing slash in api url`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
        ))
        val trailingSlashClient = GroundsForgeClient(server.url("/").toString(), "test-token")

        trailingSlashClient.createPush("""{"name":"x","type":"gamemode","baseImage":"minestom"}""", "dev", fakeJar(tmp))

        val recorded = server.takeRequest()
        assertEquals("/v1/pushes", recorded.path)
    }

    @Test
    fun `createPush 200 idempotent hit`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"pushId":"p1","status":"build_succeeded","reused":true,"logsUrl":null}"""
        ))
        val r = client.createPush("{}", "dev", fakeJar(tmp))
        assertTrue(r.reused)
    }

    @Test
    fun `createPush 400 surfaces ApiException with error body`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":"invalid_baseImage","message":"unknown baseImage 'bungeecord'","allowed":["paper","velocity"]}"""
        ))
        val e = assertThrows<GroundsForgeClient.ApiException> {
            client.createPush("{}", "dev", fakeJar(tmp))
        }
        assertEquals(400, e.statusCode)
        assertEquals("invalid_baseImage", e.errorBody?.error)
        assertNotNull(e.errorBody?.allowed)
    }

    @Test
    fun `createPush 413 jar too large`(@TempDir tmp: File) {
        server.enqueue(MockResponse().setResponseCode(413).setBody(
            """{"error":"jar_too_large","maxBytes":52428800}"""
        ))
        val e = assertThrows<GroundsForgeClient.ApiException> {
            client.createPush("{}", "dev", fakeJar(tmp))
        }
        assertEquals(413, e.statusCode)
    }

    @Test
    fun `getPush 200 parses build detail`() {
        server.enqueue(MockResponse().setBody(
            """{"id":"p1","status":"building","target":"dev","baseImage":"paper","imageTag":null,"failureReason":null,"createdAt":"2026-04-24T10:00:00Z","updatedAt":"2026-04-24T10:00:01Z","build":{"id":"b1","status":"running","kanikoJobName":"build-abc","startedAt":"2026-04-24T10:00:01Z","finishedAt":null}}"""
        ))
        val r = client.getPush("p1")
        assertEquals("building", r.status)
        assertEquals("b1", r.build?.id)
    }

    @Test
    fun `getPush 404 throws`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        val e = assertThrows<GroundsForgeClient.ApiException> { client.getPush("p1") }
        assertEquals(404, e.statusCode)
    }

    @Test
    fun `listBaseImages 200 parses catalog`() {
        server.enqueue(MockResponse().setBody(
            """{"items":[{"key":"paper","displayName":"Paper","manifestType":"plugin-paper","image":"ghcr.io/groundsgg/paper","versions":[{"version":"0.8.2","selectable":true}]}]}"""
        ))

        val catalog = client.listBaseImages()

        assertEquals("paper", catalog.items.single().key)
        assertEquals("/v1/base-images", server.takeRequest().path)
    }

    @Test
    fun `streamLogs receives status and done events`() {
        val sseBody = "event: status\ndata: {\"status\":\"building\"}\n\n" +
            "event: log\ndata: {\"ts\":\"2026-04-24T10:00:00Z\",\"line\":\"INFO: Executing Kaniko build\"}\n\n" +
            "event: status\ndata: {\"status\":\"build_succeeded\",\"imageTag\":\"zot/x:abc\"}\n\n" +
            "event: done\ndata: {}\n\n"
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody)
        )

        val statuses = mutableListOf<String>()
        val imageTags = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val doneLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        val failures = mutableListOf<String>()

        client.streamLogs("p1", object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {
                statuses += status; imageTags += imageTag
            }
            override fun onLog(ts: String, line: String) { logs += line }
            override fun onWarning(reason: String) {}
            override fun onDone() { doneLatch.countDown() }
            override fun onError(reason: String) {}
            override fun onStreamClosed(normal: Boolean) {
                if (!normal) failures += "stream closed abnormally"
                closedLatch.countDown()
            }
        })

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "done event not received; failures=$failures")
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "stream not closed")
        assertEquals(listOf("building", "build_succeeded"), statuses)
        assertEquals(listOf("INFO: Executing Kaniko build"), logs)
    }

    @Test
    fun `streamLogs appends project scope`() {
        val scopedClient = GroundsForgeClient(
            server.url("/").toString().removeSuffix("/"),
            "test-token",
            projectId = "project-1",
        )
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: done\ndata: {}\n\n")
        )

        val doneLatch = CountDownLatch(1)
        scopedClient.streamLogs("p1", object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {}
            override fun onLog(ts: String, line: String) {}
            override fun onWarning(reason: String) {}
            override fun onDone() { doneLatch.countDown() }
            override fun onError(reason: String) {}
            override fun onStreamClosed(normal: Boolean) {}
        })

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "done event not received")
        assertEquals("/v1/pushes/p1/logs?projectId=project-1", server.takeRequest().path)
    }

    @Test
    fun `streamLogs handles error event`() {
        val sseBody = "event: error\ndata: {\"reason\":\"token_expired\"}\n\n"
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody)
        )

        val errorLatch = CountDownLatch(1)
        val errors = mutableListOf<String>()
        client.streamLogs("p1", object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {}
            override fun onLog(ts: String, line: String) {}
            override fun onWarning(reason: String) {}
            override fun onDone() {}
            override fun onError(reason: String) { errors += reason; errorLatch.countDown() }
            override fun onStreamClosed(normal: Boolean) {}
        })
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
        assertTrue(errors[0].contains("stream_error"), errors[0])
        assertTrue(errors[0].contains("pushId=p1"), errors[0])
        assertTrue(errors[0].contains("reason=token_expired"), errors[0])
    }

    @Test
    fun `streamLogs reports malformed frames with pushId context`() {
        val sseBody = "event: status\ndata: {not-json}\n\n" +
            "event: done\ndata: {}\n\n"
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody)
        )

        val warningLatch = CountDownLatch(1)
        val warnings = mutableListOf<String>()
        client.streamLogs("p1", object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {}
            override fun onLog(ts: String, line: String) {}
            override fun onWarning(reason: String) { warnings += reason; warningLatch.countDown() }
            override fun onDone() {}
            override fun onError(reason: String) {}
            override fun onStreamClosed(normal: Boolean) {}
        })

        assertTrue(warningLatch.await(5, TimeUnit.SECONDS), "malformed frame warning not received")
        assertTrue(warnings[0].contains("malformed_sse_frame"), warnings[0])
        assertTrue(warnings[0].contains("pushId=p1"), warnings[0])
        assertTrue(warnings[0].contains("event=status"), warnings[0])
    }

    @Test
    fun `streamLogs reports failed stream response with pushId and status code`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("service unavailable"))

        val warningLatch = CountDownLatch(1)
        val warnings = mutableListOf<String>()
        client.streamLogs("p1", object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {}
            override fun onLog(ts: String, line: String) {}
            override fun onWarning(reason: String) { warnings += reason; warningLatch.countDown() }
            override fun onDone() {}
            override fun onError(reason: String) {}
            override fun onStreamClosed(normal: Boolean) {}
        })

        assertTrue(warningLatch.await(5, TimeUnit.SECONDS), "stream failure warning not received")
        assertTrue(warnings[0].contains("stream_failed"), warnings[0])
        assertTrue(warnings[0].contains("pushId=p1"), warnings[0])
        assertTrue(warnings[0].contains("statusCode=503"), warnings[0])
    }
}
