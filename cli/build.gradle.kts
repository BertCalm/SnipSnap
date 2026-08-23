plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":audio"))
    implementation(project(":kit"))
    implementation(project(":xpm"))
    implementation(project(":mpc3"))
    implementation(project(":json"))
    testImplementation(kotlin("test"))
}

// Java 17 bytecode, same as every module — the CLI is a consumer of the
// exact artifacts the Android app will consume.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.snipsnap.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * The one-file deliverable: `java -jar snipsnap.jar chop break.wav`.
 * Every dependency is a project module (plus the Kotlin stdlib), so the
 * fat jar is just a merge — no shading, no relocation.
 */
tasks.register<Jar>("snipsnapJar") {
    group = "distribution"
    description = "Assemble the runnable snipsnap.jar under cli/build/libs/."
    archiveFileName.set("snipsnap.jar")
    manifest { attributes("Main-Class" to "com.snipsnap.cli.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}
