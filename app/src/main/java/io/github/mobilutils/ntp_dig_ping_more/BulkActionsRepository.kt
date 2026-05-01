package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import android.os.Environment
import io.github.mobilutils.ntp_dig_ping_more.deviceinfo.SystemInfoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * @param timeoutMs   Optional per-command timeout in milliseconds (null = use default 30s).
 * @param outputAsCsv Optional flag to output results as CSV (default false).
 */
data class BulkConfig(
    val outputFile: String?,
    val commands: Map<String, String>,
    val timeoutMs: Long? = null,
    val outputAsCsv: Boolean = false,
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

data class BulkCommandClosed(
    override val commandName: String,
    override val command: String,
    val outputLines: List<String>,
    val durationMs: Long,
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

    /** Application context, set once by BulkActionsViewModel factory. Used for private-dir path expansion. */
    @Volatile
    internal var appContext: Context? = null

    /**
     * Parses a per-command `-t N` timeout from the command string.
     * Returns the timeout in milliseconds, or null if not present.
     * Example: "ping -c 4 -t 10 google.com" → 10_000L
     */
    fun extractCommandTimeout(cmd: String): Long? {
        val parts = cmd.trim().split(Regex("\\s+"))
        val tIdx = parts.indexOf("-t")
        if (tIdx >= 0 && tIdx < parts.size - 1) {
            return parts[tIdx + 1].toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000L }
        }
        return null
    }

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
            val path = root.optString("output-file", "")
            if (path.isNullOrBlank()) null
            else expandTilde(path)
        }.getOrNull()

        val timeoutMs = runCatching {
            val seconds = root.optLong("timeout", 0L)
            if (seconds == null || seconds <= 0) null
            else seconds * 1000L
        }.getOrNull()

        val outputAsCsv = runCatching {
            root.optBoolean("outputAsCsv", false)
        }.getOrDefault(false)

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

        return BulkConfig(outputFile, commands, timeoutMs, outputAsCsv)
    }

    /** Expands `~` to the app's private files directory (no permissions needed on SDK 33+). */
    private fun expandTilde(path: String): String {
        if (!path.startsWith("~/")) return path
        val privateDir = appContext?.applicationContext?.filesDir?.absolutePath
             ?: Environment.getExternalStorageDirectory().absolutePath
        return "$privateDir${path.substring(1)}"
    }

    /**
     * Validates whether [rawPath] can be written to. If not, suggests a fallback path.
     *
     * @return `OutputFileValid(path)` if writable, or `OutputFileInvalid(original, suggested)` if not.
     */
    fun validateOutputFile(rawPath: String): OutputFileValidationResult {
        val expanded = expandTilde(rawPath)

        // Try to create parent dirs and a test file
        val parent = File(expanded).parentFile
        val canCreateDirs = parent?.mkdirs() == true || parent?.exists() == true
        if (!canCreateDirs) {
            val suggested = suggestFallbackPath(rawPath)
            return OutputFileValidationResult.Invalid(rawPath, suggested)
        }

        val testFile = File(expanded)
        val canWrite = try {
            testFile.createNewFile() && testFile.canWrite()
        } catch (_: Exception) {
            false
        }

        return if (canWrite) {
            // Clean up the test file
            runCatching { testFile.delete() }
            OutputFileValidationResult.Valid(expanded)
        } else {
            val suggested = suggestFallbackPath(rawPath)
            OutputFileValidationResult.Invalid(rawPath, suggested)
        }
    }

    /** Suggests a fallback writable path using the app's private directory. */
    private fun suggestFallbackPath(rawPath: String): String {
        val privateDir = appContext?.applicationContext?.filesDir?.absolutePath
             ?: Environment.getExternalStorageDirectory().absolutePath
        val bulkDir = "$privateDir/BulkActions"
        val fileName = rawPath.substringAfterLast("/")
        return "$bulkDir/$fileName"
    }

    /** Result of output file validation. */
    sealed class OutputFileValidationResult {
        data class Valid(val path: String) : OutputFileValidationResult()
        data class Invalid(val originalPath: String, val suggestedPath: String) : OutputFileValidationResult()
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
    private val context: Context,
    private val digRepo: DigRepository = DigRepository(),
    private val ntpRepo: NtpRepository = NtpRepository(),
    private val certRepo: HttpsCertRepository = HttpsCertRepository(),
) {

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Parses a per-command `-t N` timeout from the command string.
     * Returns the timeout in milliseconds, or null if not present.
     * Example: "ping -c 4 -t 10 google.com" → 10_000L
     */
    private fun extractCommandTimeout(cmd: String): Long? {
        val parts = cmd.trim().split(Regex("\\s+"))
        val tIdx = parts.indexOf("-t")
        if (tIdx >= 0 && tIdx < parts.size - 1) {
            return parts[tIdx + 1].toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000L }
        }
        return null
    }

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
        val defaultTimeoutMs = config.timeoutMs ?: 30_000L

        commands.forEachIndexed { index, (name, cmd) ->
            if (cancellationToken.get()) {
                results.add(BulkCommandTimeout(name, cmd))
                return@forEachIndexed
            }

            onProgress?.invoke(BulkProgress(index, total, name, cmd))

            // Per-command `-t N` overrides config-level timeout
            val commandTimeoutMs = extractCommandTimeout(cmd) ?: defaultTimeoutMs

            val result = withTimeoutOrNull(commandTimeoutMs) {
                executeSingleCommand(name, cmd, commandTimeoutMs)
            }

            val finalResult = result ?: BulkCommandTimeout(name, cmd)
            results.add(finalResult)
        }

        return results
    }

    suspend fun executeSingleCommand(name: String, cmd: String, timeoutMs: Long? = null): BulkCommandResult {
        val trimmed = cmd.trim()
        val parts = trimmed.split(Regex("\\s+"))
        val prefix = parts.firstOrNull()?.lowercase() ?: ""

        return when {
            prefix == "ping"            -> executePing(name, trimmed, parts, timeoutMs)
            prefix == "dig"             -> executeDig(name, trimmed, timeoutMs)
            prefix == "ntp"             -> executeNtp(name, trimmed, timeoutMs)
            prefix == "port-scan"       -> executePortScan(name, trimmed, timeoutMs)
            prefix == "checkcert"       -> executeCheckcert(name, trimmed, timeoutMs)
            prefix == "device-info"     -> executeDeviceInfo(name, trimmed)
            prefix == "tracert"         -> executeTracert(name, trimmed, timeoutMs)
            prefix == "google-timesync" -> executeGoogleTimeSync(name, trimmed, timeoutMs)
            prefix == "lan-scan"        -> executeLanScan(name, trimmed, timeoutMs)
            else                        -> executeRaw(name, trimmed, timeoutMs)
        }
    }

    // ── ping ───────────────────────────────────────────────────────

    private suspend fun executePing(name: String, cmd: String, parts: List<String>, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()

                // Parse -c count (default 4)
                val countIdx = parts.indexOf("-c")
                val count = if (countIdx >= 0 && countIdx < parts.size - 1) {
                    parts[countIdx + 1].toIntOrNull() ?: 4
                } else {
                    4
                }

                // Parse -t timeout (default 0 = no per-packet timeout)
                val timeoutIdx = parts.indexOf("-t")
                val timeout = if (timeoutIdx >= 0 && timeoutIdx < parts.size - 1) {
                    parts[timeoutIdx + 1].toIntOrNull() ?: 0
                } else {
                    0
                }

                // Host = first non-flag argument after skipping flag-value pairs (works regardless of -t position)
                val host = run {
                    var i = 1
                    val flags = setOf("-c", "-t", "-W", "-i", "-I", "-D", "-S", "-p", "-f", "-q", "-C", "-N", "-R", "-r", "-l", "-L", "-M", "-n", "-O", "-s", "-T", "-v")
                    while (i < parts.size) {
                        if (parts[i] in flags && i + 1 < parts.size) i += 2
                        else if (parts[i].startsWith("-")) i++
                        else break
                    }
                    parts.getOrNull(i) ?: parts.last()
                }

                val pingArgs = mutableListOf("ping", "-c", count.toString())
                if (timeout > 0) {
                    pingArgs.add("-W")
                    pingArgs.add(timeout.toString())
                }
                pingArgs.add(host)

                val process = Runtime.getRuntime().exec(pingArgs.toTypedArray())
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

    // ── dig ───────────────────────────────────────────────────────────────

    private suspend fun executeDig(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Parse: dig @server fqdn [-t timeout]
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

    // ── ntp ─────────────────────────────────────────────────────────────────

    private suspend fun executeNtp(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
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

    // ── port-scan (formerly nmap) ───────────────────────────────────────

    private suspend fun executePortScan(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Parse: port-scan [-p ports] [-t timeout] host  OR  port-scan ports host  (positional)
                val parts = cmd.split(Regex("\\s+"))
                val portIdx = parts.indexOfFirst { it == "-p" }
                val (portStr, hasFlag) = if (portIdx >= 0) {
                    parts.getOrNull(portIdx + 1) to true
                } else {
                    // Positional: port-scan <ports> [-t timeout] <host>
                    parts.getOrNull(1) to false
                } ?: ("22" to false)
                // Parse host: first non-flag element after ports (handles -t before or after host)
                val host = run {
                    val hostStart = if (hasFlag) portIdx + 2 else 2  // right after ports
                    var i = hostStart
                    val skipFlags = setOf("-t")
                    while (i < parts.size) {
                        if (parts[i] in skipFlags && i + 1 < parts.size) i += 2
                        else if (parts[i].startsWith("-")) i++
                        else break
                    }
                    parts.getOrNull(i)
                } ?: parts.last()

                // Parse per-command -t timeout (default 2000ms per port; config-level timeout is for coroutine, not connect)
                val tIdx = parts.indexOf("-t")
                val connectTimeout = if (tIdx >= 0 && tIdx < parts.size - 1) {
                    parts[tIdx + 1].toIntOrNull()?.times(1000) ?: 2000
                } else {
                    2000
                }

                val ports = parsePortRange(portStr ?: "22")

                // Validate host resolves before scanning
                try {
                    java.net.InetAddress.getByName(host)
                } catch (e: java.net.UnknownHostException) {
                    val duration = System.currentTimeMillis() - t0
                    val lines = buildList {
                        add("[${timestampFmt.format(java.time.LocalDateTime.now())}] $cmd")
                        add("[${timestampFmt.format(java.time.LocalDateTime.now())}] Status: HOST NOT FOUND (${duration}ms)")
                        add("    ${e.message}")
                    }
                    return@withContext BulkCommandError(name, cmd, e.message ?: "Unknown host")
                }

                val openPorts = mutableListOf<Int>()
                val mutex = Mutex()

                // Limit concurrency to prevent OutOfMemory and Too Many Open Files errors
                val concurrencyLimit = 50
                val chunks = ports.chunked(concurrencyLimit)

                for (chunk in chunks) {
                    val deferreds = chunk.map { port ->
                        async {
                            val isOpen = try {
                                val socket = Socket()
                                socket.connect(InetSocketAddress(host, port), connectTimeout)
                                socket.close()
                                true
                            } catch (_: Exception) {
                                false
                            }

                            mutex.withLock {
                                if (isOpen) {
                                    openPorts.add(port)
                                }
                            }
                        }
                    }
                    deferreds.awaitAll()
                }
                openPorts.sort()

                val duration = System.currentTimeMillis() - t0
                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    if (openPorts.isEmpty()) {
                        add("[${timestampFmt.format(LocalDateTime.now())}] Status: CLOSED (${duration}ms)")
                        add("  No open ports found.")
                     } else {
                        add("[${timestampFmt.format(LocalDateTime.now())}] Status: SUCCESS (${duration}ms)")
                        add("  Open ports: ${openPorts.joinToString(", ")}")
                     }
                 }
                if (openPorts.isEmpty()) {
                    BulkCommandClosed(name, cmd, lines, duration)
                 } else {
                    BulkCommandSuccess(name, cmd, lines, duration)
                 }
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── checkcert ────────────────────────────────────────────────────────

    private suspend fun executeCheckcert(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Parse: checkcert -p port [-t timeout] host
                val parts = cmd.split(Regex("\\s+"))
                val portIdx = parts.indexOfFirst { it == "-p" }
                val port = parts.getOrNull(portIdx + 1)?.toIntOrNull() ?: 443
                // Skip -t N if present between port and host
                val tIdx = parts.indexOf("-t")
                val host = if (tIdx > portIdx && tIdx < parts.size - 1) {
                    parts.getOrNull(tIdx + 2) ?: parts.last()
                } else {
                    parts.getOrNull(portIdx + 2) ?: parts.last()
                }

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

    // ── device-info ─────────────────────────────────────────────────────

    private suspend fun executeDeviceInfo(name: String, cmd: String): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                val repo = SystemInfoRepository(context)
                val di = repo.getDeviceInfo()
                val dur = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    add("[${timestampFmt.format(LocalDateTime.now())}] Status: SUCCESS (${dur}ms)")
                    add("  Device Name: ${di.deviceName}")
                    add("  Android Version: ${di.androidVersion}")
                    add("  API Level: ${di.apiLevel}")
                    add("  IPv4: ${di.ipv4Address ?: "N/A"}")
                    add("  IPv6: ${di.ipv6Address ?: "N/A"}")
                    add("  Subnet Mask: ${di.subnetMask ?: "N/A"}")
                    add("  Default Gateway: ${di.defaultGateway ?: "N/A"}")
                    add("  NTP Server: ${di.ntpServer ?: "N/A"}")
                    add("  DNS Servers: ${di.dnsServers?.joinToString(", ") ?: "N/A"}")
                    add("  Carrier: ${di.carrierName ?: "N/A"}")
                    add("  Wi-Fi SSID: ${di.wifiSSID ?: "N/A"}")
                    add("  Battery Level: ${di.batteryLevel ?: "N/A"}%")
                    add("  Charging: ${di.isCharging ?: "N/A"}")
                    add("  Battery Health: ${di.batteryHealth ?: "N/A"}")
                    add("  Time Since Reboot: ${di.timeSinceReboot}")
                    add("  IMEI: ${di.imei ?: "Restricted by Android 10+"}")
                    add("  Serial: ${di.serialNumber ?: "Restricted by Android 10+"}")
                    add("  ICCID: ${di.iccid ?: "Restricted by Android 10+"}")
                    add("  Total RAM: ${di.totalRam ?: "N/A"}")
                    add("  Available RAM: ${di.availableRam ?: "N/A"}")
                    add("  Total Storage: ${di.totalStorage ?: "N/A"}")
                    add("  Available Storage: ${di.availableStorage ?: "N/A"}")
                    add("  CPU ABI: ${di.cpuAbi?.joinToString(", ") ?: "N/A"}")
                    add("  Active Network: ${di.activeNetworkType ?: "N/A"}")
                }
                BulkCommandSuccess(name, cmd, lines, dur)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── tracert ─────────────────────────────────────────────────────────

    private suspend fun executeTracert(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val parts = cmd.split(Regex("\\s+"))
                val host = parts.getOrNull(1)
                    ?: return@withContext BulkCommandError(name, cmd, "Usage: tracert <host>")

                // Parse per-command -t as max hops (default 30)
                val tIdx = parts.indexOf("-t")
                val maxHops = if (tIdx >= 0 && tIdx < parts.size - 1) {
                    parts[tIdx + 1].toIntOrNull()?.takeIf { it > 0 } ?: 30
                } else {
                    timeoutMs?.toInt()?.takeIf { it > 0 } ?: 30
                }

                val t0 = System.currentTimeMillis()
                val out = mutableListOf<String>()
                var dstReached = false
                var hops = 0

                out.add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                out.add("traceroute to $host, $maxHops hops max")

                val ttlIpRegex = Regex("""From (\S+).*[Tt]ime to live exceeded""")
                val dstReplyRegex = Regex("""bytes from (\S+):""")
                val ttlAltRegex = Regex("""From (\S+):.*ttl""", RegexOption.IGNORE_CASE)

                for (ttl in 1..maxHops) {
                    val num = ttl.toString().padStart(2)
                    try {
                        val proc = Runtime.getRuntime().exec(
                            arrayOf("ping", "-c", "1", "-t", ttl.toString(), "-W", "2", host)
                        )
                        val stdout = proc.inputStream.bufferedReader().readText()
                        val stderr = proc.errorStream.bufferedReader().readText()
                        proc.waitFor()
                        val elapsed = System.currentTimeMillis() - t0
                        val combined = stdout + "\n" + stderr

                        when {
                            ttlIpRegex.containsMatchIn(combined) -> {
                                val ip = ttlIpRegex.find(combined)!!.groupValues[1].trimEnd(':')
                                out.add("$num  $ip  ${elapsed} ms")
                                hops++
                            }
                            dstReplyRegex.containsMatchIn(combined) -> {
                                val ip = dstReplyRegex.find(combined)!!.groupValues[1].trimEnd(':')
                                val rtt = Regex("""time[=<]([\d.]+) ms""").find(combined)
                                    ?.groupValues?.get(1)?.let { "$it ms" } ?: "${elapsed} ms"
                                out.add("$num  $ip  $rtt")
                                hops++
                                dstReached = true
                                break
                            }
                            ttlAltRegex.containsMatchIn(combined) -> {
                                val ip = ttlAltRegex.find(combined)!!.groupValues[1].trimEnd(':')
                                out.add("$num  $ip  ${elapsed} ms")
                                hops++
                            }
                            else -> out.add("$num  * * *")
                        }
                    } catch (_: Exception) {
                        out.add("$num  * * *")
                    }
                }

                val dur = System.currentTimeMillis() - t0
                out.add("[${timestampFmt.format(LocalDateTime.now())}] Status: COMPLETE (${dur}ms)")
                out.add("  Reachable hops: $hops, Destination reached: $dstReached")
                BulkCommandSuccess(name, cmd, out, dur)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── google-timesync ─────────────────────────────────────────────────

    private suspend fun executeGoogleTimeSync(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                val repo = GoogleTimeSyncRepository()
                val result = repo.fetchGoogleTime()
                val dur = System.currentTimeMillis() - t0

                val lines = buildList {
                    add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                    when (result) {
                        is GoogleTimeSyncResult.Success -> {
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: SUCCESS (${dur}ms)")
                            add("  Server Time:  ${java.util.Date(result.data.serverTimeMillis)}")
                            add("  RTT:          ${result.data.rttMillis} ms")
                            add("  Offset:       ${result.data.offsetMillis} ms")
                            add("  Corrected:    ${java.util.Date(result.data.correctedServerTimeMillis)}")
                        }
                        is GoogleTimeSyncResult.NoNetwork ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: NO NETWORK (${dur}ms)")
                        is GoogleTimeSyncResult.Timeout ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: TIMEOUT (${dur}ms)")
                        is GoogleTimeSyncResult.HttpError ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: HTTP ${result.code} (${dur}ms)")
                        is GoogleTimeSyncResult.ParseError ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: PARSE ERROR - ${result.message} (${dur}ms)")
                        is GoogleTimeSyncResult.Error ->
                            add("[${timestampFmt.format(LocalDateTime.now())}] Status: ERROR - ${result.message} (${dur}ms)")
                    }
                }
                BulkCommandSuccess(name, cmd, lines, dur)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── lan-scan ────────────────────────────────────────────────────────

    private suspend fun executeLanScan(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                val repo = LanScannerRepository(context)
                val subnet = repo.getLocalSubnetInfo()
                    ?: return@withContext BulkCommandError(name, cmd, "No active WiFi network found")

                val out = mutableListOf<String>()
                out.add("[${timestampFmt.format(LocalDateTime.now())}] $cmd")
                out.add("[${timestampFmt.format(LocalDateTime.now())}] Subnet: ${subnet.cidr}")
                out.add("[${timestampFmt.format(LocalDateTime.now())}] Scanning up to 256 hosts…")

                val devices = mutableListOf<String>()
                val limit = minOf(subnet.numHosts, 256L).toInt()
                for (i in 0 until limit) {
                    val ip = repo.longToIp(subnet.baseIp + i.toLong() + 1)
                    val rtt = repo.ping(ip)
                    if (rtt == null) continue
                    val mac = repo.getMacFromArpTable(ip)
                    val host = repo.resolveHostname(ip)
                    val isRouter = (i == 0)

                    val info = buildString {
                        append(ip)
                        mac?.let { append(" MAC:$it") }
                        host?.let { append(" Host:$it") }
                        append(" RTT:${rtt}ms")
                        if (isRouter) append(" [ROUTER]")
                    }
                    devices.add(info)
                }

                val dur = System.currentTimeMillis() - t0
                out.add("[${timestampFmt.format(LocalDateTime.now())}] Status: COMPLETE (${dur}ms)")
                out.add("  Devices found: ${devices.size}")
                devices.forEach { out.add("  $it") }
                BulkCommandSuccess(name, cmd, out, dur)
            } catch (e: Exception) {
                BulkCommandError(name, cmd, e.message ?: "Unknown error")
            }
        }
    }

    // ── raw ────────────────────────────────────────────────────────────────

    private suspend fun executeRaw(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
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
