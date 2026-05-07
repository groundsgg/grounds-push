package gg.grounds.push.bundle

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginBundlerTest {
    private fun fakeJar(dir: File, name: String, payload: String): File {
        val f = File(dir, name)
        // Real JARs are zips; the bundler doesn't introspect contents, but
        // tests still write distinct payloads so we can detect the
        // wrong file landing under the wrong tar entry.
        f.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + payload.toByteArray())
        return f
    }

    @Test
    fun `bundles two jars under plugins prefix in manifest order`(@TempDir tmp: File) {
        val foo = fakeJar(tmp, "foo.jar", "FOO")
        val bar = fakeJar(tmp, "bar.jar", "BAR")
        val out = File(tmp, "out/bundle.tar.gz")

        PluginBundler.bundle(listOf(foo, bar), out)
        assertTrue(out.isFile)

        // Verify gzip magic.
        val bytes = out.readBytes()
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])

        // Walk entries — order must match input list.
        val entries = mutableListOf<Pair<String, String>>()
        TarArchiveInputStream(GzipCompressorInputStream(out.inputStream())).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                val payload = tar.readBytes()
                // Skip 4-byte zip header to recover the test payload.
                val data = payload.copyOfRange(4, payload.size).toString(Charsets.UTF_8)
                entries += e.name to data
                e = tar.nextEntry
            }
        }

        assertEquals(listOf("plugins/00-foo.jar", "plugins/01-bar.jar"), entries.map { it.first })
        assertEquals("FOO", entries[0].second)
        assertEquals("BAR", entries[1].second)
    }

    @Test
    fun `rejects single-jar input — single-plugin path uses plain jar upload`(@TempDir tmp: File) {
        val only = fakeJar(tmp, "only.jar", "X")
        assertThrows<IllegalArgumentException> {
            PluginBundler.bundle(listOf(only), File(tmp, "x.tar.gz"))
        }
    }

    @Test
    fun `rejects more than 10 entries`(@TempDir tmp: File) {
        val jars = (0..10).map { fakeJar(tmp, "p$it.jar", "P$it") }
        val e = assertThrows<IllegalArgumentException> {
            PluginBundler.bundle(jars, File(tmp, "x.tar.gz"))
        }
        assertNotNull(e.message)
    }

    @Test
    fun `rejects non-jar file extension`(@TempDir tmp: File) {
        val foo = fakeJar(tmp, "foo.jar", "FOO")
        val notJar = File(tmp, "bar.txt").also { it.writeText("hi") }
        assertThrows<IllegalArgumentException> {
            PluginBundler.bundle(listOf(foo, notJar), File(tmp, "x.tar.gz"))
        }
    }
}
