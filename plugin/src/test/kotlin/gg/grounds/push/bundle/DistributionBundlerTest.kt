package gg.grounds.push.bundle

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistributionBundlerTest {
    private fun installDir(tmp: File, scriptContent: String = "#!/bin/sh\necho myapp\n"): File {
        val dir = File(tmp, "install/myapp")
        val bin = File(dir, "bin").also { it.mkdirs() }
        val lib = File(dir, "lib").also { it.mkdirs() }
        File(bin, "myapp").writeText(scriptContent)
        File(bin, "myapp.bat").writeText("@echo off\necho myapp\n")
        File(lib, "a.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "A".toByteArray())
        File(lib, "b.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "B".toByteArray())
        return dir
    }

    private fun tarEntries(bytes: ByteArray): Map<String, Pair<Int, ByteArray>> {
        val entries = mutableMapOf<String, Pair<Int, ByteArray>>()
        TarArchiveInputStream(GzipCompressorInputStream(bytes.inputStream())).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                entries[e.name] = e.mode to tar.readBytes()
                e = tar.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `packs installDist output under app prefix, renaming the unix start script to bin-app`(@TempDir tmp: File) {
        val dir = installDir(tmp)
        val out = File(tmp, "out/bundle.tar.gz")

        DistributionBundler.bundle(dir, out)
        assertTrue(out.isFile)

        val entries = tarEntries(out.readBytes())
        assertEquals(setOf("app/bin/app", "app/lib/a.jar", "app/lib/b.jar"), entries.keys)

        // No .bat, and no leftover entry under the original script name.
        assertTrue("app/bin/myapp.bat" !in entries)
        assertTrue("app/bin/myapp" !in entries)

        // Executable bit survived the tar round-trip.
        val (mode, _) = entries.getValue("app/bin/app")
        assertEquals(0b111_101_101, mode and 0b111_111_111, "expected rwxr-xr-x, got mode=${mode.toString(8)}")
    }

    // A server is not only its code. `distributions { contents { from("maps") } }`
    // is how a gamemode ships the worlds it opens at runtime; packing only bin/
    // and lib/ would drop them in silence, and the image would build, start, and
    // then fail to find the world it is supposed to load.
    @Test
    fun `carries whatever else the distribution ships, not just bin and lib`(@TempDir tmp: File) {
        val dir = installDir(tmp)
        val arena = File(dir, "maps/arena").apply { mkdirs() }
        File(arena, "level.dat").writeBytes("LEVEL".toByteArray())
        File(dir, "README.txt").writeBytes("hi".toByteArray())
        val out = File(tmp, "out/bundle.tar.gz")

        DistributionBundler.bundle(dir, out)

        val entries = tarEntries(out.readBytes())
        assertTrue("app/maps/arena/level.dat" in entries, "shipped world must survive: ${entries.keys}")
        assertEquals("LEVEL", String(entries.getValue("app/maps/arena/level.dat").second))
        assertTrue("app/README.txt" in entries)
    }

    @Test
    fun `renamed start script content is unchanged — only the name moved`(@TempDir tmp: File) {
        val scriptContent = "#!/bin/sh\nAPP_HOME=\$(cd \"\$(dirname \"\$0\")/..\" && pwd)\nexec java -jar \"\$APP_HOME/lib/a.jar\"\n"
        val dir = installDir(tmp, scriptContent)
        val out = File(tmp, "out/bundle.tar.gz")

        DistributionBundler.bundle(dir, out)

        val entries = tarEntries(out.readBytes())
        val (_, content) = entries.getValue("app/bin/app")
        assertEquals(scriptContent, content.toString(Charsets.UTF_8))
    }

    @Test
    fun `rejects an install dir with no bin directory`(@TempDir tmp: File) {
        val dir = File(tmp, "install/myapp").also { it.mkdirs() }
        File(dir, "lib").mkdirs()
        assertThrows<IllegalArgumentException> {
            DistributionBundler.bundle(dir, File(tmp, "x.tar.gz"))
        }
    }

    @Test
    fun `rejects an install dir with no lib directory`(@TempDir tmp: File) {
        val dir = File(tmp, "install/myapp").also { it.mkdirs() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/myapp").writeText("#!/bin/sh\n")
        assertThrows<IllegalArgumentException> {
            DistributionBundler.bundle(dir, File(tmp, "x.tar.gz"))
        }
    }
}
