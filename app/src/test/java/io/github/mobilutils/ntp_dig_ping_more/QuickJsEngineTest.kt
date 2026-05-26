package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.proxy.QuickJsEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QuickJsEngine] — IP parsing and subnet comparison helpers.
 *
 * The [QuickJsEngine.parseIp] and [QuickJsEngine.compareSubnet] helpers are
 * pure-Kotlin utilities and run as plain JVM unit tests without any JS engine.
 *
 * Integration tests for [QuickJsEngine.evaluatePac] require an Android device
 * or emulator (JavaScriptSandbox is backed by the system WebView process), so
 * they live in the instrumented test set.
 */
class QuickJsEngineTest {

    // ── parseIp tests ───────────────────────────────────────────────────────

    @Test
    fun `parseIp parses valid IPv4`() {
        val result = QuickJsEngine.parseIp("10.64.66.0")
        assertNotNull(result)
        assertArrayEquals(intArrayOf(10, 64, 66, 0), result)
    }

    @Test
    fun `parseIp parses 255 octets correctly`() {
        val result = QuickJsEngine.parseIp("255.255.255.0")
        assertNotNull(result)
        assertArrayEquals(intArrayOf(255, 255, 255, 0), result)
    }

    @Test
    fun `parseIp parses all-zeros`() {
        val result = QuickJsEngine.parseIp("0.0.0.0")
        assertNotNull(result)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), result)
    }

    @Test
    fun `parseIp parses all-255`() {
        val result = QuickJsEngine.parseIp("255.255.255.255")
        assertNotNull(result)
        assertArrayEquals(intArrayOf(255, 255, 255, 255), result)
    }

    @Test
    fun `parseIp returns null for too few octets`() {
        assertNull(QuickJsEngine.parseIp("10.0.0"))
    }

    @Test
    fun `parseIp returns null for too many octets`() {
        assertNull(QuickJsEngine.parseIp("10.0.0.1.2"))
    }

    @Test
    fun `parseIp returns null for non-numeric octets`() {
        assertNull(QuickJsEngine.parseIp("10.abc.0.1"))
    }

    @Test
    fun `parseIp returns null for octet out of range`() {
        assertNull(QuickJsEngine.parseIp("10.256.0.1"))
    }

    @Test
    fun `parseIp returns null for negative octet`() {
        assertNull(QuickJsEngine.parseIp("10.-1.0.1"))
    }

    @Test
    fun `parseIp returns null for empty string`() {
        assertNull(QuickJsEngine.parseIp(""))
    }

    @Test
    fun `parseIp returns null for null`() {
        assertNull(QuickJsEngine.parseIp(null))
    }

    @Test
    fun `parseIp returns null for garbage`() {
        assertNull(QuickJsEngine.parseIp("not.an.ip.address"))
    }

    @Test
    fun `parseIp parses loopback`() {
        val result = QuickJsEngine.parseIp("127.0.0.1")
        assertNotNull(result)
        assertArrayEquals(intArrayOf(127, 0, 0, 1), result)
    }

    // ── compareSubnet tests ─────────────────────────────────────────────────

    @Test
    fun `compareSubnet matches 10_x_x_x in class A`() {
        val hostIp = intArrayOf(10, 64, 66, 68)
        val pattern = intArrayOf(10, 0, 0, 0)
        val mask = intArrayOf(255, 0, 0, 0)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet rejects different class A`() {
        val hostIp = intArrayOf(8, 8, 8, 8)
        val pattern = intArrayOf(10, 0, 0, 0)
        val mask = intArrayOf(255, 0, 0, 0)
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet matches 172_16_x_x in CIDR 12`() {
        val hostIp = intArrayOf(172, 20, 5, 1)
        val pattern = intArrayOf(172, 16, 0, 0)
        val mask = intArrayOf(255, 240, 0, 0)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet rejects 172_32_x_x outside CIDR 12`() {
        val hostIp = intArrayOf(172, 32, 5, 1)
        val pattern = intArrayOf(172, 16, 0, 0)
        val mask = intArrayOf(255, 240, 0, 0)
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet matches 192_168_x_x in class B`() {
        val hostIp = intArrayOf(192, 168, 1, 100)
        val pattern = intArrayOf(192, 168, 0, 0)
        val mask = intArrayOf(255, 255, 0, 0)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet rejects 192_169_x_x`() {
        val hostIp = intArrayOf(192, 169, 1, 100)
        val pattern = intArrayOf(192, 168, 0, 0)
        val mask = intArrayOf(255, 255, 0, 0)
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet matches 127_x_x_x loopback`() {
        val hostIp = intArrayOf(127, 0, 0, 1)
        val pattern = intArrayOf(127, 0, 0, 0)
        val mask = intArrayOf(255, 0, 0, 0)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet matches exact host with 32 mask`() {
        val hostIp = intArrayOf(10, 1, 2, 3)
        val pattern = intArrayOf(10, 1, 2, 3)
        val mask = intArrayOf(255, 255, 255, 255)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet rejects different host with 32 mask`() {
        val hostIp = intArrayOf(10, 1, 2, 4)
        val pattern = intArrayOf(10, 1, 2, 3)
        val mask = intArrayOf(255, 255, 255, 255)
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet with zero mask matches everything`() {
        val hostIp = intArrayOf(8, 8, 8, 8)
        val pattern = intArrayOf(10, 0, 0, 0)
        val mask = intArrayOf(0, 0, 0, 0)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet matches class C 24 mask`() {
        val hostIp = intArrayOf(192, 168, 1, 42)
        val pattern = intArrayOf(192, 168, 1, 0)
        val mask = intArrayOf(255, 255, 255, 0)
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `compareSubnet rejects different class C 24 mask`() {
        val hostIp = intArrayOf(192, 168, 2, 42)
        val pattern = intArrayOf(192, 168, 1, 0)
        val mask = intArrayOf(255, 255, 255, 0)
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    // ── Integration: parseIp + compareSubnet ────────────────────────────────

    @Test
    fun `integration - isInNet check for 10_64_66_68 in 10_0_0_0 slash 8`() {
        val hostIp = QuickJsEngine.parseIp("10.64.66.68")!!
        val pattern = QuickJsEngine.parseIp("10.0.0.0")!!
        val mask = QuickJsEngine.parseIp("255.0.0.0")!!
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - isInNet rejects 8_8_8_8 from 10_0_0_0 slash 8`() {
        val hostIp = QuickJsEngine.parseIp("8.8.8.8")!!
        val pattern = QuickJsEngine.parseIp("10.0.0.0")!!
        val mask = QuickJsEngine.parseIp("255.0.0.0")!!
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - 172_20_5_1 is in 172_16_0_0 slash 12`() {
        val hostIp = QuickJsEngine.parseIp("172.20.5.1")!!
        val pattern = QuickJsEngine.parseIp("172.16.0.0")!!
        val mask = QuickJsEngine.parseIp("255.240.0.0")!!
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - 172_32_5_1 is NOT in 172_16_0_0 slash 12`() {
        val hostIp = QuickJsEngine.parseIp("172.32.5.1")!!
        val pattern = QuickJsEngine.parseIp("172.16.0.0")!!
        val mask = QuickJsEngine.parseIp("255.240.0.0")!!
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - 192_168_1_100 is in 192_168_0_0 slash 16`() {
        val hostIp = QuickJsEngine.parseIp("192.168.1.100")!!
        val pattern = QuickJsEngine.parseIp("192.168.0.0")!!
        val mask = QuickJsEngine.parseIp("255.255.0.0")!!
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - 192_169_1_100 is NOT in 192_168_0_0 slash 16`() {
        val hostIp = QuickJsEngine.parseIp("192.169.1.100")!!
        val pattern = QuickJsEngine.parseIp("192.168.0.0")!!
        val mask = QuickJsEngine.parseIp("255.255.0.0")!!
        assertFalse(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - 127_0_0_1 is in 127_0_0_0 slash 8`() {
        val hostIp = QuickJsEngine.parseIp("127.0.0.1")!!
        val pattern = QuickJsEngine.parseIp("127.0.0.0")!!
        val mask = QuickJsEngine.parseIp("255.0.0.0")!!
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))
    }

    @Test
    fun `integration - parseIp handles high octet values 200+`() {
        // Verify that values > 127 (which would be negative as signed bytes) work correctly
        val hostIp = QuickJsEngine.parseIp("200.100.50.25")!!
        val pattern = QuickJsEngine.parseIp("200.100.0.0")!!
        val mask = QuickJsEngine.parseIp("255.255.0.0")!!
        assertTrue(QuickJsEngine.compareSubnet(hostIp, pattern, mask))

        // Different /16 should fail
        val otherHost = QuickJsEngine.parseIp("200.101.50.25")!!
        assertFalse(QuickJsEngine.compareSubnet(otherHost, pattern, mask))
    }

    // ── Integration: evaluatePac ─────────────────────────────────────────────
    //
    // NOTE: Tests that call evaluatePac require JavaScriptSandbox, which is backed
    // by the system WebView process and is not available in plain JVM unit tests.
    // These integration tests live in the androidTest source set (instrumented tests)
    // and must run on a real device or emulator.
    //
    // The parseIp / compareSubnet tests above cover the core pure-Kotlin logic
    // without requiring any JS engine or Android runtime.
}
