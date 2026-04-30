# CSV Output Feature for Bulk Actions

**Date:** 2026-04-30
**Purpose:** Allow users to export Bulk Actions results as CSV instead of the default text report.

## CSV Format

```
cmdname,command,time,result
ping,ping -c 4 example.com,14:23:01,64 bytes from ...
```

- `time` is formatted as `HH:mm:ss` (UTC/US locale)
- `result` is the joined output lines (semicolon-separated) for success, the error message for failure, or `TIMEOUT` for timeouts

## Data Flow

```
Checkbox (UI) → toggleCsvOutput() → DataStore (bulk_actions_csv)
                                                         ↓
csvOutputEnabled (StateFlow) ← DataStore                          ↓
                                                         writeViaSAF / writeDirect / writeFileViaSAF
                                                         → if csvEnabled → generateCsvContent()
                                                         → else          → generateOutputContent()
```

The CSV preference is **persisted** across sessions via DataStore. The JSON config field `outputAsCsv` can **override** the persisted preference (JSON takes precedence on file load).

## Modified Files

### 1. `BulkActionsRepository.kt`

**Modified:**
- `data class BulkConfig` — added `outputAsCsv: Boolean = false` field (set from JSON `"outputAsCsv"` key)
- `fun BulkConfigParser.parse()` — reads optional `"outputAsCsv"` boolean from JSON

### 2. `BulkActionsHistoryStore.kt`

**Not modified.** The CSV preference uses a **separate** DataStore (`bulk_actions_csv`) defined directly in `BulkActionsViewModel.kt` via `private val Context.bulkActionsCsvDataStore`. The history store (`bulk_actions_history`) is untouched.

### 3. `BulkActionsViewModel.kt`

**Added:**
- `val csvOutputEnabled: StateFlow<Boolean>` — DataStore-backed StateFlow (exposed to UI)
- `private val csvDataStore: DataStore<Preferences>` — lazy DataStore reference
- `fun toggleCsvOutput()` — toggles the CSV setting in DataStore (async via viewModelScope)
- `fun generateCsvContent(results: List<BulkCommandResult>): String` — formats results as CSV

**Modified:**
- `data class BulkUiState` — added `csvOutputEnabled: Boolean = false` field (UI copy, JSON-overrideable)
- `fun onFileSelected()` — reads `csvOutputEnabled.value` from DataStore, JSON `outputAsCsv` overrides it
- `fun onWriteOutputFile()` — checks `csvOutputEnabled.value` to choose CSV vs text format
- `fun writeFileViaSAF()` — checks `csvOutputEnabled.value` to choose CSV vs text format
- `fun writeViaSAF()` — checks `csvOutputEnabled.value` to choose CSV vs text format
- `fun writeDirect()` — checks `csvOutputEnabled.value` to choose CSV vs text format

### 4. `BulkActionsScreen.kt`

**Added:**
- `import androidx.compose.material3.Checkbox`
- `import androidx.compose.material3.CheckboxDefaults`
- `val csvOutputEnabled by viewModel.csvOutputEnabled.collectAsState()` — binds DataStore value to UI
- Checkbox composable next to "Load JSON Config" button (lines ~182-191)

**Modified:**
- "Load Config" button row now includes a Checkbox labeled "CSV output"

### 5. `BulkActionsHistoryStore.kt` (DataStore keys)

**Added:**
- `CSV_OUTPUT_ENABLED` preference key for boolean storage

## UI Behavior

1. User loads a JSON config → `onFileSelected()` fires
2. CSV checkbox reflects the **persisted** DataStore value (or JSON override if `outputAsCsv: true`)
3. Toggling the checkbox calls `toggleCsvOutput()` → writes to DataStore
4. On write (SAF or direct), `csvOutputEnabled.value` determines format:
   - `true` → `generateCsvContent()` (CSV)
   - `false` → `generateOutputContent()` (text report)

## Key Design Decisions

- **Two sources of truth for `csvOutputEnabled`:**
  - `viewModel.csvOutputEnabled` (DataStore-backed StateFlow) — the **canonical** source, used by checkbox UI and all write methods
  - `_uiState.value.csvOutputEnabled` (UI state field) — a **copy** set on file load, can be overridden by JSON config
- **JSON override:** If the JSON config has `"outputAsCsv": true`, it forces CSV output regardless of the persisted preference
- **Persistence:** The checkbox toggle persists to `bulk_actions_csv` DataStore, surviving app restarts
- **Auto-save:** When `output-file` is set in JSON config, auto-save after execution also respects the CSV flag
