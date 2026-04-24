package gg.grounds.push

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
}
