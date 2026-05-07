package gg.grounds.push.source

import java.io.File

internal class SourceRefParseException(message: String) : RuntimeException(message)

/**
 * Parses `grounds.yaml: plugins[]` strings into [SourceRef]s. Enforces
 * security policy at parse time so callers can't accidentally bypass
 * the allowlist by skipping straight to a fetcher:
 *
 *   - GitHub owner is hardcoded `groundsgg` (case-insensitive, exact)
 *   - GitHub tag must be a SemVer-shaped tag (`v1.2.3` + optional
 *     pre-release / build metadata) OR a 40-char hex commit SHA, so
 *     bundles stay reproducible (no `@latest` / `@main`)
 *   - GitHub asset, when set, must end in `.jar` (no source tarballs,
 *     no zips bundled together)
 *
 * Defense-in-depth: the same checks run server-side in forge against
 * the `pluginSources` field of the manifest.
 */
internal object SourceRefParser {

    private const val ALLOWED_OWNER = "groundsgg"

    private val SEMVER_TAG = Regex("^v\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?$")
    private val COMMIT_SHA = Regex("^[a-f0-9]{40}$")
    private val REPO_NAME = Regex("^[A-Za-z0-9._-]+$")

    // github:owner/repo@tag        -> groups: owner, repo, tag, ""
    // github:owner/repo@tag:asset  -> groups: owner, repo, tag, asset
    private val GITHUB_FORM = Regex(
        "^github:([^/\\s]+)/([^@\\s]+)@([^:\\s]+)(?::(.+))?$",
    )

    fun parse(raw: String): SourceRef {
        if (raw.isBlank()) {
            throw SourceRefParseException("plugins[] entry is blank")
        }
        return when {
            raw.startsWith("github:") -> parseGitHub(raw)
            raw.startsWith(":") -> parseGradleProject(raw)
            else -> parseLocal(raw)
        }
    }

    fun parseAll(raws: List<String>): List<SourceRef> = raws.map(::parse)

    private fun parseLocal(raw: String): SourceRef.Local {
        // We don't filesystem-check here — parsing is independent of
        // the working directory. The task-action resolves + verifies.
        return SourceRef.Local(
            raw = raw,
            path = raw,
            isAbsolute = File(raw).isAbsolute,
        )
    }

    private fun parseGradleProject(raw: String): SourceRef.GradleProject {
        // Gradle accepts ":a", ":a:b", ":a:b:c". Reject empty segments
        // and trailing colons up front.
        val segments = raw.removePrefix(":").split(":")
        if (segments.isEmpty() || segments.any { it.isBlank() }) {
            throw SourceRefParseException(
                "Gradle project ref '$raw' has empty path segments — expected ':project' or ':a:b'",
            )
        }
        return SourceRef.GradleProject(raw = raw, projectPath = raw)
    }

    private fun parseGitHub(raw: String): SourceRef.GitHubRelease {
        val m = GITHUB_FORM.matchEntire(raw) ?: throw SourceRefParseException(
            "GitHub ref '$raw' does not match 'github:<owner>/<repo>@<tag>[:<asset>]'",
        )
        val owner = m.groupValues[1]
        val repo = m.groupValues[2]
        val tag = m.groupValues[3]
        val asset = m.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }

        if (!owner.equals(ALLOWED_OWNER, ignoreCase = true)) {
            throw SourceRefParseException(
                "GitHub ref '$raw' rejected: only owner '$ALLOWED_OWNER' is allowed " +
                    "(got '$owner'). This is a hard security boundary, not configurable.",
            )
        }
        if (!REPO_NAME.matches(repo)) {
            throw SourceRefParseException(
                "GitHub ref '$raw' has invalid repo name '$repo' (allowed: alphanumerics, dot, dash, underscore)",
            )
        }
        if (!SEMVER_TAG.matches(tag) && !COMMIT_SHA.matches(tag)) {
            throw SourceRefParseException(
                "GitHub ref '$raw' must pin a SemVer tag (vX.Y.Z) or a 40-char commit SHA " +
                    "(got '$tag'). 'latest', branch names, and partial SHAs are intentionally not accepted.",
            )
        }
        if (asset != null && !asset.endsWith(".jar")) {
            throw SourceRefParseException(
                "GitHub ref '$raw' asset '$asset' must be a .jar (no source tarballs / archives)",
            )
        }
        return SourceRef.GitHubRelease(
            raw = raw,
            owner = owner.lowercase(),
            repo = repo,
            tag = tag,
            asset = asset,
        )
    }
}
