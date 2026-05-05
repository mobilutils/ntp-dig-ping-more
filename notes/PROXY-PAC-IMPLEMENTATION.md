# Proxy PAC Implementation

## Overview

The **Proxy PAC** feature allows the app to route outbound HTTP/HTTPS traffic through a proxy server determined by a [PAC (Proxy Auto-Config)](https://developer.mozilla.org/en-US/docs/Web/HTTP/Proxy_server_configuration) JavaScript script. This is useful in corporate or restricted networks where outbound traffic must pass through a proxy.

The system integrates with three network-based tools:
- **HTTPS Certificate Inspector** — routes SSL/TLS connections via HTTP CONNECT tunneling
- **Google Time Sync** — routes HTTP requests through the resolved proxy
- **Bulk Actions** — supports per-batch `url-proxypac` in JSON configs, independent of persisted settings

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Settings Screen (UI)                          │
│  ┌──────────────┐  ┌──────────────────┐  ┌────────────────┐   │
│  │ Proxy Toggle  │  │   PAC URL Field  │  │ Test Proxy Btn │   │
│  └──────┬───────┘  └────────┬─────────┘  └────────┬───────┘   │
│         │                    │                       │            │
└─────────┼────────────────────┼───────────────────────┼──────────┘
          │                    │                       │
          ▼                    ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    SettingsViewModel                              │
│  • onProxyEnabledChange()  → toggle + persist                  │
│  • onProxyPacUrlChange()   → debounce (300ms) + validate      │
│  • testProxy()             → launch connectivity test           │
│  • validatePacUrl()        → http/https, non-empty host       │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                   SettingsRepository                               │
│  • proxyConfigFlow: Flow<ProxyConfig>  (reactive DataStore)    │
│  • saveProxyConfig(config)              (atomic save)           │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ProxyResolver                                  │
│  • resolveProxy(targetUrl)  → Proxy?                            │
│  • testProxy()              → ProxyTestResult                   │
│  • clearCache()             → invalidate all caches             │
│  • parsePacResult(pac)     → Proxy? (internal)                 │
│  • fetchPacScript(url)     → String? (internal)                │
│                                                                    │
│  Two-level cache:                                                  │
│  1. PAC Script Cache  (5-min TTL, @Volatile fields)             │
│  2. Per-Host Proxy Cache (5-min TTL, synchronized map)          │
└───┬──────────────────────────────┬──────────────────────────────┘
    │                              │
    ▼                              ▼
┌──────────────────┐    ┌────────────────────────────────────┐
│   JsEngine (I)   │    │  HttpURLConnection (PAC fetch)      │
│  .evaluatePac()  │    │  GET pacUrl, 10s timeout           │
└──────┬───────────┘    └────────────────────────────────────┘
       │
       ▼
┌──────────────────┐
│ QuickJsEngine    │
│ (JsEngine impl)  │
│ • Creates fresh  │
│   QuickJs runtime│
│ • Injects PAC    │
│   utility stubs  │
│ • Evaluates      │
│   FindProxyForURL│
│ • Closes runtime │
└──────────────────┘
```

## Component Breakdown

### 1. `JsEngine` (Interface)

**File:** `app/src/main/java/.../proxy/JsEngine.kt`

```kotlin
interface JsEngine {
    fun evaluatePac(pacScript: String, targetUrl: String, targetHost: String): String
}
```

Abstraction over a JavaScript engine. Implementations must:
- Be safe to call from `Dispatchers.IO`
- Evaluate the standard `FindProxyForURL(url, host)` function
- Return the raw PAC result string (e.g., `"PROXY 10.0.0.1:8080"`, `"DIRECT"`, or a semicolon-separated fallback chain)
- Throw `Exception` if the script is malformed or evaluation fails

### 2. `QuickJsEngine` (Concrete Implementation)

**File:** `app/src/main/java/.../proxy/QuickJsEngine.kt`

Backed by `app.cash.quickjs:quickjs-android-wrapper:0.9.2`.

**Key design decision:** Each `evaluatePac()` call creates a **fresh** `QuickJs` runtime, evaluates, and tears it down in a `finally` block. This makes it safe for concurrent use — no shared mutable state between invocations.

#### PAC Utility Stubs

A minimal set of standard PAC helper functions is prepended to every evaluation so that common corporate PAC scripts work without a full browser environment:

| Function | Implementation | Notes |
|---|---|---|
| `isPlainHostName(host)` | Returns `true` if `host` has no `.` | Full implementation |
| `dnsDomainIs(host, domain)` | Checks if `host` ends with `domain` | Full implementation |
| `localHostOrDomainIs(host, hostdom)` | Exact match or prefix match | Full implementation |
| `isResolvable(host)` | Always returns `true` | Stubbed |
| `isInNet(host, pattern, mask)` | Always returns `false` | Stubbed — would need real DNS |
| `dnsResolve(host)` | Always returns `"127.0.0.1"` | Stubbed — would need Android DNS APIs |
| `myIpAddress()` | Always returns `"127.0.0.1"` | Stubbed — would need network introspection |
| `dnsDomainLevels(host)` | Counts `.`-separated segments minus 1 | Full implementation |
| `shExpMatch(str, shexp)` | Converts shell glob to regex and tests | Full implementation |
| `weekdayRange()` | Always returns `true` | Stubbed |
| `dateRange()` | Always returns `true` | Stubbed |
| `timeRange()` | Always returns `true` | Stubbed |

**TODO in code:** Consider implementing real DNS resolution for `isInNet`/`dnsResolve` if corporate PAC scripts require accurate subnet matching.

#### JS Escaping

The `escapeJs()` method escapes `\` and `'` for safe embedding in JS string literals, preventing injection when URLs or hostnames contain special characters.

#### Evaluation Flow

```kotlin
val engine = QuickJs.create()
try {
    engine.evaluate(PAC_UTILS)              // Inject utility stubs
    engine.evaluate(pacScript)              // Load user's PAC script
    val result = engine.evaluate(
        "FindProxyForURL('$url', '$host');"
    )
    return result?.toString() ?: "DIRECT"
} finally {
    engine.close()                           // Always tear down
}
```

### 3. `ProxyResolver` (Central Orchestrator)

**File:** `app/src/main/java/.../proxy/ProxyResolver.kt`

The core of the system. Fetches PAC scripts, evaluates them via `JsEngine`, and returns `java.net.Proxy` instances.

#### Constructor

```kotlin
class ProxyResolver(
    private val settingsRepository: SettingsRepository,
    private val jsEngine: JsEngine,
    private val staticPacUrl: String? = null,  // override for BulkActions
)
```

- `settingsRepository` — source of persisted `ProxyConfig`
- `jsEngine` — JS engine for PAC evaluation
- `staticPacUrl` — when non-null, bypasses `SettingsRepository` and uses this URL directly (used by BulkActions)

#### Constants

| Constant | Value | Purpose |
|---|---|---|
| `PAC_CACHE_TTL_MS` | 300,000 ms (5 min) | TTL for PAC script and per-host caches |
| `TEST_URL` | `http://connectivitycheck.gstatic.com/generate_204` | Google's connectivity-check endpoint |
| `CONNECT_TIMEOUT_MS` | 10,000 ms | Connect timeout for PAC fetch and test |
| `READ_TIMEOUT_MS` | 10,000 ms | Read timeout for PAC fetch and test |

#### Factory Method for BulkActions

```kotlin
fun forStaticPacUrl(pacUrl: String, context: Context, jsEngine: JsEngine = QuickJsEngine()): ProxyResolver
```

Creates a resolver that uses a static PAC URL directly, without reading from or writing to `SettingsRepository`. This ensures that `url-proxypac` in a BulkActions JSON config does not affect the user's persisted proxy settings.

#### Caching — Two Levels

**Level 1: PAC Script Cache** (in-memory, 5-minute TTL)
```kotlin
@Volatile private var cachedPacScript: String? = null
@Volatile private var cachedPacUrl: String? = null
@Volatile private var pacFetchedAt: Long = 0L
```

If the same PAC URL is requested within 5 minutes, the cached script body is returned without network I/O.

**Level 2: Per-Host Proxy Cache** (in-memory, 5-minute TTL)
```kotlin
private data class CachedProxy(val proxy: Proxy?, val resolvedAt: Long)
private val proxyCache = mutableMapOf<String, CachedProxy>()
```

Keyed by hostname. Access is `synchronized(proxyCache)` for thread safety.

#### `resolveProxy(targetUrl: String): Proxy?`

The main public method. Flow:

1. **Determine effective PAC URL:** uses `staticPacUrl` if set, otherwise reads from `settingsRepository.proxyConfigFlow.first()`
2. **Early exit:** if proxy is disabled or PAC URL is blank, returns `null` (DIRECT)
3. **Extract hostname** from the target URL via `extractHost()`
4. **Check per-host cache:** if within TTL, return cached result immediately
5. **Fetch PAC script:** uses script cache if within TTL, otherwise performs a `GET` request
6. **Evaluate:** calls `jsEngine.evaluatePac(pacScript, targetUrl, host)` to get `FindProxyForURL` result
7. **Parse:** converts the PAC result string into a `Proxy` via `parsePacResult()`
8. **Cache and return:** stores result in per-host cache, returns the proxy
9. **Error handling:** any failure at any step returns `null` (silent fallback to DIRECT)

#### `testProxy(): ProxyTestResult`

Connectivity test that:
1. Calls `resolveProxy(TEST_URL)` to get a proxy
2. Opens an `HttpURLConnection` (proxied or direct) to Google's `generate_204` endpoint
3. Sends a `HEAD` request with 10s connect/read timeouts
4. Returns `ProxyTestResult(success, message, latencyMs)`:
   - On HTTP 2xx: success with latency and "via" info
   - On non-2xx: failure with status code
   - On exception: failure with exception message
5. Always disconnects in `finally`

#### `clearCache()`

Clears both the PAC script cache and per-host proxy cache. Called when the user changes the PAC URL.

#### `parsePacResult(pacResult: String): Proxy?`

Internal method that parses PAC result strings:

| PAC Result | Output |
|---|---|
| `"DIRECT"` | `null` |
| `"PROXY host:port"` | `Proxy(Type.HTTP, InetSocketAddress)` |
| `"SOCKS host:port"` | `Proxy(Type.SOCKS, InetSocketAddress)` |
| `"SOCKS5 host:port"` | `Proxy(Type.SOCKS, InetSocketAddress)` |
| `"SOCKS4 host:port"` | `Proxy(Type.SOCKS, InetSocketAddress)` |
| `"PROXY a:1; PROXY b:2"` | First valid proxy (fallback chain) |
| Empty/unknown directive | `null` (DIRECT) |

- Case-insensitive directive matching
- Port validation: must be 1..65535
- Iterates semicolon-separated entries, returns first valid proxy or falls through to `null`

#### `fetchPacScript(pacUrl: String): String?`

Private helper:
- Checks script cache first (same URL + within TTL)
- Makes a `GET` request with 10s timeouts, follows redirects
- On `HTTP_OK`, reads body as UTF-8, updates cache, returns script
- On any error, returns `null`

#### `extractHost(url: String): String?`

Private helper:
- Tries `URL(url).host` first
- Falls back to manual stripping (`http://`, `https://`, then `/` and `:`) for non-standard strings

### 4. `ProxyConfig` (Data Class)

**File:** `app/src/main/java/.../settings/ProxyConfig.kt`

```kotlin
data class ProxyConfig(
    val enabled: Boolean = false,
    val pacUrl: String = "",
    val lastTested: Long = 0L,
    val lastTestResult: String? = null,
)
```

Persisted in DataStore. Represents the user's proxy configuration.

### 5. `SettingsDataStore` + `SettingsRepository`

**Files:**
- `app/src/main/java/.../settings/SettingsDataStore.kt`
- `app/src/main/java/.../settings/SettingsRepository.kt`

**DataStore preference keys:**

| Key | Type | Preference Name |
|---|---|---|
| `PROXY_ENABLED` | `booleanPreferencesKey` | `"proxy_enabled"` |
| `PROXY_PAC_URL` | `stringPreferencesKey` | `"proxy_pac_url"` |
| `PROXY_LAST_TESTED` | `longPreferencesKey` | `"proxy_last_tested"` |
| `PROXY_LAST_TEST_RESULT` | `stringPreferencesKey` | `"proxy_last_test_result"` |

**Repository API:**
```kotlin
val proxyConfigFlow: Flow<ProxyConfig>       // reactive flow
suspend fun saveProxyConfig(config: ProxyConfig)  // atomic save
```

`saveProxyConfig` handles null `lastTestResult` by removing the key from DataStore.

### 6. `SettingsViewModel` (UI State Management)

**File:** `app/src/main/java/.../SettingsViewModel.kt`

Manages the Settings screen UI state with proxy-related fields in `SettingsUiState`:

| Field | Type | Purpose |
|---|---|---|
| `proxyEnabled` | `Boolean` | Whether proxy routing is active |
| `proxyPacUrl` | `String` | Text in the PAC URL field |
| `proxyPacUrlError` | `String?` | Inline validation error, or null if valid |
| `isTestingProxy` | `Boolean` | True while a proxy test is in progress |
| `proxyTestResult` | `String?` | Human-readable result of last test |
| `proxyLastTested` | `Long` | Epoch millis of last test |

**Key methods:**

- **`onProxyEnabledChange(enabled: Boolean)`** — toggles proxy on/off, updates UI state immediately, persists via `saveCurrentProxyConfig()` in a coroutine

- **`onProxyPacUrlChange(url: String)`** — called on every keystroke:
  1. Updates `proxyPacUrl` immediately, clears `proxyPacUrlError` (UX: no error while typing)
  2. Calls `proxyResolver.clearCache()` — invalidates PAC script and per-host caches
  3. Cancels previous debounce job, starts a new one with **300ms delay**
  4. After debounce: validates URL via `validatePacUrl(url)`
  5. If valid (no error), persists via `saveCurrentProxyConfig()`

- **`testProxy()`** — tests proxy connectivity:
  1. Cancels any previous test job
  2. Sets `isTestingProxy = true`
  3. Launches coroutine calling `proxyResolver.testProxy()`
  4. On completion: updates `proxyTestResult`, `proxyLastTested`, sets `isTestingProxy = false`
  5. Persists test result to DataStore

- **`validatePacUrl(url: String): String?`** — internal validation:
  - Empty/blank URL → `null` (allowed, means "no proxy")
  - Parses as `java.net.URL`
  - Only `http://` and `https://` protocols allowed (FTP rejected)
  - Host must be non-null and non-blank
  - Returns error string on failure, `null` on success

- **`saveCurrentProxyConfig()`** — private, persists current UI state as `ProxyConfig` to DataStore

### 7. `SettingsScreen` (Compose UI)

**File:** `app/src/main/java/.../SettingsScreen.kt`

The proxy configuration section contains:

1. **Enable/Disable toggle** — `Switch` bound to `uiState.proxyEnabled`, calls `viewModel::onProxyEnabledChange`
2. **PAC URL input** — `OutlinedTextField` with:
   - Placeholder: `"http://proxy.corp.com/proxy.pac"`
   - Enabled only when `uiState.proxyEnabled` is `true`
   - Error styling when `uiState.proxyPacUrlError != null`
   - Keyboard type: `KeyboardType.Uri`
3. **Test Proxy/PAC button** — `Button` that:
   - Is enabled only when: proxy enabled, PAC URL non-blank, no validation error, not currently testing
   - Shows `CircularProgressIndicator` while testing
   - Calls `viewModel::testProxy`
4. **Test result display** — shows the last test result in monospace font, color-coded green (success, starts with `✓`) or red (failure), with a formatted timestamp of last test

---

## Integration Points

### GoogleTimeSyncRepository

**File:** `app/src/main/java/.../GoogleTimeSyncRepository.kt`

```kotlin
class GoogleTimeSyncRepository(
    private val proxyResolver: ProxyResolver? = null,
)
```

In `fetchGoogleTime()`:
```kotlin
val proxy = proxyResolver?.resolveProxy(url)
connection = if (proxy != null) {
    URL(url).openConnection(proxy) as HttpURLConnection
} else {
    URL(url).openConnection() as HttpURLConnection
}
```

Created via factory in `GoogleTimeSyncViewModel`:
```kotlin
val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine())
return GoogleTimeSyncViewModel(
    repository = GoogleTimeSyncRepository(proxyResolver),
    ...
)
```

### HttpsCertRepository

**File:** `app/src/main/java/.../HttpsCertRepository.kt`

```kotlin
class HttpsCertRepository(
    private val proxyResolver: ProxyResolver? = null,
)
```

In `fetchCertificate()`:
```kotlin
val proxy = proxyResolver?.resolveProxy("https://$host:$port")
socket = if (proxy != null && proxy.type() == Proxy.Type.HTTP) {
    createProxiedSSLSocket(targetHost, targetPort, proxy, sslContext.socketFactory)
} else {
    // Direct connection (no proxy, or SOCKS)
}
```

For **HTTP proxy**, it uses **HTTP CONNECT tunneling** via `createProxiedSSLSocket()`. For SOCKS proxy or no proxy, it connects directly.

Created via factory in `HttpsCertViewModel`:
```kotlin
val proxyResolver = ProxyResolver(settingsRepo, QuickJsEngine())
return HttpsCertViewModel(
    repository = HttpsCertRepository(proxyResolver),
    ...
)
```

### BulkActionsRepository

**File:** `app/src/main/java/.../BulkActionsRepository.kt`

The `BulkConfig` data class has a `urlProxyPac: String?` field. When present:

1. At the start of `executeCommands()`:
   ```kotlin
   bulkProxyResolver = config.urlProxyPac?.let { buildProxyResolver(it) }
   ```

2. `buildProxyResolver(pacUrl)` uses `ProxyResolver.forStaticPacUrl(pacUrl, context)` — bypasses persisted settings

3. For `checkcert` pseudo-commands:
   ```kotlin
   val repo = bulkProxyResolver?.let { HttpsCertRepository(proxyResolver = it) } ?: certRepo
   ```

4. For `google-timesync` pseudo-commands:
   ```kotlin
   val repo = GoogleTimeSyncRepository(proxyResolver = bulkProxyResolver)
   ```

5. In the `finally` block of `executeCommands()`, `bulkProxyResolver` is set to `null` (cleanup)

The `BulkActionsViewModel` also calls:
- `repository.setupProxyResolver(config.urlProxyPac)` before execution
- `repository.clearProxyResolver()` after execution

This design ensures that the `url-proxypac` value in a JSON config is **scoped to that batch** and does not affect the user's persisted proxy settings.

---

## Error Handling Strategy

The proxy system follows a **fail-silent, fall-through to DIRECT** pattern:

| Failure Point | Behavior |
|---|---|
| Proxy disabled in settings | Returns `null` (DIRECT) |
| PAC URL blank | Returns `null` (DIRECT) |
| PAC script fetch fails (network error, non-200) | Returns `null` (DIRECT) |
| JS evaluation throws | Returns `null` (DIRECT) |
| PAC result is unparseable | Returns `null` (DIRECT) |
| Port out of range (not 1..65535) | Skips to next entry in fallback chain |
| `resolveProxy` outer try-catch | Catches all exceptions, returns `null` |

The proxy feature is **non-blocking**: if anything goes wrong, the app simply uses a direct connection rather than failing the entire operation.

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `app.cash.quickjs:quickjs-android-wrapper` | `0.9.2` | JS engine for PAC script evaluation |
| `androidx.datastore:datastore-preferences` | `1.1.1` | Persistent storage for proxy config |

Declared in:
- `gradle/libs.versions.toml` (version catalog: `quickjs = "0.9.2"`)
- `app/build.gradle.kts` (consumed as `implementation(libs.quickjs)`)

---

## Thread Safety

| Component | Mechanism |
|---|---|
| PAC Script Cache | `@Volatile` fields on `cachedPacScript`, `cachedPacUrl`, `pacFetchedAt` |
| Per-Host Proxy Cache | `synchronized(proxyCache)` on all read/write access |
| QuickJsEngine | Fresh runtime per call — no shared state |
| SettingsViewModel | Debounce job cancellation prevents concurrent validation |
| ProxyResolver | `withContext(Dispatchers.IO)` for all suspend methods |

---

## Test Coverage

### ProxyResolverTest.kt (18 tests)

**`parsePacResult` — 14 tests:**
- `DIRECT` returns `null`
- `PROXY host:port` parses correctly (HTTP type)
- `SOCKS host:port` parses correctly (SOCKS type)
- `SOCKS5 host:port` parses correctly
- Fallback chain: uses first valid entry
- Fallback chain: skips malformed proxy, falls to DIRECT
- Fallback chain: uses second proxy when first is malformed
- Empty string returns `null`
- Unknown directive returns `null`
- Case-insensitive directive matching
- Extra whitespace handling
- Invalid port (99999) returns `null`
- Missing port returns `null`

**`resolveProxy` — 3 tests:**
- Returns `null` when proxy is disabled
- Returns `null` when PAC URL is blank
- Returns `null` when JS engine throws

**`clearCache` — 1 test:**
- Does not throw

Note: Network I/O (PAC script fetching) and JS evaluation are **mocked** in these tests.

### SettingsViewModelTest.kt (18 proxy/PAC-related tests)

- Initial state: proxy disabled, empty PAC URL
- Loading persisted proxy config from DataStore
- `onProxyEnabledChange` updates state and saves config
- `validatePacUrl`: empty string (OK), valid HTTP (OK), valid HTTPS (OK), FTP (rejected), malformed URL (rejected), URL without host (rejected)
- `onProxyPacUrlChange`: updates URL immediately, clears error while typing, clears proxy cache
- `testProxy`: sets `isTestingProxy` during run, updates result on success/failure, saves to settings

### BulkConfigParserTest.kt (4 tests for `url-proxypac`)

- Parses `url-proxypac` value correctly
- Returns `null` when key is absent
- Returns `null` when value is blank string
- Preserves `url-proxypac` alongside other config fields

---

## Known Limitations

1. **Stubbed PAC functions:** `isInNet()`, `dnsResolve()`, `myIpAddress()`, `weekdayRange()`, `dateRange()`, and `timeRange()` return safe defaults rather than performing real operations. Corporate PAC scripts relying on subnet matching (`isInNet`) or time-based routing (`timeRange`) will not work correctly. A TODO comment in `QuickJsEngine.kt` notes this.

2. **No SOCKS support for HTTPS cert inspection:** The `HttpsCertRepository` only tunnels through HTTP proxies (`Proxy.Type.HTTP`). SOCKS proxies fall through to a direct connection. This is by design — SOCKS tunneling over SSL requires additional socket-level handling.

3. **In-memory cache only:** The PAC script and per-host proxy caches are in-memory only. They are lost on process death. This is acceptable given the 5-minute TTL and the fact that PAC URLs rarely change.

4. **No proxy authentication:** The current implementation does not support proxy authentication (Basic/Digest). Corporate proxies requiring credentials will not work.

5. **Single PAC URL:** The system supports a single PAC URL. It does not support fallback PAC URLs or multiple PAC scripts.

---

## File Index

| File | Role |
|---|---|
| `proxy/JsEngine.kt` | Interface for JS evaluation |
| `proxy/QuickJsEngine.kt` | QuickJS-based implementation of JsEngine |
| `proxy/ProxyResolver.kt` | PAC fetch, cache, evaluate, resolve, test |
| `settings/ProxyConfig.kt` | Data class for persisted proxy config |
| `settings/SettingsDataStore.kt` | DataStore singleton + preference keys |
| `settings/SettingsRepository.kt` | Reactive DataStore accessor |
| `SettingsViewModel.kt` | ViewModel for Settings screen (proxy UI) |
| `SettingsScreen.kt` | Compose UI for Settings (proxy section) |
| `GoogleTimeSyncRepository.kt` | Uses ProxyResolver for Google Time Sync requests |
| `GoogleTimeSyncViewModel.kt` | Factory creates ProxyResolver for GoogleTimeSyncRepository |
| `HttpsCertRepository.kt` | Uses ProxyResolver for HTTPS cert inspection (HTTP CONNECT) |
| `HttpsCertViewModel.kt` | Factory creates ProxyResolver for HttpsCertRepository |
| `BulkActionsRepository.kt` | Uses static PAC URL for bulk command proxy routing |
| `BulkActionsViewModel.kt` | Sets up and tears down proxy resolver for bulk execution |
| `test/ProxyResolverTest.kt` | Unit tests for ProxyResolver |
| `test/SettingsViewModelTest.kt` | Unit tests for SettingsViewModel (proxy/PAC) |
| `test/BulkConfigParserTest.kt` | Unit tests for url-proxypac in JSON config |
