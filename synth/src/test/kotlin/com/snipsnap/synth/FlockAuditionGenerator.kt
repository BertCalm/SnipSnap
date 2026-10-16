package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** The first finite FLOCK listening experiment, rendered by the production Kotlin engine. */
object FlockAuditionGenerator {
    private const val TARGET = 0.12
    private const val CEILING = 0.90
    private const val OVERSAMPLE = 4
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val macroNames = listOf("SPREAD", "PULL", "FLOW", "SCATTER", "EDGE", "HOLD")
    private val descriptions = mapOf(
        FlockVoice.GATHER to "A scattered attack gathers around the played note.",
        FlockVoice.RIBBON to "A turn moves through neighboring oscillators as a traveling pitch shimmer.",
        FlockVoice.SPLIT to "Separate harmonic groups move around the root, fifth and octave.",
    )

    private data class Spec(
        val id: String,
        val label: String,
        val description: String,
        val category: String,
        val voice: FlockVoice = FlockVoice.RIBBON,
        val macros: Map<String, Float> = Flock.defaults(voice),
        val midi: Int = 60,
        val velocity: Float = 1f,
        val seed: Long = Flock.DEFAULT_SEED,
        val mode: FlockMode = FlockMode.FLOCK,
        val perturb: Boolean = false,
        val sweepMacro: String? = null,
        val sweepValue: Float? = null,
    )

