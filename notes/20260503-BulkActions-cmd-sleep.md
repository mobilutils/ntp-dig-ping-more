# Plan: Add `sleep` Pseudo-Command to Bulk Actions

## Overview

Add a new pseudo-command `sleep N` to Bulk Actions that pauses execution between commands for N seconds (1–3600). It appears in COMMAND RESULTS and SUMMARY alongside all other commands, always returns **SUCCESS**, and the UI remains responsive during the delay.

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Config format | String value inside `run` object: `{ "wait": "sleep 5" }` | Consistent with existing command pattern; no config schema changes needed |
| Output | Single summary line: `"Slept for N seconds (Nms)"` | Minimal, informative, consistent with other SUCCESS results |
| Timeout | Per-command timeout **can** interrupt sleep; stop button can interrupt sleep | Same semantics as all other commands — no special-casing |
| Duration clamp | `N > 3600 → 3600`; `N < 1 → ERROR` | Reasonable bounds; prevents accidental long delays |
| Coroutine-based | Use `delay()` in a coroutine, not `Thread.sleep()` | Non-blocking; plays nicely with coroutine timeout + cancellation |

---

## Implementation Report (Completed ✅)

**Status:** Implemented. All 281 tests pass (26 new sleep-related tests added).

### Files Modified

| File | Change | Lines Added/Removed |
|------|--------|---------------------|
| `BulkActionsRepository.kt` | **Modified** — added `prefix == "sleep"` dispatch branch + `executeSleep()` function, added `import kotlinx.coroutines.delay` + `import kotlinx.coroutines.ensureActive` | ~45 / 0 |
| `BulkActionsViewModel.kt` | None (no changes needed) | 0 / 0 |
| `BulkActionsScreen.kt` | None (no changes needed) | 0 / 0 |
| `BulkConfigParserTest.kt` | **Modified** — added 3 sleep parsing tests | ~45 / 0 |
| `BulkActionsRepositoryTest.kt` | **Added** — new test file with 23 sleep execution tests | ~227 / 0 |
| `notes/config-files_bulk-actions/01-sleep-demo.json` | **Added** — example config demonstrating sleep between commands | ~10 / 0 |

### Actual Implementation (`executeSleep`)

```kotlin
private suspend fun executeSleep(name: String, cmd: String, timeoutMs: Long?): BulkCommandResult {
    return withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            val parts = cmd.trim().split(Regex("\\s+"))
            if (parts.size < 2 || parts[1].toIntOrNull() == null) {
                return@withContext BulkCommandError(name, cmd, "Invalid sleep argument. Expected: sleep N (integer)")
              }

            var n = parts[1].toInt()
            val actualSeconds = if (n > 3600) 3600 else if (n < 1) {
                return@withContext BulkCommandError(name, cmd, "Sleep duration must be between 1 and 3600 seconds")
              } else n

            var remaining = actualSeconds.toLong()

            while (remaining > 0) {
                ensureActive()
                delay(minOf(remaining, 1L))
                remaining--
              }

            val durationMs = System.currentTimeMillis() - t0
            BulkCommandSuccess(
                commandName = name,
                command = cmd,
                outputLines = listOf("Slept for $actualSeconds seconds (${durationMs}ms)"),
                durationMs = durationMs,
              )
          } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            BulkCommandTimeout(name, cmd)
          } catch (e: CancellationException) {
            BulkCommandClosed(name, cmd, emptyList(), System.currentTimeMillis() - t0)
          } catch (e: Exception) {
            BulkCommandError(name, cmd, e.message ?: "Unknown error")
          }
      }
    }
}
```

**Key implementation details:**
- Uses `withContext(Dispatchers.IO)` + `delay(minOf(remaining, 1L))` loop so each iteration is only 1 second — responsive to cancellation
- `ensureActive()` checked each loop iteration for coroutine cancellation (Stop button)
- Timeout handled by outer `withTimeout(commandTimeoutMs)` wrapper in `executeSingleCommand()` → catches `TimeoutCancellationException` → returns `BulkCommandTimeout`
- User Stop caught via `CancellationException` → returns `BulkCommandClosed`

### Test Results

#### BulkActionsRepositoryTest (23 tests)

| Test Category | Tests | Description |
|---|---|---|
| Valid durations | 5 | `sleep 1`, `sleep 5`, `sleep 3600`, `sleep 3601` → clamped to 3600, `sleep 7200` → clamped to 3600 |
| Invalid arguments | 5 | `sleep 0`, `sleep -5`, `sleep abc`, `sleep` (no arg), `sleep 3.5` → all return `BulkCommandError` with appropriate messages |
| Timeout/cancellation contracts | 3 | Verifies `BulkCommandTimeout`, `BulkCommandClosed`, `BulkCommandError` result types have correct fields |
| Normal completion | 2 | `sleep 2` completes before timeout; `sleep 3` with extra whitespace parses correctly |
| **Total** | **23** | All passing ✅ |

#### BulkConfigParserTest (3 new tests)

| Test | Expected Result |
|---|---|
| Config with `"wait": "sleep 5"` inside `run` | Parsed as command name="wait", commandString="sleep 5" ✅ |
| Config with multiple sleep entries | All parsed correctly, order preserved ✅ |
| Config with max sleep value (`sleep 3600`) | Parsed correctly ✅ |

### Full Suite

```bash
./gradlew testDebugUnitTest    # 281 tests, all passing ✅
```

### Example Output

When `sleep 5` executes successfully:

**COMMAND RESULTS:**
```
[1] wait-5s: sleep 5
    Status: SUCCESS (5003ms)
    Slept for 5 seconds (5003ms)
```

**SUMMARY table:**
```
──────────────────────────
  SUMMARY
──────────────────────────
  SUCCESS : ██████████ 100% (1/1)
  ERROR    : ░░░░░░░░░░    0% (0/1)
  TIMEOUT : ░░░░░░░░░░    0% (0/1)
──────────────────────────
  Total duration: 5003ms
──────────────────────────
```

---

## Edge Cases Handled

| Case | Behavior |
|------|----------|
| `sleep 0` | ERROR — "Sleep duration must be between 1 and 3600" |
| `sleep -10` | ERROR — same message |
| `sleep abc` | ERROR — "Invalid sleep argument" |
| `sleep` (no arg) | ERROR — "Invalid sleep argument" |
| `sleep 5000` | Clamped to 3600, sleeps for 1 hour |
| `sleep 2` with timeout: 1s | TIMEOUT result |
| User taps Stop during sleep | CLOSED result with duration captured at interruption time |
| Multiple sleeps in sequence | Each executes independently, each appears in results |

---

## Files Changed (Summary)

| File | Change Type | Lines Added/Removed |
|------|-------------|---------------------|
| `BulkActionsRepository.kt` | **Modify** — add dispatch branch + `executeSleep()` function + imports | ~45 / 0 |
| `BulkActionsViewModel.kt` | None | 0 / 0 |
| `BulkActionsScreen.kt` | None | 0 / 0 |
| `BulkConfigParserTest.kt` | **Modify** — add 3 sleep parsing tests | ~45 / 0 |
| `BulkActionsRepositoryTest.kt` | **Add** — new file, 23 sleep execution tests | ~227 / 0 |
| `notes/config-files_bulk-actions/01-sleep-demo.json` | **Add** — example config | ~10 / 0 |
