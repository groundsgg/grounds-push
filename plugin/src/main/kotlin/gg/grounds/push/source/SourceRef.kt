package gg.grounds.push.source

/**
 * One entry in `grounds.yaml: plugins[]`. Resolved at task-action time
 * to a JAR `File` (downloaded for [GitHubRelease], looked up for
 * [GradleProject], or used directly for [Local]).
 *
 * The string forms accepted by [SourceRefParser]:
 *
 *   - `modules/economy/build/libs/economy.jar` → [Local] (relative)
 *   - `/opt/jars/legacy.jar`                   → [Local] (absolute)
 *   - `:economy`                               → [GradleProject]
 *   - `github:groundsgg/plugin-chat@v1.4.2`               → [GitHubRelease], first .jar asset
 *   - `github:groundsgg/plugin-chat@v1.4.2:chat-all.jar`  → [GitHubRelease], named asset
 */
internal sealed interface SourceRef {
    /** Original raw string from `grounds.yaml`, retained for error messages. */
    val raw: String

    data class Local(
        override val raw: String,
        val path: String,
        val isAbsolute: Boolean,
    ) : SourceRef

    data class GradleProject(
        override val raw: String,
        /** Gradle project path including the leading colon, e.g. `":economy"`. */
        val projectPath: String,
    ) : SourceRef

    data class GitHubRelease(
        override val raw: String,
        val owner: String,
        val repo: String,
        /** Either a SemVer-shaped tag (`v1.2.3`, optional pre-release) or a 40-char commit SHA. */
        val tag: String,
        /** Specific .jar asset name; `null` means "first .jar in the release". */
        val asset: String?,
    ) : SourceRef
}
