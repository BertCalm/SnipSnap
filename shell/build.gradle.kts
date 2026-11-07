import java.util.Locale


plugins {
    kotlin("jvm")
}

tasks.register<JavaExec>("generateLibrationTouchAudition") {
    group = "distribution"
    description = "Render two-touch Libration instruments and performances of their exported PCM24 samples."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.LibrationTouchAuditionGenerator")
    workingDir = rootProject.projectDir
    maxHeapSize = "3g"
    args(providers.gradleProperty("librationTouchOutput").orNull ?: "${rootDir}/testkit/libration-touch")
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
    // Measured 2026-10-24 on the default at 9c10bab15: Gradle's default 512m test heap held
    // 266 to 298 MB live after full GCs, almost all of it rendered audio that the pad-maker
    // tests memoise in companion `by lazy` fields (CreasePadMakerTest's touch notes alone
    // are 34.5 MB), and SnipStoreTest's three-minute import then could not find room for
    // its 32 MB buffer. The memos are deliberate (one render per class, not per test), so
    // the live set grows with every engine (10 to 35 MB each). With 1g the whole suite
    // passed at a 475 MB peak live set; 1536m is that peak with room for the next
    // dozen engines, set here rather than discovered when one tips it over.
    maxHeapSize = "1536m"
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

/** Package the unchanged model-3 OSPREY starter with editable recipes and both MPC generations. */
tasks.register<JavaExec>("generateOspreyHandoff") {
    group = "distribution"
    description = "Package the existing OSPREY R3 starter, source evidence and native/compatibility exports under testkit/osprey-handoff/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OspreyHandoffGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/osprey-handoff")
    args(providers.gradleProperty("ospreyGentleBaselineDir").orElse("${rootDir}/testkit/osprey-gentle-audition").get())
}

/** Focused ORIGAMI gestures, one-zone held references and a portable C4 listening page. */
tasks.register<JavaExec>("generateOrigamiGestureAudition") {
    group = "distribution"
    description = "Render the ORIGAMI gesture listening study and its self-contained starter."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OrigamiGestureAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("origamiGestureOutput").orElse(rootDir.resolve("testkit/origami-gestures").absolutePath).get())
}

/** Render the saved held RIBBON through the production exporter and reference sample player. */
tasks.register<JavaExec>("generateFlockHeldInstrument") {
    group = "distribution"
    description = "Export held RIBBON for SnipSnap/MPC, with exact recipes, reference playback, measurements and ZIP."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.FlockHeldInstrumentGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("flockHeldOutput").orNull ?: "${rootDir}/testkit/flock-held-instrument")
}

/** Render independently excited soft, middle and hard RIBBON keygroup layers. */
tasks.register<JavaExec>("generateFlockVelocityInstrument") {
    group = "distribution"
    description = "Export touch-layer RIBBON for SnipSnap/MPC with PCM24 readback, reference clips, source evidence and download ZIP parts."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.FlockVelocityInstrumentGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("flockVelocityOutput").orNull ?: "${rootDir}/testkit/flock-velocity-instrument")
}

/** Three provisional expanded OHM studies through the production keygroup handoff. */
tasks.register<JavaExec>("generateOhmExpandedHandoff") {
    group = "distribution"
    description = "Export three provisional OHM keygroups, complete source recipes, MPC 2/3 packages and a verified ZIP."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OhmExpandedHandoffGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("ohmExpandedHandoffOutput").orNull ?: "${rootDir}/testkit/ohm-expanded-handoff")
}

/** The five approved finite OHM Reply/Ring factories through the production keygroup exporter. */
tasks.register<JavaExec>("generateOhmReplyRingHandoff") {
    group = "distribution"
    description = "Export five OHM Reply/Ring keygroups, 45 finite native zones, replay recipes and a verified MPC 2/3 ZIP."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OhmReplyRingHandoffGenerator")
    workingDir = rootDir
    args(providers.gradleProperty("ohmReplyRingHandoffOutput").orNull ?: "${rootDir}/testkit/ohm-reply-ring-handoff")
}

/** Package and play the approved CREASE sounds as continuous-onset held keys. */
tasks.register<JavaExec>("generateCreaseKeysAudition") {
    group = "distribution"
    description = "Render CREASE held-key performances and dual-MPC/phone instrument packages under testkit/crease-keys-audition/."
    maxHeapSize = "2g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.CreaseKeysAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("creaseKeysOutput").orNull ?: "${rootDir}/testkit/crease-keys-audition")
}

