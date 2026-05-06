pluginManagement {
    // Make the local plugin build available to the sample subproject.
    // The plugin directory is a standalone composite build so Gradle can
    // resolve `id("gg.grounds.push")` without a published artifact.
    includeBuild("plugin")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "grounds-push"

include(":sample")
