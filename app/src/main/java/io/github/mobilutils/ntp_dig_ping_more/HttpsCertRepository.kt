package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

// ─────────────────────────────────────────────────────────────────────────────
// Domain models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Parsed fields from an X.509 Distinguished Name.
 */
data class DistinguishedName(
    val cn: String?,
    val o:  String?,
    val ou: String?,
    val c:  String?,
)

/**
 * A single Subject Alternative Name entry.
 *
 * @param type  "DNS" or "IP"
 * @param value The hostname or IP address string.
 */
data class SanEntry(val type: String, val value: String)

/** Validity status derived from the certificate's expiry date. */
enum class CertValidityStatus {
    /** Certificate is valid and expires more than 30 days from now. */
    VALID,
    /** Certificate is valid but expires within the next 30 days. */
    EXPIRING_SOON,
    /** Certificate has already expired. */
    EXPIRED,
}

/**
 * All metadata extracted from a leaf X.509 TLS certificate.
 *
 * All date strings are ISO-8601 formatted in UTC.
 */
data class CertificateInfo(
    val host: String,
    val port: Int,
    val subject: DistinguishedName,
    val issuer:  DistinguishedName,
    val notBefore:          String,
    val notAfter:           String,
    val validityStatus:     CertValidityStatus,
    val daysUntilExpiry:    Long,    // negative = already expired
    val serialNumber:       String,  // hexadecimal
    val sha256Fingerprint:  String,  // colon-separated upper-case hex
    val sha1Fingerprint:    String,  // colon-separated upper-case hex
    val subjectAltNames:    List<SanEntry>,
    val keyAlgorithm:       String,  // e.g. "RSA"
    val keySize:            Int,     // e.g. 2048; -1 if unknown
    val version:            Int,     // X.509 certificate version (1, 2, or 3)
    val signatureAlgorithm: String,  // e.g. "SHA256withRSA"
    val chainDepth:         Int,     // number of certs in chain (1 = leaf only)
)

// ─────────────────────────────────────────────────────────────────────────────
// Result sealed hierarchy
// ─────────────────────────────────────────────────────────────────────────────

sealed class HttpsCertResult {
    /** TLS handshake succeeded and the cert chain is fully trusted. */
    data class Success(val info: CertificateInfo) : HttpsCertResult()

    /** No active network connection. */
    data object NoNetwork : HttpsCertResult()

    /** The hostname could not be resolved. */
    data class HostnameUnresolved(val host: String) : HttpsCertResult()

    /** Connection or handshake timed out. */
    data class Timeout(val host: String) : HttpsCertResult()

    /**
     * The leaf certificate is expired. The cert data is still extracted
     * and exposed so the UI can display it with a warning banner.
     */
    data class CertExpired(val info: CertificateInfo) : HttpsCertResult()

    /**
      * The certificate chain is not trusted by the system (e.g. untrusted root,
      * unknown CA). The full certificate data is always extracted and exposed
      * so the UI can display it with a warning.
      */
    data class UntrustedChain(val info: CertificateInfo, val reason: String) : HttpsCertResult()

     /** Any other error during the handshake or parsing phase. */
    data class Error(val message: String) : HttpsCertResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// Recording TrustManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A [X509TrustManager] that wraps the system default PKIX trust manager.
 *
 * The full chain is recorded **before** calling the system trust manager,
 * so untrusted or expired chains are still captured for inspection.
 * The system manager's [checkServerTrusted] still enforces PKIX rules —
 * if validation fails the exception propagates but the chain is already recorded.
 */
private class RecordingTrustManager(private val systemTm: X509TrustManager) : X509TrustManager {

    /** Populated on every [checkServerTrusted] call, regardless of validation outcome. */
    var chain: Array<out X509Certificate>? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // Record BEFORE validation so untrusted/expired chains are still captured.
        this.chain = chain
        systemTm.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        systemTm.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        systemTm.acceptedIssuers
}

// ─────────────────────────────────────────────────────────────────────────────
// Repository
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Connects to an HTTPS endpoint, performs a TLS handshake, and extracts
 * metadata from the peer's leaf certificate.
 *
 * TLS validation follows the standard Android PKIX chain — no security
 * shortcuts are taken. Expired and self-signed certificates are detected
 * by catching the specific exceptions and mapped to dedicated result types
 * so the UI can still display the parsed certificate fields.
 */
class HttpsCertRepository(
    private val proxyResolver: ProxyResolver? = null,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 10_000

        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val EXPIRY_WARN_DAYS = 30L
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches and parses the TLS certificate for [host]:[port].
     * Must be called from a coroutine; network I/O runs on [Dispatchers.IO].
     */
    suspend fun fetchCertificate(
        host: String,
        port: Int = 443,
    ): HttpsCertResult = withContext(Dispatchers.IO) {

        // ── Build the recording SSLContext ────────────────────────────────
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as java.security.KeyStore?) // use system key store
        val systemTm = systemTmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: return@withContext HttpsCertResult.Error("No system X509TrustManager found")

        val recorder = RecordingTrustManager(systemTm)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(recorder), null)

