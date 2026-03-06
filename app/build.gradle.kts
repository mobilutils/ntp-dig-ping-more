plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mobilutils.ntp_dig_ping_more"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mobilutils.ntp_dig_ping_more"
        minSdk = 26
        targetSdk { version = release(rootProject.extra["defaultTargetSdkVersion"] as Int) }
        versionCode = 3
        versionName = "2.0"

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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
