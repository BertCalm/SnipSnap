// The Android shell. settings.gradle.kts includes :app only when an SDK
// is located, so a session with none does not configure it at all. CI's
// android-build job assembles it. See app/README.md.
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    google()
    mavenCentral()
}

// Compose instrumentation shares the app's Kotlin runtime. Use the normal
// unshrunk test shape; verify the optimized listening APK separately.
val creaseStudyDeviceTests = providers.gradleProperty("creaseStudyDeviceTests").getOrElse("false").toBooleanStrict()
val creaseStudyEnabled = creaseStudyDeviceTests || providers.gradleProperty("creaseStudyCache").isPresent ||
    providers.gradleProperty("creaseStudyBuild").getOrElse("false").toBooleanStrict()


android {
    namespace = "com.snipsnap.app"
    compileSdk = 35
    // The native library (app/src/main/cpp): SurfaceEngine, PadEngine,
    // LiveSnapEngine, and the JNI bridge. The host harness names those
    // sources again; scripts/check_native_sources.py checks the lists agree.
    // The NDK is pinned so every machine, CI's runner included, compiles
    // the same toolchain; AGP fetches it through the SDK manager when it
    // is not installed.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.snipsnap.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
        debug {
            // Keep the established development package so updates retain its shelf.
            // The launcher label describes the whole development app, not one voice.
            applicationIdSuffix = ".brassgrow"
            versionNameSuffix = "-development"
        }
        if (creaseStudyEnabled) create("creaseStudy") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".creasestudy"
            versionNameSuffix = "-crease-study"
            isMinifyEnabled = !creaseStudyDeviceTests
            isShrinkResources = !creaseStudyDeviceTests
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "crease-study-rules.pro")
            matchingFallbacks += listOf("debug")
            ndk { abiFilters += providers.gradleProperty("creaseStudyAbi").getOrElse("arm64-v8a") }
        }
        create("strikeFx") {
            // A separately installable listening build. CI debug signing keys
            // are ephemeral, so replacing yesterday's audition can fail to
            // install. A separate identity also keeps the player's shelf safe.
            initWith(getByName("debug"))
            applicationIdSuffix = ".strikefx"
            versionNameSuffix = "-strike-fx"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    if (creaseStudyEnabled) sourceSets.getByName("creaseStudy").assets.srcDir(layout.buildDirectory.dir("generated/creaseStudyAssets"))

    testBuildType = providers.gradleProperty("snipsnapTestBuildType").getOrElse(if (creaseStudyDeviceTests) "creaseStudy" else "debug")

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

    // CameraX — LIVE's own preview + frame analysis (PHOTO_SPECS.md §8).
    // camera-view for PreviewView (wrapped in an AndroidView, TapeOS draws
    // everything else itself); camera-lifecycle to bind to the screen's
    // own LocalLifecycleOwner rather than a manual open/close.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // The on-device suite: Compose's own test rule and finders, driving a
    // screen on an emulator the way a finger would. The manifest artifact
    // gives the debug build the plain activity the rule hosts content in.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Audio binaries stay outside Git. An explicit measured input is mandatory for
// assembling this study, while ordinary builds and study compiler/tests stay independent.
val prepareCreaseStudyCache by tasks.registering(Exec::class) {
    val source = providers.gradleProperty("creaseStudyCache")
    val output = layout.buildDirectory.dir("generated/creaseStudyAssets/crease")
    inputs.property("cachePath", source.getOrElse("missing"))
    inputs.files(rootProject.file("scripts/crease-release/prepare_phone.py"), rootProject.file("scripts/crease-release/verify_phone.py"))
    inputs.files(fileTree("src/main/cpp/crease"), file("src/main/cpp/OboeOutput.h"))
    if (source.isPresent) inputs.files(fileTree(source.get()) {
        include("manifest.json", "report.json", "reader-verification.json", "native-verification.json", "*.cpcb")
    })
    outputs.dir(output)
    doFirst {
        require(source.isPresent) { "Set -PcreaseStudyCache=/absolute/path/to/the/verified/study" }
        commandLine("python3", rootProject.file("scripts/crease-release/prepare_phone.py"), source.get(), output.get().asFile)
    }
}
tasks.matching { it.name == "mergeCreaseStudyAssets" }.configureEach { dependsOn(prepareCreaseStudyCache) }
