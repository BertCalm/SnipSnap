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
    // The one native library (the SURFACE engine, app/src/main/cpp). Pinned
    // so every machine - CI's runner included - compiles the same toolchain;
    // AGP fetches this NDK through the SDK manager when it is not installed.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.snipsnap.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-m0"
        // The on-device suite (src/androidTest): Compose UI tests that run
        // on an emulator or a plugged-in phone, `:app:connectedDebugAndroidTest`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        // Oboe ships as a prefab package inside its AAR; this lets CMake
        // find_package(oboe) it instead of vendoring the source.
        prefab = true
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

    testOptions {
        // A UI test waits for the screen to settle; system animations would
        // make "settled" a moving target.
        animationsDisabled = true
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

    // FileProvider (SHARE / BACKUP hand files out as content URIs).
    implementation("androidx.core:core-ktx:1.13.1")

    // Oboe — the low-latency callback under the SURFACE engine (native,
    // consumed through prefab). Deliberately the only native dependency.
    implementation("com.google.oboe:oboe:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // TapeOS is fully custom-drawn: foundation only, no Material — the
    // design system's bevels and LCDs owe nothing to any stock theme.
    implementation("androidx.compose.foundation:foundation")
    // The reels on OUTSIDE's LCD strip turn on an infinite transition.
    implementation("androidx.compose.animation:animation-core")
    // LoopGrid (plan-03) draws with material3 Text and a lifecycle scope;
    // the TapeOS screens stay foundation-only.
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The on-device suite: Compose's own test rule and finders, driving a
    // screen on an emulator the way a finger would. The manifest artifact
    // gives the debug build the plain activity the rule hosts content in.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
