# Automated Testing Guide

This project now has a comprehensive unit test suite for testing business logic, ViewModels, and data parsing.

## Test Structure

```
app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/
├── LanScannerRepositoryTest.kt           # IP conversion utilities (pure functions)
├── HistoryStoreParsingTest.kt            # History serialization/deserialization logic
├── NtpViewModelTest.kt                   # NTP ViewModel with MockK
├── GoogleTimeSyncViewModelTest.kt        # Google Time Sync ViewModel with MockK
├── PortScannerViewModelTest.kt           # Port Scanner ViewModel
├── DigViewModelTest.kt                   # DNS Dig ViewModel with MockK
├── TracerouteViewModelTest.kt            # Traceroute ViewModel with MockK
├── LanScannerViewModelTest.kt            # LAN Scanner ViewModel with MockK
├── HttpsCertViewModelTest.kt             # HTTPS Certificate ViewModel with MockK
└── deviceinfo/
    └── DeviceInfoViewModelTest.kt        # Device Info ViewModel with MockK
```

## Running Tests

### Run all unit tests
```bash
./gradlew test
```

### Run specific test class
```bash
./gradlew test --tests "NtpViewModelTest"
```

### Run specific test method
```bash
./gradlew test --tests "NtpViewModelTest.checkReachability handles DNS failure"
```

### Run with detailed output
```bash
./gradlew test --info
```

## Test Coverage

### ✅ Completed Tests

1. **LanScannerRepositoryTest** (18 tests)
   - `longToIp()` - IP long-to-string conversion
   - `ipToLong()` - IP string-to-long conversion with validation
   - Edge cases: invalid IPs, out-of-range octets, format errors

2. **HistoryStoreParsingTest** (30+ tests)
   - NtpHistoryStore parsing (pipe-delimited, backward compatibility)
   - PingHistoryStore parsing (enum + old boolean format)
   - TracerouteHistoryStore parsing
   - PortScannerHistoryStore parsing (TCP/UDP protocols)
   - DigHistoryStore parsing (SUCCESS/FAILED status)
   - GoogleTimeSyncHistoryStore parsing (with offset/rtt)
   - HttpsCertHistoryStore parsing (with pipe character in summary)
   - LanScannerHistoryStore parsing (JSON-based)
   - PingViewModel `computeStatus()` logic (ALL_SUCCESS/PARTIAL/ALL_FAILED)

3. **NtpViewModelTest** (14 tests)
   - State mutations (`onServerAddressChange`, `onPortChange`)
   - Port input validation (digits only, max 5 chars)
   - `checkReachability()` with mocked repository
   - Error handling (DNS failure, timeout, no network)
   - History deduplication and capping at 5 entries
   - Coroutine cancellation

4. **GoogleTimeSyncViewModelTest** (14 tests)
   - URL blank-fallback to default
   - Success state with time data
   - Error state mapping (NoNetwork, Timeout, HttpError, ParseError, Error)
   - History management (deduplication, capping)
   - `reset()` functionality
   - `selectHistoryEntry()` callback

5. **PortScannerViewModelTest** (12 tests)
   - Input validation (blank host, port ranges)
   - State mutations (host, ports, protocol)
   - Scan lifecycle (start, stop)
   - History management

6. **DigViewModelTest** (14 tests)
   - Initial state validation
   - Input handlers (`onDnsServerChange`, `onFqdnChange`)
   - `runDigQuery()` with mocked repository
   - Error handling (NxDomain, DnsServerError, NoNetwork)
   - History deduplication and capping at 5 entries
   - `selectHistoryEntry()` functionality
   - Coroutine cancellation

7. **TracerouteViewModelTest** (16 tests)
   - Initial state validation
   - Input handler (`onHostChange`)
   - `startTraceroute()` state management
   - `stopTraceroute()` functionality
   - Blank/empty input guards
   - History loading on init
   - History capping at 5 entries
   - `selectHistoryEntry()` functionality
   - Job cancellation

8. **LanScannerViewModelTest** (21 tests)
   - Initial state validation
   - Subnet info loading
   - Input handlers (`onStartIpChange`, `onEndIpChange`)
   - IP range validation (invalid format, reversed range, too large)
   - `startScan()` with full and quick scan modes
   - `stopScan()` functionality
   - Progress tracking
   - Active device detection
   - History management (loading, capping at 10 entries)
   - Error handling

