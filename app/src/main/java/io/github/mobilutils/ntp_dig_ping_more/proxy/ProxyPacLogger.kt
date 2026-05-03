package io.github.mobilutils.ntp_dig_ping_more.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Proxy PAC Logger
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lightweight, thread-safe logger for Proxy PAC operations.
 *
 * When [enabled] (or when the caller forces logging via [ProxyResolver.forceLogging]),
 * high-level events (PAC fetches, proxy resolutions, errors) are:
 *  1. Appended to an in-memory [buffer] (capped at [MAX_LINES]).
 *  2. Asynchronously written to a persistent file at [logFile] (also capped at [MAX_LINES]).
 *
 * All file I/O runs on [Dispatchers.IO] via a dedicated [CoroutineScope] and is
 * serialised with a [Mutex] to prevent concurrent writes from corrupting the file.
 * Logging is strictly fire-and-forget — it never blocks the caller, never throws,
 * and never alters the control flow of the proxy resolution pipeline.
 *
 * @param logFile The persistent log file. Typically `context.filesDir.resolve("proxypac-logs.txt")`.
 */
class ProxyPacLogger(
    internal val logFile: File,
) {

    companion object {
        /** Maximum number of lines retained in both the in-memory buffer and the file. */
        internal const val MAX_LINES = 500

        /** Timestamp format prepended to every log line. */
        private const val TIMESTAMP_FMT = "yyyy-MM-dd HH:mm:ss.SSS"

        @Volatile
        private var instance: ProxyPacLogger? = null

        /**
         * Returns the application-wide singleton [ProxyPacLogger].
         *
         * All ViewModels (Settings, HttpsCert, GoogleTimeSync, BulkActions) must
         * use this method so they share the same in-memory buffer and file handle.
         * Creating separate instances would cause events logged by one screen to
         * be invisible to the "View Logs" dialog on the Settings screen.
         *
         * @param logFile The persistent log file — typically
         *                `context.filesDir.resolve("proxypac-logs.txt")`.
         */
        fun getInstance(logFile: File): ProxyPacLogger {
            return instance ?: synchronized(this) {
                instance ?: ProxyPacLogger(logFile).also { instance = it }
            }
        }

        /**
         * Replaces the singleton instance. **Test-only** — allows injecting
         * a fresh logger between tests without leaking state.
         */
        internal fun resetInstance() {
            synchronized(this) { instance = null }
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Master toggle. When `false`, [log] is a no-op with zero overhead. */
    @Volatile
    var enabled: Boolean = false

    /** In-memory rolling buffer. Access must be `synchronized(buffer)`. */
    internal val buffer = ArrayDeque<String>(MAX_LINES)

    /** Dedicated scope for async file writes — survives individual call cancellations. */
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Serialises all file operations to prevent concurrent corruption. */
    private val mutex = Mutex()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Logs a high-level proxy event.
     *
     * Gated on [enabled] — when `false`, this is a no-op.
     * The event is timestamped, added to the in-memory buffer, and asynchronously
     * appended to the persistent file. Both stores are capped at [MAX_LINES].
     *
     * This method never blocks, never suspends, and never throws.
     *
     * @param event Short, single-line description of the event
     *              (e.g. `"PAC_FETCH_SUCCESS url=http://..."`, `"PROXY_RESOLVED host=... result=DIRECT"`).
     * @param force  When `true`, the event is logged regardless of [enabled].
     *               Used by [ProxyResolver] when `forceLogging` is set via
     *               the BulkActions `"log-proxy": true` JSON field.
     */
    fun log(event: String, force: Boolean = false) {
        if (!enabled && !force) return

        val timestamp = SimpleDateFormat(TIMESTAMP_FMT, Locale.US).format(Date())
        val line = "[$timestamp] $event"

        // Buffer — synchronised for thread safety
        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }

        // File — async, never blocks caller
        writeScope.launch {
            try {
                mutex.withLock {
                    appendAndRoll(line)
                }
            } catch (_: Exception) {
                // Swallow — logging must never affect app behaviour
            }
        }
    }

    /**
     * Returns an immutable snapshot of the in-memory log buffer.
     *
     * The returned list is a copy — safe to read on any thread.
     */
    fun getLogs(): List<String> {
        synchronized(buffer) {
            return buffer.toList()
        }
    }

    /**
     * Clears both the in-memory buffer and the persistent log file.
     *
     * File truncation runs under the [mutex] to avoid racing with [log].
     */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
        }
        writeScope.launch {
            try {
                mutex.withLock {
                    logFile.writeText("")
                }
            } catch (_: Exception) {
                // Swallow
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Appends [line] to the log file, then enforces the [MAX_LINES] rolling limit.
     *
     * Must be called under [mutex].
     */
    private fun appendAndRoll(line: String) {
        // Ensure parent directory exists
        logFile.parentFile?.mkdirs()

        // Append line
        logFile.appendText(line + "\n", Charsets.US_ASCII)

        // Enforce rolling limit
        val allLines = logFile.readLines(Charsets.US_ASCII)
        if (allLines.size > MAX_LINES) {
            val trimmed = allLines.takeLast(MAX_LINES)
            logFile.writeText(trimmed.joinToString("\n") + "\n", Charsets.US_ASCII)
        }
    }
}
