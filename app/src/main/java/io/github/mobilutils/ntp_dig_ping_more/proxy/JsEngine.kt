package io.github.mobilutils.ntp_dig_ping_more.proxy

/**
 * Abstraction over a JavaScript engine used to evaluate PAC scripts.
 *
 * Implementations must be safe to call from [kotlinx.coroutines.Dispatchers.IO].
 * The engine should evaluate the standard `FindProxyForURL(url, host)` function
 * defined in the PAC script and return the raw result string.
 */
interface JsEngine {

    /**
     * Evaluates a PAC script's `FindProxyForURL` function.
     *
     * @param pacScript   The full PAC JavaScript source code.
     * @param targetUrl   The URL being requested (e.g. `http://example.com/path`).
     * @param targetHost  The hostname portion of [targetUrl] (e.g. `example.com`).
     * @return The raw PAC result string, e.g. `"PROXY 10.0.0.1:8080"`,
     *         `"DIRECT"`, or a semicolon-separated fallback chain.
     * @throws Exception if the script is malformed or evaluation fails.
     */
    fun evaluatePac(pacScript: String, targetUrl: String, targetHost: String): String
}
