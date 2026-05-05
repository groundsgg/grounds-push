package gg.grounds.push.tasks

import gg.grounds.push.manifest.GroundsYamlParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Run a local Paper or Velocity server with the project's compiled
 * plugin JAR pre-loaded. The cloud-based equivalent is `grounds push
 * --target=dev`; this task is the offline development loop — no
 * Grounds infra is touched, no quota consumed, no Cluster needed.
 *
 * Server JARs are downloaded from the PaperMC v2 API (papermc.io)
 * on first use and cached under `build/grounds-test/cache/`. The
 * server runs in `build/grounds-test/<baseImage>/`; that directory
 * persists between runs so player data + world chunks survive.
 *
 * v0 limitations:
 * - Only baseImage=paper or velocity. minestom + service workloads
 *   need `grounds push --target=dev` (they run as plain JVM apps,
 *   not Minecraft servers, and we don't ship a generic JVM runner).
 * - Paper version is fixed at 1.21.4 (mirrors what forge's
 *   ghcr.io/groundsgg/paper image targets). Override with
 *   `groundsPush.paperVersion = "1.21.5"` once forge bumps.
 */
@DisableCachingByDefault(because = "Boots a long-running local Minecraft server; no deterministic outputs to cache.")
abstract class GroundsTestLocalTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifestFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val jarFile: RegularFileProperty

    /** Paper version to download. Defaults to 1.21.4 (matches forge baseImage). */
    @get:Input @get:Optional abstract val paperVersion: Property<String>

    /** Velocity version to download. Defaults to 3.4.0-SNAPSHOT (matches forge). */
    @get:Input @get:Optional abstract val velocityVersion: Property<String>

    @get:Internal
    val workDir = project.layout.buildDirectory.dir("grounds-test")

    @TaskAction
    fun run() {
        val manifestPath = manifestFile.get().asFile
        if (!manifestPath.exists()) {
            throw GradleException("grounds.yaml not found at ${manifestPath.absolutePath}")
        }
        val manifest = manifestPath.bufferedReader().use { GroundsYamlParser.parse(it) }

        when (manifest.baseImage) {
            "paper" -> runPaper(manifest.name)
            "velocity" -> runVelocity(manifest.name)
            else -> throw GradleException(
                "groundsTestLocal only supports baseImage=paper|velocity (got '${manifest.baseImage}'). " +
                    "Use `grounds push --target=dev` for ${manifest.baseImage} workloads.",
            )
        }
    }

    private fun runPaper(pluginName: String) {
        val version = paperVersion.getOrElse("1.21.4")
        val serverJar = downloadPaperApiJar(project = "paper", version = version)
        val serverDir = workDir.get().asFile.resolve("paper-$version").apply { mkdirs() }
        ensureEula(serverDir)
        ensurePaperServerProperties(serverDir, pluginName)
        installPlugin(serverDir.resolve("plugins"))
        runJava(serverDir, serverJar, "Paper $version")
    }

    private fun runVelocity(pluginName: String) {
        val version = velocityVersion.getOrElse("3.4.0-SNAPSHOT")
        val serverJar = downloadPaperApiJar(project = "velocity", version = version)
        val serverDir = workDir.get().asFile.resolve("velocity-$version").apply { mkdirs() }
        installPlugin(serverDir.resolve("plugins"))
        runJava(serverDir, serverJar, "Velocity $version (plugin: $pluginName)")
    }

    private fun ensureEula(serverDir: File) {
        val eula = serverDir.resolve("eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
    }

    private fun ensurePaperServerProperties(serverDir: File, pluginName: String) {
        val props = serverDir.resolve("server.properties")
        if (props.exists()) return
        props.writeText(
            """
            # grounds-test local sandbox — generated once on first run.
            # Edit freely; we won't overwrite.
            online-mode=false
            motd=grounds-test :: $pluginName
            spawn-protection=0
            difficulty=normal
            view-distance=8
            """.trimIndent() + "\n",
        )
    }

    /**
     * Drop the user's plugin JAR into plugins/. Removes any previous
     * grounds-test-installed JAR first so a renamed build doesn't leave
     * orphans, but leaves dev-installed dependency plugins untouched
     * (anything not matching this build's filename).
     */
    private fun installPlugin(pluginsDir: File) {
        pluginsDir.mkdirs()
        val jar = jarFile.get().asFile
        // Remove any previous copy of THIS plugin (best-effort: same filename)
        // — but don't blast unrelated JARs the user might have placed manually.
        pluginsDir.resolve(jar.name).delete()
        Files.copy(jar.toPath(), pluginsDir.resolve(jar.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("[grounds-test] installed ${jar.name} into ${pluginsDir.absolutePath}")
    }

    private fun runJava(serverDir: File, serverJar: File, label: String) {
        logger.lifecycle("[grounds-test] starting $label in ${serverDir.absolutePath}")
        logger.lifecycle("[grounds-test] connect with: minecraft://localhost:25565 (paper) or 25577 (velocity)")
        val cmd = listOf(
            "java",
            "-Xms512m",
            "-Xmx2G",
            "-XX:+UseG1GC",
            "-jar",
            serverJar.absolutePath,
            "nogui",
        )
        val proc = ProcessBuilder(cmd)
            .directory(serverDir)
            .inheritIO()
            .start()
        // Forward Ctrl-C to the server so it shuts down cleanly.
        Runtime.getRuntime().addShutdownHook(Thread { proc.destroy() })
        val exit = proc.waitFor()
        if (exit != 0) {
            throw GradleException("$label exited with code $exit")
        }
    }

    /**
     * Download the latest stable build of `<project>` at `<version>`
     * from the PaperMC v2 API. Cached at
     * `build/grounds-test/cache/<project>-<version>-<build>.jar`.
     */
    private fun downloadPaperApiJar(project: String, version: String): File {
        val cacheDir = workDir.get().asFile.resolve("cache").apply { mkdirs() }
        // Resolve the latest build number via the API.
        val versionMeta = fetchJson("https://api.papermc.io/v2/projects/$project/versions/$version")
        val builds = Regex("\"builds\"\\s*:\\s*\\[([^\\]]*)]").find(versionMeta)?.groupValues?.get(1)
            ?: throw GradleException("PaperMC API returned no builds for $project $version")
        val build = builds.split(",").map { it.trim() }.lastOrNull { it.isNotBlank() }
            ?: throw GradleException("PaperMC API returned empty builds list for $project $version")
        val cached = cacheDir.resolve("$project-$version-$build.jar")
        if (cached.exists() && cached.length() > 0) return cached

        val downloadName = "$project-$version-$build.jar"
        val url = "https://api.papermc.io/v2/projects/$project/versions/$version/builds/$build/downloads/$downloadName"
        logger.lifecycle("[grounds-test] downloading $downloadName from PaperMC")
        URI(url).toURL().openStream().use { input ->
            cached.outputStream().use { input.copyTo(it) }
        }
        return cached
    }

    private fun fetchJson(url: String): String =
        URI(url).toURL().openStream().bufferedReader().use { it.readText() }
}
