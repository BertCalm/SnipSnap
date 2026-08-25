plugins {
    id("com.android.application") version "8.13.2"
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

// Java 17 bytecode, matching the eight modules this app is a shell over.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // :shell uses `implementation`, so none of these arrive transitively —
    // the app declares every module it touches.
    implementation(project(":json"))
    implementation(project(":xpm"))
    implementation(project(":audio"))
    implementation(project(":kit"))
    implementation(project(":synth"))
    implementation(project(":shell"))

    // Foundation only. TapeOS is a complete design system; Material would
    // fight it at every surface.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Deliberately absent: androidx.compose.ui:ui-tooling and
    // ui-tooling-preview. They exist to serve @Preview in Android Studio,
    // M0 writes no @Preview, and ui-tooling drags
    // androidx.compose.material onto the debug classpath — which the
    // no-Material constraint forbids. A later milestone that actually
    // wants previews can add them back with an
    // `exclude(group = "androidx.compose.material")`.

    // JUnit 5, as in every other module. Named explicitly rather than via
    // kotlin("test") so the platform launcher is on the runtime classpath.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
