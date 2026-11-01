plugins {
    kotlin("jvm")
}

tasks.register<JavaExec>("generateFerryMatchAudition") {
    group = "distribution"
    description = "Render Ferry MATCH tuning comparisons, accepted aligned PCM checks and saved recipes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FerryMatchAuditionGenerator")
    workingDir = rootProject.projectDir
    maxHeapSize = "768m"
    args(providers.gradleProperty("ferryMatchOutput").orNull ?: "${rootDir}/testkit/ferry-match-audition")
}

tasks.register<JavaExec>("generateLibrationMusicalBank") {
    group = "distribution"
    description = "Render Libration's finite bank, performance probes, musical scores and layered MPC starter."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.LibrationMusicalGenerator")
    maxHeapSize = "1g"
    workingDir = projectDir
    args(providers.gradleProperty("librationMusicalOutput").orNull ?: "${rootDir}/testkit/libration-musical")
}

/** Finite first-listen proof, including actual trajectories and causal audio comparisons. */
tasks.register<JavaExec>("generateLibrationAudition") {
    group = "distribution"
    description = "Render Libration's three dry voices, controlled comparisons, recipes and measured diagnostics."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.LibrationAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("librationOutput").orNull ?: "${rootDir}/testkit/libration-audition")
}

tasks.register<JavaExec>("replayLibrationAudition") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.LibrationAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args("--replay", providers.gradleProperty("librationRecipe").orNull ?: "",
        providers.gradleProperty("librationReplayOutput").orNull ?: "${rootDir}/testkit/libration-replay.wav")
}

tasks.register<JavaExec>("generateTrefoilPlayableBank") {
    group = "distribution"
    description = "Render twelve Trefoil presets, three native velocity layers, sixteen MPC pads and two musical performances."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TrefoilPlayableBankGenerator")
    maxHeapSize = "1g"
    workingDir = projectDir
    args(providers.gradleProperty("trefoilBankOutput").orNull ?: "${rootDir}/testkit/trefoil-playable-bank")
}

/** Port the approved passive handoff and compare actual Kotlin exports with its retained references. */
tasks.register<JavaExec>("generateFerryAudition") {
    group = "distribution"
    description = "Render Ferry PASSAGE, approved reference comparisons, complete recipes and Kotlin timing evidence."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FerryAuditionGenerator")
    workingDir = rootDir
    maxHeapSize = "768m"
    args(providers.gradleProperty("ferryOutput").orNull ?: "${rootDir}/testkit/ferry-audition")
}

/** Explicit production-call measurements; timing never decides correctness. */
tasks.register<JavaExec>("benchmarkScannedChain") {
    group = "verification"
    description = "Measure Scanned Chain fresh renders, completed-cache hits, knob misses, raw probes and finishing with PCM hashes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ScannedChainRenderBenchmark")
    minHeapSize = "256m"
    maxHeapSize = "768m"
    workingDir = rootProject.projectDir
    args("--casegroup=${providers.gradleProperty("scannedChainBenchmarkCases").orNull ?: "defaults"}")
    args("--warmups=${providers.gradleProperty("scannedChainBenchmarkWarmups").orNull ?: "2"}")
    args("--samples=${providers.gradleProperty("scannedChainBenchmarkSamples").orNull ?: "5"}")
    providers.gradleProperty("scannedChainBenchmarkOutput").orNull?.let { args("--output=$it") }
    providers.gradleProperty("scannedChainBenchmarkLabel").orNull?.let { args("--label=$it") }
    providers.gradleProperty("scannedChainBenchmarkPaths").orNull?.let { args("--paths=$it") }
    providers.gradleProperty("scannedChainBenchmarkStates").orNull?.let { args("--states=$it") }
    providers.gradleProperty("scannedChainBenchmarkCancellation").orNull?.let { args("--cancellation=$it") }
    providers.gradleProperty("scannedChainBenchmarkProcessors").orNull?.let { jvmArgs("-XX:ActiveProcessorCount=$it") }
}

/** Saved production PLUCK/UPRIGHT notes, phrase comparisons and control probes. */
tasks.register<JavaExec>("generateUprightAudition") {
    group = "distribution"
    description = "Render UPRIGHT native/matched WAVs, exact recipes, measurements and a listening pack."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.UprightAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    val replay = providers.gradleProperty("uprightReplay").orNull
    if (replay != null) {
        args("--replay", replay, providers.gradleProperty("uprightReplayOutput").get(),
            providers.gradleProperty("uprightReplayMode").orNull ?: "native")
    } else {
        args(providers.gradleProperty("uprightOutput").orNull ?: "${rootDir}/testkit/upright-audition")
    }
}

/** The same finite bass voice on sixteen editable pads in both MPC formats. */
tasks.register<JavaExec>("generateUprightKit") {
    group = "distribution"
    description = "Export sixteen UPRIGHT pads, complete recipes, MPC 3 track and MPC 2 program."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.UprightKitGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("uprightKitOutput").orNull ?: "${rootDir}/testkit")
}

/** Finite two-voice CAIRN reconstruction, with raw audio and causal mechanics evidence. */
tasks.register<JavaExec>("generateCairnAudition") {
    group = "distribution"
    description = "Render CAIRN KNOCK/ANSWER, common controls, sweeps, causes, recipes and render cost."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CairnAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("cairnOutput").orNull ?: "${rootDir}/testkit/cairn-reconstruction")
    if (providers.gradleProperty("cairnColdOnly").orNull == "true") args("--cold-only")
}

/** The current wind-duet handoff beside a slightly longer overlap. */
tasks.register<JavaExec>("generateObbligatoMoreOverlapAudition") {
    group = "distribution"
    description = "Compare current and slightly longer OBBLIGATO overlaps, melodies and actual player taps."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoMoreOverlapAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("obbligatoMoreOverlapOutput").orNull ?: "${rootDir}/testkit/obbligato-more-overlap-audition")
}

/** A focused comparison of previous and overlapping duet handoffs. */
tasks.register<JavaExec>("generateObbligatoOverlapAudition") {
    group = "distribution"
    description = "Compare previous and overlapping OBBLIGATO handoffs, melodies and actual player taps."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoOverlapAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("obbligatoOverlapOutput").orNull ?: "${rootDir}/testkit/obbligato-overlap-audition")
}

/** Three relationship conversations beside their gentler reference performances. */
tasks.register<JavaExec>("generateObbligatoConversationAudition") {
    group = "distribution"
    description = "Compare OBBLIGATO Play, Fight and Protect conversations with their gentler reference notes and melodies."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoConversationAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("obbligatoConversationOutput").orNull ?: "${rootDir}/testkit/obbligato-conversation-audition")
}

/** A focused comparison of reed voicing and tuning, with the accepted kit retained. */
tasks.register<JavaExec>("generateObbligatoMusicalAudition") {
    group = "distribution"
    description = "Compare previous and gentler OBBLIGATO Fight single notes, melodies and actual source taps."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoMusicalAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("obbligatoMusicalOutput").orNull ?: "${rootDir}/testkit/obbligato-musical-audition")
}

/** An opt-in listening comparison; the accepted model-five kit remains unchanged. */
tasks.register<JavaExec>("generateObbligatoResponsiveAudition") {
    group = "distribution"
    description = "Compare accepted OBBLIGATO model 5 with responsive model 6 and causal source interventions."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoResponsiveAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("obbligatoResponsiveOutput").orNull ?: "${rootDir}/testkit/obbligato-responsive-audition")
}

/** LATTICE source proof, native instrument phrases, periodic HOLD and MPC starter. */
tasks.register<JavaExec>("generateLatticeAudition") {
    group = "distribution"
    description = "Render LATTICE voices, HOLD loops, native patches, MPC kit and replayable evidence."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.LatticeAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    val recipe = providers.gradleProperty("latticeRecipe").orNull
    if (recipe != null) {
        args("--replay", recipe, providers.gradleProperty("latticeReplayOutput").get())
    } else {
        args(providers.gradleProperty("latticeOutput").orNull ?: "${rootDir}/testkit/lattice-audition")
    }
}

/** Native previous/revised EDGE comparisons in complete finite notes and held periods. */
tasks.register<JavaExec>("generateContourEdgeAudition") {
    group = "distribution"
    description = "Render nine Contour recipes at five EDGE positions, finite/held, previous/revised, with exact replay JSON and matched WAVs."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ContourEdgeAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("contourEdgeOutput").orNull ?: "${rootDir}/testkit/contour-edge-audition")
}

/** All 36 complete factory recipes, with finite attacks and independently measured settled tones. */
tasks.register<JavaExec>("generateContourPresetAudition") {
    group = "distribution"
    description = "Render 36 Contour presets, native/matched finite and held WAVs, held EDGE ranges and exact replay JSON."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ContourPresetAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("contourPresetOutput").orNull ?: "${rootDir}/testkit/contour-presets")
}

/** Dry first-listen guided-path synthesis, escaped stems and causal comparisons. */
tasks.register<JavaExec>("generateFilamentAudition") {
    group = "distribution"
    description = "Render FILAMENT native/matched WAVs, exact recipes, diagnostics and the first-listen page."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FilamentAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("filamentOutput").orNull ?: "${rootDir}/testkit/filament-audition")
}

/** Explicit render-only measurements; timing never determines whether the test suite passes. */
tasks.register<JavaExec>("benchmarkFilament") {
    group = "verification"
    description = "Measure approved FILAMENT finite and held renders, with per-case warmup and JSON timings."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FilamentPerformanceProbe")
    maxHeapSize = "768m"
    workingDir = rootProject.projectDir
    args("--path=${providers.gradleProperty("filamentBenchmarkPath").orNull ?: "production"}")
    args("--warmups=${providers.gradleProperty("filamentBenchmarkWarmups").orNull ?: "2"}")
    args("--samples=${providers.gradleProperty("filamentBenchmarkSamples").orNull ?: "5"}")
    providers.gradleProperty("filamentBenchmarkOutput").orNull?.let { args("--output=$it") }
    providers.gradleProperty("filamentBenchmarkLabel").orNull?.let { args("--label=$it") }
}

/** Render-only measurements; timings never decide whether correctness tests pass. */
tasks.register<JavaExec>("benchmarkTidal") {
    group = "verification"
    description = "Measure TIDAL cold, geometry-warm and completed-cache rendering with exact audio hashes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TidalRenderBenchmark")
    minHeapSize = "256m"
    maxHeapSize = "768m"
    workingDir = rootProject.projectDir
    providers.gradleProperty("tidalBenchmarkProcessors").orNull?.let { jvmArgs("-XX:ActiveProcessorCount=$it") }
    args("--path=${providers.gradleProperty("tidalBenchmarkPath").orNull ?: "production"}")
    args("--state=${providers.gradleProperty("tidalBenchmarkState").orNull ?: "all"}")
    args("--cases=${providers.gradleProperty("tidalBenchmarkCases").orNull ?: "defaults"}")
    args("--warmups=${providers.gradleProperty("tidalBenchmarkWarmups").orNull ?: "2"}")
    args("--samples=${providers.gradleProperty("tidalBenchmarkSamples").orNull ?: "5"}")
    providers.gradleProperty("tidalBenchmarkOutput").orNull?.let { args("--output=$it") }
    providers.gradleProperty("tidalBenchmarkLabel").orNull?.let { args("--label=$it") }
    providers.gradleProperty("tidalBenchmarkProfile").orNull?.let { args("--profile=$it") }
}

