package gg.grounds.push.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.time.Duration

interface PushSseListener {
    fun onStatus(status: String, imageTag: String?, failureReason: String?)
    fun onLog(ts: String, line: String)
    fun onWarning(reason: String)
    fun onDone()
    fun onError(reason: String)
    fun onStreamClosed(normal: Boolean)
}

class GroundsForgeClient(
    apiUrl: String,
    private val token: String,
    connectTimeout: Duration = Duration.ofSeconds(20),
    callTimeout: Duration = Duration.ofMinutes(5),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeout)
        .callTimeout(callTimeout)
        .retryOnConnectionFailure(false)
        .build(),
) {
    private val apiUrl = apiUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    class ApiException(
        val statusCode: Int,
        val errorBody: ApiErrorBody?,
        message: String,
    ) : RuntimeException(message)

    fun createPush(
        manifestJson: String,
        target: String,
        jarFile: File,
        force: Boolean = false,
        flavor: String? = null,
        effectivePluginSourcesJson: String? = null,
    ): CreatePushResponse {
        // Multi-plugin bundles ship as tar.gz; the server detects the
        // upload shape via magic bytes (gzip 1f8b vs zip PK), so this
        // content-type is purely cosmetic for proxy log readability.
        val contentType = if (jarFile.name.endsWith(".tar.gz")) {
            "application/gzip".toMediaType()
        } else {
            "application/java-archive".toMediaType()
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "manifest", null,
                manifestJson.toByteArray().toRequestBody("application/json".toMediaType()),
            )
            .addFormDataPart("target", target)
            .addFormDataPart(
                "jar", jarFile.name,
                jarFile.asRequestBody(contentType),
            )
        if (!flavor.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("flavor", flavor)
        }
        if (effectivePluginSourcesJson != null) {
            bodyBuilder.addFormDataPart(
                "effectivePluginSources",
                null,
                effectivePluginSourcesJson.toByteArray().toRequestBody("application/json".toMediaType()),
            )
        }
        val body = bodyBuilder.build()
        val url = if (force) "$apiUrl/v1/pushes?force=true" else "$apiUrl/v1/pushes"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body.string()
            when (resp.code) {
                200, 202 -> return json.decodeFromString(CreatePushResponse.serializer(), raw)
                else -> throw toApiException(resp.code, raw)
            }
        }
    }

    fun getPush(pushId: String): PushDetail {
        val req = Request.Builder()
            .url("$apiUrl/v1/pushes/$pushId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body.string()
            if (resp.code == 200) return json.decodeFromString(PushDetail.serializer(), raw)
            throw toApiException(resp.code, raw)
        }
    }

    fun retryPush(pushId: String): CreatePushResponse {
        val req = Request.Builder()
            .url("$apiUrl/v1/pushes/$pushId/retry")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body.string()
            if (resp.code == 202) return json.decodeFromString(CreatePushResponse.serializer(), raw)
            throw toApiException(resp.code, raw)
        }
    }

    fun listBaseImages(): BaseImageCatalog {
        val req = Request.Builder()
            .url("$apiUrl/v1/base-images")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body.string()
            if (resp.code == 200) return json.decodeFromString(BaseImageCatalog.serializer(), raw)
            throw toApiException(resp.code, raw)
        }
    }

    /**
     * Opens an SSE connection. Returns an EventSource the caller can cancel.
     * The listener is invoked on OkHttp's dispatcher thread — callers should
     * marshal back to their own context as needed.
     */
    fun streamLogs(pushId: String, listener: PushSseListener): EventSource {
        val req = Request.Builder()
            .url("$apiUrl/v1/pushes/$pushId/logs")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .get()
            .build()

        // SSE needs a client WITHOUT a callTimeout so it can stream for minutes.
        val sseClient = httpClient.newBuilder()
            .readTimeout(Duration.ofHours(1))
            .callTimeout(Duration.ZERO)
            .build()

        return EventSources.createFactory(sseClient).newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    when (type) {
                        "status" -> {
                            val obj = parseObj(data)
                            listener.onStatus(
                                status = obj["status"]?.jsonPrimitive?.content ?: return,
                                imageTag = obj["imageTag"]?.jsonPrimitive?.contentOrNull,
                                failureReason = obj["failureReason"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                        "log" -> {
                            val obj = parseObj(data)
                            listener.onLog(
                                ts = obj["ts"]?.jsonPrimitive?.content ?: "",
                                line = obj["line"]?.jsonPrimitive?.content ?: "",
                            )
                        }
                        "warning" -> {
                            val obj = parseObj(data)
                            listener.onWarning(obj["reason"]?.jsonPrimitive?.content ?: "unknown")
                        }
                        "done" -> listener.onDone()
                        "error" -> {
                            val obj = parseObj(data)
                            listener.onError(
                                "stream_error (pushId=$pushId, " +
                                    "reason=${obj["reason"]?.jsonPrimitive?.content ?: "unknown"})"
                            )
                        }
                    }
                } catch (e: Exception) {
                    listener.onWarning(
                        "malformed_sse_frame (pushId=$pushId, event=${type ?: "message"}, " +
                            "reason=${e.message ?: e::class.java.simpleName})"
                    )
                }
            }

            override fun onClosed(eventSource: EventSource) { listener.onStreamClosed(normal = true) }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                listener.onWarning(
                    "stream_failed (pushId=$pushId, statusCode=${response?.code ?: "none"}, " +
                        "reason=${response?.message?.takeIf { it.isNotEmpty() } ?: t?.message ?: "unknown"})"
                )
                listener.onStreamClosed(normal = false)
            }
        })
    }

    private fun parseObj(raw: String): JsonObject =
        json.parseToJsonElement(raw) as JsonObject

    private fun toApiException(code: Int, raw: String): ApiException {
        val body = try {
            json.decodeFromString(ApiErrorBody.serializer(), raw)
        } catch (_: Exception) { null }
        val msg = body?.let { "${it.error}${it.message?.let { m -> ": $m" } ?: ""}" }
            ?: "HTTP $code${raw.take(200).let { if (it.isNotEmpty()) ": $it" else "" }}"
        return ApiException(code, body, msg)
    }
}
