import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    val signingProps = file("signing.properties")
    val releaseKeystore = if (file(".keystore/my-release.keystore").exists()) {
        file(".keystore/my-release.keystore")
    } else {
        rootProject.file(".keystore/my-release.keystore")
    }

    if (signingProps.exists() && releaseKeystore.exists()) {
        val props = Properties().apply {
            signingProps.inputStream().use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = props.getProperty("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = props.getProperty("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = props.getProperty("RELEASE_KEY_PASSWORD") ?: props.getProperty("RELEASE_STORE_PASSWORD") ?: ""
            }
        }
    } else if (!signingProps.exists()) {
        println("WARNING: signing.properties not found. Release builds will fail.")
    }

    namespace = "io.github.mobilutils.ntp_dig_ping_more"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mobilutils.ntp_dig_ping_more"
        minSdk = 26
        targetSdk { version = release(rootProject.extra["defaultTargetSdkVersion"] as Int) }
        versionCode = 41
        versionName = "3.54"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            versionNameSuffix = "-dev"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
            excludes += "META-INF/INDEX.LIST"        // dnsjava
            excludes += "META-INF/io.netty.versions.properties" // netty (transitive)
            excludes += "META-INF/services/java.net.spi.InetAddressResolverProvider"
            excludes += "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor"
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

    // AndroidX JavaScript Sandbox for PAC script evaluation
    implementation(libs.androidx.javascriptengine)
    // Coroutines ↔ Guava ListenableFuture bridge (required by JavaScriptSandbox API)
    implementation(libs.kotlinx.coroutines.guava)

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
