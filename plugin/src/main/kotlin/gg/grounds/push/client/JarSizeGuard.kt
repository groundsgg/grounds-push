package gg.grounds.push.client

object JarSizeGuard {
    const val MAX_BYTES: Long = 150L * 1024 * 1024
    const val WARN_BYTES: Long = 120L * 1024 * 1024

    sealed interface Result {
        data object Ok : Result
        data class Warn(val message: String) : Result
        data class Reject(val message: String) : Result
    }

    fun check(sizeBytes: Long): Result = when {
        sizeBytes > MAX_BYTES -> Result.Reject(
            "JAR is ${human(sizeBytes)}, exceeding the 150 MB cap. " +
                "Trim dependencies or contact platform-admin if the cap needs raising."
        )
        sizeBytes > WARN_BYTES -> Result.Warn(
            "JAR is ${human(sizeBytes)}, approaching the 150 MB cap. Consider minimising."
        )
        else -> Result.Ok
    }

    private fun human(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }
}
