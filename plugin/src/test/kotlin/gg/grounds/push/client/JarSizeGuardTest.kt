package gg.grounds.push.client

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JarSizeGuardTest {

    @Test
    fun `1 MB is Ok`() {
        val result = JarSizeGuard.check(1L * 1024 * 1024)
        assertIs<JarSizeGuard.Result.Ok>(result)
    }

    @Test
    fun `exactly 40 MB is Ok`() {
        val result = JarSizeGuard.check(JarSizeGuard.WARN_BYTES)
        assertIs<JarSizeGuard.Result.Ok>(result)
    }

    @Test
    fun `40 MB plus 1 byte is Warn mentioning 50 MB cap`() {
        val result = JarSizeGuard.check(JarSizeGuard.WARN_BYTES + 1)
        assertIs<JarSizeGuard.Result.Warn>(result)
        assertTrue(result.message.contains("50 MB"), "Warn message should mention 50 MB cap")
    }

    @Test
    fun `exactly 50 MB is Warn (above 40 MB threshold, not yet Reject)`() {
        val result = JarSizeGuard.check(JarSizeGuard.MAX_BYTES)
        assertIs<JarSizeGuard.Result.Warn>(result)
    }

    @Test
    fun `50 MB plus 1 byte is Reject`() {
        val result = JarSizeGuard.check(JarSizeGuard.MAX_BYTES + 1)
        assertIs<JarSizeGuard.Result.Reject>(result)
    }

    @Test
    fun `reject message includes size in MB`() {
        val result = JarSizeGuard.check(JarSizeGuard.MAX_BYTES + 1)
        assertIs<JarSizeGuard.Result.Reject>(result)
        assertTrue(result.message.contains("MB"), "Reject message should include size in MB")
    }

    @Test
    fun `0 bytes is Ok`() {
        val result = JarSizeGuard.check(0L)
        assertIs<JarSizeGuard.Result.Ok>(result)
    }
}
