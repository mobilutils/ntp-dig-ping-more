---
name: Load Proxy PAC from Local File (v4)
description: Plan for adding local file support to proxy PAC configuration in SettingsScreen, BulkActions JSON configs, GoogleTimeSyncViewModel, and HttpsCertViewModel. Includes Settings file copy-to-private-storage, Context injection into all ViewModels, FileSystemIO abstraction, mutual exclusion validation, and parse-time skip flag.
type: project
---

# Plan: Load Proxy PAC from Local File (v4)

## Goal

1. **SettingsScreen**: Allow users to load a PAC script from a local file via SAF picker. The selected file content is **copied** into the app's private storage on selection, then the internal path is stored and restored across sessions. Segmented toggle (URL/File) switches between sources.
2. **BulkActions JSON**: Add `file-proxypac` field supporting absolute filesystem paths with tilde (`~`) expansion to app's private files directory. Validate at parse time that the file exists (skippable in unit tests via `skipFileValidation`). Throw an error if both `url-proxypac` and `file-proxypac` are present (mutually exclusive).
3. **GoogleTimeSyncViewModel & HttpsCertViewModel**: Both must work with proxy PAC files as well as PAC URLs. Inject `Context` into their constructors so `ProxyResolver` has access to the filesystem.

---

## Design Decisions (v4)

### 1. ProxyResolver — Option B: New Primary Constructor + Deprecated Companion Factory

**Decision**: Add `Context? appContext = null` as the new last parameter to the **existing primary constructor**. The old call pattern (`ProxyResolver(repo, jsEngine, logger = ...)`) continues working with `appContext = null`. For a clean migration path, add a deprecated companion factory `withoutFileSupport()` that existing call sites can use instead.

```kotlin
class ProxyResolver(
    private val settingsRepository: SettingsRepository,
    private val jsEngine: JsEngine,
    private val staticPacUrl: String? = null,
    private val logger: ProxyPacLogger? = null,
    private val forceLogging: Boolean = false,
    private val appContext: Context? = null,     // NEW — for file-based PAC
) {
      // ... all existing code

    companion object {
          // ... existing forStaticPacUrl() stays unchanged (passes context → enables file + URL)

          /**
           * Creates a ProxyResolver without file-based PAC support.
           * Use this to migrate existing call sites; eventually remove after migration.
           */
          @Deprecated(
               message = "Use constructor with Context for file-based PAC, or forStaticPacUrl()",
               replaceWith = ReplaceWith(
                    "ProxyResolver(settingsRepository, jsEngine, staticPacUrl, logger, forceLogging, appContext)",
                    "io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver"
                )
          )
          fun withoutFileSupport(
              settingsRepository: SettingsRepository,
              jsEngine: JsEngine,
              staticPacUrl: String? = null,
              logger: ProxyPacLogger? = null,
              forceLogging: Boolean = false,
           ): ProxyResolver = ProxyResolver(
              settingsRepository = settingsRepository,
              jsEngine = jsEngine,
              staticPacUrl = staticPacUrl,
              logger = logger,
              forceLogging = forceLogging,
              appContext = null,
           )
       }
}
```

**Why**: The new primary constructor is the single source of truth. Existing call sites continue working without changes (defaulting to `appContext = null`). When ready to migrate, each site switches to `ProxyResolver.withoutFileSupport(repo, jsEngine, ...)` which delegates with `appContext = null`. New code that needs file access uses the primary constructor directly or `forStaticPacUrl()`.

---

### 2. SettingsScreen — Segmented Toggle + Single Field + Copy-to-Private-Storage

**Decision**: A segmented chip/button group at the top of the Proxy Configuration section: `[ URL | File ]`, with the active selection highlighted. The PAC URL field below changes its placeholder and supporting text based on mode.

```
┌─────────────────────────────────────┐
│ Enable Proxy     [Switch]                │
│ Route traffic through a PAC proxy      │
├─────────────────────────────────────┤
│ [ URL ● | File ○ ]                     │
├─────────────────────────────────────┤
│ PAC URL                                │
│ ┌──────────────────────────────────┐│
│ │ http://proxy.corp.com/proxy.pac     ││
│ └──────────────────────────────────┘│
│ URL to an auto-configuration (.pac) script                       │
└─────────────────────────────────────┘
```

When "File" is selected:
```
┌─────────────────────────────────────┐
│ Enable Proxy     [Switch]                │
│ Route traffic through a PAC proxy      │
├─────────────────────────────────────┤
│ [ URL ○ | File ● ]                     │
├─────────────────────────────────────┤
│ PAC File                               │
│ ┌──────────────────────────────────┐│
│ │ /data/user/0/app/files/saved-pac.pac││
│ └──────────────────────────────────┘│
│ Local file: saved-pac.pac              │
└─────────────────────────────────────┘
```

**Copy-to-Private-Storage Decision**: When user selects a file via SAF picker, the file content is **copied** into the app's private files directory (e.g., `context.openFileOutput("saved-pac.pac", Context.MODE_PRIVATE)`) and the internal path (`context.filesDir.resolve("saved-pac.pac").absolutePath`) is stored. This guarantees persistence regardless of SAF provider (Google Drive, Downloads, cloud-backed storage).

**Why**: SAF URIs like `content://com.android.externalstorage...` often return non-useable or non-persistent paths from `.path`. Files pushed via ADB to `/sdcard/Downloads/proxy.pac` work with direct path storage, but cloud-backed files may not survive reboots. Copying ensures reliability across all providers.

---

### 3. SettingsScreen — Toggle as State in ViewModel

**Decision**: Add `pacSourceMode: PacSourceMode` to `SettingsUiState`, where `PacSourceMode` is an `enum class` with values `URL` and `FILE`. The UI reads this state to show/hide the Browse button and adjust labels/placeholders.

---

### 4. SettingsScreen — File Mode Copies to Private Storage

**Decision**: When user selects a file via SAF picker:
- Copy the file content into app's private files dir (`context.openFileOutput("saved-pac.pac", Context.MODE_PRIVATE)`)
- Store the internal path (`context.filesDir.resolve("saved-pac.pac").absolutePath`) in `proxyPacUrl` field
- On subsequent loads, restore from this internal path — always accessible via `openFileInput()` or `File(path)`

**Why**: Absolute paths work across app sessions and reboots for files in private storage. SAF URIs expire. Copying to private storage is the most robust approach for all file sources (ADB-pushed, cloud-backed, Downloads).

---

### 5. BulkActions — `file-proxypac` as Absolute Path with ~ Expansion to App Files Dir

**Decision**: `file-proxypac` stores a filesystem path string. Supports tilde (`~`) expansion to the app's private files directory (same mechanism as existing `expandTilde()` for `output-file`). Validate at parse time that the resolved file exists and is readable. Throw a validation error if not.

**No tilde expansion in Settings file mode** — if a user types `~/proxy.pac` in the Settings file field, it stays literal (no expansion). Only BulkActions JSON configs get tilde expansion.

