package io.github.mobilutils.ntp_dig_ping_more

import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

// ────────────────────────────────────────────────────────────────────
// Data models
// ────────────────────────────────────────────────────────────────────

/**
 * Parsed bulk configuration.
 *
 * @param outputFile  Optional expanded output file path (null = don't write to file).
 * @param commands    Ordered command map (key = command name, value = command string).
 */
data class BulkConfig(
    val outputFile: String?,
    val commands: Map<String, String>,
)

/**
 * Result of executing a single bulk command.
 */
sealed class BulkCommandResult {
    abstract val commandName: String
    abstract val command: String
}

data class BulkCommandSuccess(
    override val commandName: String,
    override val command: String,
    val outputLines: List<String>,
    val durationMs: Long,
) : BulkCommandResult()

data class BulkCommandError(
    override val commandName: String,
    override val command: String,
    val errorMessage: String,
) : BulkCommandResult()

data class BulkCommandTimeout(
    override val commandName: String,
    override val command: String,
) : BulkCommandResult()

/** Progress callback emitted during bulk execution. */
data class BulkProgress(
    val currentIndex: Int,
    val totalCommands: Int,
    val currentCommandName: String,
    val currentCommand: String,
)

// ────────────────────────────────────────────────────────────────────
// JSON parser
// ────────────────────────────────────────────────────────────────────

object BulkConfigParser {

    /**
     * Parses a JSON string into a [BulkConfig].
     *
     * Expected structure:
     * ```json
     * {
     *   "output-file": "~/Downloads/output.txt",
     *   "run": {
     *     "cmd1": "ping -c 4 google.com",
     *     "cmd2": "dig @1.1.1.1 example.com"
     *   }
     * }
     * ```
     */
    @Throws(IllegalArgumentException::class)
    fun parse(json: String): BulkConfig {
        val root = JSONObject(json)

        val outputFile = runCatching {
            val path = root.optString("output-file", null)
            if (path.isNullOrBlank()) null
            else expandTilde(path)
        }.getOrNull()

        val runObj = root.optJSONObject("run")
            ?: throw IllegalArgumentException("Missing required 'run' object in configuration")

        val commands = mutableMapOf<String, String>()
        val keys = runObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = runObj.getString(key).trim()
            if (value.isNotBlank()) {
                commands[key] = value
            }
        }

        return BulkConfig(outputFile, commands)
    }

    /** Expands `~` to the external storage directory path. */
    private fun expandTilde(path: String): String {
        if (!path.startsWith("~/")) return path
        val externalDir = Environment.getExternalStorageDirectory().absolutePath
        return "$externalDir${path.substring(1)}"
    }
}

// ────────────────────────────────────────────────────────────────────
// Command mapper & executor
// ────────────────────────────────────────────────────────────────────

/**
 * Executes bulk commands sequentially, mapping each to the appropriate tool.
 *
 * @param config      Parsed [BulkConfig].
 * @param cancellationToken  Optional cancellation token; if `isCancelled` becomes true,
 *                         execution stops and returns partial results.
 * @param onProgress  Callback for progress updates (invoked on the calling thread).
 * @return            List of [BulkCommandResult] in command order.
 */