9. **DeviceInfoViewModelTest** (13 tests)
   - Initial Loading state
   - `onPermissionsResult()` state transitions
   - Success state with device info
   - Error handling for repository exceptions
   - PermissionDenied state
   - Periodic updates for time-sensitive fields
   - Device info field validation

10. **HttpsCertViewModelTest** (28 tests)
    - Initial Idle state
    - Default host and port values
    - Input handlers (`onHostChange`, `onPortChange`)
    - Port validation (non-numeric, out of range)
    - `fetchCert()` with all result variants (Success, CertExpired, UntrustedChain, NoNetwork, HostnameUnresolved, Timeout, Error)
    - `selectHistoryEntry()` functionality
    - `reset()` to Idle state
    - History management (loading, deduplication, capping at 5 entries)
    - State transitions and warning messages

### 🚧 Future Test Coverage (Pending)

The following components can be tested using the same patterns established:

1. **PingViewModel** - Ping functionality with Runtime.exec mocking
2. **Compose UI tests** - UI component testing with Compose Test Runner

## Testing Approach

### Pure Function Testing
Functions with no side effects or external dependencies are tested directly:
- IP conversion functions
- String parsing/serialization
- Status derivation logic

### ViewModel Testing with MockK
ViewModels are tested using MockK to mock dependencies:
```kotlin
@Before
fun setup() {
    Dispatchers.setMain(testDispatcher)
    repository = mockk(relaxed = true)
    historyStore = mockk(relaxed = true)
    coEvery { historyStore.historyFlow } returns flowOf(emptyList())
    viewModel = SimpleNtpViewModel(repository, historyStore)
}
```

Key patterns:
- Use `StandardTestDispatcher` for coroutine control
- Use `testDispatcher.scheduler.advanceUntilIdle()` to execute coroutines
- Mock suspend functions with `coEvery { ... } returns ...`
- Verify calls with `coVerify { ... }`

### State Testing
All ViewModels expose state via `StateFlow<NtpUiState>` (or similar):
```kotlin
val state = viewModel.uiState.value
assertEquals("pool.ntp.org", state.serverAddress)
assertTrue(state.result is NtpResult.Success)
```

## Testing Dependencies

Added to `gradle/libs.versions.toml`:
```toml
[versions]
mockk = "1.13.12"
coroutinesTest = "1.8.1"

[libraries]
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
```

## Best Practices

1. **Test pure functions first** - No mocking needed, fast execution
2. **Use MockK for dependencies** - Relaxed mocks reduce boilerplate
3. **Control coroutines explicitly** - Use `TestDispatcher` and `advanceUntilIdle()`
4. **Test state transitions** - Verify initial, intermediate, and final states
5. **Test error paths** - Mock failures and verify error handling
6. **Test edge cases** - Empty inputs, invalid formats, boundary conditions
7. **Keep tests isolated** - Each test should be independent
8. **Use descriptive test names** - Backtick names describe the scenario

## Adding New Tests

1. Create test file in `app/src/test/java/io/github/mobilutils/ntp_dig_ping_more/`
2. Use `@RunWith(JUnit4::class)` if needed (optional with JUnit 4.13+)
3. Setup mocks in `@Before` method
4. Use `runTest` coroutine scope for all async tests
5. Assert state changes using standard JUnit assertions

Example template:
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: MyViewModel
    private lateinit var dependency: MyDependency

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dependency = mockk(relaxed = true)
        viewModel = MyViewModel(dependency)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `my test description`() = runTest {
        // Arrange
        coEvery { dependency.someSuspendFunction() } returns expectedResult

        // Act
        viewModel.someMethod()
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertEquals(expectedResult, state.someField)
    }
}
```

## CI/CD Integration

Tests run automatically on:
- Every `./gradlew test` command
- Every `./gradlew build` command
- CI pipelines (configure in `.github/workflows/`)

To add to GitHub Actions:
```yaml
- name: Run Unit Tests
  run: ./gradlew test
```

## Next Steps

1. Add tests for PingViewModel with Runtime.exec mocking strategies
2. Add integration tests in `androidTest/` for UI components
3. Add Compose UI tests for screen interactions using Compose Test Runner
4. Measure test coverage with JaCoCo
5. Set up continuous integration with automated test runs
6. Add parameterized tests for extensive input validation scenarios
