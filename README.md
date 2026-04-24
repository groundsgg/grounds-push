# grounds-push

Gradle plugin for pushing Minecraft plugin / gamemode JARs to
[grounds-forge](https://github.com/groundsgg/grounds-forge).

**Phase:** 2.2 — scaffolding. Exposes `./gradlew groundsPush` for
upload + build + log streaming against the platform cluster.
No business logic yet — comes in later chapters.

## Install (once 0.1.0 is released)

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/grounds-push")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")  // packages:read
            }
        }
    }
}

// build.gradle.kts
plugins {
    id("java")
    id("gg.grounds.push") version "0.1.0"
}
```

## Local dev

```
./gradlew :plugin:build
./gradlew :plugin:test
./gradlew :sample:tasks   # lists grounds-push tasks wired into the sample
```

## Auth

The plugin reads a bearer token from:

1. `GROUNDS_TOKEN` environment variable, or
2. `~/.config/grounds/credentials.json` (XDG on Linux, `~/Library/Application Support/grounds/credentials.json` on macOS)

The Phase 4.1 CLI (`grounds login`) will be the writer of `credentials.json`.

## Bootstrap without CLI

Until `grounds login` (Phase 4.1) ships, obtain a token manually:

```bash
# Exchange Keycloak credentials for a bearer token (device flow or password grant)
export GROUNDS_TOKEN=$(curl -s -X POST \
  https://account.grounds.gg/realms/grounds/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=grounds-push' \
  -d "username=$YOUR_USERNAME" \
  -d "password=$YOUR_PASSWORD" \
  | jq -r .access_token)

./gradlew groundsPush
```

Tokens expire after ~1 hour. Re-run the curl to refresh. Phase 4.1 will automate this.
