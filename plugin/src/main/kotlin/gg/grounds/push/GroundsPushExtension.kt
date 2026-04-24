package gg.grounds.push

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class GroundsPushExtension @Inject constructor(objects: ObjectFactory) {
    /** grounds-forge API URL. Defaults to the credentials file's apiUrl, or https://forge.grnds.io */
    abstract val apiUrl: Property<String>
    /** Path to grounds.yaml. Defaults to `<projectDir>/grounds.yaml`. */
    abstract val manifestFile: RegularFileProperty
    /** JAR to push. Auto-detected from shadowJar/jar if not set. */
    abstract val jarFile: RegularFileProperty
    /** target override — "dev" or "staging". */
    abstract val target: Property<String>
    abstract val timeoutMinutes: Property<Int>
    abstract val connectTimeoutSeconds: Property<Int>
    abstract val failOnWhitelistError: Property<Boolean>

    init {
        timeoutMinutes.convention(5)
        connectTimeoutSeconds.convention(20)
        failOnWhitelistError.convention(true)
    }
}
