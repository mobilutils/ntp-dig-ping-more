package io.github.mobilutils.ntp_dig_ping_more.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────────────────────
// SettingsRepository
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Single source of truth for persisted global application settings.
 *
 * Backed by [settingsDataStore] (AndroidX Preferences DataStore). All reads
 * are reactive [Flow]s; writes are suspend functions that run on the DataStore
 * IO executor — safe to call from any coroutine context.
 *
 * Input validation (clamping to [SettingsKeys.MIN_TIMEOUT_SECONDS]..[SettingsKeys.MAX_TIMEOUT_SECONDS])
 * is enforced here so callers never need to guard against out-of-range values.
 *
 * Instantiation: create with an [android.app.Application] context so the
 * underlying DataStore singleton is shared across the process.
 */
class SettingsRepository(private val context: Context) {

    // ── Timeout ───────────────────────────────────────────────────────────────

    /**
     * Emits the currently persisted operation timeout in seconds.
     *
     * Defaults to [SettingsKeys.DEFAULT_TIMEOUT_SECONDS] when no value has
     * been written yet (i.e. first launch).
     */
    val timeoutSecondsFlow: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[SettingsKeys.TIMEOUT_SECONDS] ?: SettingsKeys.DEFAULT_TIMEOUT_SECONDS
    }

    /**
     * Persists [seconds] as the new operation timeout.
     *
     * Values outside [SettingsKeys.MIN_TIMEOUT_SECONDS]..[SettingsKeys.MAX_TIMEOUT_SECONDS]
     * are silently clamped so the store never holds an invalid value.
     */
    suspend fun updateTimeout(seconds: Int) {
        val clamped = seconds.coerceIn(
            SettingsKeys.MIN_TIMEOUT_SECONDS,
            SettingsKeys.MAX_TIMEOUT_SECONDS,
        )
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.TIMEOUT_SECONDS] = clamped
        }
    }
}
