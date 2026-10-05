plugins {
    kotlin("jvm")
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

tasks.register<JavaExec>("generateMercuryKit") {
    group = "distribution"
    description = "Render the MERCURY modal-glass acceptance kit under testkit/."
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

/** Render the VALVE V1 listening clips and manifest under testkit/valve-audition/. See ValveAuditionGenerator. */
tasks.register<JavaExec>("generateValveAudition") {
    group = "distribution"
    description = "Render the VALVE V1 listening clips and manifest under testkit/valve-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.ValveAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/valve-audition")
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

/** Render MURK's dry voices, causal probes and accessible offline listening page. */
tasks.register<JavaExec>("generateMurkAudition") {
    group = "distribution"
    description = "Render the MURK audition WAVs, evidence manifest and accessible page under testkit/murk-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.MurkAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/murk-audition")
}
