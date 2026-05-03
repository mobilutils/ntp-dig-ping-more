package io.github.mobilutils.ntp_dig_ping_more.proxy

import app.cash.quickjs.QuickJs

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
 * `shExpMatch`, `isInNet`, `myIpAddress`, `dnsResolve`, `dnsDomainLevels`,
 * `localHostOrDomainIs`) is prepended so that common PAC scripts work
 * out of the box without requiring a full browser environment.
 */
class QuickJsEngine : JsEngine {

    companion object {
        /**
         * Minimal PAC utility stubs required by the PAC specification.
         *
         * Real DNS resolution (`dnsResolve`, `isInNet`) is stubbed to return
         * safe defaults. A production-grade implementation would perform
         * actual DNS lookups, but that requires Android-specific APIs and
         * significantly complicates the JS engine lifecycle.
         */
        // TODO: Consider implementing real DNS resolution for isInNet/dnsResolve
        //       if corporate PAC scripts require accurate subnet matching.
        private val PAC_UTILS = """
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
            function isInNet(host, pattern, mask) { return false; }
            function dnsResolve(host) { return '127.0.0.1'; }
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
    }

    override fun evaluatePac(pacScript: String, targetUrl: String, targetHost: String): String {
        val engine = QuickJs.create()
        try {
            // Load PAC utilities + the actual PAC script
            engine.evaluate(PAC_UTILS)
            engine.evaluate(pacScript)

            // Invoke FindProxyForURL and return the result
            val result = engine.evaluate(
                "FindProxyForURL('${escapeJs(targetUrl)}', '${escapeJs(targetHost)}');"
            )
            return result?.toString() ?: "DIRECT"
        } finally {
            engine.close()
        }
    }

    /**
     * Escapes single quotes and backslashes for safe embedding in a JS string literal.
     */
    private fun escapeJs(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}
