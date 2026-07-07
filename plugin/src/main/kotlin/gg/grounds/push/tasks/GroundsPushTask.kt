package gg.grounds.push.tasks

import gg.grounds.push.bundle.PluginBundler
import gg.grounds.push.client.*
import gg.grounds.push.manifest.BaseImageCatalogValidationException
import gg.grounds.push.manifest.BaseImageCatalogValidator
import gg.grounds.push.manifest.GroundsYaml
import gg.grounds.push.manifest.GroundsYamlParseException
import gg.grounds.push.manifest.GroundsYamlParser
import gg.grounds.push.source.GitHubReleaseFetchException
import gg.grounds.push.source.GitHubReleaseFetcher
import gg.grounds.push.source.ResolvedPluginSource
import gg.grounds.push.source.ResolvedPluginSources
import gg.grounds.push.source.SourceRef
import gg.grounds.push.source.SourceRefParseException
import gg.grounds.push.source.SourceRefParser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@DisableCachingByDefault(because = "Pushes a plugin artifact to grounds-forge and streams remote build logs.")
abstract class GroundsPushTask : DefaultTask() {
    @get:Input @get:Optional abstract val apiUrl: Property<String>
    @get:Input @get:Optional abstract val projectId: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifestFile: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val jarFile: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val autoDetectedJarFile: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val resolvedPluginsFile: RegularFileProperty
    @get:Input @get:Optional abstract val target: Property<String>
    @get:Input @get:Optional abstract val flavor: Property<String>
    @get:Input abstract val timeoutMinutes: Property<Int>
    @get:Input abstract val connectTimeoutSeconds: Property<Int>
    @get:Input abstract val failOnWhitelistError: Property<Boolean>
    @get:Input abstract val baseImageCatalogMode: Property<String>
    @get:Internal abstract val projectDirectory: DirectoryProperty
    /** Output location for multi-plugin tar.gz bundles (only used when
     *  manifest declares `plugins:`). Lives under the project's
     *  buildDirectory so it gets cleaned with `gradle clean`. */
    @get:Internal abstract val bundleOutputFile: RegularFileProperty
    /** Cache directory for GitHub release downloads. Convention is
     *  `<gradleUserHome>/caches/grounds-push/`. */
    @get:Internal abstract val bundleCacheDir: DirectoryProperty
    /** Pre-resolved Jar archive paths for `:gradle-project`-style
     *  plugins[] entries. Wired from the plugin's afterEvaluate so the
     *  task action only has to look up by project path. */
    @get:Internal abstract val gradleProjectArtifacts: MapProperty<String, RegularFile>

    @get:Internal
    abstract val overrideTarget: Property<String>

    @Option(option = "target", description = "Override target (dev|staging)")
    fun setTargetOption(v: String) { overrideTarget.set(v) }

    @get:Internal
    abstract val overrideFlavor: Property<String>

    @Option(option = "flavor", description = "Select app flavor from grounds.yaml flavors")
    fun setFlavorOption(v: String) { overrideFlavor.set(v) }

    @get:Internal
    abstract val force: Property<Boolean>

    @Option(option = "force", description = "Skip reuse-by-contentHash and force a fresh build")
    fun setForceOption(v: Boolean) { force.set(v) }

    @Option(option = "resolved-plugins-file", description = "Path to CLI-resolved plugin sources JSON")
    fun setResolvedPluginsFileOption(path: String) {
        val file = File(path)
        resolvedPluginsFile.fileValue(
            if (file.isAbsolute) file else projectDirectory.file(path).get().asFile,
        )
    }

