---
name: Load Proxy PAC from Local File via SAF
description: Plan to add SAF file picker support for loading proxy PAC scripts from local files, alongside existing URL input.
type: project
---

# Plan: Load Proxy PAC from Local File via SAF

## Goal

On the **SettingsScreen** under **Proxy Configuration**, allow users to load a PAC script from a local file using Android's Storage Access Framework (SAF) `GetContent` picker, in addition to the existing URL text field.

---

## Current State

- **UI**: Single `OutlinedTextField` for PAC URL with `KeyboardType.Uri`. Supporting text says "URL to an auto-configuration (.pac) script".
- **Validation**: `validatePacUrl()` checks for `http://` or `https://` protocol + non-empty host. Empty string is allowed (means disabled).
- **Fetching**: `ProxyResolver.fetchPacScript()` uses `HttpURLConnection` only — HTTP/HTTPS GET, follows redirects, 10s timeouts.
- **Storage**: PAC URL stored in DataStore under `PROXY_PAC_URL` key as a plain string.
- **SAF precedent**: `BulkActionsScreen.kt` already uses `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` for loading JSON config files, and `CreateDocument` for output.

---

## Design Decisions

### 1. Storage Model — Single Field, Scheme-Based Routing

**Decision**: Store the file URI in the **same** `PROXY_PAC_URL` DataStore field. Detect source type by URI scheme:
- `http://` or `https://` → HTTP fetch (existing behavior)
- `content://` → SAF file read (new behavior)

**Why**: No new DataStore keys, no migration, backward compatible. The string value is the single "source of truth" regardless of type.

### 2. MIME Type Filter for Picker

**Decision**: Use `"application/javascript"` as the MIME filter, with fallback to `"*/*"` if user's file manager doesn't show files matching that type. Alternatively, offer both options via a small popup menu or two separate buttons (e.g., "Browse PAC" and "Browse Any").

**Why**: `.pac` files are typically `application/x-ns-proxy-autoconfig` (not widely supported) or `application/javascript`. Using `"application/javascript"` narrows the picker to JS files while still allowing manual browsing to other types.

### 3. Display in UI — How to Show the Selected File

**Decision**: When a file is selected, display the file's last path segment (filename) in the text field alongside the `content://` URI. Optionally show the filename as a tooltip or secondary line for readability.

Example:
```
PAC URL
┌───────────────────────────────────────┬────────┐
│ content:///document/324...  proxy.pac │ Browse │
└───────────────────────────────────────┴────────┘
Local file: proxy.pac (1.2 KB)
```

**Why**: `content://` URIs are opaque and hard for users to read — showing the filename provides useful context.

### 4. Caching Behavior

**Decision**: Local file PAC scripts use the same 5-minute cache TTL as HTTP-fetched scripts. Cache key is the URI string (not file content hash). If the user re-selects the same file, the cached copy is used unless TTL has expired. `clearCache()` is called on every URL/URI change.

**Why**: Consistent with existing behavior. File contents are unlikely to change during a session, and the 5-minute TTL prevents stale PAC logic from persisting across edits.

### 5. Test Proxy Behavior

**Decision**: The "Test Proxy/PAC" button works identically regardless of source type — it resolves through the loaded PAC script and pings `connectivitycheck.gstatic.com`. No changes needed here.

---

## Implementation Plan (Wall Version)

### Step 1: `proxy/ProxyResolver.kt` — Add file reading + Context dependency

**1a. Add `Context` to constructor** (stored as private val, used only for SAF access):

```kotlin
class ProxyResolver(
    private val settingsRepository: SettingsRepository,
    private val jsEngine: JsEngine,
    private val staticPacUrl: String? = null,
    private val logger: ProxyPacLogger? = null,
    private val forceLogging: Boolean = false,
    private val appContext: Context? = null,  // NEW — for SAF file reads
) { ... }
```

**1b. Add `fetchPacFromUri()` method**:

