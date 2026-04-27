# Project Health Report — 2026-04-26

**Project:** android-ntp_dig_ping_more  
**Version:** 2.6 (versionCode 9)  
**Branch:** main (tag: v2.6)  
**Last commit:** `1f2b71d` — Release 2.6  
**Working tree:** clean (no uncommitted changes)

---

## 1. Build Status

### Compile — ✅ PASS

Both `assembleDebug` and `assembleRelease` compile successfully.

**Warnings (4 deprecated AGP flags):**

| Deprecated Flag | Current Value | Default | Impact |
|---|---|---|---|
| `android.usesSdkInManifest.disallowed` | `false` | `true` | Will be removed in AGP 10 |
| `android.sdk.defaultTargetSdkToCompileSdkIfUnset` | `false` | `true` | Will be removed in AGP 10 |
| `android.enableAppCompileTimeRClass` | `false` | `true` | Will be removed in AGP 10 |
| `android.r8.optimizedResourceShrinking` | `false` | `true` | Will be removed in AGP 10 |
| `android.defaults.buildfeatures.resvalues` | `true` | `false` | Will be removed in AGP 10 |

**Compile warnings (2):**

1. `BulkActionsRepository.kt:118` — Dead code: condition always `false`
2. `Theme.kt:50` — `Window.statusBarColor` setter is deprecated (use `InsetsController`)

**No compile errors.**

---

## 2. Test Status

### Unit Tests — ❌ 4 FAILURES (out of 214)

| # | Test | Failure | Root Cause |
|---|---|---|---|
| 1 | `PortScannerViewModelTest.stopScan cancels job and sets not running` | `IllegalStateException: Module with the Main dispatcher had failed to initialize` | Test uses `StandardTestDispatcher` but `PortScannerViewModel` internally calls `Dispatchers.IO` which triggers `Dispatchers.getMain` during coroutine creation — `Dispatchers.setMain()` is not properly scoped in this test |
| 2 | `TracerouteViewModelTest.startTraceroute sets isRunning and clears output` | `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` | Same Main dispatcher issue; `TracerouteViewModel` spawns coroutines that call `Dispatchers.getMain` before `setMain()` takes effect |
| 3 | `GoogleTimeSyncViewModelTest.syncTime handles Timeout error` | `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` | Same pattern — ViewModel creates coroutines before test dispatcher is fully wired |
| 4 | `NtpViewModelTest.initial state has default values` | `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` | Same — `SimpleNtpViewModel` creates coroutines on init that need `Dispatchers.IO` |

**Common root cause:** All 4 failures share the same pattern — `StandardTestDispatcher` is set via `Dispatchers.setMain()`, but the ViewModels' internal coroutine builders (e.g., `viewModelScope.launch`, `launch(Dispatchers.IO)`) trigger `Dispatchers.getMain()` during coroutine construction, which fails because Android's `Looper.getMainLooper()` is not mocked on JVM.

**Affected test files:**
- `PortScannerViewModelTest.kt` (line 75)
- `TracerouteViewModelTest.kt` (line 96)
- `GoogleTimeSyncViewModelTest.kt` (line 134)
- `NtpViewModelTest.kt` (line 48)

**Note:** The previous run (`build`) showed 2 failures (PortScanner + Traceroute). The separate `test` run showed 4 (added GoogleTimeSync + NtpViewModel). These are likely timing-dependent — all 4 share the same underlying issue.

---

## 3. Lint Status

### Lint — ❌ 1 ERROR, 41 WARNINGS

**Error (build-breaking):**

| Location | Issue | Severity |
|---|---|---|
| `SystemInfoRepository.kt:170` | `Build.getSerial()` called without `READ_PRIVILEGED_PHONE_STATE` permission ([MissingPermission]) | **Error** — causes `BUILD FAILED` |

**41 warnings** (full report: `app/build/reports/lint-results-debug.html`). Typical categories:
- Deprecated API usage (e.g., `WifiManager.connectionInfo`, `StatusBarColor`)
- Missing `@RequiresPermission` annotations
- Redundant null checks
- Hardcoded strings

---

## 4. CI/CD Status

### Workflow: `build.yml` (every push/PR) — ✅ OK

- Runs `./gradlew assembleDebug`
- Uploads debug APK as artifact
- **No test step** — unit tests are not run in CI

### Workflow: `android-signed-apk.yml` (tag `v*`) — ⚠️ Needs attention

- Builds release APK, signs it, publishes as GitHub Release
- **Requires:** Settings > Actions > General > "Workflow permissions: Read and write"
- **Required secrets:** `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`
- Uses `BUILD_TOOLS_VERSION: "34.0.0"` — may need updating if build tools version > 34

