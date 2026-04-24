package gg.grounds.push.client

import kotlinx.serialization.Serializable

@Serializable
data class CreatePushResponse(
    val pushId: String,
    val status: String,
    val reused: Boolean,
    val logsUrl: String? = null,
)

@Serializable
data class PushDetail(
    val id: String,
    val status: String,
    val target: String,
    val baseImage: String,
    val imageTag: String? = null,
    val failureReason: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val build: BuildDetail? = null,
) {
    @Serializable
    data class BuildDetail(
        val id: String,
        val status: String,
        val kanikoJobName: String? = null,
        val startedAt: String? = null,
        val finishedAt: String? = null,
    )
}

@Serializable
data class ApiErrorBody(
    val error: String,
    val message: String? = null,
    val allowed: List<String>? = null,
    val maxBytes: Long? = null,
    val required: List<String>? = null,
    val reason: String? = null,
    val status: String? = null,
)
