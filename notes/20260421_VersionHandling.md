# Version Handling

## Options Considered

### Option 1: `versionNameSuffix` per Build Type
- Appends a suffix to `versionName` based on the build variant.
- **Retained.**

### Option 2: Separate `version.properties` File
- External properties file for version metadata.
- More flexible but adds file maintenance overhead.

### Option 3: Product Flavors (Multiple Release Channels)
- Supports parallel release/beta channels (e.g., Google Play stable + beta tracks).
- More complex, 4 APK variants (debug/release × release/beta).

## Implementation (Option 1 Retained)

```kotlin
// app/build.gradle.kts

defaultConfig {
    versionName = "2.3"
}

buildTypes {
    debug {
        versionNameSuffix = "-dev"
    }
    release {
        // no suffix
    }
}
```

## Result

| Variant   | Version Name |
|-----------|-------------|
| Debug     | `2.3-dev`   |
| Release   | `2.3`       |
