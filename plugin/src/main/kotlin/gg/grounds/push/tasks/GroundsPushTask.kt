package gg.grounds.push.tasks

import gg.grounds.push.client.*
import gg.grounds.push.manifest.GroundsYamlParseException
import gg.grounds.push.manifest.GroundsYamlParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

abstract class GroundsPushTask : DefaultTask() {
    @get:Input @get:Optional abstract val apiUrl: Property<String>
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:InputFile abstract val jarFile: RegularFileProperty
    @get:Input @get:Optional abstract val target: Property<String>
    @get:Input abstract val timeoutMinutes: Property<Int>
    @get:Input abstract val connectTimeoutSeconds: Property<Int>
    @get:Input abstract val failOnWhitelistError: Property<Boolean>

    @get:Internal
    val overrideTarget: Property<String> = project.objects.property(String::class.java)

    @Option(option = "target", description = "Override target (dev|staging)")
    fun setTargetOption(v: String) { overrideTarget.set(v) }

    @TaskAction
    fun run() {
        val jar = jarFile.get().asFile
        if (!jar.isFile) {
            throw GradleException(
                "grounds-push: JAR not found at ${jar.absolutePath}. " +
                    "Did the build task run? Or set groundsPush.jarFile explicitly."
            )
        }
        val sizeCheck = JarSizeGuard.check(jar.length())
        when (sizeCheck) {
            is JarSizeGuard.Result.Reject -> throw GradleException("grounds-push: ${sizeCheck.message}")
            is JarSizeGuard.Result.Warn -> logger.lifecycle("[grounds-push] ${sizeCheck.message}")
            is JarSizeGuard.Result.Ok -> { /* no-op */ }
        }

        val manifest = try {
            GroundsYamlParser.parse(manifestFile.get().asFile)
        } catch (e: GroundsYamlParseException) {
            throw GradleException(e.message!!, e)
        }

        val creds = try {
            CredentialResolver().resolve()
        } catch (e: CredentialResolutionException) {
            throw GradleException(e.message!!, e)
        }

        val resolvedApiUrl = apiUrl.orNull
            ?: System.getenv("GROUNDS_API_URL")
            ?: (creds as? Credentials.FromFile)?.apiUrl
            ?: "https://platform.grnds.io"    // internal default

        val resolvedTarget = overrideTarget.orNull ?: target.orNull ?: manifest.target ?: "dev"
        if (resolvedTarget != "dev" && resolvedTarget != "staging") {
            throw GradleException("grounds-push: target must be 'dev' or 'staging', got '$resolvedTarget'")
        }

        logger.lifecycle("[grounds-push] ${grepVersion()}")
        logger.lifecycle("[grounds-push] Resolving credentials from ${credsSource(creds)}")
        logger.lifecycle("[grounds-push] Uploading ${jar.name} (${humanSize(jar.length())}) to $resolvedApiUrl")

        val client = GroundsForgeClient(
            apiUrl = resolvedApiUrl,
            token = creds.accessToken,
            connectTimeout = Duration.ofSeconds(connectTimeoutSeconds.get().toLong()),
            callTimeout = Duration.ofMinutes(timeoutMinutes.get().toLong()),
        )

        val manifestJson = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("name", JsonPrimitive(manifest.name))
            put("type", JsonPrimitive(manifest.type))
            put("baseImage", JsonPrimitive(manifest.baseImage))
            manifest.resources?.let { r ->
                put("resources", buildJsonObject {
                    r.cpu?.let { put("cpu", JsonPrimitive(it)) }
                    r.memory?.let { put("memory", JsonPrimitive(it)) }
                })
            }
        })

        val push = try {
            client.createPush(manifestJson, resolvedTarget, jar)
        } catch (e: GroundsForgeClient.ApiException) {
            throw GradleException("grounds-forge rejected the push: ${e.message}", e)
        }

        logger.lifecycle(
            "[grounds-push] POST /v1/pushes → HTTP ${if (push.reused) "200" else "202"}, " +
                "pushId=${push.pushId} (reused=${push.reused})"
        )
        if (push.reused) {
            logger.lifecycle("[grounds-push] Reused existing successful build for this content-hash.")
            return
        }

        streamAndWait(client, push.pushId)
    }

    private fun streamAndWait(client: GroundsForgeClient, pushId: String) {
        logger.lifecycle("[grounds-push] Streaming build logs:")
        val done = CountDownLatch(1)
        val terminal = AtomicReference<TerminalState?>()
        val last20 = ArrayDeque<String>(20)

        val listener = object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {
                logger.lifecycle("[grounds-push] status=$status${imageTag?.let { " imageTag=$it" } ?: ""}${failureReason?.let { " reason=$it" } ?: ""}")
                when (status) {
                    "build_succeeded" -> terminal.compareAndSet(null, TerminalState.Succeeded(imageTag))
                    "build_failed" -> terminal.compareAndSet(null, TerminalState.Failed(failureReason ?: "unknown"))
                }
            }
            override fun onLog(ts: String, line: String) {
                logger.lifecycle("  [build] $line")
                if (last20.size == 20) last20.removeFirst()
                last20.addLast(line)
            }
            override fun onWarning(reason: String) { logger.warn("[grounds-push] warning: $reason") }
            override fun onDone() { done.countDown() }
            override fun onError(reason: String) {
                terminal.compareAndSet(null, TerminalState.Failed(reason))
                done.countDown()
            }
            override fun onStreamClosed(normal: Boolean) {
                if (!normal && terminal.get() == null) {
                    // Server hung up without terminal status — don't fail yet; try polling.
                }
                done.countDown()
            }
        }
        val es = client.streamLogs(pushId, listener)

        val waited = done.await(timeoutMinutes.get().toLong(), TimeUnit.MINUTES)
        es.cancel()
        if (!waited) {
            throw GradleException("grounds-push: push $pushId exceeded ${timeoutMinutes.get()}-minute timeout")
        }

        // If SSE closed without terminal status, poll once.
        if (terminal.get() == null) {
            try {
                val detail = client.getPush(pushId)
                when (detail.status) {
                    "build_succeeded" -> terminal.set(TerminalState.Succeeded(detail.imageTag))
                    "build_failed" -> terminal.set(TerminalState.Failed(detail.failureReason ?: "unknown"))
                    else -> throw GradleException("grounds-push: stream closed with non-terminal status=${detail.status}")
                }
            } catch (e: GroundsForgeClient.ApiException) {
                throw GradleException("grounds-push: stream closed and status poll failed: ${e.message}", e)
            }
        }

        when (val t = terminal.get()!!) {
            is TerminalState.Succeeded -> {
                logger.lifecycle("[grounds-push] ✔ build_succeeded imageTag=${t.imageTag ?: "?"}")
            }
            is TerminalState.Failed -> {
                val tailMsg = if (last20.isNotEmpty()) "\n  last 20 lines:\n" + last20.joinToString("\n") { "    $it" } else ""
                throw GradleException("grounds-push: build_failed reason=${t.reason}$tailMsg")
            }
        }
    }

    private sealed interface TerminalState {
        data class Succeeded(val imageTag: String?) : TerminalState
        data class Failed(val reason: String) : TerminalState
    }

    private fun grepVersion(): String {
        // Best-effort — reads this plugin's project version if accessible.
        return "grounds-push ${project.findProperty("version")?.toString() ?: "0.1.0-dev"}"
    }

    private fun credsSource(c: Credentials): String = when (c) {
        is Credentials.FromEnv -> "GROUNDS_TOKEN env ✔"
        is Credentials.FromFile -> "${c.sourcePath.absolutePath} ✔"
    }

    private fun humanSize(bytes: Long): String {
        val kb = bytes.toDouble() / 1024
        val mb = kb / 1024
        return when {
            kb < 1 -> "$bytes B"
            mb < 1 -> String.format("%.1f KB", kb)
            else -> String.format("%.1f MB", mb)
        }
    }
}
