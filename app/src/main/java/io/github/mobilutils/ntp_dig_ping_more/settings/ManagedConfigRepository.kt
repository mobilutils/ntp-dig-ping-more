package io.github.mobilutils.ntp_dig_ping_more.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Typed data class representing all MDM-configurable fields.
 *
 * Each field is nullable (or has a safe default) so that:
 *   - `null` means "not set by MDM" → use the app's built-in default.
 *   - A non-null value means "admin pushed this" → override the default.
 *
 * All restrictions are **overridable defaults**: the user can still change
 * any value after it has been pre-filled by the MDM.
 */
data class ManagedConfig(
    // ── NTP Client ────────────────────────────────────────────────────
    val ntpServer: String? = null,
    val ntpPort: String? = null,

    // ── DIG / DNS ─────────────────────────────────────────────────────
    val digServer: String? = null,
    val digFqdn: String? = null,

    // ── Ping ──────────────────────────────────────────────────────────
    val pingHost: String? = null,

    // ── Port Scanner ──────────────────────────────────────────────────
    val portScannerHost: String? = null,

    // ── HTTPS Certificate Inspector ───────────────────────────────────
    val httpsCertHost: String? = null,
    val httpsCertPort: String? = null,

    // ── Settings / Proxy PAC ──────────────────────────────────────────
    val proxyEnabled: Boolean? = null,
    val proxyPacUrl: String? = null,
    val proxyLoggingEnabled: Boolean? = null,

    // ── Bulk Actions (zero-touch provisioning) ────────────────────────
    /** Inline JSON payload. When non-null, takes precedence over [bulkActionsUrl]. */
    val bulkActionsJson: String? = null,
    /** URL to fetch JSON from at startup. Used only when [bulkActionsJson] is null. */
    val bulkActionsUrl: String? = null,
    /** When true, auto-executes the loaded Bulk Actions config without user interaction. */
    val bulkActionsAutoRun: Boolean = false,
)

/**
 * Repository that bridges Android's [RestrictionsManager] with the app's MVVM architecture.
 *
 * Responsibilities:
 *   - Reads the current managed configuration once at construction.
 *   - Exposes [configFlow] — a [StateFlow] of [ManagedConfig] — for reactive consumption.
 *   - Registers a [BroadcastReceiver] for [Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED]
 *     so that live MDM pushes update [configFlow] instantly without restarting the app.
 *
 * Non-managed devices: [RestrictionsManager.getApplicationRestrictions] returns an empty
 * [android.os.Bundle], which maps to [ManagedConfig] with all fields null/default —
 * completely transparent to the rest of the app.
 *
 * Lifecycle: call [unregister] when the repository is no longer needed (e.g., ViewModel
 * onCleared) to avoid a BroadcastReceiver leak.
 */
class ManagedConfigRepository(private val context: Context) {

    private val restrictionsManager =
        context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager

    private val _configFlow = MutableStateFlow(readConfig())
    val configFlow: StateFlow<ManagedConfig> = _configFlow.asStateFlow()

    /**
     * Returns true when the app is operating under active MDM-pushed restrictions.
     * Useful for the Device Info screen "App is managed" indicator.
     */
    val isAppManaged: Boolean
        get() = restrictionsManager.applicationRestrictions.size() > 0

    // ── BroadcastReceiver for live restriction changes ────────────────

    private val restrictionsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
                _configFlow.value = readConfig()
            }
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                restrictionsChangedReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(restrictionsChangedReceiver, filter)
        }
    }

    /**
     * Unregisters the [BroadcastReceiver]. Call from [androidx.lifecycle.ViewModel.onCleared].
     */
    fun unregister() {
        try {
            context.unregisterReceiver(restrictionsChangedReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was never registered (e.g., in unit tests) — safe to ignore.
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun readConfig(): ManagedConfig {
        val bundle = restrictionsManager.applicationRestrictions
        return ManagedConfig(
            // NTP
            ntpServer           = bundle.getString("ntp_default_server")?.takeIfNotBlank(),
            ntpPort             = bundle.getString("ntp_default_port")?.takeIfNotBlank(),
            // DIG
            digServer           = bundle.getString("dig_default_server")?.takeIfNotBlank(),
            digFqdn             = bundle.getString("dig_default_fqdn")?.takeIfNotBlank(),
            // Ping
            pingHost            = bundle.getString("ping_default_host")?.takeIfNotBlank(),
            // Port Scanner
            portScannerHost     = bundle.getString("port_scanner_default_host")?.takeIfNotBlank(),
            // HTTPS Cert
            httpsCertHost       = bundle.getString("https_cert_default_host")?.takeIfNotBlank(),
            httpsCertPort       = bundle.getString("https_cert_default_port")?.takeIfNotBlank(),
            // Proxy PAC — nullable Boolean: only non-null when explicitly pushed
            proxyEnabled        = if (bundle.containsKey("proxy_enabled"))
                                      bundle.getBoolean("proxy_enabled") else null,
            proxyPacUrl         = bundle.getString("proxy_pac_url")?.takeIfNotBlank(),
            proxyLoggingEnabled = if (bundle.containsKey("proxy_logging_enabled"))
                                      bundle.getBoolean("proxy_logging_enabled") else null,
            // Bulk Actions
            bulkActionsJson     = bundle.getString("bulk_actions_json")?.takeIfNotBlank(),
            bulkActionsUrl      = bundle.getString("bulk_actions_url")?.takeIfNotBlank(),
            bulkActionsAutoRun  = bundle.getBoolean("bulk_actions_auto_run", false),
        )
    }

    private fun String.takeIfNotBlank(): String? = takeIf { it.isNotBlank() }
}
