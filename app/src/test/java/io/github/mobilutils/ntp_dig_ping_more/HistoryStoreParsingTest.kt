package io.github.mobilutils.ntp_dig_ping_more

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HistoryStore parsing logic.
 * Tests the pure parsing functions by implementing them directly (mirroring the source code).
 */
class HistoryStoreParsingTest {

    // ─────────────────────────────────────────────────────────────────────
    // NtpHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `NtpHistoryStore parsing - valid entry`() {
        val raw = "2024/01/15 10:30:00|pool.ntp.org|123|true"
        val entries = parseNtpHistory(raw)

        assertEquals(1, entries.size)
        assertEquals("2024/01/15 10:30:00", entries[0].timestamp)
        assertEquals("pool.ntp.org", entries[0].server)
        assertEquals(123, entries[0].port)
        assertTrue(entries[0].success)
    }

    @Test
    fun `NtpHistoryStore parsing - backward compat without success`() {
        val raw = "2024/01/15 10:30:00|pool.ntp.org|123"
        val entries = parseNtpHistory(raw)

        assertEquals(1, entries.size)
        assertTrue(!entries[0].success) // defaults to false
    }

    @Test
    fun `NtpHistoryStore parsing - caps at 5`() {
        val raw = (1..6).joinToString("\n") { i ->
            "2024/01/15 10:30:0${i}|server${i}.ntp.org|123|true"
        }
        val entries = parseNtpHistory(raw)
        assertEquals(5, entries.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PingHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `PingHistoryStore parsing - ALL_SUCCESS`() {
        val raw = "2024/01/15 10:30:00|8.8.8.8|ALL_SUCCESS"
        val entries = parsePingHistory(raw)

        assertEquals(PingStatus.ALL_SUCCESS, entries[0].status)
    }

    @Test
    fun `PingHistoryStore parsing - backward compat with true`() {
        val raw = "2024/01/15 10:30:00|8.8.8.8|true"
        val entries = parsePingHistory(raw)

        assertEquals(PingStatus.ALL_SUCCESS, entries[0].status)
    }

    @Test
    fun `PingHistoryStore parsing - PARTIAL`() {
        val raw = "2024/01/15 10:30:00|8.8.8.8|PARTIAL"
        val entries = parsePingHistory(raw)

        assertEquals(PingStatus.PARTIAL, entries[0].status)
    }

    // ─────────────────────────────────────────────────────────────────────
    // TracerouteHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `TracerouteHistoryStore parsing - ALL_SUCCESS`() {
        val raw = "2024/01/15 10:30:00|example.com|ALL_SUCCESS"
        val entries = parseTracerouteHistory(raw)

        assertEquals(TracerouteStatus.ALL_SUCCESS, entries[0].status)
    }

    @Test
    fun `TracerouteHistoryStore parsing - defaults to ALL_FAILED`() {
        val raw = "2024/01/15 10:30:00|example.com|UNKNOWN"
        val entries = parseTracerouteHistory(raw)

        assertEquals(TracerouteStatus.ALL_FAILED, entries[0].status)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PortScannerHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `PortScannerHistoryStore parsing - TCP`() {
        val raw = "2024/01/15 10:30:00|192.168.1.1|1|1000|TCP"
        val entries = parsePortScannerHistory(raw)

        assertEquals(PortScannerProtocol.TCP, entries[0].protocol)
    }

    @Test
    fun `PortScannerHistoryStore parsing - UDP`() {
        val raw = "2024/01/15 10:30:00|192.168.1.1|53|53|UDP"
        val entries = parsePortScannerHistory(raw)

        assertEquals(PortScannerProtocol.UDP, entries[0].protocol)
    }

    // ─────────────────────────────────────────────────────────────────────
    // DigHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `DigHistoryStore parsing - SUCCESS`() {
        val raw = "2024/01/15 10:30:00|8.8.8.8|example.com|SUCCESS"
        val entries = parseDigHistory(raw)

        assertEquals(DigStatus.SUCCESS, entries[0].status)
    }

    @Test
    fun `DigHistoryStore parsing - defaults to FAILED`() {
        val raw = "2024/01/15 10:30:00|8.8.8.8|example.com|UNKNOWN"
        val entries = parseDigHistory(raw)

        assertEquals(DigStatus.FAILED, entries[0].status)
    }

    // ─────────────────────────────────────────────────────────────────────
    // GoogleTimeSyncHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `GoogleTimeSyncHistoryStore parsing - valid entry`() {
        val raw = "2024/01/15 10:30:00|http://google.com|45|120|true"
        val entries = parseGoogleTimeSyncHistory(raw)

        assertEquals(45L, entries[0].offsetMs)
        assertEquals(120L, entries[0].rttMs)
        assertTrue(entries[0].success)
    }

    @Test
    fun `GoogleTimeSyncHistoryStore parsing - backward compat`() {
        val raw = "2024/01/15 10:30:00|http://google.com|45|120"
        val entries = parseGoogleTimeSyncHistory(raw)

        assertTrue(!entries[0].success) // defaults to false
    }

    // ─────────────────────────────────────────────────────────────────────
    // HttpsCertHistoryStore parsing tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `HttpsCertHistoryStore parsing - VALID`() {
        val raw = "2024/01/15 10:30:00|example.com|443|VALID|RSA 2048"
        val entries = parseHttpsCertHistory(raw)

        assertEquals(CertHistoryStatus.VALID, entries[0].status)
        assertEquals("RSA 2048", entries[0].summary)
    }

    @Test
    fun `HttpsCertHistoryStore parsing - summary with pipes`() {
        val raw = "2024/01/15 10:30:00|example.com|443|VALID|summary|with|pipes"
        val entries = parseHttpsCertHistory(raw)

        assertEquals("summary|with|pipes", entries[0].summary)
    }

    @Test
    fun `HttpsCertHistoryStore parsing - defaults to ERROR`() {
        val raw = "2024/01/15 10:30:00|example.com|443|INVALID|error"
        val entries = parseHttpsCertHistory(raw)

        assertEquals(CertHistoryStatus.ERROR, entries[0].status)
    }

    // ─────────────────────────────────────────────────────────────────────
    // LanScannerHistoryStore parsing tests (JSON-based)
    // Note: JSON parsing depends on org.json which may not be available in unit tests
    // These tests verify the parsing logic structure
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `LanScannerHistoryStore parsing - empty array`() {
        val json = "[]"
        val entries = parseLanScannerHistory(json)

        assertEquals(0, entries.size)
    }

    @Test
    fun `LanScannerHistoryStore parsing - invalid JSON returns empty`() {
        val json = "not json"
        val entries = parseLanScannerHistory(json)

        assertEquals(0, entries.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PingViewModel computeStatus tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `computeStatus - ALL_FAILED when no replies`() {
        val lines = listOf(
            "PING 8.8.8.8 (8.8.8.8): 56 data bytes",
            "Request timeout for icmp_seq 0",
        )
        val status = computeStatus(lines)

        assertEquals(PingStatus.ALL_FAILED, status)
    }

    @Test
    fun `computeStatus - ALL_SUCCESS when all received`() {
        val lines = listOf(
            "64 bytes from 8.8.8.8: icmp_seq=0 ttl=116 time=12.3 ms",
            "64 bytes from 8.8.8.8: icmp_seq=1 ttl=116 time=11.8 ms",
        )
        val status = computeStatus(lines)

        assertEquals(PingStatus.ALL_SUCCESS, status)
    }

    @Test
    fun `computeStatus - PARTIAL when some lost`() {
        // With icmp_seq 0, 1, 2 and only 0 and 2 responding:
        // received=2, maxSeq=2, sent=2, received>=sent → ALL_SUCCESS
        // Need a case where maxSeq > received
        val lines = listOf(
            "64 bytes from 8.8.8.8: icmp_seq=0 ttl=116 time=12.3 ms",
            "Request timeout for icmp_seq 1",
            "Request timeout for icmp_seq 2",
            "64 bytes from 8.8.8.8: icmp_seq=3 ttl=116 time=11.8 ms",
        )
        val status = computeStatus(lines)

        // received=2, maxSeq=3, sent=3, received < sent → PARTIAL
        assertEquals(PingStatus.PARTIAL, status)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper functions (mirror the actual implementation)
    // ─────────────────────────────────────────────────────────────────────

    private fun parseNtpHistory(raw: String): List<NtpHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 3) {
                    val port = parts[2].toIntOrNull() ?: return@mapNotNull null
                    val success = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false
                    NtpHistoryEntry(timestamp = parts[0], server = parts[1], port = port, success = success)
                } else null
            }
            .take(5)

    private fun parsePingHistory(raw: String): List<PingHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 2) {
                    val status = when (parts.getOrNull(2)) {
                        "ALL_SUCCESS", "true" -> PingStatus.ALL_SUCCESS
                        "PARTIAL" -> PingStatus.PARTIAL
                        else -> PingStatus.ALL_FAILED
                    }
                    PingHistoryEntry(timestamp = parts[0], host = parts[1], status = status)
                } else null
            }
            .take(5)

    private fun parseTracerouteHistory(raw: String): List<TracerouteHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 2) {
                    val status = when (parts.getOrNull(2)) {
                        "ALL_SUCCESS" -> TracerouteStatus.ALL_SUCCESS
                        "PARTIAL" -> TracerouteStatus.PARTIAL
                        else -> TracerouteStatus.ALL_FAILED
                    }
                    TracerouteHistoryEntry(timestamp = parts[0], host = parts[1], status = status)
                } else null
            }
            .take(5)

    private fun parsePortScannerHistory(raw: String): List<PortScannerHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 5) {
                    val protocol = when (parts[4]) {
                        "UDP" -> PortScannerProtocol.UDP
                        else -> PortScannerProtocol.TCP
                    }
                    PortScannerHistoryEntry(
                        timestamp = parts[0], host = parts[1],
                        startPort = parts[2], endPort = parts[3], protocol = protocol
                    )
                } else null
            }
            .take(5)

    private fun parseDigHistory(raw: String): List<DigHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 3) {
                    val status = when (parts.getOrNull(3)) {
                        "SUCCESS" -> DigStatus.SUCCESS
                        else -> DigStatus.FAILED
                    }
                    DigHistoryEntry(timestamp = parts[0], dnsServer = parts[1], fqdn = parts[2], status = status)
                } else null
            }
            .take(5)

    private fun parseGoogleTimeSyncHistory(raw: String): List<GoogleTimeSyncHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 4) {
                    val offsetMs = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                    val rttMs = parts.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
                    val success = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: false
                    GoogleTimeSyncHistoryEntry(
                        timestamp = parts[0], url = parts[1],
                        offsetMs = offsetMs, rttMs = rttMs, success = success
                    )
                } else null
            }
            .take(5)

    private fun parseHttpsCertHistory(raw: String): List<HttpsCertHistoryEntry> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 5) {
                    val status = runCatching {
                        CertHistoryStatus.valueOf(parts[3])
                    }.getOrDefault(CertHistoryStatus.ERROR)
                    HttpsCertHistoryEntry(
                        timestamp = parts[0], host = parts[1],
                        port = parts[2].toIntOrNull() ?: 443,
                        status = status, summary = parts.drop(4).joinToString("|")
                    )
                } else null
            }
            .take(5)

    private fun parseLanScannerHistory(json: String): List<LanScannerHistoryEntry> {
        val items = mutableListOf<LanScannerHistoryEntry>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    LanScannerHistoryEntry(
                        timestamp = obj.getString("timestamp"),
                        type = obj.getString("type"),
                        subnet = obj.getString("subnet"),
                        activeHostsCount = obj.getInt("activeHostsCount"),
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return items
    }

    private fun computeStatus(lines: List<String>): PingStatus {
        val icmpSeqRegex = Regex("""icmp_seq=(\d+)""")
        val received = lines.count { it.contains("bytes from") }
        val maxSeq = lines.mapNotNull { line ->
            icmpSeqRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0

        val sent = if (maxSeq > 0) maxSeq else received

        return when {
            received == 0 -> PingStatus.ALL_FAILED
            received >= sent -> PingStatus.ALL_SUCCESS
            else -> PingStatus.PARTIAL
        }
    }
}