        var socket: SSLSocket? = null

        try {
            // ── Establish connection (direct or proxied) ─────────────────
            val proxy = proxyResolver?.resolveProxy("https://$host:$port")

            socket = if (proxy != null && proxy.type() == java.net.Proxy.Type.HTTP) {
                // HTTP CONNECT tunneling for proxied SSL
                createProxiedSSLSocket(
                    targetHost  = host,
                    targetPort  = port,
                    proxy       = proxy,
                    sslFactory  = sslContext.socketFactory,
                )
            } else {
                // Direct connection (no proxy, or SOCKS proxy handled by Socket)
                (sslContext.socketFactory.createSocket() as SSLSocket).also { s ->
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    s.soTimeout = READ_TIMEOUT_MS
                    s.useClientMode = true
                }
            }

            // ── Attempt handshake ─────────────────────────────────────────
            // Will throw SSLHandshakeException if chain is untrusted/expired.
            socket.startHandshake()

            // ── Parse the trusted chain ───────────────────────────────────
            val chain = recorder.chain
                ?: socket.session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray()
            if (chain.isEmpty()) return@withContext HttpsCertResult.Error("No certificate in chain")

            val info = parseCertificate(host, port, chain)
            HttpsCertResult.Success(info)

        } catch (e: CertificateExpiredException) {
            // Chain was recorded before PKIX expiry check — always available.
            val info = parseCertificate(host, port, recorder.chain!!)
            HttpsCertResult.CertExpired(info)

        } catch (e: SSLHandshakeException) {
            // Could be self-signed, wrong host, or other trust failure.
            // Chain was recorded before PKIX validation — always available.
            val info = parseCertificate(host, port, recorder.chain!!)
            val reason = e.localizedMessage ?: "TLS handshake failed"

            // Distinguish expired specifically (PKIX embeds it in the handshake ex)
            val causeIsExpiry = generateSequence(e.cause) { it.cause }
                .any { it is CertificateExpiredException }
            if (causeIsExpiry) {
                HttpsCertResult.CertExpired(info)
            } else {
                HttpsCertResult.UntrustedChain(info, reason)
            }

        } catch (e: SocketTimeoutException) {
            HttpsCertResult.Timeout(host)

        } catch (e: UnknownHostException) {
            HttpsCertResult.HostnameUnresolved(host)

        } catch (e: java.net.ConnectException) {
            val msg = e.message.orEmpty()
            if (msg.contains("ENETUNREACH", ignoreCase = true) ||
                msg.contains("Network is unreachable", ignoreCase = true)
            ) {
                HttpsCertResult.NoNetwork
            } else {
                HttpsCertResult.Error("Connection refused or unreachable: ${e.localizedMessage}")
            }

        } catch (e: java.io.IOException) {
            val msg = e.message.orEmpty()
            if (msg.contains("ENETUNREACH", ignoreCase = true) ||
                msg.contains("Network is unreachable", ignoreCase = true)
            ) {
                HttpsCertResult.NoNetwork
            } else {
                HttpsCertResult.Error(e.localizedMessage ?: "I/O error")
            }

        } catch (e: Exception) {
            HttpsCertResult.Error(e.localizedMessage ?: "Unknown error")

        } finally {
            runCatching { socket?.close() }
        }
    }
    // ── CONNECT tunnel (proxied SSL) ───────────────────────────────────────────

    /**
     * Establishes an HTTP CONNECT tunnel through [proxy], then wraps the raw
     * socket with SSL for a transparent TLS handshake to [targetHost]:[targetPort].
     *
     * Steps:
     *  1. Open a raw TCP [Socket] to the proxy address.
     *  2. Send `CONNECT targetHost:targetPort HTTP/1.1\r\n\r\n`.
     *  3. Read the proxy's response; expect `HTTP/1.x 200 ...`.
     *  4. Wrap the raw socket with [sslFactory] for the real TLS handshake.
     *
     * @throws java.io.IOException if the proxy rejects the CONNECT or if the
     *         connection cannot be established within [CONNECT_TIMEOUT_MS].
     */
    // TODO: Proxy authentication is deferred — CONNECT does not send
    //       Proxy-Authorization headers. Add support when needed.
    private fun createProxiedSSLSocket(
        targetHost: String,
        targetPort: Int,
        proxy: java.net.Proxy,
        sslFactory: javax.net.ssl.SSLSocketFactory,
    ): SSLSocket {
        val proxyAddr = proxy.address() as InetSocketAddress

        // 1. Raw TCP connection to the proxy
        val rawSocket = Socket()
        rawSocket.connect(
            InetSocketAddress(proxyAddr.hostString, proxyAddr.port),
            CONNECT_TIMEOUT_MS,
        )
        rawSocket.soTimeout = READ_TIMEOUT_MS

        // 2. Send CONNECT request
        val connectRequest = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                "Host: $targetHost:$targetPort\r\n" +
                "\r\n"
        rawSocket.getOutputStream().apply {
            write(connectRequest.toByteArray(Charsets.US_ASCII))
            flush()
        }

        // 3. Read the proxy's response status line and headers
        val reader = BufferedReader(InputStreamReader(rawSocket.getInputStream(), Charsets.US_ASCII))
        val statusLine = reader.readLine()
            ?: throw java.io.IOException("Proxy closed connection before sending CONNECT response")

        // Expect "HTTP/1.x 200 ..."
        if (!statusLine.contains(" 200 ")) {
            rawSocket.close()
            throw java.io.IOException("Proxy CONNECT failed: $statusLine")
        }

        // Consume remaining headers until the blank line
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        // 4. Wrap with SSL — autoClose=true so closing SSLSocket also closes rawSocket
        val sslSocket = sslFactory.createSocket(
            rawSocket, targetHost, targetPort, /* autoClose = */ true,
        ) as SSLSocket
        sslSocket.soTimeout = READ_TIMEOUT_MS
        sslSocket.useClientMode = true

        return sslSocket
    }

    // ── Certificate parsing ───────────────────────────────────────────────────

    private fun parseCertificate(
        host: String,
        port: Int,
        chain: Array<out X509Certificate>,
    ): CertificateInfo {
        val leaf = chain[0]

        val now      = Date()
        val notAfter = leaf.notAfter
        val isExpired = now.after(notAfter)
        val daysLeft  = TimeUnit.MILLISECONDS.toDays(notAfter.time - now.time)
        val status = when {
            isExpired                  -> CertValidityStatus.EXPIRED
            daysLeft <= EXPIRY_WARN_DAYS -> CertValidityStatus.EXPIRING_SOON
            else                       -> CertValidityStatus.VALID
        }

        return CertificateInfo(
            host                = host,
            port                = port,
            subject             = parseDn(leaf.subjectX500Principal),
            issuer              = parseDn(leaf.issuerX500Principal),
            notBefore           = "${ISO_FMT.format(leaf.notBefore)} UTC",
            notAfter            = "${ISO_FMT.format(leaf.notAfter)} UTC",
            validityStatus      = status,
            daysUntilExpiry     = daysLeft,
            serialNumber        = leaf.serialNumber.toString(16).uppercase(),
            sha256Fingerprint   = fingerprint(leaf, "SHA-256"),
            sha1Fingerprint     = fingerprint(leaf, "SHA-1"),
            subjectAltNames     = parseSans(leaf),
            keyAlgorithm        = leaf.publicKey.algorithm,
            keySize             = keySize(leaf),
            version             = leaf.version,
            signatureAlgorithm  = leaf.sigAlgName,
            chainDepth          = chain.size,
        )
    }

    /** Parses a [X500Principal] into its component parts. */
    private fun parseDn(principal: X500Principal): DistinguishedName {
        // RFC 2253 format: CN=foo,O=bar,OU=baz,C=US
        val rfc2253 = principal.getName(X500Principal.RFC2253)
        return DistinguishedName(
            cn = dnComponent(rfc2253, "CN"),
            o  = dnComponent(rfc2253, "O"),
            ou = dnComponent(rfc2253, "OU"),
            c  = dnComponent(rfc2253, "C"),
        )
    }

    /**
     * Extracts a single attribute value from an RFC 2253 DN string.
     *
     * Handles quoted values and escaped commas (simplified — sufficient for
     * most real-world certs).
     */
    private fun dnComponent(rfc2253: String, attr: String): String? {
        // Regex: attr=<value> where value stops at an unescaped comma
        val pattern = Regex("""(?:^|,)\s*${Regex.escape(attr)}=([^,]*)""", RegexOption.IGNORE_CASE)
        return pattern.find(rfc2253)?.groupValues?.getOrNull(1)?.trim()?.ifEmpty { null }
    }

    /** Computes a colon-separated hex fingerprint of the certificate's DER encoding. */
    private fun fingerprint(cert: X509Certificate, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** Extracts Subject Alternative Names from the certificate extension. */
    @Suppress("UNCHECKED_CAST")
    private fun parseSans(cert: X509Certificate): List<SanEntry> {
        return try {
            cert.subjectAlternativeNames?.mapNotNull { san ->
                val typeCode = (san[0] as? Int) ?: return@mapNotNull null
                val value    = san[1]?.toString() ?: return@mapNotNull null
                when (typeCode) {
                    2  -> SanEntry("DNS", value)   // dNSName
                    7  -> SanEntry("IP",  value)   // iPAddress
                    else -> null                   // skip URI, email, etc.
                }
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Returns the public key size in bits, or -1 if the type is not recognised. */
    private fun keySize(cert: X509Certificate): Int = when (val key = cert.publicKey) {
        is RSAPublicKey -> key.modulus.bitLength()
        is ECPublicKey  -> key.params?.order?.bitLength() ?: -1
        else            -> -1
    }
}