/** Sixteen finite TIDAL pads with complete saved recipes in both MPC formats. */
tasks.register<JavaExec>("generateTidalKit") {
    group = "distribution"
    description = "Export sixteen finite TIDAL pads, editable recipes, and MPC 3/MPC 2 programs."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TidalKitGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("tidalKitOutput").orNull ?: "${rootDir}/testkit/tidal-kit")
}

/** Finite TIDAL listening proof: two populations, encounter controls and causal comparisons. */
tasks.register<JavaExec>("generateTidalAudition") {
    group = "distribution"
    description = "Render finite PAIR/STREAM raw and matched WAVs, replayable recipes and the TIDAL listening page."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TidalAuditionGenerator")
    workingDir = projectDir
    val replay = providers.gradleProperty("tidalReplay").orNull
    if (replay != null) {
        args("--replay", replay, providers.gradleProperty("tidalReplayOutput").get(),
            providers.gradleProperty("tidalReplayMode").orNull ?: "matched")
    } else {
        args(providers.gradleProperty("tidalOutput").orNull ?: "${rootDir}/testkit/tidal-audition")
    }
}

/** Dry, finite OHM family encounters and their replayable listening evidence. */
tasks.register<JavaExec>("generateOhmAudition") {
    group = "distribution"
    description = "Render OHM solo/argument/communion comparisons, control trials and the audition page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OhmAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args("${rootDir}/testkit/ohm-audition")
}

/** Isolated model-3 additions, explicit capture provenance and actual held-region playback. */
tasks.register<JavaExec>("generateOhmExpandedAudition") {
    group = "distribution"
    description = "Render OHM model-3 feature comparisons, phrases, held-loop studies and portable source recipes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OhmExpandedAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    val replay = providers.gradleProperty("ohmExpandedReplay").orNull
    if (replay != null) {
        args("--replay", replay, providers.gradleProperty("ohmExpandedReplayOutput").get(),
            providers.gradleProperty("ohmExpandedReplayMode").orNull ?: "matched")
    } else {
        args(providers.gradleProperty("ohmExpandedOutput").orNull ?: "${rootDir}/testkit/ohm-expanded-audition")
    }
}

/** Three focused model-4 additions with complete, byte-pinned model-2 depth references. */
tasks.register<JavaExec>("generateOhmRefinementAudition") {
    group = "distribution"
    description = "Render three OHM shared-depth comparisons, exact reference notes and portable replay evidence."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OhmRefinementAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    val replay = providers.gradleProperty("ohmRefinementReplay").orNull
    if (replay != null) {
        args("--replay", replay, providers.gradleProperty("ohmRefinementReplayOutput").get(),
            providers.gradleProperty("ohmRefinementReplayMode").orNull ?: "matched")
    } else {
        args(providers.gradleProperty("ohmRefinementOutput").orNull ?: "${rootDir}/testkit/ohm-refinement-audition")
    }
}

/** Sixteen dry OHM factory notes, shared MPC 3 / MPC 2 audio and editable recipes. */
tasks.register<JavaExec>("generateOhmKit") {
    group = "distribution"
    description = "Export the OHM starter kit, native MPC 3 track, MPC 2 twin, recipes and SnipSnap-Ohm-Kit.zip."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OhmKitGenerator")
    workingDir = projectDir
    maxHeapSize = "768m"
    args(providers.gradleProperty("ohmKitOutput").orNull ?: "${rootDir}/testkit")
}

/** Warm render-only timings; this benchmark is explicit rather than a timing assertion in CI. */
tasks.register<JavaExec>("benchmarkOhm") {
    group = "verification"
    description = "Measure OHM raw/final renders at three lengths and relationships, with per-case warmup and timing samples."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OhmPerformanceProbe")
    maxHeapSize = "512m"
    args("bench", providers.gradleProperty("ohmBenchmarkPath").orNull ?: "both")
}

/** The retained DRAMA development comparisons. */
tasks.register<JavaExec>("generateTrioAudition") {
    group = "distribution"
    description = "Render TRIO solos, alliances, moving relationships and companion entrances with raw/matched audio and saved recipes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TrioAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("trioOutput").orNull ?: "${rootDir}/testkit/trio-audition")
}

/** Accepted versus revised upper-TOUCH character with identical seeded scores. */
tasks.register<JavaExec>("generateTrioTouchAudition") {
    group = "distribution"
    description = "Render twelve TRIO model 5/6 upper-TOUCH comparisons and a listening page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TrioTouchAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("trioTouchOutput").orNull ?: "${rootDir}/testkit/trio-touch")
}

/** The playable TRIO bank, musical phrases and sixteen-pad MPC acceptance kit. */
tasks.register<JavaExec>("generateTrioMusicalBank") {
    group = "distribution"
    description = "Render TRIO presets, musical demos and the regenerable MPC kit with its listening page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TrioMusicalBankGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("trioMusicalOutput").orNull ?: "${rootDir}/testkit/trio-musical-bank")
}

/** Finite CHAMBER listening studies, including the preserved timpani comparisons. */
tasks.register<JavaExec>("generateChamberAudition") {
    group = "distribution"
    description = "Render CHAMBER raw/matched WAVs, complete room recipes and the first-listen audition page."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ChamberAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("chamberOutput").orNull ?: "${rootDir}/testkit/chamber-audition")
    providers.gradleProperty("chamberRevision").orNull?.let {
        require(it in setOf("timpani", "timpani-boost", "finite")) {
            "chamberRevision must be timpani, timpani-boost or finite"
        }
        args("--$it")
    }
    providers.gradleProperty("chamberPrevious").orNull?.let { args("--previous=$it") }
    providers.gradleProperty("chamberKit").orNull?.let { args("--kit=$it") }
}

/** Sixteen finite CHAMBER pads with exact recipes and both supported MPC formats. */
tasks.register<JavaExec>("generateChamberKit") {
    group = "distribution"
    description = "Export the dry CHAMBER MPC 3 track, MPC 2 twin, recipes and SnipSnap-Chamber-Kit.zip."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ChamberKitGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("chamberKitOutput").orNull ?: "${rootDir}/testkit")
}

/** Warm production misses and exact-recipe cache hits, with PCM compatibility hashes. */
tasks.register<JavaExec>("benchmarkChamber") {
    group = "verification"
    description = "Measure finite CHAMBER production rendering and completed-recipe cache reuse."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ChamberPerformanceBenchmark")
    workingDir = projectDir
    args(providers.gradleProperty("chamberBenchmarkOutput").orNull ?: "${rootDir}/testkit/chamber-performance")
    args(providers.gradleProperty("chamberBenchmarkRounds").orNull ?: "3")
}

tasks.register<JavaExec>("replayChamberDiagnostic") {
    group = "distribution"
    description = "Replay an exact diagnostic room/stem recipe saved by the CHAMBER listening bench."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ChamberDiagnosticReplay")
    workingDir = projectDir
    doFirst {
        args(providers.gradleProperty("chamberRecipe").get(), providers.gradleProperty("chamberReplayOutput").get(),
            providers.gradleProperty("chamberReplayMode").orNull ?: "matched")
    }
}

/** A small, opt-in MPC keygroup trial built from the approved steady held bodies. */
tasks.register<JavaExec>("generateOverlapHeldKeygroups") {
    group = "distribution"
    description = "Export four OVERLAP held keygroups, looped samples and a hardware-check package."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OverlapHeldKeygroupGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("overlapHeldKeygroupOutput").orNull ?: "${rootDir}/testkit/overlap-held-keygroup")
}

/** Separate steady sustain proof with real loop recurrence and passive note-off. */
tasks.register<JavaExec>("generateOverlapHeldAudition") {
    group = "distribution"
    description = "Render the separate steady OVERLAP held proof, measured loops and note-off examples."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OverlapHeldAuditionGenerator")
    workingDir = projectDir
    val recipe = providers.gradleProperty("overlapHeldRecipe").orNull
    if (recipe != null) {
        args("--replay", recipe, providers.gradleProperty("overlapHeldWav").get(),
            providers.gradleProperty("overlapHeldMode").orNull ?: "native")
    } else {
        args(providers.gradleProperty("overlapHeldOutput").orNull ?: "${rootDir}/testkit/overlap-held-audition")
    }
}

/** Twelve approved model-two patches, three dry studies, and the downloadable starter kit. */
tasks.register<JavaExec>("generateOverlapMusicalAudition") {
    group = "distribution"
    description = "Render OVERLAP musical-bank native/matched WAVs, replayable recipes and study scores."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OverlapMusicalAuditionGenerator")
    workingDir = projectDir
    val score = providers.gradleProperty("overlapScore").orNull
    if (score != null) {
        args("--score", score, providers.gradleProperty("overlapWav").get())
    } else {
        args("${rootDir}/testkit/overlap-musical-audition")
        dependsOn("generateOverlapKit")
        val kitOutput = providers.gradleProperty("overlapKitOutput")
            .map { project.file(it) }.orElse(rootProject.file("testkit"))
        doLast {
            val assets = rootProject.file("testkit/overlap-musical-audition/assets")
            check(assets.isDirectory || assets.mkdirs()) { "Cannot create $assets" }
            kitOutput.get().resolve("SnipSnap-Overlap-Kit.zip")
                .copyTo(assets.resolve("overlap-starter-kit-v1.zip"), overwrite = true)
            kitOutput.get().resolve("SnipSnap Overlap Kit/kit-manifest.json")
                .copyTo(assets.resolve("overlap-starter-kit-manifest.json"), overwrite = true)
        }
    }
}

/** The approved musical bank plus four lower plucks, with native MPC programs and recipes. */
tasks.register<JavaExec>("generateOverlapKit") {
    group = "distribution"
    description = "Export the sixteen-pad dry OVERLAP MPC 3 track, MPC 2 twin, complete source kit and ZIP."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OverlapKitGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("overlapKitOutput").orNull ?: "${rootDir}/testkit")
}

/** Model-2 Nacre listening proof: fixed-pattern tone pairs and frozen model-1 comparison. */
tasks.register<JavaExec>("generateNacreAudition") {
    group = "distribution"
    description = "Render NACRE's finite duet, control and causal comparisons with recipes and a listening page."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.NacreAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/nacre-audition")
}

/** Sixteen dry finite FLOCK factory pads, editable recipes and native exports for both MPC generations. */
tasks.register<JavaExec>("generateFlockKit") {
    group = "distribution"
    description = "Export the FLOCK factory kit with saved recipes, native MPC 3 track, MPC 2 twin and SnipSnap-Flock-Kit.zip."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlockKitGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("flockKitOutput").orNull ?: "${rootDir}/testkit")
}

/** First-listen microsound-harp prototype: dry attack, causal grains and captured tails. */
tasks.register<JavaExec>("generateOrphicaAudition") {
    group = "distribution"
    description = "Render ORPHICA raw/matched WAVs, replayable patches, manifest and listening page under testkit/orphica-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OrphicaAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/orphica-audition")
}

