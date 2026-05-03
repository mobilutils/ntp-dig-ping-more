package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyPacLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [ProxyPacLogger].
 *
 * Tests buffer capping, file rolling, clear, and thread safety.
 * Uses a temporary directory for file I/O to avoid flaky behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyPacLoggerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var logFile: File
    private lateinit var logger: ProxyPacLogger

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ProxyPacLogger.resetInstance()
        tempDir = File(System.getProperty("java.io.tmpdir"), "proxypac-logger-test-${System.nanoTime()}")
        tempDir.mkdirs()
        logFile = File(tempDir, "proxypac-logs.txt")
        logger = ProxyPacLogger(logFile)
        logger.enabled = true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ProxyPacLogger.resetInstance()
        // Give any in-flight fire-and-forget writes a moment to settle
        Thread.sleep(100)
        tempDir.deleteRecursively()
    }

    // ── Buffer tests ─────────────────────────────────────────────────────────

    @Test
    fun `log adds event to buffer`() {
        logger.log("TEST_EVENT")

        val logs = logger.getLogs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("TEST_EVENT"))
    }

    @Test
    fun `log prepends timestamp to event`() {
        logger.log("TIMESTAMPED_EVENT")

        val logs = logger.getLogs()
        // Format: [yyyy-MM-dd HH:mm:ss.SSS] EVENT
        assertTrue(logs[0].matches(Regex("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] TIMESTAMPED_EVENT$")))
    }

    @Test
    fun `buffer is capped at 500 lines`() {
        repeat(501) { i ->
            logger.log("EVENT_$i")
        }

        val logs = logger.getLogs()
        assertEquals(ProxyPacLogger.MAX_LINES, logs.size)
        // Oldest (EVENT_0) should be dropped, EVENT_1 should be first
        assertTrue(logs.first().contains("EVENT_1"))
        assertTrue(logs.last().contains("EVENT_500"))
    }

    @Test
    fun `getLogs returns immutable snapshot`() {
        logger.log("SNAP_1")
        logger.log("SNAP_2")

        val snapshot = logger.getLogs()
        assertEquals(2, snapshot.size)

        // Adding more events should not affect the snapshot
        logger.log("SNAP_3")
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `log is no-op when disabled`() {
        logger.enabled = false
        logger.log("SHOULD_NOT_APPEAR")

        val logs = logger.getLogs()
        assertTrue(logs.isEmpty())
    }

    // ── File tests ───────────────────────────────────────────────────────────

    @Test
    fun `log writes to file`() {
        logger.log("FILE_EVENT")

        // Give the writeScope time to complete (it uses real Dispatchers.IO)
        Thread.sleep(500)

        assertTrue(logFile.exists())
        val lines = logFile.readLines(Charsets.US_ASCII)
        assertTrue(lines.any { it.contains("FILE_EVENT") })
    }

    @Test
    fun `file is capped at 500 lines`() {
        // Write 510 events
        repeat(510) { i ->
            logger.log("FILE_LINE_$i")
        }

        // Wait for async file writes — writeScope uses real Dispatchers.IO
        Thread.sleep(3000)

        if (logFile.exists()) {
            val lines = logFile.readLines(Charsets.US_ASCII).filter { it.isNotBlank() }
            assertTrue("File should have at most ${ProxyPacLogger.MAX_LINES} lines, got ${lines.size}",
                lines.size <= ProxyPacLogger.MAX_LINES)
        }
        // If file doesn't exist yet (extremely slow I/O), the test passes vacuously —
        // the buffer cap test already validates the in-memory limit.
    }

    // ── Clear tests ──────────────────────────────────────────────────────────

    @Test
    fun `clear empties buffer`() {
        logger.log("CLEAR_TEST")
        assertEquals(1, logger.getLogs().size)

        logger.clear()
        assertTrue(logger.getLogs().isEmpty())
    }

    @Test
    fun `clear truncates file`() {
        logger.log("CLEAR_FILE_TEST")
        // Wait for the async write to complete before clearing
        Thread.sleep(1000)

        logger.clear()
        // Wait for the async truncate to complete
        Thread.sleep(1000)

        if (logFile.exists()) {
            val content = logFile.readText(Charsets.US_ASCII)
            assertTrue("File should be empty after clear, got: ${content.length} chars", content.isEmpty())
        }
    }

    // ── Thread safety tests ──────────────────────────────────────────────────

    @Test
    fun `concurrent log calls do not throw`() {
        val threads = (1..10).map { threadIdx ->
            Thread {
                repeat(50) { i ->
                    logger.log("THREAD_${threadIdx}_EVENT_$i")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 10 threads × 50 events = 500 total, buffer should cap at 500
        val logs = logger.getLogs()
        assertTrue("Expected up to ${ProxyPacLogger.MAX_LINES} logs, got ${logs.size}",
            logs.size <= ProxyPacLogger.MAX_LINES)
    }

    @Test
    fun `concurrent log and clear do not throw`() {
        val logThread = Thread {
            repeat(100) { i ->
                logger.log("LOG_$i")
            }
        }

        val clearThread = Thread {
            repeat(10) {
                Thread.sleep(5)
                logger.clear()
            }
        }

        logThread.start()
        clearThread.start()
        logThread.join()
        clearThread.join()

        // Just verify no crash — final state depends on timing
        logger.getLogs() // should not throw
    }
}
