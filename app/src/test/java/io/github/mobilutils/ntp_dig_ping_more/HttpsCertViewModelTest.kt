package io.github.mobilutils.ntp_dig_ping_more

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HttpsCertViewModel] using MockK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpsCertViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: HttpsCertViewModel
    private lateinit var repository: HttpsCertRepository
    private lateinit var historyStore: HttpsCertHistoryStore

    private val sampleCertificateInfo = CertificateInfo(
        host = "google.com",
        port = 443,
        subject = DistinguishedName(cn = "google.com", o = "Google LLC", ou = null, c = "US"),
        issuer = DistinguishedName(cn = "GTS CA 1C3", o = "Google Trust Services LLC", ou = null, c = "US"),
        notBefore = "2024-01-01 00:00:00 UTC",
        notAfter = "2024-04-01 00:00:00 UTC",
        validityStatus = CertValidityStatus.VALID,
        daysUntilExpiry = 90,
        serialNumber = "ABC123",
        sha256Fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33",
        sha1Fingerprint = "11:22:33:44:55:66:77:88:99:AA",
        subjectAltNames = listOf(SanEntry("DNS", "google.com"), SanEntry("DNS", "*.google.com")),
        keyAlgorithm = "RSA",
        keySize = 2048,
        version = 3,
        signatureAlgorithm = "SHA256withRSA",
        chainDepth = 3,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        historyStore = mockk()

        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        // Stub save to avoid MockK exceptions during tests
        coEvery { historyStore.save(any()) } coAnswers { }
        // Default stub: unstubbed fetchCertificate calls return Error
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Error("mock error")

        viewModel = HttpsCertViewModel(repository, historyStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Idle)
    }

    @Test
    fun `initial host is googlecom`() = runTest {
        assertEquals("google.com", viewModel.host.value)
    }

    @Test
    fun `initial port is 443`() = runTest {
        assertEquals("443", viewModel.port.value)
    }

    @Test
    fun `initial history is empty`() = runTest {
        assertTrue(viewModel.history.value.isEmpty())
    }

    @Test
    fun `onHostChange updates host value`() = runTest {
        viewModel.onHostChange("example.com")

        assertEquals("example.com", viewModel.host.value)
    }

    @Test
    fun `onHostChange resets state to Idle if not Idle`() = runTest {
        // First, fetch a cert to change state
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HttpsCertUiState.Success)

        // Now change host - should reset to Idle
        viewModel.onHostChange("newhost.com")
        assertTrue(viewModel.uiState.value is HttpsCertUiState.Idle)
    }

    @Test
    fun `onPortChange accepts valid port`() = runTest {
        viewModel.onPortChange("8443")

        assertEquals("8443", viewModel.port.value)
    }

    @Test
    fun `onPortChange rejects non-numeric values`() = runTest {
        viewModel.onPortChange("abc")

        assertEquals("443", viewModel.port.value) // Should not change
    }

    @Test
    fun `onPortChange rejects ports longer than 5 digits`() = runTest {
        viewModel.onPortChange("123456")

        assertEquals("443", viewModel.port.value) // Should not change
    }

    @Test
    fun `onPortChange resets state to Idle if not Idle`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HttpsCertUiState.Success)

        viewModel.onPortChange("8443")
        assertTrue(viewModel.uiState.value is HttpsCertUiState.Idle)
    }

    @Test
    fun `fetchCert with blank host does nothing`() = runTest {
        viewModel.onHostChange("")
        viewModel.fetchCert()

        assertTrue(viewModel.uiState.value is HttpsCertUiState.Idle)
    }

    @Test
    fun `fetchCert with invalid port shows error`() = runTest {
        // "0" passes onPortChange validation but fails the range check in fetchCert
        viewModel.onPortChange("0")
        viewModel.fetchCert()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)

        val errorState = state as HttpsCertUiState.Error
        assertTrue(errorState.message.contains("Port must be a number"))
    }

    @Test
    fun `fetchCert with port out of range shows error`() = runTest {
        viewModel.onPortChange("70000")
        viewModel.fetchCert()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)

        val errorState = state as HttpsCertUiState.Error
        assertTrue(errorState.message.contains("Port must be a number"))
    }

    @Test
    fun `fetchCert with port 0 shows error`() = runTest {
        viewModel.onPortChange("0")
        viewModel.fetchCert()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)
    }

    @Test
    fun `fetchCert sets Loading state`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()

        // Before advanceUntilIdle, should be in Loading state
        assertTrue(viewModel.uiState.value is HttpsCertUiState.Loading)
    }

    @Test
    fun `fetchCert handles Success result`() = runTest {
        coEvery { repository.fetchCertificate("google.com", 443) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Success)

        val successState = state as HttpsCertUiState.Success
        assertEquals(sampleCertificateInfo, successState.info)
    }

    @Test
    fun `fetchCert handles CertExpired result`() = runTest {
        val expiredCert = sampleCertificateInfo.copy(
            validityStatus = CertValidityStatus.EXPIRED,
            daysUntilExpiry = -30
        )

        viewModel.onHostChange("expired.com")
        coEvery { repository.fetchCertificate("expired.com", 443) } returns
            HttpsCertResult.CertExpired(expiredCert)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.PartialSuccess)

        val partialState = state as HttpsCertUiState.PartialSuccess
        assertEquals(expiredCert.host, partialState.info.host)
        assertEquals(expiredCert.port, partialState.info.port)
        assertEquals(expiredCert.validityStatus, partialState.info.validityStatus)
        assertEquals(expiredCert.daysUntilExpiry, partialState.info.daysUntilExpiry)
        assertTrue(partialState.warningMessage.contains("expired"))
    }

    @Test
    fun `fetchCert handles UntrustedChain with info`() = runTest {
        viewModel.onHostChange("self-signed.com")
        coEvery { repository.fetchCertificate("self-signed.com", 443) } returns
            HttpsCertResult.UntrustedChain(
                info = sampleCertificateInfo,
                reason = "Self-signed certificate"
            )

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.PartialSuccess)

        val partialState = state as HttpsCertUiState.PartialSuccess
        assertEquals(sampleCertificateInfo.host, partialState.info.host)
        assertEquals(sampleCertificateInfo.port, partialState.info.port)
        assertEquals(sampleCertificateInfo.validityStatus, partialState.info.validityStatus)
        assertEquals(sampleCertificateInfo.daysUntilExpiry, partialState.info.daysUntilExpiry)
        assertTrue(partialState.warningMessage.contains("Untrusted"))
    }

    @Test
    fun `fetchCert handles UntrustedChain with cert data shows PartialSuccess`() = runTest {
        val info = CertificateInfo(
            host                 = "broken.com",
            port                 = 443,
            subject              = DistinguishedName(cn = "broken.com", o = null, ou = null, c = null),
            issuer               = DistinguishedName(cn = "Broken CA", o = null, ou = null, c = null),
            notBefore            = "2024-01-01 00:00:00 UTC",
            notAfter             = "2026-12-31 23:59:59 UTC",
            validityStatus       = CertValidityStatus.VALID,
            daysUntilExpiry      = 365,
            serialNumber         = "01",
            sha256Fingerprint    = "AA:BB",
            sha1Fingerprint      = "CC:DD",
            subjectAltNames      = emptyList(),
            keyAlgorithm         = "RSA",
            keySize              = 2048,
            version              = 3,
            signatureAlgorithm   = "SHA256withRSA",
            chainDepth           = 1,
         )
        viewModel.onHostChange("broken.com")
        coEvery { repository.fetchCertificate("broken.com", 443) } returns
            HttpsCertResult.UntrustedChain(
                info     = info,
                reason = "TLS handshake failed"
             )

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.PartialSuccess)

        val partialState = state as HttpsCertUiState.PartialSuccess
        assertTrue(partialState.warningMessage.contains("Untrusted"))
        assertEquals(info, partialState.info)
    }

    @Test
    fun `fetchCert handles NoNetwork result`() = runTest {
        viewModel.onHostChange("offline.com")
        coEvery { repository.fetchCertificate("offline.com", 443) } returns
            HttpsCertResult.NoNetwork

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)

        val errorState = state as HttpsCertUiState.Error
        assertEquals("No network connection", errorState.message)
    }

    @Test
    fun `fetchCert handles HostnameUnresolved result`() = runTest {
        viewModel.onHostChange("nonexistent.invalid")
        coEvery { repository.fetchCertificate("nonexistent.invalid", 443) } returns
            HttpsCertResult.HostnameUnresolved("nonexistent.invalid")

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)

        val errorState = state as HttpsCertUiState.Error
        assertTrue(errorState.message.contains("Cannot resolve hostname"))
    }

    @Test
    fun `fetchCert handles Timeout result`() = runTest {
        viewModel.onHostChange("slow.com")
        coEvery { repository.fetchCertificate("slow.com", 443) } returns
            HttpsCertResult.Timeout("slow.com")

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)

        val errorState = state as HttpsCertUiState.Error
        assertTrue(errorState.message.contains("timed out"))
    }

    @Test
    fun `fetchCert handles generic Error result`() = runTest {
        viewModel.onHostChange("error.com")
        coEvery { repository.fetchCertificate("error.com", 443) } returns
            HttpsCertResult.Error("Connection refused")

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HttpsCertUiState.Error)

        val errorState = state as HttpsCertUiState.Error
        assertEquals("Connection refused", errorState.message)
    }

    @Test
    fun `selectHistoryEntry populates host and port`() = runTest {
        val entry = HttpsCertHistoryEntry(
            timestamp = "2024/01/15 10:30:00",
            host = "example.com",
            port = 8443,
            status = CertHistoryStatus.VALID,
            summary = "RSA 2048 · valid"
        )

        viewModel.selectHistoryEntry(entry)

        assertEquals("example.com", viewModel.host.value)
        assertEquals("8443", viewModel.port.value)
    }

    @Test
    fun `selectHistoryEntry triggers fetchCert`() = runTest {
        val entry = HttpsCertHistoryEntry(
            timestamp = "2024/01/15 10:30:00",
            host = "example.com",
            port = 443,
            status = CertHistoryStatus.VALID,
            summary = "RSA 2048"
        )

        coEvery { repository.fetchCertificate("example.com", 443) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.selectHistoryEntry(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HttpsCertUiState.Success)
    }

    @Test
    fun `reset returns to Idle state`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HttpsCertUiState.Success)

        viewModel.reset()
        assertTrue(viewModel.uiState.value is HttpsCertUiState.Idle)
    }

    @Test
    fun `reset cancels in-flight fetch job`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        viewModel.reset()

        assertTrue(viewModel.uiState.value is HttpsCertUiState.Idle)
    }

    @Test
    fun `history is loaded on init`() = runTest {
        val savedHistory = listOf(
            HttpsCertHistoryEntry(
                timestamp = "2024/01/15 10:30:00",
                host = "google.com",
                port = 443,
                status = CertHistoryStatus.VALID,
                summary = "RSA 2048 · valid"
            )
        )

        coEvery { historyStore.historyFlow } returns flowOf(savedHistory)

        val newViewModel = HttpsCertViewModel(repository, historyStore)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, newViewModel.history.value.size)
        assertEquals("google.com", newViewModel.history.value[0].host)
    }

    @Test
    fun `history is saved after successful fetch`() = runTest {
        coEvery { repository.fetchCertificate("google.com", 443) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `history is deduplicated by host and port`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        // Fetch same host+port twice
        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.history.value.size)
    }

    @Test
    fun `history is capped at 5 entries`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        // Fetch 6 different hosts
        for (i in 1..6) {
            viewModel.onHostChange("host${i}.example.com")
            viewModel.fetchCert()
            testDispatcher.scheduler.advanceUntilIdle()
        }

        assertEquals(5, viewModel.history.value.size)
    }

    @Test
    fun `history entry for expired certificate`() = runTest {
        val expiredCert = sampleCertificateInfo.copy(
            validityStatus = CertValidityStatus.EXPIRED,
            daysUntilExpiry = -30
        )

        coEvery { repository.fetchCertificate("expired.com", 443) } returns
            HttpsCertResult.CertExpired(expiredCert)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `history entry status for success with long validity`() = runTest {
        val longValidCert = sampleCertificateInfo.copy(
            validityStatus = CertValidityStatus.VALID,
            daysUntilExpiry = 400
        )

        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(longValidCert)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `history entry status for expiring soon`() = runTest {
        val expiringSoonCert = sampleCertificateInfo.copy(
            validityStatus = CertValidityStatus.EXPIRING_SOON,
            daysUntilExpiry = 15
        )

        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(expiringSoonCert)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { historyStore.save(any()) }
    }

    @Test
    fun `fetchCert trims host value`() = runTest {
        viewModel.onHostChange("  google.com  ")
        coEvery { repository.fetchCertificate("google.com", 443) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        // Should have called repository with trimmed value
        coVerify { repository.fetchCertificate("google.com", 443) }
    }

    @Test
    fun `multiple fetchCert calls cancel previous job`() = runTest {
        coEvery { repository.fetchCertificate(any(), any()) } returns
            HttpsCertResult.Success(sampleCertificateInfo)

        // Start first fetch
        viewModel.fetchCert()

        // Start second fetch immediately
        viewModel.fetchCert()
        testDispatcher.scheduler.advanceUntilIdle()

        // Should complete without issues
        assertTrue(viewModel.uiState.value is HttpsCertUiState.Success)
    }
}
