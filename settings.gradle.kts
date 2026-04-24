rootProject.name = "grounds-push"

pluginManagement {
    // Make the local plugin build available to the sample subproject.
    // The plugin directory is a standalone composite build so Gradle can
    // resolve `id("gg.grounds.push")` without a published artifact.
    includeBuild("plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Required by :sample — Paper API is distributed from their own Maven repo.
        maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    }
}

include(":sample")
