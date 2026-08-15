plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

// Targets Java 17 bytecode so the Android app can consume this module directly,
// without pinning contributors to a specific JDK install.
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

/**
 * Rewrite the golden .xpm from the current writer. Only for deliberate format
 * changes — see GoldenGenerator.
 */
tasks.register<JavaExec>("regenerateGolden") {
    group = "verification"
    description = "Rewrite the golden .xpm fixture from the current writer output."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.xpm.GoldenGenerator")
    workingDir = projectDir
}
