package io.github.mobilutils.ntp_dig_ping_more.proxy

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.guava.await
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [JsEngine] implementation backed by [JavaScriptSandbox] (androidx.javascriptengine 1.1.0).
 *
 * This replaces the abandoned `app.cash.quickjs:quickjs-android:0.9.2` library, whose
 * `libquickjs.so` had LOAD segments not aligned to 16 KB boundaries — triggering a
 * Google Play rejection for apps targeting Android 15+ (API 35+) after Nov 1 2025.
 * `androidx.javascriptengine` ships no native `.so` file of its own; it delegates to
 * the system WebView process, making it inherently 16 KB–safe.
 *
 * **Native-bridge replacement:**
 * `app.cash.quickjs` allowed direct Kotlin↔JS interface bindings (QuickJs.set).
 * `JavaScriptEngine` does not support synchronous Kotlin callbacks from JS.
 * The bridges are replaced as follows:
 *
 * - `dnsResolve(host)`:  The target host is resolved in Kotlin via [InetAddress.getByName]
 *   *before* entering the JS sandbox; the result is injected as a JS constant
 *   `_resolvedTargetIp` so PAC scripts that call `dnsResolve(targetHost)` get a real answer.
 *   For any other hostname, the pure-JS stub returns `"127.0.0.1"` — identical to the
 *   fallback path of the previous native bridge.
 *
 * - `isInNet(host, pattern, mask)`:  Implemented entirely in JavaScript using the same
 *   bitwise subnet comparison algorithm as the Kotlin [compareSubnet] helper.
 *
 * **Threading:** Callers must invoke this on [kotlinx.coroutines.Dispatchers.IO] because
 * [evaluatePac] is `suspend` and internally awaits [com.google.common.util.concurrent.ListenableFuture]
 * results via `kotlinx-coroutines-guava`.
 *
 * **Sandbox lifecycle:** A new [JavaScriptIsolate] is created per [evaluatePac] call and
 * closed immediately afterwards. The [JavaScriptSandbox] is created once per [QuickJsEngine]
 * instance and closed when the engine is no longer needed (callers may leave it open for
 * the lifetime of the containing ViewModel — the sandbox is lightweight when idle).
 *
 * @param context Application or activity context (used to connect to the WebView sandbox process).
 */
class QuickJsEngine(private val context: Context) : JsEngine {

    companion object {

        // ── IP parsing & subnet comparison helpers ──────────────────────────────
        // Kept as public internal helpers so QuickJsEngineTest covers the pure-Kotlin
        // logic without needing the JS engine.

        /**
         * Parses a dotted-decimal IPv4 string into a 4-element [IntArray].
         *
         * Each octet is stored as an unsigned int (0–255).
         * Returns `null` if the input is not a valid IPv4 address.
         *
         * @param ip Dotted-decimal IPv4 string (e.g. `"10.64.66.0"`).
         */
        internal fun parseIp(ip: String?): IntArray? {
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
         *
         * @return `true` if `(hostIp & mask) == (pattern & mask)`.
         */
        internal fun compareSubnet(hostIp: IntArray, pattern: IntArray, mask: IntArray): Boolean {
            for (i in 0..3) {
                if ((hostIp[i] and mask[i]) != (pattern[i] and mask[i])) return false
            }
            return true
        }

        /**
         * PAC utility functions implemented entirely in JavaScript.
         *
         * `dnsResolve` uses a pre-injected constant `_resolvedTargetIp` / `_resolvedTargetHost`
         * for the target host (resolved in Kotlin before entering the sandbox), and falls back
         * to `"127.0.0.1"` for all other hostnames — matching the old native bridge fallback.
         *
         * `isInNet` performs a pure-JS bitwise subnet comparison, compatible with the PAC spec
         * and equivalent to the Kotlin [compareSubnet] helper.
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
            // falls back to "127.0.0.1" for any other host (same as old native bridge fallback).
            function dnsResolve(host) {
                if (typeof _resolvedTargetHost !== 'undefined' &&
                    host === _resolvedTargetHost) {
                    return _resolvedTargetIp;
                }
                return '127.0.0.1';
            }

            // isInNet: pure-JS bitwise subnet comparison matching the PAC spec.
            function isInNet(host, pattern, mask) {
                var ip = dnsResolve(host);
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

    override suspend fun evaluatePac(
        pacScript: String,
        targetUrl: String,
        targetHost: String,
    ): String {
        // 1. Pre-resolve the target host so dnsResolve(targetHost) works inside JS.
        val resolvedIp = resolveHost(targetHost)

        // 2. Create an isolate, evaluate, then close it.
        val sandbox: JavaScriptSandbox = JavaScriptSandbox
            .createConnectedInstanceAsync(context.applicationContext)
            .await()

        return sandbox.use { sb ->
            val isolate: JavaScriptIsolate = sb.createIsolate()
            isolate.use { iso ->
                // Inject pre-resolved DNS result as JS constants.
                iso.evaluateJavaScriptAsync(
                    "var _resolvedTargetHost = '${escapeJs(targetHost)}';" +
                    "var _resolvedTargetIp   = '${escapeJs(resolvedIp)}';"
                ).await()

                // Load PAC utility stubs (isPlainHostName, dnsResolve, isInNet, etc.).
                iso.evaluateJavaScriptAsync(PAC_UTILS_JS).await()

                // Load the actual PAC script.
                iso.evaluateJavaScriptAsync(pacScript).await()

                // Invoke FindProxyForURL and return the result.
                val result = iso.evaluateJavaScriptAsync(
                    "FindProxyForURL('${escapeJs(targetUrl)}', '${escapeJs(targetHost)}');"
                ).await()

                result?.toString() ?: "DIRECT"
            }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Resolves [host] to its primary IPv4 address string.
     * Returns `"127.0.0.1"` if resolution fails (matches the old native bridge fallback).
     */
    private fun resolveHost(host: String): String =
        try {
            InetAddress.getByName(host).hostAddress ?: "127.0.0.1"
        } catch (_: UnknownHostException) {
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }

    /**
     * Escapes single quotes and backslashes for safe embedding in a JS string literal.
     */
    private fun escapeJs(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}
