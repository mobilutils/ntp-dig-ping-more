import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    val releaseKeystore = file(".keystore/my-release.keystore")
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "1PassIs1PassWaitNoMaybe?"
                keyAlias = "my-release-key"
            }
        }
    }
    namespace = "io.github.mobilutils.ntp_dig_ping_more"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mobilutils.ntp_dig_ping_more"
        minSdk = 26
        targetSdk { version = release(rootProject.extra["defaultTargetSdkVersion"] as Int) }
        versionCode = 9
        versionName = "2.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            versionNameSuffix = "-dev"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // commons-net ships its own LICENSE / NOTICE files – exclude them to avoid
            // merge conflicts in the APK's META-INF directory.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/INDEX.LIST"       // dnsjava
            excludes += "META-INF/io.netty.versions.properties" // netty (transitive)
        }
    }
    buildToolsVersion = "36.0.0"
}

jacoco {
    toolVersion = "0.8.12"
}

// Add JaCoCo report task to match documentation
tasks.register("jacocoUnitTestReport", JacocoReport::class) {
    dependsOn("createDebugUnitTestCoverageReport")
    group = "verification"
    description = "Generate JaCoCo coverage reports"
    
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
    
    // Configure the execution data file
    executionData.setFrom(fileTree("app/build/jacoco/"))
    
    // Configure class directories
    classDirectories.setFrom(fileTree("app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/"))
    
    // Configure source directories
    sourceDirectories.setFrom(fileTree("app/src/main/java/"))
}

tasks.withType<Test> {
    maxHeapSize = "2g"
    jvmArgs("-XX:+UseG1GC")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended) // Error, NetworkCheck, WifiOff, etc.

    // ViewModel + Compose integration
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Apache Commons Net – NTP client
    implementation(libs.commons.net)

    // Preferences DataStore – persistent history
    implementation(libs.amdx.datastore.preferences)

    // Navigation – Compose NavHost / BottomNavigation
    implementation(libs.androidx.navigation.compose)

    // dnsjava – full DNS resolution (records, TTL, CNAME chains)
    implementation(libs.dnsjava)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
