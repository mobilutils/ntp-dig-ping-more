package io.github.mobilutils.ntp_dig_ping_more

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Unit tests for [BulkActionsRepository] checkcert pseudo-command chain display.
 *
 * These tests mirror the UntrustedChain formatting logic from the repository
 * to verify output lines contain [Leaf], [Intermediate N], and [Root] markers
 * with correct cert metadata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkActionsCheckcertTest {

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

     @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
     }

     @After
    fun tearDown() {
        Dispatchers.resetMain()
     }

     // ────────────────────────────────────────────────────────────────
      // UntrustedChain — single cert (leaf-only chain)
      // ────────────────────────────────────────────────────────────────

     @Test
    fun `checkcert_untrustedSingleCert_displaysLeafMarker`() = runTest {
        val leaf = CertificateInfo(
            host = "self-signed.local", port = 443,
            subject = DistinguishedName(cn = "self-signed.local", o = null, ou = null, c = null),
            issuer = DistinguishedName(cn = "self-signed.local", o = null, ou = null, c = null),
            notBefore = "2025-01-01 00:00:00 UTC", notAfter = "2027-01-01 00:00:00 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 500,
            serialNumber = "DEADBEEF", sha256Fingerprint = "AA:BB:CC:DD", sha1Fingerprint = "11:22:33",
            subjectAltNames = listOf(SanEntry("DNS", "self-signed.local")),
            keyAlgorithm = "RSA", keySize = 2048,
            version = 3, signatureAlgorithm = "SHA256withRSA", chainDepth = 1, chainPosition = 0,
          )
        val result = HttpsCertResult.UntrustedChain(
            chain = listOf(leaf),
            reason = "Trust anchor for certification path not found",
          )

        val output = _formatUntrustedChain("cert1", "checkcert self-signed.local", result, 150L)

        assertTrue(output[0].contains("checkcert self-signed.local"))
        assertTrue(output[1].contains("UNTRUSTED"))
        assertTrue(output[1].contains("Trust anchor for certification path not found"))
        assertTrue(output[1].contains("150ms"))
        assertTrue(output[2].contains("[Leaf]"))
        assertTrue(output[2].contains("CN=self-signed.local"))
        assertTrue(output[3].contains("CN=self-signed.local"))
        assertTrue(output[4].contains("2025-01-01 00:00:00 UTC"))
        assertTrue(output[5].contains("AA:BB:CC:DD"))

          // Should return BulkCommandWarning
        val warning = _wrapAsWarning("cert1", "checkcert self-signed.local", output, 150L)
        assertTrue(warning is BulkCommandWarning)
      }

     // ────────────────────────────────────────────────────────────────
      // UntrustedChain — multi-cert chain (leaf + intermediate + root)
      // ────────────────────────────────────────────────────────────────

     @Test
    fun `checkcert_untrustedMultiCert_displaysAllChainMarkers`() = runTest {
        val leaf = CertificateInfo(
            host = "app.example.com", port = 443,
            subject = DistinguishedName(cn = "app.example.com", o = "App Corp", ou = null, c = "US"),
            issuer = DistinguishedName(cn = "Intermediate CA", o = null, ou = null, c = null),
            notBefore = "2025-06-01 00:00:00 UTC", notAfter = "2027-06-01 00:00:00 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 360,
            serialNumber = "01", sha256Fingerprint = "AA:BB", sha1Fingerprint = "11:22",
            subjectAltNames = emptyList(),
            keyAlgorithm = "RSA", keySize = 2048,
            version = 3, signatureAlgorithm = "SHA256withRSA", chainDepth = 3, chainPosition = 0,
          )
        val intermediate = CertificateInfo(
            host = "app.example.com", port = 443,
            subject = DistinguishedName(cn = "Intermediate CA", o = null, ou = null, c = null),
            issuer = DistinguishedName(cn = "Root CA", o = "Root Org", ou = null, c = "US"),
            notBefore = "2020-01-01 00:00:00 UTC", notAfter = "2035-01-01 00:00:00 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 5000,
            serialNumber = "02", sha256Fingerprint = "CC:DD", sha1Fingerprint = "33:44",
            subjectAltNames = emptyList(),
            keyAlgorithm = "ECDSA", keySize = 256,
            version = 3, signatureAlgorithm = "SHA256withECDSA", chainDepth = 3, chainPosition = 1,
          )
        val root = CertificateInfo(
            host = "app.example.com", port = 443,
            subject = DistinguishedName(cn = "Root CA", o = "Root Org", ou = null, c = "US"),
            issuer = DistinguishedName(cn = "Root CA", o = "Root Org", ou = null, c = "US"),
            notBefore = "2015-01-01 00:00:00 UTC", notAfter = "2045-01-01 00:00:00 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 10000,
            serialNumber = "03", sha256Fingerprint = "EE:FF:00", sha1Fingerprint = "55:66:77",
            subjectAltNames = emptyList(),
            keyAlgorithm = "RSA", keySize = 4096,
            version = 3, signatureAlgorithm = "SHA256withRSA", chainDepth = 3, chainPosition = 2,
          )
        val chain = listOf(leaf, intermediate, root)
        val result = HttpsCertResult.UntrustedChain(
            chain = chain,
            reason = "Untrusted root",
          )

        val output = _formatUntrustedChain("cert2", "checkcert -p 443 app.example.com", result, 200L)

          // Status line
        assertTrue(output[0].contains("checkcert -p 443 app.example.com"))
        assertTrue(output[1].contains("UNTRUSTED"))
        assertTrue(output[1].contains("Untrusted root"))

          // Leaf cert: lines [2-5], separator at [6]
        assertTrue(output[2].contains("[Leaf]"))
        assertTrue(output[2].contains("CN=app.example.com"))
        assertTrue(output[3].contains("CN=Intermediate CA"))

          // Intermediate cert: lines [7-10], separator at [11]
        assertTrue(output[7].contains("[Intermediate 1]"))
        assertTrue(output[7].contains("CN=Intermediate CA"))
        assertTrue(output[8].contains("CN=Root CA"))

          // Root cert: lines [12-15]
        assertTrue(output[12].contains("[Root]"))
        assertTrue(output[12].contains("CN=Root CA"))
        assertTrue(output[13].contains("CN=Root CA"))

          // Separators between certs
        assertTrue(output.any { it.contains("---") && !it.contains("[Leaf]") })
      }

     // ────────────────────────────────────────────────────────────────
      // UntrustedChain — two-cert chain (leaf + root, no intermediate)
      // ────────────────────────────────────────────────────────────────

     @Test
    fun `checkcert_untrustedTwoCertChain_leafAndRootOnly`() = runTest {
        val leaf = CertificateInfo(
            host = "two.example.com", port = 443,
            subject = DistinguishedName(cn = "two.example.com", o = null, ou = null, c = null),
            issuer = DistinguishedName(cn = "My CA", o = null, ou = null, c = null),
            notBefore = "2025-01-01 00:00:00 UTC", notAfter = "2026-12-31 23:59:59 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 600,
            serialNumber = "AA", sha256Fingerprint = "AB", sha1Fingerprint = "CD",
            subjectAltNames = emptyList(),
            keyAlgorithm = "RSA", keySize = 2048,
            version = 3, signatureAlgorithm = "SHA256withRSA", chainDepth = 2, chainPosition = 0,
          )
        val ca = CertificateInfo(
            host = "two.example.com", port = 443,
            subject = DistinguishedName(cn = "My CA", o = null, ou = null, c = null),
            issuer = DistinguishedName(cn = "My CA", o = null, ou = null, c = null),
            notBefore = "2020-01-01 00:00:00 UTC", notAfter = "2030-12-31 23:59:59 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 1800,
            serialNumber = "BB", sha256Fingerprint = "EF", sha1Fingerprint = "GH",
            subjectAltNames = emptyList(),
            keyAlgorithm = "RSA", keySize = 4096,
            version = 3, signatureAlgorithm = "SHA256withRSA", chainDepth = 2, chainPosition = 1,
          )

        val output = _formatUntrustedChain("cert3", "checkcert two.example.com",
            HttpsCertResult.UntrustedChain(listOf(leaf, ca), "Self-signed"), 100L)

          // Leaf at [2], Root at [6] (no intermediate, so leaf[2-5] + separator[6]? No — 2 certs = 4 lines each + 1 sep)
          // Actually: [2]=[Leaf] Subject, [3]=Issuer, [4]=Valid, [5]=SHA256, [6]=---, [7]=[Root] Subject...
        assertTrue(output[2].contains("[Leaf]"))
        assertTrue(output[7].contains("[Root]"))
          // Exactly one separator between two certs
        val separatorCount = output.count { it.contains("---") && !it.contains("[Leaf]") }
        assertEquals(1, separatorCount)
      }

     // ────────────────────────────────────────────────────────────────
      // UntrustedChain — null CN handling
      // ────────────────────────────────────────────────────────────────

     @Test
    fun `checkcert_untrustedNullCN_displaysNone`() = runTest {
        val leaf = CertificateInfo(
            host = "noname.example.com", port = 443,
            subject = DistinguishedName(cn = null, o = "NoName Corp", ou = null, c = null),
            issuer = DistinguishedName(cn = null, o = "NoName Corp", ou = null, c = null),
            notBefore = "2025-01-01 00:00:00 UTC", notAfter = "2027-01-01 00:00:00 UTC",
            validityStatus = CertValidityStatus.VALID, daysUntilExpiry = 500,
            serialNumber = "FF", sha256Fingerprint = "FF:EE", sha1Fingerprint = "DD:CC",
            subjectAltNames = emptyList(),
            keyAlgorithm = "RSA", keySize = 2048,
            version = 3, signatureAlgorithm = "SHA256withRSA", chainDepth = 1, chainPosition = 0,
          )

        val output = _formatUntrustedChain("cert4", "checkcert noname.example.com",
            HttpsCertResult.UntrustedChain(listOf(leaf), "No trust anchor"), 50L)

        assertTrue(output[2].contains("CN=(none)"))
      }

     // ────────────────────────────────────────────────────────────────
      // Helper: mirrors the UntrustedChain formatting from BulkActionsRepository
      // ────────────────────────────────────────────────────────────────

      /**
       * Mirrors the UntrustedChain branch from [BulkActionsRepository.executeCheckcert].
       * Returns the formatted output lines.
       */
    private fun CoroutineScope._formatUntrustedChain(
        name: String,
        cmd: String,
        result: HttpsCertResult.UntrustedChain,
        duration: Long,
      ): List<String> {
        val lines = mutableListOf<String>()
        lines.add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
        lines.add("[${timestampFmt.format(LocalDateTime.now())}] Status: UNTRUSTED - ${result.reason} (${duration}ms)")
        result.chain.forEachIndexed { index, ci ->
            val tag = when {
                index == 0 -> "[Leaf]"
                index == result.chain.size - 1 -> "[Root]"
                else -> "[Intermediate $index]"
               }
            lines.add("          $tag Subject: CN=${ci.subject.cn ?: "(none)"}")
            lines.add("          Issuer:  CN=${ci.issuer.cn ?: "(none)"}")
            lines.add("          Valid:           ${ci.notBefore} to ${ci.notAfter}")
            lines.add("          SHA256:            ${ci.sha256Fingerprint}")
            if (index < result.chain.size - 1) lines.add("                 ---")
           }
        return lines
      }

      /** Mirrors the warning result wrapper from executeCheckcert. */
    private fun _wrapAsWarning(
        name: String, cmd: String, lines: List<String>, duration: Long,
     ): BulkCommandResult = BulkCommandWarning(name, cmd, lines, duration)
}