/** BALLAST held: four keygroup instruments through the production exporter, with held and walking previews. */
tasks.register<JavaExec>("generateBallastKeys") {
    group = "distribution"
    description = "Export four BALLAST sounds as thirteen-zone held keygroups, with previews, under testkit/ballast-keys/."
    maxHeapSize = "2g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.BallastKeysGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/ballast-keys")
}

/** Package Contour's 36 factory recipes as held instruments with verified native and compatibility programs. */
tasks.register<JavaExec>("generateContourStarter") {
    group = "distribution"
    description = "Render 36 CONTOUR held instruments, saved recipes, software checks and a ZIP under testkit/contour-starter/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.ContourStarterGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/contour-starter")
}

/** Audition and package the new musical presets on the unchanged model-3 OSPREY engine. */
tasks.register<JavaExec>("generateOspreyCuratedAudition") {
    group = "distribution"
    description = "Render sixteen new OSPREY presets, matched listening previews and an editable shared-WAV MPC kit under testkit/osprey-curated-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OspreyCuratedAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/osprey-curated-audition")
}

/** Render expressive touch banks without replacing the historical single-layer audition. */
tasks.register<JavaExec>("generateCreaseTouchAudition") {
    group = "distribution"
    description = "Render CREASE touch-layer instruments, JVM performances and measurements under testkit/crease-touch-audition/."
    maxHeapSize = "2g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.CreaseTouchAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("creaseTouchOutput").orNull ?: "${rootDir}/testkit/crease-touch-audition")
}

/** Render native FILAMENT attack/sustain keys plus exported-file playback demos. */
tasks.register<JavaExec>("generateFilamentKeysAudition") {
    group = "distribution"
    description = "Package FILAMENT held instruments and native/reference-player listening files."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.FilamentKeysAuditionGenerator")
    workingDir = rootDir
    maxHeapSize = "1g"
    val voice = providers.gradleProperty("filamentKeysVoice").orElse("THREAD").get()
    val touch = providers.gradleProperty("filamentKeysTouchLayers").orElse("false").get().toBooleanStrict()
    val wide = providers.gradleProperty("filamentKeysWideKeys").orElse("false").get().toBooleanStrict()
    val rangeTag = if (wide) "-wide" else ""
    val directory = if (voice == "ALL") "held" else voice.lowercase(Locale.ROOT)
    val touchTag = if (touch) "-touch" else ""
    args(providers.gradleProperty("filamentKeysAuditionDir").orElse("${rootDir}/testkit/filament-$directory$rangeTag$touchTag-keys").get(), voice, touch.toString(), wide.toString())
}

/** Compare unchanged BELLOWS native notes with the real exported sample program and release preview. */
/** KEEL held: four keygroup instruments through the production exporter, with held and walking previews (spec section 17, R2). */
tasks.register<JavaExec>("generateKeelKeys") {
    group = "distribution"
    description = "Export KEEL's two voices at their defaults and at WEIGHT 1 as nine-zone held keygroups, with previews, under testkit/keel-keys/."
    maxHeapSize = "2g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.KeelKeysGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/keel-keys")
}

/** BORE held: the held keygroup's zones as held keys beside the loop-only zones they replace. See BoreHeldAuditionGenerator. */
tasks.register<JavaExec>("generateBoreHeldAudition") {
    group = "distribution"
    description = "Render BORE's held keygroup as held keys (hard and soft layers) beside the loop-only zones, at three notes a voice, under testkit/bore-held-audition/."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.BoreHeldAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("boreHeldOutput").orElse("${rootDir}/testkit/bore-held-audition").get())
}

tasks.register<JavaExec>("generateBellowsPlaybackAudition") {
    group = "distribution"
    description = "Render BELLOWS native/program A/B and release previews through exported WAVs and the JVM player."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.BellowsPlaybackAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("bellowsPlaybackOutput").orElse("${rootDir}/testkit/bellows-playback-audition").get())
}

/** Compare ORIGAMI strike expression and production three-layer held instruments. */
tasks.register<JavaExec>("generateOrigamiExpressionAudition") {
    group = "distribution"
    description = "Render ORIGAMI strike comparisons, sibling instrument banks and a portable C4 listening page."
    maxHeapSize = "1536m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OrigamiExpressionAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("origamiExpressionOutput").orElse(rootDir.resolve("testkit/origami-expression").absolutePath).get())
}

