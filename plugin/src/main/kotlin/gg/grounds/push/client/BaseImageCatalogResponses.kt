package gg.grounds.push.client

import kotlinx.serialization.Serializable

@Serializable
data class BaseImageCatalog(val items: List<BaseImageSource> = emptyList())

@Serializable
data class BaseImageSource(
    val key: String,
    val displayName: String,
    val manifestType: String,
    val image: String,
    val defaultChannel: String? = null,
    val channels: List<BaseImageChannel> = emptyList(),
    val versions: List<BaseImageVersion> = emptyList(),
)

@Serializable
data class BaseImageChannel(val name: String, val version: String)

@Serializable
data class BaseImageVersion(
    val version: String,
    val tag: String? = null,
    val digest: String? = null,
    val state: String? = null,
    val selectable: Boolean = true,
)
