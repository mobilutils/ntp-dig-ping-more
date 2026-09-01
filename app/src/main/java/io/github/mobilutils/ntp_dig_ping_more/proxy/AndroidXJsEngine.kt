package io.github.mobilutils.ntp_dig_ping_more.proxy

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxDeadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [JsEngine] implementation backed by [JavaScriptSandbox] (androidx.javascriptengine).
 *
 * **Hardened Features:**
 * - **Persistent Sandbox Connection:** Reuses the out-of-process [JavaScriptSandbox] instance across evaluations,
 *   avoiding expensive process binding/unbinding cycles per PAC request.
 * - **Availability Verification:** Checks [JavaScriptSandbox.isSupported] before attempting IPC initialization.
 * - **Injection Safety:** Uses [JSONObject.quote] for embedding user strings into JavaScript code.
 * - **Resource Bounds:** Restricts isolate memory via [IsolateStartupParameters] (16 MB cap when supported).
 * - **Crash Recovery:** Catches [SandboxDeadException] and resets the stale sandbox connection for auto-recovery.
 * - **Evaluation Timeout:** Caps evaluation at [evalTimeoutMs] (default 5 seconds).
 *
 * @param context Application or activity context.
 */
class AndroidXJsEngine(
    private val context: Context,
    private val maxHeapSizeBytes: Long = 16 * 1024 * 1024L,
    private val evalTimeoutMs: Long = 5_000L,
) : JsEngine, Closeable {

    private val sandboxMutex = Mutex()

    @Volatile
    private var sandboxInstance: JavaScriptSandbox? = null

    private suspend fun getOrCreateSandbox(): JavaScriptSandbox? = sandboxMutex.withLock {
        sandboxInstance?.let { return it }

        if (!JavaScriptSandbox.isSupported()) {
            return null
        }

        return try {
            val sandbox = JavaScriptSandbox
                .createConnectedInstanceAsync(context.applicationContext)
                .await()
            sandboxInstance = sandbox
            sandbox
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun evaluatePac(
        pacScript: String,
        targetUrl: String,
        targetHost: String,
    ): String = withContext(Dispatchers.IO) {
        val sandbox = getOrCreateSandbox() ?: return@withContext "DIRECT"
        val resolvedIp = resolveHost(targetHost)

        try {
            withTimeout(evalTimeoutMs) {
                val isolateParams = IsolateStartupParameters().apply {
                    if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                        maxHeapSizeBytes = this@AndroidXJsEngine.maxHeapSizeBytes
                    }
                }

                var isolate: JavaScriptIsolate? = null
                try {
                    isolate = sandbox.createIsolate(isolateParams)

                    // 1. Inject pre-resolved DNS target constants safely using JSONObject.quote
                    val initJs = """
                        var _resolvedTargetHost = ${JSONObject.quote(targetHost)};
                        var _resolvedTargetIp   = ${JSONObject.quote(resolvedIp)};
                    """.trimIndent()
                    isolate.evaluateJavaScriptAsync(initJs).await()

                    // 2. Load standard PAC utility stubs
                    isolate.evaluateJavaScriptAsync(PAC_UTILS_JS).await()

                    // 3. Load PAC script
                    isolate.evaluateJavaScriptAsync(pacScript).await()

                    // 4. Invoke FindProxyForURL safely
                    val evalJs = "FindProxyForURL(${JSONObject.quote(targetUrl)}, ${JSONObject.quote(targetHost)});"
                    val result = isolate.evaluateJavaScriptAsync(evalJs).await()

                    result?.takeIf { it.isNotBlank() } ?: "DIRECT"
                } catch (e: SandboxDeadException) {
                    // Reset stale sandbox connection on WebView process termination
                    sandboxMutex.withLock {
                        runCatching { sandboxInstance?.close() }
                        sandboxInstance = null
                    }
                    "DIRECT"
                } catch (_: Exception) {
                    "DIRECT"
                } finally {
                    runCatching { isolate?.close() }
                }
            }
        } catch (_: Exception) {
            "DIRECT"
        }
    }

    private fun resolveHost(host: String): String =
        try {
            InetAddress.getByName(host).hostAddress ?: "127.0.0.1"
        } catch (_: UnknownHostException) {
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }

    override fun close() {
        sandboxInstance?.close()
        sandboxInstance = null
    }

    companion object {

        // ── IP parsing & subnet comparison helpers ──────────────────────────────
        // Kept as public internal helpers so unit tests cover the pure-Kotlin logic.

        /**
         * Parses a dotted-decimal IPv4 string into a 4-element [IntArray].
         */
        fun parseIp(ip: String?): IntArray? {
            if (ip == null) return null
            val parts = ip.split(".")
            if (parts.size != 4) return null
            val result = IntArray(4)
            for (i in 0..3) {
                val octet = parts[i].toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                result[i] = octet
            }
            return result
        }

        /**
         * Compares two IPs under a subnet mask using unsigned bitwise AND.
         */
        fun compareSubnet(hostIp: IntArray, pattern: IntArray, mask: IntArray): Boolean {
            for (i in 0..3) {
                if ((hostIp[i] and mask[i]) != (pattern[i] and mask[i])) return false
            }
            return true
        }

        /**
         * PAC utility functions implemented entirely in JavaScript.
         */
        private val PAC_UTILS_JS = """
            function isPlainHostName(host) {
                return host.indexOf('.') === -1;
            }
            function dnsDomainIs(host, domain) {
                var d = domain;
                if (d.charAt(0) !== '.') d = '.' + d;
                return host.length >= d.length &&
                       host.substring(host.length - d.length) === d;
            }
            function localHostOrDomainIs(host, hostdom) {
                return host === hostdom ||
                       hostdom.indexOf(host + '.') === 0;
            }
            function isResolvable(host) { return true; }
            function myIpAddress() { return '127.0.0.1'; }
            function dnsDomainLevels(host) {
                var s = host.split('.');
                return s.length - 1;
            }
            function shExpMatch(str, shexp) {
                var re = shexp.replace(/\./g, '\\.').replace(/\*/g, '.*').replace(/\?/g, '.');
                return new RegExp('^' + re + '${'$'}').test(str);
            }
            function weekdayRange() { return true; }
            function dateRange()    { return true; }
            function timeRange()    { return true; }

            // dnsResolve: returns the pre-resolved IP for the target host;
            // falls back to "127.0.0.1" for any other host.
            function dnsResolve(host) {
                if (typeof _resolvedTargetHost !== 'undefined' &&
                    host === _resolvedTargetHost) {
                    return _resolvedTargetIp;
                }
                return '127.0.0.1';
            }

            // isInNet: pure-JS bitwise subnet comparison matching the PAC spec.
            function isInNet(host, pattern, mask) {
                function parseOctets(s) {
                    var parts = s.split('.');
                    if (parts.length !== 4) return null;
                    var r = [];
                    for (var i = 0; i < 4; i++) {
                        var n = parseInt(parts[i], 10);
                        if (isNaN(n) || n < 0 || n > 255) return null;
                        r.push(n);
                    }
                    return r;
                }
                var ip = parseOctets(host) ? host : dnsResolve(host);
                var h = parseOctets(ip);
                var p = parseOctets(pattern);
                var m = parseOctets(mask);
                if (!h || !p || !m) return false;
                for (var i = 0; i < 4; i++) {
                    if ((h[i] & m[i]) !== (p[i] & m[i])) return false;
                }
                return true;
            }
        """.trimIndent()
    }
}

/** Backward compatibility alias for legacy call sites and tests */
typealias QuickJsEngine = AndroidXJsEngine
