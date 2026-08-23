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
    testImplementation(kotlin("test"))
    // Flow tests verify real exports through the format detectors.
    testImplementation(project(":mpc3"))
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
}