class BulkActionsRepository(
    private val digRepo: DigRepository = DigRepository(),
    private val ntpRepo: NtpRepository = NtpRepository(),
    private val certRepo: HttpsCertRepository = HttpsCertRepository(),
) {

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Executes all commands in [config] sequentially.
     */
    suspend fun executeCommands(
        config: BulkConfig,
        cancellationToken: AtomicBoolean = AtomicBoolean(false),
        onProgress: ((BulkProgress) -> Unit)? = null,
    ): List<BulkCommandResult> {
        val results = mutableListOf<BulkCommandResult>()
        val commands = config.commands.toList()
        val total = commands.size

        commands.forEachIndexed { index, (name, cmd) ->
            if (cancellationToken.get()) return results

            onProgress?.invoke(BulkProgress(index, total, name, cmd))

            val result = withTimeoutOrNull(30_000) {
                executeSingleCommand(name, cmd)
            }

            val finalResult = result ?: BulkCommandTimeout(name, cmd)
            results.add(finalResult)
        }

        return results
    }

    suspend fun executeSingleCommand(name: String, cmd: String): BulkCommandResult {
        val trimmed = cmd.trim()
        val parts = trimmed.split(Regex("\\s+"))
        val prefix = parts.firstOrNull()?.lowercase() ?: ""

        return when {
            prefix == "ping" -> executePing(name, trimmed, parts)
            prefix == "dig"  -> executeDig(name, trimmed)
            prefix == "ntp"  -> executeNtp(name, trimmed)
            prefix == "nmap" -> executeNmap(name, trimmed)
            prefix == "checkcert" -> executeCheckcert(name, trimmed)
            else             -> executeRaw(name, trimmed)
        }
    }

    // ── ping ────────────────────────────────────────────────────────────

    private suspend fun executePing(name: String, cmd: String, parts: List<String>): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "4", parts.last()))
                val output = process.inputStream.bufferedReader().readLines()
                val exitCode = process.waitFor()
                val duration = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    add("[${timestampFmt.format(LocalDateTime.now())}] Status: ${if (exitCode == 0) "SUCCESS" else "FAILED"} (${duration}ms)")
                    addAll(output)
                }
                BulkCommandSuccess(name, cmd, lines, duration)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── dig ─────────────────────────────────────────────────────────────

    private suspend fun executeDig(name: String, cmd: String): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Parse: dig @server fqdn
                val parts = cmd.split(Regex("\\s+"))
                val serverIdx = parts.indexOfFirst { it == "@" }
                val (server, fqdn) = if (serverIdx > 0 && serverIdx < parts.size - 1) {
                    parts[serverIdx + 1] to parts[serverIdx + 2]
                } else {
                    "8.8.8.8" to parts.last()
                }

                val result = digRepo.resolve(server, fqdn)
                val duration = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    when (result) {
                        is DigResult.Success -> {
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: SUCCESS (${duration}ms)")
                            add(";; SERVER: ${result.dnsServer}")
                            add(";; QUESTION: ${result.questionSection}")
                            result.records.forEach { add(";; $it") }
                        }
                        is DigResult.NxDomain ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: NXDOMAIN (${duration}ms)")
                        is DigResult.DnsServerError ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: DNS ERROR - ${result.detail} (${duration}ms)")
                        is DigResult.NoNetwork ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: NO NETWORK (${duration}ms)")
                        is DigResult.Error ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: ERROR - ${result.message} (${duration}ms)")
                    }
                }
                BulkCommandSuccess(name, cmd, lines, duration)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── ntp ─────────────────────────────────────────────────────────────

    private suspend fun executeNtp(name: String, cmd: String): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                val parts = cmd.split(Regex("\\s+"))
                val pool = parts.getOrNull(1) ?: "pool.ntp.org"

                val result = ntpRepo.query(pool)
                val duration = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    when (result) {
                        is NtpResult.Success -> {
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: SUCCESS (${duration}ms)")
                            add("  Server Time:  ${result.serverTime}")
                            add("  Clock Offset: ${result.offsetMs} ms")
                            add("  Round-Trip:   ${result.delayMs} ms")
                        }
                        is NtpResult.DnsFailure ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: DNS FAILURE - ${result.host} (${duration}ms)")
                        is NtpResult.Timeout ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: TIMEOUT - ${result.host} (${duration}ms)")
                        is NtpResult.NoNetwork ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: NO NETWORK (${duration}ms)")
                        is NtpResult.Error ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: ERROR - ${result.message} (${duration}ms)")
                    }
                }
                BulkCommandSuccess(name, cmd, lines, duration)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── nmap (port scan) ────────────────────────────────────────────────

    private suspend fun executeNmap(name: String, cmd: String): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Parse: nmap -p ports host
                val parts = cmd.split(Regex("\\s+"))
                val portStr = parts.getOrNull(parts.indexOfFirst { it == "-p" } + 1) ?: "22"
                val host = parts.getOrNull(parts.indexOfFirst { it == "-p" } + 2)
                    ?: parts.last()

                val ports = parsePortRange(portStr)
                val openPorts = mutableListOf<Int>()

                ports.forEach { port ->
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, port), 2000)
                        socket.close()
                        openPorts.add(port)
                    } catch (_: Exception) {
                        // Port closed/filtered
                    }
                }

                val duration = System.currentTimeMillis() - t0
                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    add("[${timestampFmt.format(LocalDateTime.now())}] Status: SCAN COMPLETE (${duration}ms)")
                    if (openPorts.isEmpty()) {
                        add("  No open ports found.")
                    } else {
                        add("  Open ports: ${openPorts.joinToString(", ")}")
                    }
                }
                BulkCommandSuccess(name, cmd, lines, duration)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── checkcert ───────────────────────────────────────────────────────

    private suspend fun executeCheckcert(name: String, cmd: String): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Parse: checkcert -p port host
                val parts = cmd.split(Regex("\\s+"))
                val portIdx = parts.indexOfFirst { it == "-p" }
                val port = parts.getOrNull(portIdx + 1)?.toIntOrNull() ?: 443
                val host = parts.getOrNull(portIdx + 2) ?: parts.last()

                val result = certRepo.fetchCertificate(host, port)
                val duration = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    when (result) {
                        is HttpsCertResult.Success -> {
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: SUCCESS (${duration}ms)")
                            add("  Subject: CN=${result.info.subject.cn}")
                            add("  Issuer: CN=${result.info.issuer.cn}")
                            add("  Valid: ${result.info.notBefore} to ${result.info.notAfter}")
                            add("  Days until expiry: ${result.info.daysUntilExpiry}")
                            add("  Serial: ${result.info.serialNumber}")
                            add("  SHA256: ${result.info.sha256Fingerprint}")
                        }
                        is HttpsCertResult.NoNetwork ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: NO NETWORK (${duration}ms)")
                        is HttpsCertResult.HostnameUnresolved ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: HOST UNRESOLVED - ${result.host} (${duration}ms)")
                        is HttpsCertResult.Timeout ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: TIMEOUT - ${result.host} (${duration}ms)")
                        is HttpsCertResult.CertExpired -> {
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: CERT EXPIRED (${duration}ms)")
                            result.info?.let {
                                add("  Subject: CN=${it.subject.cn}")
                                add("  Expired: ${it.notAfter}")
                            }
                        }
                        is HttpsCertResult.UntrustedChain ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: UNTRUSTED - ${result.reason} (${duration}ms)")
                        is HttpsCertResult.Error ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: ERROR - ${result.message} (${duration}ms)")
                    }
                }
                BulkCommandSuccess(name, cmd, lines, duration)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── raw ─────────────────────────────────────────────────────────────

    private suspend fun executeRaw(name: String, cmd: String): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                val parts = cmd.split(Regex("\\s+"))
                val process = Runtime.getRuntime().exec(parts.toTypedArray())
                val output = process.inputStream.bufferedReader().readLines()
                val error = process.errorStream.bufferedReader().readLines()
                val exitCode = process.waitFor()
                val duration = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    add("[${timestampFmt.format(LocalDateTime.now())}] Exit code: $exitCode (${duration}ms)")
                    addAll(output)
                    if (error.isNotEmpty()) {
                        add("--- stderr ---")
                        addAll(error)
                    }
                }
                BulkCommandSuccess(name, cmd, lines, duration)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Parses port ranges like "80,443,8080" or "80-443" into a list of ints. */
    private fun parsePortRange(range: String): List<Int> {
        val ports = mutableListOf<Int>()
        range.split(",").forEach { segment ->
            if (segment.contains("-")) {
                val (start, end) = segment.split("-").map { it.trim().toInt() }
                ports.addAll(start..end)
            } else {
                segment.toIntOrNull()?.let { ports.add(it) }
            }
        }
        return ports
    }

    /** Executes [block] with a 30s timeout; returns null on timeout. */
    private suspend fun <T> withTimeoutOrNull(timeoutMs: Long, block: suspend () -> T): T? {
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e // Don't swallow cancellation
        }
    }
}
