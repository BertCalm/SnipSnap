package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dry Pitchwheel listening evidence: native and shared-level WAVs, event/energy traces and a page
 * that opens directly from disk. The compact roster covers each voice, pitch, velocity, representative
 * macro contrasts, mechanism removal and settled HOLD. `--full` adds five-step sweeps, interaction
 * grids, extreme corners and a solver-rate comparison. Recipes await owner listening approval.
 *
 * Run `./gradlew :synth:generatePitchwheelAudition`; files belong under testkit/pitchwheel-audition/.
 */
object PitchwheelAuditionGenerator {

    private val TIMBRE = listOf("PUSH", "TOOTH", "ADHESION", "HEAT", "BODY")
    private val MACRO_VOICES = linkedMapOf("PUSH" to PitchwheelVoice.TURN, "TOOTH" to PitchwheelVoice.PLUCK,
        "ADHESION" to PitchwheelVoice.RECOIL, "HEAT" to PitchwheelVoice.THAWED, "BODY" to PitchwheelVoice.CLUNK)

    private data class Case(
        val id: String,
        val section: String,
        val voice: PitchwheelVoice,
        val label: String,
        val description: String,
        val macros: Map<String, Float> = emptyMap(),
        val midi: Int = Pitchwheel.DEFAULT_MIDI,
        val velocity: Float = 1f,
        val seconds: Float? = null,
        val contacts: Boolean = true,
        val resin: Boolean = true,
        val bowSound: Boolean = true,
        val snapSound: Boolean = true,
        val solverRate: Int = Dsp.RATE * 4,
        val modelVersion: Int = Pitchwheel.MODEL_VERSION,
        val isolatedKind: Pitchwheel.EventKind? = null,
        val direction: Int = 1,
        val mechanismFocus: String? = null,
        val comparisonGroup: String? = null,
    )

