# JaCoCo Code Coverage Setup Guide

## Overview
This project uses JaCoCo (Java Code Coverage) to measure test coverage for unit tests and instrumented tests.

## Setup Completed

### 1. Plugin Configuration
Added JaCoCo plugin to `app/build.gradle.kts`:
```kotlin
plugins {
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}
```

### 2. Build Type Configuration
Enabled coverage in debug build type:
```kotlin
buildTypes {
    debug {
        enableUnitTestCoverage = true
        enableAndroidTestCoverage = true
    }
}
```

### 3. Memory Configuration
Increased heap size in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=false
```

Increased test heap in `app/build.gradle.kts`:
```kotlin
tasks.withType<Test> {
    maxHeapSize = "2g"
    jvmArgs("-XX:+UseG1GC")
}
```

## Available Tasks

### Run Unit Tests with Coverage Report
```bash
./gradlew jacocoUnitTestReport
```
This will:
1. Run all unit tests with coverage instrumentation
2. Generate HTML report at: `app/build/reports/jacoco-unit/html/index.html`
3. Generate XML report at: `app/build/reports/jacoco-unit/jacoco-unit.xml`

### Run Tests Only (Without Report)
```bash
./gradlew testDebugUnitTest
```

### Run Specific Test Class
```bash
./gradlew testDebugUnitTest --tests "io.github.mobilutils.ntp_dig_ping_more.DigViewModelTest"
```

## Viewing Reports

After running `./gradlew jacocoUnitTestReport`, open the HTML report:

```bash
# macOS
open app/build/reports/jacoco-unit/html/index.html

# Linux
xdg-open app/build/reports/jacoco-unit/html/index.html
```

The report shows:
- **Line Coverage**: Which lines of code were executed
- **Branch Coverage**: Which conditional branches were taken
- **Method Coverage**: Which methods were called
- **Class Coverage**: Which classes were loaded and tested

## Coverage Exclusions

The following are automatically excluded from coverage reports:
- Android generated code (R.class, BuildConfig, Manifest)
- Dagger/Hilt generated code
- Lambda classes
- Compose generated code (ComposableSingletons)

## CI/CD Integration

### GitHub Actions Example
```yaml
- name: Run Tests with Coverage
  run: ./gradlew jacocoUnitTestReport

- name: Upload Coverage Report
  uses: actions/upload-artifact@v4
  with:
    name: coverage-report
    path: app/build/reports/jacoco-unit/html/

- name: Upload Coverage to Codecov
  uses: codecov/codecov-action@v4
  with:
    file: app/build/reports/jacoco-unit/jacoco-unit.xml
    flags: unittests
```

## Coverage Thresholds (Optional)

You can enforce minimum coverage thresholds by adding to `app/build.gradle.kts`:

```kotlin
tasks.jacocoUnitTestReport {
    violationRules {
        rule {
            limit {
                minimum = "0.60".toBigDecimal() // 60% minimum coverage
            }
        }
        rule {
            element = "CLASS"
            limit {
                minimum = "0.50".toBigDecimal() // 50% per class
            }
            excludes = listOf(
                "**/*Activity*",
                "**/*Composable*"
            )
        }
    }
}
```

## Troubleshooting

### OutOfMemoryError
If tests fail with OutOfMemoryError:
1. Increase heap in `gradle.properties`: `org.gradle.jvmargs=-Xmx4096m`
2. Increase test heap in `app/build.gradle.kts`: `maxHeapSize = "2g"`
3. Stop Gradle daemon: `./gradlew --stop`
4. Run with `--no-daemon`: `./gradlew jacocoUnitTestReport --no-daemon`

### No Coverage Data
If report shows 0% coverage:
1. Ensure `enableUnitTestCoverage = true` in build type
2. Clean and rebuild: `./gradlew clean testDebugUnitTest jacocoUnitTestReport`
3. Check execution data exists: `ls app/build/jacoco/`

### Coverage Missing for Specific Classes
Check the exclusion filters in the JaCoCoReport task. Classes matching exclusion patterns won't be included.

## Best Practices

1. **Run coverage on clean builds**: `./gradlew clean jacocoUnitTestReport`
2. **Check HTML reports**: More readable than XML
3. **Focus on new code**: Ensure new code has adequate test coverage
4. **Set realistic thresholds**: Start with 60-70%, increase over time
5. **Exclude generated code**: Don't count auto-generated code in coverage
6. **Combine with quality gates**: Use coverage as one metric, not the only one

## Current Coverage Status

As of 2026-04-18:
- **Total Tests**: 174+ unit tests
- **Test Files**: 10 test files
- **ViewModels Tested**: 8 out of 9 (all except PingViewModel)

### Coverage by Module (Estimated)
- **ViewModels**: High coverage (state management, input validation, error handling)
- **Repositories**: Partial (some use Android APIs that are hard to mock)
- **History Stores**: Full coverage (parsing logic tested)
- **UI Components**: Not yet covered (requires instrumented tests)

## Next Steps

1. Fix failing tests to get accurate coverage measurements
2. Add PingViewModel tests
3. Add instrumented tests for Compose UI
4. Set up automated coverage gates in CI
5. Integrate with Codecov or similar service for coverage tracking
6. Create dashboard to track coverage trends over time

## References

- [JaCoCo Official Documentation](https://www.jacoco.org/jacoco/trunk/doc/index.html)
- [Android Code Coverage Guide](https://developer.android.com/studio/test/command-line#run-tests-gradle)
- [Gradle JaCoCo Plugin](https://docs.gradle.org/current/userguide/jacoco_plugin.html)