/** First-listen OBBLIGATO duets and additive source taps, without rack effects. */
tasks.register<JavaExec>("generateObbligatoAudition") {
    group = "distribution"
    description = "Render four dry OBBLIGATO relationships, same-gain stems, response-disabled comparisons, portable listening page and ZIP under testkit/obbligato-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "512m"
    args("${rootDir}/testkit/obbligato-audition")
}

/** Render the accepted duet as sixteen velocity-layered pads for the app and MPC. */
tasks.register<JavaExec>("generateObbligatoKit") {
    group = "distribution"
    description = "Export sixteen accepted OBBLIGATO pads with native velocity layers, MPC 3, an MPC 2 twin, recipes and ZIP."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ObbligatoKitGenerator")
    workingDir = projectDir
    maxHeapSize = "512m"
    args(providers.gradleProperty("obbligatoKitOutput").orNull ?: "${rootDir}/testkit")
}

/** Finite OVERLAP proof: twelve real Kotlin renders, replayable recipes and the listening bench. */
tasks.register<JavaExec>("generateOverlapAudition") {
    group = "distribution"
    description = "Render OVERLAP raw/matched WAVs, diagnostic comparisons and the first-listen page."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OverlapAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/overlap-audition")
}

/** First finite FLOCK experiment: interacting particles, controls and causal comparisons. */
tasks.register<JavaExec>("generateFlockAudition") {
    group = "distribution"
    description = "Render FLOCK raw/matched PCM16 clips, recipes, pitch traces, source provenance and a downloadable ZIP under testkit/flock-audition/."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlockAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("flockOutput").orNull ?: "${rootDir}/testkit/flock-audition")
}

/** Revise FLOW's five recordings while preserving the complete accepted first-flight baseline. */
tasks.register<JavaExec>("generateFlockFlowAudition") {
    group = "distribution"
    description = "Render five model-2 FLOCK FLOW rates, preserve the original 51-clip baseline and export a 56-clip comparison audition."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlockFlowAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("flockFlowOutput").orNull ?: "${rootDir}/testkit/flock-flow-audition")
    args(providers.gradleProperty("flockBaseline").orNull ?: "${rootDir}/testkit/flock-audition")
}

/** Experimental RIBBON sustain: long continuous / repeated-body WAV comparisons at three FLOW rates. */
tasks.register<JavaExec>("generateFlockRibbonSustainAudition") {
    group = "distribution"
    description = "Render six long FLOCK RIBBON sustain comparisons, sampler WAVs, exact experimental recipes and split raw/matched listening packs."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlockRibbonSustainAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("flockSustainOutput").orNull ?: "${rootDir}/testkit/flock-ribbon-sustain")
}

/** Six-voice WICK milestone: dry audio, musical phrases and real held-loop evidence. */
tasks.register<JavaExec>("generateWickAudition") {
    group = "distribution"
    description = "Render WICK native/raw/matched WAVs, exact phrase scores and patches, causal traces and the audition page under testkit/wick-audition/."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.WickAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("wickOutput").orNull ?: "${rootDir}/testkit/wick-audition")
    if (providers.gradleProperty("wickQuick").orNull == "true") args("--quick")
}

/** Same RATTLE movement with diagnostic alternate tonal bodies; normal patches stay fixed. */
tasks.register<JavaExec>("generateWickSourceStudy") {
    group = "distribution"
    description = "Render four RATTLE source families at three notes, held notes and an identical phrase."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.WickSourceStudyGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("wickSourceOutput").orNull ?: "${rootDir}/testkit/wick-source-study")
}

tasks.register<JavaExec>("generateWickKit") {
    group = "distribution"
    description = "Export the dry provisional WICK MPC 3 kit, MPC 2 program twin and regenerable recipes."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.WickKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** First recorded-voice Confluence proof; no synthetic vocal placeholders. */
tasks.register<JavaExec>("generateConfluenceAudition") {
    group = "distribution"
    description = "Render Confluence recorded-source, crowd, phrase and identity comparisons with reproducible recipes."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ConfluenceAuditionGenerator")
    workingDir = rootDir
    args("${rootDir}/testkit/confluence-audition")
}

/** First Osprey proof: real five-shore SWELL renders and reproducible listening evidence. */
tasks.register<JavaExec>("generateOspreyAudition") {
    group = "distribution"
    description = "Render OSPREY shore, weather, morph, component and control comparisons under testkit/osprey-audition/."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OspreyAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/osprey-audition")
}

/** Compare preserved model 1 with model 2's coast-specific materials and ocean gestures. */
tasks.register<JavaExec>("generateOspreyRevisionAudition") {
    group = "distribution"
    description = "Render OSPREY R1/R2 pairs, shore and weather lineups, and creature-free body diagnostics."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OspreyRevisionAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/osprey-revision-audition")
    project.findProperty("ospreyBaselineDir")?.toString()?.let { args(it) }
    args("--include-bodies")
}

/** Compare preserved model 1 with model 3's small modal body-balance refinement. */
tasks.register<JavaExec>("generateOspreyGentleAudition") {
    group = "distribution"
    description = "Render OSPREY R1/R3 pairs, original-gesture lineups and creature-free body diagnostics."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OspreyGentleAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/osprey-gentle-audition")
    project.findProperty("ospreyBaselineDir")?.toString()?.let { args(it) }
    args("--include-bodies")
}

/** Dry PIPE/MUSETTE prototypes, exact engine loops and existing organ references. */
tasks.register<JavaExec>("generateRegistersAudition") {
    group = "distribution"
    description = "Render REGISTERS dry raw/matched 24-bit WAVs, regenerable recipes, diagnostics and the audition page."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-audition-r3")
    if (project.hasProperty("registersBaseline")) args("--baseline=${project.property("registersBaseline")}")
    (project.findProperty("registersR2Baseline") ?: project.findProperty("registersPrevious"))?.let { args("--previous=$it") }
}

/** Extend the exact published R3 archive with dry model-4 combo-organ evidence. */
tasks.register<JavaExec>("generateRegistersTransistorAudition") {
    group = "distribution"
    description = "Preserve published REGISTERS R3 audio and reviews, then append TRANSISTOR PCM24 audition cases."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersTransistorAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-audition-r4")
    project.findProperty("registersR3Baseline")?.toString()?.let { args("--baseline=$it") }
}

/** Compare the exact published model-4 sounds with a fuller same-pitch foundation. */
tasks.register<JavaExec>("generateRegistersTransistorDepthAudition") {
    group = "distribution"
    description = "Preserve published REGISTERS R4 audio and reviews, then render matched model-5 TRANSISTOR depth comparisons."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersTransistorDepthAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-audition-r5")
    project.findProperty("registersR4Baseline")?.toString()?.let { args("--baseline=$it") }
}

/** Actual keyboard attack-to-loop pairs with the complete accepted R5 archive retained. */
tasks.register<JavaExec>("generateRegistersTransistorSustainAudition") {
    group = "distribution"
    description = "Preserve published REGISTERS R5 audio and reviews, then compare model-5/model-6 keyboard sustain."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersTransistorSustainAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-audition-r6")
    project.findProperty("registersR5Baseline")?.toString()?.let { args("--baseline=$it") }
}

/** Stronger current-model recipe options for listening before any factory change. */
tasks.register<JavaExec>("generateRegistersDepthDirectionsAudition") {
    group = "distribution"
    description = "Compare current TRANSISTOR, warmer body and stronger ensemble at three notes without changing factory settings."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersDepthDirectionsAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-depth-directions")
}

/** Separate vibrato from spread before choosing a calmer ensemble setting. */
tasks.register<JavaExec>("generateRegistersEnsembleRefinementAudition") {
    group = "distribution"
    description = "Compare the current Rich Ensemble with less vibrato and calmer bank spread at three notes."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersDepthDirectionsAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-ensemble-refinement", "--refine-ensemble")
}

/** Compare octave registration while keeping the calmer ensemble movement fixed. */
tasks.register<JavaExec>("generateRegistersOctaveBalanceAudition") {
    group = "distribution"
    description = "Compare COLOR .42 and .65 at fixed calmer SPREAD and MOTION settings at three notes."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RegistersDepthDirectionsAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("registersOutput")?.toString() ?: "${rootDir}/testkit/registers-octave-balance", "--octave-balance")
}

/** Twelve dry Rössler-trajectory comparisons with complete replayable recipes. */
tasks.register<JavaExec>("generateVentAudition") {
    group = "distribution"
    description = "Render the twelve VENT native/matched PCM24 WAVs, recipes and measurements under testkit/vent-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.VentAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/vent-audition")
}

/** Four longer comparisons with one fixed linear readout and no projection saturation. */
tasks.register<JavaExec>("generateVentIsolationAudition") {
    group = "distribution"
    description = "Render the four VENT isolation native/matched PCM24 WAVs, diagnostic recipes and measurements under testkit/vent-isolation-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.VentIsolationAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/vent-isolation-audition")
}

/** Twelve clean model-2 gestures with ordinary replayable PadRecipes. */
tasks.register<JavaExec>("generateVentVoiceAudition") {
    group = "distribution"
    description = "Render the twelve clean VENT voice native/matched PCM24 WAVs, recipes and measurements under testkit/vent-voice-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.VentVoiceAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/vent-voice-audition")
}

/** Three finite ORIGAMI voices, unfolded comparisons and note-relative geometry sweeps. */
tasks.register<JavaExec>("generateOrigamiAudition") {
    group = "distribution"
    description = "Render the ORIGAMI first-listen raw/matched WAVs, recipes, measurements and offline listening page."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OrigamiAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/origami-audition")
    if (project.hasProperty("origamiQuick")) args("--quick")
    if (project.hasProperty("origamiEmbedAudio")) args("--embed-audio")
    if (project.hasProperty("origamiRefreshPage")) args("--refresh-page")
    project.findProperty("origamiBaseline")?.toString()?.let { args("--comparison-baseline=$it") }
}

/** Real JVM-engine listening evidence: dry voices, macro grids and settled held loops. */
tasks.register<JavaExec>("generateMurmurAudition") {
    group = "distribution"
    description = "Render MURMUR raw/matched WAVs, measurements and the audition page. -PmurmurFull adds the full pitch/velocity and interaction grids."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MurmurAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/murmur-audition")
    if (project.hasProperty("murmurFull")) args("--full")
}

/** Experimental KEY contour and low-register CHOIR loop comparisons; factory audio is unchanged. */
tasks.register<JavaExec>("generateMurmurLuthierAudition") {
    group = "distribution"
    description = "Render the reversible MURMUR Luthier comparisons with native/matched WAVs and exact experimental recipes."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MurmurLuthierAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("murmurLuthierOutput").orNull ?: "${rootDir}/testkit/murmur-luthier-audition")
}

/** Rebuild the six owner-preferred comparisons as ordinary saved production patches. */
tasks.register<JavaExec>("generateMurmurApprovedAudition") {
    group = "distribution"
    description = "Render approved KEY and low CHOIR recipes and verify their published listening hashes."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MurmurApprovedAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("murmurApprovedOutput").orNull ?: "${rootDir}/testkit/murmur-approved-audition")
}

