package io.github.mobilutils.ntp_dig_ping_more.proxy

import io.github.mobilutils.ntp_dig_ping_more.settings.ProxyConfig
import io.github.mobilutils.ntp_dig_ping_more.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// Test result
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result of a proxy connectivity test.
 *
 * @param success  True if the test request completed with HTTP 204 (or 200).
 * @param message  Human-readable description of the outcome.
 * @param latencyMs Round-trip time in milliseconds (0 on failure).
 */
data class ProxyTestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long = 0L,
)

// ─────────────────────────────────────────────────────────────────────────────
// Proxy resolver
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves a [java.net.Proxy] for a given target URL by fetching and
 * evaluating a PAC script from the user's configured PAC URL.
 *
 * **Caching:**
 *  - The PAC script body is cached for [PAC_CACHE_TTL_MS] (5 minutes).
 *  - Resolved proxy results are cached per host for the same TTL.
 *
 * **Failure handling:** Any failure (network, JS eval, parse) silently
 * returns `null`, meaning the caller should use a DIRECT connection.
 *
 * @param settingsRepository  Source of the persisted [ProxyConfig].
 * @param jsEngine            JS engine used to evaluate `FindProxyForURL`.
 * @param staticPacUrl        Optional PAC URL override. When non-null, the resolver
 *                            uses this URL directly instead of reading from
 *                            [settingsRepository]. Used by BulkActions for
 *                            config-level `url-proxypac` without touching persisted settings.
 * @param logger              Optional [ProxyPacLogger] for high-level event logging.
 *                            When `null`, no logging occurs regardless of [forceLogging].
 * @param forceLogging        When `true`, logging is enabled even if [ProxyPacLogger.enabled]
 *                            is `false`. Used by BulkActions when `"log-proxy": true` is set
 *                            in the JSON config.
 */
