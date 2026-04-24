package gg.grounds.push.client

/** Minimal SSE decoder: accumulates raw bytes, emits completed events. */
class SseDecoder {
    private val buf = StringBuilder()
    private var event: String? = null
    private val data = StringBuilder()

    /**
     * Feed a chunk of SSE text. Returns zero or more complete events
     * assembled from the running buffer. Partial events stay buffered.
     */
    fun feed(chunk: String): List<SseEvent> {
        buf.append(chunk.replace("\r\n", "\n"))
        val events = mutableListOf<SseEvent>()

        while (true) {
            val nl = buf.indexOf('\n')
            if (nl < 0) break
            val line = buf.substring(0, nl)
            buf.delete(0, nl + 1)

            when {
                line.isEmpty() -> {
                    // Dispatch the accumulated event.
                    if (event != null || data.isNotEmpty()) {
                        events += SseEvent(
                            name = event ?: "message",
                            data = data.toString().trimEnd('\n'),
                        )
                    }
                    event = null
                    data.clear()
                }
                line.startsWith(":") -> {
                    // Comment line, ignore.
                }
                line.startsWith("event:") -> {
                    event = line.substring("event:".length).trim()
                }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring("data:".length).trim())
                }
                // Other fields (id:, retry:) — ignore in Phase 2.2.
            }
        }

        return events
    }
}

data class SseEvent(val name: String, val data: String)