```kotlin
/**
 * Fetches a PAC script from a content:// URI using SAF ContentResolver.
 * Uses the same caching contract as fetchPacScript().
 */
private fun fetchPacFromUri(uri: Uri): String? {
    val context = appContext ?: run {
        logIfEnabled("PAC_FETCH_FAIL reason=no Context available for file access")
        return null
     }

    val now = System.currentTimeMillis()
    if (cachedPacScript != null && cachedPacUrl == uri.toString() &&
        now - pacFetchedAt < PAC_CACHE_TTL_MS) {
        return cachedPacScript
     }

    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
             ?: throw IOException("Failed to open input stream for $uri")
        
        val script = inputStream.bufferedReader(Charsets.UTF_8).readText()
        inputStream.close()

        cachedPacScript = script
        cachedPacUrl = uri.toString()
        pacFetchedAt = now
        logIfEnabled("PAC_FETCH_SUCCESS url=$uri (local file)")
        script
     } catch (e: Exception) {
        logIfEnabled("PAC_FETCH_FAIL url=$uri reason=${e.message}")
        null
     }
}
```

**1c. Update `resolveProxy()` routing**:

Replace this block in `resolveProxy()`:
```kotlin
// Fetch (or use cached) PAC script
val pacScript = fetchPacScript(effectivePacUrl) ?: return@withContext null
```

With:
```kotlin
// Fetch (or use cached) PAC script
val pacScript = if (effectivePacUrl.startsWith("content://")) {
    appContext?.let { fetchPacFromUri(Uri.parse(effectivePacUrl)) }
        ?: run { logIfEnabled("PAC_FETCH_FAIL reason=no Context"); null }
} else {
    fetchPacScript(effectivePacUrl)
} ?: return@withContext null
```

**1d. Update `forStaticPacUrl()` factory** — pass `context` as the new `appContext` param:

```kotlin
fun forStaticPacUrl(
    pacUrl: String,
    context: android.content.Context,
    jsEngine: JsEngine = QuickJsEngine(),
    logger: ProxyPacLogger? = null,
    forceLogging: Boolean = false,
): ProxyResolver = ProxyResolver(
    settingsRepository = SettingsRepository(context),
    jsEngine = jsEngine,
    staticPacUrl = pacUrl,
    logger = logger,
    forceLogging = forceLogging,
    appContext = context,  // NEW
)
```

---

### Step 2: `SettingsViewModel.kt` — Add file selection action + validation update

**2a. Add import**:
```kotlin
import android.net.Uri
```

**2b. Add `onPacFileSelected()` action**:

```kotlin
/**
 * Called when the user selects a local PAC file via SAF picker.
 * Stores the content:// URI as the active PAC source, clears cache.
 */
fun onPacFileSelected(uri: Uri) {
    val uriString = uri.toString()
    _uiState.value = _uiState.value.copy(
        proxyPacUrl = uriString,
        proxyPacUrlError = null,
     )
    proxyResolver.clearCache()
    viewModelScope.launch {
        saveCurrentProxyConfig()
     }
}
```

**2c. Update `validatePacUrl()`** to accept `content://` URIs:

Replace the entire function with:
```kotlin
internal fun validatePacUrl(url: String): String? {
    if (url.isBlank()) return null
    
    // Accept local file URIs from SAF picker
    if (url.startsWith("content://")) {
        return try {
            val uri = Uri.parse(url)
            if (uri.authority.isNullOrBlank()) "Invalid file URI"
            else null
         } catch (_: Exception) {
             "Invalid file URI"
         }
     }
    
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
```

**2d. No changes to `onProxyPacUrlChange()` or `saveCurrentProxyConfig()`** — they already handle any string value in the PAC URL field, including `content://` URIs. The debounce → validate → save flow works identically for both HTTP URLs and file URIs.

---

### Step 3: `SettingsScreen.kt` — Add SAF launcher + Browse button

**3a. Add imports**:
```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
```

**3b. Add SAF launcher in the `SettingsScreen()` composable** (right after `viewModel` and `uiState` declarations):

