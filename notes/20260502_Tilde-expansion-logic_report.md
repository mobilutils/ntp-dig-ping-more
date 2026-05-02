# Tilde (`~`) Expansion in Bulk Actions — Technical Report

> **Date:** 2026-05-02
> **Scope:** `BulkConfigParser.expandTilde()` and all downstream consumers
> **Purpose:** Explain the tilde-expansion mechanism for developers new to the Bulk Actions feature.

---

## 1. Why Tilde Expansion Exists

On Android (API 26+), writing to arbitrary filesystem paths is heavily restricted. Paths like `~/Downloads/output.txt` would fail because the app has no write access to the user's public Downloads directory.

The solution: when a config's `output-file` field starts with `~/`, the app rewrites the path to use its **own private files directory** instead. This directory (`/data/user/0/<package>/files`) is always writable without any runtime permissions.

---

## 2. The Core Function

The expansion logic lives in `BulkConfigParser`, a companion object inside `BulkActionsRepository.kt`:

```kotlin
/** Expands `~` to the app's private files directory (no permissions needed on SDK 33+). */
private fun expandTilde(path: String): String {
    if (!path.startsWith("~/")) return path
    val privateDir = appContext?.applicationContext?.filesDir?.absolutePath
          ?: Environment.getExternalStorageDirectory().absolutePath
    return "$privateDir${path.substring(1)}"
}
```

### Step-by-step breakdown

| Step | What happens |
|------|-------------|
| **1. Prefix check** | If the path does **not** start with `~/`, it is returned unchanged. This means absolute paths (`/sdcard/…`) and relative paths (`output.txt`) pass through untouched. |
| **2. Resolve base directory** | The function reads `BulkConfigParser.appContext` (a `Context` set once in the ViewModel factory — see §5). It calls `applicationContext.filesDir.absolutePath`, which on Android yields something like `/data/user/0/io.github.mobilutils.ntp_dig_ping_more/files`. |
| **3. Fallback** | If `appContext` is `null` (e.g. during JVM unit tests where no Android `Context` exists), it falls back to `Environment.getExternalStorageDirectory().absolutePath` — typically `/storage/emulated/0`. |
| **4. Reconstruct** | It strips the leading `~` from the original path using `path.substring(1)` and concatenates: `privateDir + "/Downloads/output.txt"` → `/data/user/0/.../files/Downloads/output.txt`. |

### Key property

**`~` is only expanded for `output-file` paths, never for command arguments.** A command like `ping -c 4 ~/host` is left unchanged — only the `output-file` JSON field triggers expansion.

---

## 3. Where Expansion Is Triggered

Tilde expansion is invoked at **two points** in the parsing pipeline, both inside `BulkConfigParser.parse()`:

```kotlin
fun parse(json: String): BulkConfig {
    val root = JSONObject(json)

    val outputFile = runCatching {
        val path = root.optString("output-file", "")
        if (path.isNullOrBlank()) null
        else expandTilde(path)    // ← expansion happens here
    }.getOrNull()

    // ... rest of parsing (timeout, commands, csv flag) ...

    return BulkConfig(outputFile, commands, timeoutMs, outputAsCsv)
}
```

The `runCatching` / `getOrNull()` wrapper ensures that if `expandTilde` throws (e.g. `appContext` is null and external storage is unavailable), the parse continues with `outputFile = null` rather than failing entirely.

---

