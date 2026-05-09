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
| `baseImage` | yes | Logical base-image key from Forge's runtime catalog, for example `paper`, `velocity`, or `paper@0.8.2` for an explicit selectable version. |
| `jar` | no | JAR path relative to the project root. Defaults to `build/libs/*.jar`. If `groundsPush.jarFile` is not set, a non-default `jar` value is used before auto-detected `shadowJar`/`jar` outputs. Mutually exclusive with `plugins`. |
| `plugins` | no | List of 2..10 JAR paths bundled into one Paper/Velocity/gamemode server. See *Multi-plugin bundles* below. Forbidden for `type: service`. |
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
    baseImageCatalogMode.set("warn")
}
```

`baseImageCatalogMode` controls local catalog validation before upload:
`warn` validates when the catalog is reachable and continues on catalog lookup
errors, `strict` fails on lookup errors, and `off` skips the preflight.

`groundsPush.jarFile` is the highest-priority JAR override. If it is not set,
the plugin uses a non-default `jar` from `grounds.yaml`, then falls back to an
auto-detected `shadowJar` or `jar` task output.

## Multi-plugin bundles

Test several plugins together on a single Paper / Velocity / gamemode
server by listing their JARs in `plugins:`:

```yaml
name: combo
type: plugin-paper
baseImage: paper
plugins:
  - sub-projects/economy/build/libs/economy.jar
  - sub-projects/chat/build/libs/chat.jar
  - sub-projects/teams/build/libs/teams.jar
```

The plugin packs the listed JARs into a tar.gz that grounds-forge
forwards to the build pipeline. They land at `/app/plugins/` in
manifest order (numeric prefix preserves load order). Limits: 2..10
plugins, 50 MB total upload, no `service` type.

For dependent build tasks set `groundsPush.dependsOn(...)` per
sub-project so each JAR exists before push:

```kotlin
tasks.named("groundsPush") {
    dependsOn(":economy:jar", ":chat:jar", ":teams:jar")
}
```

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
