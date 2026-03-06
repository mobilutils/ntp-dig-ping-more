package io.github.mobilutils.ntp_dig_ping_more

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import org.apache.commons.net.ntp.TimeInfo
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale

/**
 * Sealed hierarchy representing all possible outcomes of an NTP check.
 */
sealed class NtpResult {
    /**
     * A successful NTP exchange.
     *
     * @param serverTime   Server transmit timestamp formatted as a human-readable string.
     * @param offsetMs     Clock offset between local and NTP server in milliseconds.
     * @param delayMs      Round-trip delay in milliseconds.
     */
    data class Success(
        val serverTime: String,
        val offsetMs: Long,
        val delayMs: Long,
    ) : NtpResult()

    /**
     * The host name could not be resolved.
     */
    data class DnsFailure(val host: String) : NtpResult()

    /**
     * The connection attempt timed out.
     */
    data class Timeout(val host: String) : NtpResult()

    /**
     * The device has no usable network interface.
     */
    object NoNetwork : NtpResult()

    /**
     * Any other I/O or unexpected error.
     */
    data class Error(val message: String) : NtpResult()
}

/**
 * Repository encapsulating all NTP network I/O.
 *
 * All public functions are safe to call from a coroutine that runs on
 * [Dispatchers.IO]; they do NOT switch the dispatcher themselves so the
 * caller remains in control.
 */
class NtpRepository {

    companion object {
        /** NTP default port (RFC 5905). */
        private const val NTP_PORT = 123

        /** Socket read / connect timeout. */
        private val TIMEOUT = Duration.ofSeconds(5)

        /** Human-readable date/time format for the server time display. */
        private const val DATE_PATTERN = "yyyy-MM-dd  HH:mm:ss z"
    }

    /**
     * Queries [host] for NTP information and returns an [NtpResult].
     *
     * Must be called from a coroutine context that allows blocking I/O
     * (e.g. [Dispatchers.IO]).
     */
    suspend fun query(host: String, port: Int = NTP_PORT): NtpResult = withContext(Dispatchers.IO) {
        val client = NTPUDPClient()
        client.setDefaultTimeout(TIMEOUT)   // Duration overload – not deprecated
        try {
            client.open()

            val address: InetAddress = try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                return@withContext NtpResult.DnsFailure(host)
            }

            val info: TimeInfo = try {
                client.getTime(address, port)
            } catch (e: SocketException) {
                // A SocketException with "Network is unreachable" means no connectivity.
                val msg = e.message ?: ""
                return@withContext if (msg.contains("unreachable", ignoreCase = true) ||
                    msg.contains("connect failed", ignoreCase = true)
                ) {
                    NtpResult.NoNetwork
                } else {
                    NtpResult.Timeout(host)   // Most other socket errors are effectively timeouts.
                }
            } catch (e: java.net.SocketTimeoutException) {
                return@withContext NtpResult.Timeout(host)
            }

            // computeDetails() populates offset and delay from the four NTP timestamps.
            info.computeDetails()

            val offset: Long = info.offset
            val delay: Long  = info.delay

            val transmitTime: Long =
                info.message.transmitTimeStamp?.time ?: System.currentTimeMillis()
            val serverTime = SimpleDateFormat(DATE_PATTERN, Locale.getDefault())
                .format(Date(transmitTime))

            NtpResult.Success(
                serverTime = serverTime,
                offsetMs   = offset,
                delayMs    = delay,
            )
        } catch (e: Exception) {
            NtpResult.Error(e.localizedMessage ?: "Unknown error")
        } finally {
            client.close()
        }
    }
}
