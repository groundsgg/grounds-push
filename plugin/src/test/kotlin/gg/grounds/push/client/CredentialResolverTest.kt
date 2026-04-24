package gg.grounds.push.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CredentialResolverTest {
    private class FakePlatform(
        val env: MutableMap<String, String> = mutableMapOf(),
        val homeDir: File,
        val osName: String = "Mac OS X",
    ) : Platform {
        val warnings = mutableListOf<String>()
        override fun env(name: String) = env[name]?.takeIf { it.isNotEmpty() }
        override fun homeDir() = homeDir
        override fun userDir() = homeDir
        override fun osName() = osName.lowercase()
        override fun isUnix() = !osName().contains("win")
        override fun warn(message: String) { warnings += message }
        override fun fileExists(f: File) = f.exists() && f.isFile
    }

    @Test
    fun `returns FromEnv when GROUNDS_TOKEN is set`(@TempDir tmp: File) {
        val plat = FakePlatform(env = mutableMapOf("GROUNDS_TOKEN" to "abc123"), homeDir = tmp)
        val c = CredentialResolver(plat).resolve()
        assertIs<Credentials.FromEnv>(c)
        assertEquals("abc123", c.accessToken)
    }

    @Test
    fun `falls back to file when GROUNDS_TOKEN empty`(@TempDir tmp: File) {
        val creds = File(tmp, "Library/Application Support/grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText(
            """{"version":1,"apiUrl":"https://x","accessToken":"tok","expiresAt":"2099-01-01T00:00:00Z"}"""
        )
        val plat = FakePlatform(homeDir = tmp)
        val c = CredentialResolver(plat).resolve()
        assertIs<Credentials.FromFile>(c)
        assertEquals("tok", c.accessToken)
        assertEquals("https://x", c.apiUrl)
    }

    @Test
    fun `XDG_CONFIG_HOME on linux`(@TempDir tmp: File) {
        val xdg = File(tmp, "xdg-cfg")
        val creds = File(xdg, "grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText("""{"version":1,"accessToken":"tok"}""")
        val plat = FakePlatform(
            env = mutableMapOf("XDG_CONFIG_HOME" to xdg.absolutePath),
            homeDir = tmp,
            osName = "Linux",
        )
        val c = CredentialResolver(plat).resolve()
        assertEquals("tok", c.accessToken)
    }

    @Test
    fun `windows path uses APPDATA`(@TempDir tmp: File) {
        val appdata = File(tmp, "appdata")
        val creds = File(appdata, "grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText("""{"version":1,"accessToken":"tok"}""")
        val plat = FakePlatform(
            env = mutableMapOf("APPDATA" to appdata.absolutePath),
            homeDir = tmp,
            osName = "Windows 10",
        )
        val c = CredentialResolver(plat).resolve()
        assertEquals("tok", c.accessToken)
    }

    @Test
    fun `expired token throws`(@TempDir tmp: File) {
        val creds = File(tmp, "Library/Application Support/grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText(
            """{"version":1,"accessToken":"old","expiresAt":"2020-01-01T00:00:00Z"}"""
        )
        val plat = FakePlatform(homeDir = tmp)
        val e = assertThrows<CredentialResolutionException> {
            CredentialResolver(plat).resolve()
        }
        assertTrue(e.message!!.contains("expired"))
    }

    @Test
    fun `unsupported version throws`(@TempDir tmp: File) {
        val creds = File(tmp, "Library/Application Support/grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText("""{"version":2,"accessToken":"x"}""")
        val plat = FakePlatform(homeDir = tmp)
        val e = assertThrows<CredentialResolutionException> {
            CredentialResolver(plat).resolve()
        }
        assertTrue(e.message!!.contains("version 2"))
    }

    @Test
    fun `malformed json throws with file path`(@TempDir tmp: File) {
        val creds = File(tmp, "Library/Application Support/grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText("{not json")
        val plat = FakePlatform(homeDir = tmp)
        val e = assertThrows<CredentialResolutionException> {
            CredentialResolver(plat).resolve()
        }
        assertTrue(e.message!!.contains(creds.absolutePath))
    }

    @Test
    fun `no env and no file throws with helpful hint`(@TempDir tmp: File) {
        val plat = FakePlatform(homeDir = tmp)
        val e = assertThrows<CredentialResolutionException> {
            CredentialResolver(plat).resolve()
        }
        assertTrue(e.message!!.contains("GROUNDS_TOKEN"))
    }

    @Test
    fun `warns on unix when perms are not 0600`(@TempDir tmp: File) {
        val creds = File(tmp, "Library/Application Support/grounds/credentials.json")
        creds.parentFile.mkdirs()
        creds.writeText("""{"version":1,"accessToken":"tok"}""")
        try {
            Files.setPosixFilePermissions(creds.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        } catch (_: UnsupportedOperationException) {
            return // non-POSIX FS, skip
        }
        val plat = FakePlatform(homeDir = tmp)
        CredentialResolver(plat).resolve()
        assertTrue(plat.warnings.any { it.contains("chmod") })
    }
}
