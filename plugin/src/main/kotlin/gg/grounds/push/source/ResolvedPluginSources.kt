package gg.grounds.push.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class ResolvedPluginSourcesFile(
    val plugins: List<ResolvedPluginSource> = emptyList(),
    val effectivePluginSources: List<EffectivePluginSource> = emptyList(),
)

@Serializable
internal data class ResolvedPluginSource(
    val id: String,
    val variant: String? = null,
    val localPath: String? = null,
    val source: String? = null,
)

@Serializable
internal data class EffectivePluginSource(
    val id: String,
    val variant: String? = null,
    val effective: String,
    val defaultSource: String? = null,
    val source: String? = null,
    val artifactName: String? = null,
    val artifactSha256: String? = null,
    val git: GitMetadata? = null,
)

@Serializable
internal data class GitMetadata(
    val remote: String? = null,
    val commit: String? = null,
    val dirty: Boolean? = null,
)

internal object ResolvedPluginSources {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun parse(file: File): ResolvedPluginSourcesFile =
        json.decodeFromString(ResolvedPluginSourcesFile.serializer(), file.readText())
}