**Skip File Validation Flag**: Add `@Volatile internal var skipFileValidation: Boolean = false` to `BulkConfigParser` (default `false`). When set to `true`, unit tests can skip file existence checks without needing to create temp files on disk.

---

### 6. BulkActions — Mutual Exclusion Validation

**Decision**: If both `url-proxypac` and `file-proxypac` are present and non-blank in the same JSON config, `BulkConfigParser.parse()` throws an `IllegalArgumentException` with message: `"Cannot specify both 'url-proxypac' and 'file-proxypac'. They are mutually exclusive."`. This is caught by the ViewModel's loading flow and shown as a `ValidationMessage.Error`.

---

### 7. ProxyResolver — Unified Fetch Method with FileSystemIO Abstraction

**Decision**:

a) Add a `FileSystemIO` interface abstracting file operations:
```kotlin
/**
 * Abstracts file system operations for PAC script loading.
 *
 * Enables unit testing by allowing a mock implementation that returns
 * fake PAC content without touching the real filesystem.
 */
interface FileSystemIO {
    fun canRead(path: String): Boolean
    fun isFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun readText(path: String): String
}

/** Default production implementation wrapping [java.io.File]. */
internal object DefaultFileSystemIO : FileSystemIO {
    override fun canRead(path: String) = java.io.File(path).canRead()
    override fun isFile(path: String) = java.io.File(path).isFile
    override fun exists(path: String) = java.io.File(path).exists()
    override fun readText(path: String) = java.io.File(path).readText(Charsets.UTF_8)
}
```

b) Add `fetchPacFromFile(filePath: String, fs: FileSystemIO): String?` that uses the abstraction. Production code passes `DefaultFileSystemIO`; tests inject a mock via `mockk()` that returns fake PAC script content without touching the real filesystem.

**Visibility**: `fetchPacFromFile()` is `internal` (not `private`) with `@visibleForTesting` KDoc, so unit tests can invoke it directly via reflection or direct access within the same module.

c) The existing `resolveProxy()` routing logic expands to three cases:
- `http://` or `https://` → `fetchPacScript()` (HTTP GET)
- Absolute file path (no scheme prefix) → `fetchPacFromFile()` (local read via FileSystemIO)
- No Context available → log failure, return null

The effective PAC source is determined by checking the first characters: if it starts with a scheme (`http://`, `https://`), use HTTP; otherwise treat as filesystem path.

---

### 8. GoogleTimeSyncViewModel & HttpsCertViewModel — Accept Context in Constructor

**Decision**: Both ViewModels currently create `ProxyResolver` inline in their companion factory without passing `Context`. To support file-based PAC, they must accept `Context?` in their primary constructor and pass it through to `ProxyResolver`.

```kotlin
class GoogleTimeSyncViewModel(
    private val repository    : GoogleTimeSyncRepository     = GoogleTimeSyncRepository(),
    private val historyStore  : GoogleTimeSyncHistoryStore,
    private val settingsRepository: SettingsRepository,
    private val appContext    : Context? = null,       // NEW — for file-based PAC
) : ViewModel() { ... }

class HttpsCertViewModel(
    private val repository    : HttpsCertRepository,
    private val historyStore  : HttpsCertHistoryStore,
    private val appContext    : Context? = null,         // NEW — for file-based PAC
) : ViewModel() { ... }
```

**Factory updates**: Both factories pass `context.applicationContext` as the new param:

```kotlin
// GoogleTimeSyncViewModel.factory()
fun factory(context: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            val settingsRepo = SettingsRepository(appContext)
            val logger = ProxyPacLogger.getInstance(
                logFile = java.io.File(appContext.filesDir, "proxypac-logs.txt"),
             )
            val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
            return GoogleTimeSyncViewModel(
                repository    = GoogleTimeSyncRepository(proxyResolver),
                historyStore  = GoogleTimeSyncHistoryStore(appContext),
                settingsRepository = settingsRepo,
                appContext    = appContext,       // NEW
             ) as T
         }
     }

// HttpsCertViewModel.factory()
fun factory(context: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            val settingsRepo = SettingsRepository(appContext)
            val logger = ProxyPacLogger.getInstance(
                logFile = java.io.File(appContext.filesDir, "proxypac-logs.txt"),
             )
            val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
            return HttpsCertViewModel(
                repository    = HttpsCertRepository(proxyResolver),
                historyStore  = HttpsCertHistoryStore(appContext),
                appContext    = appContext,       // NEW
             ) as T
         }
     }
```

**Why**: Both ViewModels need `Context` so that `ProxyResolver` can access the filesystem when resolving proxy settings from a file path. The existing `factory(context)` already receives `Context`, so this is just threading it through to the constructor. No composable changes needed — `viewModel(factory = ...)` pattern is unchanged.

---

## Implementation Plan

### Step 1: `proxy/ProxyResolver.kt` — New constructor param, FileSystemIO, file reading + routing

**1a. Add `FileSystemIO` interface and default impl** (near top of file, before class):

```kotlin
/**
 * Abstracts file system operations for PAC script loading.
 *
 * Enables unit testing by allowing a mock implementation that returns
 * fake PAC content without touching the real filesystem.
 */
interface FileSystemIO {
    fun canRead(path: String): Boolean
    fun isFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun readText(path: String): String
}

/** Default production implementation wrapping [java.io.File]. */
internal object DefaultFileSystemIO : FileSystemIO {
    override fun canRead(path: String) = java.io.File(path).canRead()
    override fun isFile(path: String) = java.io.File(path).isFile
    override fun exists(path: String) = java.io.File(path).exists()
    override fun readText(path: String) = java.io.File(path).readText(Charsets.UTF_8)
}
```

**1b. Add `Context?` as new last parameter to primary constructor + deprecated companion factory**:

Replace the existing class signature (add `appContext: Context? = null` as last parameter):

```kotlin
class ProxyResolver(
    private val settingsRepository: SettingsRepository,
    private val jsEngine: JsEngine,
    private val staticPacUrl: String? = null,
    private val logger: ProxyPacLogger? = null,
    private val forceLogging: Boolean = false,
    private val appContext: Context? = null,     // NEW — for file-based PAC
) {
      // ... all existing code

    companion object {
          // ... existing forStaticPacUrl() stays unchanged (passes context → enables file + URL)

          /**
           * Creates a ProxyResolver without file-based PAC support.
           * Use this to migrate existing call sites; eventually remove after migration.
           */
          @Deprecated(
               message = "Use constructor with Context for file-based PAC, or forStaticPacUrl()",
               replaceWith = ReplaceWith(
                    "ProxyResolver(settingsRepository, jsEngine, staticPacUrl, logger, forceLogging, appContext)",
                    "io.github.mobilutils.ntp_dig_ping_more.proxy.ProxyResolver"
                )
          )
          fun withoutFileSupport(
              settingsRepository: SettingsRepository,
              jsEngine: JsEngine,
              staticPacUrl: String? = null,
              logger: ProxyPacLogger? = null,
              forceLogging: Boolean = false,
           ): ProxyResolver = ProxyResolver(
              settingsRepository = settingsRepository,
              jsEngine = jsEngine,
              staticPacUrl = staticPacUrl,
              logger = logger,
              forceLogging = forceLogging,
              appContext = null,
           )
       }
}
```

