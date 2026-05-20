package gg.grounds.push.manifest

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File
import java.io.Reader

data class GroundsYaml(
    val name: String,
    val type: String? = null,      // "paper" | "velocity" | "gamemode" | "minestom" | "service"
    val baseImage: String? = null, // "paper" | "velocity" | "minestom" | "service"
    val jar: String = "build/libs/*.jar",
    /**
     * Multi-plugin bundle: list of glob/path entries, each resolves to a
     * plugin JAR that lands in `/app/plugins/` on the rendered server.
     * Mutually exclusive with `jar`. Length 2..10. Forbidden for
     * `type: service` and `type: minestom` — service-shaped workloads
     * are single-jar workloads with their own ENTRYPOINT.
     */
    val plugins: List<PluginEntry>? = null,
    val target: String? = null,    // optional — plugin extension's target wins when set
    val resources: Resources? = null,
    val flavors: Map<String, Flavor>? = null,
) {
    data class Resources(val cpu: String? = null, val memory: String? = null)

    data class Flavor(
        val type: String,
        val baseImage: String,
        val jar: String = "build/libs/*.jar",
        val plugins: List<PluginEntry>? = null,
        val resources: Resources? = null,
    )

    sealed interface PluginEntry {
        val source: String

        data class Legacy(override val source: String) : PluginEntry

        data class Structured(override val source: String) : PluginEntry
    }
}

class GroundsYamlParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object GroundsYamlParser {
    private val yaml = Yaml()
    private val flavorKeyPattern = Regex("^[a-z][a-z0-9-]{1,31}$")
    private val runtimeFieldKeys = setOf("type", "baseImage", "jar", "plugins", "resources")

    fun parse(reader: Reader): GroundsYaml {
        val raw = try {
            yaml.load<Any?>(reader)
        } catch (e: YAMLException) {
            throw GroundsYamlParseException(
                "grounds.yaml: YAML syntax error — ${e.message}",
                e,
            )
        }
        if (raw !is Map<*, *>) {
            throw GroundsYamlParseException(
                "grounds.yaml: expected a top-level mapping, got ${raw?.javaClass?.simpleName ?: "null"}",
            )
        }
        val name = raw.requireString("name")
        val type = raw.optString("type")
        val baseImage = raw.optString("baseImage")
        val flavors = raw.optFlavors("flavors")
        val hasTopLevelRuntimeFields = runtimeFieldKeys.any { raw.containsKey(it) }
        if (flavors != null && hasTopLevelRuntimeFields) {
            throw GroundsYamlParseException(
                "grounds.yaml: manifest must use either top-level runtime fields or flavors",
            )
        }
        if (flavors == null) {
            if (type == null) throw GroundsYamlParseException("grounds.yaml: missing required field 'type'")
            if (baseImage == null) throw GroundsYamlParseException("grounds.yaml: missing required field 'baseImage'")
        }
        val rawJar = raw.optString("jar")
        val plugins = raw.optPluginEntries("plugins")
        validatePluginEntries("plugins", type, rawJar, plugins)
        val jar = rawJar ?: "build/libs/*.jar"
        val target = raw.optString("target")
        val resources = raw.optResources("resources")
        return GroundsYaml(
            name = name, type = type, baseImage = baseImage,
            jar = jar, plugins = plugins,
            target = target, resources = resources, flavors = flavors,
        )
    }

    fun parse(file: File): GroundsYaml =
        file.bufferedReader().use { parse(it) }

    private fun Map<*, *>.requireString(key: String): String {
        val v = this[key]
            ?: throw GroundsYamlParseException("grounds.yaml: missing required field '$key'")
        if (v !is String) throw GroundsYamlParseException(
            "grounds.yaml: field '$key' must be a string, got ${v.javaClass.simpleName}",
        )
        if (v.isEmpty()) throw GroundsYamlParseException(
            "grounds.yaml: field '$key' must not be empty",
        )
        return v
    }

    private fun Map<*, *>.optString(key: String): String? {
        val v = this[key] ?: return null
        if (v !is String) throw GroundsYamlParseException(
            "grounds.yaml: field '$key' must be a string, got ${v.javaClass.simpleName}",
        )
        return v.ifEmpty { null }
    }

    private fun Map<*, *>.optResources(
        key: String,
        label: String = key,
    ): GroundsYaml.Resources? {
        val v = this[key] ?: return null
        if (v !is Map<*, *>) throw GroundsYamlParseException(
            "grounds.yaml: '$label' must be a mapping, got ${v.javaClass.simpleName}",
        )
        return GroundsYaml.Resources(
            cpu = v.optString("cpu"),
            memory = v.optString("memory"),
        )
    }

