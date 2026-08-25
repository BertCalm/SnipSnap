plugins {
    id("com.android.application") version "8.13.0"
    kotlin("android") version "2.0.21"
    // Kotlin 2.0 moved the Compose compiler into its own plugin, versioned in
    // lockstep with the Kotlin compiler itself; the old
    // composeOptions { kotlinCompilerExtensionVersion } block no longer applies.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.snipsnap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snipsnap.app"
        minSdk = 29 // AudioPlaybackCapture; see docs/CONCEPT.md
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":loop"))
    implementation(project(":kit"))
    implementation(project(":audio"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