**Then update existing call sites to use `withoutFileSupport()`**:

| File | Current Call | New Call |
|---|---|---|
| `SettingsViewModel.kt:331` | `ProxyResolver(settingsRepository, jsEngine, logger = logger)` | **MIGRATE** — pass `appContext = context.applicationContext` (needed for Settings file mode) |
| `GoogleTimeSyncViewModel.kt:187` | `ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)` | **MIGRATE** — accept `Context?` in constructor, pass to ProxyResolver primary ctor |
| `HttpsCertViewModel.kt:222` | `ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)` | **MIGRATE** — accept `Context?` in constructor, pass to ProxyResolver primary ctor |
| `ProxyResolverTest.kt:38` | `ProxyResolver(settingsRepository, jsEngine)` | `ProxyResolver.withoutFileSupport(settingsRepository, jsEngine)` |

SettingsScreen is the one that needs migration because it's the only screen where users actively select PAC files. GoogleTimeSync and HttpsCert are also migrated (Step 8) to support file-based proxy for their commands.

**1c. Add `fetchPacFromFile()` method** (`internal` with `@visibleForTesting`):

```kotlin
/**
 * Fetches a PAC script from an absolute filesystem path.
 * Uses the same caching contract as fetchPacScript().
 *
 * @param filePath Absolute path to the .pac file.
 * @param fs       FileSystemIO abstraction (default: [DefaultFileSystemIO]).
 * @return The PAC script content, or `null` if the file is inaccessible.
 */
@VisibleForTesting
internal fun fetchPacFromFile(
    filePath: String,
    fs: FileSystemIO = DefaultFileSystemIO,
): String? {
    if (!fs.exists(filePath) || !fs.isFile(filePath) || !fs.canRead(filePath)) {
        logIfEnabled("PAC_FETCH_FAIL path=$filePath reason=file not accessible")
        return null
     }

    val now = System.currentTimeMillis()
    if (cachedPacScript != null && cachedPacUrl == filePath &&
        now - pacFetchedAt < PAC_CACHE_TTL_MS
     ) {
        return cachedPacScript
     }

    return try {
        val script = fs.readText(filePath)

        cachedPacScript = script
        cachedPacUrl = filePath
        pacFetchedAt = now
        logIfEnabled("PAC_FETCH_SUCCESS path=$filePath")
        script
     } catch (e: Exception) {
        logIfEnabled("PAC_FETCH_FAIL path=$filePath reason=${e.message}")
        null
     }
}
```

**1d. Update `resolveProxy()` routing** — replace the single fetch call with scheme-based dispatch:

Replace this block in `resolveProxy()`:
```kotlin
// Fetch (or use cached) PAC script
val pacScript = fetchPacScript(effectivePacUrl) ?: return@withContext null
```

With:
```kotlin
// Fetch (or use cached) PAC script — dispatch by source type
val pacScript = when {
    effectivePacUrl.startsWith("http://") || effectivePacUrl.startsWith("https://") ->
        fetchPacScript(effectivePacUrl)
    else ->
         // Treat as filesystem path — requires Context for file access
        if (appContext != null) {
            fetchPacFromFile(effectivePacUrl)
         } else {
            logIfEnabled("PAC_FETCH_FAIL reason=no Context for file access")
            null
         }
} ?: return@withContext null
```

**1e. No changes needed to `forStaticPacUrl()` factory** — it already passes `context` as the new `appContext` param, which enables both URL and file-based PAC.

---

### Step 2: Create `PacSourceMode.kt` — enum class

**New file**: `app/src/main/java/io/github/mobilutils/ntp_dig_ping_more/PacSourceMode.kt`

```kotlin
package io.github.mobilutils.ntp_dig_ping_more

/** Enum of supported PAC script sources. */
enum class PacSourceMode {
     /** HTTP/HTTPS URL (existing behavior). */
    URL,

     /** Local filesystem path (private storage or absolute path). */
    FILE;

    companion object {
         /** Default source mode for new Settings screens. */
        val Default = URL
     }
}
```

---

### Step 3: `SettingsViewModel.kt` — Add source mode tracking + file selection + copy-to-storage

**3a. Add imports**:
```kotlin
import android.net.Uri
import java.io.File
```

**3b. Accept `Context?` in constructor** (migrate from `withoutFileSupport()` pattern):

Replace:
```kotlin
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() { ... }
```

With:
```kotlin
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context? = null,   // NEW — for file-based PAC (copy-to-storage)
) : ViewModel() { ... }
```

**Factory update**: Pass `context.applicationContext` to enable file mode:
```kotlin
companion object {
    fun factory(context: Context): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val settingsRepo = SettingsRepository(appContext)
                val logger = ProxyPacLogger.getInstance(
                    logFile = java.io.File(appContext.filesDir, "proxypac-logs.txt"),
                 )
                val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
                return SettingsViewModel(
                    settingsRepository = settingsRepo,
                    proxyResolver      = proxyResolver,
                    appContext         = appContext,        // NEW
                 ) as T
             }
         }
}
```

**3c. Add `pacSourceMode` to `SettingsUiState`**:

Replace:
```kotlin
data class SettingsUiState(
    val timeoutInput: String = SettingsKeys.DEFAULT_TIMEOUT_SECONDS.toString(),
    val isError: Boolean = false,
    val savedTimeout: Int = SettingsKeys.DEFAULT_TIMEOUT_SECONDS,
       // Proxy / PAC fields
    val proxyEnabled: Boolean = false,
    val proxyPacUrl: String = "",
    val proxyPacUrlError: String? = null,
```

With:
```kotlin
data class SettingsUiState(
    val timeoutInput: String = SettingsKeys.DEFAULT_TIMEOUT_SECONDS.toString(),
    val isError: Boolean = false,
    val savedTimeout: Int = SettingsKeys.DEFAULT_TIMEOUT_SECONDS,
       // Proxy / PAC fields
    val proxyEnabled: Boolean = false,
    val pacSourceMode: PacSourceMode = PacSourceMode.Default,     // NEW
    val proxyPacUrl: String = "",
    val proxyPacUrlError: String? = null,
```

**3d. Add `onPacSourceModeChange(mode: PacSourceMode)` action**:

