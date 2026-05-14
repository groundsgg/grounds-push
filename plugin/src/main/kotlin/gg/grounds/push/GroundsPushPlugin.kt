package gg.grounds.push

import gg.grounds.push.manifest.GroundsYamlParseException
import gg.grounds.push.manifest.GroundsYamlParser
import gg.grounds.push.source.ResolvedPluginSources
import gg.grounds.push.tasks.GroundsPromoteTask
import gg.grounds.push.tasks.GroundsPushRetryTask
import gg.grounds.push.tasks.GroundsPushTask
import gg.grounds.push.tasks.GroundsTestLocalTask
import kotlinx.serialization.SerializationException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownProjectException
import org.gradle.jvm.tasks.Jar
import java.io.File

class GroundsPushPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val ext = target.extensions.create("groundsPush", GroundsPushExtension::class.java)

        // Default: manifest file at project-root/grounds.yaml.
        ext.manifestFile.convention(target.layout.projectDirectory.file("grounds.yaml"))

        val pushTask = target.tasks.register("groundsPush", GroundsPushTask::class.java) { t ->
            t.group = "grounds"
            t.description = "Upload JAR + manifest to grounds-forge and stream build logs"
            t.apiUrl.set(ext.apiUrl)
            t.manifestFile.set(ext.manifestFile)
            t.jarFile.set(ext.jarFile)
            t.target.set(ext.target.orElse("dev"))
            t.timeoutMinutes.set(ext.timeoutMinutes)
            t.connectTimeoutSeconds.set(ext.connectTimeoutSeconds)
            t.failOnWhitelistError.set(ext.failOnWhitelistError)
            t.baseImageCatalogMode.set(ext.baseImageCatalogMode)
            t.projectDirectory.set(target.layout.projectDirectory)
            t.bundleOutputFile.set(
                target.layout.buildDirectory.file("grounds-push/bundle.tar.gz"),
            )
            // GitHub release downloads survive across `gradle clean` —
            // they live in the Gradle user home cache, not the per-
            // project build dir. Same convention Gradle itself uses
            // for module dependencies.
            t.bundleCacheDir.set(
                target.layout.dir(
                    target.providers.provider {
                        target.gradle.gradleUserHomeDir.resolve("caches/grounds-push")
                    },
                ),
            )
            t.force.convention(false)
        }

        target.tasks.register("groundsPushRetry", GroundsPushRetryTask::class.java) { t ->
            t.group = "grounds"
            t.description = "Retry a failed push by pushId (reuses the server-stored JAR)"
            t.apiUrl.set(ext.apiUrl)
            t.timeoutMinutes.set(ext.timeoutMinutes)
            t.connectTimeoutSeconds.set(ext.connectTimeoutSeconds)
        }

        target.tasks.register("groundsPromote", GroundsPromoteTask::class.java) { t ->
            t.group = "grounds"
            t.description = "[NOT YET AVAILABLE] Promote a successful push to another target"
        }

        // Local-test task. Runs Paper or Velocity locally with this plugin
        // pre-installed — the offline equivalent of `grounds push --target=dev`,
        // for inner-loop iteration without spending Grounds infra quota.
        val testLocalTask = target.tasks.register(
            "groundsTestLocal",
            GroundsTestLocalTask::class.java,
        ) { t ->
            t.group = "grounds"
            t.description = "Run Paper/Velocity locally with this plugin loaded (offline)"
            t.manifestFile.set(ext.manifestFile)
            t.jarFile.set(ext.jarFile)
            t.paperVersion.set(ext.paperVersion)
            t.velocityVersion.set(ext.velocityVersion)
        }

        // JAR auto-detection at afterEvaluate so the user's `shadowJar`/`jar` task
        // exists by then. Both push and testLocal benefit from the same wiring.
        target.afterEvaluate { p ->
            if (!ext.jarFile.isPresent) {
                val autoJarTask = findJarTask(p)
                if (autoJarTask != null) {
                    pushTask.configure { t -> t.autoDetectedJarFile.set(autoJarTask.archiveFile) }
                    pushTask.configure { t -> t.dependsOn(autoJarTask) }
                    testLocalTask.configure { t ->
                        t.jarFile.set(autoJarTask.archiveFile)
                        t.dependsOn(autoJarTask)
                    }
                }
            }

            // Multi-plugin bundle: if grounds.yaml lists `plugins:` with
            // `:foo`-style Gradle project refs, look each one up now and
            // wire dependsOn + plumb the Jar task's archiveFile into the
            // task. We do this here (config-time) so users don't have to
            // hand-write `tasks.named("groundsPush") { dependsOn(":foo:jar") }`.
            wireGradleProjectPluginRefs(p, pushTask)
        }
    }

    private fun findJarTask(p: Project): Jar? = listOf(
        "shadowJar", // com.gradleup.shadow, io.github.goooler.shadow, johnrengelman.shadow
        "jar",
    ).firstNotNullOfOrNull { name -> p.tasks.findByName(name) as? Jar }

    private fun wireGradleProjectPluginRefs(
        p: Project,
        pushTask: org.gradle.api.tasks.TaskProvider<GroundsPushTask>,
    ) {
        val entries = (
            gradleProjectRefsFromManifest(p) +
                gradleProjectRefsFromResolvedPluginsFile(p)
            ).toSet()

        for (entry in entries) {
            val sub = try {
                p.project(entry)
            } catch (_: UnknownProjectException) {
                throw IllegalStateException(
                    "grounds-push: plugin source references Gradle project '$entry' " +
                        "but no such project is included. Add it to settings.gradle(.kts).",
                )
            }
            // Sub-project's Jar task may not exist yet (its build.gradle
            // hasn't run). evaluationDependsOn would force it but is the
            // long-deprecated lever; afterEvaluate of `sub` is the modern
            // way. We bind into the subproject's afterEvaluate to find
            // the Jar task once it's been registered.
            sub.afterEvaluate { subP ->
                val jarTask = findJarTask(subP) ?: throw IllegalStateException(
                    "grounds-push: project '$entry' has no shadowJar/jar task to bundle.",
                )
                pushTask.configure { t ->
                    t.dependsOn(jarTask)
                    t.gradleProjectArtifacts.put(entry, jarTask.archiveFile)
                }
            }
        }
    }

    private fun gradleProjectRefsFromManifest(p: Project): List<String> {
        val manifestFile = p.layout.projectDirectory.file("grounds.yaml").asFile
        if (!manifestFile.isFile) return emptyList()

        val pluginEntries = try {
            GroundsYamlParser.parse(manifestFile).plugins ?: return emptyList()
        } catch (_: GroundsYamlParseException) {
            // Don't fail apply() on a malformed manifest — the task action
            // re-parses and surfaces the error there with proper context.
            return emptyList()
        }

        return pluginEntries.map { it.source }.filter { it.startsWith(":") }
    }

    private fun gradleProjectRefsFromResolvedPluginsFile(p: Project): List<String> {
        // Gradle applies @Option values too late for dependency wiring during afterEvaluate.
        // Read the task request args directly so resolved project refs can still add dependsOn edges.
        val resolvedPluginsFile = resolvedPluginsFileFromCommandLine(p)
            ?: return emptyList()
        if (!resolvedPluginsFile.isFile) return emptyList()

        val resolvedPlugins = try {
            ResolvedPluginSources.parse(resolvedPluginsFile)
        } catch (_: SerializationException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        return resolvedPlugins.plugins.mapNotNull { it.source }.filter { it.startsWith(":") }
    }

    private fun resolvedPluginsFileFromCommandLine(p: Project): File? {
        val args = p.gradle.startParameter.taskRequests.flatMap { it.args }
        args.forEachIndexed { index, arg ->
            when {
                arg.startsWith("--resolved-plugins-file=") ->
                    return resolveProjectFile(p, arg.substringAfter("="))
                arg == "--resolved-plugins-file" && index + 1 < args.size ->
                    return resolveProjectFile(p, args[index + 1])
            }
        }
        return null
    }

    private fun resolveProjectFile(p: Project, path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else p.layout.projectDirectory.file(path).asFile
    }
}