## 4. Diagram: Full Tilde-Expansion Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  User selects a JSON config file via the file picker            │
│  (or ADB automation passes a URI)                               │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  BulkActionsViewModel.onFileSelected() / onLoadAndRun()          │
│  • Reads file bytes → decodes to String (JSON)                  │
│  • Calls BulkConfigParser.parse(json)                           │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  BulkConfigParser.parse(json)                                    │
│                                                                  │
│  1. Extract "output-file" from JSON                             │
│  2. If present and non-blank → call expandTilde(path)           │
│                                                                  │
│  expandTilde(path):                                              │
│    ├─ Does path start with "~/"?                                │
│    │   ├─ NO  → return path unchanged                          │
│    │   └─ YES → continue                                       │
│    ├─ Read appContext?.filesDir?.absolutePath                   │
│    │   ├─ Found → use it                                       │
│    │   └─ Null  → fallback to Environment.getExternalStorageDir()│
│    └─ Return: privateDir + path.substring(1)                    │
│                                                                  │
│  3. Return BulkConfig(outputFile = <expanded path>, ...)        │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Back in ViewModel: config.outputFile is now an expanded path    │
│                                                                  │
│  ┌─ Validation path (immediately after parse) ─────────────────┐ │
│  │  BulkConfigParser.validateOutputFile(rawPath)               │ │
│  │    ├─ Calls expandTilde(rawPath) again to get expanded path │ │
│  │    ├─ Attempts to create parent dirs + test file            │ │
│  │    └─ Returns Valid(path) or Invalid(path, suggested)       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌─ Execution path (after user taps "Run") ────────────────────┐ │
│  │  onRunClicked() / onLoadAndRun()                            │ │
│  │    ├─ Execute commands sequentially                         │ │
│  │    └─ After all commands: autoSaveResults(outputPath, ...)  │ │
│  │         ├─ writeViaSAF(path) — only works for URI syntax    │ │
│  │         └─ writeDirect(path) — fallback for plain paths     │ │
│  │              └─ File(path).parentFile?.mkdirs()              │ │
│  │              └─ File(path).writeText(content)               │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Output file is written to:                                      │
│  /data/user/0/io.github.mobilutils.ntp_dig_ping_more/            │
│    files/Downloads/output.txt   (if original was ~/Downloads/…)  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. Prerequisite: `appContext` Must Be Set

The `expandTilde` function accesses a **static volatile field**:

```kotlin
object BulkConfigParser {

    @Volatile
    internal var appContext: Context? = null

    private fun expandTilde(path: String): String {
        val privateDir = appContext?.applicationContext?.filesDir?.absolutePath
              ?: Environment.getExternalStorageDirectory().absolutePath
        // ...
    }
}
```

This field is set **exactly once** in the ViewModel factory:

```kotlin
companion object {
    fun factory(context: Context): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                // Set app context for BulkConfigParser to use private dir path expansion
                BulkConfigParser.appContext = context.applicationContext
                return BulkActionsViewModel(
                    context = context.applicationContext,
                    repository = BulkActionsRepository(context.applicationContext),
                ) as T
            }
        }
}
```

### Implications

| Scenario | `appContext` value | Result of `expandTilde("~/foo")` |
|----------|-------------------|----------------------------------|
| Normal app use (UI) | Non-null | `/data/user/0/.../files/foo` |
| ADB automation (ViewModel created via factory) | Non-null | `/data/user/0/.../files/foo` |
| JVM unit test (no Android `Context`) | `null` | `/storage/emulated/0/foo` (fallback) |

If the factory is never called before `parse()` is invoked, `appContext` is `null` and the fallback path is used.

---

## 6. Downstream Consumers

### 6.1 Config Validation (`validateOutputFile`)

After parsing, the ViewModel calls `BulkConfigParser.validateOutputFile(rawPath)` with the **raw** (pre-expansion) path:

```kotlin
fun validateOutputFile(rawPath: String): OutputFileValidationResult {
    val expanded = expandTilde(rawPath)       // expand again

    val parent = File(expanded).parentFile
    val canCreateDirs = parent?.mkdirs() == true || parent?.exists() == true
    if (!canCreateDirs) {
        val suggested = suggestFallbackPath(rawPath)
        return OutputFileValidationResult.Invalid(rawPath, suggested)
    }

    val testFile = File(expanded)
    val canWrite = try {
        testFile.createNewFile() && testFile.canWrite()
    } catch (_: Exception) {
        false
    }

    return if (canWrite) {
        runCatching { testFile.delete() }
        OutputFileValidationResult.Valid(expanded)
    } else {
        val suggested = suggestFallbackPath(rawPath)
        OutputFileValidationResult.Invalid(rawPath, suggested)
    }
}
```

**What this means:** The same `expandTilde` function is called **twice** for the same path — once during `parse()` and once during `validateOutputFile()`. Both calls should produce identical results as long as `appContext` hasn't changed (which it doesn't).

### 6.2 Auto-Save After Execution

After all commands finish, if `config.outputFile` is not null:

```kotlin
if (config.outputFile != null) {
    val outputPath = _uiState.value.validatedOutputFile ?: config.outputFile
    val saved = autoSaveResults(outputPath, allResults)
}
```

The `autoSaveResults` function tries SAF first, then falls back to direct file writing:

```kotlin
private suspend fun autoSaveResults(outputPath: String, results: List<BulkCommandResult>): Boolean {
    return try {
        val success = writeViaSAF(outputPath, results)
        if (!success) {
            writeDirect(outputPath, results)
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }
}

private suspend fun writeViaSAF(path: String, results: List<BulkCommandResult>): Boolean {
    val uri = android.net.Uri.parse(path)
    // Plain file paths (not file:// URIs) skip SAF and fall through to writeDirect
    if (uri == null) return false
    // ... SAF write ...
}

private suspend fun writeDirect(path: String, results: List<BulkCommandResult>): Boolean {
    return withContext(Dispatchers.IO) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    }
}
```

