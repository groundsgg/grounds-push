plugins {
    `java-library`
    id("gg.grounds.push")
}

group = "gg.grounds.sample"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    // Required by Paper API.
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    // compileOnly — Paper provides these at runtime.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks.named<Jar>("jar") {
    // Keep the JAR small; just the sample class.
    manifest {
        attributes["Implementation-Title"] = "grounds-push-sample"
    }
}

groundsPush {
    // For local testing only — real users set GROUNDS_API_URL or configure ~/.config/grounds/credentials.json.
    apiUrl.set(providers.environmentVariable("GROUNDS_API_URL").orElse("https://platform.grnds.io"))
}
