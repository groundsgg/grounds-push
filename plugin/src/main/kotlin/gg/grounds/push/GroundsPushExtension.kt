package gg.grounds.push

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class GroundsPushExtension @Inject constructor(objects: ObjectFactory) {
    /** grounds-forge API URL. Precedence: this value, GROUNDS_API_URL, credentials file apiUrl, https://platform.grnds.io. */
    abstract val apiUrl: Property<String>
    /** Path to grounds.yaml. Defaults to `<projectDir>/grounds.yaml`. */
    abstract val manifestFile: RegularFileProperty
    /** JAR to push. Auto-detected from shadowJar/jar if not set. */
    abstract val jarFile: RegularFileProperty
    /** target override — "dev" or "staging". */
    abstract val target: Property<String>
    /** App flavor to push when grounds.yaml declares `flavors:`. */
    abstract val flavor: Property<String>
    abstract val timeoutMinutes: Property<Int>
    abstract val connectTimeoutSeconds: Property<Int>
    abstract val failOnWhitelistError: Property<Boolean>
    /** Base-image catalog validation mode: warn, strict, or off. */
    abstract val baseImageCatalogMode: Property<String>

    /** `groundsTestLocal` override — Paper version. Defaults to 1.21.4 (matches forge baseImage). */
    abstract val paperVersion: Property<String>
    /** `groundsTestLocal` override — Velocity version. Defaults to 3.4.0-SNAPSHOT. */
    abstract val velocityVersion: Property<String>

    init {
        timeoutMinutes.convention(5)
        connectTimeoutSeconds.convention(20)
        failOnWhitelistError.convention(true)
        baseImageCatalogMode.convention("warn")
    }
}
