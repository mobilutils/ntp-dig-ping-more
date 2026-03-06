package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance – one per app process. */
private val Context.historyDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "ntp_history")

/**
 * Persists the NTP query history (up to [MAX_ENTRIES] entries) in Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   timestamp|server|port  — one entry per line ('\n' separated)
 */
class NtpHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    /** Emits the current history list (newest‑first) whenever it changes on disk. */
    val historyFlow: Flow<List<NtpHistoryEntry>> = context.historyDataStore.data.map { prefs ->
        prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
    }

    /** Persists [history] to disk, replacing whatever was there before. */
    suspend fun save(history: List<NtpHistoryEntry>) {
        context.historyDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP${entry.server}$FIELD_SEP${entry.port}$FIELD_SEP${entry.success}"
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deserialise(raw: String): List<NtpHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 3) {
                    val port = parts[2].toIntOrNull() ?: return@mapNotNull null
                    // parts[3] may be absent in entries saved before the success field was added
                    val success = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false
                    NtpHistoryEntry(timestamp = parts[0], server = parts[1], port = port, success = success)
                } else null
            }
            .take(MAX_ENTRIES)
}
