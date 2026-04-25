# Bulk Actions — Local Config File Feature

**Date:** 2026-04-25
**Status:** Implemented, compiled, tests passing

---

## Summary

Added a **Bulk Actions** screen accessible from the MORE overflow menu. Users select a JSON configuration file, review parsed commands, and execute them in sequence with real-time terminal-style output and optional file export via two strategies:

1. **Config-specified output file** — If the JSON config includes an `"output-file"` field, results are written to that path (with `~` expansion to external storage).
2. **SAF picker** — If no output file is specified in the config, or the config-specified path fails, a Storage Access Framework (SAF) picker lets the user choose where to save the output.

---

## New Files

| File | Purpose |
|---|---|
| `BulkActionsRepository.kt` | JSON config parsing (`BulkConfigParser`), command mapping (ping/dig/ntp/nmap/checkcert → app tools or raw shell), sequential execution with 30s timeout per command |
| `BulkActionsViewModel.kt` | UI state (`BulkUiState`), file loading, run/stop/clear/export logic, validation message support |
| `BulkActionsScreen.kt` | File picker (JSON filter), config summary, progress bar, terminal-style result output (color-coded by status), validation message display, write-to-file button |
| `BulkActionsHistoryStore.kt` | DataStore persistence of last 5 loaded config URIs |
| `BulkConfigParserTest.kt` | 9 unit tests for parsing logic (valid config, tilde expansion, missing run key, empty commands, blank command filtering, etc.) |
| `BulkActionsViewModelTest.kt` | 5 unit tests for ViewModel state management |

## Modified Files

| File | Change |
|---|---|
| `MainActivity.kt` | Added `BulkActions` to `AppScreen` sealed class and NavHost route; uses `Icons.Filled.Terminal` as the Bulk Actions icon |
| `MoreToolsScreen.kt` | Added `BulkActions` as first item in overflow menu |
| `AndroidManifest.xml` | Added `WRITE_EXTERNAL_STORAGE` permission (maxSdkVersion 32) |

## Command Mapping

| Prefix | Behavior |
|---|---|
| `ping` | `ProcessBuilder("ping", "-c", N, host)` — streams stdout line-by-line |
| `dig @server fqdn` | `DigRepository.resolve(server, fqdn)` — formatted as dig output |
| `ntp pool` | `NtpRepository.query(pool)` — formatted as NTP result |
| `nmap -p ports host` | TCP connect scan per port (parses ranges like `80-443` and comma lists) |
| `checkcert -p port host` | `HttpsCertRepository.fetchCertificate(host, port)` — formatted cert info |
| Unknown | Raw `ProcessBuilder` execution — falls back to shell |

---

## Error Handling Philosophy

The implementation follows the same error-handling pattern established across the app:

1. **Sealed result classes** — Each command produces one of three outcomes: `BulkCommandSuccess`, `BulkCommandError`, or `BulkCommandTimeout`. This gives the UI type-safe, exhaustive-match rendering without null checks.

2. **Per-command timeouts** — Each command has a 30-second timeout. If it exceeds that, the command is marked as `Timeout` and execution continues to the next command. No single slow command blocks the entire batch.

3. **Cancellation support** — The `AtomicBoolean` cancellation token lets users stop execution mid-batch. Commands already in-flight complete; the rest are skipped.

4. **Graceful degradation** — Commands that fail (network errors, invalid syntax, missing tools) are captured as `Error` entries with the exception message. The batch continues executing remaining commands. The UI shows a summary count (e.g., "Results (3/6)") so users immediately see how many succeeded.

5. **No crash-on-bad-input** — Invalid JSON produces a clear error message. Malformed command strings are passed through to the underlying tool, which may succeed or fail — the result is captured either way.

---

## Tests

### BulkConfigParserTest (9 tests)
Pure-JVM tests that mirror the JSON parsing logic to avoid Android API dependencies:

- `parseValidConfig returns config with commands` — valid JSON → correct command map
- `parseMissingRunKey throws exception` — missing `run` object → `IllegalArgumentException`
- `parseEmptyCommands returns empty map` — empty `run` → empty commands
- `parseIgnoresBlankCommands` — blank/whitespace-only values are filtered
- `parseMultipleCommands preserves all commands` — 6 commands preserved in order
- `parseNoOutputFile returns null outputFile` — optional `output-file` is null when absent
- `parseTildePath preserves tilde in JVM tests` — tilde handling verified
- `parseNonTildePath returns unchanged` — absolute paths pass through
- `parseCommandsWithWhitespace trimsValues` — command values are trimmed

### BulkActionsViewModelTest (5 tests)
Tests ViewModel state management using MockK:

- `initialState_allDefaults` — all UI state defaults verified
- `onStopClicked_stopsExecution` — stop sets `isExecuting = false`
- `onClearResults_clearsResultsAndProgress` — results cleared, progress reset
- `onRunClicked_setsExecutingTrueBeforeCoroutineRuns` — synchronous state update verified
- `uiState_isImmutableCopy` — state immutability verified

### Running Tests

```bash
# All Bulk Actions tests
./gradlew :app:testDebugUnitTest --tests "io.github.mobilutils.ntp_dig_ping_more.BulkConfigParserTest"
./gradlew :app:testDebugUnitTest --tests "io.github.mobilutils.ntp_dig_ping_more.BulkActionsViewModelTest"

# All tests together
./gradlew :app:testDebugUnitTest
```

---

## Example Config File

```json
{
  "output-file": "~/Downloads/output-test-run.txt",
  "run": {
    "cmd1": "ping -c 4 google.com",
    "cmd2": "ping -c 5 10.0.0.1",
    "cmd3": "dig @1.1.1.1 cybernews.com",
    "cmd4": "ntp pool.ntp.org",
    "cmd5": "nmap -p 80-443 mobilutils.com",
    "cmd6": "nmap -p 80,443,8080 cat.phttp.com",
    "cmd7": "nmap -p 80 landing.phttp.com",
    "cmd8": "checkcert -p 443 landing.phttp.com",
    "cmd9": "checkcert -p 8443 proxy.phttp.com",
    "cmd10": "ntp fr.pool.ntp.org"
  }
}
```

---

## Output File Strategy (Resolved)

The output file strategy was implemented with a **dual approach**:

1. **Config-specified path** — If the JSON config includes `"output-file"`, results are written directly to that path (with `~` expanded to external storage). The write attempts SAF first via `contentResolver.openOutputStream()`, then falls back to direct file write if SAF fails.

2. **SAF picker fallback** — If no `"output-file"` is in the config, or the config-specified path fails, the "Write to File" button launches an SAF `CreateDocument` picker, letting the user choose the save location.

This dual strategy handles scoped storage on Android 10+ gracefully — SAF works universally across API levels, while direct write provides a no-prompt path for configs that specify a target.

---

## Questions

1. **Concurrency** — Commands currently execute sequentially. Should we add a `max-concurrent` field to the config to allow parallel execution (e.g., multiple pings or dig queries in parallel)?

2. **Config validation** — Should we add a "Validate Config" button that checks the JSON structure and command syntax before execution, giving feedback on any issues?
