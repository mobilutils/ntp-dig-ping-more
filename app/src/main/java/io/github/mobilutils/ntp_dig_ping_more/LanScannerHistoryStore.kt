package io.github.mobilutils.ntp_dig_ping_more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.lanScannerDataStore: DataStore<Preferences> by preferencesDataStore(name = "lan_scanner_history")

data class LanScannerHistoryEntry(
    val timestamp: String,
    val type: String,               // "Quick Scan" or "Full Scan"
    val subnet: String,             // e.g. "192.168.1.0/24"
    val activeHostsCount: Int
)

class LanScannerHistoryStore(private val context: Context) {
    private val HISTORY_KEY = stringPreferencesKey("lan_scanner_history")

    val historyFlow: Flow<List<LanScannerHistoryEntry>> = context.lanScannerDataStore.data.map { prefs ->
        val jsonString = prefs[HISTORY_KEY] ?: "[]"
        parseHistory(jsonString)
    }

    suspend fun save(history: List<LanScannerHistoryEntry>) {
        val jsonArray = JSONArray()
        history.forEach { entry ->
            val obj = JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("type", entry.type)
                put("subnet", entry.subnet)
                put("activeHostsCount", entry.activeHostsCount)
            }
            jsonArray.put(obj)
        }
        context.lanScannerDataStore.edit { prefs ->
            prefs[HISTORY_KEY] = jsonArray.toString()
        }
    }

    private fun parseHistory(jsonString: String): List<LanScannerHistoryEntry> {
        val items = mutableListOf<LanScannerHistoryEntry>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    LanScannerHistoryEntry(
                        timestamp = obj.getString("timestamp"),
                        type = obj.getString("type"),
                        subnet = obj.getString("subnet"),
                        activeHostsCount = obj.getInt("activeHostsCount"),
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse errors, return what we have so far
        }
        return items
    }
}