/** Export the sixteen first-listen pads through the standard MPC program pipeline. */
tasks.register<JavaExec>("generateMurmurKit") {
    group = "distribution"
    description = "Render the dry MURMUR MPC acceptance kit with regenerable kit.json recipes."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MurmurKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Original BELLOWS source models, saved gestures and dry listening comparisons. */
tasks.register<JavaExec>("generateBellowsAudition") {
    group = "distribution"
    description = "Render BELLOWS PCM16 raw/matched WAVs, recipes, measurements and first-listen ZIP under testkit/bellows-audition/."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.BellowsAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/bellows-audition")
}

repositories {
    mavenCentral()
}

dependencies {
    // Versioned eighth-order 8(5,3) integrator and dense output for TREFOIL's
    // offline Newtonian trajectory. Apache-2.0; never runs on the audio clock.
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation(project(":audio"))
    implementation(project(":json"))
    // The factory kit builders hand ArrangedPads (audio + class + recipe)
    // straight to the kit pipeline.
    implementation(project(":kit"))
    testImplementation(kotlin("test"))
    // Integration tests drive a rendered kit through the real export pipeline.
    testImplementation(project(":xpm"))
    // The MPC 3 generators write native tracks with embedded groove clips.
    testImplementation(project(":mpc3"))
}

tasks.register<JavaExec>("generateTrefoilAudition") {
    group = "distribution"
    description = "Render finite TREFOIL studies, causal comparisons, trajectories and replayable recipes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TrefoilAuditionGenerator")
    maxHeapSize = "1g"
    workingDir = projectDir
    val replay = providers.gradleProperty("trefoilReplay").orNull
    if (replay != null) {
        args("--replay", replay, providers.gradleProperty("trefoilReplayWav").get(),
            providers.gradleProperty("trefoilReplayMode").orNull ?: "matched")
    } else {
        args(providers.gradleProperty("trefoilOutput").orNull ?: "${rootDir}/testkit/trefoil-model-two-audition")
    }
}

// The native processor and the JVM recipe reader share one canonical registry.
// This is data copying only: the JVM module still builds without an Android SDK.
tasks.processResources {
    from(rootProject.files("app/src/main/cpp/aquarium/parameters.json", "app/src/main/cpp/aquarium/presets.json")) {
        into("com/snipsnap/synth/aquarium")
    }
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
    // Parallel oversampled drone renders exceed Gradle's 512 MiB worker default.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/** Stoop's dry pack; preserve both published banks and the previous held-control references. */
tasks.register<JavaExec>("generateStoopAudition") {
    group = "distribution"
    description = "Render STOOP presets, phrases, macro comparisons and held loops under testkit/stoop-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.StoopAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/stoop-audition")
    providers.gradleProperty("stoopBaselineDir").orNull?.let { args("--baseline-dir=$it") }
    providers.gradleProperty("stoopOriginalBaselineDir").orNull?.let { args("--original-baseline-dir=$it") }
    if (project.hasProperty("stoopQuick")) args("--quick")
    if (project.hasProperty("stoopRefreshPage")) args("--refresh-page")
}

/** Actual TANGLE renders, listening assets and a reproducible probe report. */
tasks.register<JavaExec>("generateTangleAudition") {
    group = "distribution"
    description = "Render TANGLE matched/raw WAVs, patches, manifest, causal probes and listening page under testkit/tangle-audition/. -Pquick selects a development pack."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TangleAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "2g"
    args("${rootDir}/testkit/tangle-audition")
    if (project.hasProperty("quick")) args("--quick")
}

/** REVEL's dry acceptance matrix and standalone listening page; -Pquick selects first-listen evidence. */
tasks.register<JavaExec>("generateRevelAudition") {
    group = "distribution"
    description = "Render REVEL raw/matched WAVs, shared-performance mic/source taps, diagnostics and a standalone audition page under testkit/revel-audition/. -Pquick selects the smaller first-listen pack."
    maxHeapSize = "1536m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RevelAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/revel-audition")
    if (project.hasProperty("quick")) args("--quick")
}

/** CIRCUIT's complete listener pack; -Pquick renders its smaller development pack. */
tasks.register<JavaExec>("generateCircuitAudition") {
    group = "distribution"
    description = "Render CIRCUIT raw/matched WAVs, diagnostics, manifest and listening page under testkit/circuit-audition/. -Pquick selects the smoke pack."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CircuitAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/circuit-audition")
    if (project.hasProperty("quick")) args("--quick")
}

/** One continuous minute; a diagnostic performance rather than a new patch or HOLD format. */
tasks.register<JavaExec>("generateCircuitCeremony") {
    group = "distribution"
    description = "Render one 60-second CIRCUIT ceremony with raw/matched PCM24 and event/path/source diagnostics."
    dependsOn("testClasses")
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CircuitCeremonyGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("circuitCeremonyOut").orElse("${rootDir}/testkit/circuit-ceremony").get())
    providers.gradleProperty("circuitCeremonyInputs").orNull?.let {
        systemProperty("circuit.ceremony.inputs", it)
    }
}

/** Five ordinary ROOT patches with complete native, audition and web diagnostics. */
tasks.register<JavaExec>("generateCircuitPlayable") {
    group = "distribution"
    description = "Render CIRCUIT finite/held ROOT defaults and three presets with raw/matched PCM24, web PCM16 and replayable diagnostics."
    dependsOn("testClasses")
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CircuitPlayableGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("circuitPlayableOut").orElse("${rootDir}/testkit/circuit-playable").get())
    providers.gradleProperty("circuitPlayableInputs").orNull?.let {
        systemProperty("circuit.playable.inputs", it)
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

/** Render the PLUCK depth audition clips and page under testkit/pluck-audition/. See PluckAuditionGenerator. */
tasks.register<JavaExec>("generateTinesAudition") {
    group = "distribution"
    description = "Render the TINES sound-design audition phrases, manifest and listening page under testkit/tines-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TinesAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/tines-audition")
}

tasks.register<JavaExec>("generatePluckAudition") {
    group = "distribution"
    description = "Render the PLUCK depth audition clips and listening page under testkit/pluck-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PluckAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pluck-audition")
}

/** Render the SILK Phase 4 audition clips and page under testkit/silk-audition/. See SilkAuditionGenerator. */
tasks.register<JavaExec>("generateSilkAudition") {
    group = "distribution"
    description = "Render the SILK Phase 4 audition clips and listening page under testkit/silk-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SilkAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/silk-audition")
}

/** Render the GLINT paths audition (WAVs only) under testkit/glint-paths-audition/. See GlintPathsAuditionGenerator. */
tasks.register<JavaExec>("generateGlintPathsAudition") {
    group = "distribution"
    description = "Render the GLINT paths audition clips under testkit/glint-paths-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintPathsAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-paths-audition")
}

/** Render the GLINT DEPTH audition (held pads, WAVs only) under testkit/glint-depth-audition/. See GlintDepthAuditionGenerator. */
tasks.register<JavaExec>("generateGlintDepthAudition") {
    group = "distribution"
    description = "Render the GLINT DEPTH audition pads under testkit/glint-depth-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintDepthAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-depth-audition")
}

/** Render the GLINT roster candidates (16 held pads per voice, WAVs and a manifest) under testkit/glint-roster-candidates/. See GlintRosterAuditionGenerator. */
tasks.register<JavaExec>("generateGlintRosterAudition") {
    group = "distribution"
    description = "Render the GLINT roster candidates (16 held pads per voice) under testkit/glint-roster-candidates/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintRosterAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-roster-candidates")
}

/** Render the GLINT roster's final listen (each preset as a one-shot and three pads, WAVs and a manifest) under testkit/glint-roster-final/. See GlintRosterListenGenerator. */
tasks.register<JavaExec>("generateGlintRosterListen") {
    group = "distribution"
    description = "Render the GLINT roster's final listen (one file per preset) under testkit/glint-roster-final/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintRosterListenGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-roster-final")
}

/** Render the factory kit as a native MPC 3 track into testkit/. See Mpc3KitGenerator. */
tasks.register<JavaExec>("generateMpc3Kit") {
    group = "distribution"
    description = "Render the factory kit as a native MPC 3 .xtd + _[TrackData]/ under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.Mpc3KitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the whole session as one MPC 3 project into testkit/. See SessionProjectGenerator. */
tasks.register<JavaExec>("generateSessionProject") {
    group = "distribution"
    description = "Render kit + instruments + groove as one .xpj + _[ProjectData]/ under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SessionProjectGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the S5 instrument suite (dual-generation) into testkit/Instruments/. See InstrumentSuiteGenerator. */
tasks.register<JavaExec>("generateInstrumentSuite") {
    group = "distribution"
    description = "Render the nine-instrument S5 suite as .xty + .xpm twins under testkit/Instruments/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.InstrumentSuiteGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the keygroup program as a native MPC 3 .xty into testkit/. See Mpc3KeysGenerator. */
tasks.register<JavaExec>("generateMpc3Keys") {
    group = "distribution"
    description = "Render the VELVET keygroup program as a native MPC 3 .xty + _[TrackData]/ under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.Mpc3KeysGenerator")
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

/** Render the TIDE acceptance kit into testkit/. See TideKitGenerator. */
tasks.register<JavaExec>("generateTideKit") {
    group = "distribution"
    description = "Render the TIDE West Coast acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TideKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the ENSEMBLE gate: four sources dry and through the section, stereo beside its fold, under testkit/ensemble-audition/. See EnsembleAuditionGenerator. */
tasks.register<JavaExec>("generateEnsembleAudition") {
    group = "distribution"
    description = "Render the ENSEMBLE rack section's four-source gate, manifest and listening page under testkit/ensemble-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.EnsembleAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/ensemble-audition")
}

/** First WIDE compound-board proof, rendered in its own explicit stage order. */
tasks.register<JavaExec>("generateWideAudition") {
    group = "distribution"
    description = "Render WIDE original/strength/stereo/mono comparisons, recipes and measurements."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.WideAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("wideOutput")?.toString() ?: "${rootDir}/testkit/wide-audition")
}

/** RUSH's reproducible compound-board listening proof, including level response. */
tasks.register<JavaExec>("generateRushAudition") {
    group = "distribution"
    description = "Render RUSH original/intensity, cumulative-stage and input-level stereo/mono comparisons."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RushAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("rushOutput")?.toString() ?: "${rootDir}/testkit/rush-audition")
}

tasks.register<JavaExec>("generateStringMachineAudition") {
    group = "distribution"
    description = "Render the four string-machine presets' gate (as landed, bare voice, control), manifest and listening page under testkit/stringmachine-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.StringMachineAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/stringmachine-audition")
}

/** Render the SIREN audition clips, manifest and page under testkit/siren-audition/. See SirenAuditionGenerator. */
tasks.register<JavaExec>("generateSirenAudition") {
    group = "distribution"
    description = "Render the SIREN audition clips, manifest and listening page under testkit/siren-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SirenAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/siren-audition")
}

