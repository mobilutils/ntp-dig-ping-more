package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for HTTPS cert history – one per app process. */
private val Context.httpsCertHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "https_cert_history")

/** Validity status stored in a history entry. */
enum class CertHistoryStatus { VALID, EXPIRING_SOON, EXPIRED, UNTRUSTED, ERROR }

/** A single entry in the HTTPS cert lookup history. */
data class HttpsCertHistoryEntry(
    val timestamp: String,   // "yyyy/MM/dd HH:mm:ss"
    val host: String,
    val port: Int,
    val status: CertHistoryStatus,
    /** Short label: e.g. "valid · 47d", "expired", "RSA 2048". */
    val summary: String,
)

/**
 * Persists the HTTPS cert query history (up to [MAX_ENTRIES] entries) using
 * Preferences DataStore.
 *
 * Serialisation format (single string preference):
 *   timestamp|host|port|status|summary — one entry per line ('\n' separated)
 */
class HttpsCertHistoryStore(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("https_cert_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    /** Emits the current history list (newest-first) whenever it changes on disk. */
    val historyFlow: Flow<List<HttpsCertHistoryEntry>> =
        context.httpsCertHistoryDataStore.data.map { prefs ->
            prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
        }

    /** Persists [history] to disk, replacing whatever was there before. */
    suspend fun save(history: List<HttpsCertHistoryEntry>) {
        context.httpsCertHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { e ->
                "${e.timestamp}$FIELD_SEP${e.host}$FIELD_SEP${e.port}$FIELD_SEP${e.status.name}$FIELD_SEP${e.summary}"
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deserialise(raw: String): List<HttpsCertHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 5) {
                    val status = runCatching {
                        CertHistoryStatus.valueOf(parts[3])
                    }.getOrDefault(CertHistoryStatus.ERROR)
                    HttpsCertHistoryEntry(
                        timestamp = parts[0],
                        host      = parts[1],
                        port      = parts[2].toIntOrNull() ?: 443,
                        status    = status,
                        summary   = parts.drop(4).joinToString(FIELD_SEP), // re-join if summary itself had "|"
                    )
                } else null
            }
            .take(MAX_ENTRIES)
}
