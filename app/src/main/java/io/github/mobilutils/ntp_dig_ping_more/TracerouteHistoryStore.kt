package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for traceroute history – one per app process. */
private val Context.tracerouteHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "traceroute_history")

/** Three-state traceroute result. */
enum class TracerouteStatus { ALL_SUCCESS, PARTIAL, ALL_FAILED }

/** A single entry in the traceroute history list. */
data class TracerouteHistoryEntry(
    val timestamp: String,   // "yyyy/MM/dd HH:mm:ss"
    val host: String,
    val status: TracerouteStatus,
)

/**
 * Persists the traceroute query history (up to [MAX_ENTRIES] entries) in Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   timestamp|host|status  — one entry per line ('\n' separated)
 */
class TracerouteHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("traceroute_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    /** Emits the current history list (newest-first) whenever it changes on disk. */
    val historyFlow: Flow<List<TracerouteHistoryEntry>> =
        context.tracerouteHistoryDataStore.data.map { prefs ->
            prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
        }

    /** Persists [history] to disk, replacing whatever was there before. */
    suspend fun save(history: List<TracerouteHistoryEntry>) {
        context.tracerouteHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP${entry.host}$FIELD_SEP${entry.status.name}"
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deserialise(raw: String): List<TracerouteHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 2) {
                    val status = when (parts.getOrNull(2)) {
                        "ALL_SUCCESS" -> TracerouteStatus.ALL_SUCCESS
                        "PARTIAL"     -> TracerouteStatus.PARTIAL
                        else          -> TracerouteStatus.ALL_FAILED
                    }
                    TracerouteHistoryEntry(
                        timestamp = parts[0],
                        host      = parts[1],
                        status    = status,
                    )
                } else null
            }
            .take(MAX_ENTRIES)
}
