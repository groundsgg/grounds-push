package gg.grounds.push.manifest

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File
import java.io.Reader

data class GroundsYaml(
    val name: String,
    val type: String,              // "gamemode" | "plugin-paper" | "plugin-velocity" | "service"
    val baseImage: String,         // "paper" | "velocity" | "minestom" | "service"
    val jar: String = "build/libs/*.jar",
    /**
     * Multi-plugin bundle: list of glob/path entries, each resolves to a
     * plugin JAR that lands in `/app/plugins/` on the rendered server.
     * Mutually exclusive with `jar`. Length 2..10. Forbidden for
     * `type: service` — services are single-jar workloads with their
     * own ENTRYPOINT.
     */
    val plugins: List<String>? = null,
    val target: String? = null,    // optional — plugin extension's target wins when set
    val resources: Resources? = null,
) {
    data class Resources(val cpu: String? = null, val memory: String? = null)
}

class GroundsYamlParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object GroundsYamlParser {
    private val yaml = Yaml()

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
        val type = raw.requireString("type")
        val baseImage = raw.requireString("baseImage")
        val rawJar = raw.optString("jar")
        val plugins = raw.optStringList("plugins")
        if (plugins != null) {
            if (rawJar != null) throw GroundsYamlParseException(
                "grounds.yaml: 'plugins' and 'jar' are mutually exclusive — pick one",
            )
            if (plugins.size < 2) throw GroundsYamlParseException(
                "grounds.yaml: 'plugins' must contain at least 2 entries (use 'jar' for single-plugin)",
            )
            if (plugins.size > 10) throw GroundsYamlParseException(
                "grounds.yaml: 'plugins' supports at most 10 entries, got ${plugins.size}",
            )
            if (type == "service") throw GroundsYamlParseException(
                "grounds.yaml: 'plugins' is not supported for type 'service'",
            )
        }
        val jar = rawJar ?: "build/libs/*.jar"
        val target = raw.optString("target")
        val resourcesMap = raw["resources"]
        val resources = if (resourcesMap is Map<*, *>) {
            GroundsYaml.Resources(
                cpu = resourcesMap.optString("cpu"),
                memory = resourcesMap.optString("memory"),
            )
        } else if (resourcesMap == null) null else {
            throw GroundsYamlParseException(
                "grounds.yaml: 'resources' must be a mapping, got ${resourcesMap.javaClass.simpleName}",
            )
        }
        return GroundsYaml(
            name = name, type = type, baseImage = baseImage,
            jar = jar, plugins = plugins,
            target = target, resources = resources,
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

    private fun Map<*, *>.optStringList(key: String): List<String>? {
        val v = this[key] ?: return null
        if (v !is List<*>) throw GroundsYamlParseException(
            "grounds.yaml: field '$key' must be a sequence of strings, got ${v.javaClass.simpleName}",
        )
        return v.map { item ->
            if (item !is String || item.isEmpty()) throw GroundsYamlParseException(
                "grounds.yaml: field '$key' entries must be non-empty strings",
            )
            item
        }
    }
}
