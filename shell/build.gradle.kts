plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":audio"))
    implementation(project(":kit"))
    implementation(project(":xpm"))
    implementation(project(":json"))
    // The starter-kit registry renders through the synth engines.
    implementation(project(":synth"))
    // Groove capture hands the app Mpc3Clip values; the flow tests also
    // verify real exports through the format detectors.
    implementation(project(":mpc3"))
    testImplementation(kotlin("test"))
}

// Java 17 bytecode so the Android app can consume this module directly —
// this module IS the app's brain; :app binds Compose to it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }

    // ConventionTest's source-scanning laws (BackHandler registration, the
    // bounded-decode rule, the kit-write lock rule) read :app's Kotlin
    // sources as DATA rather than importing them, so Gradle has no way to
    // know this test depends on them. Without this declaration the test is
    // UP-TO-DATE after an :app-only change — which is precisely the change
    // it exists to police. It would go green having never re-run, and a
    // guardrail that doesn't re-run is worse than none, because the green
    // is trusted.
    //
    // Declaring the directory as an input is not a new dependency or a new
    // plugin; it just tells Gradle the truth about what this task reads.
    // `withPropertyName` keeps the build cache key stable, and RELATIVE
    // path sensitivity means moving the checkout doesn't invalidate it.
    inputs.dir(layout.projectDirectory.dir("../app/src/main/kotlin"))
        .withPropertyName("appSourcesScannedByConventionTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
