package gg.grounds.push.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.format.DateTimeParseException

sealed interface Credentials {
    val accessToken: String

    data class FromEnv(override val accessToken: String) : Credentials

    data class FromFile(
        override val accessToken: String,
        val apiUrl: String?,
        val expiresAt: Instant?,
        val sourcePath: File,
    ) : Credentials
}

class CredentialResolutionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface Platform {
    fun env(name: String): String?
    fun homeDir(): File
    fun userDir(): File // for Windows APPDATA fallback computation
    fun osName(): String
    fun isUnix(): Boolean
    fun warn(message: String)
    fun fileExists(f: File): Boolean
}

object DefaultPlatform : Platform {
    override fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
    override fun homeDir() = File(System.getProperty("user.home"))
    override fun userDir() = File(System.getProperty("user.dir"))
    override fun osName() = System.getProperty("os.name").lowercase()
    override fun isUnix() = !osName().contains("win")
    override fun warn(message: String) = System.err.println("[grounds-push] $message")
    override fun fileExists(f: File) = f.exists() && f.isFile
}

@Serializable
private data class CredentialsFile(
    val version: Int,
    val apiUrl: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: String? = null,
    val userId: String? = null,
)

class CredentialResolver(
    private val platform: Platform = DefaultPlatform,
    private val now: () -> Instant = { Instant.now() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(): Credentials {
        // 1. Env var.
        platform.env("GROUNDS_TOKEN")?.let { token ->
            return Credentials.FromEnv(token)
        }

        // 2. Credentials file.
        val path = credentialsFilePath()
        if (!platform.fileExists(path)) {
            throw CredentialResolutionException(
                "No credentials found.\n" +
                    "Set GROUNDS_TOKEN, or run 'grounds login' once the CLI is installed.\n" +
                    "Expected credentials file at: ${path.absolutePath}"
            )
        }

        // 3. Permissions check (unix only).
        if (platform.isUnix()) checkUnixPermissions(path)

        val parsed = try {
            json.decodeFromString(CredentialsFile.serializer(), path.readText())
        } catch (e: Exception) {
            throw CredentialResolutionException(
                "Failed to parse credentials file at ${path.absolutePath}: ${e.message}",
                e,
            )
        }
        if (parsed.version != 1) {
            throw CredentialResolutionException(
                "credentials.json version ${parsed.version} is not supported; " +
                    "upgrade grounds-push or 'grounds login' to migrate."
            )
        }

        // 4. Expiry check.
        val expires = parsed.expiresAt?.let { parseInstant(it, path) }
        if (expires != null && expires.isBefore(now())) {
            throw CredentialResolutionException(
                "Token expired at $expires; run 'grounds login' or set GROUNDS_TOKEN."
            )
        }

        if (parsed.accessToken.isEmpty()) {
            throw CredentialResolutionException(
                "credentials.json at ${path.absolutePath} has an empty accessToken"
            )
        }

        return Credentials.FromFile(
            accessToken = parsed.accessToken,
            apiUrl = parsed.apiUrl,
            expiresAt = expires,
            sourcePath = path,
        )
    }

    internal fun credentialsFilePath(): File {
        val os = platform.osName()
        val home = platform.homeDir()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                File(home, "Library/Application Support/grounds/credentials.json")
            os.contains("win") -> {
                val appData = platform.env("APPDATA") ?: File(home, "AppData/Roaming").absolutePath
                File(appData, "grounds/credentials.json")
            }
            else -> {
                val xdg = platform.env("XDG_CONFIG_HOME")
                val dir = if (xdg != null) File(xdg) else File(home, ".config")
                File(dir, "grounds/credentials.json")
            }
        }
    }

    private fun checkUnixPermissions(file: File) {
        try {
            val perms = Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            val groupOrOther = perms.any {
                it in setOf(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE,
                )
            }
            if (groupOrOther) {
                platform.warn(
                    "Credential file permissions are insecure " +
                        "(path=${file.absolutePath}, recommendation=chmod_0600)"
                )
            }
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem; skip.
        }
    }

    private fun parseInstant(raw: String, file: File): Instant {
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw CredentialResolutionException(
                "credentials.json at ${file.absolutePath} has invalid expiresAt '$raw'",
                e,
            )
        }
    }
}
