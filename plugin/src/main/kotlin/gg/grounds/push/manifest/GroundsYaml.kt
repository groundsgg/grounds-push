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
        val jar = raw.optString("jar") ?: "build/libs/*.jar"
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
            jar = jar, target = target, resources = resources,
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
}
