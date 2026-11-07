plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":json"))
    implementation(project(":xpm"))
    implementation(project(":audio"))
    implementation(project(":mpc3"))
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
    // KitBackupTest compares the temp directory before and after a failed
    // operation. A neighboring preview or restore must not change that set.
    // Keep kit classes together and other modules/checkouts in their own temp
    // namespace; an actual leaked kit directory still fails the same assertion.
    maxParallelForks = 1
    val testTemp = layout.buildDirectory.dir("test-temp").get().asFile
    systemProperty("java.io.tmpdir", testTemp.absolutePath)
    doFirst { testTemp.mkdirs() }
    testLogging {
        events("passed", "failed", "skipped")
    }
}
