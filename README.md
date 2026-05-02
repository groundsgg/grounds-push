# grounds-push

Gradle plugin for pushing Minecraft plugin / gamemode JARs to
[grounds-forge](https://github.com/groundsgg/grounds-forge).

The plugin provides `./gradlew groundsPush` for uploading a JAR and
`grounds.yaml`, creating a remote build, and streaming build logs back into
Gradle.

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

## Configure a project

Create `grounds.yaml` in the project root:

```yaml
name: my-plugin
type: plugin-paper
baseImage: paper
jar: build/libs/my-plugin.jar
target: dev
resources:
  cpu: 500m
  memory: 512Mi
```

Schema:

| Field | Required | Description |
| --- | --- | --- |
| `name` | yes | Grounds component name. |
| `type` | yes | One of `gamemode`, `plugin-paper`, `plugin-velocity`, or `service`. |
| `baseImage` | yes | One of `paper`, `velocity`, `minestom`, or `service`. |
| `jar` | no | JAR path relative to the project root. Defaults to `build/libs/*.jar`. If `groundsPush.jarFile` is not set, a non-default `jar` value is used before auto-detected `shadowJar`/`jar` outputs. |
| `target` | no | `dev` or `staging`. The Gradle extension and `--target` option override this value. |
| `resources.cpu` | no | Requested CPU, for example `500m`. |
| `resources.memory` | no | Requested memory, for example `512Mi`. |

Optional Gradle configuration:

```kotlin
groundsPush {
    apiUrl.set("https://platform.grnds.io")
    manifestFile.set(layout.projectDirectory.file("grounds.yaml"))
    jarFile.set(layout.projectDirectory.file("build/libs/my-plugin.jar"))
    target.set("dev")
    timeoutMinutes.set(5)
    connectTimeoutSeconds.set(20)
    failOnWhitelistError.set(true)
}
```

`groundsPush.jarFile` is the highest-priority JAR override. If it is not set,
the plugin uses a non-default `jar` from `grounds.yaml`, then falls back to an
auto-detected `shadowJar` or `jar` task output.

## Push

```bash
./gradlew groundsPush
./gradlew groundsPush --target=staging
```

Supported targets are `dev` and `staging`.

API URL precedence is:

1. `groundsPush.apiUrl`
2. `GROUNDS_API_URL`
3. `apiUrl` in `credentials.json`
4. `https://platform.grnds.io`

## Retry a push

Retry a failed push by ID:

```bash
./gradlew groundsPushRetry --pushId=<push-id>
```

`groundsPushRetry` uses the same API URL precedence as `groundsPush`. It
reuses the server-stored JAR for the push ID and prints the logs URL returned
by grounds-forge.

## Auth

The plugin reads a bearer token from:

1. `GROUNDS_TOKEN` environment variable, or
2. `~/.config/grounds/credentials.json` (XDG on Linux, `~/Library/Application Support/grounds/credentials.json` on macOS)

The `grounds login` CLI will write `credentials.json` once it is available.

## Bootstrap without CLI

Until `grounds login` ships, obtain a token manually:

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

Tokens expire after ~1 hour. Re-run the curl to refresh until `grounds login`
automates this flow.

## Common failures

| Symptom | Fix |
| --- | --- |
| `No credentials found` | Set `GROUNDS_TOKEN`, or create credentials with `grounds login` once the CLI is available. |
| `Token expired` | Refresh credentials with `grounds login`, or export a fresh `GROUNDS_TOKEN`. |
| `JAR not found` | Run the build task, set `groundsPush.jarFile`, or set `jar` in `grounds.yaml` to the produced artifact. |
| `target must be 'dev' or 'staging'` | Use `target: dev`, `target: staging`, or `./gradlew groundsPush --target=staging`. |
| `JAR is ... exceeding the 50 MB cap` | Trim bundled dependencies or ask the platform team whether the cap should be raised. |
| `not_whitelisted` | Request access for the component/target. For non-blocking local workflows, set `failOnWhitelistError.set(false)`. |
| `stream closed and status poll failed` | Retry after checking the logged `pushId`, `statusCode`, and `reason`; if it persists, include those fields when reporting the issue. |
