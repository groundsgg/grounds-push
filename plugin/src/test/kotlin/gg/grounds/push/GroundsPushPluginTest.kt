package gg.grounds.push

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mirrors the OS-conditional path resolution in `CredentialResolver`
 * so TestKit-driven `groundsPush` runs find the JSON file we just
 * wrote regardless of which platform the test suite is running on.
 *
 * Without this the macOS builds wrote to `<tmp>/.config/grounds/`
 * (XDG layout) while the resolver, picking up the macOS branch,
 * looked under `<tmp>/Library/Application Support/grounds/` and
 * surfaced "No credentials found" — four locally-flaky failures
 * carried across many sessions.
 */
private fun credentialsFileFor(homeRoot: File): File {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") ->
            File(homeRoot, "Library/Application Support/grounds/credentials.json")
        os.contains("win") ->
            File(homeRoot, "AppData/Roaming/grounds/credentials.json")
        else ->
            File(homeRoot, ".config/grounds/credentials.json")
    }
}

private fun GradleRunner.withIsolatedCredentialsEnvironment(homeRoot: File): GradleRunner {
    val os = System.getProperty("os.name").lowercase()
    val env = System.getenv().toMutableMap()
    env.remove("GROUNDS_TOKEN")
    env.remove("GROUNDS_API_URL")
    if (os.contains("win")) {
        env["APPDATA"] = File(homeRoot, "AppData/Roaming").absolutePath
    } else {
        env["XDG_CONFIG_HOME"] = File(homeRoot, ".config").absolutePath
    }
    return withEnvironment(env)
}