tasks.register<JavaExec>("generateSirenKit") {
    group = "distribution"
    description = "Render the SIREN dub siren acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SirenKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

tasks.register<JavaExec>("generateForkKit") {
    group = "distribution"
    description = "Render the FORK modal electric piano acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ForkKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the FORK round-one audition clips, manifest and page under testkit/fork-audition/. See ForkAuditionGenerator. */
tasks.register<JavaExec>("generateForkAudition") {
    group = "distribution"
    description = "Render the FORK audition clips, manifest and listening page under testkit/fork-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ForkAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/fork-audition")
}

tasks.register<JavaExec>("generateBoreKit") {
    group = "distribution"
    description = "Render the BORE blown-woodwind acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.BoreKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the BORE round-one audition clips, manifest and page under testkit/bore-audition/. See BoreAuditionGenerator. */
tasks.register<JavaExec>("generateBoreAudition") {
    group = "distribution"
    description = "Render the BORE audition clips, manifest and listening page under testkit/bore-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.BoreAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/bore-audition")
}

tasks.register<JavaExec>("generateArcoKit") {
    group = "distribution"
    description = "Render the ARCO bowed-string acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the ARCO audition clips, manifest and page under testkit/arco-audition/. See ArcoAuditionGenerator. */
tasks.register<JavaExec>("generateArcoAudition") {
    group = "distribution"
    description = "Render the ARCO audition clips, manifest and listening page under testkit/arco-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-audition")
}

/** Render the ARCO R1c re-listen clips, manifest and page under testkit/arco-retune/. See ArcoRetuneGenerator. */
tasks.register<JavaExec>("generateArcoRetune") {
    group = "distribution"
    description = "Render the ARCO R1c re-listen clips, manifest and listening page under testkit/arco-retune/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoRetuneGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-retune")
}

/** Render the ARCO R1d BODY listening clips, manifest and page under testkit/arco-body/ and the key beside it. See ArcoBodyGenerator. */
tasks.register<JavaExec>("generateArcoBody") {
    group = "distribution"
    description = "Render the ARCO R1d BODY listening clips, manifest and listening page under testkit/arco-body/ and the key to it beside the folder as testkit/arco-body-key.json."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoBodyGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-body")
}

/** Render the ARCO R1e warmth listening clips, manifest and page under testkit/arco-warmth/ and the key beside it. See ArcoWarmthGenerator. */
tasks.register<JavaExec>("generateArcoWarmth") {
    group = "distribution"
    description = "Render the ARCO R1e warmth listening clips, manifest and listening page under testkit/arco-warmth/ and the key to it beside the folder as testkit/arco-warmth-key.json."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoWarmthGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-warmth")
}

/**
 * Render the ARCO R1h body gate clips, manifest and page under testkit/arco-body-gate/ and the key beside it. See ArcoBodyGateGenerator. The full run refuses a missing or placeholder page;
 * `-PclipsOnly` is the development mode (clips, manifest and key, no index.html).
 */
tasks.register<JavaExec>("generateArcoBodyGate") {
    group = "distribution"
    description = "Render the ARCO R1h body gate clips, manifest and listening page under testkit/arco-body-gate/ and the key to it beside the folder as testkit/arco-body-gate-key.json. -PclipsOnly skips the page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoBodyGateGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-body-gate")
    if (project.hasProperty("clipsOnly")) args("--clips-only")
}

/**
 * Read the saved verdicts of the ARCO R1h body gate page and print the pass rule P1 to P5, the reading rules and the fix map, using the key testkit/arco-body-gate-key.json.
 * `-PgateVerdicts=<folder or file>[,<folder or file>...]` names what to read. See ArcoBodyGateDecoder.
 */
tasks.register<JavaExec>("decodeArcoBodyGate") {
    group = "distribution"
    description = "Decode the saved verdicts of the ARCO R1h body gate page (-PgateVerdicts=<folder or file>[,<folder or file>...]) against testkit/arco-body-gate-key.json."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoBodyGateDecoder")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-body-gate-key.json")
    (project.findProperty("gateVerdicts") as String?)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { args(it) }
}

/**
 * Render the ARCO R1j bell gate clips, manifest and page under testkit/arco-bell-gate/ and the key beside it. See ArcoBellGateGenerator. The full run refuses a missing or placeholder page;
 * `-PclipsOnly` is the development mode (clips, manifest and key, no index.html).
 */
tasks.register<JavaExec>("generateArcoBellGate") {
    group = "distribution"
    description = "Render the ARCO R1j bell gate clips, manifest and listening page under testkit/arco-bell-gate/ and the key to it beside the folder as testkit/arco-bell-gate-key.json. -PclipsOnly skips the page."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoBellGateGenerator")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-bell-gate")
    if (project.hasProperty("clipsOnly")) args("--clips-only")
}

/**
 * Read the saved verdicts of the ARCO R1j bell gate page and print the pre-written reading rules, each comparison, the keep answers, the verdicts and the build map's case line, using the key
 * testkit/arco-bell-gate-key.json. `-PgateVerdicts=<folder or file>[,<folder or file>...]` names what to read (none: only the rules and the decoder's self-check on invented answers). See ArcoBellGateDecoder.
 */
tasks.register<JavaExec>("decodeArcoBellGate") {
    group = "distribution"
    description = "Decode the saved verdicts of the ARCO R1j bell gate page (-PgateVerdicts=<folder or file>[,<folder or file>...]) against testkit/arco-bell-gate-key.json."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ArcoBellGateDecoder")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    workingDir = projectDir
    args("${rootDir}/testkit/arco-bell-gate-key.json")
    (project.findProperty("gateVerdicts") as String?)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { args(it) }
}

tasks.register<JavaExec>("generateMercuryKit") {
    group = "distribution"
    description = "Render the MERCURY modal-glass acceptance kits (SnipSnap Mercury Kit and Kit 2) under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MercuryKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the AEROSTAT audition clips, manifest and page under testkit/aerostat-audition/. See AerostatAuditionGenerator. */
tasks.register<JavaExec>("generateAerostatAudition") {
    group = "distribution"
    description = "Render the AEROSTAT audition clips, manifest and listening page under testkit/aerostat-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.AerostatAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/aerostat-audition")
}

/** Render the MERCURY audition clips, manifest and page under testkit/mercury-audition/. See MercuryAuditionGenerator. */
tasks.register<JavaExec>("generateMercuryAudition") {
    group = "distribution"
    description = "Render the MERCURY audition clips, manifest and listening page under testkit/mercury-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MercuryAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/mercury-audition")
}

/** Render the GYRE round-one audition clips, manifest and page under testkit/gyre-audition/. See GyreAuditionGenerator. */
tasks.register<JavaExec>("generateGyreAudition") {
    group = "distribution"
    description = "Render the GYRE audition clips, manifest and listening page under testkit/gyre-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GyreAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/gyre-audition")
}

/** Render THAW's raw/matched listening gate under testkit/thaw-audition/. */
tasks.register<JavaExec>("generateThawAudition") {
    group = "distribution"
    description = "Render THAW's voices, macro sweeps, material diagnostics, interaction grids and held loops with a listening page under testkit/thaw-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ThawAuditionGenerator")
    workingDir = projectDir
    val firstListen = project.hasProperty("thawFirstListen")
    args("${rootDir}/testkit/" + if (firstListen) "thaw-first-listen" else "thaw-audition")
    if (firstListen) args("--first-listen")
    (project.findProperty("thawSections") as String?)?.let { args("--sections=$it") }
}

/** Render SCOUR's R2 listen (the impact into a drop, raw and matched WAVs, manifest, page) under testkit/scour-audition/. */
tasks.register<JavaExec>("generateScourAudition") {
    group = "distribution"
    description = "Render SCOUR's kit bank B, its three voices into a drop at 120 and 90 BPM, each build alone, the voices side by side, and SHEET's sweeps and edges with a listening page under testkit/scour-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ScourAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/scour-audition")
}

/** Export SCOUR's thirty-two dry pads over two banks (builds at 120 and 90 BPM, then the presets; bank B is SHEET's) to testkit/SnipSnap Scour Kit/. */
tasks.register<JavaExec>("generateScourKit") {
    group = "distribution"
    description = "Export SCOUR's two banks (builds cut for 120 and 90 BPM, its twelve presets and SHEET's low hits) as an MPC kit under testkit/SnipSnap Scour Kit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ScourKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render SUTURE's raw/matched mechanical and listening evidence locally. */
tasks.register<JavaExec>("generateSutureAudition") {
    group = "distribution"
    description = "Render SUTURE's voices, macro sweeps, interaction grids, mechanical diagnostics and held loops with a local listening page."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SutureAuditionGenerator")
    workingDir = projectDir
    val firstListen = project.hasProperty("sutureFirstListen")
    args("${rootDir}/testkit/" + if (firstListen) "suture-first-listen" else "suture-audition")
    if (firstListen) args("--first-listen")
    (project.findProperty("sutureSections") as String?)?.let { args("--sections=$it") }
    (project.findProperty("suturePrevious") as String?)?.let { args("--previous=$it") }
}

/** Export the sixteen dry THAW acceptance pads as an MPC program folder. */
tasks.register<JavaExec>("generateThawKit") {
    group = "distribution"
    description = "Render the THAW ice-plate acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ThawKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

tasks.register<JavaExec>("generateMagnetKit") {
    group = "distribution"
    description = "Render the MAGNET electric-string acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MagnetKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the MAGNET R1 audition clips and manifest under testkit/magnet-audition/. See MagnetAuditionGenerator. */
tasks.register<JavaExec>("generateMagnetAudition") {
    group = "distribution"
    description = "Render the MAGNET R1 audition clips and manifest under testkit/magnet-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MagnetAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/magnet-audition")
}

/** Render the TERRA world-percussion acceptance kit into testkit/. See TerraKitGenerator. */
tasks.register<JavaExec>("generateTerraKit") {
    group = "distribution"
    description = "Render the TERRA world-percussion acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TerraKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the TERRA audition clips, manifest and page under testkit/terra-audition/. See TerraAuditionGenerator. */
tasks.register<JavaExec>("generateTerraAudition") {
    group = "distribution"
    description = "Render the TERRA audition clips, manifest and listening page under testkit/terra-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TerraAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/terra-audition")
}

/** Render TERRA R1's listening clips (HIT in the engine) and manifest under testkit/terra-audition/R1/. See TerraAuditionGenerator.renderR1. */
tasks.register<JavaExec>("generateTerraR1Audition") {
    group = "distribution"
    description = "Render the TERRA R1 (HIT) listening clips and manifest under testkit/terra-audition/R1/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TerraAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/terra-audition", "r1")
}

/** Render TERRA R1b's listening clips (HIT's ladder and its floor) and manifest under testkit/terra-audition/R1B/. See TerraAuditionGenerator.renderR1B. */
tasks.register<JavaExec>("generateTerraR1BAudition") {
    group = "distribution"
    description = "Render the TERRA R1b (HIT's ladder and floor) listening clips and manifest under testkit/terra-audition/R1B/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TerraAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/terra-audition", "r1b")
}

/** Render the TREMOR audition clips, manifest and page under testkit/tremor-audition/. See TremorAuditionGenerator. */
tasks.register<JavaExec>("generateTremorAudition") {
    group = "distribution"
    description = "Render the TREMOR audition clips, manifest and listening page under testkit/tremor-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TremorAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/tremor-audition")
}