class ProxyResolver(
    private val settingsRepository: SettingsRepository,
    private val jsEngine: JsEngine,
    private val staticPacUrl: String? = null,
    private val logger: ProxyPacLogger? = null,
    private val forceLogging: Boolean = false,
) {

    companion object {
        /** Cache TTL for both the PAC script body and per-host resolutions. */
        private const val PAC_CACHE_TTL_MS = 5 * 60 * 1000L   // 5 minutes

        /** Connectivity-check URL used by [testProxy]. */
        private const val TEST_URL = "http://connectivitycheck.gstatic.com/generate_204"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 10_000

        /**
         * Creates a [ProxyResolver] that uses a static PAC URL directly,
         * without reading from or writing to [SettingsRepository].
         *
         * Intended for BulkActions where the PAC URL comes from the JSON config
         * and should not affect the user's persisted proxy settings.
         *
         * @param pacUrl        PAC script URL (e.g. `http://proxy.corp.com/proxy.pac`).
         * @param context       Application context (needed by [SettingsRepository] internally).
         * @param jsEngine      JS engine for PAC evaluation (default: [QuickJsEngine]).
         * @param logger        Optional logger for PAC events.
         * @param forceLogging  When `true`, logging is enabled regardless of [ProxyPacLogger.enabled].
         */
        fun forStaticPacUrl(
            pacUrl: String,
            context: android.content.Context,
            jsEngine: JsEngine = QuickJsEngine(),
            logger: ProxyPacLogger? = null,
            forceLogging: Boolean = false,
        ): ProxyResolver = ProxyResolver(
            settingsRepository = SettingsRepository(context),
            jsEngine = jsEngine,
            staticPacUrl = pacUrl,
            logger = logger,
            forceLogging = forceLogging,
        )
    }

    // ── PAC script cache ─────────────────────────────────────────────────────

    @Volatile private var cachedPacScript: String? = null
    @Volatile private var cachedPacUrl: String? = null
    @Volatile private var pacFetchedAt: Long = 0L

    // ── Per-host proxy cache ─────────────────────────────────────────────────

    private data class CachedProxy(val proxy: Proxy?, val resolvedAt: Long)

    private val proxyCache = mutableMapOf<String, CachedProxy>()

    // ── Logging helper ───────────────────────────────────────────────────────

    /**
     * Logs [message] if logging is active. Logging is active when [logger] is
     * non-null AND either [ProxyPacLogger.enabled] or [forceLogging] is `true`.
     *
     * This is strictly fire-and-forget — it never suspends or blocks.
     */
    private fun logIfEnabled(message: String) {
        val log = logger ?: return
        if (log.enabled || forceLogging) {
            log.log(message, force = forceLogging)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Resolves the proxy to use for [targetUrl].
     *
     * @return A [Proxy] instance, or `null` if the connection should be DIRECT
     *         (proxy disabled, resolution failure, or PAC says DIRECT).
     */
    suspend fun resolveProxy(targetUrl: String): Proxy? = withContext(Dispatchers.IO) {
        try {
            // Determine the effective PAC URL: static override or persisted config
            val effectivePacUrl = if (staticPacUrl != null) {
                staticPacUrl
            } else {
                val config = settingsRepository.proxyConfigFlow.first()
                if (!config.enabled || config.pacUrl.isBlank()) return@withContext null
                config.pacUrl
            }

            val host = extractHost(targetUrl) ?: return@withContext null

            // Check per-host cache
            val now = System.currentTimeMillis()
            synchronized(proxyCache) {
                proxyCache[host]?.let { cached ->
                    if (now - cached.resolvedAt < PAC_CACHE_TTL_MS) {
                        return@withContext cached.proxy
                    }
                }
            }

            // Fetch (or use cached) PAC script
            val pacScript = fetchPacScript(effectivePacUrl) ?: return@withContext null

            // Evaluate FindProxyForURL
            val pacResult = try {
                jsEngine.evaluatePac(pacScript, targetUrl, host)
            } catch (e: Exception) {
                logIfEnabled("PROXY_ERROR step=evaluatePac reason=${e.message}")
                return@withContext null
            }

            // Parse PAC result into a Proxy
            val proxy = parsePacResult(pacResult)
            val resultLabel = if (proxy != null) proxy.address().toString() else "DIRECT"
            logIfEnabled("PROXY_RESOLVED host=$host result=$resultLabel")

            // Cache the result
            synchronized(proxyCache) {
                proxyCache[host] = CachedProxy(proxy, now)
            }

            proxy
        } catch (e: Exception) {
            logIfEnabled("PROXY_ERROR step=resolveProxy reason=${e.message}")
            null
        }
    }

    /**
     * Performs a lightweight connectivity test through the resolved proxy.
     *
     * Sends a HEAD request to Google's connectivity-check endpoint
     * ([TEST_URL]) and reports success/failure with latency.
     */
    suspend fun testProxy(): ProxyTestResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val proxy = resolveProxy(TEST_URL)
            val startMs = System.currentTimeMillis()

            connection = if (proxy != null) {
                URL(TEST_URL).openConnection(proxy) as HttpURLConnection
            } else {
                URL(TEST_URL).openConnection() as HttpURLConnection
            }

            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            val code = connection.responseCode
            val latencyMs = System.currentTimeMillis() - startMs

            if (code in 200..299) {
                val via = if (proxy != null) "via ${proxy.address()}" else "DIRECT"
                logIfEnabled("PROXY_TEST result=SUCCESS latency=${latencyMs}ms via=$via")
                ProxyTestResult(
                    success   = true,
                    message   = "✓ HTTP $code $via (${latencyMs}ms)",
                    latencyMs = latencyMs,
                )
            } else {
                logIfEnabled("PROXY_TEST result=FAIL code=$code latency=${latencyMs}ms")
                ProxyTestResult(
                    success = false,
                    message = "✗ HTTP $code (${latencyMs}ms)",
                    latencyMs = latencyMs,
                )
            }
        } catch (e: Exception) {
            logIfEnabled("PROXY_ERROR step=testProxy reason=${e.message}")
            ProxyTestResult(
                success = false,
                message = "✗ ${e.localizedMessage ?: "Connection failed"}",
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Clears all cached PAC scripts and per-host proxy resolutions.
     * Call this when the user changes the PAC URL.
     */
    fun clearCache() {
        cachedPacScript = null
        cachedPacUrl = null
        pacFetchedAt = 0L
        synchronized(proxyCache) { proxyCache.clear() }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Fetches the PAC script from [pacUrl], using a 5-minute in-memory cache.
     */
    private fun fetchPacScript(pacUrl: String): String? {
        val now = System.currentTimeMillis()
        if (cachedPacScript != null &&
            cachedPacUrl == pacUrl &&
            now - pacFetchedAt < PAC_CACHE_TTL_MS
        ) {
            return cachedPacScript
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(pacUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logIfEnabled("PAC_FETCH_FAIL url=$pacUrl reason=HTTP_${connection.responseCode}")
                return null
            }

            val script = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            cachedPacScript = script
            cachedPacUrl = pacUrl
            pacFetchedAt = now
            logIfEnabled("PAC_FETCH_SUCCESS url=$pacUrl")
            script
        } catch (e: Exception) {
            logIfEnabled("PAC_FETCH_FAIL url=$pacUrl reason=${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts the hostname from a URL string.
     */
    private fun extractHost(url: String): String? = try {
        URL(url).host.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        // Try simple extraction for non-URL strings (e.g. bare hostnames)
        url.removePrefix("https://").removePrefix("http://")
            .substringBefore("/").substringBefore(":").takeIf { it.isNotBlank() }
    }

    // ── PAC result parsing ───────────────────────────────────────────────────

    /**
     * Parses a PAC result string into a [Proxy].
     *
     * Supports:
     *  - `"DIRECT"` → returns `null`
     *  - `"PROXY host:port"` → returns [Proxy.Type.HTTP]
     *  - `"SOCKS host:port"` → returns [Proxy.Type.SOCKS]
     *  - Fallback chains separated by `;` — uses the first valid entry
     *
     * @return A [Proxy] or `null` (meaning DIRECT).
     */
    internal fun parsePacResult(pacResult: String): Proxy? {
        val entries = pacResult.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        for (entry in entries) {
            val upper = entry.uppercase()
            when {
                upper == "DIRECT" -> return null

                upper.startsWith("PROXY ") -> {
                    val address = entry.substring(6).trim()
                    val proxy = parseAddress(address, Proxy.Type.HTTP)
                    if (proxy != null) return proxy
                    // Malformed — try next entry in fallback chain
                }

                upper.startsWith("SOCKS ") || upper.startsWith("SOCKS5 ") || upper.startsWith("SOCKS4 ") -> {
                    val spaceIdx = entry.indexOf(' ')
                    val address = entry.substring(spaceIdx + 1).trim()
                    val proxy = parseAddress(address, Proxy.Type.SOCKS)
                    if (proxy != null) return proxy
                }
            }
        }
        // No valid entry found — fall back to DIRECT
        return null
    }

    /**
     * Parses `"host:port"` into an [InetSocketAddress]-backed [Proxy].
     */
    private fun parseAddress(address: String, type: Proxy.Type): Proxy? {
        val parts = address.split(":")
        if (parts.size != 2) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Proxy(type, InetSocketAddress.createUnresolved(host, port))
    }
}
