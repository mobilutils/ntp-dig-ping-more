package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Top-level DataStore instance for port scanner history – one per app process. */
private val Context.portScannerHistoryDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "port_scanner_history")

enum class PortScannerProtocol { TCP, UDP }

data class PortScannerHistoryEntry(
    val timestamp: String,
    val host: String,
    val startPort: String,
    val endPort: String,
    val protocol: PortScannerProtocol,
)

class PortScannerHistoryStore(private val context: Context) {
    companion object {
        private const val MAX_ENTRIES = 5
        private val KEY = stringPreferencesKey("port_scanner_history")
        private const val FIELD_SEP = "|"
        private const val ENTRY_SEP = "\n"
    }

    val historyFlow: Flow<List<PortScannerHistoryEntry>> = context.portScannerHistoryDataStore.data.map { prefs ->
        prefs[KEY]?.let { raw -> deserialise(raw) } ?: emptyList()
    }

    suspend fun save(history: List<PortScannerHistoryEntry>) {
        context.portScannerHistoryDataStore.edit { prefs ->
            prefs[KEY] = history.take(MAX_ENTRIES).joinToString(ENTRY_SEP) { entry ->
                "${entry.timestamp}$FIELD_SEP${entry.host}$FIELD_SEP${entry.startPort}$FIELD_SEP${entry.endPort}$FIELD_SEP${entry.protocol.name}"
            }
        }
    }

    private fun deserialise(raw: String): List<PortScannerHistoryEntry> =
        raw.split(ENTRY_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size >= 5) {
                    val protocol = when (parts[4]) {
                        "UDP" -> PortScannerProtocol.UDP
                        else -> PortScannerProtocol.TCP
                    }
                    PortScannerHistoryEntry(
                        timestamp = parts[0],
                        host = parts[1],
                        startPort = parts[2],
                        endPort = parts[3],
                        protocol = protocol
                    )
                } else null
            }
            .take(MAX_ENTRIES)
}
