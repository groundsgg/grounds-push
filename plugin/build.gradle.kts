plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.serialization") version "1.9.25"
}

group = "gg.grounds"
// Version is driven by release-please's manifest file. For local dev, read
// from .release-please-manifest.json at the root, else default to "0.1.0-dev".
version = providers.fileContents(rootProject.layout.projectDirectory.file(".release-please-manifest.json"))
    .asText
    .map { text ->
        val m = Regex("\".\":\\s*\"([^\"]+)\"").find(text)
        m?.groupValues?.get(1) ?: "0.1.0-dev"
    }
    .getOrElse("0.1.0-dev")

java {
    toolchain {
        // Spec says 17; bumped to 21 — Paper 1.21+ requires 21 and only 21 is
        // available locally (no Java 17 install). CI uses actions/setup-java
        // Temurin 21. JVM target bytecode stays compatible with Paper/Velocity.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        create("groundsPush") {
            id = "gg.grounds.push"
            implementationClass = "gg.grounds.push.GroundsPushPlugin"
            displayName = "grounds-push"
            description = "Push Minecraft plugin/gamemode JARs to grounds-forge"
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/groundsgg/grounds-push")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
