plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":audio"))
    implementation(project(":json"))
    testImplementation(kotlin("test"))
    // Integration tests drive a rendered kit through the real export pipeline.
    testImplementation(project(":kit"))
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

/** Render the THUMP acceptance kit into testkit/. See ThumpKitGenerator. */
tasks.register<JavaExec>("generateThumpKit") {
    group = "distribution"
    description = "Render the synthesized THUMP acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ThumpKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}