```kotlin
val pacFilePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let { selectedUri ->
        viewModel.onPacFileSelected(selectedUri)
     }
}
```

**3c. Replace the existing PAC URL `OutlinedTextField` block** with a `Row` containing the text field + Browse button:

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
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
) {
    OutlinedTextField(
        value = uiState.proxyPacUrl,
        onValueChange = viewModel::onProxyPacUrlChange,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        label = { Text("PAC URL") },
        placeholder = { Text("http://proxy.corp.com/proxy.pac") },
        singleLine = true,
        enabled = uiState.proxyEnabled,
        isError = uiState.proxyPacUrlError != null,
        supportingText = {
            when {
                uiState.proxyPacUrlError != null -> Text(
                    text = uiState.proxyPacUrlError!!,
                    color = MaterialTheme.colorScheme.error,
                 )
                uiState.proxyEnabled && uiState.proxyPacUrl.startsWith("content://") -> {
                    val fileName = Uri.parse(uiState.proxyPacUrl).lastPathSegment ?: "unknown"
                    Text(
                        text = "Local file: $fileName",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                     )
                 }
                uiState.proxyEnabled -> Text(
                    text = "URL to an auto-configuration (.pac) script or local file",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                 )
             }
         },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
     )

    TextButton(
        onClick = { pacFilePickerLauncher.launch("application/javascript") },
        enabled = uiState.proxyEnabled,
        modifier = Modifier.align(Alignment.CenterVertically),
     ) {
         Text("Browse")
     }
}
```

**3d. Update the "Test Proxy/PAC" button's `enabled` condition** — add a check that the PAC URL is not empty (same as before, no change needed since `content://` URIs are non-blank strings).

---

### Step 4: Tests

#### 4a. `SettingsViewModelTest.kt` — Add tests

```kotlin
// ── PAC file selection ───────────────────────────────────────────────────

@Test
fun `onPacFileSelected stores content URI in state`() = runTest {
    val fakeUri = Uri.parse("content://com.android.externalstorage.documents/document/324-162:proxy.pac")
    viewModel.onPacFileSelected(fakeUri)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(fakeUri.toString(), viewModel.uiState.value.proxyPacUrl)
}

@Test
fun `onPacFileSelected clears error and persists config`() = runTest {
    val fakeUri = Uri.parse("content://com.../proxy.pac")
     // Set an invalid URL first, then select a file to clear it
    viewModel.onProxyPacUrlChange("not a url")
    testDispatcher.scheduler.advanceTimeBy(400)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.uiState.value.proxyPacUrlError != null)

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

// ── Validation: content:// URIs ──────────────────────────────────────────

@Test
fun `validatePacUrl accepts valid content:// URI`() {
    val uri = "content://com.android.externalstorage.documents/document/123-proxy.pac"
    assertNull(viewModel.validatePacUrl(uri))
}

@Test
fun `validatePacUrl rejects malformed content:// URI without authority`() {
    val badUri = "content://"
    val error = viewModel.validatePacUrl(badUri)
    assertTrue(error != null)
}

