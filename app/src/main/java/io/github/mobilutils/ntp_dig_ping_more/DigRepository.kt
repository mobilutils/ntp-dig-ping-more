package io.github.mobilutils.ntp_dig_ping_more

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.UnknownHostException
import java.time.Duration

// ─────────────────────────────────────────────────────────────────────────────
// Result sealed hierarchy
// ─────────────────────────────────────────────────────────────────────────────

sealed class DigResult {
    /**
     * Successful DNS response.
     *
     * @param questionSection  The formatted question line, e.g. `"pool.ntp.org.  IN  A"`
     * @param records          Answer-section records formatted like real dig output,
     *                         e.g. `"pool.ntp.org.  300  IN  A  1.2.3.4"`
     * @param dnsServer        Server that was queried.
     */
    data class Success(
        val questionSection: String,
        val records: List<String>,
        val dnsServer: String,
    ) : DigResult()

    /** The FQDN does not exist (NXDOMAIN / empty answer). */
    data class NxDomain(val fqdn: String) : DigResult()

    /** The DNS server itself could not be reached. */
    data class DnsServerError(val detail: String) : DigResult()

    /** Device has no network. */
    object NoNetwork : DigResult()

    /** Any other failure. */
    data class Error(val message: String) : DigResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// Repository
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Performs a DNS A query for [fqdn] against [dnsServerHost], returning full
 * dig-style answer-section records (name, TTL, class, type, rdata).
 *
 * Uses **dnsjava** which works on all API levels and sends the query directly
 * to the specified server — no OS resolver involved.
 */
class DigRepository {

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }

    suspend fun resolve(dnsServerHost: String, fqdn: String): DigResult =
        withContext(Dispatchers.IO) {
            val server = dnsServerHost.ifBlank { "8.8.8.8" }
            try {
                val resolver = SimpleResolver(server)
                resolver.setTimeout(TIMEOUT)

                // Ensure the FQDN is absolute (trailing dot)
                val name: Name = try {
                    val raw = if (fqdn.endsWith(".")) fqdn else "$fqdn."
                    Name.fromString(raw)
                } catch (e: Exception) {
                    return@withContext DigResult.Error("Invalid FQDN: $fqdn")
                }

                // Query A records (CNAME chain is included automatically)
                val questionRecord = Record.newRecord(name, Type.A, DClass.IN)
                val query = Message.newQuery(questionRecord)
                val response: Message = resolver.send(query)

                val answerRecords = response.getSection(Section.ANSWER)

                // Build question-section string
                val questionSection = ";${name}    IN    A"

                if (answerRecords.isNullOrEmpty()) {
                    // Also try AAAA before declaring NXDOMAIN
                    val query6 = Message.newQuery(Record.newRecord(name, Type.AAAA, DClass.IN))
                    val response6: Message = resolver.send(query6)
                    val aaaa = response6.getSection(Section.ANSWER)
                    if (aaaa.isNullOrEmpty()) {
                        return@withContext DigResult.NxDomain(fqdn)
                    }
                    return@withContext DigResult.Success(
                        questionSection = ";${name}    IN    AAAA",
                        records = formatRecords(aaaa),
                        dnsServer = server,
                    )
                }

                // Also append AAAA records if present
                val query6 = Message.newQuery(Record.newRecord(name, Type.AAAA, DClass.IN))
                val response6: Message = resolver.send(query6)
                val aaaaRecords = response6.getSection(Section.ANSWER) ?: emptyList()

                val allRecords = (answerRecords + aaaaRecords)
                    .distinctBy { it.toString() }

                DigResult.Success(
                    questionSection = questionSection,
                    records = formatRecords(allRecords),
                    dnsServer = server,
                )

            } catch (e: UnknownHostException) {
                // The DNS *server* host itself can't be resolved
                DigResult.DnsServerError(
                    "Cannot reach DNS server \"$dnsServerHost\": ${e.localizedMessage}"
                )
            } catch (e: java.net.SocketTimeoutException) {
                DigResult.DnsServerError(
                    "DNS server \"$dnsServerHost\" did not respond within ${TIMEOUT.seconds}s"
                )
            } catch (e: java.net.SocketException) {
                val msg = e.message ?: ""
                if (msg.contains("unreachable", ignoreCase = true) ||
                    msg.contains("connect failed", ignoreCase = true)
                ) {
                    DigResult.NoNetwork
                } else {
                    DigResult.DnsServerError("Socket error: $msg")
                }
            } catch (e: Exception) {
                DigResult.Error(e.localizedMessage ?: e.toString())
            }
        }

    // ── Formatting ────────────────────────────────────────────────────────────

    /**
     * Formats a list of dnsjava [Record]s as dig-style answer lines with
     * proper column alignment:
     *
     *   name (padded)   ttl (right-aligned)   IN   type (padded)   rdata
     *
     * The name column width is derived from the longest name in [records].
     */
    private fun formatRecords(records: List<Record>): List<String> {
        if (records.isEmpty()) return emptyList()

        // Compute column widths from the actual data
        val nameWidth = records.maxOf { it.name.toString().length }.coerceAtLeast(20)
        val ttlWidth  = records.maxOf { it.ttl.toString().length }.coerceAtLeast(5)
        val typeWidth = records.maxOf { Type.string(it.type).length }.coerceAtLeast(5)

        return records.map { record ->
            val name  = record.name.toString().padEnd(nameWidth)
            val ttl   = record.ttl.toString().padStart(ttlWidth)
            val type  = Type.string(record.type).padEnd(typeWidth)
            val rdata = record.rdataToString()
            "$name  $ttl  IN  $type  $rdata"
        }
    }
}
