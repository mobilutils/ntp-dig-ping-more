package io.github.mobilutils.ntp_dig_ping_more.settings

/**
 * Holds the user's Proxy/PAC configuration, persisted in DataStore.
 *
 * @param enabled         Whether proxy routing is active.
 * @param pacUrl          URL to a PAC script (e.g. `http://proxy.corp.com/proxy.pac`).
 * @param lastTested      Epoch millis of the last "Test Proxy" action, or 0 if never tested.
 * @param lastTestResult  Human-readable result of the last test (null if never tested).
 * @param loggingEnabled  Whether high-level proxy PAC logging is active.
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val pacUrl: String = "",
    val lastTested: Long = 0L,
    val lastTestResult: String? = null,
    val loggingEnabled: Boolean = false,
)