    private data class Metrics(
        val peak: Double,
        val rms: Double,
        val dc: Double,
        val loudness: Float,
        val wrapStep: Double?,
        val wrapSlope: Double?,
    ) {
        fun json() = "{\"peak\":$peak,\"rms\":$rms,\"dc\":$dc,\"loudness\":$loudness," +
            "\"wrapStep\":$wrapStep,\"wrapSlope\":$wrapSlope}"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/pitchwheel-audition")
        val full = "--full" in args
        val onlyVoice = args.firstOrNull { it.startsWith("--voice=") }?.substringAfter('=')
            ?.uppercase(Locale.ROOT)?.let(PitchwheelVoice::valueOf)
        val sections = args.firstOrNull { it.startsWith("--sections=") }?.substringAfter('=')
            ?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.toSet()
        val cases = (if (full) fullCases() else compactCases()).filter {
            (onlyVoice == null || it.voice == onlyVoice) &&
                (sections == null || it.section.lowercase(Locale.ROOT) in sections)
        }
        require(cases.isNotEmpty()) { "No Pitchwheel cases match the supplied filters" }
        check(cases.map { it.id }.distinct().size == cases.size) { "Duplicate Pitchwheel audition IDs" }
        check(root.isDirectory || root.mkdirs()) { "Could not create ${root.absolutePath}" }
        val sourceFiles = mapOf(Pitchwheel.MODEL_VERSION to sourceFile("Pitchwheel.kt"),
            PitchwheelV2.MODEL_VERSION to sourceFile("PitchwheelV2.kt"),
            PitchwheelV1.MODEL_VERSION to sourceFile("PitchwheelV1.kt"))
        val sourceHashes = sourceFiles.mapValues { sha256(it.value.readBytes()) }
        val compiledHashes = mapOf(Pitchwheel.MODEL_VERSION to compiledHash("Pitchwheel"),
            PitchwheelV2.MODEL_VERSION to compiledHash("PitchwheelV2"),
            PitchwheelV1.MODEL_VERSION to compiledHash("PitchwheelV1"))
        val sourceHash = sourceHashes.getValue(Pitchwheel.MODEL_VERSION)
        val compiledHash = compiledHashes.getValue(Pitchwheel.MODEL_VERSION)
        val recipeHash = sha256(cases.joinToString("\n") { case ->
            val macros = caseMacros(case).toSortedMap()
            "${case.id}|${case.voice}|${macroJson(macros)}|${case.midi}|${case.velocity}|${case.seconds}|" +
                "${case.contacts}|${case.resin}|${case.bowSound}|${case.snapSound}|${case.solverRate}|" +
                "${case.modelVersion}|${case.isolatedKind}|${case.direction}|" +
                "${case.mechanismFocus}|${case.comparisonGroup}"
        }.toByteArray(Charsets.UTF_8))
        val listeningRound = "pitchwheel-v${Pitchwheel.MODEL_VERSION}-${sourceHash.take(12)}-${recipeHash.take(12)}"

        val clips = ArrayList<String>(cases.size)
        val started = System.nanoTime()
        for ((index, case) in cases.withIndex()) {
            checkSourceHashes(sourceFiles, sourceHashes)
            val macros = caseMacros(case)
            val held = Pitchwheel.isLoop(macros.getValue("HOLD"))
            val loop = held && case.seconds == null && case.isolatedKind == null
            val before = System.nanoTime()
            val probe = when {
                case.isolatedKind != null -> null
                case.modelVersion == PitchwheelV1.MODEL_VERSION -> legacyProbe(PitchwheelV1.renderProbe(
                    case.voice, macros, case.midi, case.velocity,
                    seconds = case.seconds, normalize = false, contacts = case.contacts,
                    resin = case.resin, bowSound = case.bowSound, snapSound = case.snapSound,
                    solverRate = case.solverRate,
                ))
                case.modelVersion == PitchwheelV2.MODEL_VERSION -> legacyProbe(PitchwheelV2.renderProbe(
                    case.voice, macros, case.midi, case.velocity,
                    seconds = case.seconds, normalize = false, contacts = case.contacts,
                    resin = case.resin, bowSound = case.bowSound, snapSound = case.snapSound,
                    solverRate = case.solverRate,
                ))
                else -> Pitchwheel.renderProbe(
                    case.voice, macros, case.midi, case.velocity,
                    seconds = case.seconds, normalize = false, contacts = case.contacts,
                    resin = case.resin, bowSound = case.bowSound, snapSound = case.snapSound,
                    solverRate = case.solverRate,
                )
            }
            val raw = probe?.samples ?: when (case.modelVersion) {
                PitchwheelV2.MODEL_VERSION -> PitchwheelV2.acousticProbe(case.voice, macros, case.midi,
                    kind = PitchwheelV2.EventKind.valueOf(case.isolatedKind!!.name),
                    direction = case.direction, seconds = case.seconds!!.toDouble())
                else -> Pitchwheel.acousticProbe(case.voice, macros, case.midi,
                    kind = case.isolatedKind!!, direction = case.direction, seconds = case.seconds!!.toDouble())
            }
            val renderMs = (System.nanoTime() - before) / 1_000_000.0
            require(raw.isNotEmpty() && raw.all { it.isFinite() }) { "Empty or non-finite clip: ${case.id}" }
            val native = Snip(raw, channels = 1, sampleRate = Dsp.RATE)
            val rawMetrics = metrics(native, loop)
            // PCM cannot represent an over-range source. Preserve its measured level in the manifest
            // and record any export attenuation instead of concealing it with source normalization.
            val exportGain = if (rawMetrics.peak > .99) (.99 / rawMetrics.peak).toFloat() else 1f
            val rawSnip = if (exportGain == 1f) native else
                Snip(FloatArray(raw.size) { raw[it] * exportGain }, channels = 1, sampleRate = Dsp.RATE)
            val matched = AuditionLevel.level(native)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val eventsPath = "diagnostics/${case.id}_events.csv"
            val tracePath = "diagnostics/${case.id}_trace.csv"
            WavWriter.write(File(root, rawPath), rawSnip, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            writeDiagnostics(root, eventsPath, tracePath, probe)
            val d = probe?.diagnostics
            val counts = d?.events?.groupingBy { it.kind.name }?.eachCount().orEmpty()
            val temperature = d?.trace?.takeIf { it.isNotEmpty() }?.let { trace ->
                "{\"initial\":${n(trace.first().temperature)},\"final\":${n(trace.last().temperature)}," +
                    "\"min\":${n(trace.minOf { it.temperature })},\"max\":${n(trace.maxOf { it.temperature })}}"
            } ?: "null"
            val encounters = if (case.mechanismFocus == "recoil") d?.events.orEmpty()
                .filter { it.kind == Pitchwheel.EventKind.RELEASE }.joinToString(",", "[", "]") {
                    "{\"time\":${n(it.time)},\"tooth\":${it.tooth},\"direction\":${it.direction},\"energy\":${n(it.energy)}}"
                } else "null"
            val diagnostics = if (d == null) "null" else "{\"initialEnergy\":${n(d.initialEnergy)},\"inputWork\":${n(d.inputWork)}," +
                "\"dissipated\":${n(d.dissipated)},\"passiveCorrection\":${n(d.passiveCorrection)}," +
                "\"maxEnergyError\":${n(d.maxEnergyError)},\"finalEnergy\":${n(d.finalEnergy)}," +
                "\"releasedEnergy\":${n(d.releasedEnergy)},\"bowedEnergy\":${n(d.bowedEnergy)}," +
                "\"snappedEnergy\":${n(d.snappedEnergy)},\"maxAttachments\":${d.maxAttachments}," +
                "\"eventCounts\":${counts.entries.joinToString(",", "{", "}") { "${q(it.key)}:${it.value}" }}," +
                "\"forwardReleases\":${d.events.count { it.kind.name == "RELEASE" && it.direction > 0 }}," +
                "\"reverseReleases\":${d.events.count { it.kind.name == "RELEASE" && it.direction < 0 }}," +
                "\"releaseEncounters\":$encounters,\"temperature\":$temperature," +
                "\"traceFrames\":${d.trace.size}}"
            clips += "{\"id\":${q(case.id)},\"section\":${q(case.section)},\"voice\":${q(case.voice.name)}," +
                "\"label\":${q(case.label)},\"description\":${q(case.description)}," +
                "\"modelVersion\":${case.modelVersion},\"dspSourceSha256\":${q(sourceHashes.getValue(case.modelVersion))}," +
                "\"compiledClassSha256\":${q(compiledHashes.getValue(case.modelVersion))}," +
                "\"isolatedKind\":${case.isolatedKind?.name?.let(::q) ?: "null"},\"direction\":${case.direction}," +
                "\"mechanismFocus\":${case.mechanismFocus?.let(::q) ?: "null"}," +
                "\"comparisonGroup\":${case.comparisonGroup?.let(::q) ?: "null"}," +
                "\"recipeStatus\":\"provisional; owner listening pending\",\"macros\":${macroJson(macros)}," +
                "\"midi\":${case.midi},\"requestedMidi\":${Pitchwheel.midiFor(case.voice, macros.getValue("TUNE"), case.midi)}," +
                "\"requestedHz\":${Pitchwheel.frequencyFor(case.voice, macros.getValue("TUNE"), case.midi)}," +
                "\"velocity\":${case.velocity},\"held\":$held,\"loop\":$loop,\"requestedSeconds\":${case.seconds}," +
                "\"probe\":{\"mode\":${q(if (case.isolatedKind == null) "moving wheel" else "release-only resonator")}," +
                "\"contacts\":${case.contacts},\"resin\":${case.resin},\"bowSound\":${case.bowSound}," +
                "\"snapSound\":${case.snapSound},\"solverRate\":${case.solverRate}}," +
                "\"frames\":${raw.size},\"seconds\":${raw.size.toDouble() / Dsp.RATE}," +
                "\"renderMs\":$renderMs,\"renderMsPerAudioSecond\":${renderMs / (raw.size.toDouble() / Dsp.RATE)}," +
                "\"rawPath\":${q(rawPath)},\"matchedPath\":${q(matchedPath)},\"eventsPath\":${q(eventsPath)}," +
                "\"tracePath\":${q(tracePath)},\"rawExportGain\":$exportGain," +
                "\"seamError\":${if (loop) n(probe!!.seamError) else "null"}," +
                "\"stateError\":${if (loop) n(probe!!.stateError) else "null"},\"prerollCycles\":${probe?.prerollCycles ?: 0}," +
                "\"previousCycleFrames\":${probe?.previousCycle?.size ?: 0},\"diagnostics\":$diagnostics," +
                "\"rawMetrics\":${rawMetrics.json()},\"matchedMetrics\":${metrics(matched, loop).json()}}"
            if ((index + 1) % 10 == 0 || index == cases.lastIndex) {
                println("Pitchwheel audition: ${index + 1}/${cases.size} cases")
            }
        }

        checkSourceHashes(sourceFiles, sourceHashes)
        val modelProvenance = sourceHashes.keys.joinToString(",", "{", "}") { model ->
            "${q(model.toString())}:{\"dspSourceSha256\":${q(sourceHashes.getValue(model))}," +
                "\"compiledClassSha256\":${q(compiledHashes.getValue(model))}}"
        }
        val manifest = "{\"engine\":\"PITCHWHEEL\",\"modelVersion\":${Pitchwheel.MODEL_VERSION}," +
            "\"dspSourceSha256\":${q(sourceHash)},\"compiledClassSha256\":${q(compiledHash)}," +
            "\"modelProvenance\":$modelProvenance," +
            "\"recipeSha256\":${q(recipeHash)},\"listeningRound\":${q(listeningRound)}," +
            "\"sampleRate\":${Dsp.RATE},\"bitDepth\":24,\"full\":$full," +
            "\"sonicAcceptance\":\"pending owner listening; recipes and presets are provisional\"," +
            "\"previousFeedback\":${q(PREVIOUS_FEEDBACK)}," +
            "\"metricNotes\":${q(METRIC_NOTES)},\"clips\":[\n${clips.joinToString(",\n")}\n]}\n"
        prunePreviousClips(root, cases)
        File(root, "manifest.json").writeText(manifest)
        File(root, "index.html").writeText(PAGE.replace("__MANIFEST__", manifest.replace("</", "<\\/")))
        File(root, "README.md").writeText(readme(cases.size, full, listeningRound))
        println("Wrote ${cases.size * 2} WAVs, ${cases.size * 2} CSVs, manifest.json, index.html and README.md " +
            "under ${root.absolutePath} in %.1f s".format(Locale.ROOT, (System.nanoTime() - started) / 1e9))
    }

    private fun compactCases(): List<Case> = buildList {
        for (voice in PitchwheelVoice.entries) {
            add(case("Isolated attacks", voice, "isolated_release", "${voice.name} · release-only hit",
                "One equal-work release into this voice's resonator at C3. No wheel, resin bowing, recoil or warming is exercised. Compare matched clips for body colour and decay; use Real mechanisms for the moving instrument.")
                .copy(seconds = .75f, isolatedKind = Pitchwheel.EventKind.RELEASE,
                    comparisonGroup = "${voice.name.lowercase(Locale.ROOT)}_release_character"))
        }
        for (voice in PitchwheelVoice.entries) {
            add(case("Previous model 2 hits", voice, "previous_v2_release", "${voice.name} · previous model 2 release-only hit",
                "The preserved 0.75-second, equal-work model-2 resonator hit from the preceding audition. No moving-wheel mechanisms are exercised. Compare matched audio directly with the current release-only hit.")
                .copy(seconds = .75f, modelVersion = PitchwheelV2.MODEL_VERSION,
                    isolatedKind = Pitchwheel.EventKind.RELEASE,
                    comparisonGroup = "${voice.name.lowercase(Locale.ROOT)}_release_character"))
        }
        addAll(realMechanismCases())
        for (voice in PitchwheelVoice.entries) {
            add(case("Defaults", voice, "default", "${voice.name} default", "All controls at this voice's provisional defaults. Dry mechanism, with no rack effects."))
            add(case("Previous model 2", voice, "previous_v2_default", "${voice.name} · previous model 2",
                "Preserved model-2 default from the preceding listening round. Compare at matched level and the same C3 root; the frozen renderer retains the previous sound.")
                .copy(modelVersion = PitchwheelV2.MODEL_VERSION))
            add(case("Previous model 1", voice, "previous_default", "${voice.name} · previous model 1",
                "Preserved model-1 default from the first listening round. Compare at matched level and the same C3 root; the frozen renderer retains the original sound.")
                .copy(modelVersion = PitchwheelV1.MODEL_VERSION))
            for (midi in listOf(36, 60)) add(case("Pitch and velocity", voice, "midi_$midi", "${voice.name} · ${noteName(midi)}",
                "Same gesture and velocity; only the requested root changes.").copy(midi = midi))
            for (velocity in listOf(.3f, .65f)) add(case("Pitch and velocity", voice, "velocity_${tag(velocity)}",
                "${voice.name} · velocity ${fmt(velocity)}", "Velocity scales the initial gesture energy within the PUSH character.").copy(velocity = velocity))
            add(case("Held loops", voice, "held", "${voice.name} held", "Settled loop-only export. Enable repeat and listen through several revolutions.", mapOf("HOLD" to 1f)))
        }
        for ((macro, voice) in MACRO_VOICES) {
            for (value in listOf(0f, 1f)) add(case("Macro contrasts", voice, "contrast_${macro.lowercase()}_${tag(value)}",
                "${voice.name} · $macro ${fmt(value)}", macroDescription(macro), mapOf(macro to value)))
        }
        add(case("Mechanism isolation", PitchwheelVoice.PLUCK, "contacts_off", "PLUCK · contacts disabled",
            "Compare PLUCK default. Remove tooth/finger contacts; resin mechanics remain.").copy(contacts = false))
        add(case("Mechanism isolation", PitchwheelVoice.DRAW, "bow_off", "DRAW · bow sound disabled",
            "Compare DRAW default. Filament resistance remains; only its continuous acoustic transfer is disabled.").copy(bowSound = false))
        add(case("Mechanism isolation", PitchwheelVoice.DRAW, "resin_off", "DRAW · resin disabled",
            "Compare DRAW default. Remove filaments, their resistance, bowing and snaps together.").copy(resin = false))
        add(case("Mechanism isolation", PitchwheelVoice.RECOIL, "snap_off", "RECOIL · snap sound disabled",
            "Compare RECOIL default. Attachments and mechanical releases remain; snap acoustic transfer is disabled.").copy(snapSound = false))
        add(case("Edges", PitchwheelVoice.PLUCK, "velocity_zero", "PLUCK · zero velocity", "Exact silence control; matched export must also remain silent.").copy(velocity = 0f))
    }

    /** These are complete moving-wheel probes, not isolated or reconstructed contact sounds. */
    private fun realMechanismCases(): List<Case> = buildList {
        val draw = mapOf("PUSH" to .7f, "ADHESION" to .8f, "HEAT" to .35f)
        add(case("Real mechanisms", PitchwheelVoice.DRAW, "mechanism_bow", "DRAW · funded resin bowing",
            "Three seconds of the actual moving wheel with sticky resin. Compare the bow-muted partner at the same controls; inspect bowed work and attachment events in the diagnostics.", draw)
            .copy(seconds = 3f, mechanismFocus = "bow", comparisonGroup = "draw_bow"))
        add(case("Real mechanisms", PitchwheelVoice.DRAW, "mechanism_bow_muted", "DRAW · bow transfer muted",
            "Same controls and duration as the funded-bowing probe. Resin resistance and contacts remain active; continuous resin-to-sound transfer is muted.", draw)
            .copy(seconds = 3f, bowSound = false, mechanismFocus = "bow", comparisonGroup = "draw_bow"))
        add(case("Real mechanisms", PitchwheelVoice.RECOIL, "mechanism_reverse", "RECOIL · real forward and reverse encounters",
            "Five seconds of the actual default gesture. Release metadata identifies each heard tooth, time and direction; listen for the wheel returning to a tooth from the reverse side.")
            .copy(seconds = 5f, mechanismFocus = "recoil"))
        val thawed = mapOf("PUSH" to .7f, "ADHESION" to .65f)
        for ((suffix, heat) in listOf("cold" to .05f, "warm" to .9f)) {
            add(case("Real mechanisms", PitchwheelVoice.THAWED, "mechanism_$suffix", "THAWED · $suffix resin",
                "Six seconds of the moving wheel, HEAT ${fmt(heat)}. The cold/warm pair shares every other control. Compare resistance and sound over time with the measured temperature range and State CSV.", thawed + ("HEAT" to heat))
                .copy(seconds = 6f, mechanismFocus = "thermal", comparisonGroup = "thawed_heat"))
        }
        add(case("Real mechanisms", PitchwheelVoice.TURN, "mechanism_hold", "TURN · settled powered orbit",
            "One settled revolution of the actual powered wheel. Enable Repeat held loops to hear several cycles; diagnostics include preroll and the measured cycle, and the asset is not a concatenated simulation.", mapOf("HOLD" to 1f))
            .copy(mechanismFocus = "hold"))
    }

    private fun fullCases(): List<Case> = buildList {
        addAll(compactCases().map { if (it.section == "Macro contrasts")
            it.copy(section = "${it.macros.keys.single()} sweep") else it })
        for (voice in PitchwheelVoice.entries) {
            for (macro in TIMBRE) {
                val values = if (voice == MACRO_VOICES.getValue(macro)) listOf(.25f, .5f, .75f) else listOf(0f, 1f)
                for (value in values) add(case("$macro sweep", voice, "sweep_${macro.lowercase()}_${tag(value)}",
                    "${voice.name} · $macro ${fmt(value)}", macroDescription(macro), mapOf(macro to value)))
            }
            add(case("HOLD threshold", voice, "hold_098", "${voice.name} · HOLD .98",
                "HOLD stays in finite mode below .99. This threshold probe should match the voice's default finite gesture; compare it with the settled held loop.", mapOf("HOLD" to .98f)))
            add(case("Edges", voice, "all_high", "${voice.name} · all timbral controls high",
                "Maximum PUSH, TOOTH, ADHESION, HEAT and BODY; listen for stable pitch and a mechanically plausible finite tail.", TIMBRE.associateWith { 1f }))
        }
        for (voice in listOf(PitchwheelVoice.PLUCK, PitchwheelVoice.DRAW, PitchwheelVoice.RECOIL)) {
            for (midi in listOf(Pitchwheel.MIDI_MIN, Pitchwheel.MIDI_MAX)) add(case("Register edges", voice, "register_$midi",
                "${voice.name} · ${noteName(midi)}", "Supported requested-note boundary, with default TUNE and timbre.").copy(midi = midi))
        }
        for (tune in listOf(0f, 1f)) add(case("Register edges", PitchwheelVoice.TURN, "tune_${tag(tune)}",
            "TURN · TUNE ${fmt(tune)}", "TUNE moves the requested root by up to an octave in either direction.", mapOf("TUNE" to tune)))
        for ((voice, a, b) in listOf(Triple(PitchwheelVoice.RECOIL, "PUSH", "ADHESION"), Triple(PitchwheelVoice.THAWED, "ADHESION", "HEAT"))) {
            for (va in listOf(0f, .5f, 1f)) for (vb in listOf(0f, .5f, 1f)) add(case("$a × $b", voice,
                "grid_${a.lowercase()}${tag(va)}_${b.lowercase()}${tag(vb)}", "${voice.name} · $a ${fmt(va)} · $b ${fmt(vb)}",
                "Compare the evolving resistance, encounter timing, strain and any reverse release at the same root.", mapOf(a to va, b to vb)))
        }
        for ((id, macros) in listOf("sticky_cold" to mapOf("ADHESION" to 1f, "HEAT" to 0f),
            "slow_sticky" to mapOf("PUSH" to 0f, "ADHESION" to 1f), "all_high" to TIMBRE.associateWith { 1f })) {
            add(case("Held edges", PitchwheelVoice.TURN, "held_$id", "TURN · held ${id.replace('_', ' ')}",
                "Difficult settled HOLD corner. Inspect stateError and prior-cycle availability as well as waveform seam; repeat for several wraps.", macros + ("HOLD" to 1f)))
        }
        add(case("Held mechanics", PitchwheelVoice.TURN, "held_startup", "TURN · powered startup",
            "Six-second continuous powered simulation, including its initial gesture. This diagnostic is not a loop export.", mapOf("HOLD" to 1f)).copy(seconds = 6f))
        add(case("Mechanism isolation", PitchwheelVoice.RECOIL, "resin_acoustic_off", "RECOIL · resin acoustics disabled",
            "Compare RECOIL default. Bow and snap sound are disabled together; resin resistance and recoil remain in the trace.")
            .copy(bowSound = false, snapSound = false))
        add(case("Solver comparison", PitchwheelVoice.RECOIL, "solver_88200", "RECOIL · 88.2 kHz mechanics",
            "Compare RECOIL default at 176.4 kHz. The resonators stay at 4×; check event counts and phrase behavior as the mechanics step changes.")
            .copy(solverRate = Dsp.RATE * 2))
    }

    private fun case(section: String, voice: PitchwheelVoice, suffix: String, label: String,
                     description: String, macros: Map<String, Float> = emptyMap()) =
        Case("${voice.name.lowercase(Locale.ROOT)}_$suffix", section, voice, label, description, macros)

    private fun macroDescription(macro: String) = when (macro) {
        "PUSH" -> "Few slow encounters to a strong dense gesture. Listen for timing change while the requested root stays recognizable."
        "TOOTH" -> "Rounded compliant catches to hard short releases. Compare attack shape at the same PUSH and root."
        "ADHESION" -> "Light drag to stronger stretch, strain and possible recoil. Check attachment events against bow and snap sounds."
        "HEAT" -> "Cold resistant resin to a warm easier start. Compare early resistance and how encounter timing develops during the gesture."
        else -> "Compact dry wood to broad hollow resonance. Compare body colour and decay while the principal tuning stays recognizable."
    }

    private fun sourceFile(name: String) = listOf(File("src/main/kotlin/com/snipsnap/synth/$name"),
        File("synth/src/main/kotlin/com/snipsnap/synth/$name")).firstOrNull { it.isFile }
        ?: error("Run the Pitchwheel generator from the repository root or synth directory so its DSP sources can be recorded")

    private fun compiledHash(name: String) = Pitchwheel::class.java
        .getResourceAsStream("/com/snipsnap/synth/$name.class")
        ?.use { sha256(it.readBytes()) } ?: error("Could not fingerprint compiled $name")

    private fun checkSourceHashes(files: Map<Int, File>, hashes: Map<Int, String>) {
        check(files.all { (model, file) -> sha256(file.readBytes()) == hashes.getValue(model) }) {
            "Pitchwheel source changed during rendering; regenerate the listening round"
        }
    }

    private fun caseMacros(case: Case) = when (case.modelVersion) {
        PitchwheelV1.MODEL_VERSION -> PitchwheelV1.defaults(case.voice)
        PitchwheelV2.MODEL_VERSION -> PitchwheelV2.defaults(case.voice)
        else -> Pitchwheel.defaults(case.voice)
    } + case.macros

    /** Adapt immutable model-1 diagnostic records; its samples are never re-rendered by model 2. */
    private fun legacyProbe(p: PitchwheelV1.Probe): Pitchwheel.Probe {
        val d = p.diagnostics
        val events = d.events.map { Pitchwheel.Event(it.time, Pitchwheel.EventKind.valueOf(it.kind.name),
            it.tooth, it.direction, it.energy, it.finger) }
        val trace = d.trace.map { Pitchwheel.Trace(it.time, it.angle, it.speed, it.temperature,
            it.mechanicalEnergy, it.acousticEnergy, it.inputWork, it.dissipated, it.attachments) }
        return Pitchwheel.Probe(p.samples, Pitchwheel.Diagnostics(events, trace, d.initialEnergy,
            d.inputWork, d.dissipated, d.maxEnergyError, d.finalEnergy, d.maxAttachments,
            d.passiveCorrection, d.bowedEnergy, d.snappedEnergy, d.releasedEnergy),
            p.previousCycle, p.seamError, p.stateError, p.prerollCycles)
    }

    /** Preserve model-2 sample data while translating only the diagnostic record types. */
    private fun legacyProbe(p: PitchwheelV2.Probe): Pitchwheel.Probe {
        val d = p.diagnostics
        val events = d.events.map { Pitchwheel.Event(it.time, Pitchwheel.EventKind.valueOf(it.kind.name),
            it.tooth, it.direction, it.energy, it.finger) }
        val trace = d.trace.map { Pitchwheel.Trace(it.time, it.angle, it.speed, it.temperature,
            it.mechanicalEnergy, it.acousticEnergy, it.inputWork, it.dissipated, it.attachments) }
        return Pitchwheel.Probe(p.samples, Pitchwheel.Diagnostics(events, trace, d.initialEnergy,
            d.inputWork, d.dissipated, d.maxEnergyError, d.finalEnergy, d.maxAttachments,
            d.passiveCorrection, d.bowedEnergy, d.snappedEnergy, d.releasedEnergy),
            p.previousCycle, p.seamError, p.stateError, p.prerollCycles)
    }

    private fun writeDiagnostics(root: File, eventsPath: String, tracePath: String, probe: Pitchwheel.Probe?) {
        val events = File(root, eventsPath)
        check(events.parentFile.isDirectory || events.parentFile.mkdirs()) { "Could not create diagnostics directory" }
        events.bufferedWriter().use { out ->
            out.appendLine("time,kind,tooth,direction,energy")
            for (e in probe?.diagnostics?.events.orEmpty()) out.appendLine("${e.time},${e.kind.name},${e.tooth},${e.direction},${e.energy}")
        }
        File(root, tracePath).bufferedWriter().use { out ->
            out.appendLine("time,angle,speed,temperature,mechanicalEnergy,acousticEnergy,inputWork,dissipated,attachments")
            for (t in probe?.diagnostics?.trace.orEmpty()) out.appendLine("${t.time},${t.angle},${t.speed},${t.temperature},${t.mechanicalEnergy},${t.acousticEnergy},${t.inputWork},${t.dissipated},${t.attachments}")
        }
    }

    /** Delete only obsolete artifacts explicitly recorded in this generator's previous manifest. */
    private fun prunePreviousClips(root: File, cases: List<Case>) {
        val previous = File(root, "manifest.json")
        if (!previous.isFile) return
        val clips = runCatching { Json.parse(previous.readText()).obj().getValue("clips").arr() }.getOrDefault(emptyList())
        val retained = cases.flatMap { listOf("raw/${it.id}.wav", "matched/${it.id}.wav",
            "diagnostics/${it.id}_events.csv", "diagnostics/${it.id}_trace.csv") }.toSet()
        val allowed = Regex("(?:(?:raw|matched)/[a-z0-9_]+\\.wav|diagnostics/[a-z0-9_]+_(?:events|trace)\\.csv)")
        for (clip in clips) for (field in listOf("rawPath", "matchedPath", "eventsPath", "tracePath")) {
            val path = runCatching { clip.obj()[field]?.str() }.getOrNull() ?: continue
            if (path in retained || !allowed.matches(path)) continue
            val old = File(root, path)
            if (old.canonicalPath.startsWith(root.canonicalPath + File.separator) && old.isFile) {
                check(old.delete()) { "Could not remove obsolete Pitchwheel artifact: $path" }
            }
        }
    }

    private fun metrics(snip: Snip, loop: Boolean): Metrics {
        val s = snip.samples
        var peak = 0.0
        var energy = 0.0
        var sum = 0.0
        for (x in s) { val v = x.toDouble(); peak = maxOf(peak, abs(v)); energy += v * v; sum += v }
        val step = if (loop && s.size >= 2) abs(s.first().toDouble() - s.last()) else null
        val slope = if (loop && s.size >= 3) abs((s[1] - s[0]).toDouble() - (s.last() - s[s.lastIndex - 1])) else null
        return Metrics(peak, sqrt(energy / s.size.coerceAtLeast(1)), sum / s.size.coerceAtLeast(1), Loudness.of(snip), step, slope)
    }

    private fun macroJson(macros: Map<String, Float>) = macros.entries.joinToString(",", "{", "}") { "${q(it.key)}:${it.value}" }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun n(value: Double) = if (value.isFinite()) value.toString() else "null"
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private const val PREVIOUS_FEEDBACK = "Previous round: DRAW, RECOIL and TURN sounded alike; CLUNK and THAWED formed a second pair. PLUCK was “most different,” with no Keep verdict. This round starts with fresh votes."

    private const val METRIC_NOTES = "All audio is dry, mono, 44.1 kHz. Isolated attacks and Previous model 2 hits are equal-work, release-only resonator hits, each 0.75 seconds long. Previous hits use the frozen model-2 acoustic renderer and retain the preceding audition's sound. They do not exercise wheel motion, resin bowing, recoil or warming; their diagnostics are intentionally null and their CSVs contain headers only. Real mechanisms use complete moving-wheel renderProbe simulations. Their release directions, temperature range and funded bowed work describe the recorded simulation, not the isolated hits. Previous model 2 and Previous model 1 defaults use their frozen renderers; every clip records its model and source/compiled-class fingerprints. Gesture clips disable normalization and retain the unfaded finite tail. Matched clips use the shared AuditionLevel target measured on the loudest 200 ms RMS window. rawExportGain records PCM safety attenuation only; rawMetrics describe the unattenuated floating-point source. HOLD clips with no requested duration contain one settled revolution; enable the native player's Repeat held loops to hear several revolutions. The asset is not a concatenated multi-cycle simulation. The continuous powered-startup diagnostic is not a loop. seamError is the engine's Keys seam measurement with its real previous cycle. stateError checks the mechanical, thermal and acoustic orbit independently of the audio seam. wrapStep includes normal waveform slope; wrapSlope compares endpoint slopes. Energy values and event excitation are internal simulation units, not audio RMS. passiveCorrection is numerical energy removal, separate from dissipated physical work. Trace CSVs sample state at 100 Hz; event CSVs preserve each event. Held diagnostics may include preroll, so their time origin need not equal the exported loop start. Temperature is the model's normalized thermal state, not a measured material temperature. Other null numeric values indicate an unavailable or non-finite measurement and require investigation. Render time includes simulation and final-rate processing, but excludes WAV/CSV encoding and depends on JIT/hardware. Metrics do not establish sonic acceptance."

    private fun readme(count: Int, full: Boolean, listeningRound: String) = """
        # Pitchwheel audition

        Generated by `./gradlew :synth:generatePitchwheelAudition` (${if (full) "full matrix" else "compact first listening roster"}).
        $count cases; ${count * 2} dry mono 24-bit WAVs and ${count * 2} diagnostics CSVs.
        All voice recipes and presets remain provisional pending owner listening approval.
        Listening round: `$listeningRound`. DSP source, compiled-class and recipe SHA-256 fingerprints
        are recorded in the manifest. Rendering refuses a source change during the run.

        Open `index.html` directly in a browser. The page embeds its manifest and uses no external
        assets. Start with six equal-work, release-only resonator hits at C3 and matched level. These
        do not exercise the moving wheel, resin bowing, recoil or warming. Each current hit card has
        a Play previous model 2 matched button for direct comparison with the preserved 0.75-second
        prior hit. Use Real mechanisms for
        DRAW's funded bow/muted pair, RECOIL's recorded forward/reverse encounters, THAWED's same-control
        cold/warm pair and TURN's settled powered orbit. Then compare Defaults with Previous model 2
        and Previous model 1, which preserve their original renderers at the same root. Compare raw clips
        for gesture level and matched clips for timbre. Continue with macro contrasts, pitch/velocity,
        mechanism isolation and held loops. Listen
        for push, catch, pitched release, stretching resin, hesitation and a real reverse encounter.
        Enable repeat and listen through several HOLD wraps; compare stateError with the audio seam.

        The compact roster is the default. Pass `--full` to the generator for five-step macro sweeps
        on representative voices, macro endpoints on all voices,
        PUSH × ADHESION and ADHESION × HEAT grids, extreme registers/corners and solver comparisons.
        `--voice=RECOIL` and `--sections=Defaults,Held loops` optionally limit either roster.
        The generator's first positional argument overrides the output directory.

        Keep/Revise/Unsure and notes are stored locally in your browser. Export listening notes before
        clearing storage or moving the folder. The manifest records every control, probe switch,
        note, velocity, audio metric, event count, energy ledger and render cost. Individual CSVs
        contain event direction/energy and the mechanical, acoustic and thermal state trajectory.
        Isolated attacks have intentionally null diagnostics and header-only CSVs.

        $PREVIOUS_FEEDBACK

        $METRIC_NOTES

        Generated audio, CSVs and pages are listening artifacts under testkit/ and should not be committed.
    """.trimIndent() + "\n"

    private val PAGE = """
        <!doctype html>
        <html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Hear Pitchwheel's revised wooden voices in SnipSnap: release-only hits, actual resin and recoil mechanisms, and preserved previous models.">
        <title>Pitchwheel · SnipSnap audition</title>
        <style>
        :root{color-scheme:dark;--bg:#10130e;--panel:#1d221a;--line:#4c5940;--ink:#ece8d5;--muted:#b0b7a3;--accent:#d1e591}*{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,sans-serif}main{max-width:1200px;margin:auto;padding:28px 20px 130px}h1{font-size:36px;letter-spacing:.04em;margin:0}h2{font-size:21px;margin:28px 0 10px}p{color:var(--muted);max-width:900px}.badge{color:var(--accent);font:12px monospace}.notice{border-left:3px solid var(--accent);padding-left:13px}
        .controls,.row{display:flex;flex-wrap:wrap;gap:9px;margin:14px 0}input,select,button,textarea{font:inherit;background:#151a12;color:var(--ink);border:1px solid var(--line);border-radius:6px;padding:8px}button{cursor:pointer}button:hover,button.active{border-color:var(--accent)}button.active{background:#3a472c}input[type=search]{flex:1;min-width:210px}textarea{width:100%;min-height:66px;resize:vertical}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(285px,1fr));gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:15px}.card.playing{border-color:var(--accent)}h3{font-size:17px;margin:7px 0}.card p{font-size:13px;margin:8px 0}.card button{font-size:13px}details{font-size:12px;color:var(--muted)}summary{cursor:pointer}pre{font:11px/1.5 monospace;white-space:pre-wrap}a{color:var(--accent)}.count{font-size:13px;color:var(--muted)}
        .player{position:fixed;bottom:0;left:0;right:0;background:#10130ef5;border-top:2px solid var(--line);padding:10px 20px;display:flex;align-items:center;flex-wrap:wrap;gap:14px}.player audio{flex:1;min-width:200px;height:38px}.now{font-size:12px;min-width:170px;max-width:300px}label{display:inline-flex;align-items:center;gap:7px;font-size:13px}:focus-visible{outline:2px solid var(--accent);outline-offset:3px}
        @media(max-width:500px){main{padding:20px 12px 190px}.grid{grid-template-columns:1fr}.player{padding:9px 12px;gap:7px}.now{width:100%;max-width:none}.player audio{width:100%}}
        </style><main>
        <div class="badge">SNIPSNAP / DRY ENGINE LISTENING</div><h1>PITCHWHEEL</h1>
        <p>A resonant wooden wheel turns through resin. Teeth catch and release flexible fingers; filaments stretch, bow, snap and sometimes pull the wheel backward.</p>
        <p class="notice">Start with one equal-work, release-only resonator hit from each voice, all at C3, dry and at matched level. Use each card's previous-model button for direct comparison with the earlier hit at the same 0.75-second duration. These hits compare body colour and decay; they do not exercise wheel motion, resin bowing, recoil or warming. Use Real mechanisms to hear those processes and full gestures to hear the complete instrument. Recipes and presets remain provisional pending listening approval.</p>
        <p id="previousFeedback"></p>
        <div class="row"><button data-section="Isolated attacks">Release-only hits</button><button data-section="Previous model 2 hits">Previous model 2 hits</button><button data-section="Real mechanisms">Real mechanisms</button><button data-section="Defaults">Full gestures</button><button data-section="Previous model 2">Previous model 2 defaults</button><button data-section="Previous model 1">Previous model 1 defaults</button><button data-section="">Full matrix</button></div>
        <div class="controls"><select id="voice" aria-label="Filter voices"><option value="">All voices</option></select><select id="section" aria-label="Filter sections"><option value="">All sections</option></select><input id="search" type="search" aria-label="Search clips" placeholder="Find a voice, macro, note or mechanism"><button id="export">Export listening notes</button></div>
        <p>Votes and notes stay in this browser. Use Export listening notes and attach the JSON in this chat so I can read your feedback.</p>
        <div id="count" class="count" role="status"></div><div id="clips"></div>
        <details><summary>How to read the measurements</summary><p id="metricNotes"></p></details></main>
        <div class="player"><div id="now" class="now" role="status">Choose a clip</div><audio id="audio" controls preload="none"></audio><label><input id="repeat" type="checkbox" checked>Repeat held loops</label><button id="stop">Stop</button></div>
        <script id="manifest" type="application/json">__MANIFEST__</script><script>
        'use strict';
        const manifest=JSON.parse(document.getElementById('manifest').textContent),clips=manifest.clips;
        const voice=document.getElementById('voice'),section=document.getElementById('section'),search=document.getElementById('search'),audio=document.getElementById('audio'),repeat=document.getElementById('repeat');
        const storageKey='snipsnap-pitchwheel-listening-'+manifest.listeningRound;let verdicts={},current=null,playRequest=0;try{verdicts=JSON.parse(localStorage.getItem(storageKey)||'{}')}catch(e){}
        function el(tag,txt,cls){const n=document.createElement(tag);if(txt!==undefined)n.textContent=txt;if(cls)n.className=cls;return n}
        function save(){try{localStorage.setItem(storageKey,JSON.stringify(verdicts))}catch(e){document.getElementById('count').textContent='Browser storage unavailable; export your notes before closing.'}}
        function options(select,values){for(const value of [...new Set(values)]){const o=el('option',value);o.value=value;select.append(o)}}
        function num(v){return typeof v==='number'&&Number.isFinite(v)?v.toExponential(3):'unavailable'}
        function evidence(c){const d=c.diagnostics;if(!d)return '\nOne equal-work, release-only resonator hit. No wheel trajectory, resin bowing, recoil, warming or mechanical energy ledger; CSVs contain headers only.';let text='\nEvents '+JSON.stringify(d.eventCounts)+' · forward releases '+d.forwardReleases+' · reverse releases '+d.reverseReleases+'\nInitial energy '+num(d.initialEnergy)+' / input work '+num(d.inputWork)+'\nDissipated '+num(d.dissipated)+' / numerical removal '+num(d.passiveCorrection)+'\nMaximum energy error '+num(d.maxEnergyError)+' / final energy '+num(d.finalEnergy)+'\nReleased '+num(d.releasedEnergy)+' / bowed '+num(d.bowedEnergy)+' / snapped '+num(d.snappedEnergy)+'\nMaximum attachments '+d.maxAttachments+' · solver '+c.probe.solverRate+' Hz';if(d.temperature)text+='\nTemperature: initial '+num(d.temperature.initial)+' / final '+num(d.temperature.final)+' / min '+num(d.temperature.min)+' / max '+num(d.temperature.max);if(d.releaseEncounters)text+='\nActual release encounters\n'+d.releaseEncounters.map(e=>e.time.toFixed(4)+' s · tooth '+e.tooth+' · '+(e.direction>0?'forward':'reverse')+' · work '+num(e.energy)).join('\n');return text}
        options(voice,clips.map(c=>c.voice));options(section,clips.map(c=>c.section));if(clips.some(c=>c.section==='Isolated attacks'))section.value='Isolated attacks';document.getElementById('metricNotes').textContent=manifest.metricNotes;document.getElementById('previousFeedback').textContent=manifest.previousFeedback;
        function play(c,mode){const request=++playRequest;current=c;audio.pause();audio.src=c[mode+'Path']+'?round='+encodeURIComponent(manifest.listeningRound);audio.loop=c.loop&&repeat.checked;document.getElementById('now').textContent=c.label+' · '+mode+(c.loop?' · settled loop':'');for(const card of document.querySelectorAll('.card'))card.classList.toggle('playing',card.dataset.id===c.id);audio.play().catch(e=>{if(request!==playRequest||e.name==='AbortError')return;document.getElementById('now').textContent='Playback unavailable: '+e.message})}
        function display(){
          const query=search.value.toLowerCase(),selected=clips.filter(c=>(!voice.value||voice.value===c.voice)&&(!section.value||section.value===c.section)&&(!query||(c.label+' '+c.description+' '+c.section).toLowerCase().includes(query)));
          document.getElementById('count').textContent=selected.length+' of '+clips.length+' cases · '+(manifest.full?'full matrix':'compact roster')+' · listening approval pending';
          const host=document.getElementById('clips');host.replaceChildren();
          for(const name of [...new Set(selected.map(c=>c.section))]){
            const group=el('section'),grid=el('div',undefined,'grid');group.append(el('h2',name),grid);host.append(group);
            for(const c of selected.filter(c=>c.section===name)){
              const card=el('article',undefined,'card');card.dataset.id=c.id;card.classList.toggle('playing',current?.id===c.id);grid.append(card);
              card.append(el('div',c.voice+' / MODEL '+c.modelVersion+' / '+(c.isolatedKind?'RELEASE ONLY':c.loop?'SETTLED LOOP':c.held?'POWERED DIAGNOSTIC':'FINITE'),'badge'),el('h3',c.label),el('p',c.description));
              const row=el('div',undefined,'row');for(const mode of ['matched','raw']){const b=el('button','Play '+mode);b.addEventListener('click',()=>play(c,mode));row.append(b)}card.append(row);
              if(c.isolatedKind&&c.modelVersion===manifest.modelVersion){const previous=clips.find(p=>p.id===c.voice.toLowerCase()+'_previous_v2_release');if(previous){const b=el('button','Play previous model 2 matched');b.addEventListener('click',()=>play(previous,'matched'));row.append(b)}}
              const details=el('details');details.append(el('summary',c.seconds.toFixed(2)+' s · raw peak '+c.rawMetrics.peak.toFixed(3)+' · '+c.renderMs.toFixed(0)+' ms render'));
              details.append(el('pre',Object.entries(c.macros).map(([k,v])=>k+' '+v.toFixed(2)).join('  ')+'\nModel '+c.modelVersion+' · requested MIDI '+c.requestedMidi+' / '+c.requestedHz.toFixed(2)+' Hz · velocity '+c.velocity+'\nRaw RMS '+num(c.rawMetrics.rms)+' / DC '+num(c.rawMetrics.dc)+'\nMatched RMS '+num(c.matchedMetrics.rms)+' / peak '+c.matchedMetrics.peak.toFixed(3)+'\nRaw export gain '+c.rawExportGain+evidence(c)+(c.loop?'\nOne settled revolution; enable Repeat held loops for several wraps.\nKeys seam '+num(c.seamError)+' / state error '+num(c.stateError)+'\nPreroll cycles '+c.prerollCycles+' / previous cycle frames '+c.previousCycleFrames+'\nWrap step '+num(c.rawMetrics.wrapStep)+' / slope '+num(c.rawMetrics.wrapSlope):'')+'\nDSP source '+c.dspSourceSha256+'\nCompiled class '+c.compiledClassSha256));
              const links=el('div',undefined,'row');for(const [path,label] of [['eventsPath','Event CSV'],['tracePath','State CSV']]){const a=el('a',label);a.href=c[path];a.download='';links.append(a)}details.append(links);card.append(details);
              const votes=el('div',undefined,'row');for(const choice of ['Keep','Revise','Unsure']){const b=el('button',choice);b.classList.toggle('active',verdicts[c.id]?.verdict===choice);b.setAttribute('aria-pressed',String(verdicts[c.id]?.verdict===choice));b.addEventListener('click',()=>{verdicts[c.id]={...(verdicts[c.id]||{}),verdict:choice};save();for(const v of votes.children){v.classList.toggle('active',v.textContent===choice);v.setAttribute('aria-pressed',String(v.textContent===choice))}});votes.append(b)}card.append(votes);
              const note=el('textarea');note.placeholder='Pitch, catch, strain, recoil, tail, seam…';note.setAttribute('aria-label','Listening notes for '+c.label);note.value=verdicts[c.id]?.note||'';note.addEventListener('input',()=>{verdicts[c.id]={...(verdicts[c.id]||{}),note:note.value};save()});card.append(note);
            }
          }
        }
        for(const control of [voice,section,search])control.addEventListener('input',display);
        for(const button of document.querySelectorAll('[data-section]')){button.hidden=Boolean(button.dataset.section&&!clips.some(c=>c.section===button.dataset.section));button.addEventListener('click',()=>{section.value=button.dataset.section;search.value='';display()})}
        repeat.addEventListener('change',()=>{audio.loop=Boolean(current?.loop&&repeat.checked)});document.getElementById('stop').addEventListener('click',()=>{++playRequest;audio.pause();audio.currentTime=0});
        document.getElementById('export').addEventListener('click',()=>{const blob=new Blob([JSON.stringify({engine:manifest.engine,modelVersion:manifest.modelVersion,listeningRound:manifest.listeningRound,dspSourceSha256:manifest.dspSourceSha256,compiledClassSha256:manifest.compiledClassSha256,modelProvenance:manifest.modelProvenance,recipeSha256:manifest.recipeSha256,clipModels:Object.fromEntries(clips.map(c=>[c.id,c.modelVersion])),exportedAt:new Date().toISOString(),full:manifest.full,sonicAcceptance:manifest.sonicAcceptance,previousFeedback:manifest.previousFeedback,verdicts},null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=el('a');a.href=url;a.download=manifest.listeningRound+'-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)});display();
        </script></html>
    """.trimIndent()
}
