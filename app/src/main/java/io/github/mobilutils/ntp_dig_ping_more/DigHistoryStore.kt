package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for dig history – one per app process. */
private val Context.digHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "dig_history")

/** Two-state dig result. */
enum class DigStatus { SUCCESS, FAILED }

/** A single entry in the dig history list. */
data class DigHistoryEntry(
    val timestamp: String,   // "yyyy/MM/dd HH:mm:ss"
    val dnsServer: String,
    val fqdn: String,
    val status: DigStatus,
)

/**
 * Persists the dig query history (up to [MAX_ENTRIES] entries) in Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   timestamp|dnsServer|fqdn|status  — one entry per line ('\n' separated)
 */
class DigHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("dig_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    /** Emits the current history list (newest-first) whenever it changes on disk. */
    val historyFlow: Flow<List<DigHistoryEntry>> = context.digHistoryDataStore.data.map { prefs ->
        prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
    }

    /** Persists [history] to disk, replacing whatever was there before. */
    suspend fun save(history: List<DigHistoryEntry>) {
        context.digHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP${entry.dnsServer}$FIELD_SEP${entry.fqdn}$FIELD_SEP${entry.status.name}"
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deserialise(raw: String): List<DigHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 3) {
                    val status = when (parts.getOrNull(3)) {
                        "SUCCESS" -> DigStatus.SUCCESS
                        else      -> DigStatus.FAILED
                    }
                    DigHistoryEntry(
                        timestamp = parts[0],
                        dnsServer = parts[1],
                        fqdn      = parts[2],
                        status    = status,
                    )
                } else null
            }
            .take(MAX_ENTRIES)
}
