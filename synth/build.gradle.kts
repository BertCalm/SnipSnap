plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":audio"))
    implementation(project(":json"))
    // The factory kit builders hand ArrangedPads (audio + class + recipe)
    // straight to the kit pipeline.
    implementation(project(":kit"))
    testImplementation(kotlin("test"))
    // Integration tests drive a rendered kit through the real export pipeline.
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

/** Render the melodic acceptance kit into testkit/. See MelodicKitGenerator. */
tasks.register<JavaExec>("generateMelodicKit") {
    group = "distribution"
    description = "Render the PLUCK/TONEWHEEL melodic acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MelodicKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the acceptance expansion into testkit/. See ExpansionPackGenerator. */
tasks.register<JavaExec>("generateExpansionPack") {
    group = "distribution"
    description = "Render the browsable acceptance expansion under testkit/Expansions/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ExpansionPackGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the velocity-layered acceptance kit into testkit/. See VelocityKitGenerator. */
tasks.register<JavaExec>("generateVelocityKit") {
    group = "distribution"
    description = "Render the velocity-layered acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.VelocityKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the shuffled A/B acceptance kit into testkit/. See ShuffleKitGenerator. */
tasks.register<JavaExec>("generateShuffleKit") {
    group = "distribution"
    description = "Render the dice-rolled A/B acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ShuffleKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the keygroup acceptance program into testkit/. See KeysPackGenerator. */
tasks.register<JavaExec>("generateKeysPack") {
    group = "distribution"
    description = "Render the VELVET keygroup acceptance program under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.KeysPackGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Package the factory kit as a single .xpn file. See XpnFileGenerator. */
tasks.register<JavaExec>("generateXpnFile") {
    group = "distribution"
    description = "Package the factory kit as testkit/SnipSnap_Factory.xpn."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.XpnFileGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the atmosphere acceptance kit into testkit/. See CloudKitGenerator. */
tasks.register<JavaExec>("generateCloudKit") {
    group = "distribution"
    description = "Render the VOX/GRAINS atmosphere acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CloudKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the chip acceptance kit into testkit/. See ChipKitGenerator. */
tasks.register<JavaExec>("generateChipKit") {
    group = "distribution"
    description = "Render the VELVET/CRUNCH chip acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ChipKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}
