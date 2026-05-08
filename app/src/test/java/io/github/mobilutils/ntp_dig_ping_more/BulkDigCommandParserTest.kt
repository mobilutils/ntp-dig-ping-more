package io.github.mobilutils.ntp_dig_ping_more

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [BulkActionsRepository.executeDig] command parser.
 * Pure-Kotlin mirroring of the parsing logic — no Android or network dependencies.
 */
class BulkDigCommandParserTest {

    /** Mirrors the dig parsing logic from BulkActionsRepository.executeDig exactly. */
    private fun parseDig(cmd: String): Pair<String, String> {
        val parts = cmd.trim().split(Regex("\\s+"))
        val serverIdx = parts.indexOfFirst { it.startsWith("@") }
        return if (serverIdx >= 0 && serverIdx < parts.size && (serverIdx > 1 || serverIdx + 1 < parts.size)) {
            val serverHost = parts[serverIdx].substring(1)
            val fqdnIdx = if (serverIdx == 1) {
                // Format: dig @server [-t N] fqdn
                var i = serverIdx + 1
                while (i < parts.size && parts[i] == "-t" && i + 1 < parts.size) {
                    i += 2
                }
                i
            } else {
                // Format: dig fqdn @server [-t N] -> FQDN is right before @server
                serverIdx - 1
            }
            serverHost to parts[fqdnIdx]
        } else {
            // No @ found (or @server with no FQDN token) — use default server and last part as FQDN
            "8.8.8.8" to parts.last()
        }
    }

    // ── @server first (original working case) ────────────────────────

    @Test
    fun `digAtServerFirst_parsesCorrectly`() {
        val result = parseDig("dig @8.8.8.8 google.com")
        assertEquals(Pair("8.8.8.8", "google.com"), result)
    }

    @Test
    fun `digAtServerFirstWithTimeout_parsesCorrectly`() {
        val result = parseDig("dig @1.1.1.1 -t 30 example.com")
        assertEquals(Pair("1.1.1.1", "example.com"), result)
    }

    // ── @server last (the bug from #53) ──────────────────────────────

    @Test
    fun `digFqdnAtServerFirst_parsesCorrectly`() {
        val result = parseDig("dig google.com @8.8.8.8")
        assertEquals(Pair("8.8.8.8", "google.com"), result)
    }

    @Test
    fun `digFqdnAtServerWithTimeoutAfter_parsesCorrectly`() {
        val result = parseDig("dig example.com @1.1.1.1 -t 30")
        assertEquals(Pair("1.1.1.1", "example.com"), result)
    }

    @Test
    fun `digFqdnAtServerWithTimeoutBetween_parsesCorrectly`() {
        val result = parseDig("dig google.com @8.8.8.8 -t 10")
        assertEquals(Pair("8.8.8.8", "google.com"), result)
    }

    // ── No server specified ─────────────────────────────────────────

    @Test
    fun `digNoServer_parsesWithDefault`() {
        val result = parseDig("dig google.com")
        assertEquals(Pair("8.8.8.8", "google.com"), result)
    }

    @Test
    fun `digNoServerNoFqdn_usesLastAsFqdn`() {
        // "dig" alone — last part is "dig" itself
        val result = parseDig("dig")
        assertEquals(Pair("8.8.8.8", "dig"), result)
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `digAtServerOnly_noFqdn_usesDefaultServerAndLastPart`() {
        // dig @8.8.8.8 alone — condition fails (serverIdx==1, no FQDN after), falls to else branch
        val result = parseDig("dig @8.8.8.8")
        assertEquals("8.8.8.8", result.first)
        // fqdn = parts.last() = "@8.8.8.8" — dnsjava will reject this as invalid domain
        assertEquals("@8.8.8.8", result.second)
    }

    @Test
    fun `digAtServerWithSubdomain_parsesCorrectly`() {
        val result = parseDig("dig @8.8.8.8 api.example.com")
        assertEquals(Pair("8.8.8.8", "api.example.com"), result)
    }

    @Test
    fun `digFqdnAtServerWithSubdomain_parsesCorrectly`() {
        val result = parseDig("dig api.cloudflare.com @1.0.0.1")
        assertEquals(Pair("1.0.0.1", "api.cloudflare.com"), result)
    }

    // ── Both formats produce same result ─────────────────────────────

    @Test
    fun `bothFormats_sameServerAndFqdn`() {
        val result1 = parseDig("dig @8.8.8.8 google.com")
        val result2 = parseDig("dig google.com @8.8.8.8")
        assertEquals(result1, result2)
    }

    @Test
    fun `bothFormats_sameServerAndFqdn_differentOrderings`() {
        val result1 = parseDig("dig @1.1.1.1 example.com")
        val result2 = parseDig("dig example.com @1.1.1.1")
        assertEquals(result1, result2)
    }

    @Test
    fun `bothFormats_withTimeout_sameResult`() {
        val result1 = parseDig("dig @8.8.8.8 -t 30 google.com")
        val result2 = parseDig("dig google.com @8.8.8.8 -t 30")
        assertEquals(result1, result2)
    }
}
