# Implementation Plan: Bulk Actions `-t` (timeout) Argument

**Date:** 2026-04-26
**Status:** Planned

---

## Goal

Add a global `timeout` field (alias `-t`) to the Bulk Actions JSON config that sets a **per-command timeout** (in seconds). This lets users control how long each individual command is allowed to run before being cancelled, overriding the default 30-second per-command timeout in `executeCommands()`.

---

## Design Decisions

1. **Global per-config timeout** — A single `timeout` field at the root of the JSON config applies to every command. This is simpler than per-command timeouts and matches the existing pattern (`output-file` is also global).
2. **Unit: seconds (user-friendly)** — Users specify timeout in seconds (e.g., `"timeout": 60`). Internally converted to milliseconds.
3. **Default: 30s** — If `timeout` is absent or zero/negative, fall back to the existing 30-second default.
4. **Applied at execution level** — The timeout is passed to `executeCommands()` and used to parameterize `withTimeoutOrNull()` instead of the hardcoded 30_000.

### Commands that benefit from `-t`

| Command | Default behavior | `-t` effect |
|---|---|---|
| `ping` | Runs until `-c` count completes (can hang) | Cancels after timeout |
| `dig` | Uses `DigRepository` internal timeout | Overrides with shorter/longer |
| `ntp` | Uses `NtpRepository` internal timeout | Overrides with shorter/longer |
| `port-scan` | 2000ms per port (hardcoded in loop) | Cancels entire scan early |
| `checkcert` | Uses `HttpsCertRepository` internal timeout | Overrides with shorter/longer |
| `tracert` | `-W 2` per hop, up to 30 hops | Cancels entire trace early |
| `google-timesync` | Uses `GoogleTimeSyncRepository` internal timeout | Overrides with shorter/longer |
| `lan-scan` | Per-host ping timeout (repo internal) | Cancels entire scan early |
| `device-info` | Local (instant) | No practical effect |

---

## Files to Modify

### 1. `BulkConfig` data class (`BulkActionsRepository.kt`)

**Change:** Add `timeoutMs: Long?` field.

```kotlin
data class BulkConfig(
    val outputFile: String?,
    val commands: Map<String, String>,
    val timeoutMs: Long?,  // NEW
)
```

### 2. `BulkConfigParser.parse()` (`BulkActionsRepository.kt`)

**Change:** Extract optional `"timeout"` field from JSON, convert seconds → milliseconds.

```kotlin
// After extracting outputFile, before extracting run:
val timeoutMs = runCatching {
    val seconds = root.optLong("timeout", 0L)
    if (seconds == null || seconds <= 0) null
    else seconds * 1000L
}.getOrNull()

// Pass timeoutMs to BulkConfig constructor
return BulkConfig(outputFile, commands, timeoutMs)
```

### 3. `BulkActionsRepository.executeCommands()` (`BulkActionsRepository.kt`)

**Change:** Accept `configTimeoutMs` parameter and use it in `withTimeoutOrNull()`.

```kotlin
suspend fun executeCommands(
    config: BulkConfig,
    cancellationToken: AtomicBoolean = AtomicBoolean(false),
    onProgress: ((BulkProgress) -> Unit)? = null,
): List<BulkCommandResult> {
    val results = mutableListOf<BulkCommandResult>()
    val commands = config.commands.toList()
    val total = commands.size
    // Determine per-command timeout: config timeout or default 30s
    val commandTimeoutMs = config.timeoutMs ?: 30_000L

    commands.forEachIndexed { index, (name, cmd) ->
        if (cancellationToken.get()) return results

        onProgress?.invoke(BulkProgress(index, total, name, cmd))

        val result = withTimeoutOrNull(commandTimeoutMs) {
            executeSingleCommand(name, cmd)
        }

        val finalResult = result ?: BulkCommandTimeout(name, cmd)
        results.add(finalResult)
    }

    return results
}
```

### 4. `BulkActionsViewModel.kt`

**Change:** Pass `config.timeoutMs` through to the UI state and repository.

#### 4a. Add to `BulkUiState`

```kotlin
data class BulkUiState(
    // ... existing fields ...
    val configTimeoutMs: Long? = null,  // NEW
    // ... existing fields ...
)
```

#### 4b. In `onFileSelected()` / config loading

```kotlin
// After parsing config:
val updatedState = state.copy(
    // ... existing fields ...
    configTimeoutMs = config.timeoutMs,  // NEW
)
```

#### 4c. In `onRunClicked()` / execution

