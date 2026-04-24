package gg.grounds.push.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

abstract class GroundsPromoteTask : DefaultTask() {
    @TaskAction
    fun run() {
        throw GradleException(
            "promote task is not available until grounds-forge 0.5.0+. " +
                "Wait for Phase 2.3 or later."
        )
    }
}
