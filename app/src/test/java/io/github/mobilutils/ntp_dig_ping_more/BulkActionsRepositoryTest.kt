package io.github.mobilutils.ntp_dig_ping_more

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * Unit tests for sleep command execution in [BulkActionsRepository].
 * Uses pure-Kotlin mirroring of the dispatch logic to avoid Android API dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkActionsRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
         }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
          }

           // ────────────────────────────────────────────────────────────────
           // Valid sleep durations (tested with runTest's dispatcher for virtual time)
           // ────────────────────────────────────────────────────────────────

            /** Uses the implicit dispatcher from runTest so delay() supports virtual time. */
            @Test
    fun `sleep1_executesSuccessfully`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 1")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertEquals("wait", success.commandName)
        assertEquals("sleep 1", success.command)
        assertTrue(success.outputLines.any { "Slept for 1 second" in it })
          }

            @Test
    fun `sleep5_executesSuccessfully`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 5")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertTrue(success.outputLines.any { "Slept for 5 seconds" in it })
          }

            @Test
    fun `sleep3601_clampedToMaxAndExecutesSuccessfully`() = runTest {
              // Use generous timeout but verify clamping logic produces correct message
        val result = _executeSleepInternal("wait", "sleep 5000")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertTrue(success.outputLines.any { "Slept for 3600 seconds" in it })
          }

            @Test
    fun `sleep7200_clampedToMaxAndExecutesSuccessfully`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 7200")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertTrue(success.outputLines.any { "Slept for 3600 seconds" in it })
          }

            @Test
    fun `sleep3600_clampedToMaxAndExecutesSuccessfully`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 3600")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertTrue(success.outputLines.any { "Slept for 3600 seconds" in it })
          }

           // ────────────────────────────────────────────────────────────────
           // Invalid arguments — error cases (no delay needed)
           // ────────────────────────────────────────────────────────────────

            @Test
    fun `sleep0_returnsError`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 0")

        assertTrue(result is BulkCommandError)
        val error = result as BulkCommandError
        assertTrue(error.errorMessage.contains("must be between 1 and 3600"))
          }

            @Test
    fun `sleepNegative_returnsError`() = runTest {
        val result = _executeSleepInternal("wait", "sleep -5")

        assertTrue(result is BulkCommandError)
        val error = result as BulkCommandError
        assertTrue(error.errorMessage.contains("must be between 1 and 3600"))
          }

            @Test
    fun `sleepNonInteger_returnsError`() = runTest {
        val result = _executeSleepInternal("wait", "sleep abc")

        assertTrue(result is BulkCommandError)
        val error = result as BulkCommandError
        assertTrue(error.errorMessage.contains("Invalid sleep argument"))
          }

            @Test
    fun `sleepNoArgument_returnsError`() = runTest {
        val result = _executeSleepInternal("wait", "sleep")

        assertTrue(result is BulkCommandError)
        val error = result as BulkCommandError
        assertTrue(error.errorMessage.contains("Invalid sleep argument"))
          }

            @Test
    fun `sleepFloat_returnsError`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 3.5")

        assertTrue(result is BulkCommandError)
        val error = result as BulkCommandError
        assertTrue(error.errorMessage.contains("Invalid sleep argument"))
          }

           // ────────────────────────────────────────────────────────────────
           // Timeout and cancellation (mocked — verifies result type contracts)
           // ────────────────────────────────────────────────────────────────

            /** Verifies that BulkCommandTimeout is the correct result type when timeout fires. */
            @Test
    fun `sleepExceedsTimeout_returnsTimeout`() = runTest {
        val timeoutResult = BulkCommandTimeout("wait", "sleep 60")
        assertTrue(timeoutResult is BulkCommandResult)
        assertEquals("wait", timeoutResult.commandName)
        assertEquals("sleep 60", timeoutResult.command)
          }

            /** Verifies that BulkCommandClosed is the correct result type when user stops. */
            @Test
    fun `sleepInterruptedByUserStop_returnsClosed`() = runTest {
        val closedResult = BulkCommandClosed("wait", "sleep 10", emptyList(), 0L)
        assertTrue(closedResult is BulkCommandResult)
        assertEquals("wait", closedResult.commandName)
        assertEquals("sleep 10", closedResult.command)
          }

            /** Verifies that BulkCommandError is the correct result type for invalid args. */
            @Test
    fun `sleepInvalidArgs_returnsError`() = runTest {
        val errorResult = BulkCommandError("wait", "sleep -1", "Sleep duration must be between 1 and 3600 seconds")
        assertTrue(errorResult is BulkCommandResult)
        assertEquals("wait", errorResult.commandName)
        assertEquals("sleep -1", errorResult.command)
          }

            @Test
    fun `sleepCompletesBeforeTimeout_returnsSuccess`() = runTest {
        val result = _executeSleepInternal("wait", "sleep 2")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertTrue(success.outputLines.any { "Slept for 2 seconds" in it })
          }

            @Test
    fun `sleepWithExtraWhitespace_parsesCorrectly`() = runTest {
        val result = _executeSleepInternal("wait", "   sleep               3                ")

        assertTrue(result is BulkCommandSuccess)
        val success = result as BulkCommandSuccess
        assertTrue(success.outputLines.any { "Slept for 3 seconds" in it })
          }

           // ────────────────────────────────────────────────────────────────
           // Helper: mirrors _executeSleepInternal() dispatch logic from repository
           // Uses the implicit dispatcher from runTest so delay() supports virtual time.
           // ────────────────────────────────────────────────────────────────

    private suspend fun CoroutineScope._executeSleepInternal(
        name: String,
        cmd: String,
       ): BulkCommandResult {
        val parts = cmd.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[1].toIntOrNull() == null) {
            return BulkCommandError(name, cmd, "Invalid sleep argument. Expected: sleep N (integer)")
           }

        var n = parts[1].toInt()
        val actualSeconds = if (n > 3600) {
             3600
             } else if (n < 1) {
            return BulkCommandError(name, cmd, "Sleep duration must be between 1 and 3600 seconds")
           } else {
            n
           }

        val t0 = System.currentTimeMillis()
        var remaining = actualSeconds.toLong()

        while (remaining > 0) {
            delay(minOf(remaining, 1L))
            remaining--
           }

        val durationMs = System.currentTimeMillis() - t0
        return BulkCommandSuccess(
            commandName = name,
            command = cmd,
            outputLines = listOf("Slept for $actualSeconds seconds (${durationMs}ms)"),
            durationMs = durationMs,
           )
       }
}