tasks.register<JavaExec>("generateTremorComparison") {
    group = "distribution"
    description = "Render matched Tremor voice, control, touch and held comparisons with metadata."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TremorComparisonGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/tremor-comparison/${project.findProperty("comparisonVersion") ?: "candidate"}")
}

/** Render the VALVE V1 listening clips and manifest under testkit/valve-audition/. See ValveAuditionGenerator. */
tasks.register<JavaExec>("generateValveAudition") {
    group = "distribution"
    description = "Render the VALVE V1 listening clips and manifest under testkit/valve-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ValveAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/valve-audition")
}

/** Render ROOM's listening gate (the real section across SIZE, WALLS and DISTANCE, manifest, page) under testkit/room-audition/. See RoomAuditionGenerator. */
tasks.register<JavaExec>("generateRoomAudition") {
    group = "distribution"
    description = "Render ROOM's listening gate clips, manifest and page under testkit/room-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RoomAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/room-audition", "${rootDir}")
}

/** Render FORGE's DEPTH gate (YIELD 0.25 against DEPTH, DEPTH's in-between values, manifest, page) under testkit/forge-depth-audition/. See ForgeDepthAuditionGenerator. */
tasks.register<JavaExec>("generateForgeDepthAudition") {
    group = "distribution"
    description = "Render FORGE's DEPTH gate clips, manifest and listening page under testkit/forge-depth-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ForgeDepthAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/forge-depth-audition", "${rootDir}")
}

/** Render FORGE's CHARGE tuning gate (four media, clips, manifest, page) under testkit/forge-charge-audition/. See ForgeChargeAuditionGenerator. */
tasks.register<JavaExec>("generateForgeChargeAudition") {
    group = "distribution"
    description = "Render FORGE's CHARGE tuning gate under testkit/forge-charge-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ForgeChargeAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/forge-charge-audition", "${rootDir}")
}

/** Render FORGE's listening gate (clips, manifest, page) under testkit/forge-audition/. See ForgeAuditionGenerator. */
tasks.register<JavaExec>("generateForgeAudition") {
    group = "distribution"
    description = "Render FORGE's listening gate (clips, manifest, page) under testkit/forge-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ForgeAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/forge-audition", "${rootDir}")
}

/** Render the pedal board's Stage 0 listening clips and manifest under testkit/pedal-audition/. See PedalAuditionGenerator. */
tasks.register<JavaExec>("generateFadeLawAudition") {
    group = "distribution"
    description = "Render the fade law's listen (DRIVE at AMT 0.7, the linear and the LOG walk) under testkit/fade-law-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FadeLawAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/fade-law-audition")
}

tasks.register<JavaExec>("generatePedalAudition") {
    group = "distribution"
    description = "Render the pedal board's Stage 0 listening clips and manifest under testkit/pedal-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PedalAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pedal-audition")
}

/** Render Stage 3's listen (the pedals on both guitar pads after and in front of the amp) under testkit/amp-front-audition/. See AmpFrontAuditionGenerator. */
tasks.register<JavaExec>("generateAmpFrontAudition") {
    group = "distribution"
    description = "Render Stage 3's listen (pedals after and in front of a guitar pad's amp) under testkit/amp-front-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.AmpFrontAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/amp-front-audition")
}

/** Render the WAH section's gate clips and manifest under testkit/wah-audition/. See WahAuditionGenerator. */
tasks.register<JavaExec>("generateWahAudition") {
    group = "distribution"
    description = "Render the WAH section's gate clips and manifest under testkit/wah-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.WahAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/wah-audition")
}

/** Render the pedal board's Stage 1b listens (short pads through WAH with and without the one-cycle floor, and the FADE IN, VIBRATO and DELAY comparisons) under testkit/pedal-listens/. See PedalListensGenerator. */
tasks.register<JavaExec>("generatePedalListens") {
    group = "distribution"
    description = "Render the pedal board's Stage 1b listening clips, manifest and page under testkit/pedal-listens/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PedalListensGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pedal-listens")
}

tasks.register<JavaExec>("generateFlotillaKit") {
    group = "distribution"
    description = "Render the FLOTILLA acceptance kit under testkit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlotillaKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Render the FLOTILLA audition clips, manifest and page under testkit/flotilla-audition/. See FlotillaAuditionGenerator. */
tasks.register<JavaExec>("generateFlotillaAudition") {
    group = "distribution"
    description = "Render the FLOTILLA audition clips, manifest and listening page under testkit/flotilla-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlotillaAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/flotilla-audition")
}

/** Render CISTERN's raw/matched listening matrix and diagnostics under testkit/cistern-audition/. */
tasks.register<JavaExec>("generateCisternAudition") {
    group = "distribution"
    description = "Render the CISTERN raw/matched clips, causal diagnostics, manifest and local listening page under testkit/cistern-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CisternAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/cistern-audition")
}

tasks.register<JavaExec>("generateFlotillaComparison") {
    group = "distribution"
    description = "Render matched FLOTILLA voices, population steps, controls and held clips with metadata."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlotillaComparisonGenerator")
    workingDir = projectDir
    val version = project.findProperty("flotillaComparisonVersion") as String? ?: "candidate"
    args("${rootDir}/testkit/flotilla-comparison/$version")
}

/** Full BALLAST audition with shared-gain touch and component diagnostics. */
tasks.register<JavaExec>("generateBallastAudition") {
    group = "distribution"
    description = "Render BALLAST presets, control sweeps and correctly calibrated diagnostics."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.BallastAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/ballast-audition")
}

/** Capture before first; candidate mode requires the frozen baseline calibration. */
tasks.register<JavaExec>("generateBallastComparison") {
    group = "distribution"
    description = "Render BALLAST's matched bass, glass, structure and velocity comparison."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.BallastComparisonGenerator")
    workingDir = projectDir
    val version = project.findProperty("ballastComparisonVersion") as String? ?: "candidate"
    args("${rootDir}/testkit/ballast-comparison/$version")
}

/** Render MURK's dry voices, causal probes and accessible offline listening page. */
tasks.register<JavaExec>("generateMurkAudition") {
    group = "distribution"
    description = "Render the MURK audition WAVs, evidence manifest and accessible page under testkit/murk-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MurkAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/murk-audition")
    if (providers.gradleProperty("murkRefreshPage").orNull == "true") {
        args("--refresh-page")
    } else {
        providers.gradleProperty("murkBaselineDir").orNull?.let { args("--baseline-dir=$it") }
        providers.gradleProperty("murkBaselineRevision").orNull?.let { args("--baseline-revision=$it") }
    }
}

/** Render Undertow's raw/matched section-18 roster and offline evidence page. */
tasks.register<JavaExec>("generateUndertowAudition") {
    group = "distribution"
    description = "Render UNDERTOW audition WAVs, mechanics CSVs, manifest and accessible listening page under testkit/undertow-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.UndertowAuditionGenerator")
    // Keep sequential full-roster rendering within a bounded JVM heap.
    maxHeapSize = "512m"
    workingDir = projectDir
    args("${rootDir}/testkit/undertow-audition")
    if (providers.gradleProperty("undertowQuick").orNull == "true") args("--quick")
}

/** Render Corolla's pitch/velocity, macro, interaction and loop listening matrix. */
tasks.register<JavaExec>("generateCorollaAudition") {
    group = "distribution"
    description = "Render COROLLA native/matched clips, metrics and an offline listening page under testkit/corolla-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CorollaAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/corolla-audition")
    if (project.hasProperty("corollaQuick")) args("--quick")
}

/** Render Nimbus's six same-note identities, interaction grids and powered HOLD listening pack. */
tasks.register<JavaExec>("generateNimbusAudition") {
    group = "distribution"
    description = "Render NIMBUS dry audition WAVs, mechanical evidence and an accessible page under testkit/nimbus-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.NimbusAuditionGenerator")
    maxHeapSize = "512m"
    workingDir = projectDir
    args("${rootDir}/testkit/nimbus-audition")
    if (providers.gradleProperty("nimbusRefreshPage").orNull == "true") args("--refresh-page")
    if (providers.gradleProperty("nimbusQuick").orNull == "true") args("--quick")
    providers.gradleProperty("nimbusBaselineDir").orNull?.let { args("--baseline-dir=$it") }
    providers.gradleProperty("nimbusBaselineRevision").orNull?.let { args("--baseline-revision=$it") }
    if (providers.gradleProperty("nimbusCompareHold").orNull == "true") args("--compare-hold")
    if (providers.gradleProperty("nimbusCompareHoldAll").orNull == "true") args("--compare-hold-all")
}

/** Render Tessera's dry materials, moving paths, causal probes and settled loops. */
tasks.register<JavaExec>("generateTesseraAudition") {
    group = "distribution"
    description = "Render TESSERA raw/matched WAVs, traces, manifest and listening page under testkit/tessera-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TesseraAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/tessera-audition")
    if (project.hasProperty("tesseraRefreshPage")) args("--refresh-page")
    else if (project.hasProperty("tesseraQuick")) args("--quick")
}

/** Compare a committed material proposal with an explicitly supplied published audition. */
tasks.register<JavaExec>("generateTesseraMaterialComparison") {
    group = "distribution"
    description = "Render 12 controlled Tessera before/after pairs; requires -PtesseraComparisonBaseline=/absolute/path/to/published-audition."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TesseraMaterialComparisonGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/tessera-material-comparison", "--source-dir=${rootDir}")
    providers.gradleProperty("tesseraComparisonBaseline").orNull?.let { args("--baseline-dir=$it") }
    providers.gradleProperty("tesseraComparisonBaselineRevision").orNull?.let { args("--baseline-revision=$it") }
    doFirst {
        require(project.hasProperty("tesseraComparisonBaseline")) {
            "Set -PtesseraComparisonBaseline=/absolute/path/to/published-audition."
        }
    }
}

/** Render Pitchwheel's provisional dry voices, causal probes and settled held loops. */
tasks.register<JavaExec>("generatePitchwheelAudition") {
    group = "distribution"
    description = "Render PITCHWHEEL raw/matched WAVs, mechanical traces, a manifest and a local listening page under testkit/pitchwheel-audition/. -PpitchwheelFull adds complete sweeps and interaction grids."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PitchwheelAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pitchwheel-audition")
    if (project.hasProperty("pitchwheelFull")) args("--full")
}

/** Render Spool's factory library, original recordings, causal evidence, sweeps, loops and MPC kit. */
tasks.register<JavaExec>("generateSpoolAudition") {
    group = "distribution"
    description = "Render SPOOL's 36-preset library, dry native/matched WAVs, traces, musical examples and 16-pad MPC kit under testkit/spool-audition/. -PspoolQuick selects a smaller development pack; -PspoolMovementOnly or -PspoolMusicalOnly refreshes one section in an unchanged-source full pack."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SpoolAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/spool-audition")
    if (project.hasProperty("spoolQuick")) args("--quick")
    if (project.hasProperty("spoolMovementOnly")) args("--movement-only")
    if (project.hasProperty("spoolMusicalOnly")) args("--musical-only")
}

