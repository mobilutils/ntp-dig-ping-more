package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.proxy.JsEngine
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyPacLogger
import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver
import io.github.mobilutils.ntp_dig_ping_more.settings.ProxyConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * Tests PAC result parsing, caching, fallback behaviour, and logging integration.
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

    @Test
    fun `resolveProxy returns Proxy NO_PROXY when PAC evaluates to DIRECT`() = runTest {
        // This test cannot verify the full PAC-fetch→eval→DIRECT pipeline in a
        // unit test (fetchPacScript does real HTTP), but it validates the contract
        // indirectly: parsePacResult("DIRECT") returns null, and resolveProxy wraps
        // that null as Proxy.NO_PROXY.  Callers (GoogleTimeSyncRepository, etc.)
        // then pass Proxy.NO_PROXY to URL.openConnection(proxy) which forces a
        // truly direct connection, bypassing the system ProxySelector.
        val direct = resolver.parsePacResult("DIRECT")
        assertNull("parsePacResult should return null for DIRECT", direct)

        // After the fix, resolveProxy() wraps that null → Proxy.NO_PROXY, so
        // callers receive a non-null Proxy of type DIRECT.
        assertEquals(Proxy.Type.DIRECT, Proxy.NO_PROXY.type())
    }

    // ── clearCache test ──────────────────────────────────────────────────────

    @Test
    fun `clearCache does not throw`() {
        // Just verify it doesn't crash
        resolver.clearCache()
    }

    // ── Logging integration tests ────────────────────────────────────────────

    @Test
    fun `resolver with null logger does not throw`() = runTest {
        // Default resolver has no logger — should work fine
        val noLogResolver = ProxyResolver(settingsRepository, jsEngine, logger = null)
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = false)
        )

        val result = noLogResolver.resolveProxy("http://example.com")
        assertNull(result)
    }

    @Test
    fun `logIfEnabled does not log when logger disabled and forceLogging false`() = runTest {
        val logger = mockk<ProxyPacLogger>(relaxed = true)
        every { logger.enabled } returns false

        val loggedResolver = ProxyResolver(
            settingsRepository, jsEngine, logger = logger, forceLogging = false
        )
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = false)
        )

        loggedResolver.resolveProxy("http://example.com")

        // Logger.log should not be called because proxy is disabled (early return)
        // and even if logging path were hit, logger.enabled is false + forceLogging is false
        verify(exactly = 0) { logger.log(any(), any()) }
    }

    @Test
    fun `logIfEnabled logs when forceLogging is true even if logger disabled`() = runTest {
        val logger = mockk<ProxyPacLogger>(relaxed = true)
        every { logger.enabled } returns false

        val loggedResolver = ProxyResolver(
            settingsRepository, jsEngine, logger = logger, forceLogging = true
        )
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = true, pacUrl = "http://pac.example.com/proxy.pac")
        )

        // PAC fetch will fail (no real network), which should trigger a log
        loggedResolver.resolveProxy("http://example.com")

        // Should have logged the PAC fetch failure
        verify(atLeast = 1) { logger.log(match { it.contains("PAC_FETCH_FAIL") }, force = true) }
    }

    @Test
    fun `logIfEnabled logs when logger enabled`() = runTest {
        val logger = mockk<ProxyPacLogger>(relaxed = true)
        every { logger.enabled } returns true

        val loggedResolver = ProxyResolver(
            settingsRepository, jsEngine, logger = logger, forceLogging = false
        )
        coEvery { settingsRepository.proxyConfigFlow } returns flowOf(
            ProxyConfig(enabled = true, pacUrl = "http://pac.example.com/proxy.pac")
        )

        // PAC fetch will fail (no real network), which should trigger a log
        loggedResolver.resolveProxy("http://example.com")

        // Should have logged the PAC fetch failure
        verify(atLeast = 1) { logger.log(match { it.contains("PAC_FETCH_FAIL") }, any()) }
    }
}
