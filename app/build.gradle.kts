// The Android shell — M0 walking skeleton. Pre-written in the cloud
// session (which cannot build it: no Android SDK reachable); the desktop
// session owns compiling, running, and fixing what the compiler finds.
// See app/README.md for the build steps and the verification status.
plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.snipsnap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snipsnap.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-m0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The brain: every module is pure Kotlin/JVM at Java 17 exactly so
    // this app can consume them directly. No algorithm lives up here.
    implementation(project(":json"))
    implementation(project(":xpm"))
    implementation(project(":audio"))
    implementation(project(":kit"))
    implementation(project(":mpc3"))
    implementation(project(":synth"))
    implementation(project(":shell"))
    // The loop engine — plan-03's six-track phasing grid, merged forward.
    implementation(project(":loop"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // TapeOS is fully custom-drawn: foundation only, no Material — the
    // design system's bevels and LCDs owe nothing to any stock theme.
    implementation("androidx.compose.foundation:foundation")
    // LoopGrid (plan-03) draws with material3 Text and a lifecycle scope;
    // the TapeOS screens stay foundation-only.
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
