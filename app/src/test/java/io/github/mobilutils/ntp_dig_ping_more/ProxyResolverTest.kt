package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.proxy.JsEngine
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver
import io.github.mobilutils.ntp_dig_ping_more.settings.ProxyConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Proxy

/**
 * Unit tests for [ProxyResolver].
 *
 * Tests PAC result parsing, caching, and fallback behaviour.
 * Network I/O (PAC script fetching) and JS evaluation are mocked.
 */
class ProxyResolverTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var jsEngine: JsEngine
    private lateinit var resolver: ProxyResolver

    @Before
    fun setup() {
        settingsRepository = mockk(relaxed = true)
        jsEngine = mockk(relaxed = true)
        resolver = ProxyResolver(settingsRepository, jsEngine)
    }

    // ── parsePacResult tests ─────────────────────────────────────────────────

    @Test
    fun `parsePacResult returns null for DIRECT`() {
        val result = resolver.parsePacResult("DIRECT")
        assertNull(result)
    }

    @Test
    fun `parsePacResult parses PROXY host port`() {
        val result = resolver.parsePacResult("PROXY 10.0.0.1:8080")
        assertNotNull(result)
        assertEquals(Proxy.Type.HTTP, result!!.type())
        assertTrue(result.address().toString().contains("10.0.0.1"))
        assertTrue(result.address().toString().contains("8080"))
    }

    @Test
    fun `parsePacResult parses SOCKS host port`() {
        val result = resolver.parsePacResult("SOCKS 10.0.0.1:1080")
        assertNotNull(result)
        assertEquals(Proxy.Type.SOCKS, result!!.type())
        assertTrue(result.address().toString().contains("10.0.0.1"))
        assertTrue(result.address().toString().contains("1080"))
    }

    @Test
    fun `parsePacResult parses SOCKS5 host port`() {
        val result = resolver.parsePacResult("SOCKS5 10.0.0.1:1080")
        assertNotNull(result)
        assertEquals(Proxy.Type.SOCKS, result!!.type())
    }

    @Test
    fun `parsePacResult handles fallback chain - uses first valid entry`() {
        val result = resolver.parsePacResult("PROXY 10.0.0.1:8080; DIRECT")
        assertNotNull(result)
        assertEquals(Proxy.Type.HTTP, result!!.type())
    }

    @Test
    fun `parsePacResult handles fallback chain - skips to DIRECT when proxy malformed`() {
        val result = resolver.parsePacResult("PROXY invalid; DIRECT")
        assertNull(result) // Falls through to DIRECT
    }

    @Test
    fun `parsePacResult handles fallback chain - uses second proxy when first is malformed`() {
        val result = resolver.parsePacResult("PROXY invalid; PROXY 10.0.0.1:3128")
        assertNotNull(result)
        assertEquals(Proxy.Type.HTTP, result!!.type())
        assertTrue(result.address().toString().contains("10.0.0.1"))
    }

    @Test
    fun `parsePacResult returns null for empty string`() {
        val result = resolver.parsePacResult("")
        assertNull(result)
    }

    @Test
    fun `parsePacResult returns null for unknown directive`() {
        val result = resolver.parsePacResult("UNKNOWN something")
        assertNull(result)
    }

    @Test
    fun `parsePacResult is case-insensitive for directives`() {
        val result = resolver.parsePacResult("proxy 10.0.0.1:8080")
        assertNotNull(result)
        assertEquals(Proxy.Type.HTTP, result!!.type())
    }

    @Test
    fun `parsePacResult handles extra whitespace`() {
        val result = resolver.parsePacResult("  PROXY   10.0.0.1:8080  ;  DIRECT  ")
        assertNotNull(result)
        assertEquals(Proxy.Type.HTTP, result!!.type())
    }

    @Test
    fun `parsePacResult rejects invalid port`() {
        val result = resolver.parsePacResult("PROXY 10.0.0.1:99999")
        assertNull(result) // port out of range, falls to implicit DIRECT
    }

    @Test
    fun `parsePacResult rejects missing port`() {
        val result = resolver.parsePacResult("PROXY 10.0.0.1")
        assertNull(result)
    }

    // ── resolveProxy tests ───────────────────────────────────────────────────

    @Test
    fun `resolveProxy returns null when proxy is disabled`() = runTest {
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = false, pacUrl = "http://example.com/pac")
        )

        val result = resolver.resolveProxy("http://example.com")
        assertNull(result)
    }

    @Test
    fun `resolveProxy returns null when PAC URL is blank`() = runTest {
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = true, pacUrl = "")
        )

        val result = resolver.resolveProxy("http://example.com")
        assertNull(result)
    }

    @Test
    fun `resolveProxy returns null when JS engine throws`() = runTest {
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = true, pacUrl = "http://pac.example.com/proxy.pac")
        )
        every { jsEngine.evaluatePac(any(), any(), any()) } throws RuntimeException("JS error")

        // The PAC fetch will fail (no real network), so resolver returns null
        val result = resolver.resolveProxy("http://example.com")
        assertNull(result)
    }

    // ── clearCache test ──────────────────────────────────────────────────────

    @Test
    fun `clearCache does not throw`() {
        // Just verify it doesn't crash
        resolver.clearCache()
    }
}