@Test
fun `validatePacUrl still accepts http URLs`() {
    assertNull(viewModel.validatePacUrl("http://proxy.corp.com/proxy.pac"))
    assertNull(viewModel.validatePacUrl("https://proxy.corp.com/proxy.pac"))
}
```

#### 4b. `ProxyResolverTest.kt` — Add tests (instrumented, since they need Context)

Add a new test class or extend existing:

```kotlin
@RunWith(AndroidJUnit4::class)
class ProxyResolverFileTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var proxyResolver: ProxyResolver

    @Before
    fun setup() {
        // Use Application context from InstrumentationRegistry
        context = InstrumentationRegistry.getInstrumentation().applicationContext
        settingsRepository = SettingsRepository(context)
        proxyResolver = ProxyResolver(
            settingsRepository = settingsRepository,
            jsEngine = QuickJsEngine(),
            appContext = context,  // Enable file reading
         )
     }

    @Test
    fun `resolveProxy uses cached PAC from content URI`() {
        // Create a MockContentResolver with a test PAC script
        val mockResolver = MockContentResolver()
        val pacScript = "function FindProxyForURL(url, host) { return 'DIRECT'; }"
        mockResolver.openOutputStream(Uri.parse("content://test/pac.pac"))
            ?.use { it.write(pacScript.toByteArray(Charsets.UTF_8)) }
        
        // Replace appContext's resolver (requires wrapping or reflection for test)
        // Or: extract fetchPacFromUri behind an interface for mocking
    }

    @Test
    fun `resolveProxy returns null when content URI is inaccessible`() {
        // Test with non-existent document ID — should fall back to DIRECT
    }
}
```

**Note**: Testing `fetchPacFromUri` directly requires a `Context` with a `ContentResolver`. Recommended approach: extract file reading behind an interface (`PacFileReader`) that can be mocked in unit tests, keeping `ProxyResolver` constructor clean. For now, instrumented tests are acceptable since they need real Android framework components anyway.

---

## Files to Change (Summary)

| File | Changes |
|---|---|
| `proxy/ProxyResolver.kt` | Add `Context?` param to constructor; add `fetchPacFromUri()` method; update `resolveProxy()` routing; update `forStaticPacUrl()` factory |
| `SettingsViewModel.kt` | Add `onPacFileSelected(uri: Uri)` action; update `validatePacUrl()` for `content://` URIs |
| `SettingsScreen.kt` | Add SAF launcher (`GetContent`); add "Browse" TextButton + Row layout; update supporting text with filename hint |
| `SettingsViewModelTest.kt` | Add 6 new tests: file selection (3), validation (3) |
| `ProxyResolverTest.kt` or new instrumented test class | Add 2+ tests for file-based PAC resolution |

**No changes needed**: `SettingsRepository.kt`, `ProxyConfig.kt`, `SettingsDataStore.kt`, `SettingsKeys` — the existing `PROXY_PAC_URL` string field handles both HTTP URLs and `content://` URIs transparently.

---

## Implementation Order

1. **`proxy/ProxyResolver.kt`** — Context param, `fetchPacFromUri()`, routing in `resolveProxy()`, factory update
2. **`SettingsViewModel.kt`** — `onPacFileSelected()`, `validatePacUrl()` update
3. **`SettingsScreen.kt`** — SAF launcher, Browse button, filename hint
4. **Tests** — SettingsViewModelTest (6 tests), ProxyResolver instrumented tests

---

## Edge Cases & Considerations

| Scenario | Handling |
|---|---|
| User selects a non-JS / non-PAC file | No MIME enforcement beyond picker hint. The JS engine will fail to evaluate and return null (same as invalid PAC content from HTTP). Safe fallback. |
| File is deleted or permissions revoked after selection | `fetchPacFromUri()` throws → returns null → resolver falls back to DIRECT. User sees "Test Proxy/PAC" failure. Can add a UI hint on next load attempt. |
| User switches back to URL input after selecting a file | The `content://` URI is replaced by the new HTTP URL text. Cache clears, new source takes effect. Normal flow. |
| Large PAC files (e.g., corporate scripts > 1MB) | Read via `bufferedReader().readText()` — could be slow on large files. Existing 10s read timeout applies. |
| Multiple PAC file selections in quick succession | Each selection triggers debounced save + cache clear (same as URL typing). No special handling needed. |
| URI permissions across device reboots | `content://` URIs grant temporary permissions. If file is still accessible, system re-grants implicitly. If not, fetch fails gracefully as DIRECT. |

---

## Not In Scope (Future Enhancements)

- **Edit PAC content inline**: A text editor composable showing/fetching PAC script content for review
- **PAC content hash caching**: Cache by file content hash instead of URI string (handles re-selection of same file with different content)
- **"Recent files" list**: Persist last N selected file URIs for quick re-selection
- **MIME type enforcement**: Strict checking that selected file is actually a JS/PAC file before accepting
- **Permission restoration UI**: Explicit prompt if file access is revoked (currently fails silently as DIRECT)