```kotlin
/**
 * Toggles between URL and File source modes for PAC configuration.
 * Clears the PAC URL when switching modes to avoid confusion.
 */
fun onPacSourceModeChange(mode: PacSourceMode) {
     _uiState.value = _uiState.value.copy(
        pacSourceMode = mode,
        proxyPacUrl = "",     // Clear on switch to avoid stale content
        proxyPacUrlError = null,
     )
    proxyResolver.clearCache()
    viewModelScope.launch {
        saveCurrentProxyConfig()
     }
}
```

**3e. Add `onPacFileSelected(uri: Uri)` action — copies to private storage**:

```kotlin
/**
 * Called when the user selects a local PAC file via SAF picker.
 * Copies the file content into app private storage, then stores the internal path.
 */
fun onPacFileSelected(uri: Uri) {
    val internalPath = appContext?.let { ctx ->
        runCatching {
            // Copy selected file to private storage
            val outputFile = File(ctx.filesDir, "saved-pac.pac")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                 }
               }
            outputFile.absolutePath
          }.getOrNull()
      }

    _uiState.value = _uiState.value.copy(
        proxyPacUrl = internalPath ?: "",     // Store internal path, or empty on failure
        proxyPacUrlError = if (internalPath == null) "Failed to copy file" else null,
     )

    if (internalPath != null) {
        proxyResolver.clearCache()
        viewModelScope.launch {
            saveCurrentProxyConfig()
         }
      }
}
```

**3f. Update `validatePacUrl()` — validate based on source mode**:

Replace the entire function with:
```kotlin
internal fun validatePacUrl(url: String, mode: PacSourceMode = _uiState.value.pacSourceMode): String? {
    if (url.isBlank()) return null

    when (mode) {
        PacSourceMode.URL -> {
              // Existing HTTP/HTTPS URL validation...
            return try {
                val parsed = java.net.URL(url)
                when {
                    parsed.protocol !in listOf("http", "https") ->
                          "Only http:// and https:// URLs are supported"
                    parsed.host.isNullOrBlank() ->
                          "URL must contain a hostname"
                    else -> null
                  }
              } catch (_: Exception) {
                  "Invalid URL format"
              }
          }
        PacSourceMode.FILE -> {
              // For file mode: internal paths from copy-to-storage are always valid.
              // If user types a path manually, do basic validation (no tilde expansion).
              // NOTE: No tilde expansion in Settings file mode — paths are literal.
            return try {
                val resolvedPath = if (url.startsWith("content://")) {
                    android.net.Uri.parse(url).path ?: url
                  } else {
                    url
                  }

                  // Basic validation: must be a non-empty path
                if (resolvedPath.isBlank() || resolvedPath == "/") {
                      "Invalid file path"
                  } else {
                    null     // File existence checked at fetch time, not here
                  }
              } catch (_: Exception) {
                  "Invalid file path"
              }
          }
      }
}
```

**3g. Update `onProxyPacUrlChange()` call to `validatePacUrl()` — pass current mode**:

In the debounce block, change:
```kotlin
val error = validatePacUrl(url)
```
To:
```kotlin
val error = validatePacUrl(url, _uiState.value.pacSourceMode)
```

---

### Step 4: `SettingsScreen.kt` — Add segmented toggle + SAF file picker + copy-to-storage

**4a. Add imports**:
```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import java.io.File
```

**4b. Add SAF launcher in the `SettingsScreen()` composable** (right after `viewModel` and `uiState` declarations):

```kotlin
val pacFilePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let { selectedUri ->
        viewModel.onPacFileSelected(selectedUri)
      }
}
```

**4c. Add segmented toggle UI** (after the proxy enable toggle, before the PAC URL field):

```kotlin
// PAC source mode toggle
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    FilterChip(
        selected = uiState.pacSourceMode == PacSourceMode.URL,
        onClick = { viewModel.onPacSourceModeChange(PacSourceMode.URL) },
        label = { Text("URL") },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      )

    FilterChip(
        selected = uiState.pacSourceMode == PacSourceMode.FILE,
        onClick = { viewModel.onPacSourceModeChange(PacSourceMode.FILE) },
        label = { Text("File") },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      )
}
```

**4d. Replace the existing PAC URL `OutlinedTextField` block** with mode-aware controls:

Replace:
```kotlin
OutlinedTextField(
    value = uiState.proxyPacUrl,
    onValueChange = viewModel::onProxyPacUrlChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text("PAC URL") },
    placeholder = { Text("http://proxy.corp.com/proxy.pac") },
    singleLine = true,
    enabled = uiState.proxyEnabled,
    isError = uiState.proxyPacUrlError != null,
    supportingText = { /* ... */ },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
)
```

With:
```kotlin
OutlinedTextField(
    value = uiState.proxyPacUrl,
    onValueChange = viewModel::onProxyPacUrlChange,
    modifier = Modifier.fillMaxWidth(),
    label = {
        Text(
            when (uiState.pacSourceMode) {
                PacSourceMode.URL -> "PAC URL"
                PacSourceMode.FILE -> "PAC File"
              }
          )
      },
    placeholder = {
        Text(
            when (uiState.pacSourceMode) {
                PacSourceMode.URL -> "http://proxy.corp.com/proxy.pac"
                PacSourceMode.FILE -> "/data/user/0/app/files/saved-pac.pac"
              }
          )
      },
    singleLine = true,
    enabled = uiState.proxyEnabled,
    isError = uiState.proxyPacUrlError != null,
    supportingText = {
        when {
            uiState.proxyPacUrlError != null -> Text(
                text = uiState.proxyPacUrlError!!,
                color = MaterialTheme.colorScheme.error,
             )
            uiState.proxyEnabled && uiState.pacSourceMode == PacSourceMode.FILE &&
                 uiState.proxyPacUrl.isNotBlank() && !uiState.proxyPacUrl.startsWith("content://") -> {
                val fileName = File(uiState.proxyPacUrl).name
                Text(
                    text = "Local file: $fileName",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                 )
              }
            uiState.proxyEnabled && uiState.pacSourceMode == PacSourceMode.FILE -> Text(
                text = "Browse to select a local .pac file",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
             )
            uiState.proxyEnabled -> Text(
                text = "URL to an auto-configuration (.pac) script",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
             )
          }
      },
    keyboardOptions = when (uiState.pacSourceMode) {
        PacSourceMode.URL -> KeyboardOptions(keyboardType = KeyboardType.Uri)
        PacSourceMode.FILE -> KeyboardOptions(keyboardType = KeyboardType.Text)
      },
    trailingIcon = when (uiState.pacSourceMode) {
        PacSourceMode.URL -> null     // No trailing icon for URL mode
        PacSourceMode.FILE -> {
            if (uiState.proxyEnabled) {
                  {
                    TextButton(
                        onClick = { pacFilePickerLauncher.launch("application/javascript") },
                      ) {
                        Text("Browse")
                      }
                  }
              } else null
          }
      },
)
```

---

### Step 5: `BulkConfigParser.kt` / `BulkActionsRepository.kt` — Add `file-proxypac` field + validation + ~ expansion + skip flag

