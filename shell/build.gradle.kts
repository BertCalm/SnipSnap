plugins {
    kotlin("jvm")
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
    // The drone renderer is a loop-grid SampleSource (DroneSource).
    implementation(project(":loop"))
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
        // FULL, because this module's tests are mostly LAWS, and a law's whole
        // value is the sentence it prints when it fires. Without this Gradle
        // shows "AssertionFailedError at SomeTest.kt:164" and swallows the
        // message, so a failure that does not reproduce locally - a different
        // JDK on CI, say - costs a round trip to read text the runner already
        // had. ReversalTest's laws name the exact Copy constant at fault; that
        // is the point of them.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
    // Every module, not just :app: the stranded-doc-comment law scans the
    // whole tree, and the instances it was written for were spread across
    // :app, :shell and :kit. A module missing from this list is a module
    // whose source changes leave this task UP-TO-DATE, so the law would go
    // green having never re-read the file that broke it.
    listOf("app", "audio", "cli", "json", "kit", "loop", "mpc3", "shell", "synth", "xpm").forEach { module ->
        inputs.dir(layout.projectDirectory.dir("../$module/src/main/kotlin"))
            .withPropertyName("${module}SourcesScannedByConventionTest")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }

    // AuditionCopiesTest reads :synth's audition loudness rule as text to hold
    // :shell's copy of it identical (Dsp.kt is already covered by the synth
    // main-source directory above). Undeclared, an edit to the original would
    // leave this task UP-TO-DATE and the guard would go green without re-reading it.
    inputs.file(layout.projectDirectory.file("../synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt"))
        .withPropertyName("synthAuditionLevelScannedByAuditionCopiesTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The roster-count law reads these as prose, for the same reason and with
    // the same hazard: undeclared, the task stays UP-TO-DATE after a README
    // edit and the law goes green having never re-read the sentence that
    // changed. Caught exactly that way while writing it — reverting a README
    // to its stale count did not fail the test until these were declared.
    mapOf("root" to "../README.md", "app" to "../app/README.md").forEach { (owner, readme) ->
        inputs.file(layout.projectDirectory.file(readme))
            .withPropertyName("${owner}ReadmeScannedByConventionTest")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }

    // MutateWordsTest reads the house's MUTATE copy board as text and holds
    // the card's move lines and knob meanings to it character for character.
    // Undeclared, an edit to the board would leave this task UP-TO-DATE.
    inputs.file(layout.projectDirectory.file("../design/mutate-v2/Moves.dc.html"))
        .withPropertyName("mutateMovesBoardScannedByMutateWordsTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/** Render the BECOME A1 gate's clips, manifest and listening page under testkit/become-audition/. See BecomeAuditionGenerator. */
tasks.register<JavaExec>("generateBecomeAudition") {
    group = "distribution"
    description = "Render the BECOME A1 gate's ten clips, manifest and listening page under testkit/become-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.BecomeAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/become-audition")
}
