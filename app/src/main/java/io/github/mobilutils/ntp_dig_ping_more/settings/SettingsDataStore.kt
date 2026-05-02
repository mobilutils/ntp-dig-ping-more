package io.github.mobilutils.ntp_dig_ping_more.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// ─────────────────────────────────────────────────────────────────────────────
// DataStore — one singleton per app process, backed by "app_settings" file
// ─────────────────────────────────────────────────────────────────────────────

/** Top-level DataStore instance for global app settings. */
internal val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "app_settings")

// ─────────────────────────────────────────────────────────────────────────────
// Key definitions & defaults
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Centralised key declarations and boundary constants for all persisted settings.
 *
 * Adding a new setting:
 *  1. Declare a key here.
 *  2. Expose a Flow and an update function in [SettingsRepository].
 *  3. Consume it in the relevant ViewModel(s).
 */
object SettingsKeys {

    /** Preference key for the global operation timeout in seconds. */
    val TIMEOUT_SECONDS = intPreferencesKey("timeout_seconds")

    /** Default timeout applied when no value has been persisted yet. */
    const val DEFAULT_TIMEOUT_SECONDS: Int = 5

    /** Minimum accepted timeout value. */
    const val MIN_TIMEOUT_SECONDS: Int = 1

    /** Maximum accepted timeout value. */
    const val MAX_TIMEOUT_SECONDS: Int = 60
}
