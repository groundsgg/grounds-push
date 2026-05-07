package gg.grounds.push.bundle

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Packs a list of plugin JARs into a tar.gz that grounds-forge
 * forwards to kaniko unchanged. Layout matches what the bundle
 * Dockerfile expects:
 *
 *     plugins/00-foo.jar
 *     plugins/01-bar.jar
 *
 * Numeric prefixes preserve manifest order in case two plugins
 * provide colliding base filenames; Paper's `pluginsFolder` scan is
 * deterministic by file order, which makes load-order debugging
 * tractable.
 */
internal object PluginBundler {
    private const val MAX_ENTRIES = 10

    fun bundle(jars: List<File>, output: File) {
        require(jars.size in 2..MAX_ENTRIES) {
            "PluginBundler: expected 2..$MAX_ENTRIES jars, got ${jars.size}"
        }
        for (jar in jars) {
            require(jar.isFile) { "PluginBundler: not a regular file: ${jar.absolutePath}" }
            require(jar.name.endsWith(".jar")) {
                "PluginBundler: entry must be a .jar, got ${jar.name}"
            }
        }
        Files.createDirectories(output.toPath().parent)
        output.outputStream().use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    jars.forEachIndexed { idx, jar ->
                        val name = "plugins/%02d-%s".format(idx, jar.name)
                        val entry = TarArchiveEntry(jar, name)
                        tar.putArchiveEntry(entry)
                        jar.inputStream().use { it.copyTo(tar) }
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
    }
}
