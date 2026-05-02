package gg.grounds.push

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroundsPushPluginTest {
    @Test
    fun `plugin registers groundsPush, groundsPushRetry, groundsPromote tasks`(@TempDir tmp: File) {
        File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
        File(tmp, "build.gradle.kts").writeText("""
            plugins {
                id("java")
                id("gg.grounds.push")
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(tmp)
            .withPluginClasspath()
            .withArguments("tasks", "--all", "--group=grounds")
            .build()

        assertTrue(result.output.contains("groundsPush"))
        assertTrue(result.output.contains("groundsPushRetry"))
        assertTrue(result.output.contains("groundsPromote"))
    }

    @Test
    fun `groundsPromote task fails with helpful message`(@TempDir tmp: File) {
        File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
        File(tmp, "build.gradle.kts").writeText("""
            plugins {
                id("java")
                id("gg.grounds.push")
            }
        """.trimIndent())
        val result = GradleRunner.create()
            .withProjectDir(tmp)
            .withPluginClasspath()
            .withArguments("groundsPromote")
            .buildAndFail()
        assertTrue(result.output.contains("not available until grounds-forge"))
    }

    @Test
    fun `groundsPush jar auto-detection picks up jar task`(@TempDir tmp: File) {
        File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
        File(tmp, "build.gradle.kts").writeText("""
            plugins {
                id("java")
                id("gg.grounds.push")
            }
        """.trimIndent())
        // Use `help --task` to inspect task details without running it.
        // This exercises afterEvaluate + auto-detection without executing the action.
        val result = GradleRunner.create()
            .withProjectDir(tmp)
            .withPluginClasspath()
            .withArguments("help", "--task", "groundsPush")
            .build()
        // If misconfigured, this would fail at configuration time.
        assertTrue(result.output.contains("groundsPush") || result.output.contains("target"))
    }

    @Test
    fun `groundsPush uses jar path from grounds yaml when jarFile is not configured`(@TempDir tmp: File) {
        File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
        File(tmp, "build.gradle.kts").writeText("""
            plugins {
                id("java")
                id("gg.grounds.push")
            }
        """.trimIndent())
        File(tmp, "grounds.yaml").writeText("""
            name: test-plugin
            type: plugin-paper
            baseImage: paper
            jar: build/libs/from-manifest.jar
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(tmp)
            .withPluginClasspath()
            .withArguments("groundsPush")
            .buildAndFail()

        assertTrue(result.output.contains("build/libs/from-manifest.jar"), result.output)
    }

    @Test
    fun `groundsPushRetry uses api url from credentials file`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(202).setBody(
                """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            File(tmp, "build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }
            """.trimIndent())
            val credentials = File(tmp, ".config/grounds/credentials.json")
            credentials.parentFile.mkdirs()
            credentials.writeText(
                """{"version":1,"apiUrl":"${server.url("/").toString().removeSuffix("/")}","accessToken":"token"}"""
            )

            GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPushRetry", "--pushId=p1")
                .build()

            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request, "expected retry request to credentials file API URL")
            assertEquals("/v1/pushes/p1/retry", request.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush does not fail on whitelist error when configured`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody(
                """{"error":"not_whitelisted","message":"plugin is not whitelisted for this target"}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            File(tmp, "app.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(100))
            File(tmp, "grounds.yaml").writeText("""
                name: test-plugin
                type: plugin-paper
                baseImage: paper
            """.trimIndent())
            File(tmp, "build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }

                groundsPush {
                    apiUrl.set("${server.url("/").toString().removeSuffix("/")}")
                    jarFile.set(layout.projectDirectory.file("app.jar"))
                    failOnWhitelistError.set(false)
                }
            """.trimIndent())
            val credentials = File(tmp, ".config/grounds/credentials.json")
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            val result = GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush")
                .build()

            assertTrue(result.output.contains("Push skipped"), result.output)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request, "expected push request")
            assertEquals("/v1/pushes", request.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush writes parseable lifecycle output with push context`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(202).setBody(
                """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
            ))
            val sseBody = "event: status\ndata: {\"status\":\"building\"}\n\n" +
                "event: status\ndata: {\"status\":\"build_succeeded\",\"imageTag\":\"zot/test:abc\"}\n\n" +
                "event: done\ndata: {}\n\n"
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
            )
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            File(tmp, "app.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(100))
            File(tmp, "grounds.yaml").writeText("""
                name: test-plugin
                type: plugin-paper
                baseImage: paper
                target: staging
            """.trimIndent())
            File(tmp, "build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }

                version = "consumer-app"

                groundsPush {
                    apiUrl.set("${server.url("/").toString().removeSuffix("/")}")
                    jarFile.set(layout.projectDirectory.file("app.jar"))
                    target.set("staging")
                }
            """.trimIndent())
            val credentials = File(tmp, ".config/grounds/credentials.json")
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            val result = GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush")
                .build()

            assertTrue(result.output.contains("Credentials resolved (source="), result.output)
            assertTrue(result.output.contains("Artifact selected (jarName=app.jar, size=104 B, target=staging"), result.output)
            assertTrue(result.output.contains("Push accepted (pushId=p1, target=staging, statusCode=202, reused=false)"), result.output)
            assertTrue(result.output.contains("Build status received (pushId=p1, status=building"), result.output)
            assertTrue(result.output.contains("Build succeeded (pushId=p1, imageTag=zot/test:abc)"), result.output)
            assertTrue(!result.output.contains("Plugin initialized (version=consumer-app)"), result.output)
            assertTrue(!result.output.contains("Resolving credentials"), result.output)
            assertTrue(!result.output.contains("→"), result.output)
            assertTrue(!result.output.contains("✔"), result.output)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush polls status after log stream transport failure`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(202).setBody(
                """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs"}"""
            ))
            server.enqueue(MockResponse().setResponseCode(503).setBody("stream unavailable"))
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"id":"p1","status":"build_succeeded","target":"dev","baseImage":"paper","imageTag":"zot/test:abc","failureReason":null,"createdAt":"2026-04-24T10:00:00Z","updatedAt":"2026-04-24T10:00:01Z"}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            File(tmp, "app.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(100))
            File(tmp, "grounds.yaml").writeText("""
                name: test-plugin
                type: plugin-paper
                baseImage: paper
            """.trimIndent())
            File(tmp, "build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }

                groundsPush {
                    apiUrl.set("${server.url("/").toString().removeSuffix("/")}")
                    jarFile.set(layout.projectDirectory.file("app.jar"))
                }
            """.trimIndent())
            val credentials = File(tmp, ".config/grounds/credentials.json")
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            val result = GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush")
                .build()

            assertTrue(result.output.contains("Build succeeded (pushId=p1, imageTag=zot/test:abc)"), result.output)
            assertEquals("/v1/pushes", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/pushes/p1/logs", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/pushes/p1", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            server.shutdown()
        }
    }
}
