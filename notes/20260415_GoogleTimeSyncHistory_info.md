# GoogleTimeSync History info

Here's what was added/changed:

## New file — GoogleTimeSyncHistoryStore.kt
DataStore name: "google_time_sync_history"
Entry model: GoogleTimeSyncHistoryEntry(timestamp, host, offsetMs, rttMs, success)
Serialisation: timestamp|host|offsetMs|rttMs|success per line — handles negative offsets safely
Same MAX_ENTRIES = 5, deserialise() with mapNotNull guard pattern as the other stores

## Updated — GoogleTimeSyncViewModel.kt
Now requires GoogleTimeSyncHistoryStore (injected via factory, like SimpleNtpViewModel)
init {} block loads persisted history on first composition
syncTime() saves a new entry after every request (success or failure)
Duplicate-host deduplication before saving (same pattern as NTP)
GoogleTimeSyncScreenState(syncState, history) wraps both fields in one StateFlow
selectHistoryEntry() re-populates the host field and re-runs the sync via a callback

## Updated — GoogleTimeSyncScreen.kt
Uses viewModel(factory = GoogleTimeSyncViewModel.factory(context)) — context-aware factory
Collects screenState.syncState + screenState.history from the single StateFlow
Renders a "Recent Syncs" history card with timestamp, host, RTT, offset, and ✅/❌ icon per row
Tapping a history row re-populates the host field and triggers an immediate sync