/** Render Embouchure's original dry gestures, control matrix and settled held loops. */
tasks.register<JavaExec>("generateEmbouchureAudition") {
    group = "distribution"
    description = "Render EMBOUCHURE dry WAVs, complete recipes, numerical listening metadata and the audition page under testkit/embouchure-audition/. -PembouchureQuick renders a smaller development pack."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.EmbouchureAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("embouchureOutput").orNull ?: "${rootDir}/testkit/embouchure-audition")
    if (project.hasProperty("embouchureQuick")) args("--quick")
    providers.gradleProperty("embouchureReference").orNull?.let { args("--reference-dir=$it") }
}

/** Render Prism's dry register/velocity, macro, interaction and HOLD matrix. */
tasks.register<JavaExec>("generatePrismAudition") {
    group = "distribution"
    description = "Render PRISM prelevel raw and matched WAVs, metrics and manifest under testkit/prism-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PrismAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/prism-audition")
}

/** Compare original PRISM spectra with saved finite additive motion, without effects. */
tasks.register<JavaExec>("generatePrismMotionAudition") {
    group = "distribution"
    description = "Render PRISM motion dry PCM16 raw/matched WAVs, complete recipes and manifest under testkit/prism-motion-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PrismMotionAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/prism-motion-audition")
}

/** Compare eight original source candidates with their dry PRISM imprint and motion. */
tasks.register<JavaExec>("generatePrismPaletteAudition") {
    group = "distribution"
    description = "Render eight source candidates, static PRISM imprints and moving PRISM PCM16 comparisons under testkit/prism-palette-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PrismPaletteAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/prism-palette-audition")
}

/** Compare a source-derived opening with the exact previous source-selection references. */
tasks.register<JavaExec>("generatePrismOpeningAudition") {
    group = "distribution"
    description = "Render four source-derived PRISM openings beside preserved palette references under testkit/prism-opening-audition/."
    dependsOn("generatePrismPaletteAudition")
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PrismOpeningAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/prism-opening-audition", "${rootDir}/testkit/prism-palette-audition")
    providers.gradleProperty("prismOpeningReferenceManifest").orNull?.let { args(it) }
}

/** Check the delivered body-opening presets across pitch and playing strength. */
tasks.register<JavaExec>("generatePrismOpeningReleaseAudition") {
    group = "distribution"
    description = "Render PRISM body-opening factory presets at three notes and two velocities, with replay and JVM timing evidence."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PrismOpeningReleaseAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/prism-opening-release-audition")
}

/** Render Alloy's provisional dry hybrid patches, causal FUSE comparisons and held-loop listening pack. */
tasks.register<JavaExec>("generateAlloyAudition") {
    group = "distribution"
    description = "Render ALLOY matched WAVs, raw diagnostics, patch JSONs, an MPC kit and an accessible listening page under testkit/alloy-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.AlloyAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args("${rootDir}/testkit/alloy-audition")
    if (project.hasProperty("alloyFull")) args("--full")
    if (project.hasProperty("alloyRefreshPage")) args("--refresh-page")
}

/** Current selected voices, separate from the frozen original and contrast references. */
tasks.register<JavaExec>("generateAlloySelectedAudition") {
    group = "distribution"
    description = "Render the selected ALLOY voice matrix, current factory patches and 16-pad MPC kit under testkit/alloy-selected/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.AlloyAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("alloySelectedOutput").orNull ?: "${rootDir}/testkit/alloy-selected", "--selected")
    if (project.hasProperty("alloySelectedFull")) args("--full")
    if (project.hasProperty("alloySelectedRefreshPage")) args("--refresh-page")
}

/** Approved distinct-role factory release, preserving all earlier listening packs. */
tasks.register<JavaExec>("generateAlloyFactoryAudition") {
    group = "distribution"
    description = "Render ALLOY's approved 24-patch factory bank, full-config listening matrix, 16-pad starter kit and three eight-pad MPC programs under testkit/alloy-factory/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.AlloyAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("alloyFactoryOutput").orNull ?: "${rootDir}/testkit/alloy-factory", "--factory")
    if (project.hasProperty("alloyFactoryFull")) args("--full")
    if (project.hasProperty("alloyFactoryRefreshPage")) args("--refresh-page")
}

/** Separate candidate recipes; the released Alloy audition remains unchanged. */
tasks.register<JavaExec>("generateAlloyContrastAudition") {
    group = "distribution"
    description = "Render 60 ALLOY voice/role/held comparisons, candidate patches and MPC kit under testkit/alloy-contrast/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.AlloyContrastAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args(providers.gradleProperty("alloyContrastOutput").orNull ?: "${rootDir}/testkit/alloy-contrast")
    if (project.hasProperty("alloyContrastRefreshPage")) args("--refresh-page")
}

/** Render Contour's original tables, related layers and complete dry listening matrix. */
tasks.register<JavaExec>("generateContourAudition") {
    group = "distribution"
    description = "Render CONTOUR raw/matched WAVs, shared-level references, diagnostics, HOLD repeats and an accessible page under testkit/contour-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ContourAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = projectDir
    args("${rootDir}/testkit/contour-audition")
    if (providers.gradleProperty("contourQuick").orNull == "true") args("--quick")
    if (providers.gradleProperty("contourRefreshPage").orNull == "true") args("--refresh-page")
    providers.gradleProperty("contourComparisonBaseline").orNull?.let {
        args("--comparison-baseline=$it")
    }
}

/** Render Lacquer's complete bank, layer solos, macro grids and held loops. */
tasks.register<JavaExec>("generateLacquerAudition") {
    group = "distribution"
    description = "Render LACQUER dry audition WAVs, measured evidence and a listening page under testkit/lacquer-audition/."
    maxHeapSize = "512m"
    dependsOn("generateLacquerKit")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.LacquerAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/lacquer-audition")
}

tasks.register<JavaExec>("generateLacquerKit") {
    group = "distribution"
    description = "Export the sixteen-pad LACQUER starter and complete thirty-six-preset bank as three twelve-pad MPC 3/MPC 2 programs."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.LacquerKitGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit")
}

/** Full dry SKITTER listening matrix, with a smaller optional development pack. */
tasks.register<JavaExec>("generateSkitterAudition") {
    group = "distribution"
    description = "Render SKITTER voice-character probes, WAVs, patches and listening page. -Pquick selects smoke clips; -PskitterBaseline=<R3>, -PskitterPrevious=<R2>, -PskitterOriginal=<R1> add current and historical comparisons."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SkitterAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/skitter-audition")
    if (project.hasProperty("quick")) args("--quick")
    if (project.hasProperty("skitterBaseline")) args("--baseline=${project.property("skitterBaseline")}")
    if (project.hasProperty("skitterPrevious")) args("--previous=${project.property("skitterPrevious")}")
    if (project.hasProperty("skitterOriginal")) args("--original=${project.property("skitterOriginal")}")
}

/** Small raw SKITTER articulation proof, independent of the full audition matrix. */
tasks.register<JavaExec>("generateSkitterArticulationAudition") {
    group = "distribution"
    description = "Render the bounded four-track SKITTER R4/R5 articulation proof with raw PCM24, cue/gain records and a listening page."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SkitterArticulationAudition")
    workingDir = projectDir
    args("${rootDir}/testkit/skitter-articulation")
    if (project.hasProperty("skitterProofSource")) args("--source-commit=${project.property("skitterProofSource")}")
}

/** Two new R6 tracks; R4 and R5 are reused from an immutable raw R5 proof. */
tasks.register<JavaExec>("generateSkitterCharacterR6Audition") {
    group = "distribution"
    description = "Render the compact R4/R5/R6 comparison. Requires -PskitterR5Baseline=<raw R5 proof directory>."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SkitterCharacterR6Audition")
    workingDir = projectDir
    args("${rootDir}/testkit/skitter-character-r6")
    if (project.hasProperty("skitterR5Baseline")) args("--baseline=${project.property("skitterR5Baseline")}")
    if (project.hasProperty("skitterProofSource")) args("--source-commit=${project.property("skitterProofSource")}")
}

/** Compact R7 quartet comparisons plus the two unchanged, accepted R6 references. */
tasks.register<JavaExec>("generateSkitterQuartetAudition") {
    group = "distribution"
    description = "Render four R6/R7 voice pairs. Requires -PskitterR6References=<raw frozen R6 proof directory> and -PskitterProofSource=<Git SHA>."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SkitterQuartetAudition")
    workingDir = projectDir
    args("${rootDir}/testkit/skitter-quartet")
    if (project.hasProperty("skitterR6References")) args("--references=${project.property("skitterR6References")}")
    if (project.hasProperty("skitterProofSource")) args("--source-commit=${project.property("skitterProofSource")}")
    if (project.hasProperty("skitterArchiveUrl")) args("--archive-url=${project.property("skitterArchiveUrl")}")
}

/** Six R7/R8 comparisons with complete finite notes and explicit source provenance. */
tasks.register<JavaExec>("generateSkitterDepthAudition") {
    group = "distribution"
    description = "Render six R7/R8 voice pairs. Requires -PskitterProofSource=<full Git SHA>."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SkitterDepthAudition")
    workingDir = projectDir
    args("${rootDir}/testkit/skitter-depth")
    if (project.hasProperty("skitterProofSource")) args("--source-commit=${project.property("skitterProofSource")}")
    if (project.hasProperty("skitterArchiveUrl")) args("--archive-url=${project.property("skitterArchiveUrl")}")
}

/** Focused BASS R8/R9 proof across C1/C2/C3, preserving normal depth processing. */
tasks.register<JavaExec>("generateSkitterBassAudition") {
    group = "distribution"
    description = "Render BASS register pairs. Requires -PskitterProofSource=<full SHA> and -PskitterBassBaseline=<sealed R8 snapshot>."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.SkitterBassAudition")
    workingDir = projectDir
    args("${rootDir}/testkit/skitter-bass")
    if (project.hasProperty("skitterProofSource")) args("--source-commit=${project.property("skitterProofSource")}")
    if (project.hasProperty("skitterBassBaseline")) args("--baseline=${project.property("skitterBassBaseline")}")
    if (project.hasProperty("skitterArchiveUrl")) args("--archive-url=${project.property("skitterArchiveUrl")}")
}

/** Render Relay's provisional dry identities, control matrix and actual repeated held loops. */
tasks.register<JavaExec>("generateRelayAudition") {
    group = "distribution"
    description = "Render RELAY native/matched WAVs, scheduling evidence, metrics and a listening page under testkit/relay-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RelayAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/relay-audition")
    if (project.hasProperty("relayQuick")) args("--quick")
    if (project.hasProperty("relayBaseline")) args("--baseline=${project.property("relayBaseline")}")
}

/** ONSET's dry four-voice experiment, preserved accepted sounds and compact MPC acceptance kit. */
tasks.register<JavaExec>("generateOnsetAudition") {
    group = "distribution"
    description = "Render 20 ONSET raw/matched hits, 3 four-voice beats, first-pass kick references, listening page and 16-pad MPC sample kit under testkit/onset-audition/."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OnsetAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/onset-audition")
    providers.gradleProperty("onsetReferenceDir").orNull?.let { args("--reference-dir=$it") }
}

