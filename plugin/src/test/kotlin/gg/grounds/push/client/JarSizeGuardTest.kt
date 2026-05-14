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
    fun `exactly 80 MB is Ok`() {
        val result = JarSizeGuard.check(JarSizeGuard.WARN_BYTES)
        assertIs<JarSizeGuard.Result.Ok>(result)
    }

    @Test
    fun `80 MB plus 1 byte is Warn mentioning 100 MB cap`() {
        val result = JarSizeGuard.check(JarSizeGuard.WARN_BYTES + 1)
        assertIs<JarSizeGuard.Result.Warn>(result)
        assertTrue(result.message.contains("100 MB"), "Warn message should mention 100 MB cap")
    }

    @Test
    fun `exactly 100 MB is Warn (above 80 MB threshold, not yet Reject)`() {
        val result = JarSizeGuard.check(JarSizeGuard.MAX_BYTES)
        assertIs<JarSizeGuard.Result.Warn>(result)
    }

    @Test
    fun `100 MB plus 1 byte is Reject`() {
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
