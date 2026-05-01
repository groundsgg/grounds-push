package gg.grounds.push.tasks

import gg.grounds.push.client.*
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.time.Duration

@DisableCachingByDefault(because = "Retries a remote grounds-forge push and has no reproducible local outputs.")
abstract class GroundsPushRetryTask : DefaultTask() {
    @get:Input @get:Optional abstract val apiUrl: Property<String>
    @get:Input abstract val timeoutMinutes: Property<Int>
    @get:Input abstract val connectTimeoutSeconds: Property<Int>

    @get:Internal
    val pushId: Property<String> = project.objects.property(String::class.java)

    @Option(option = "pushId", description = "pushId to retry (required)")
    fun setPushIdOption(v: String) { pushId.set(v) }

    @TaskAction
    fun run() {
        val id = pushId.orNull
            ?: throw GradleException("grounds-push-retry: --pushId=<id> is required")
        val creds = CredentialResolver().resolve()
        val resolvedApi = apiUrl.orNull ?: System.getenv("GROUNDS_API_URL") ?: "https://platform.grnds.io"
        val client = GroundsForgeClient(
            apiUrl = resolvedApi,
            token = creds.accessToken,
            connectTimeout = Duration.ofSeconds(connectTimeoutSeconds.get().toLong()),
            callTimeout = Duration.ofMinutes(timeoutMinutes.get().toLong()),
        )
        val retry = try {
            client.retryPush(id)
        } catch (e: GroundsForgeClient.ApiException) {
            throw GradleException("grounds-push-retry failed: ${e.message}", e)
        }
        logger.lifecycle("[grounds-push-retry] pushId=${retry.pushId} status=${retry.status}")
        // NOTE: streaming logs for the retry is identical to GroundsPushTask.streamAndWait.
        // For Phase 2.2 we print the logsUrl and expect the user to follow separately.
        logger.lifecycle("[grounds-push-retry] Logs: $resolvedApi${retry.logsUrl ?: "/v1/pushes/${retry.pushId}/logs"}")
    }
}
