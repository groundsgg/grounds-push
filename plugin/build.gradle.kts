plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
}

group = "gg.grounds"
val versionOverride = project.findProperty("versionOverride") as? String
version = versionOverride ?: "dev"

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

repositories {
    mavenCentral()
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
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
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
