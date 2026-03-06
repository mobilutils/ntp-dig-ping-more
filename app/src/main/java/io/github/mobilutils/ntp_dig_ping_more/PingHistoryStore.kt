package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for ping history – one per app process. */
private val Context.pingHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "ping_history")

/** Three-state ping result. */
enum class PingStatus { ALL_SUCCESS, PARTIAL, ALL_FAILED }

/** A single entry in the ping history list. */
data class PingHistoryEntry(
    val timestamp: String,   // "yyyy/MM/dd HH:mm:ss"
    val host: String,
    val status: PingStatus,
)

/**
 * Persists the ping query history (up to [MAX_ENTRIES] entries) in Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   timestamp|host|status  — one entry per line ('\n' separated)
 */
class PingHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("ping_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    /** Emits the current history list (newest-first) whenever it changes on disk. */
    val historyFlow: Flow<List<PingHistoryEntry>> = context.pingHistoryDataStore.data.map { prefs ->
        prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
    }

    /** Persists [history] to disk, replacing whatever was there before. */
    suspend fun save(history: List<PingHistoryEntry>) {
        context.pingHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP${entry.host}$FIELD_SEP${entry.status.name}"
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deserialise(raw: String): List<PingHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 2) {
                    // Support old boolean format ("true"/"false") and new enum names
                    val status = when (parts.getOrNull(2)) {
                        "ALL_SUCCESS", "true"  -> PingStatus.ALL_SUCCESS
                        "PARTIAL"              -> PingStatus.PARTIAL
                        else                   -> PingStatus.ALL_FAILED
                    }
                    PingHistoryEntry(timestamp = parts[0], host = parts[1], status = status)
                } else null
            }
            .take(MAX_ENTRIES)
}