/** CREASE's reproducible dry prototype matrix and standalone audition assets. */
tasks.register<JavaExec>("generateCreaseAudition") {
    group = "distribution"
    description = "Render CREASE revision 3's 87-clip gesture/preset listening set under testkit/crease-audition-r3/. -PcreaseFull adds the complete engineering matrix."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CreaseAuditionGenerator")
    workingDir = projectDir
    args(project.findProperty("creaseOutput")?.toString() ?: "${rootDir}/testkit/crease-audition-r3")
    project.findProperty("creaseRecipe")?.toString()?.let { args("--recipe=$it") }
    if (project.hasProperty("creaseFull")) args("--full")
}

/** Preserve model 2's voice comparison and original model 1 fixtures. */
tasks.register<JavaExec>("generateShoalAudition") {
    group = "distribution"
    description = "Render SHOAL dry raw/matched24-bit WAVs, recipes, state traces, measurements and a listening page under testkit/shoal-audition/."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ShoalAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("shoalOutput").orNull ?: "${rootDir}/testkit/shoal-audition")
    if (project.hasProperty("shoalRefreshPage")) args("--refresh-page")
}

/** OPENSKY's real stereo engine listening spike and reproducible comparison WAVs. */
tasks.register<JavaExec>("generateOpenSkyAudition") {
    group = "distribution"
    description = "Render OPENSKY's eight presets, dry/wet pairs, registers, musical examples, macro sweeps and held HALO loops under testkit/opensky-audition/."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OpenSkyAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/opensky-audition")
}

/** Experimental, single-source BRASS GROW register/velocity/loop validation pack. */
tasks.register<JavaExec>("generateBrassGrowPrototype") {
    group = "distribution"
    description = "Render the single-source BRASS GROW prototype matrix and checks under testkit/brass-grow-prototype/."
    maxHeapSize = "1024m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.BrassGrowPrototypeGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/brass-grow-prototype")
}

/** A finite scanned-chain experiment: frozen versus moving physics, with saved source shapes. */
tasks.register<JavaExec>("generateScannedChainAudition") {
    group = "distribution"
    description = "Render the experimental scanned-chain frozen/moving comparisons, source previews, manifest and listening page."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ScannedChainAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/scanned-chain-audition")
}

/** Isolated controls on the unchanged scanned-chain listening core. */
tasks.register<JavaExec>("generateScannedChainSweeps") {
    group = "distribution"
    description = "Render the scanned-chain parameter sweeps, shared-level comparisons and auditable sweep manifest."
    maxHeapSize = "512m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ScannedChainSweepGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/scanned-chain-audition")
}

/** Production model 3 materials at three native velocities, with matched comparisons. */
tasks.register<JavaExec>("generateShoalMaterialAudition") {
    group = "distribution"
    description = "Render SHOAL material/motion/root/velocity comparisons with ordinary replayable recipes."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ShoalMaterialAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("shoalMaterialOutput").orNull ?: "${rootDir}/testkit/shoal-playable-audition/playable")
}

/** Real VENT starter kit, explicit dry/effects pairs and two replayable MPC sampler grooves. */
tasks.register<JavaExec>("generateVentKitAudition") {
    group = "distribution"
    description = "Render VENT's twelve playable pads, two dry/effects grooves, ordinary recipes and native MPC kit/session exports."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.VentKitAuditionGenerator")
    workingDir = projectDir
    args(providers.gradleProperty("ventKitOutput").orNull ?: "${rootDir}/testkit/vent-kit-audition")
}

/** Musical preset, touch and starter-pad audition for Relay's playability pass. */
tasks.register<JavaExec>("generateRelayPerformanceAudition") {
    group = "distribution"
    description = "Render RELAY's curated bank, musical phrases, velocity examples and sixteen starter pads."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.RelayPerformanceAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/relay-performance-audition")
    if (project.hasProperty("relayPerformanceQuick")) args("--quick")
}

/** OVEN's reproducible first dry listening gate, including actual velocity layers. */
tasks.register<JavaExec>("generateOvenAudition") {
    group = "distribution"
    description = "Render OVEN dry raw/matched 16-bit WAVs, exact recipes, keyboard layers, manifest and numerical diagnostics under testkit/oven-audition/."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OvenAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/oven-audition")
}

/** A dry sixteen-pad starter with native and compatibility MPC exports. */
tasks.register<JavaExec>("generateOvenKit") {
    group = "distribution"
    description = "Render OVEN's regenerable sixteen-pad MPC starter under testkit/oven-kit/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.OvenKitGenerator")
    workingDir = projectDir
    maxHeapSize = "768m"
    args("${rootDir}/testkit/oven-kit")
}

/** The first static circular SOURCE/FOCUS listening experiment, with portable recipes. */
tasks.register<JavaExec>("generateCausticAudition") {
    group = "distribution"
    description = "Render CAUSTIC static native/matched WAVs, exact prototype recipes, diagnostics and a portable listening pack."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CausticAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "768m"
    args(providers.gradleProperty("causticOutput").orNull ?: "${rootDir}/testkit/caustic-audition")
}

/** One saved VENT orbit with an optional amplitude envelope for listening comparison. */
tasks.register<JavaExec>("generateVentKnuckleAudition") {
    group = "distribution"
    description = "Render the original and optional decay Pulse Knuckle hits, identical rhythms, recipes and measurements."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.VentKnuckleAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "768m"
    args(providers.gradleProperty("ventKnuckleOutput").orNull ?: "${rootDir}/testkit/vent-knuckle-audition")
}

/** Three model-1 clips, preserving the foreground and comparing only venue return. */
tasks.register<JavaExec>("generateNacreGentleAudition") {
    group = "distribution"
    description = "Render the original model-1 gesture and two restrained venue-return comparisons."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.NacreGentleAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/nacre-gentle-audition")
}

/** The finite production voice, kept separate from the archived circular studies. */
tasks.register<JavaExec>("generateCausticInstrumentAudition") {
    group = "distribution"
    description = "Render CAUSTIC's editable Alloy voice, control sweeps and native patch recipes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CausticInstrumentAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("causticInstrumentOutput").orNull ?: "${rootDir}/testkit/caustic-instrument-audition")
}

/** Four permanent native voices, kept separate from the preserved listening studies. */
tasks.register<JavaExec>("generateCausticCharactersAudition") {
    group = "distribution"
    description = "Render CAUSTIC's four native voices, focused control comparisons and portable patch/WAV packs."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CausticCharactersAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("causticCharactersOutput").orNull ?: "${rootDir}/testkit/caustic-native-voices-audition")
}

tasks.register<JavaExec>("generateCausticKit") {
    group = "distribution"
    description = "Export CAUSTIC's sixteen dry finite pads, editable recipes and both MPC program formats."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.CausticKitGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("causticKitOutput").orNull ?: "${rootDir}/testkit/caustic-kit")
}

/** SPARK revision-three control comparisons and ordered expression families. */
tasks.register<JavaExec>("generateThumpSparkAudition") {
    group = "distribution"
    description = "Render revised SPARK control checks, optional preserved-baseline A/Bs, expression families, recipes and a sixteen-pad XPM kit."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ThumpSparkAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "512m"
    args(providers.gradleProperty("sparkOutput").orNull ?: "${rootDir}/testkit/thump-spark-audition")
    providers.gradleProperty("sparkBaseline").orNull?.let { args(it) }
}

/** Local recipe neighborhoods on the unchanged, heard SPARK revision-three source. */
tasks.register<JavaExec>("generateThumpSparkSweetSpots") {
    group = "distribution"
    description = "Render six SPARK starting points and nearby one-control variants, with editable recipes and matched comparisons."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ThumpSparkSweetSpotAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "512m"
    args(providers.gradleProperty("sparkSweetSpotOutput").orNull ?: "${rootDir}/testkit/thump-spark-sweet-spots")
    providers.gradleProperty("sparkSweetSpotBaseline").orNull?.let { args(it) }
}

/** Retained model-one/model-two Apsis listening history. */
tasks.register<JavaExec>("generateApsisAudition") {
    group = "distribution"
    description = "Reproduce the retained APSIS model-one/model-two comparison set and orbital evidence."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ApsisAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("apsisOutput").orNull ?: "${rootDir}/testkit/apsis-audition")
    if (providers.gradleProperty("apsisQuick").isPresent) args("--quick")
}

/** Native sixteen-pad bank, three-zone playing samples, and genuine held-loop metadata. */
tasks.register<JavaExec>("generateApsisKit") {
    group = "distribution"
    description = "Export APSIS MPC 3/MPC 2 programs, exact dry PCM24 samples, recipes and verified pad map."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ApsisKitGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("apsisMusicalOutput").orNull ?: "${rootDir}/testkit/apsis-musical")
}

/** Musical evidence replays the actual exported samples, including zones and pad gates. */
tasks.register<JavaExec>("generateApsisPlayingEvidence") {
    group = "distribution"
    description = "Render model-three velocity and material-exchange comparisons at shared gain."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ApsisAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    val bankOutput = providers.gradleProperty("apsisMusicalOutput").orNull ?: "${rootDir}/testkit/apsis-musical"
    args("$bankOutput/performance", "--performance")
}

/** Musical evidence replays the actual exported samples, including zones and pad gates. */
tasks.register<JavaExec>("generateApsisMusicalAudition") {
    group = "distribution"
    description = "Render APSIS bass, keys, chord answers, gesture pairs, held fields and layer comparisons from the exported bank."
    dependsOn("generateApsisKit", "generateApsisPlayingEvidence")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ApsisMusicalAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "1g"
    val bankOutput = providers.gradleProperty("apsisMusicalOutput").orNull ?: "${rootDir}/testkit/apsis-musical"
    args(bankOutput, "$bankOutput/SnipSnap Apsis Kit")
}

/** Actual editor/source selections, finite movement and independently verified repeating HOLD loops. */
tasks.register<JavaExec>("generatePrismMotionStudioAudition") {
    group = "distribution"
    description = "Render PRISM motion editor and source comparisons with native/matched audio and saved recipes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PrismMotionStudioAuditionGenerator")
    workingDir = projectDir
    maxHeapSize = "768m"
    args("${rootDir}/testkit/prism-motion-studio-audition")
}

/** Held TIDAL listening evidence; the accepted finite audition remains separate. */
tasks.register<JavaExec>("generateTidalHoldAudition") {
    group = "distribution"
    description = "Render TIDAL encounter-to-hold notes, eight-cycle loops, recipes and closure measurements."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TidalHoldAuditionGenerator")
    maxHeapSize = "768m"
    workingDir = rootProject.projectDir
    args(providers.gradleProperty("tidalHoldOutput").orNull ?: "${rootDir}/testkit/tidal-hold-audition")
}

/** Original harp with two experimental timed venue returns and isolated-space previews. */
tasks.register<JavaExec>("generateNacrePresenceAudition") {
    group = "distribution"
    description = "Render the model-1 reference, coral echoes, moving seaweed and space-only comparisons."
    maxHeapSize = "768m"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.NacrePresenceAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/nacre-presence-audition")
}

/** Ordinary saved patches across registers, strike strengths, controls and a short phrase. */
tasks.register<JavaExec>("generateNacrePlayableAudition") {
    group = "distribution"
    description = "Render NACRE playable presets, register/velocity matrix, control sweeps and overlap phrases."
    maxHeapSize = "1g"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.NacrePlayableAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/nacre-playable-audition")
}
