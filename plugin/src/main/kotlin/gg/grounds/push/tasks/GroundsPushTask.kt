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
import org.gradle.work.DisableCachingByDefault
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@DisableCachingByDefault(because = "Pushes a plugin artifact to grounds-forge and streams remote build logs.")
abstract class GroundsPushTask : DefaultTask() {
    @get:Input @get:Optional abstract val apiUrl: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifestFile: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val jarFile: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val autoDetectedJarFile: RegularFileProperty
    @get:Input @get:Optional abstract val target: Property<String>
    @get:Input abstract val timeoutMinutes: Property<Int>
    @get:Input abstract val connectTimeoutSeconds: Property<Int>
    @get:Input abstract val failOnWhitelistError: Property<Boolean>

    @get:Internal
    val overrideTarget: Property<String> = project.objects.property(String::class.java)

    @Option(option = "target", description = "Override target (dev|staging)")
    fun setTargetOption(v: String) { overrideTarget.set(v) }

    @get:Internal
    val force: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    @Option(option = "force", description = "Skip reuse-by-contentHash and force a fresh build")
    fun setForceOption(v: Boolean) { force.set(v) }

    @TaskAction
    fun run() {
        val manifest = try {
            GroundsYamlParser.parse(manifestFile.get().asFile)
        } catch (e: GroundsYamlParseException) {
            throw GradleException(e.message!!, e)
        }

        val manifestJar = manifest.jar
            .takeIf { it != DEFAULT_MANIFEST_JAR }
            ?.let { project.file(it) }
        val jar = jarFile.orNull?.asFile
            ?: manifestJar
            ?: autoDetectedJarFile.orNull?.asFile
            ?: project.file(manifest.jar)
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

        logger.lifecycle("[grounds-push] Plugin initialized (version=${pluginVersion()})")
        logger.lifecycle("[grounds-push] Credentials resolved (source=${credsSource(creds)})")
        logger.lifecycle(
            "[grounds-push] Artifact selected " +
                "(jarName=${jar.name}, size=${humanSize(jar.length())}, target=$resolvedTarget, apiUrl=$resolvedApiUrl)"
        )

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
            client.createPush(manifestJson, resolvedTarget, jar, force = force.get())
        } catch (e: GroundsForgeClient.ApiException) {
            if (!failOnWhitelistError.get() && e.isWhitelistError()) {
                logger.warn(
                    "[grounds-push] Push skipped (reason=not_whitelisted, target=$resolvedTarget, " +
                        "error=${e.errorBody?.error ?: "unknown"})"
                )
                return
            }
            throw GradleException("grounds-forge rejected the push: ${e.message}", e)
        }

        logger.lifecycle(
            "[grounds-push] Push accepted " +
                "(pushId=${push.pushId}, target=$resolvedTarget, statusCode=${if (push.reused) "200" else "202"}, " +
                "reused=${push.reused})"
        )
        if (push.reused) {
            logger.lifecycle("[grounds-push] Build reused (pushId=${push.pushId}, target=$resolvedTarget, reason=content_hash)")
            return
        }

        streamAndWait(client, push.pushId, resolvedTarget)
    }

    private fun streamAndWait(client: GroundsForgeClient, pushId: String, target: String) {
        logger.lifecycle("[grounds-push] Build log stream opened (pushId=$pushId, target=$target)")
        val done = CountDownLatch(1)
        val terminal = AtomicReference<TerminalState?>()
        val last20 = ArrayDeque<String>(20)

        val listener = object : PushSseListener {
            override fun onStatus(status: String, imageTag: String?, failureReason: String?) {
                logger.lifecycle(
                    "[grounds-push] Build status received " +
                        "(pushId=$pushId, status=$status${imageTag?.let { ", imageTag=$it" } ?: ""}" +
                        "${failureReason?.let { ", reason=$it" } ?: ""})"
                )
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
            override fun onWarning(reason: String) {
                logger.warn("[grounds-push] Build warning received (pushId=$pushId, reason=$reason)")
            }
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
                    else -> throw GradleException(
                        "grounds-push: stream closed with non-terminal status " +
                            "(pushId=$pushId, status=${detail.status})"
                    )
                }
            } catch (e: GroundsForgeClient.ApiException) {
                throw GradleException(
                    "grounds-push: stream closed and status poll failed " +
                        "(pushId=$pushId, statusCode=${e.statusCode}, reason=${e.message})",
                    e,
                )
            }
        }

        when (val t = terminal.get()!!) {
            is TerminalState.Succeeded -> {
                logger.lifecycle("[grounds-push] Build succeeded (pushId=$pushId, imageTag=${t.imageTag ?: "unknown"})")
            }
            is TerminalState.Failed -> {
                val tailMsg = if (last20.isNotEmpty()) "\n  last 20 lines:\n" + last20.joinToString("\n") { "    $it" } else ""
                throw GradleException("grounds-push: build_failed (pushId=$pushId, reason=${t.reason})$tailMsg")
            }
        }
    }

    private sealed interface TerminalState {
        data class Succeeded(val imageTag: String?) : TerminalState
        data class Failed(val reason: String) : TerminalState
    }

    private fun pluginVersion(): String {
        return javaClass.`package`.implementationVersion ?: "unknown"
    }

    private fun credsSource(c: Credentials): String = when (c) {
        is Credentials.FromEnv -> "env:GROUNDS_TOKEN"
        is Credentials.FromFile -> "file:${c.sourcePath.absolutePath}"
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

    private fun GroundsForgeClient.ApiException.isWhitelistError(): Boolean {
        val body = errorBody ?: return false
        return listOfNotNull(body.error, body.reason, body.message)
            .any { it.contains("whitelist", ignoreCase = true) }
    }

    private companion object {
        const val DEFAULT_MANIFEST_JAR = "build/libs/*.jar"
    }
}
