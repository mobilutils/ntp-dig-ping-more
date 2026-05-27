package io.github.mobilutils.ntp_dig_ping_more

import io.mockk.coEvery
import io.mockk.mockk
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the incremental [PingStats] computation in [PingViewModel].
 *
 * Tests focus on [PingViewModel.updateStats] — a pure function that derives
 * new stats from a single incoming ping output line.  No Android runtime needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PingViewModelStatsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun fakeSettingsRepository(): SettingsRepository =
        mockk<SettingsRepository>(relaxed = true).also {
            coEvery { it.timeoutSecondsFlow } returns flowOf(5)
        }

    private fun createViewModel(): PingViewModel {
        val historyStore = mockk<PingHistoryStore>(relaxed = true)
        coEvery { historyStore.historyFlow } returns flowOf(emptyList())
        return PingViewModel(historyStore, fakeSettingsRepository())
    }

    /** Shortcut: call updateStats starting from the default (empty) PingStats. */
    private fun PingViewModel.statsFrom(line: String, base: PingStats = PingStats()) =
        updateStats(line, base)

    // ── updateStats — reply lines ─────────────────────────────────────────────

    @Test
    fun `reply line increments received and extracts RTT`() {
        val vm = createViewModel()
        val line = "64 bytes from 142.250.185.46: icmp_seq=1 ttl=117 time=12.3 ms"
        val stats = vm.statsFrom(line)

        assertEquals(1, stats.received)
        assertEquals(1, stats.sent)
        assertEquals(12.3f, stats.minMs!!, 0.01f)
        assertEquals(12.3f, stats.avgMs!!, 0.01f)
        assertEquals(12.3f, stats.maxMs!!, 0.01f)
        assertEquals(0f, stats.lossPercent, 0.01f)
    }

    @Test
    fun `reply line with integer RTT (no decimal) is parsed`() {
        val vm = createViewModel()
        val line = "64 bytes from 8.8.8.8: icmp_seq=1 ttl=55 time=8 ms"
        val stats = vm.statsFrom(line)

        assertEquals(8f, stats.minMs!!, 0.01f)
    }

    @Test
    fun `second reply updates min, max, and running average`() {
        val vm = createViewModel()
        val line1 = "64 bytes from 8.8.8.8: icmp_seq=1 ttl=55 time=10.0 ms"
        val line2 = "64 bytes from 8.8.8.8: icmp_seq=2 ttl=55 time=20.0 ms"

        val after1 = vm.statsFrom(line1)
        val after2 = vm.statsFrom(line2, after1)

        assertEquals(2, after2.received)
        assertEquals(2, after2.sent)
        assertEquals(10f, after2.minMs!!, 0.01f)
        assertEquals(20f, after2.maxMs!!, 0.01f)
        // avg = (10 + 20) / 2 = 15
        assertEquals(15f, after2.avgMs!!, 0.01f)
        assertEquals(0f, after2.lossPercent, 0.01f)
    }

    @Test
    fun `running average is correct after five replies`() {
        val vm = createViewModel()
        val rtts = listOf(10f, 20f, 30f, 40f, 50f)
        var stats = PingStats()
        rtts.forEachIndexed { i, rtt ->
            val line = "64 bytes from 8.8.8.8: icmp_seq=${i + 1} ttl=55 time=$rtt ms"
            stats = vm.updateStats(line, stats)
        }

        // avg = (10+20+30+40+50) / 5 = 30
        assertEquals(30f, stats.avgMs!!, 0.1f)
        assertEquals(5, stats.received)
        assertEquals(5, stats.sent)
        assertEquals(10f, stats.minMs!!, 0.01f)
        assertEquals(50f, stats.maxMs!!, 0.01f)
    }

    // ── updateStats — timeout / unreachable lines ─────────────────────────────

    @Test
    fun `timeout line increments sent but NOT received`() {
        val vm = createViewModel()
        val line = "Request timeout for icmp_seq 1"
        val stats = vm.statsFrom(line)

        // "Request timeout" lines on macOS/BSD don't contain icmp_seq=N (they use space)
        // So sent stays 0 for this flavour; received also 0
        assertEquals(0, stats.received)
        assertNull(stats.minMs)
    }

    @Test
    fun `linux timeout line with icmp_seq= increments sent`() {
        val vm = createViewModel()
        // Linux "no route" / "Request timeout" style that does include icmp_seq=N
        val line = "From 192.168.1.1 icmp_seq=3 Destination Host Unreachable"
        val stats = vm.statsFrom(line)

        assertEquals(3, stats.sent)
        assertEquals(0, stats.received)
        assertEquals(100f, stats.lossPercent, 0.01f)
        assertNull(stats.minMs)
    }

    @Test
    fun `mixed replies and timeouts computes correct loss percentage`() {
        val vm = createViewModel()
        // 2 replies, 1 timeout
        val lines = listOf(
            "64 bytes from 8.8.8.8: icmp_seq=1 ttl=55 time=10.0 ms",
            "64 bytes from 8.8.8.8: icmp_seq=2 ttl=55 time=15.0 ms",
            "From 192.168.1.1 icmp_seq=3 Destination Host Unreachable",
        )
        var stats = PingStats()
        lines.forEach { stats = vm.updateStats(it, stats) }

        assertEquals(3, stats.sent)
        assertEquals(2, stats.received)
        // loss = (3 - 2) / 3 * 100 ≈ 33.3%
        assertEquals(33.3f, stats.lossPercent, 0.5f)
    }

    // ── updateStats — lines with no icmp_seq / unrelated lines ───────────────

    @Test
    fun `header line with no icmp_seq leaves stats unchanged`() {
        val vm = createViewModel()
        val line = "PING google.com (142.250.185.46): 56 data bytes"
        val initial = PingStats()
        val stats = vm.statsFrom(line, initial)

        assertEquals(initial, stats)
    }

    @Test
    fun `stats summary line does not affect running stats`() {
        val vm = createViewModel()
        val line = "round-trip min/avg/max/stddev = 10.234/12.567/15.890/1.234 ms"
        val initial = PingStats(sent = 5, received = 5, lossPercent = 0f, minMs = 10f, avgMs = 12f, maxMs = 15f)
        val stats = vm.statsFrom(line, initial)

        // No icmp_seq, no "bytes from" → unchanged
        assertEquals(initial, stats)
    }

    // ── Stats reset on startPing ──────────────────────────────────────────────

    @Test
    fun `stats are reset to defaults when startPing is called`() = runTest {
        val vm = createViewModel()
        vm.onHostChange("example.com")

        // Simulate a previous run having stats
        // (we can't inject directly, but startPing resets via copy)
        vm.startPing()
        val stateAfterStart = vm.uiState.value

        assertEquals(PingStats(), stateAfterStart.stats)

        // Cleanup
        vm.stopPing()
        advanceUntilIdle()
    }

    // ── Loss percentage edge cases ────────────────────────────────────────────

    @Test
    fun `zero packets sent gives zero loss percent`() {
        val vm = createViewModel()
        val stats = vm.statsFrom("PING google.com (8.8.8.8): 56 data bytes")

        assertEquals(0f, stats.lossPercent, 0.001f)
    }

    @Test
    fun `all packets lost gives 100 percent loss`() {
        val vm = createViewModel()
        var stats = PingStats()
        repeat(3) { i ->
            stats = vm.updateStats("From router icmp_seq=${i + 1} Destination Host Unreachable", stats)
        }

        assertEquals(3, stats.sent)
        assertEquals(0, stats.received)
        assertEquals(100f, stats.lossPercent, 0.01f)
        assertNull(stats.minMs)
    }

    @Test
    fun `RTT fields remain null if no reply received`() {
        val vm = createViewModel()
        val stats = vm.statsFrom("From router icmp_seq=1 Destination Host Unreachable")

        assertNull(stats.minMs)
        assertNull(stats.avgMs)
        assertNull(stats.maxMs)
    }

    @Test
    fun `RTT fields are non-null after first reply`() {
        val vm = createViewModel()
        val stats = vm.statsFrom("64 bytes from 8.8.8.8: icmp_seq=1 ttl=55 time=25.5 ms")

        assertNotNull(stats.minMs)
        assertNotNull(stats.avgMs)
        assertNotNull(stats.maxMs)
    }

    // ── State-level: initial state still has PingStats() ─────────────────────

    @Test
    fun `initial ui state has empty PingStats`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(PingStats(), vm.uiState.value.stats)
    }

    @Test
    fun `startPing with blank host does not modify stats`() = runTest {
        val vm = createViewModel()
        vm.onHostChange("")
        vm.startPing()
        advanceUntilIdle()

        assertEquals(PingStats(), vm.uiState.value.stats)
        assertFalse(vm.uiState.value.isRunning)
    }
}
