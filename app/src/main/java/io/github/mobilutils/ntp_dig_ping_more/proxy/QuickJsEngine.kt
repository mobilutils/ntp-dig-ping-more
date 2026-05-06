package io.github.mobilutils.ntp_dig_ping_more.proxy

import app.cash.quickjs.QuickJs
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [JsEngine] implementation backed by [QuickJs] (app.cash.quickjs).
 *
 * Each [evaluatePac] call creates a fresh QuickJS runtime, loads the PAC
 * script, invokes `FindProxyForURL(url, host)`, and tears down the runtime.
 * This is safe for concurrent use because no shared mutable state exists
 * between invocations.
 *
 * **Threading:** Callers must invoke this on [kotlinx.coroutines.Dispatchers.IO].
 *
 * A minimal set of PAC helper functions (`isPlainHostName`, `dnsDomainIs`,
 * `shExpMatch`, `myIpAddress`, `dnsDomainLevels`, `localHostOrDomainIs`)
 * is prepended so that common PAC scripts work out of the box without
 * requiring a full browser environment.
 *
 * `dnsResolve` and `isInNet` are provided as **native Kotlin bridges** that
 * perform real DNS resolution via [InetAddress.getByName] and bitwise subnet
 * comparison, matching the behaviour of Android's system PAC evaluator.
 */
class QuickJsEngine : JsEngine {

    // ── Interfaces for QuickJs native binding ────────────────────────────────

    /**
     * Bridge interface for `dnsResolve(host)`.
     *
     * Exposed to JavaScript via [QuickJs.set]. The JS wrapper function
     * `dnsResolve(host)` delegates to `_dnsResolver.resolve(host)`.
     */
    interface DnsResolveService {
        fun resolve(host: String): String
    }

    /**
     * Bridge interface for `isInNet(host, pattern, mask)`.
     *
     * Exposed to JavaScript via [QuickJs.set]. The JS wrapper function
     * `isInNet(host, pattern, mask)` delegates to `_isInNetService.check(host, pattern, mask)`.
     */
    interface IsInNetService {
        fun check(host: String, pattern: String, mask: String): Boolean
    }

    companion object {
        /**
         * PAC utility stubs that work purely in JavaScript.
         *
         * `dnsResolve` and `isInNet` are NOT included here — they are
         * provided as native Kotlin bridges via [QuickJs.set].
         */
        private val PAC_UTILS_JS_ONLY = """
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
                var re = shexp.replace(/\./g, '\\\\.').replace(/\*/g, '.*').replace(/\?/g, '.');
                return new RegExp('^' + re + '$').test(str);
            }
            function weekdayRange() { return true; }
            function dateRange() { return true; }
            function timeRange() { return true; }
        """.trimIndent()

        /**
         * JS wrapper that delegates `dnsResolve(host)` to the native bridge.
         * The native object `_dnsResolver` is bound via [QuickJs.set].
         */
        private const val DNS_RESOLVE_WRAPPER = """
            function dnsResolve(host) {
                return _dnsResolver.resolve(host);
            }
        """

        /**
         * JS wrapper that delegates `isInNet(host, pattern, mask)` to the native bridge.
         * The native object `_isInNetService` is bound via [QuickJs.set].
         */
        private const val IS_IN_NET_WRAPPER = """
            function isInNet(host, pattern, mask) {
                return _isInNetService.check(host, pattern, mask);
            }
        """

        // ── IP parsing & subnet comparison helpers ──────────────────────────

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
    }

    override fun evaluatePac(pacScript: String, targetUrl: String, targetHost: String): String {
        val engine = QuickJs.create()
        try {
            // 1. Load JS-only PAC utilities
            engine.evaluate(PAC_UTILS_JS_ONLY)

            // 2. Register native bridges
            registerNativeDnsResolve(engine)
            registerNativeIsInNet(engine)

            // 3. Load the PAC script
            engine.evaluate(pacScript)

            // 4. Invoke FindProxyForURL and return result
            val result = engine.evaluate(
                "FindProxyForURL('${escapeJs(targetUrl)}', '${escapeJs(targetHost)}');"
            )
            return result?.toString() ?: "DIRECT"
        } finally {
            engine.close()
        }
    }

    // ── Native bridge registration ───────────────────────────────────────────

    /**
     * Registers a native `dnsResolve(host)` function into the QuickJS runtime.
     *
     * Uses [InetAddress.getByName] for DNS resolution — this respects the
     * device's default DNS resolver (WiFi/mobile network DNS, VPN routing, etc.).
     * Falls back to `"127.0.0.1"` for unresolvable hostnames.
     */
    private fun registerNativeDnsResolve(engine: QuickJs) {
        val service = object : DnsResolveService {
            override fun resolve(host: String): String {
                return try {
                    InetAddress.getByName(host).hostAddress ?: "127.0.0.1"
                } catch (_: UnknownHostException) {
                    "127.0.0.1"   // fallback — keep existing behavior for unresolvable hosts
                } catch (_: Exception) {
                    "127.0.0.1"
                }
            }
        }
        engine.set("_dnsResolver", DnsResolveService::class.java, service)
        engine.evaluate(DNS_RESOLVE_WRAPPER)
    }

    /**
     * Registers a native `isInNet(host, pattern, mask)` function into the QuickJS runtime.
     *
     * Resolves the hostname to an IP via [InetAddress.getByName], then performs
     * bitwise subnet comparison: `(hostIp & mask) == (pattern & mask)`.
     *
     * TODO: IPv6 addresses are not supported — PAC scripts in this project use IPv4 only.
     */
    private fun registerNativeIsInNet(engine: QuickJs) {
        val service = object : IsInNetService {
            override fun check(host: String, pattern: String, mask: String): Boolean {
                // Resolve hostname to IP (uses device default DNS)
                val hostIp = try {
                    InetAddress.getByName(host).hostAddress
                } catch (_: UnknownHostException) {
                    return false
                } catch (_: Exception) {
                    return false
                }

                // Parse all three IPs into int arrays
                val hostIpInts = parseIp(hostIp) ?: return false
                val patternInts = parseIp(pattern) ?: return false
                val maskInts = parseIp(mask) ?: return false

                // Compare: (hostIp & mask) == (pattern & mask)
                return compareSubnet(hostIpInts, patternInts, maskInts)
            }
        }
        engine.set("_isInNetService", IsInNetService::class.java, service)
        engine.evaluate(IS_IN_NET_WRAPPER)
    }

    /**
     * Escapes single quotes and backslashes for safe embedding in a JS string literal.
     */
    private fun escapeJs(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}