**5a. Update `BulkConfig` data class**:

Replace:
```kotlin
data class BulkConfig(
    val outputFile: String?,
    val commands: Map<String, String>,
    val timeoutMs: Long? = null,
    val outputAsCsv: Boolean = false,
    val urlProxyPac: String? = null,
    val logProxy: Boolean? = null,
)
```

With:
```kotlin
data class BulkConfig(
    val outputFile: String?,
    val commands: Map<String, String>,
    val timeoutMs: Long? = null,
    val outputAsCsv: Boolean = false,
    val urlProxyPac: String? = null,
    val fileProxyPac: String? = null,     // NEW — absolute filesystem path with ~ expansion to app files dir
    val logProxy: Boolean? = null,
)
```

**5b. Add `skipFileValidation` flag to BulkConfigParser**:

After the existing `appContext` property in `BulkConfigParser` object:
```kotlin
object BulkConfigParser {

     /** Application context, set once by BulkActionsViewModel factory. Used for private-dir path expansion. */
     @Volatile
    internal var appContext: Context? = null

     /** When true, skip file existence checks during parse (for unit tests). Default: false. */
     @Volatile
    internal var skipFileValidation: Boolean = false

     // ... rest of object
}
```

**5c. Update `BulkConfigParser.parse()`** — add `file-proxypac` parsing + mutual exclusion validation:

After the existing `urlProxyPac` and before `logProxy` parsing blocks (around line 118), add:

```kotlin
val fileProxyPac = runCatching {
    val path = root.optString("file-proxypac", "")
    if (path.isNullOrBlank()) null else expandTilde(path.trim())
}.getOrNull()

// Mutual exclusion validation — throw if both are present
if (!urlProxyPac.isNullOrBlank() && !fileProxyPac.isNullOrBlank()) {
    throw IllegalArgumentException(
          "Cannot specify both 'url-proxypac' and 'file-proxypac'. They are mutually exclusive."
      )
}
```

**5d. Add `file-proxypac` existence validation at parse time**:

After parsing `fileProxyPac`, add:

```kotlin
// Validate file-proxypac exists and is readable (parse-time validation, skippable in tests)
if (!fileProxyPac.isNullOrBlank() && !skipFileValidation) {
    val file = java.io.File(fileProxyPac)
    if (!file.exists() || !file.isFile || !file.canRead()) {
        throw IllegalArgumentException(
              "file-proxypac points to inaccessible file: $fileProxyPac. " +
                  "Ensure the file exists and is readable."
          )
      }
}
```

**5e. Existing `expandTilde()` in BulkConfigParser** — already expands to app's private files dir (verified from code reading). No changes needed.

---

### Step 6: `BulkActionsViewModel.kt` — Wire `file-proxypac` into proxy resolver setup

**6a. Update proxy resolver setup calls** — pass either URL or file path:

In `onLoadAndRun()` (around line 252) and `onRunClicked()` (around line 337), replace:
```kotlin
repository.setupProxyResolver(config.urlProxyPac, forceLogging = config.logProxy == true)
```

With:
```kotlin
val pacSource = config.fileProxyPac ?: config.urlProxyPac
repository.setupProxyResolver(pacSource, forceLogging = config.logProxy == true)
```

**No changes needed to `setupProxyResolver()` or `clearProxyResolver()` in BulkActionsRepository** — they already accept a nullable `String?` and pass it through to `ProxyResolver.forStaticPacUrl()`. The routing logic in `resolveProxy()` (Step 1d) will dispatch based on scheme.

---

### Step 7: `GoogleTimeSyncViewModel.kt` — Accept Context, enable file-based PAC

**7a. Add `Context?` to constructor**:

Replace:
```kotlin
class GoogleTimeSyncViewModel(
    private val repository    : GoogleTimeSyncRepository     = GoogleTimeSyncRepository(),
    private val historyStore  : GoogleTimeSyncHistoryStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() { ... }
```

With:
```kotlin
class GoogleTimeSyncViewModel(
    private val repository    : GoogleTimeSyncRepository     = GoogleTimeSyncRepository(),
    private val historyStore  : GoogleTimeSyncHistoryStore,
    private val settingsRepository: SettingsRepository,
    private val appContext    : Context? = null,       // NEW — for file-based PAC
) : ViewModel() { ... }
```

**7b. Update factory to pass Context**:

Replace:
```kotlin
val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
return GoogleTimeSyncViewModel(
    repository    = GoogleTimeSyncRepository(proxyResolver),
    historyStore  = GoogleTimeSyncHistoryStore(appContext),
    settingsRepository = settingsRepo,
) as T
```

With:
```kotlin
val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
return GoogleTimeSyncViewModel(
    repository    = GoogleTimeSyncRepository(proxyResolver),
    historyStore  = GoogleTimeSyncHistoryStore(appContext),
    settingsRepository = settingsRepo,
    appContext    = appContext,       // NEW — enables file-based PAC for google-timesync command
) as T
```

**No changes needed to `syncTime()` method** — it uses `GoogleTimeSyncRepository` which internally uses the injected `ProxyResolver`. The `ProxyResolver` now has `appContext` and will dispatch file vs URL routes automatically.

---

### Step 8: `HttpsCertViewModel.kt` — Accept Context, enable file-based PAC

**8a. Add `Context?` to constructor**:

Replace:
```kotlin
class HttpsCertViewModel(
    private val repository: HttpsCertRepository,
    private val historyStore: HttpsCertHistoryStore,
) : ViewModel() { ... }
```

With:
```kotlin
class HttpsCertViewModel(
    private val repository: HttpsCertRepository,
    private val historyStore: HttpsCertHistoryStore,
    private val appContext: Context? = null,         // NEW — for file-based PAC
) : ViewModel() { ... }
```

**8b. Update factory to pass Context**:

Replace:
```kotlin
val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
return HttpsCertViewModel(
    repository    = HttpsCertRepository(proxyResolver),
    historyStore  = HttpsCertHistoryStore(appContext),
) as T
```

With:
```kotlin
val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine(), logger = logger)
return HttpsCertViewModel(
    repository    = HttpsCertRepository(proxyResolver),
    historyStore  = HttpsCertHistoryStore(appContext),
    appContext    = appContext,       // NEW — enables file-based PAC for checkcert command
) as T
```

**No changes needed to `fetchCert()` method** — it uses `HttpsCertRepository` which internally uses the injected `ProxyResolver`. The `ProxyResolver` now has `appContext` and will dispatch file vs URL routes automatically.

---

### Step 9: Tests

#### 9a. `SettingsViewModelTest.kt` — Add tests

