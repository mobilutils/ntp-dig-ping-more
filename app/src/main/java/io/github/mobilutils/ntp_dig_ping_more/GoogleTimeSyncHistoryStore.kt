package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for Google Time Sync history – one per app process. */
private val Context.googleTimeSyncHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "google_time_sync_history")

/**
 * A single entry in the Google Time Sync history list.
 *
 * @param timestamp  Formatted as `"yyyy/MM/dd HH:mm:ss"`.
 * @param url        The full URL that was queried (e.g. `http://clients2.google.com/time/1/current`).
 * @param offsetMs   Clock offset in ms at the time of the sync (may be negative).
 * @param rttMs      Round-trip time in ms.
 * @param success    Whether the sync completed without error.
 */
data class GoogleTimeSyncHistoryEntry(
    val timestamp: String,
    val url: String,
    val offsetMs: Long,
    val rttMs: Long,
    val success: Boolean,
)

/**
 * Persists the Google Time Sync query history (up to [MAX_ENTRIES] entries)
 * in Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   `timestamp|url|offsetMs|rttMs|success` — one entry per line (`\n` separated).
 *
 * Fields are separated by `|`.  URLs never contain `|`, and the other
 * constituent types (Long, Boolean) also satisfy this invariant, so the
 * separator is unambiguous.
 */
class GoogleTimeSyncHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("google_time_sync_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    /** Emits the current history list (newest-first) whenever it changes on disk. */
    val historyFlow: Flow<List<GoogleTimeSyncHistoryEntry>> =
        context.googleTimeSyncHistoryDataStore.data.map { prefs ->
            prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
        }

    /** Persists [history] to disk, replacing whatever was there before. */
    suspend fun save(history: List<GoogleTimeSyncHistoryEntry>) {
        context.googleTimeSyncHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP" +
                "${entry.url}$FIELD_SEP" +
                "${entry.offsetMs}$FIELD_SEP" +
                "${entry.rttMs}$FIELD_SEP" +
                "${entry.success}"
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deserialise(raw: String): List<GoogleTimeSyncHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 4) {
                    val offsetMs = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                    val rttMs    = parts.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
                    // parts[4] may be absent in entries saved before the success field was added
                    val success  = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: false
                    GoogleTimeSyncHistoryEntry(
                        timestamp = parts[0],
                        url       = parts[1],
                        offsetMs  = offsetMs,
                        rttMs     = rttMs,
                        success   = success,
                    )
                } else null
            }
            .take(MAX_ENTRIES)
}
