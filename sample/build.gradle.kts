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

// grounds.yaml will be added in Chapter 7
