package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for bulk actions history. */
private val Context.bulkActionsHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "bulk_actions_history")

/** A single entry in the bulk actions history list. */
data class BulkHistoryEntry(
    val timestamp: Long,    // epoch millis
    val uri: String,
    val fileName: String,
)

/**
 * Persists recently loaded bulk config URIs (up to [MAX_ENTRIES]) in Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   timestamp|uri|fileName  — one entry per line ('\n' separated)
 */
class BulkActionsHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("bulk_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    val historyFlow: Flow<List<BulkHistoryEntry>> = context.bulkActionsHistoryDataStore.data.map { prefs ->
        prefs[KEY]?.let { deserialise(it) } ?: emptyList()
    }

    suspend fun save(history: List<BulkHistoryEntry>) {
        context.bulkActionsHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP${entry.uri}$FIELD_SEP${entry.fileName}"
            }
        }
    }

    private fun deserialise(raw: String): List<BulkHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 3) {
                    parts.getOrNull(0)?.toLongOrNull()?.let { ts ->
                        BulkHistoryEntry(timestamp = ts, uri = parts[1], fileName = parts[2])
                    }
                } else null
            }
            .take(MAX_ENTRIES)
}