/** Export continuous LIBRATION keys and play their PCM24 samples through real note-on/note-off. */
tasks.register<JavaExec>("generateLibrationHoldAudition") {
    group = "distribution"
    description = "Render three LIBRATION held instruments, playback comparisons and measured recurrence evidence."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.LibrationHoldAuditionGenerator")
    workingDir = rootDir
    args(providers.gradleProperty("librationHoldOutput").orElse("${rootDir}/testkit/libration-sustain").get())
}

/** One opening before a separately marked sustain region, compared with unchanged PRISM pad renders. */
tasks.register<JavaExec>("generatePrismOpeningSustainAudition") {
    group = "distribution"
    description = "Render PRISM OPEN, pure HOLD and opening-once keygroup notes with sampler metadata and eight-sustain proofs."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.PrismOpeningSustainAuditionGenerator")
    maxHeapSize = "1g"
    workingDir = projectDir
    args(providers.gradleProperty("prismOpeningSustainOutput").orElse("${rootDir}/testkit/prism-opening-sustain-audition").get())
}

/** The wide-register duet, actual held-note playback and four portable keygroup instruments. */
tasks.register<JavaExec>("generateObbligatoPerformanceAudition") {
    group = "distribution"
    description = "Render OBBLIGATO phrase/held comparisons across C3-C6, actual taps and MPC instruments."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.ObbligatoPerformanceAuditionGenerator")
    maxHeapSize = "1g"
    workingDir = projectDir
    args(providers.gradleProperty("obbligatoPerformanceOutput").orElse("${rootDir}/testkit/obbligato-performance-audition").get())
}

/** The phone's nine approved cultural passages, all five playback modes and the complete portable bank. */
tasks.register<JavaExec>("generateConfluenceSelectedAudition") {
    group = "distribution"
    description = "Render the cultural passage audition through the app workshop and package its unchanged source bank."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.ConfluenceSelectedAuditionGenerator")
    maxHeapSize = "1g"
    workingDir = rootDir
    args(providers.gradleProperty("confluenceSelectedAuditionOutput").orElse("${rootDir}/testkit/confluence-selected-audition").get())
    providers.gradleProperty("confluenceSelectedAuditionRevision").orNull?.let { args(it) }
}

/** Three native Rime keyboard instruments, each with nine attack-plus-loop zones. */
tasks.register<JavaExec>("generateRimeInstruments") {
    group = "distribution"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.RimeInstrumentGenerator")
    maxHeapSize = "768m"
    args(providers.gradleProperty("rimeInstrumentOutput").orNull ?: "${rootDir}/testkit/rime-instruments")
}

tasks.register<JavaExec>("generateRimeCompleteInstruments") {
    group = "distribution"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.RimeInstrumentGenerator")
    maxHeapSize = "768m"
    args(providers.gradleProperty("rimeInstrumentOutput").orNull ?: "${rootDir}/testkit/rime-complete-instruments", "--complete")
}

tasks.register<JavaExec>("generateScannedChainControlsAudition") {
    group = "distribution"
    description = "Render Scanned Chain editor sweeps, complete recipes and numerical evidence."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.ScannedChainControlsAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("scannedChainControlsOutput").orElse("${rootDir}/testkit/scanned-chain-controls").get())
}

/** Reproduce the six factory performance recipes and four MPC-ready held instruments. */
tasks.register<JavaExec>("generateOrigamiPerformanceBank") {
    group = "distribution"
    description = "Render the ORIGAMI performance bank, native/matched takes, portable audition and MPC downloads."
    maxHeapSize = "1536m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.OrigamiPerformanceBankGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("origamiPerformanceOutput").orElse(rootDir.resolve("testkit/origami-performance-bank").absolutePath).get())
    if (providers.gradleProperty("origamiPerformanceLegacyFold").orNull == "true") args("--legacy-fold")
}

/** Model-2 CAUSTIC motion, measured continuous loops and real exported keygroups. */
tasks.register<JavaExec>("generateCausticCompleteAudition") {
    group = "distribution"
    description = "Render CAUSTIC motion and sustain comparisons, genuine-cycle evidence and playable keygroup exports."
    maxHeapSize = "2g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.CausticCompleteAuditionGenerator")
    workingDir = rootDir
    args(providers.gradleProperty("causticCompleteOutput").orElse(rootDir.resolve("testkit/caustic-complete-audition").absolutePath).get())
}

