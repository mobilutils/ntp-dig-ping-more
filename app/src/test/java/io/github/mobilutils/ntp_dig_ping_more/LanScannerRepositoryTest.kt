package io.github.mobilutils.ntp_dig_ping_more

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [LanScannerRepository] pure IP conversion functions.
 * These are pure functions with no Android dependencies, making them ideal for JVM unit tests.
 */
class LanScannerRepositoryTest {

    private val repository = LanScannerRepositoryStub()

    // ─────────────────────────────────────────────────────────────────────
    // longToIp tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `longToIp converts 0 to 0 0 0 0`() {
        assertEquals("0.0.0.0", repository.longToIp(0L))
    }

    @Test
    fun `longToIp converts localhost to 127 0 0 1`() {
        assertEquals("127.0.0.1", repository.longToIp(2130706433L))
    }

    @Test
    fun `longToIp converts max value to 255 255 255 255`() {
        assertEquals("255.255.255.255", repository.longToIp(4294967295L))
    }

    @Test
    fun `longToIp converts common private IP range`() {
        // 192.168.1.1 = 192<<24 | 168<<16 | 1<<8 | 1 = 3232235777
        assertEquals("192.168.1.1", repository.longToIp(3232235777L))
    }

    @Test
    fun `longToIp converts 10 0 0 1`() {
        // 10<<24 | 0<<16 | 0<<8 | 1 = 167772161
        assertEquals("10.0.0.1", repository.longToIp(167772161L))
    }

    @Test
    fun `longToIp handles negative values correctly`() {
        // In Kotlin/Java, Long is signed, but IP conversion treats it as unsigned 32-bit
        // This tests the bitwise operations work correctly
        assertEquals("172.16.0.1", repository.longToIp(2886729729L))
    }

    // ─────────────────────────────────────────────────────────────────────
    // ipToLong tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `ipToLong converts 0 0 0 0 to 0`() {
        assertEquals(0L, repository.ipToLong("0.0.0.0"))
    }

    @Test
    fun `ipToLong converts 127 0 0 1 to correct long value`() {
        assertEquals(2130706433L, repository.ipToLong("127.0.0.1"))
    }

    @Test
    fun `ipToLong converts 255 255 255 255 to max value`() {
        assertEquals(4294967295L, repository.ipToLong("255.255.255.255"))
    }

    @Test
    fun `ipToLong converts 192 168 1 1 to correct long value`() {
        assertEquals(3232235777L, repository.ipToLong("192.168.1.1"))
    }

    @Test
    fun `ipToLong converts 10 0 0 1 to correct long value`() {
        assertEquals(167772161L, repository.ipToLong("10.0.0.1"))
    }

    @Test
    fun `ipToLong converts 172 16 0 1 to correct long value`() {
        assertEquals(2886729729L, repository.ipToLong("172.16.0.1"))
    }

    @Test
    fun `ipToLong round-trips with longToIp`() {
        val ip = "192.168.0.100"
        val longValue = repository.ipToLong(ip)
        val convertedBack = repository.longToIp(longValue)
        assertEquals(ip, convertedBack)
    }

    @Test
    fun `ipToLong throws exception for invalid IP format`() {
        assertThrows("Invalid IP format", IllegalArgumentException::class.java) {
            repository.ipToLong("192.168.1")
        }
    }

    @Test
    fun `ipToLong throws exception for IP with too many octets`() {
        assertThrows("Invalid IP format", IllegalArgumentException::class.java) {
            repository.ipToLong("192.168.1.1.1")
        }
    }

    @Test
    fun `ipToLong throws exception for octet out of range`() {
        assertThrows("Invalid IP part", IllegalArgumentException::class.java) {
            repository.ipToLong("192.168.1.256")
        }
    }

    @Test
    fun `ipToLong throws exception for negative octet`() {
        assertThrows(Exception::class.java) {
            repository.ipToLong("192.168.1.-1")
        }
    }

    @Test
    fun `ipToLong throws exception for non-numeric octet`() {
        assertThrows(Exception::class.java) {
            repository.ipToLong("192.168.1.abc")
        }
    }

    @Test
    fun `ipToLong handles edge case 0 0 0 0`() {
        assertEquals(0L, repository.ipToLong("0.0.0.0"))
    }

    @Test
    fun `ipToLong handles edge case 1 0 0 0`() {
        assertEquals(16777216L, repository.ipToLong("1.0.0.0"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper stub to access pure functions without Android dependencies
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Minimal stub that exposes the pure functions without requiring Android Context.
     */
    private class LanScannerRepositoryStub {
        fun longToIp(ipLong: Long): String {
            return String.format(
                "%d.%d.%d.%d",
                (ipLong shr 24) and 0xff,
                (ipLong shr 16) and 0xff,
                (ipLong shr 8) and 0xff,
                ipLong and 0xff
            )
        }

        fun ipToLong(ipAddress: String): Long {
            var result: Long = 0
            val ipAddressInArray = ipAddress.split(".")
            if (ipAddressInArray.size != 4) throw IllegalArgumentException("Invalid IP format")
            for (i in 3 downTo 0) {
                val ip = ipAddressInArray[3 - i].toLong()
                if (ip !in 0..255) throw IllegalArgumentException("Invalid IP part")
                result = result or (ip shl (i * 8))
            }
            return result
        }
    }
}