---

## 5. Dependencies

### Version Catalog (`gradle/libs.versions.toml`)

| Dependency | Version | Notes |
|---|---|---|
| AGP | 9.2.0 | Latest stable; 5 deprecated flags active |
| Kotlin | 2.2.10 | Latest stable |
| Compose BOM | 2025.01.00 | Current |
| Commons Net | 3.11.1 | Current |
| dnsjava | 3.6.2 | Current |
| MockK | 1.13.12 | Current |
| Coroutines Test | 1.8.1 | Current |
| DataStore | 1.1.1 | Current |
| Navigation Compose | 2.8.9 | Current |

**No obvious outdated dependencies.** All versions are recent.

### SDK Targets

| Setting | Value |
|---|---|
| compileSdk | 37 |
| minSdk | 26 |
| targetSdk (release) | 37 |
| targetSdk (debug) | 37-dev |

---

## 6. Code Quality Issues

### High Priority

1. **Lint error blocks builds** — `Build.getSerial()` at `SystemInfoRepository.kt:170` needs `@RequiresPermission(READ_PRIVILEGED_PHONE_STATE)` or a `@SuppressLint` annotation.

2. **4 test failures** — All stem from coroutine dispatcher setup. The fix is to use `TestDispatcher` properly in the ViewModel constructors or use `runTest { withContext(Dispatchers.IO) { ... } }` pattern.

### Medium Priority

3. **5 deprecated AGP flags** in `gradle.properties` — Will be removed in AGP 10. Should be cleaned up:
   - `android.enableAppCompileTimeRClass=false` → remove (default `true`)
   - `android.r8.optimizedResourceShrinking=false` → remove (default `true`)
   - `android.defaults.buildfeatures.resvalues=true` → remove (default `false`)
   - `android.usesSdkInManifest.disallowed=false` → remove (default `true`)
   - `android.sdk.defaultTargetSdkToCompileSdkIfUnset=false` → remove (default `true`)

4. **Dead code** — `BulkActionsRepository.kt:118` has an always-false condition.

5. **Deprecated API** — `Theme.kt:50` uses deprecated `Window.statusBarColor` setter.

6. **No instrumentation tests** — No `androidTest/` Kotlin files found. Only unit tests exist.

### Low Priority

7. **`enableUnitTestCoverage = true`** in debug build type — JaCoCo reports are generated for debug builds but the `jacocoUnitTestReport` task points to `app/build/jacoco/` which may not match the actual output path.

8. **`compileSdk = 37`** — SDK 37 may not be fully stable; verify compatibility with installed SDK platform.

---

## 7. Project Statistics

| Metric | Value |
|---|---|
| Total unit tests | 214 |
| Passing | 210 |
| Failing | 4 |
| Pass rate | 98.1% |
| Instrumentation tests | 0 |
| Lint errors | 1 |
| Lint warnings | 41 |
| Uncommitted changes | 0 |
| Recent tags | v2.6 |
| Kotlin files (main) | ~120+ |
| Kotlin files (test) | 12 |

---

## 8. Recommended Actions (in priority order)

1. **Fix lint error** in `SystemInfoRepository.kt:170` — add `@SuppressLint("MissingPermission")` or `@RequiresPermission(READ_PRIVILEGED_PHONE_STATE)`. This alone will unblock lint.

2. **Fix 4 test failures** — ensure `Dispatchers.setMain(testDispatcher)` is called before any coroutine builder in the test setup. Consider using `StandardTestDispatcher()` inside `runTest` (which auto-sets main dispatcher) instead of manually calling `setMain()`.

3. **Remove 5 deprecated AGP flags** from `gradle.properties` — they are no-ops now and will break in AGP 10.

4. **Add CI test step** — `build.yml` only runs `assembleDebug`. Add `./gradlew test` to catch test failures in CI.

5. **Clean up dead code** at `BulkActionsRepository.kt:118`.

6. **Update `BUILD_TOOLS_VERSION`** in `android-signed-apk.yml` if needed (currently hardcoded to 34.0.0).

---

## 9. Overall Health Score

| Category | Status | Score |
|---|---|---|
| Build (compile) | ✅ | 10/10 |
| Tests | ⚠️ | 7/10 |
| Lint | ❌ | 3/10 |
| CI/CD | ⚠️ | 6/10 |
| Dependencies | ✅ | 9/10 |
| Code quality | ⚠️ | 6/10 |
| **Overall** | **⚠️** | **6.7/10** |

**Summary:** The project compiles cleanly and has a healthy dependency stack. The main blockers are a lint error that breaks lint-as-failure builds and 4 coroutine-dispatcher-related test failures. These are straightforward fixes that would bring the project to a fully green state.