    @TaskAction
    fun run() {
        val manifest = try {
            GroundsYamlParser.parse(manifestFile.get().asFile)
        } catch (e: GroundsYamlParseException) {
            throw GradleException(e.message!!, e)
        }
        val resolvedPlugins = resolvedPluginsFile.orNull?.asFile?.let { file ->
            try {
                ResolvedPluginSources.parse(file)
            } catch (e: SerializationException) {
                throw GradleException("grounds-push: failed to parse resolved plugins file ${file.absolutePath}: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw GradleException("grounds-push: failed to parse resolved plugins file ${file.absolutePath}: ${e.message}", e)
            }
        }
        val selected = try {
            selectManifestRuntime(manifest, overrideFlavor.orNull ?: flavor.orNull)
        } catch (e: GroundsYamlParseException) {
            throw GradleException(e.message!!, e)
        }
        val runtime = selected.runtime

        // Two upload shapes:
        //   1. plugins: [...] in the manifest → tar.gz bundle, mixed
        //      sources (local paths, :gradle-project refs, github
        //      releases). Forge detects gzip magic and unpacks.
        //   2. single jar (autoDetected, ext.jarFile, or manifest.jar)
        //      → existing path.
        val manifestPluginSources = runtime.plugins?.map { it.source }
        val pluginSources: List<SourceRef> = if (resolvedPlugins != null) {
            resolvedPlugins.plugins.mapNotNull { entry ->
                entry.source?.takeIf { it.isNotBlank() }?.let { source ->
                    try {
                        SourceRefParser.parse(source)
                    } catch (e: SourceRefParseException) {
                        throw GradleException("grounds-push: resolved plugins file entry '${entry.id}': ${e.message}", e)
                    }
                }
            }
        } else {
            manifestPluginSources?.let {
                try {
                    SourceRefParser.parseAll(it)
                } catch (e: SourceRefParseException) {
                    throw GradleException("grounds-push: ${e.message}", e)
                }
            } ?: emptyList()
        }
        val bundleEntryCount = resolvedPlugins?.plugins?.size ?: pluginSources.size
        val artifact: File = if (resolvedPlugins != null) {
            val resolved = resolvedPlugins.plugins.map { entry -> resolveResolvedPluginSource(entry) }
            val bundleFile = bundleOutputFile.get().asFile
            try {
                PluginBundler.bundle(resolved, bundleFile)
            } catch (e: IllegalArgumentException) {
                throw GradleException("grounds-push: ${e.message}", e)
            }
            bundleFile
        } else if (pluginSources.isNotEmpty()) {
            val resolved = pluginSources.map { ref -> resolveSource(ref) }
            val bundleFile = bundleOutputFile.get().asFile
            try {
                PluginBundler.bundle(resolved, bundleFile)
            } catch (e: IllegalArgumentException) {
                throw GradleException("grounds-push: ${e.message}", e)
            }
            bundleFile
        } else {
            val manifestJar = runtime.jar
                .takeIf { it != DEFAULT_MANIFEST_JAR }
                ?.let { projectDirectory.file(it).get().asFile }
            val jar = jarFile.orNull?.asFile
                ?: manifestJar
                ?: autoDetectedJarFile.orNull?.asFile
                ?: projectDirectory.file(runtime.jar).get().asFile
            if (!jar.isFile) {
                throw GradleException(
                    "grounds-push: JAR not found at ${jar.absolutePath}. " +
                        "Did the build task run? Or set groundsPush.jarFile explicitly."
                )
            }
            jar
        }
        val sizeCheck = JarSizeGuard.check(artifact.length())
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
        val resolvedProjectId = projectId.orNull
            ?: System.getenv("GROUNDS_PROJECT")

        val resolvedTarget = overrideTarget.orNull ?: target.orNull ?: manifest.target ?: "dev"
        if (resolvedTarget != "dev" && resolvedTarget != "staging") {
            throw GradleException("grounds-push: target must be 'dev' or 'staging', got '$resolvedTarget'")
        }

        logger.lifecycle("[grounds-push] Plugin initialized (version=${pluginVersion()})")
        logger.lifecycle("[grounds-push] Credentials resolved (source=${credsSource(creds)})")
        logger.lifecycle(
            "[grounds-push] Artifact selected " +
                "(name=${artifact.name}, size=${humanSize(artifact.length())}, " +
                "shape=${if (bundleEntryCount > 0) "bundle($bundleEntryCount)" else "single-jar"}, " +
                "target=$resolvedTarget, flavor=${selected.flavorKey ?: "single"}, apiUrl=$resolvedApiUrl)"
        )

        val client = GroundsForgeClient(
            apiUrl = resolvedApiUrl,
            token = creds.accessToken,
            projectId = resolvedProjectId,
            connectTimeout = Duration.ofSeconds(connectTimeoutSeconds.get().toLong()),
            callTimeout = Duration.ofMinutes(timeoutMinutes.get().toLong()),
        )

        validateBaseImageCatalog(client, catalogManifestType(runtime.type), runtime.baseImage)

        val manifestJson = buildUploadManifestJson(manifest, selected, pluginSources)
        val effectivePluginSourcesJson = resolvedPlugins?.let {
            ResolvedPluginSources.json.encodeToString(it.effectivePluginSources)
        }

        val push = try {
            client.createPush(
                manifestJson,
                resolvedTarget,
                artifact,
                force = force.get(),
                flavor = selected.flavorKey,
                effectivePluginSourcesJson = effectivePluginSourcesJson,
            )
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
        logger.lifecycle(
            "[grounds-push] Build link available " +
                "(pushId=${push.pushId}, url=${push.buildLink(resolvedApiUrl)})"
        )
        if (push.reused) {
            logger.lifecycle("[grounds-push] Build reused (pushId=${push.pushId}, target=$resolvedTarget, reason=content_hash)")
            return
        }

        streamAndWait(client, push.pushId, resolvedTarget)
    }

    private fun resolveResolvedPluginSource(entry: ResolvedPluginSource): File {
        val localPath = entry.localPath?.takeIf { it.isNotBlank() }
        val source = entry.source?.takeIf { it.isNotBlank() }
        if ((localPath == null) == (source == null)) {
            throw GradleException(
                "grounds-push: resolved plugins file entry '${entry.id}' must set exactly one of localPath or source",
            )
        }
        if (localPath != null) {
            val file = File(localPath)
            if (!file.isAbsolute) {
                throw GradleException(
                    "grounds-push: resolved plugins file entry '${entry.id}' localPath must be absolute",
                )
            }
            if (!file.isFile) {
                throw GradleException(
                    "grounds-push: resolved plugins file entry '${entry.id}' not found at ${file.absolutePath}",
                )
            }
            return file
        }
        val ref = try {
            SourceRefParser.parse(source!!)
        } catch (e: SourceRefParseException) {
            throw GradleException("grounds-push: resolved plugins file entry '${entry.id}': ${e.message}", e)
        }
        return resolveSource(ref)
    }

    private fun validateBaseImageCatalog(client: GroundsForgeClient, type: String, baseImage: String) {
        when (val mode = baseImageCatalogMode.get().lowercase()) {
            "off" -> logger.lifecycle("[grounds-push] Base image catalog validation skipped (mode=off)")
            "warn", "strict" -> {
                try {
                    BaseImageCatalogValidator.validate(
                        client.listBaseImages(),
                        type = type,
                        baseImage = baseImage,
                    )
                    logger.lifecycle("[grounds-push] Base image catalog validated (type=$type, baseImage=$baseImage)")
                } catch (e: BaseImageCatalogValidationException) {
                    throw GradleException(e.message!!, e)
                } catch (e: Exception) {
                    if (mode == "strict") {
                        throw GradleException(
                            "grounds-push: failed to fetch base image catalog in strict mode (${e.message})",
                            e,
                        )
                    }
                    logger.warn(
                        "[grounds-push] Base image catalog unavailable " +
                            "(mode=warn, reason=${e.message ?: e::class.java.simpleName})",
                    )
                }
            }
            else -> throw GradleException("grounds-push: baseImageCatalogMode must be warn, strict, or off, got '$mode'")
        }
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

        val terminalState = terminal.get() ?: pollTerminalState(client, pushId)
        handleTerminalState(pushId, terminalState, last20)
    }

    private fun pollTerminalState(client: GroundsForgeClient, pushId: String): TerminalState {
        try {
            val detail = client.getPush(pushId)
            return when (detail.status) {
                "build_succeeded" -> TerminalState.Succeeded(detail.imageTag)
                "build_failed" -> TerminalState.Failed(detail.failureReason ?: "unknown")
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

    private fun handleTerminalState(pushId: String, terminalState: TerminalState, last20: ArrayDeque<String>) {
        when (terminalState) {
            is TerminalState.Succeeded -> {
                logger.lifecycle("[grounds-push] Build succeeded (pushId=$pushId, imageTag=${terminalState.imageTag ?: "unknown"})")
            }
            is TerminalState.Failed -> {
                val tailMsg = if (last20.isNotEmpty()) "\n  last 20 lines:\n" + last20.joinToString("\n") { "    $it" } else ""
                throw GradleException("grounds-push: build_failed (pushId=$pushId, reason=${terminalState.reason})$tailMsg")
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

    private data class SelectedRuntime(
        val flavorKey: String?,
        val runtime: ManifestRuntime,
    )

    private data class ManifestRuntime(
        val type: String,
        val baseImage: String,
        val jar: String,
        val plugins: List<GroundsYaml.PluginEntry>?,
        val resources: GroundsYaml.Resources?,
    )

    private fun selectManifestRuntime(manifest: GroundsYaml, requestedFlavor: String?): SelectedRuntime {
        val flavors = manifest.flavors
        if (flavors == null) {
            return SelectedRuntime(
                flavorKey = null,
                runtime = ManifestRuntime(
                    type = manifest.type ?: throw GroundsYamlParseException("grounds.yaml: missing required field 'type'"),
                    baseImage = manifest.baseImage
                        ?: throw GroundsYamlParseException("grounds.yaml: missing required field 'baseImage'"),
                    jar = manifest.jar,
                    plugins = manifest.plugins,
                    resources = manifest.resources,
                ),
            )
        }

        val key = requestedFlavor?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GroundsYamlParseException(
                "grounds.yaml: flavor selection required (available=${flavors.keys.joinToString(",")})",
            )
        val flavor = flavors[key]
            ?: throw GroundsYamlParseException(
                "grounds.yaml: unknown flavor '$key' (available=${flavors.keys.joinToString(",")})",
            )
        return SelectedRuntime(
            flavorKey = key,
            runtime = ManifestRuntime(
                type = flavor.type,
                baseImage = flavor.baseImage,
                jar = flavor.jar,
                plugins = flavor.plugins,
                resources = flavor.resources,
            ),
        )
    }

    private fun catalogManifestType(type: String): String = when (type) {
        "paper", "plugin-paper" -> "plugin-paper"
        "velocity", "plugin-velocity" -> "plugin-velocity"
        "gamemode" -> "gamemode"
        "minestom", "service" -> "service"
        else -> type
    }

    private fun buildUploadManifestJson(
        manifest: GroundsYaml,
        selected: SelectedRuntime,
        pluginSources: List<SourceRef>,
    ): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("name", JsonPrimitive(manifest.name))
        // Forge maps these to the gg.grounds/events SA annotation + NATS_URL.
        manifest.events?.takeIf { it.isNotEmpty() }?.let { events ->
            put("events", buildJsonArray {
                events.forEach { e ->
                    add(buildJsonObject {
                        put("subject", JsonPrimitive(e.subject))
                        e.dir?.let { put("dir", JsonPrimitive(it)) }
                        e.schema?.let { put("schema", JsonPrimitive(it)) }
                    })
                }
            })
        }
        // Forge maps each key to a ${KEY_UPPER}_SERVICE_URL env var on the pod.
        manifest.services?.takeIf { it.isNotEmpty() }?.let { services ->
            put("services", buildJsonObject {
                services.forEach { (key, decl) ->
                    put(key, buildJsonObject {
                        decl.use?.let { put("use", JsonPrimitive(it)) }
                        decl.provider?.let { put("provider", JsonPrimitive(it)) }
                        decl.version?.let { put("version", JsonPrimitive(it)) }
                    })
                }
            })
        }
        val flavors = manifest.flavors
        if (flavors != null) {
            put("flavors", buildJsonObject {
                flavors.forEach { (key, flavor) ->
                    put(
                        key,
                        flavor.toJson(
                            pluginSources = if (key == selected.flavorKey) pluginSources else emptyList(),
                        ),
                    )
                }
            })
        } else {
            putRuntimeFields(
                type = selected.runtime.type,
                baseImage = selected.runtime.baseImage,
                resources = selected.runtime.resources,
                pluginSources = pluginSources,
            )
        }
    })

    private fun GroundsYaml.Flavor.toJson(pluginSources: List<SourceRef>): JsonObject =
        buildJsonObject {
            putRuntimeFields(type, baseImage, resources, pluginSources)
            if (plugins == null) {
                put("jar", JsonPrimitive(jar))
            }
        }

    private fun JsonObjectBuilder.putRuntimeFields(
        type: String,
        baseImage: String,
        resources: GroundsYaml.Resources?,
        pluginSources: List<SourceRef>,
    ) {
        put("type", JsonPrimitive(type))
        put("baseImage", JsonPrimitive(baseImage))
        resources?.let { r ->
            put("resources", buildJsonObject {
                r.cpu?.let { put("cpu", JsonPrimitive(it)) }
                r.memory?.let { put("memory", JsonPrimitive(it)) }
            })
        }
        if (pluginSources.isNotEmpty()) {
            // Forge re-validates owner=groundsgg + tag pin-shape on
            // every github source as defense-in-depth.
            put("pluginSources", buildJsonArray { pluginSources.forEach { add(it.toJson()) } })
        }
    }

    private fun resolveSource(ref: SourceRef): File = when (ref) {
        is SourceRef.Local -> {
            val f = projectDirectory.file(ref.path).get().asFile
            if (ref.isAbsolute) {
                logger.lifecycle(
                    "[grounds-push] Note: '${ref.raw}' is an absolute path — manifest is non-portable across machines",
                )
            }
            if (!f.isFile) throw GradleException(
                "grounds-push: plugin entry not found at ${f.absolutePath} (grounds.yaml plugins[]).",
            )
            f
        }
        is SourceRef.GradleProject -> {
            val mapped = gradleProjectArtifacts.get()[ref.projectPath]?.asFile
                ?: throw GradleException(
                    "grounds-push: Gradle project '${ref.projectPath}' was not wired at configuration time. " +
                        "This usually means the subproject's afterEvaluate hadn't run when the manifest was inspected — " +
                        "ensure the subproject applies a Jar-producing plugin (`java`, shadow, etc.).",
                )
            if (!mapped.isFile) throw GradleException(
                "grounds-push: archive for '${ref.projectPath}' not found at ${mapped.absolutePath} — was the jar task allowed to run?",
            )
            mapped
        }
        is SourceRef.GitHubRelease -> {
            val cacheDir = bundleCacheDir.get().asFile
            val token = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
            try {
                GitHubReleaseFetcher().fetch(ref, cacheDir, token, logger::lifecycle)
            } catch (e: GitHubReleaseFetchException) {
                throw GradleException("grounds-push: ${e.message}", e)
            }
        }
    }

    private fun SourceRef.toJson(): JsonObject = buildJsonObject {
        when (this@toJson) {
            is SourceRef.Local -> put("kind", JsonPrimitive("local"))
            is SourceRef.GradleProject -> {
                put("kind", JsonPrimitive("gradle-project"))
                put("project", JsonPrimitive(projectPath))
            }
            is SourceRef.GitHubRelease -> {
                put("kind", JsonPrimitive("github"))
                put("owner", JsonPrimitive(owner))
                put("repo", JsonPrimitive(repo))
                put("tag", JsonPrimitive(tag))
                asset?.let { put("asset", JsonPrimitive(it)) }
            }
        }
    }

    private companion object {
        const val DEFAULT_MANIFEST_JAR = "build/libs/*.jar"
    }
}
