package gg.grounds.push.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseDecoderTest {

    @Test
    fun `single complete event emitted`() {
        val decoder = SseDecoder()
        val events = decoder.feed("event: log\ndata: hello\n\n")
        assertEquals(1, events.size)
        assertEquals("log", events[0].name)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun `two events in one chunk emitted in order`() {
        val decoder = SseDecoder()
        val events = decoder.feed("event: log\ndata: first\n\nevent: status\ndata: second\n\n")
        assertEquals(2, events.size)
        assertEquals("log", events[0].name)
        assertEquals("first", events[0].data)
        assertEquals("status", events[1].name)
        assertEquals("second", events[1].data)
    }

    @Test
    fun `event split across two feeds first returns empty second returns completed event`() {
        val decoder = SseDecoder()
        val first = decoder.feed("event: log\ndata: partial")
        assertTrue(first.isEmpty(), "First feed should return no events (frame incomplete)")
        val second = decoder.feed("\n\n")
        assertEquals(1, second.size)
        assertEquals("log", second[0].name)
        assertEquals("partial", second[0].data)
    }

    @Test
    fun `only data without event defaults name to message`() {
        val decoder = SseDecoder()
        val events = decoder.feed("data: payload\n\n")
        assertEquals(1, events.size)
        assertEquals("message", events[0].name)
        assertEquals("payload", events[0].data)
    }

    @Test
    fun `multi-line data concatenates with newline`() {
        val decoder = SseDecoder()
        val events = decoder.feed("data: line1\ndata: line2\n\n")
        assertEquals(1, events.size)
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun `comment lines are ignored`() {
        val decoder = SseDecoder()
        val events = decoder.feed(": keepalive\nevent: ping\ndata: {}\n\n")
        assertEquals(1, events.size)
        assertEquals("ping", events[0].name)
        assertEquals("{}", events[0].data)
    }

    @Test
    fun `CRLF line endings are handled`() {
        val decoder = SseDecoder()
        val events = decoder.feed("event: status\r\ndata: ok\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("status", events[0].name)
        assertEquals("ok", events[0].data)
    }
}
