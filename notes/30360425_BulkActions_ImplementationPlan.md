ExitPlanMode Plan:

User approved the plan.

Bulk Actions Feature — Implementation Plan

Overview                                                                                                                                                                         
Add a new Bulk Actions screen accessible from the MORE overflow menu. Users select a JSON config file, review parsed commands, and execute them in sequence with real-time       
terminal-style output and optional file export.
                                                                                                                                                                                 
---                                                                                                                                                                              

1. New Files (6 files)

BulkActionsRepository.kt
- `BulkConfig` data class: outputFile: String?, commands: Map<String, String>
- `BulkConfigParser` (companion object): parses JSON via org.json.JSONObject (already on the platform — no new deps)
    - Expands ~ to Environment.getExternalStorageDirectory().absolutePath for output paths
    - Validates required run object exists
- `BulkCommandResult` sealed class hierarchy:
    - Success(commandName: String, outputLines: List<String>, durationMs: Long)
    - Error(commandName: String, errorMessage: String)
    - Timeout(commandName: String)
- `executeCommands()` method:
    - Iterates commands sequentially (ordered output for the output file)
    - Maps each command prefix to the appropriate tool:
        - ping ... → ProcessBuilder("ping", ...) — streams stdout line-by-line
        - dig @server fqdn → DigRepository.resolve(server, fqdn) → formats as dig output
        - ntp pool → NtpRepository.query(pool) → formats as NTP result
        - nmap -p ports host → PortScannerRepository TCP scan on specified ports
        - checkcert -p port host → HttpsCertRepository.fetchCertificate(host, port) → formats cert info
        - Unknown prefix → ProcessBuilder raw execution
    - Each result includes streamed output lines, status, and duration
    - Respects CancellationToken for stop/cancel
    - Emits progress callbacks (current index, total, command name)

BulkActionsViewModel.kt
- `BulkUiState` data class:
    - configLoaded: Boolean, configFileName: String?, commandCount: Int
    - isExecuting: Boolean, currentCommand: String?, progress: Float (0–1)
    - results: List<BulkCommandResult>, isFileWriting: Boolean
- Methods: onFileSelected(), onRunClicked(), onStopClicked(), onClearResults(), onWriteOutputFile()
- onCleared() cancels in-flight execution

BulkActionsScreen.kt
- Top section: Button → ActivityResultContracts.GetContent() (filters application/json)
- Loaded config summary: command count + file name (if loaded)
- Run button (enabled when config loaded, disabled while executing)
- Progress bar + current command indicator
- Terminal output card: scrollable monospace LazyColumn with color-coded lines:
    - Green prefix for Success, red for Error, yellow for Timeout
    - Each command's output grouped under a header like ▶ cmd1: ping -c 4 google.com
- Write to file button (enabled when results exist)
- Clear results button

BulkActionsHistoryStore.kt
- DataStore persistence of the last 5 loaded config file URIs (for "recent configs" quick access)

---                                                                                                                                                                              

2. Modified Files (3 files)

MainActivity.kt
- Add BulkActions to AppScreen sealed class:                                                                                                                                    
  1   object BulkActions : AppScreen("bulk_actions", "Bulk Actions", Icons.Filled.BatchPrediction)
- Add to allAppScreens list
- Add composable(AppScreen.BulkActions.route) { BulkActionsScreen() } to NavHost

MoreToolsScreen.kt
- Add AppScreen.BulkActions to the extraTools list (as the 7th item, at the top for visibility)

AndroidManifest.xml
- Add WRITE_EXTERNAL_STORAGE permission (for direct file write fallback):                                                                                                       
  1   <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="32" />

---                                                                                                                                                                              

3. Output File Strategy

Tier 1 — Direct write (preferred):
- If output-file is an absolute path (e.g., ~/Downloads/output-test-run.txt), expand ~ and write directly via FileOutputStream
- Requires WRITE_EXTERNAL_STORAGE (auto-granted on Android 9-, runtime-requested on Android 10–12)

Tier 2 — SAF fallback:
- If direct write fails (EACCES / scoped storage), launch ActivityResultContracts.CreateDocument("text/plain") to let the user pick the save location
- Streams results line-by-line to avoid OOM on large outputs

---                                                                                                                                                                              

4. Command Parsing Details


┌───────────┬──────────────────────────────────────────────┬──────────────────────────────────────────────────┐                                                                  
│ Prefix    │ Parsing                                      │ Tool Used                                        │                                                                  
├───────────┼──────────────────────────────────────────────┼──────────────────────────────────────────────────┤                                                                  
│ ping      │ ping -c N host → split by whitespace         │ ProcessBuilder("ping", "-c", N, host)            │                                                                  
├───────────┼──────────────────────────────────────────────┼──────────────────────────────────────────────────┤                                                                  
│ dig       │ dig @server fqdn → extract @server and fqdn  │ DigRepository.resolve(server, fqdn)              │                                                                  
├───────────┼──────────────────────────────────────────────┼──────────────────────────────────────────────────┤                                                                  
│ ntp       │ ntp pool → second token                      │ NtpRepository.query(pool)                        │                                                                  
├───────────┼──────────────────────────────────────────────┼──────────────────────────────────────────────────┤                                                                  
│ nmap      │ nmap -p ports host → extract ports + host    │ PortScannerRepository (TCP connect per port)     │                                                                  
├───────────┼──────────────────────────────────────────────┼──────────────────────────────────────────────────┤                                                                  
│ checkcert │ checkcert -p port host → extract port + host │ HttpsCertRepository.fetchCertificate(host, port) │                                                                  
└───────────┴──────────────────────────────────────────────┴──────────────────────────────────────────────────┘
                                                                                                                                                                                 
---                                                                                                                                                                              

5. Tests (1 file)

BulkActionsRepositoryTest.kt (unit tests)
- testParseValidConfig() — valid JSON → correct command map
- testParseTildeExpansion() — ~/Downloads/x.txt → /storage/emulated/0/Downloads/x.txt
- testParseMissingRunKey() — throws descriptive error
- testParseEmptyCommands() — returns empty map
- testMapAndExecutePing() — mocks ProcessBuilder → Success result
- testMapAndExecuteDig() — mocks DigRepository → Success result
- testMapAndExecuteNtp() — mocks NtpRepository → Success result
- testMapAndExecuteNmap() — mocks PortScannerRepository → Success result
- testMapAndExecuteCheckcert() — mocks HttpsCertRepository → Success result
- testMapUnknownPrefix() — falls back to ProcessBuilder
- testCancellationStopsExecution() — token cancels mid-batch

BulkActionsViewModelTest.kt (unit tests)
- testInitialState() — all defaults
- testOnFileSelected() — config loaded, commandCount updated
- testOnRunClicked() — execution starts, isExecuting = true
- testOnStopClicked() — execution stops, results preserved
- testOnClearResults() — results cleared
- testOnWriteOutputFileDirect() — direct write path
- testOnWriteOutputFileSaferFallback() — SAF fallback path                                                                                                                      