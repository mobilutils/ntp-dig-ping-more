# Fixed the warnings and errors in app/build.gradle.kts 2026-04-20

by performing the following steps:
1.Updated compileSdk to 37 to resolve the "newer version available" warning.
2.Migrated to AGP 9.0's Built-in Kotlin:
 ◦Removed the kotlin-android plugin from app/build.gradle.kts as it is no longer required with built-in Kotlin support in AGP 9.0+.
 ◦Removed the deprecated kotlinOptions block. Since built-in Kotlin is used, jvmTarget now defaults to the value set in compileOptions.targetCompatibility (Java 11), making the explicit kotlinOptions redundant.
3.Refactored JaCoCo Task: Removed the redundant qualifier for JacocoReport to address the "redundant qualifier" warning.
4.Cleaned up imports: Removed the now unused JvmTarget import.
5.Project-wide configuration: Updated the defaultTargetSdkVersion in the root build.gradle.kts and enabled android.newDsl=true in gradle.properties to align with modern AGP requirements.
```Kotlin
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "io.github.mobilutils.ntp_dig_ping_more"
    compileSdk = 37
    // ... rest of the file
}
```
The remaining warning regarding the android { block is a deprecation notice from AGP 9.0 indicating a transition to a new DSL. While android { remains the standard way to configure the plugin, it now internally maps to ApplicationExtension instead of the legacy BaseAppModuleExtension.