    private fun Map<*, *>.optFlavors(key: String): Map<String, GroundsYaml.Flavor>? {
        val v = this[key] ?: return null
        if (v !is Map<*, *>) throw GroundsYamlParseException(
            "grounds.yaml: field '$key' must be a mapping, got ${v.javaClass.simpleName}",
        )
        if (v.isEmpty()) throw GroundsYamlParseException(
            "grounds.yaml: field '$key' must include at least one flavor",
        )

        return v.entries.associate { (rawKey, rawFlavor) ->
            if (rawKey !is String || !flavorKeyPattern.matches(rawKey)) {
                throw GroundsYamlParseException(
                    "grounds.yaml: flavor key '$rawKey' must match ${flavorKeyPattern.pattern}",
                )
            }
            if (rawFlavor !is Map<*, *>) throw GroundsYamlParseException(
                "grounds.yaml: flavor '$rawKey' must be a mapping, got ${rawFlavor?.javaClass?.simpleName ?: "null"}",
            )
            val type = rawFlavor.requireString("type")
            val rawJar = rawFlavor.optString("jar")
            val plugins = rawFlavor.optPluginEntries("plugins", "flavors.$rawKey.plugins")
            validatePluginEntries("flavors.$rawKey.plugins", type, rawJar, plugins)
            rawKey to GroundsYaml.Flavor(
                type = type,
                baseImage = rawFlavor.requireString("baseImage"),
                jar = rawJar ?: "build/libs/*.jar",
                plugins = plugins,
                resources = rawFlavor.optResources("resources", "flavors.$rawKey.resources"),
            )
        }
    }

    private fun validatePluginEntries(
        fieldName: String,
        type: String?,
        rawJar: String?,
        plugins: List<GroundsYaml.PluginEntry>?,
    ) {
        if (plugins == null) return
        if (rawJar != null) throw GroundsYamlParseException(
            "grounds.yaml: '$fieldName' and 'jar' are mutually exclusive — pick one",
        )
        if (plugins.size < 2) throw GroundsYamlParseException(
            "grounds.yaml: '$fieldName' must contain at least 2 entries (use 'jar' for single-plugin)",
        )
        if (plugins.size > 10) throw GroundsYamlParseException(
            "grounds.yaml: '$fieldName' supports at most 10 entries, got ${plugins.size}",
        )
        if (type == "service" || type == "minestom") throw GroundsYamlParseException(
            "grounds.yaml: '$fieldName' is not supported for type '$type'",
        )
    }

    private fun Map<*, *>.optPluginEntries(
        key: String,
        label: String = key,
    ): List<GroundsYaml.PluginEntry>? {
        val v = this[key] ?: return null
        if (v !is List<*>) throw GroundsYamlParseException(
            "grounds.yaml: field '$label' must be a sequence of strings or mappings, got ${v.javaClass.simpleName}",
        )
        return v.map { item ->
            when (item) {
                is String -> {
                    if (item.isEmpty()) throw GroundsYamlParseException(
                        "grounds.yaml: field '$label' entries must be non-empty strings",
                    )
                    GroundsYaml.PluginEntry.Legacy(item)
                }
                is Map<*, *> -> {
                    // id/variant are consumed by the CLI workspace resolver. The Gradle plugin
                    // validates the shared schema but only needs source for bundling.
                    item.requirePluginString(label, "id")
                    item.optPluginString(label, "variant")
                    GroundsYaml.PluginEntry.Structured(
                        source = item.requirePluginString(label, "source"),
                    )
                }
                else -> throw GroundsYamlParseException(
                    "grounds.yaml: field '$label' entries must be non-empty strings or mappings",
                )
            }
        }
    }

    private fun Map<*, *>.requirePluginString(parent: String, key: String): String {
        val v = this[key]
            ?: throw GroundsYamlParseException(
                "grounds.yaml: field '$parent' structured entries must include '$key'",
            )
        if (v !is String) throw GroundsYamlParseException(
            "grounds.yaml: field '$parent.$key' must be a string, got ${v.javaClass.simpleName}",
        )
        if (v.isEmpty()) throw GroundsYamlParseException(
            "grounds.yaml: field '$parent.$key' must not be empty",
        )
        return v
    }

    private fun Map<*, *>.optPluginString(parent: String, key: String): String? {
        val v = this[key] ?: return null
        if (v !is String) throw GroundsYamlParseException(
            "grounds.yaml: field '$parent.$key' must be a string, got ${v.javaClass.simpleName}",
        )
        return v.ifEmpty { null }
    }
}
