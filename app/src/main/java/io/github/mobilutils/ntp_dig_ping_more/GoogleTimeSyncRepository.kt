package io.github.mobilutils.ntp_dig_ping_more

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// Domain model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Holds all time-sync data computed from a single successful exchange
 * with the Google time endpoint.
 *
 * @param serverTimeMillis          Raw `current_time_millis` from the JSON response (UTC epoch ms).
 * @param rttMillis                 Round-trip time: T4 − T1 (ms).
 * @param offsetMillis              correctedServerTime − T4 (ms).
 *                                  Positive → local clock is behind the server.
 *                                  Negative → local clock is ahead.
 * @param correctedServerTimeMillis serverTime + RTT / 2 (ms).
 * @param requestTimestamp          T1: client timestamp just before the request (ms).
 * @param responseTimestamp         T4: client timestamp when the response was received (ms).
 */
data class TimeSyncResult(
    val serverTimeMillis: Long,
    val rttMillis: Long,
    val offsetMillis: Long,
    val correctedServerTimeMillis: Long,
    val requestTimestamp: Long,
    val responseTimestamp: Long,
)

// ─────────────────────────────────────────────────────────────────────────────
// Result sealed hierarchy
// ─────────────────────────────────────────────────────────────────────────────

sealed class GoogleTimeSyncResult {
    data class Success(val data: TimeSyncResult) : GoogleTimeSyncResult()
    data object NoNetwork : GoogleTimeSyncResult()
    data class Timeout(val host: String) : GoogleTimeSyncResult()
    data class HttpError(val code: Int, val host: String) : GoogleTimeSyncResult()
    data class ParseError(val message: String) : GoogleTimeSyncResult()
    data class Error(val message: String) : GoogleTimeSyncResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// Repository
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches the current UTC time from Google's time-sync endpoint and computes
 * the clock offset between the local device and the server.
 *
 * Endpoint: `GET http://<host>/time/1/current`
 * The response is prefixed with `)]}'` (XSSI protection) which is stripped
 * before JSON parsing.
 */
class GoogleTimeSyncRepository {

    companion object {
        /** Default endpoint – exposed so the ViewModel and Screen can reference
         *  the same value without duplication. */
        const val DEFAULT_URL        = "http://clients2.google.com/time/1/current"
        private const val CONNECT_TIMEOUT_MS = 10_000  // 10 s
        private const val READ_TIMEOUT_MS    = 15_000  // 15 s

        /** Number of characters in the XSSI prefix: )]}'  */
        private const val XSSI_PREFIX_LEN   = 4
    }

    /**
     * Performs a synchronous HTTP GET on the IO dispatcher.
     * Safe to call from any coroutine scope.
     *
     * @param url  Full URL to query (default: [DEFAULT_URL]).
     */
    suspend fun fetchGoogleTime(
        url: String = DEFAULT_URL,
    ): GoogleTimeSyncResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            // T1: record timestamp BEFORE the request goes out.
            val t1 = System.currentTimeMillis()

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod        = "GET"
                connectTimeout       = CONNECT_TIMEOUT_MS
                readTimeout          = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext GoogleTimeSyncResult.HttpError(responseCode, url)
            }

            val rawBody = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()

            // T4: record timestamp AFTER the response body is fully read.
            val t4 = System.currentTimeMillis()

            // ── Strip XSSI prefix ────────────────────────────────────────
            if (rawBody.length < XSSI_PREFIX_LEN) {
                return@withContext GoogleTimeSyncResult.ParseError(
                    "Response body too short to contain XSSI prefix (got ${rawBody.length} chars)"
                )
            }
            val jsonStr = rawBody.substring(XSSI_PREFIX_LEN).trim()

            // ── Parse JSON ───────────────────────────────────────────────
            val json = try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                return@withContext GoogleTimeSyncResult.ParseError(
                    "Malformed JSON: ${e.localizedMessage}"
                )
            }

            if (!json.has("current_time_millis")) {
                return@withContext GoogleTimeSyncResult.ParseError(
                    "Missing field: current_time_millis"
                )
            }
            val serverTimeMillis = json.getLong("current_time_millis")

            // ── Time-sync calculation ────────────────────────────────────
            // RTT = T4 − T1
            val rtt = t4 - t1
            // correctedServerTime = serverTime + RTT / 2
            val correctedServerTime = serverTimeMillis + (rtt / 2L)
            // offset = correctedServerTime − T4
            // Positive  → local clock is behind
            // Negative  → local clock is ahead
            val offset = correctedServerTime - t4

            GoogleTimeSyncResult.Success(
                TimeSyncResult(
                    serverTimeMillis          = serverTimeMillis,
                    rttMillis                 = rtt,
                    offsetMillis              = offset,
                    correctedServerTimeMillis = correctedServerTime,
                    requestTimestamp          = t1,
                    responseTimestamp         = t4,
                )
            )

        } catch (e: SocketTimeoutException) {
            GoogleTimeSyncResult.Timeout(url)

        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            if (msg.contains("unreachable", ignoreCase = true) ||
                msg.contains("connect failed", ignoreCase = true) ||
                msg.contains("network", ignoreCase = true)
            ) {
                GoogleTimeSyncResult.NoNetwork
            } else {
                GoogleTimeSyncResult.Error(e.localizedMessage ?: "Network error")
            }

        } catch (e: Exception) {
            GoogleTimeSyncResult.Error(e.localizedMessage ?: "Unknown error")

        } finally {
            connection?.disconnect()
        }
    }
}
