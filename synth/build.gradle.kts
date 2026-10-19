plugins {
    kotlin("jvm")
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

/** Three-voice WICK milestone: dry audio, musical phrases and real held-loop evidence. */
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

/** Stoop's dry pack; -PstoopQuick selects smoke, -PstoopBaselineDir adds original factory references. */
tasks.register<JavaExec>("generateStoopAudition") {
    group = "distribution"
    description = "Render STOOP presets, phrases, macro comparisons and held loops under testkit/stoop-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.StoopAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/stoop-audition")
    providers.gradleProperty("stoopBaselineDir").orNull?.let { args("--baseline-dir=$it") }
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
    description = "Render the 16 matched Tremor listening-repair clips and metadata."
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

/** Render the pedal board's Stage 0 listening clips and manifest under testkit/pedal-audition/. See PedalAuditionGenerator. */
tasks.register<JavaExec>("generatePedalAudition") {
    group = "distribution"
    description = "Render the pedal board's Stage 0 listening clips and manifest under testkit/pedal-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PedalAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pedal-audition")
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
    description = "Render the sixteen matched FLOTILLA listening-fix clips and metadata."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.FlotillaComparisonGenerator")
    workingDir = projectDir
    val version = project.findProperty("flotillaComparisonVersion") as String? ?: "candidate"
    args("${rootDir}/testkit/flotilla-comparison/$version")
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

/** Render Shoal's first two voices with causal comparisons and moving held loops. */
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
