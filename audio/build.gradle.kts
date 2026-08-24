plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // Test-only: round-trip written WAVs back through the reader the exporter uses.
    testImplementation(project(":xpm"))
}

// Java 17 bytecode so the Android app can consume this module directly.
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

/** Generate the hardware acceptance kits into testkit/. See TestKitGenerator. */
tasks.register<JavaExec>("generateTestKits") {
    group = "distribution"
    description = "Generate the MPC hardware acceptance kits under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.audio.TestKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Write the decode-contract fixture WAVs into reference/fixtures/decode/. */
tasks.register<JavaExec>("generateDecodeFixtures") {
    group = "distribution"
    description = "Write the DecodeContract fixture WAVs under reference/fixtures/decode/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.audio.DecodeFixtureGenerator")
    workingDir = projectDir
    args("${rootDir}/reference/fixtures/decode")
}