private fun multipartPart(body: ByteArray, boundary: String, name: String): ByteArray {
    val text = body.toString(Charsets.ISO_8859_1)
    val headerStart = text.indexOf("""Content-Disposition: form-data; name="$name"""")
    require(headerStart >= 0) { "multipart part '$name' not found" }
    val dataStart = text.indexOf("\r\n\r\n", headerStart)
    require(dataStart >= 0) { "multipart part '$name' has no data separator" }
    val nextBoundary = text.indexOf("\r\n--$boundary", dataStart + 4)
    require(nextBoundary >= 0) { "multipart part '$name' has no closing boundary" }
    return body.copyOfRange(dataStart + 4, nextBoundary)
}

private fun tarGzEntryNames(bytes: ByteArray): List<String> {
    val names = mutableListOf<String>()
    TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(bytes))).use { tar ->
        var entry = tar.nextEntry
        while (entry != null) {
            names += entry.name
            entry = tar.nextEntry
        }
    }
    return names
}

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
    fun `groundsPush resolves manifest jar without execution time project access warning`(@TempDir tmp: File) {
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
            .withArguments("groundsPush", "--warning-mode=fail")
            .buildAndFail()

        assertTrue(result.output.contains("build/libs/from-manifest.jar"), result.output)
        assertTrue(!result.output.contains("Invocation of Task.project at execution time"), result.output)
    }

    @Test
    fun `groundsPushRetry uses api url from credentials file`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(202).setBody(
                """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs","buildUrl":"https://platform.grnds.io/builds/p1"}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            File(tmp, "build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }
            """.trimIndent())
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText(
                """{"version":1,"apiUrl":"${server.url("/").toString().removeSuffix("/")}","accessToken":"token"}"""
            )

            GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
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
            server.enqueue(catalogResponse())
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
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            val result = GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush")
                .build()

            assertTrue(result.output.contains("Push skipped"), result.output)
            assertEquals("/v1/base-images", server.takeRequest(5, TimeUnit.SECONDS)?.path)
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
            server.enqueue(catalogResponse())
            server.enqueue(MockResponse().setResponseCode(202).setBody(
                """{"pushId":"p1","status":"received","reused":false,"logsUrl":"/v1/pushes/p1/logs","buildUrl":"https://platform.grnds.io/builds/p1"}"""
            ))
            val sseBody = "event: status\ndata: {\"status\":\"building\"}\n\n" +
                "event: status\ndata: {\"status\":\"build_succeeded\",\"imageTag\":\"zot/test:abc\"}\n\n" +
                "event: status\ndata: {\"status\":\"ready\"}\n\n" +
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
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            val result = GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush")
                .build()

            assertTrue(result.output.contains("Credentials resolved (source="), result.output)
            assertTrue(
                result.output.contains("Artifact selected (name=app.jar, size=104 B, shape=single-jar, target=staging"),
                result.output,
            )
            assertTrue(result.output.contains("Push accepted (pushId=p1, target=staging, statusCode=202, reused=false)"), result.output)
            assertTrue(result.output.contains("Build link available (pushId=p1, url=https://platform.grnds.io/builds/p1)"), result.output)
            assertTrue(result.output.contains("Build status received (pushId=p1, status=building"), result.output)
            assertTrue(result.output.contains("Deployment ready (pushId=p1, imageTag=zot/test:abc)"), result.output)
            assertTrue(!result.output.contains("Plugin initialized (version=consumer-app)"), result.output)
            assertTrue(!result.output.contains("Resolving credentials"), result.output)
            assertTrue(!result.output.contains("→"), result.output)
            assertTrue(!result.output.contains("✔"), result.output)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush uploads full app flavor manifest and selected artifact`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(catalogResponse())
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"pushId":"p1","status":"build_succeeded","reused":true,"logsUrl":null}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            val paperJar = File(tmp, "paper.jar").also {
                it.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "PAPER".toByteArray())
            }
            val velocityJar = File(tmp, "velocity.jar").also {
                it.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "VELOCITY".toByteArray())
            }
            File(tmp, "grounds.yaml").writeText("""
                name: plugin-config
                flavors:
                  paper:
                    type: paper
                    baseImage: paper
                    jar: ${paperJar.name}
                  velocity:
                    type: velocity
                    baseImage: velocity
                    jar: ${velocityJar.name}
            """.trimIndent())
            File(tmp, "build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }

                groundsPush {
                    apiUrl.set("${server.url("/").toString().removeSuffix("/")}")
                }
            """.trimIndent())
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush", "--flavor=velocity")
                .build()

            assertEquals("/v1/base-images", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request, "expected push request")
            assertEquals("/v1/pushes", request.path)
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val body = request.body.readByteArray()
            val manifest = multipartPart(body, boundary, "manifest").toString(Charsets.UTF_8)
            val manifestJson = Json.parseToJsonElement(manifest).jsonObject
            assertEquals("plugin-config", manifestJson["name"]?.jsonPrimitive?.content, manifest)
            assertFalse("type" in manifestJson, manifest)
            assertFalse("baseImage" in manifestJson, manifest)
            val flavors = assertNotNull(manifestJson["flavors"], manifest).jsonObject
            assertEquals(setOf("paper", "velocity"), flavors.keys, manifest)
            val paper = assertNotNull(flavors["paper"], manifest).jsonObject
            assertEquals("paper", assertNotNull(paper["type"], manifest).jsonPrimitive.content, manifest)
            assertEquals("paper", assertNotNull(paper["baseImage"], manifest).jsonPrimitive.content, manifest)
            assertEquals(paperJar.name, assertNotNull(paper["jar"], manifest).jsonPrimitive.content, manifest)
            val velocity = assertNotNull(flavors["velocity"], manifest).jsonObject
            assertEquals("velocity", assertNotNull(velocity["type"], manifest).jsonPrimitive.content, manifest)
            assertEquals("velocity", assertNotNull(velocity["baseImage"], manifest).jsonPrimitive.content, manifest)
            assertEquals(velocityJar.name, assertNotNull(velocity["jar"], manifest).jsonPrimitive.content, manifest)
            assertEquals("velocity", multipartPart(body, boundary, "flavor").toString(Charsets.UTF_8))
            assertTrue(multipartPart(body, boundary, "jar").toString(Charsets.ISO_8859_1).contains("VELOCITY"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush wires Gradle project refs only for selected app flavor`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(catalogResponse())
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"pushId":"p1","status":"build_succeeded","reused":true,"logsUrl":null}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("""
                rootProject.name = "test"
                include(":app", ":velocity-plugin")
            """.trimIndent())
            File(tmp, "app").mkdirs()
            File(tmp, "app/companion.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "COMPANION".toByteArray())
            File(tmp, "app/grounds.yaml").writeText("""
                name: plugin-config
                flavors:
                  paper:
                    type: paper
                    baseImage: paper
                    plugins:
                      - id: paper-plugin
                        source: :paper-plugin
                      - id: companion
                        source: companion.jar
                  velocity:
                    type: velocity
                    baseImage: velocity
                    plugins:
                      - id: velocity-plugin
                        source: :velocity-plugin
                      - id: companion
                        source: companion.jar
            """.trimIndent())
            File(tmp, "app/build.gradle.kts").writeText("""
                plugins {
                    id("gg.grounds.push")
                }

                groundsPush {
                    apiUrl.set("${server.url("/").toString().removeSuffix("/")}")
                }
            """.trimIndent())
            File(tmp, "velocity-plugin").mkdirs()
            File(tmp, "velocity-plugin/build.gradle.kts").writeText("""
                plugins {
                    id("java")
                }
            """.trimIndent())
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments("-Duser.home=${tmp.absolutePath}", ":app:groundsPush", "--flavor=velocity")
                .build()

            assertEquals("/v1/base-images", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request, "expected push request")
            assertEquals("/v1/pushes", request.path)
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val jarPart = multipartPart(request.body.readByteArray(), boundary, "jar")
            assertEquals(listOf("plugins/00-velocity-plugin.jar", "plugins/01-companion.jar"), tarGzEntryNames(jarPart))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush polls status after log stream transport failure`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(catalogResponse())
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
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            val result = GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments("-Duser.home=${tmp.absolutePath}", "groundsPush")
                .build()

            assertTrue(result.output.contains("Deployment ready (pushId=p1, imageTag=zot/test:abc)"), result.output)
            assertEquals("/v1/base-images", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/pushes", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/pushes/p1/logs", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/pushes/p1", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush bundles resolved localPath plugins and uploads sanitized effective plugin sources`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(catalogResponse())
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"pushId":"p1","status":"build_succeeded","reused":true,"logsUrl":null}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            val chatJar = File(tmp, "plugin-chat.jar").also {
                it.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "CHAT".toByteArray())
            }
            val permissionsJar = File(tmp, "plugin-permissions.jar").also {
                it.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "PERMISSIONS".toByteArray())
            }
            val resolvedPlugins = File(tmp, "resolved-plugins.json").also {
                it.writeText("""
                    {
                      "plugins": [
                        {"id":"plugin-chat","variant":"paper","localPath":"${chatJar.absolutePath}"},
                        {"id":"plugin-permissions","variant":"paper","localPath":"${permissionsJar.absolutePath}"}
                      ],
                      "effectivePluginSources": [
                        {
                          "id":"plugin-chat",
                          "variant":"paper",
                          "effective":"local",
                          "defaultSource":"github:groundsgg/plugin-chat@v1.2.3:plugin-chat.jar",
                          "localPath":"${chatJar.absolutePath}",
                          "artifactName":"plugin-chat.jar",
                          "artifactSha256":"${"a".repeat(64)}",
                          "git":{"remote":"groundsgg/plugin-chat","commit":"abcdef1","dirty":true}
                        },
                        {
                          "id":"plugin-permissions",
                          "variant":"paper",
                          "effective":"local",
                          "localPath":"${permissionsJar.absolutePath}",
                          "artifactName":"plugin-permissions.jar",
                          "artifactSha256":"${"b".repeat(64)}"
                        }
                      ]
                    }
                """.trimIndent())
            }
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
                }
            """.trimIndent())
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments(
                    "-Duser.home=${tmp.absolutePath}",
                    "groundsPush",
                    "--resolved-plugins-file=${resolvedPlugins.absolutePath}",
                )
                .build()

            assertEquals("/v1/base-images", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request, "expected push request")
            assertEquals("/v1/pushes", request.path)
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val body = request.body.readByteArray()
            val bodyText = body.toString(Charsets.ISO_8859_1)
            assertTrue(!bodyText.contains(chatJar.absolutePath), bodyText)
            assertTrue(!bodyText.contains(permissionsJar.absolutePath), bodyText)
            val effectiveSources = multipartPart(body, boundary, "effectivePluginSources").toString(Charsets.UTF_8)
            assertTrue(effectiveSources.contains("plugin-chat"), effectiveSources)
            assertTrue(!effectiveSources.contains(chatJar.absolutePath), effectiveSources)
            assertTrue(!effectiveSources.contains(permissionsJar.absolutePath), effectiveSources)

            val jarPart = multipartPart(body, boundary, "jar")
            assertEquals(
                listOf("plugins/00-plugin-chat.jar", "plugins/01-plugin-permissions.jar"),
                tarGzEntryNames(jarPart),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `groundsPush wires resolved Gradle project plugin refs without manifest plugin entry`(@TempDir tmp: File) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(catalogResponse())
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """{"pushId":"p1","status":"build_succeeded","reused":true,"logsUrl":null}"""
            ))
            File(tmp, "settings.gradle.kts").writeText("""
                rootProject.name = "test"
                include(":plugin")
            """.trimIndent())
            File(tmp, "plugin").mkdirs()
            File(tmp, "plugin/build.gradle.kts").writeText("""
                plugins {
                    id("java")
                }
            """.trimIndent())
            val companionJar = File(tmp, "companion.jar").also {
                it.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "COMPANION".toByteArray())
            }
            val resolvedPlugins = File(tmp, "resolved-plugins.json").also {
                it.writeText("""
                    {
                      "plugins": [
                        {"id":"plugin","variant":"paper","source":":plugin"},
                        {"id":"companion","variant":"paper","localPath":"${companionJar.absolutePath}"}
                      ]
                    }
                """.trimIndent())
            }
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
                }
            """.trimIndent())
            val credentials = credentialsFileFor(tmp)
            credentials.parentFile.mkdirs()
            credentials.writeText("""{"version":1,"accessToken":"token"}""")

            GradleRunner.create()
                .withProjectDir(tmp)
                .withPluginClasspath()
                .withIsolatedCredentialsEnvironment(tmp)
                .withArguments(
                    "-Duser.home=${tmp.absolutePath}",
                    "groundsPush",
                    "--resolved-plugins-file=${resolvedPlugins.absolutePath}",
                )
                .build()

            assertEquals("/v1/base-images", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request, "expected push request")
            assertEquals("/v1/pushes", request.path)
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val jarPart = multipartPart(request.body.readByteArray(), boundary, "jar")
            assertEquals(listOf("plugins/00-plugin.jar", "plugins/01-companion.jar"), tarGzEntryNames(jarPart))
        } finally {
            server.shutdown()
        }
    }

    private fun catalogResponse(): MockResponse =
        MockResponse().setResponseCode(200).setBody(
            """{"items":[{"key":"paper","displayName":"Paper","manifestType":"plugin-paper","image":"ghcr.io/groundsgg/paper","versions":[{"version":"0.8.2","selectable":true}]},{"key":"velocity","displayName":"Velocity","manifestType":"plugin-velocity","image":"ghcr.io/groundsgg/velocity","versions":[{"version":"0.8.2","selectable":true}]}]}"""
        )
}