```kotlin
// ── PAC source mode ──────────────────────────────────────────────────────

@Test
fun `initial state has URL as default source mode`() = runTest {
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(PacSourceMode.URL, viewModel.uiState.value.pacSourceMode)
}

@Test
fun `onPacSourceModeChange switches to FILE and clears URL`() = runTest {
      // Set a URL first
    viewModel.onProxyPacUrlChange("http://proxy.corp.com/proxy.pac")
    testDispatcher.scheduler.advanceUntilIdle()

      // Switch to FILE mode
    viewModel.onPacSourceModeChange(PacSourceMode.FILE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(PacSourceMode.FILE, viewModel.uiState.value.pacSourceMode)
    assertEquals("", viewModel.uiState.value.proxyPacUrl)
    assertNull(viewModel.uiState.value.proxyPacUrlError)
    io.mockk.verify { proxyResolver.clearCache() }
    coVerify { settingsRepository.saveProxyConfig(any()) }
}

@Test
fun `onPacSourceModeChange switches to URL and clears file path`() = runTest {
      // Set a file path
    viewModel.onPacSourceModeChange(PacSourceMode.FILE)
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.onProxyPacUrlChange("/data/user/0/app/files/saved-pac.pac")
    testDispatcher.scheduler.advanceUntilIdle()

      // Switch back to URL mode
    viewModel.onPacSourceModeChange(PacSourceMode.URL)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(PacSourceMode.URL, viewModel.uiState.value.pacSourceMode)
    assertEquals("", viewModel.uiState.value.proxyPacUrl)
}

// ── PAC file selection (copy-to-storage) ─────────────────────────────────

@Test
fun `onPacFileSelected copies content to private storage and stores path`() = runTest {
      // Mock: simulate SAF URI → copy to private storage succeeds
    val fakeUri = Uri.parse("content://com.android.externalstorage.documents/document/324-162:proxy.pac")

      // Verify appContext is not null in this test (SettingsViewModel needs Context for file mode)
    assertNotNull(viewModel.appContext)

    viewModel.onPacFileSelected(fakeUri)
    testDispatcher.scheduler.advanceUntilIdle()

      // The stored path should be the internal private storage path
    val storedPath = viewModel.uiState.value.proxyPacUrl
    assertTrue(storedPath.startsWith("/data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/"))
    assertTrue(storedPath.endsWith("saved-pac.pac"))

      // Verify URL is cleared, no error
    assertNull(viewModel.uiState.value.proxyPacUrlError)
    coVerify { settingsRepository.saveProxyConfig(any()) }
}

@Test
fun `onPacFileSelected stores error when copy fails`() = runTest {
      // When appContext is null (not set up for file mode), onPacFileSelected should fail gracefully
    val vmWithoutContext = SettingsViewModel(settingsRepository, proxyResolver, appContext = null)
    val fakeUri = Uri.parse("content://com.../proxy.pac")

    vmWithoutContext.onPacFileSelected(fakeUri)
    testDispatcher.scheduler.advanceUntilIdle()

      // Should store empty string and show error
    assertEquals("", vmWithoutContext.uiState.value.proxyPacUrl)
    assertNotNull(vmWithoutContext.uiState.value.proxyPacUrlError)
    assertTrue(vmWithoutContext.uiState.value.proxyPacUrlError!!.contains("Failed to copy"))
}

@Test
fun `onPacFileSelected clears error and persists config`() = runTest {
      // Set an invalid URL first, then select a file to clear it
    viewModel.onProxyPacUrlChange("not a url")
    testDispatcher.scheduler.advanceTimeBy(400)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.uiState.value.proxyPacUrlError != null)

      // Select a file (simulates successful copy)
    val fakeUri = Uri.parse("content://com.../proxy.pac")
    viewModel.onPacFileSelected(fakeUri)
    testDispatcher.scheduler.advanceUntilIdle()

    assertNull(viewModel.uiState.value.proxyPacUrlError)
    coVerify { settingsRepository.saveProxyConfig(any()) }
}

@Test
fun `onPacFileSelected clears proxy cache`() = runTest {
    val fakeUri = Uri.parse("content://com.../proxy.pac")
    viewModel.onPacFileSelected(fakeUri)
    testDispatcher.scheduler.advanceUntilIdle()

    io.mockk.verify { proxyResolver.clearCache() }
}

// ── Validation: FILE mode ────────────────────────────────────────────────

@Test
fun `validatePacUrl accepts internal private storage path in FILE mode`() {
    val vm = SettingsViewModel(settingsRepository, proxyResolver, appContext = mockk())
      // Internal paths from copy-to-storage are always valid (no existence check at validate time)
    assertNull(vm.validatePacUrl("/data/user/0/app/files/saved-pac.pac", PacSourceMode.FILE))
}

@Test
fun `validatePacUrl accepts content:// URI in FILE mode`() {
    val vm = SettingsViewModel(settingsRepository, proxyResolver, appContext = mockk())
    val uri = "content://com.android.externalstorage.documents/document/123-proxy.pac"
    assertNull(vm.validatePacUrl(uri, PacSourceMode.FILE))
}

@Test
fun `validatePacUrl rejects empty path in FILE mode`() {
    val vm = SettingsViewModel(settingsRepository, proxyResolver, appContext = mockk())
    val error = vm.validatePacUrl("/", PacSourceMode.FILE)
    assertTrue(error != null && error.contains("Invalid"))
}

@Test
fun `validatePacUrl rejects malformed content:// URI in FILE mode`() {
    val vm = SettingsViewModel(settingsRepository, proxyResolver, appContext = mockk())
    val badUri = "content://"
    val error = vm.validatePacUrl(badUri, PacSourceMode.FILE)
    assertTrue(error != null)
}

// ── Validation: URL mode (unchanged) ─────────────────────────────────────

@Test
fun `validatePacUrl in URL mode accepts valid HTTP URL`() {
    assertNull(viewModel.validatePacUrl("http://proxy.corp.com/proxy.pac", PacSourceMode.URL))
}

@Test
fun `validatePacUrl in URL mode rejects FTP URL`() {
    val error = viewModel.validatePacUrl("ftp://proxy.corp.com/proxy.pac", PacSourceMode.URL)
    assertTrue(error != null && error.contains("http"))
}
```

#### 9b. `BulkConfigParserTest.kt` — Add tests

```kotlin
// ── file-proxypac parsing ────────────────────────────────────────────────

@Test
fun `parseConfigWithFileProxyPac_returnsPath_whenSkipValidation`() {
      // Enable skip flag to avoid needing real file on disk
    BulkConfigParser.skipFileValidation = true
    try {
        val json = """{
               "run": { "ping_test": "ping -c 3 google.com" },
               "file-proxypac": "/sdcard/Downloads/proxy.pac"
           }"""

        val config = BulkConfigParser.parse(json)
        assertEquals("/sdcard/Downloads/proxy.pac", config.fileProxyPac)
    } finally {
        BulkConfigParser.skipFileValidation = false
     }
}

@Test
fun `parseConfigWithBothUrlAndFileProxyPac_throwsError`() {
    val json = """{
           "run": { "ping_test": "ping -c 3 google.com" },
           "url-proxypac": "http://proxy.corp.com/proxy.pac",
           "file-proxypac": "/sdcard/Downloads/proxy.pac"
       }"""

    assertThrows<IllegalArgumentException> {
        BulkConfigParser.parse(json)
      }
}