    private data class Rendered(val spec: Spec, val probe: Flock.Probe, val raw: Snip)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/flock-audition").canonicalFile
        root.mkdirs()
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() } ?: error("run from the SnipSnap repository")
        val source = provenance(repo)
        @Suppress("UNCHECKED_CAST")
        for (entry in source.getValue("files") as List<Map<String, String>>) {
            val path = entry.getValue("path")
            val copy = File(root, "source/$path")
            copy.parentFile.mkdirs()
            File(repo, path).copyTo(copy, overwrite = true)
        }
        val clips = mutableListOf<Map<String, Any?>>()
        val sections = mutableListOf<Map<String, Any?>>()
        val causalPairs = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        var renderNanos = 0L
        var renderedSeconds = 0.0

        fun render(spec: Spec): Rendered {
            val began = System.nanoTime()
            val probe = Flock.renderDetailed(
                voice = spec.voice, macros = spec.macros, midi = spec.midi,
                velocity = spec.velocity, seed = spec.seed, mode = spec.mode,
                perturb = spec.perturb, oversample = OVERSAMPLE,
                model = 1,
            )
            renderNanos += System.nanoTime() - began
            val raw = Snip(probe.rawSamples, channels = probe.snip.channels, sampleRate = probe.snip.sampleRate)
            require(raw.sampleRate == 44_100 && raw.channels == 1 && raw.samples.isNotEmpty())
            require(raw.samples.all { it.isFinite() }) { "${spec.id}: non-finite audio" }
            require(probe.trace.isNotEmpty() && probe.trace.all {
                it.seconds.isFinite() && it.pitchSemitones.size == 24 && it.energy.size == 24 &&
                    it.pitchSemitones.all(Double::isFinite) && it.energy.all(Double::isFinite)
            }) { "${spec.id}: malformed particle trace" }
            renderedSeconds += raw.frameCount.toDouble() / raw.sampleRate
            return Rendered(spec, probe, raw)
        }

        fun write(rendered: Rendered, sharedGain: Double? = null, matchGroup: String? = null) {
            val (spec, probe, raw) = rendered
            require(clips.none { it["id"] == spec.id }) { "duplicate clip id ${spec.id}" }
            val peak = peak(raw)
            val loudness = Loudness.of(raw).toDouble()
            require(loudness > 1e-8) { "${spec.id}: silent rendering" }
            val rawGain = minOf(1.0, CEILING / peak.coerceAtLeast(1e-12))
            val matchedGain = sharedGain ?: minOf(TARGET / loudness, CEILING / peak.coerceAtLeast(1e-12))
            val native = gain(raw, rawGain)
            val matched = gain(raw, matchedGain)
            require(peak(native) <= CEILING + 1e-6 && peak(matched) <= CEILING + 1e-6)
            val rawPath = "raw/${spec.id}.wav"
            val matchedPath = "matched/${spec.id}.wav"
            val recipePath = "recipes/${spec.id}.json"
            val tracePath = "traces/${spec.id}.csv"
            WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            writeTrace(File(root, tracePath), probe.trace)
            val level = linkedMapOf<String, Any?>(
                "rawGain" to rawGain, "matchedGain" to matchedGain,
                "rawPeak" to peak(native), "rawRms" to rms(native),
                "matchedPeak" to peak(matched), "matchedRms" to rms(matched),
                "synthesisPeak" to peak, "synthesisRms" to rms(raw),
                "rawLoudest200msRms" to Loudness.of(native).toDouble(),
                "matchedLoudest200msRms" to Loudness.of(matched).toDouble(),
                "peakLimited" to (peak(matched) >= CEILING - 1e-6),
                "matchGroup" to matchGroup,
                "matchingPolicy" to if (sharedGain == null) "per-clip fixed gain" else "shared causal-pair fixed gain",
            )
            val patch = FlockPatch(spec.label, spec.voice, spec.macros, spec.midi, spec.velocity, spec.seed, model = 1)
            writeJson(File(root, recipePath), linkedMapOf(
                "schemaVersion" to 1,
                "patch" to patch.toJsonValue(),
                "render" to linkedMapOf("entryPoint" to "Flock.renderDetailed", "mode" to spec.mode.name,
                    "perturb" to spec.perturb, "oversample" to OVERSAMPLE, "sourceSamples" to "Probe.rawSamples"),
                "output" to linkedMapOf("sampleRate" to raw.sampleRate, "channels" to raw.channels,
                    "format" to "PCM_16", "loop" to false, "level" to level),
                "source" to source,
            ))
            val d = probe.diagnostics
            clips += linkedMapOf(
                "id" to spec.id, "label" to spec.label, "description" to spec.description,
                "category" to spec.category, "gesture" to spec.voice.name, "mode" to spec.mode.name,
                "src" to matchedPath, "rawSrc" to rawPath, "recipeSrc" to recipePath, "traceSrc" to tracePath,
                "duration" to raw.frameCount.toDouble() / raw.sampleRate,
                "note" to spec.midi, "noteLabel" to noteLabel(spec.midi), "velocity" to spec.velocity,
                "seed" to spec.seed, "params" to spec.macros, "perturb" to spec.perturb,
                "sweepMacro" to spec.sweepMacro, "sweepValue" to spec.sweepValue,
                "sha256" to linkedMapOf("raw" to sha(File(root, rawPath)), "matched" to sha(File(root, matchedPath)),
                    "recipe" to sha(File(root, recipePath)), "trace" to sha(File(root, tracePath))),
                "level" to level,
                "diagnostics" to linkedMapOf(
                    "durationSeconds" to d.durationSeconds, "rawPeak" to d.rawPeak, "rawRms" to d.rawRms,
                    "outputPeak" to d.outputPeak, "minFrequencyHz" to d.minFrequencyHz,
                    "maxFrequencyHz" to d.maxFrequencyHz, "maxSpeed" to d.maxSpeed,
                    "neighborMean" to d.neighborMean, "perturbationSeconds" to d.perturbationSeconds,
                    "directNeighborsAtImpulse" to d.directNeighborsAtImpulse,
                    "perturbationNeighbors" to d.perturbationNeighbors.map {
                        linkedMapOf("seconds" to it.seconds, "receiversOfZero" to it.receiversOfZero)
                    },
                ),
            )
            println("${clips.size.toString().padStart(2)} ${spec.id}: ${"%.2f".format(Locale.ROOT, raw.frameCount.toDouble() / raw.sampleRate)} s")
        }

        fun section(id: String, title: String, description: String, ids: List<String>) {
            sections += linkedMapOf("id" to id, "title" to title, "description" to description, "clipIds" to ids)
        }

        val defaults = FlockVoice.entries.associateWith { voice ->
            val id = voice.name.lowercase() + "-default"
            write(render(Spec(id, voice.name, descriptions.getValue(voice), "gestures", voice)))
            id
        }
        section("gestures", "Start here: three gestures", "C4, hard strike, the same saved seed. These are finite dry renderings; repeat and seamless loops are not claimed.", defaults.values.toList())

        val comparisonIds = mutableListOf(defaults.getValue(FlockVoice.RIBBON))
        for (mode in listOf(FlockMode.FIXED, FlockMode.INDEPENDENT)) {
            val id = "ribbon-${mode.name.lowercase()}"
            write(render(Spec(id, mode.name, when (mode) {
                FlockMode.FIXED -> "The same seeded initial detuning, held still. Compare against the moving flock."
                else -> "The same seeded particles and intrinsic drives, with neighbor interactions disabled."
            }, "comparison", mode = mode)))
            comparisonIds += id
        }
        section("comparison", "Does the flock earn its place?", "RIBBON with identical note, seed, oscillator phases, initial detuning and duration. FIXED holds initial detuning; INDEPENDENT removes neighbor influence. Playback uses the reported fixed matching gains.", comparisonIds)

        val causalIds = mutableListOf<String>()
        val causalMacros = Flock.defaults(FlockVoice.RIBBON) + mapOf(
            "SPREAD" to .6f, "PULL" to .35f, "FLOW" to .85f,
            "SCATTER" to .35f, "EDGE" to .4f, "HOLD" to 1f,
        )
        for (mode in listOf(FlockMode.FLOCK, FlockMode.INDEPENDENT)) {
            val pairId = "causal-${mode.name.lowercase()}"
            val off = render(Spec("$pairId-off", "${mode.name}: impulse off", "The unchanged deterministic reference.", "causal", macros = causalMacros, mode = mode))
            val on = render(Spec("$pairId-on", "${mode.name}: impulse on", "At 1.00 s, one velocity impulse reaches particle 0 only. All other settings and noise drives stay identical.", "causal", macros = causalMacros, mode = mode, perturb = true))
            val sharedGain = minOf(TARGET / maxOf(Loudness.of(off.raw), Loudness.of(on.raw)).toDouble(), CEILING / maxOf(peak(off.raw), peak(on.raw)))
            require(off.raw.samples.size == on.raw.samples.size)
            // The shared symmetric decimator can pre-ring immediately around the impulse.
            // Its two 16-source-sample half-widths sum to <0.3 ms; 5 ms is a conservative guard.
            val safePrefix = (off.raw.sampleRate * .995).toInt()
            require((0 until safePrefix).all { off.raw.samples[it] == on.raw.samples[it] }) { "$pairId differs before the decimator's impulse support" }
            write(off, sharedGain, pairId)
            write(on, sharedGain, pairId)
            val tracePath = "traces/$pairId.json"
            val pair = causalDifference(pairId, mode, off, on)
            writeJson(File(root, tracePath), pair)
            causalPairs += linkedMapOf(
                "id" to pairId, "mode" to mode.name, "off" to off.spec.id, "on" to on.spec.id,
                "traceSrc" to tracePath, "sha256" to sha(File(root, tracePath)),
                "description" to "Only particle 0 is directly disturbed at 1.00 s. Pitch differences reveal whether other particles respond.",
                "affectedAgentsExcludingDirect" to pair.getValue("affectedAgentsExcludingDirect"),
                "indirectResponders" to pair.getValue("indirectResponders"),
            )
            causalIds += listOf(off.spec.id, on.spec.id)
        }
        section("causal", "One particle turns. Who follows?", "An impulse reaches particle 0 at exactly 1 second. Each off/on pair uses one shared output gain. State traces agree before the impulse; audio agrees through 995 ms, allowing for the decimator's short pre-ringing. Pitch differences measure modeled response; musical quality remains a listening judgment.", causalIds)

        val registerIds = mutableListOf<String>()
        for (voice in FlockVoice.entries) {
            for (midi in listOf(48, 60, 72)) {
                if (midi == 60) registerIds += defaults.getValue(voice) else {
                    val id = "${voice.name.lowercase()}-${noteLabel(midi).lowercase()}"
                    write(render(Spec(id, "${voice.name} ${noteLabel(midi)}", "Saved defaults transposed to ${noteLabel(midi)}; seed and gesture unchanged.", "register", voice, midi = midi)))
                    registerIds += id
                }
            }
        }
        section("register", "C3, C4, C5", "Every gesture across three registers. The C4 references reuse the start-here files.", registerIds)

        val velocityIds = mutableListOf<String>()
        for (voice in FlockVoice.entries) {
            for ((velocity, label) in listOf(.25f to "Soft", .6f to "Medium", 1f to "Hard")) {
                if (velocity == 1f) velocityIds += defaults.getValue(voice) else {
                    val id = "${voice.name.lowercase()}-${label.lowercase()}"
                    write(render(Spec(id, "${voice.name} $label", "C4 at velocity $velocity. Matching playback level exposes changes in the initial disturbance.", "velocity", voice, velocity = velocity)))
                    velocityIds += id
                }
            }
        }
        section("velocity", "Soft, medium, hard", "Velocity supplies the initial disturbance. The six timbral controls remain at each gesture's defaults; hard references reuse the start-here files.", velocityIds)

        val sweepCompanions = Flock.defaults(FlockVoice.RIBBON) + mapOf(
            "SPREAD" to .55f, "PULL" to .55f, "FLOW" to .65f,
            "SCATTER" to .55f, "EDGE" to .4f, "HOLD" to .55f,
        )
        val sweepDescriptions = mapOf(
            "SPREAD" to "The permitted pitch spread and separation of the flock.",
            "PULL" to "The strength drawing the flock toward the played pitch.",
            "FLOW" to "How strongly neighbors follow each other's motion.",
            "SCATTER" to "Sensitivity to the strike's initial disturbance.",
            "EDGE" to "Rounded to brighter oscillator material, with the same trajectory.",
            "HOLD" to "Finite note duration: 2 to 6 seconds. This control exports no loop.",
        )
        val sweepIds = mutableListOf<String>()
        for (macro in macroNames) for ((index, value) in steps.withIndex()) {
            val id = "sweep-${macro.lowercase()}-$index"
            write(render(Spec(id, "$macro ${"%.2f".format(Locale.ROOT, value)}", sweepDescriptions.getValue(macro), "sweeps",
                macros = sweepCompanions + (macro to value), sweepMacro = macro, sweepValue = value)))
            sweepIds += id
        }
        section("sweeps", "Six controls, five steps", "RIBBON at C4 and velocity 1. Each row changes one control through 0, .25, .5, .75, 1; all companion values are saved in every recipe. HOLD changes finite duration only.", sweepIds)

        require(clips.size == 51) { "expected 51 clips, got ${clips.size}" }
        val clipIds = clips.map { it.getValue("id") }.toSet()
        require(sections.flatMap { @Suppress("UNCHECKED_CAST") (it.getValue("clipIds") as List<String>) }.all { it in clipIds })
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "FLOCK", "title" to "FLOCK — first flight",
            "subtitle" to "A finite flock of 24 oscillators. Listen for a reaction traveling through the group.",
            "status" to "First prototype; objective checks completed, musical acceptance awaits listening.",
            "createdUtc" to Instant.now().toString(), "source" to source,
            "audio" to linkedMapOf(
                "sampleRate" to 44_100, "channels" to 1, "format" to "PCM_16", "peakCeiling" to CEILING,
                "matchedMetric" to "SnipSnap loudest 200 ms RMS with its gentle 120 Hz low cut; not LUFS",
                "matchedTarget" to TARGET,
                "normalization" to "Fixed scalar gain, capped at peak 0.90; no effects. Causal pairs share one gain, so their louder member reaches the target.",
                "rawPolicy" to "Probe.rawSamples before engine leveling, with only a declared safety gain when peak exceeds 0.90.",
                "metrics" to "Pre-encoding float measurements; exported WAVs use PCM16 quantization.",
                "loops" to false, "oversample" to OVERSAMPLE,
            ),
            "simulation" to linkedMapOf("particles" to 24, "behaviorRateHz" to 420, "traceRateHz" to 42,
                "impulseTraceRateHz" to 420, "impulseTraceWindowSeconds" to listOf(1.0, 1.25),
                "neighborPolicy" to "Each receiver selects at most four nearest particles within its local radius; the directed graph is recalculated from the previous snapshot.",
                "tracePitchUnits" to "semitones relative to requested MIDI root, including SPLIT harmonic offsets",
                "traceEnergyUnits" to "engine particle energy coordinate", "traceColumns" to "seconds,pitch_00..pitch_23,energy_00..energy_23"),
            "download" to linkedMapOf("path" to "flock-matched.zip", "rawPath" to "flock-raw.zip", "checksumPath" to "flock-archives.sha256"),
            "clipCount" to clips.size, "sections" to sections, "clips" to clips, "causalPairs" to causalPairs,
            "sweepCompanions" to sweepCompanions,
            "generation" to linkedMapOf("renderSeconds" to renderNanos / 1e9, "audioSeconds" to renderedSeconds,
                "wallSeconds" to (System.nanoTime() - started) / 1e9,
                "javaVersion" to System.getProperty("java.version")),
        )
        writeJson(File(root, "manifest.json"), manifest)
        File(root, "README.txt").writeText("""
            FLOCK FIRST FLIGHT — SnipSnap production Kotlin engine audition

            51 finite dry one-shot clips; no seamless-loop claim, reverb or other effect.
            matched/ uses fixed gain toward SnipSnap loudest-200-ms RMS 0.12, peak capped at 0.90.
            raw/ preserves pre-level engine samples except a declared safety gain if needed.
            Both members of each causal off/on pair use the same gain and align from sample zero.
            Their state traces agree before 1 s. Audio agrees through 995 ms; the symmetric
            decimator can pre-ring immediately around the impulse (kernel support <0.3 ms).
            The impulse directly reaches particle 0 only, at exactly 1.00 s.
            Compare causal-flock against causal-independent and inspect traces/*.json.

            manifest.json lists sections, exact controls, measurements and file SHA256 hashes.
            recipes/ contains patches, render options, leveling gains and source-file provenance.
            source/ preserves the exact render and export source files identified by those hashes.
            traces/ CSV columns: seconds, 24 pitch offsets in semitones, 24 energy coordinates.
            A trace demonstrates modeled behavior, not musical quality; listening remains necessary.

            To rebuild, check out the recorded base revision and overlay source/ onto the
            repository root, then run:
              ./gradlew :synth:generateFlockAudition
            Revision: ${source.getValue("revision")}
            Working-tree SHA256 hashes in manifest.json identify the actual render source.
        """.trimIndent() + "\n")
        zipPacks(root)
        println("wrote ${clips.size} raw/matched clips, recipes, traces, manifest and ZIP under $root")
    }

    private fun causalDifference(id: String, mode: FlockMode, off: Rendered, on: Rendered): Map<String, Any?> {
        val a = off.probe.trace
        val b = on.probe.trace
        require(a.size == b.size)
        val threshold = .001
        val impulse = requireNotNull(on.probe.diagnostics.perturbationSeconds)
        val referenceContacts = off.probe.diagnostics.perturbationNeighbors
        val perturbedContacts = on.probe.diagnostics.perturbationNeighbors
        require(referenceContacts.size == perturbedContacts.size)
        // An edge disappearing in the perturbed run can also carry direct influence:
        // count receivers present in either graph, rather than only the perturbed graph.
        val contactHistory = referenceContacts.indices.map { index ->
            require(referenceContacts[index].seconds == perturbedContacts[index].seconds)
            Flock.NeighborFrame(referenceContacts[index].seconds,
                (referenceContacts[index].receiversOfZero + perturbedContacts[index].receiversOfZero).distinct().sorted())
        }
        val deltas = a.indices.map { frame ->
            require(a[frame].seconds == b[frame].seconds)
            List(24) { particle -> b[frame].pitchSemitones[particle] - a[frame].pitchSemitones[particle] }
        }
        fun responsesAt(onsetThreshold: Double) = (0 until 24).map { particle ->
            val first = a.indices.firstOrNull { a[it].seconds >= impulse && abs(deltas[it][particle]) >= onsetThreshold }
            val firstDirectContact = contactHistory
                .firstOrNull { particle in it.receiversOfZero }?.seconds
            linkedMapOf<String, Any?>("particle" to particle,
                "maxAbsSemitones" to deltas.maxOf { abs(it[particle]) },
                "firstResponseSeconds" to first?.let { a[it].seconds },
                "secondsAfterImpulse" to first?.let { a[it].seconds - impulse },
                "firstDirectContactSeconds" to firstDirectContact,
                "respondedBeforeDirectContact" to (first != null && a[first].seconds <= 1.25 &&
                    (firstDirectContact == null || firstDirectContact >= a[first].seconds - .5 / Flock.CONTROL_RATE)))
        }
        val responses = responsesAt(threshold)
        val structuralThreshold = 1e-10
        val structuralResponses = responsesAt(structuralThreshold)
        val affected = responses.drop(1).count { (it.getValue("maxAbsSemitones") as Double) >= threshold }
        val indirect = structuralResponses.drop(1).filter { it["respondedBeforeDirectContact"] == true }.map { it.getValue("particle") }
        val musicalSizedIndirect = responses.drop(1).filter { it["respondedBeforeDirectContact"] == true }.map { it.getValue("particle") }
        require(deltas.indices.filter { a[it].seconds < impulse }.all { frame -> deltas[frame].all { it == 0.0 } })
        if (mode == FlockMode.INDEPENDENT) require(responses.drop(1).all { (it.getValue("maxAbsSemitones") as Double) <= 1e-12 }) {
            "independent particles responded to another particle's impulse"
        }
        if (mode == FlockMode.FLOCK) {
            require(affected > 0) { "the flock shows no measurable neighbor response" }
            require(indirect.isNotEmpty()) { "no particle responded through an indirect neighbor path" }
        }
        return linkedMapOf(
            "id" to id, "mode" to mode.name, "off" to off.spec.id, "on" to on.spec.id,
            "impulseTime" to impulse, "thresholdSemitones" to threshold,
            "description" to "Perturbed minus reference pitch offset. Only particle 0 receives the direct impulse; other responses arise through neighbor interaction.",
            "frames" to a.indices.map { linkedMapOf("seconds" to a[it].seconds, "deltas" to deltas[it]) },
            "agentResponses" to responses, "affectedAgentsExcludingDirect" to affected,
            "directNeighborIds" to on.probe.diagnostics.directNeighborsAtImpulse,
            "directNeighborDefinition" to "Receivers whose selected-neighbor list contains particle 0 in the pre-impulse graph at 1.00 s.",
            "contactHistory" to contactHistory.map {
                linkedMapOf("seconds" to it.seconds, "receiversOfZero" to it.receiversOfZero)
            },
            "contactHistoryDefinition" to "Union of reference and perturbed receivers of particle 0 at each step; a disappearing edge still counts as direct influence.",
            "contactHistoryEndSeconds" to 1.25,
            "indirectResponders" to indirect,
            "structuralThresholdSemitones" to structuralThreshold,
            "structuralAgentResponses" to structuralResponses,
            "musicalThresholdIndirectResponders" to musicalSizedIndirect,
            "indirectDetectionNote" to "Indirect-path detection uses a 1e-10-semitone numerical threshold, not an audibility threshold. The separate 0.001-semitone response measure is also a diagnostic, not a guarantee of audibility.",
        )
    }

    private fun provenance(repo: File): Map<String, Any?> {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git failed: $output" }
            return output
        }
        val files = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Flock.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/FlockPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/FlockPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FlockAuditionGenerator.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
            "synth/build.gradle.kts",
        )
        return linkedMapOf(
            "revision" to git("rev-parse", "HEAD"), "dirty" to git("status", "--porcelain").isNotEmpty(),
            "repository" to "https://github.com/BertCalm/SnipSnap",
            "files" to files.map { path ->
                val file = File(repo, path)
                require(file.isFile) { "missing provenance file: $path" }
                linkedMapOf("path" to path, "sha256" to sha(file))
            },
        )
    }

    private fun writeTrace(file: File, trace: List<Flock.TraceFrame>) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine((listOf("seconds") + (0 until 24).map { "pitch_%02d".format(it) } + (0 until 24).map { "energy_%02d".format(it) }).joinToString(","))
            for (frame in trace) out.appendLine((listOf(frame.seconds) + frame.pitchSemitones + frame.energy)
                .joinToString(",") { "%.9f".format(Locale.ROOT, it) })
        }
    }

    private fun gain(snip: Snip, gain: Double) = Snip(FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() }, snip.channels, snip.sampleRate)
    private fun peak(snip: Snip) = snip.samples.maxOf { abs(it.toDouble()) }
    private fun rms(snip: Snip) = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun noteLabel(midi: Int) = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }

    private fun zipPacks(root: File) {
        val checksums = mutableListOf<String>()
        for (audioFolder in listOf("matched", "raw")) {
            val archive = File(root, "flock-$audioFolder.zip")
            val include = root.walkTopDown().filter { file ->
                if (!file.isFile) false else {
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    path.substringBefore('/') in listOf(audioFolder, "recipes", "traces", "source") || path in listOf("manifest.json", "README.txt")
                }
            }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in include) {
                    val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(archive.length() < 24L * 1024 * 1024) { "${archive.name} exceeds the hosting asset budget" }
            checksums += "${sha(archive)}  ${archive.name}"
        }
        File(root, "flock-archives.sha256").writeText(checksums.joinToString("\n", postfix = "\n"))
    }
}