Since the expanded path (e.g. `/data/user/0/.../files/Downloads/output.txt`) is a **plain file path** (not a `file://` URI), `writeViaSAF` returns `false` and `writeDirect` handles the actual write. This works because the private files directory is always writable.

### 6.3 Manual Output File Write (`onWriteOutputFile`)

When the user taps the export button in the UI, `onWriteOutputFile()` is called. It uses `writeViaSAF` with a SAF picker, so tilde expansion is irrelevant here — the user chooses the destination.

### 6.4 ADB Automation Script (`BULKACTIONS-ADB-SCRIPT.sh`)

The shell script that automates bulk actions via ADB mirrors the same tilde expansion logic:

```bash
# FIX 5: Expand ~ in output-file to app's private dir
# (same as BulkConfigParser.expandTilde)
OUTPUT_FILE=$(echo "$OUTPUT_FILE" | sed 's|^~/|'"$FILES_DIR"'/|')
```

Where `FILES_DIR` is set to the app's private files directory via:
```bash
FILES_DIR=$(adb shell -e "run-as $PACKAGE_NAME echo \$FILES_DIR" 2>/dev/null || echo "/data/data/$PACKAGE_NAME/files")
```

This ensures the ADB script and the app itself resolve `~` to the same directory.

---

## 7. Edge Cases and Behaviors

| Scenario | Behavior |
|----------|----------|
| Path starts with `~` but not `~/` (e.g. `~host`) | **Not expanded** — returned unchanged. Only the exact prefix `~/` triggers expansion. |
| `output-file` is empty or missing | `outputFile` in `BulkConfig` is `null`; no expansion or file writing occurs. |
| `output-file` is an absolute path (e.g. `/sdcard/…`) | **Not expanded** — returned unchanged. Validation may fail if the path isn't writable. |
| `output-file` is a relative path (e.g. `output.txt`) | **Not expanded** — returned unchanged. Treated as a plain file path. |
| `appContext` is `null` at expansion time | Falls back to `/storage/emulated/0` (external storage root). This typically happens in JVM unit tests. |
| Parent directories don't exist | `writeDirect` calls `parentFile?.mkdirs()` before writing, so intermediate directories are created automatically. |
| Test file cleanup in validation | `validateOutputFile` creates a zero-byte test file to verify writability, then deletes it immediately. |

---

## 8. Test Coverage

The test file `BulkConfigParserTest.kt` includes one test specifically for tilde expansion:

```kotlin
@Test
fun `parseTildePath preserves tilde in JVM tests`() {
    val json = """
        {
            "output-file": "~/Downloads/test-run.txt",
            "run": {
                "cmd1": "ping -c 1 example.com"
            }
        }
    """.trimIndent()

    val config = parseBulkConfig(json)

    // In JVM tests without Android APIs, tilde is preserved as-is
    // In production (Android), it would be expanded to external storage dir
    assertTrue(config.outputFile?.startsWith("~/") == true)
    assertTrue(config.outputFile?.contains("Downloads/test-run.txt") == true)
}
```

This test uses a **minimal pure-Kotlin parser** that mirrors `BulkConfigParser.parse()` but does **not** call `expandTilde()`. The test verifies that the tilde is preserved in the raw string, confirming the test infrastructure works. The comment clarifies that actual expansion only happens in production with a real Android `Context`.

No additional unit tests exist for `expandTilde` itself, `validateOutputFile`, or the fallback behavior when `appContext` is null.

---

## 9. Summary

| Aspect | Detail |
|--------|--------|
| **Trigger** | `output-file` field in JSON config starts with `~/` |
| **Expansion target** | `Context.applicationContext.filesDir.absolutePath` (private app directory) |
| **Fallback** | `Environment.getExternalStorageDirectory().absolutePath` (`/storage/emulated/0`) |
| **Called from** | `parse()` (once), `validateOutputFile()` (once), ADB script (mirrored) |
| **Scope** | `output-file` paths only — never command arguments |
| **Prefix requirement** | Must start with exactly `~/` (not just `~`) |
| **Thread safety** | `appContext` is `@Volatile` for safe visibility across threads |
| **Test coverage** | One JVM test confirms tilde preservation; no direct unit tests for the expansion logic itself |
