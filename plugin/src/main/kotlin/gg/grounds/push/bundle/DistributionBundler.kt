package gg.grounds.push.bundle

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Packs a Gradle `application` plugin's `installDist` output (`bin/` +
 * `lib/`) into a tar.gz that grounds-forge forwards to kaniko unchanged.
 * Unlike a plugin, a Minestom server's binary IS the server — forge's
 * Dockerfile for it is a fixed:
 *
 *     COPY app/ /app/
 *     ENTRYPOINT ["/app/bin/app"]
 *
 * so every entry is prefixed `app/`, and Gradle's unix start script
 * (named after the project, e.g. `bin/myapp`) is renamed to the fixed
 * `bin/app` the ENTRYPOINT expects. That rename is safe: the script
 * derives APP_HOME from its own location on disk, not its filename.
 * The `.bat` script is dropped — the image is Linux.
 */
internal object DistributionBundler {
    fun bundle(installDir: File, output: File) {
        require(installDir.isDirectory) {
            "DistributionBundler: not a directory: ${installDir.absolutePath}"
        }
        val binDir = File(installDir, "bin")
        val libDir = File(installDir, "lib")
        require(binDir.isDirectory) {
            "DistributionBundler: no bin/ under ${installDir.absolutePath} — did installDist run?"
        }
        require(libDir.isDirectory) {
            "DistributionBundler: no lib/ under ${installDir.absolutePath} — did installDist run?"
        }

        val startScripts = binDir.listFiles { f -> f.isFile && !f.name.endsWith(".bat") }.orEmpty()
        require(startScripts.size == 1) {
            "DistributionBundler: expected exactly one unix start script under ${binDir.absolutePath}, " +
                "found ${startScripts.size}"
        }
        val startScript = startScripts[0]

        // Everything the distribution carries, not just bin/ and lib/. A server
        // is not only its code: `distributions { contents { from("maps") } }` is
        // how a gamemode ships the worlds it loads at runtime, and packing a
        // hand-picked subset would drop them in silence — the image would build,
        // start, and then fail to find the world it is supposed to open.
        val payload = installDir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.name.endsWith(".bat") } // the image is Linux
            .sortedBy { it.relativeTo(installDir).invariantPath() }
            .toList()

        Files.createDirectories(output.toPath().parent)
        output.outputStream().use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                    payload.forEach { file ->
                        val isStartScript = file == startScript
                        // Gradle names the start script after the project; the
                        // ENTRYPOINT is a fixed /app/bin/app. Renaming is safe —
                        // the script derives APP_HOME from its own location on
                        // disk, not from its filename.
                        val path = if (isStartScript) {
                            "app/bin/app"
                        } else {
                            "app/${file.relativeTo(installDir).invariantPath()}"
                        }
                        val entry = TarArchiveEntry(file, path)
                        if (isStartScript) {
                            // The container execs this file directly. Without the
                            // executable bit surviving the tar round-trip, the
                            // build produces an image that cannot start.
                            entry.mode = EXECUTABLE_FILE_MODE
                        }
                        tar.putArchiveEntry(entry)
                        file.inputStream().use { it.copyTo(tar) }
                        tar.closeArchiveEntry()
                    }

                    tar.finish()
                }
            }
        }
    }

    /** Tar paths are `/`-separated, whatever the host's separator is. */
    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    // Regular file (mirrors TarArchiveEntry's own DEFAULT_FILE_MODE
    // convention) with rwxr-xr-x permissions instead of the default rw-r--r--.
    private val EXECUTABLE_FILE_MODE = "100755".toInt(8)
}