@Test
fun `parseConfigWithBlankFileProxyPac_returnsNull`() {
    val json = """{
           "run": { "ping_test": "ping -c 3 google.com" },
           "file-proxypac": ""
       }"""

    val config = BulkConfigParser.parse(json)
    assertNull(config.fileProxyPac)
}

@Test
fun `parseConfigWithMissingFileProxyPac_returnsNull`() {
    val json = """{
           "run": { "ping_test": "ping -c 3 google.com" }
       }"""

    val config = BulkConfigParser.parse(json)
    assertNull(config.fileProxyPac)
}

@Test
fun `parseConfigWithUrlProxyPacAndFileProxyPacPreservesOtherFields_throws`() {
      // Test that mutual exclusion error is thrown, not silent failure
    val json = """{
           "output-file": "/tmp/test.txt",
           "url-proxypac": "http://proxy.corp.com/proxy.pac",
           "file-proxypac": "/sdcard/Downloads/proxy.pac",
           "log-proxy": true,
           "run": { "ping_test": "ping -c 3 google.com" }
       }"""

    assertThrows<IllegalArgumentException> {
        BulkConfigParser.parse(json)
      }
}

@Test
fun `parseConfigWithFileProxyPac_andTilde_expands_whenSkipValidation`() {
    BulkConfigParser.skipFileValidation = true
    try {
          // Set appContext for tilde expansion
        val mockCtx = mockk<Context>(relaxed = true)
        every { mockCtx.applicationContext.filesDir.absolutePath } returns "/data/user/0/app/files"
        BulkConfigParser.appContext = mockCtx

        val json = """{
               "run": { "ping_test": "ping -c 3 google.com" },
               "file-proxypac": "~/proxy.pac"
           }"""

        val config = BulkConfigParser.parse(json)
        assertEquals("/data/user/0/app/files/proxy.pac", config.fileProxyPac)
    } finally {
        BulkConfigParser.skipFileValidation = false
        BulkConfigParser.appContext = null
     }
}

@Test
fun `parseConfigWithInaccessibleFileProxyPac_throws_whenValidationEnabled`() {
      // skipFileValidation is false by default — this should throw
    val json = """{
           "run": { "ping_test": "ping -c 3 google.com" },
           "file-proxypac": "/nonexistent/path/proxy.pac"
       }"""

    assertThrows<IllegalArgumentException> {
        BulkConfigParser.parse(json)
      }
}
```

#### 9c. `ProxyResolverTest.kt` — Add tests for file-based PAC (unit tests with FileSystemIO mock)

```kotlin
// ── fetchPacFromFile tests (unit tests with mocked FileSystemIO) ───────────

@Test
fun `fetchPacFromFile reads PAC script from valid path via mock FS`() {
    val mockFs = mockk<FileSystemIO>()
    every { mockFs.exists("/tmp/test.pac") } returns true
    every { mockFs.isFile("/tmp/test.pac") } returns true
    every { mockFs.canRead("/tmp/test.pac") } returns true
    every { mockFs.readText("/tmp/test.pac") } returns "function FindProxyForURL(u, h) { return 'DIRECT'; }"

    val resolver = ProxyResolver.withoutFileSupport(settingsRepository, jsEngine)
      // fetchPacFromFile is internal — accessible from same module in test scope
    val result = resolver.fetchPacFromFile("/tmp/test.pac", mockFs)

    assertNotNull(result)
    assertEquals("function FindProxyForURL(u, h) { return 'DIRECT'; }", result)
}

@Test
fun `fetchPacFromFile returns null for non-existent path`() {
    val mockFs = mockk<FileSystemIO>()
    every { mockFs.exists("/nonexistent/pac.pac") } returns false

    val resolver = ProxyResolver.withoutFileSupport(settingsRepository, jsEngine)
    val result = resolver.fetchPacFromFile("/nonexistent/pac.pac", mockFs)

    assertNull(result)
}

@Test
fun `fetchPacFromFile returns null for non-readable path`() {
    val mockFs = mockk<FileSystemIO>()
    every { mockFs.exists(any()) } returns true
    every { mockFs.isFile(any()) } returns true
    every { mockFs.canRead(any()) } returns false

    val resolver = ProxyResolver.withoutFileSupport(settingsRepository, jsEngine)
    val result = resolver.fetchPacFromFile("/unreadable/pac.pac", mockFs)

    assertNull(result)
}

@Test
fun `fetchPacFromFile caches result and reuses within TTL`() {
    val mockFs = mockk<FileSystemIO>()
    every { mockFs.exists("/tmp/test.pac") } returns true
    every { mockFs.isFile("/tmp/test.pac") } returns true
    every { mockFs.canRead("/tmp/test.pac") } returns true
    every { mockFs.readText("/tmp/test.pac") } returns "function FindProxyForURL(u, h) { return 'DIRECT'; }"

    val resolver = ProxyResolver.withoutFileSupport(settingsRepository, jsEngine)

      // Call twice within TTL — readText should only be called once (cached)
    resolver.fetchPacFromFile("/tmp/test.pac", mockFs)
    resolver.fetchPacFromFile("/tmp/test.pac", mockFs)

    coVerify(exactly = 1) { mockFs.readText("/tmp/test.pac") }
}

@Test
fun `fetchPacFromFile throws exception on read error`() {
    val mockFs = mockk<FileSystemIO>()
    every { mockFs.exists(any()) } returns true
    every { mockFs.isFile(any()) } returns true
    every { mockFs.canRead(any()) } returns true
    every { mockFs.readText(any()) } throws RuntimeException("Permission denied")

    val resolver = ProxyResolver.withoutFileSupport(settingsRepository, jsEngine)
    val result = resolver.fetchPacFromFile("/error/pac.pac", mockFs)

    assertNull(result)
}
```

#### 9d. `BulkActionsRepositoryTest.kt` — Add tests (if repository layer testing is added)

```kotlin
@Test
fun `expandTilde_expands_to_app_files_directory`() {
    val original = "~/proxy.pac"
      // Set appContext for BulkConfigParser first
    val mockCtx = mockk<Context>(relaxed = true)
    every { mockCtx.applicationContext.filesDir.absolutePath } returns "/data/user/0/app/files"
    BulkConfigParser.appContext = mockCtx

    val expanded = BulkConfigParser.expandTilde(original)
    assertEquals("/data/user/0/app/files/proxy.pac", expanded)
}