/** Held TRACE delivery and note-on/off recordings from the exported keygroup samples. */
tasks.register<JavaExec>("generateLatticeKeysAudition") {
    group = "distribution"
    description = "Render TRACE attack/sustain keygroups, independent-cycle evidence and exported-file listening clips."
    maxHeapSize = "2g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.LatticeKeysAuditionGenerator")
    workingDir = rootDir
    val recipe = providers.gradleProperty("latticeKeysRecipe").orNull
    if (recipe != null) {
        args("--replay", recipe, providers.gradleProperty("latticeKeysReplayOutput").get())
    } else {
        args(providers.gradleProperty("latticeKeysOutput").orElse(rootDir.resolve("testkit/lattice-keys-audition").absolutePath).get())
        providers.gradleProperty("latticeKeysBaseline").orNull?.let { args(it) }
    }
}

/** ARCO's held keys (hard and soft layers) beside the loop-only zone they replace, at three notes per voice, under testkit/arco-held-audition/. See ArcoHeldAuditionGenerator. */
tasks.register<JavaExec>("generateArcoHeldAudition") {
    group = "distribution"
    description = "Render ARCO's held keys, both layers, through the instrument player beside the loop-only zone, three notes per voice, under testkit/arco-held-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.ArcoHeldAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "2g"
    args(providers.gradleProperty("arcoHeldOutput").orElse("${rootDir}/testkit/arco-held-audition").get())
}

tasks.register<JavaExec>("generateHeldStartAudition") {
    group = "distribution"
    description = "Render ARCO's and BORE's held keys with the hit lasting as shipped, medium, short or not at all, three notes per voice, under testkit/held-start-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.HeldStartAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "2g"
    args(providers.gradleProperty("heldStartOutput").orElse("${rootDir}/testkit/held-start-audition").get())
}

/** Qualify the HOLD-loop engines for a shared keys instrument; writes testkit/loop-instruments/. See LoopInstrumentScan. */
tasks.register<JavaExec>("scanLoopInstruments") {
    group = "verification"
    description = "Measure each HOLD-loop engine's loop as a keys instrument (wrap, pitch, steadiness) under testkit/loop-instruments/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.LoopInstrumentScan")
    workingDir = projectDir
    maxHeapSize = "3g"
    args(listOf("${rootDir}/testkit/loop-instruments") + (providers.gradleProperty("engines").orNull?.split(",") ?: emptyList()))
}

/** The factory expansion of the new held instruments (MERCURY, KEEL, LoopPadMaker's qualifiers). See InstrumentPackGenerator. */
tasks.register<JavaExec>("generateInstrumentPack") {
    group = "distribution"
    description = "Render the new held instruments and pack them as one MPC expansion (.xpn) under testkit/loop-instruments-pack/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.InstrumentPackGenerator")
    workingDir = projectDir
    maxHeapSize = "3g"
    args(listOf("${rootDir}/testkit/loop-instruments-pack") + (providers.gradleProperty("engines").orNull?.split(",") ?: emptyList()))
}

/** The factory instruments again, one expansion per engine, from the packages generateInstrumentPack left. */
tasks.register<JavaExec>("splitInstrumentPack") {
    group = "distribution"
    description = "Pack the rendered new held instruments one expansion per engine under testkit/loop-instruments-pack/By engine/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.InstrumentPackGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/loop-instruments-pack", "--split")
}

tasks.register<JavaExec>("generateFilamentHaloAudition") {
    group = "distribution"
    description = "Capture CURL body/halo once and compare independent release from exported PCM24."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.FilamentHaloAuditionGenerator")
    workingDir = rootDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("filamentHaloOutput").orElse("${rootDir}/testkit/filament-halo-release").get())
}

tasks.register<JavaExec>("generateTrefoilHeldMpc") {
    group = "distribution"
    description = "Export the native three-root Trefoil HOLD sampler trial and render its note-off demonstration."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.shell.TrefoilHeldMpcGenerator")
    maxHeapSize = "768m"
    workingDir = rootProject.projectDir
    args(providers.gradleProperty("trefoilHeldMpcOutput").orNull ?: "${rootDir}/testkit/trefoil-held-mpc")
}
