package gg.grounds.push.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

internal class GitHubReleaseFetchException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Resolves a [SourceRef.GitHubRelease] to a JAR on disk.
 *
 * Caching contract: the cache key is `{owner}-{repo}-{tag}-{asset}`,
 * which is deterministic because [SourceRefParser] requires `tag` to
 * be a SemVer pin or a commit SHA — both are immutable on GitHub. So
 * "cache hit" really does mean "same bytes as before".
 *
 * Auth: reads `GITHUB_TOKEN` from the environment, falling back to
 * `~/.gradle/gradle.properties` `github.token=`. Public repos work
 * without auth. Anonymous requests against private repos return 404
 * (GitHub doesn't reveal that the repo exists) — we surface that as a
 * targeted error message naming the env var.
 */
internal class GitHubReleaseFetcher(
    private val httpClient: OkHttpClient = defaultClient(),
    private val apiBase: String = "https://api.github.com",
) {

    /** Returns the cached / freshly-downloaded JAR file. */
    fun fetch(
        ref: SourceRef.GitHubRelease,
        cacheDir: File,
        token: String?,
        logger: (String) -> Unit = {},
    ): File {
        Files.createDirectories(cacheDir.toPath())

        val cacheName = buildString {
            append(ref.owner).append('-')
            append(ref.repo).append('-')
            append(ref.tag).append('-')
            append(ref.asset ?: "_default")
            if (!toString().endsWith(".jar")) append(".jar")
        }
        val cached = File(cacheDir, cacheName)
        if (cached.isFile && cached.length() > 0) {
            logger("[grounds-push] Cache hit (${ref.raw} → ${cached.name})")
            return cached
        }

        // Step 1: list assets in the release.
        val release = fetchReleaseMeta(ref, token)
        val asset = pickAsset(ref, release.assets)

        // Step 2: download the chosen asset's bytes. The `assets/{id}`
        // URL with Accept: application/octet-stream is the canonical
        // way to download — it works for both public and private
        // repos when authed, and avoids the redirect dance of the
        // browser_download_url path for private repos.
        logger(
            "[grounds-push] Downloading ${ref.raw} " +
                "(asset=${asset.name}, size=${humanSize(asset.size)})",
        )
        downloadAsset(asset, token, cached)

        // Step 3 (optional): SHA256 verify if the release ships
        // a `<asset>.sha256` sibling. Best-effort only — releases
        // without one are accepted unchanged.
        verifyShaIfPresent(ref, release.assets, asset, cached, token, logger)

        logger("[grounds-push] Cached ${ref.raw} → ${cached.name}")
        return cached
    }

    private fun fetchReleaseMeta(
        ref: SourceRef.GitHubRelease,
        token: String?,
    ): ReleaseDto {
        val url = "$apiBase/repos/${ref.owner}/${ref.repo}/releases/tags/${ref.tag}"
        val req = githubRequest(url, token, "application/vnd.github+json")
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                val hint = if (token == null)
                    "release ${ref.tag} not found in ${ref.owner}/${ref.repo}; if the repo is private, set GITHUB_TOKEN"
                else
                    "release ${ref.tag} not found in ${ref.owner}/${ref.repo}"
                throw GitHubReleaseFetchException(hint)
            }
            if (!resp.isSuccessful) {
                throw GitHubReleaseFetchException(
                    "GitHub API ${resp.code} for ${ref.raw}: ${resp.body.string().take(200)}",
                )
            }
            return JSON.decodeFromString(ReleaseDto.serializer(), resp.body.string())
        }
    }

    private fun pickAsset(
        ref: SourceRef.GitHubRelease,
        assets: List<AssetDto>,
    ): AssetDto {
        val candidate = if (ref.asset != null) {
            assets.firstOrNull { it.name == ref.asset } ?: throw GitHubReleaseFetchException(
                "asset '${ref.asset}' not found on release ${ref.tag} of ${ref.owner}/${ref.repo} " +
                    "(found: ${assets.joinToString { it.name }})",
            )
        } else {
            assets.firstOrNull { it.name.endsWith(".jar") } ?: throw GitHubReleaseFetchException(
                "no .jar asset on release ${ref.tag} of ${ref.owner}/${ref.repo} " +
                    "(found: ${assets.joinToString { it.name }})",
            )
        }
        if (!candidate.name.endsWith(".jar")) {
            throw GitHubReleaseFetchException(
                "asset '${candidate.name}' is not a .jar; pick a different asset or release",
            )
        }
        return candidate
    }

    private fun downloadAsset(asset: AssetDto, token: String?, dest: File) {
        val req = githubRequest(asset.url, token, "application/octet-stream")
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw GitHubReleaseFetchException(
                    "asset download failed (${resp.code}) for ${asset.name}",
                )
            }
            val tmp = File(dest.parentFile, "${dest.name}.partial")
            resp.body.byteStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            // Atomic rename so a partially-written file never matches
            // the cache-hit path on a later run.
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun verifyShaIfPresent(
        ref: SourceRef.GitHubRelease,
        assets: List<AssetDto>,
        asset: AssetDto,
        downloaded: File,
        token: String?,
        logger: (String) -> Unit,
    ) {
        val shaAsset = assets.firstOrNull { it.name == "${asset.name}.sha256" } ?: return
        val req = githubRequest(shaAsset.url, token, "application/octet-stream")
        val expected = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            // .sha256 sidecars are usually `<sha>  <filename>\n` per
            // sha256sum(1). Take the first hex token.
            resp.body.string().trim().substringBefore(' ').lowercase()
        }
        val actual = sha256(downloaded)
        if (!expected.equals(actual, ignoreCase = true)) {
            downloaded.delete()
            throw GitHubReleaseFetchException(
                "SHA256 mismatch on ${ref.raw}: expected=$expected actual=$actual " +
                    "(asset=${asset.name}). Cache file deleted.",
            )
        }
        logger("[grounds-push] SHA256 verified for ${asset.name}")
    }

    private fun githubRequest(url: String, token: String?, accept: String): Request =
        Request.Builder()
            .url(url.toHttpUrl())
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "grounds-push")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .get()
            .build()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .callTimeout(Duration.ofMinutes(2))
            .build()

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun humanSize(bytes: Long): String {
            val kb = bytes.toDouble() / 1024
            val mb = kb / 1024
            return when {
                kb < 1 -> "$bytes B"
                mb < 1 -> "%.1f KB".format(kb)
                else -> "%.1f MB".format(mb)
            }
        }
    }

    @Serializable
    internal data class ReleaseDto(
        val tag_name: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    internal data class AssetDto(
        val name: String,
        val url: String,
        val size: Long = 0,
    )
}