```kotlin
// When calling the repository:
val results = bulkRepo.executeCommands(
    config = parsedConfig,
    cancellationToken = running,
    onProgress = { progress -> /* ... */ }
)
// executeCommands reads config.timeoutMs internally, no extra param needed
```

### 5. `BulkActionsScreen.kt`

**Change:** Display timeout in the config loaded summary card.

In the "Config Loaded" card (where `commandCount` is shown), add a line:

```kotlin
if (state.configTimeoutMs != null) {
    Text("Timeout: ${state.configTimeoutMs / 1000}s per command")
}
```

Or combine into the summary:

```kotlin
val timeoutText = state.configTimeoutMs?.let { " · ${it / 1000}s timeout" } ?: ""
Text("${state.commandCount} commands$timeoutText")
```

### 6. Tests

#### 6a. `BulkConfigParserTest.kt` — Add timeout parsing tests

```kotlin
@Test
fun `parseConfigWithTimeout returns timeoutMs`() {
    val json = """
        {
            "timeout": 60,
            "run": {
                "cmd1": "ping -c 1 example.com"
            }
        }
    """.trimIndent()
    val config = parseBulkConfig(json)
    assertEquals(60_000L, config.timeoutMs)
}

@Test
fun `parseConfigWithoutTimeout returns null`() {
    val json = """
        {
            "run": {
                "cmd1": "ping -c 1 example.com"
            }
        }
    """.trimIndent()
    val config = parseBulkConfig(json)
    assertNull(config.timeoutMs)
}

@Test
fun `parseConfigWithZeroTimeout returns null (fallback to default)`() {
    val json = """
        {
            "timeout": 0,
            "run": {
                "cmd1": "ping -c 1 example.com"
            }
        }
    """.trimIndent()
    val config = parseBulkConfig(json)
    assertNull(config.timeoutMs)
}

@Test
fun `parseConfigWithNegativeTimeout returns null (fallback to default)`() {
    val json = """
        {
            "timeout": -10,
            "run": {
                "cmd1": "ping -c 1 example.com"
            }
        }
    """.trimIndent()
    val config = parseBulkConfig(json)
    assertNull(config.timeoutMs)
}
```

#### 6b. `BulkActionsViewModelTest.kt` — Add timeout flow test

```kotlin
@Test
fun `onFileSelected with timeout sets configTimeoutMs`() {
    val json = """
        {
            "timeout": 45,
            "run": {
                "cmd1": "ping -c 1 example.com"
            }
        }
    """.trimIndent()
    // ... set up file mock ...
    viewModel.onFileSelected(testUri, "test.json")
    assertThat(viewModel.state.value.configTimeoutMs).isEqualTo(45_000L)
}
```

### 7. Example config file (`notes/config-files_bulk-actions/`)

Create a new example:

```json
{
  "output-file": "~/Downloads/bulk-with-timeout.txt",
  "timeout": 60,
  "run": {
    "ping_google": "ping -c 4 google.com",
    "dig_cloudflare": "dig @1.1.1.1 example.com",
    "tracert_google": "tracert google.com",
    "port_scan_google": "port-scan -p 80,443 google.com",
    "ntp_pool": "ntp pool.ntp.org"
  }
}
```

---

## Execution Order

1. **Data model changes** — `BulkConfig` + `BulkConfigParser` (files 1-2)
2. **Repository changes** — `executeCommands()` timeout parameterization (file 3)
3. **ViewModel changes** — `BulkUiState` + state propagation (file 4)
4. **UI changes** — Display timeout in config summary (file 5)
5. **Tests** — Parser tests + ViewModel test (file 6)
6. **Example config** — New JSON example (file 7)

---

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Backward compatibility: existing configs without `timeout` | Parser returns `null`, falls back to 30s default — no behavior change |
| `optLong()` throws on non-numeric values | Wrapped in `runCatching`, returns `null` on failure → falls back to 30s |
| Very large timeout values (overflow) | `optLong()` max is `Long.MAX_VALUE` (~292 years); no practical concern |
| `withTimeoutOrNull` interaction with `CancellationException` | Existing code already handles this — no change needed |

---

## Verification Steps

1. Run `./gradlew test` — all existing tests pass + new timeout tests pass
2. Load a config with `"timeout": 60` — verify `configTimeoutMs` shows in UI
3. Load a config without `timeout` — verify default 30s behavior (no UI change)
4. Load a config with `"timeout": 0` — verify fallback to 30s default
5. Execute a slow command with `"timeout": 5` — verify command is cancelled after 5s
6. Build debug APK — `./gradlew assembleDebug` — no compile errors
