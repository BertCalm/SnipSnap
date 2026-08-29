plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":json"))
    implementation(project(":audio"))
    implementation(project(":kit"))
    testImplementation(kotlin("test"))
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

// Throwaway data generator for Task 7's device check -- writes a session with
// six tracks (chain lengths 2, 3, 2, 1, 4, 2; LCM 12) and their WAVs to
// build/demo-session (or -PoutDir=<path>). Not part of the app; test-scoped
// because it's a data generator, not product code.
tasks.register<JavaExec>("generateDemoSession") {
    group = "snipsnap"
    description = "Writes a throwaway six-track demo session for manual device testing."
    dependsOn("testClasses")
    mainClass.set("com.snipsnap.loop.GenerateDemoSessionKt")
    classpath = sourceSets["test"].runtimeClasspath
    args(project.findProperty("outDir")?.toString() ?: layout.buildDirectory.dir("demo-session").get().asFile.path)
}

// Throwaway diagnostic: bounces a session's full cycle offline through
// Bouncer (the same bake -> mix -> sink path playback uses) and prints
// per-interval RMS/peak, to prove or disprove numerically whether the engine
// actually varies material across a cycle. Not part of the app.
tasks.register<JavaExec>("analyzePhasing") {
    group = "snipsnap"
    description = "Bounces a session and prints per-interval RMS/peak to check for real variation."
    dependsOn("testClasses")
    mainClass.set("com.snipsnap.loop.AnalyzePhasingKt")
    classpath = sourceSets["test"].runtimeClasspath
    args(project.findProperty("sessionDir")?.toString() ?: layout.buildDirectory.dir("demo-session").get().asFile.path)
}
