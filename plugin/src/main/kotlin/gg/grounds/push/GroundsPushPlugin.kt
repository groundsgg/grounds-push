package gg.grounds.push

import gg.grounds.push.tasks.GroundsPromoteTask
import gg.grounds.push.tasks.GroundsPushRetryTask
import gg.grounds.push.tasks.GroundsPushTask
import gg.grounds.push.tasks.GroundsTestLocalTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar

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
                val autoJarTask = listOf(
                    "shadowJar",   // com.gradleup.shadow, io.github.goooler.shadow, johnrengelman.shadow
                    "jar",
                ).firstNotNullOfOrNull { name ->
                    p.tasks.findByName(name) as? Jar
                }
                if (autoJarTask != null) {
                    pushTask.configure { t -> t.autoDetectedJarFile.set(autoJarTask.archiveFile) }
                    pushTask.configure { t -> t.dependsOn(autoJarTask) }
                    testLocalTask.configure { t ->
                        t.jarFile.set(autoJarTask.archiveFile)
                        t.dependsOn(autoJarTask)
                    }
                }
            }
        }
    }
}