@Test
fun `expandTilde_returns_unchanged_when_no_tilde`() {
    val original = "/sdcard/Downloads/proxy.pac"
    val expanded = BulkConfigParser.expandTilde(original)
    assertEquals(original, expanded)
}
```

---

## Files to Change (Summary)

| File | Changes |
|---|---|
| `proxy/ProxyResolver.kt` | Add `FileSystemIO` interface + `DefaultFileSystemIO`; add `appContext: Context? = null` param to primary constructor; add `withoutFileSupport()` companion factory (deprecated); add `fetchPacFromFile()` (internal, @visibleForTesting); update `resolveProxy()` routing to dispatch by scheme |
| **NEW** `PacSourceMode.kt` | New file: `enum class PacSourceMode { URL, FILE }` with `Default` companion |
| `SettingsViewModel.kt` | Add `Context? appContext` param to constructor; add `pacSourceMode` to `SettingsUiState`; add `onPacSourceModeChange()` and `onPacFileSelected()` (copy-to-storage) actions; update `validatePacUrl()` for mode-aware validation; update `onProxyPacUrlChange()` to pass mode; factory → pass `appContext` |
| `SettingsScreen.kt` | Add SAF launcher (`GetContent`); add segmented `FilterChip` toggle (URL/File); make PAC URL field mode-aware (label, placeholder, supporting text, trailing icon) |
| **BulkConfig** (`BulkActionsRepository.kt`) | Add `fileProxyPac: String?` to data class; parse `file-proxypac` in BulkConfigParser; add mutual exclusion validation (`url-proxypac` + `file-proxypac`); add file existence check at parse time (skippable via `skipFileValidation`) |
| `BulkConfigParser.kt` (in `BulkActionsRepository.kt`) | Add `@Volatile internal var skipFileValidation: Boolean = false`; add `file-proxypac` parsing + ~ expansion + validation |
| `BulkActionsViewModel.kt` | Update proxy resolver setup calls to pass either `fileProxyPac` or `urlProxyPac` (whichever is non-null) |
| **NEW** `GoogleTimeSyncViewModel.kt` | Add `Context? appContext` param to constructor; factory → pass `appContext` |
| **NEW** `HttpsCertViewModel.kt` | Add `Context? appContext` param to constructor; factory → pass `appContext` |
| **NEW** tests in `SettingsViewModelTest.kt` | ~16 new tests: source mode switching, file selection (copy-to-storage), validation (URL and FILE modes) |
| **NEW** tests in `BulkConfigParserTest.kt` | 7 new tests: file-proxypac parsing, mutual exclusion error, blank/missing handling, tilde expansion, skipFileValidation flag |
| **NEW** tests in `ProxyResolverTest.kt` | ~5 new tests: file-based PAC resolution with mocked `FileSystemIO` |

---

## Implementation Order

1. **`proxy/ProxyResolver.kt`** — `FileSystemIO` interface + default impl, new primary constructor param `appContext`, `withoutFileSupport()` companion factory, `fetchPacFromFile()` (internal), routing in `resolveProxy()`
2. **NEW `PacSourceMode.kt`** — Enum class creation
3. **`SettingsViewModel.kt`** — `Context?` in constructor, `PacSourceMode` import, state field, mode switch action, file selection action (copy-to-storage), validation update, factory → pass `appContext`
4. **`SettingsScreen.kt`** — SAF launcher, segmented toggle, mode-aware field
5. **BulkActions files** — `fileProxyPac` field in BulkConfig, parsing in BulkConfigParser, mutual exclusion validation, file existence check + `skipFileValidation` flag; wire in BulkActionsViewModel
6. **`GoogleTimeSyncViewModel.kt`** — Accept `Context?` in constructor, pass through factory
7. **`HttpsCertViewModel.kt`** — Accept `Context?` in constructor, pass through factory
8. **Tests** — SettingsViewModelTest (~16 tests), BulkConfigParserTest (~7 tests), ProxyResolverTest (~5 tests with FileSystemIO mock)

---

## Edge Cases & Considerations

| Scenario | Handling |
|---|---|
| User selects a non-JS / non-PAC file | No MIME enforcement beyond picker hint. The JS engine will fail to evaluate and return null (same as invalid PAC content from HTTP). Safe fallback. |
| File is deleted or permissions revoked after selection (Settings) | Copy-to-storage means the internal copy is independent of the source file. If user re-selects, a fresh copy overwrites. No stale reference risk. |
| User switches between URL and File modes multiple times | Each switch clears the PAC URL/Path field and cache. Clean slate for new input. |
| Large PAC files (e.g., corporate scripts > 1MB) | Copy via `inputStream.copyTo(outputStream)` through FileSystemIO — could be slow on large files. Existing read timeout is per-operation; file I/O has no explicit timeout but should complete within seconds for typical PAC scripts. |
| Multiple PAC file selections in quick succession | Each selection triggers debounced save + cache clear (same as URL typing). No special handling needed. |
| File path with spaces or special characters | `File()` handles these natively. SAF picker returns clean paths. No escaping needed. |
| BulkActions config loaded via ADB push — file not on device | Parse-time validation throws `IllegalArgumentException` → shown as `ValidationMessage.Error` in UI. User sees clear message about missing file. (Unless `skipFileValidation = true` in tests.) |
| User types `~/proxy.pac` in Settings file field | **No tilde expansion** — path stays literal (no expansion happens). Only BulkActions JSON configs get tilde expansion. |
| BulkActions config with `~/proxy.pac` | Expands to app's private files dir (e.g., `/data/user/0/io.github.mobilutils.ntp_dig_ping_more/files/proxy.pac`) via existing `expandTilde()`. |
| Settings ViewModel created without Context (e.g., manual test instantiation) | `appContext = null` → `onPacFileSelected()` fails gracefully, stores empty string + error message "Failed to copy". File mode is effectively disabled. URL mode still works. |
| GoogleTimeSync / HttpsCert with file-based PAC from BulkActions | BulkActionsRepository.buildProxyResolver() uses `ProxyResolver.forStaticPacUrl(pacUrl, context, ...)` which passes Context → enables file routing. Works "out of the box" because `resolveProxy()` dispatches by scheme. |
| GoogleTimeSync / HttpsCert with PAC URL from Settings | If user sets a PAC URL in Settings, then runs google-timesync or checkcert via BulkActions, the BulkActions config's `url-proxypac` / `file-proxypac` takes precedence (BulkActions creates its own ProxyResolver, not the one from Settings). |

---

## Not In Scope (Future Enhancements)

- **Edit PAC content inline**: A text editor composable showing/fetching PAC script content for review
- **PAC content hash caching**: Cache by file content hash instead of path string (handles re-selection of same file with different content, or file modifications in-place). Copy-to-storage already handles the "same file re-selected" case since we always overwrite.
- **"Recent files" list**: Persist last N selected file paths/URIs for quick re-selection
- **MIME type enforcement**: Strict checking that selected file is actually a JS/PAC file before accepting
- **Permission restoration UI**: Explicit prompt if file access is revoked (currently fails silently as DIRECT)
- **Symlink resolution**: Resolve symbolic links in file paths (Android rarely uses symlinks for user files, but corporate environments may)
