package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/** Complete dry listening evidence; generated copy describes intent, never sonic acceptance. */
object TesseraAuditionGenerator {
    private const val LISTENING_TARGET = .12f
    private const val LISTENING_CEILING = .90f
    private val holdPlaybackNotes = """
        HOLD learns a finite collector reply score from returning pressure, then repeats that
        compatible source score while the complete coupled object continues settling. The score
        fixes recurring source events; it does not copy an audio cycle. Sources, frame, paths,
        walls and collector state must still pass the convergence gate before loop export.

        The exported buffer contains settled recurring material; the initial isolated strike
        is excluded. Repeat starts on. Main controls use sample-accurate decoded buffer loops
        over HTTP(S), with gain ramps only when playback starts or stops. Pause/Resume preserve
        loop phase. Turn Repeat off to preview one cycle; the WAV's repeating samples remain
        unchanged. Local-file playback and native controls use browser media fallback.
    """.trimIndent()
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val timbral = listOf("MATERIAL", "HAMMER", "SCALE", "FOLD", "MOTION")
    private val voiceDescriptions = mapOf(
        TesseraVoice.WOOD to "Rounded wooden bars lead; paired strings and metal can answer through the shared frame and chamber.",
        TesseraVoice.COURSE to "Paired strings lead with deterministic shimmer, sharing their energy with wood and metal.",
        TesseraVoice.TUBE to "A clear tubular-metal strike leads, with subordinate upper modes and differently voiced returns.",
        TesseraVoice.ANSWER to "The shared object favors separated, differently voiced mechanical answers after the initial strike.",
        TesseraVoice.FOLDING to "Stronger gesture-driven wall motion and selective routes reshape the chamber response.",
        TesseraVoice.CHAMBER to "A spacious chamber separates the initial material sound from its evolving reflected response.",
    )
    private val macroDescriptions = mapOf(
        "MATERIAL" to "Wood passes continuously through paired strings to metal. Compare the leading contact and the material that answers it; all three families remain in the coupled object.",
        "HAMMER" to "Soft broad contact becomes harder, brighter contact with bounded rebound. Compare articulation as well as the later response.",
        "SCALE" to "Compact rapid returns become more spacious, separated paths. Compare arrival spacing and the material loading.",
        "FOLD" to "Open routes become more selective intersecting paths. Compare which receiving material is favored and when its response emerges.",
        "MOTION" to "A stationary chamber becomes more responsive to the gesture. Compare the direct root, reflected texture and slower geometry changes.",
    )
    private val categories = linkedMapOf(
        "voices" to ("Six voices, three registers" to "Each voice's saved defaults at C3, C4 and C5. One note initializes the complete coupled object; independently exported pads share no chamber state."),
        "presets" to ("Factory recipes" to "Every named Tessera preset, rendered dry with its exact saved controls. These characters remain subject to listening review."),
        "energy" to ("Quiet, medium and strong gestures" to "Existing host velocity scales gesture energy while HAMMER retains its contact character. Matching playback level helps expose changes in the response."),
        "sweeps" to ("Five steps, every voice" to "Each timbral macro at 0, 0.25, 0.5, 0.75 and 1. Its companions use declared neutral values; TUNE is centered and HOLD is off."),
        "grids" to ("Five required interactions" to "ANSWER with neutral companions. Each 3 × 3 grid samples the required macro pair at 0, 0.5 and 1. Compare rows and columns for changes in routing, articulation and timing."),
        "levels" to ("Raw and matched pairs" to "The same synthesis appears twice. Raw preserves the original synthesis amplitude; matched uses loudest-200-ms RMS 0.12 with a 0.90 peak ceiling."),
        "diagnostics" to ("Follow the cause" to "Engineering switches isolate frame transfer, ordinary chamber audio, receiving ports, wall movement and pressure collectors. Source taps preserve their reference mix's gain. These switches are absent from the public instrument controls."),
        "hold" to ("Settled continuations" to "HOLD learns a finite collector reply score from returning pressure, then repeats that compatible source score while the complete object continues settling. The initial isolated strike is excluded from the exported loop. Repeat starts on; Pause and Stop fade smoothly, and Repeat off previews one cycle."),
    )
    private data class Clip(val evidence: Map<String, Any?>, val group: String) {
        val id get() = evidence.getValue("id") as String
        val category get() = evidence.getValue("category") as String
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1 && args.filter { it.startsWith("--") }.all { it == "--refresh-page" || it == "--quick" }) {
            "supply one output directory, optionally --refresh-page or --quick"
        }
        val root = File(positional.firstOrNull() ?: "../testkit/tessera-audition")
        val quick = "--quick" in args
        require(!(quick && "--refresh-page" in args)) { "quick generation and page refresh are separate modes" }
        if ("--refresh-page" in args) {
            val original = Json.parse(File(root, "manifest.json").readText()).obj()
            require(original.getValue("engine").str() == "TESSERA")
            val clips = original.getValue("clips").arr().map { row ->
                val evidence = row.obj().mapValues { fromJson(it.value) }
                for (key in listOf("path", "eventPath")) {
                    val asset = File(root, evidence.getValue(key) as String).canonicalFile
                    require(asset.toPath().startsWith(root.canonicalFile.toPath()) && asset.isFile) { "missing or unsafe $key" }
                }
                Clip(evidence, evidence.getValue("group") as String)
            }
            require(clips.size == original.getValue("clipCount").int() && clips.isNotEmpty())
            writePage(root, clips, Json.write(JsonValue.Obj(original)) + "\n")
            writeHoldNotes(root)
            println("refreshed ${clips.size} TESSERA audition cards and HOLD notes; audio and traces unchanged")
            return
        }
        root.mkdirs()
        val clips = mutableListOf<Clip>()
        var totalRenderNanos = 0L
        var peakObservedHeap = 0L
        val started = System.nanoTime()
        fun observeHeap() {
            val runtime = Runtime.getRuntime()
            peakObservedHeap = maxOf(peakObservedHeap, runtime.totalMemory() - runtime.freeMemory())
        }

        fun render(
            id: String, title: String, description: String, category: String, group: String,
            voice: TesseraVoice = TesseraVoice.ANSWER,
            macros: Map<String, Float> = Tessera.defaults(voice), velocity: Float = 1f,
            options: Tessera.ProbeOptions = Tessera.ProbeOptions(), branch: String = "full",
            matched: Boolean = true, existing: Tessera.Probe? = null,
        ): Tessera.Probe {
            observeHeap()
            val before = System.nanoTime()
            val probe = existing ?: Tessera.probe(voice, macros, velocity = velocity, options = options)
            val nanos = if (existing == null) System.nanoTime() - before else 0L
            totalRenderNanos += nanos
            observeHeap()
            val loop = (macros["HOLD"] ?: 0f) >= Tessera.LOOP_THRESHOLD
            require(probe.recoveredStates == 0) { "$id recovered a non-finite internal state" }
            require(listOf(probe.primaryWork, probe.primaryWorkBudget, probe.wallWork, probe.wallWorkBudget).all { it.isFinite() && it >= 0.0 }) {
                "$id has invalid source work or budgets"
            }
            require(probe.primaryWork <= probe.primaryWorkBudget + 1e-9) { "$id exceeded primary work budget" }
            require(probe.wallWork <= probe.wallWorkBudget + 1e-9) { "$id exceeded wall work budget" }
            if (loop) require(probe.loopConverged && probe.loopStateError < .003 && probe.seamError < Keys.MAX_SEAM_ERROR) {
                "$id has an unsettled or discontinuous loop: state=${probe.loopStateError}, seam=${probe.seamError}, converged=${probe.loopConverged}"
            }
            val samples = when (branch) {
                "direct-wood" -> probe.directMaterials[0]
                "direct-course" -> probe.directMaterials[1]
                "direct-tube" -> probe.directMaterials[2]
                "return-wood" -> probe.reexcitedMaterials[0]
                "return-course" -> probe.reexcitedMaterials[1]
                "return-tube" -> probe.reexcitedMaterials[2]
                "port-wood" -> probe.continuousReturns[0]
                "port-course" -> probe.continuousReturns[1]
                "port-tube" -> probe.continuousReturns[2]
                "chamber" -> probe.chamber
                "frame" -> probe.frame
                else -> probe.samples
            }
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "$id has invalid samples" }
            val raw = Snip(samples, channels = 1, sampleRate = Tessera.RATE)
            require(matched || raw.peak() <= 1f) { "$id raw peak ${raw.peak()} exceeds PCM range" }
            val reference = Snip(probe.samples, channels = 1, sampleRate = Tessera.RATE)
            val referenceGain = Loudness.of(listeningLevel(reference)) / Loudness.of(reference).coerceAtLeast(1e-9f)
            val output = when {
                !matched -> raw
                branch == "full" -> listeningLevel(raw)
                else -> Snip(raw.samples.map { it * referenceGain }.toFloatArray(), 1, Tessera.RATE)
            }
            require(output.peak() <= if (matched) LISTENING_CEILING + 1e-6f else 1f) { "$id export peak ${output.peak()} exceeds its ceiling" }
            val path = "clips/$id${if (matched) "_listen_012" else ""}.wav"
            WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_16)
            val eventPath = "events/$id.json"
            val events = linkedMapOf<String, Any?>(
                "id" to id, "voice" to voice.name, "branch" to branch, "macros" to macros, "velocity" to velocity,
                "hammerContacts" to probe.hammerContacts.map { contact -> linkedMapOf(
                    "timeSeconds" to contact.timeSeconds, "rebound" to contact.rebound,
                    "reservedEnergy" to contact.reservedEnergy, "durationSeconds" to contact.durationSeconds,
                ) },
                "arrivals" to probe.arrivals.map { event -> linkedMapOf(
                    "timeSeconds" to event.timeSeconds, "path" to event.path, "material" to event.material,
                    "strength" to event.strength, "delaySeconds" to event.delaySeconds,
                ) },
                "collectorEvents" to probe.collectorEvents.map { event -> linkedMapOf(
                    "timeSeconds" to event.timeSeconds, "material" to event.material,
                    "strength" to event.strength, "spentEnergy" to event.spentEnergy,
                    "remainingBudget" to event.remainingBudget,
                ) },
                "geometry" to probe.geometry.map { state -> linkedMapOf(
                    "timeSeconds" to state.timeSeconds, "size" to state.size, "fold" to state.fold,
                    "velocity" to state.velocity, "wallWork" to state.wallWork,
                    "passiveEnergy" to state.passiveEnergy, "collectorEnergy" to state.collectorEnergy,
                    "releaseCount" to state.releaseCount,
                ) },
                "finalPassiveEnergy" to probe.finalPassiveEnergy, "maxPassiveEnergy" to probe.maxPassiveEnergy,
                "wallWork" to probe.wallWork, "wallWorkBudget" to probe.wallWorkBudget, "primaryWork" to probe.primaryWork, "primaryWorkBudget" to probe.primaryWorkBudget, "initialCollectorBudget" to probe.initialCollectorBudget,
                "remainingCollectorBudget" to probe.remainingCollectorBudget,
                "loopStartFrame" to probe.loopStartFrame, "recoveredStates" to probe.recoveredStates,
                "seamError" to if (loop) probe.seamError else null,
                "loopStateError" to if (loop) probe.loopStateError else null,
                "loopConverged" to if (loop) probe.loopConverged else null,
                "loopStateErrors" to if (loop) probe.loopStateErrors else null,
                "traceNotes" to "Source taps and event traces expose this invented model's causal branches. Geometry snapshots are slow-state observations, not measurements of a physical room.",
            )
            File(root, eventPath).apply { parentFile.mkdirs(); writeText(Json.write(json(events)) + "\n") }
            val firstCollector = probe.collectorEvents.minOfOrNull { it.timeSeconds }
            val evidence = linkedMapOf<String, Any?>(
                "id" to id, "title" to title, "description" to description, "category" to category,
                "voice" to voice.name, "group" to group, "path" to path, "eventPath" to eventPath,
                "macros" to macros, "velocity" to velocity, "branch" to branch,
                "level" to if (!matched) "raw" else if (branch == "full") "matched" else "reference gain", "loop" to loop,
                "durationSeconds" to output.durationSeconds, "sampleRate" to Tessera.RATE,
                "rawPeak" to raw.peak(), "exportPeak" to output.peak(), "rawLoudness" to Loudness.of(raw),
                "exportLoudness" to Loudness.of(output), "referenceGain" to if (matched) referenceGain else 1f,
                "renderMilliseconds" to nanos / 1_000_000.0, "hammerContactCount" to probe.hammerContacts.size, "reboundCount" to probe.hammerContacts.count { it.rebound }, "arrivalCount" to probe.arrivals.size, "collectorCount" to probe.collectorEvents.size,
                "firstCollectorSeconds" to firstCollector, "geometrySnapshotCount" to probe.geometry.size,
                "finalPassiveEnergy" to probe.finalPassiveEnergy, "maxPassiveEnergy" to probe.maxPassiveEnergy,
                "wallWork" to probe.wallWork, "wallWorkBudget" to probe.wallWorkBudget, "primaryWork" to probe.primaryWork, "primaryWorkBudget" to probe.primaryWorkBudget, "initialCollectorBudget" to probe.initialCollectorBudget,
                "remainingCollectorBudget" to probe.remainingCollectorBudget, "recoveredStates" to probe.recoveredStates,
                "seamError" to if (loop) probe.seamError else null,
                "loopStateError" to if (loop) probe.loopStateError else null,
                "loopConverged" to if (loop) probe.loopConverged else null,
                "loopStateErrors" to if (loop) probe.loopStateErrors else null,
                "audioSha256" to sha256(File(root, path)), "eventSha256" to sha256(File(root, eventPath)),
                "options" to linkedMapOf(
                    "normalize" to options.normalize, "chamberEnabled" to options.chamberEnabled,
                    "receiversEnabled" to options.receiversEnabled, "collectorsEnabled" to options.collectorsEnabled,
                    "primaryStrikeEnabled" to options.primaryStrikeEnabled, "wallMotionEnabled" to options.wallMotionEnabled,
                    "frameCouplingEnabled" to options.frameCouplingEnabled, "isolatedMaterial" to options.isolatedMaterial,
                    "durationSeconds" to options.durationSeconds, "seedContext" to options.seedContext,
                ),
            )
            clips += Clip(evidence, group)
            if (clips.size % 25 == 0) println("rendered ${clips.size} TESSERA audition clips")
            return probe
        }

        for (voice in TesseraVoice.entries) for ((tune, register) in if (quick) listOf(.5f to "middle") else listOf(0f to "low", .5f to "middle", 1f to "high")) {
            val note = noteName(Tessera.midiFor(voice, tune))
            render("${voice.name.lowercase()}_$register", "${voice.name} · $note · $register",
                "${voiceDescriptions.getValue(voice)} TUNE ${fmt(tune)}; the remaining controls use this voice's defaults.",
                "voices", voice.name, voice, Tessera.defaults(voice) + ("TUNE" to tune))
        }
        for (preset in TesseraPresets.all()) {
            val loop = (preset.macros["HOLD"] ?: 0f) >= Tessera.LOOP_THRESHOLD
            val note = noteName(Tessera.midiFor(preset.voice, preset.macros.getValue("TUNE")))
            render("preset_" + preset.name.lowercase().replace(' ', '_'), "${preset.name} · $note",
                "${voiceDescriptions.getValue(preset.voice)} This exact factory recipe ${if (loop) "exports settled recurring material with Repeat on by default." else "is a finite one-shot."}",
                "presets", preset.voice.name, preset.voice, preset.macros)
        }
        if (!quick) {
            for (voice in TesseraVoice.entries) for ((velocity, label) in listOf(.3f to "quiet", .65f to "medium", 1f to "strong")) {
                render("${voice.name.lowercase()}_velocity_${tag(velocity)}", "${voice.name} · $label gesture",
                    "Host velocity ${fmt(velocity)} scales the finite hammer energy and the response it can provoke. HAMMER stays at the voice default. Playback is matched so articulation and answers remain comparable.",
                    "energy", voice.name, voice, velocity = velocity)
            }
            for (voice in TesseraVoice.entries) {
                val neutral = Tessera.macrosFor(voice).associate { it.name to it.neutral }
                for (macro in timbral) for (step in steps) {
                    render("${voice.name.lowercase()}_${macro.lowercase()}_${tag(step)}", "${voice.name} · $macro ${fmt(step)}",
                        "${macroDescriptions.getValue(macro)} Companion controls are neutral: ${macroLine(neutral.filterKeys { it != macro })}.",
                        "sweeps", "${voice.name} · $macro", voice, neutral + (macro to step))
                }
            }
        }
        val neutral = Tessera.macrosFor(TesseraVoice.ANSWER).associate { it.name to it.neutral }
        val interactions = listOf("MATERIAL" to "FOLD", "HAMMER" to "MOTION", "SCALE" to "MOTION", "SCALE" to "FOLD", "FOLD" to "MOTION")
        if (!quick) {
            for ((a, b) in interactions) for (va in listOf(0f, .5f, 1f)) for (vb in listOf(0f, .5f, 1f)) {
                render("grid_${a.lowercase()}_${tag(va)}_${b.lowercase()}_${tag(vb)}", "$a ${fmt(va)} × $b ${fmt(vb)}",
                    "ANSWER interaction grid; the other controls use their neutral values. Compare neighboring points for changes in routing, reflected texture, timing and material response.",
                    "grids", "$a × $b", macros = neutral + (a to va) + (b to vb))
            }
            for (voice in TesseraVoice.entries) {
                val raw = render("${voice.name.lowercase()}_raw", "${voice.name} · raw synthesis",
                    "Dry synthesis before engine loudness targeting and audition matching. The original amplitude is preserved; compare its matched partner to hear quiet details at the shared listening level.",
                    "levels", voice.name, voice, matched = false)
                render("${voice.name.lowercase()}_matched", "${voice.name} · matched synthesis",
                    "The exact raw partner raised to loudest-200-ms RMS 0.12, with a 0.90 peak ceiling. It has no added effects.",
                    "levels", voice.name, voice, existing = raw)
            }

        }
        val diagnostic = Tessera.defaults(TesseraVoice.ANSWER) + mapOf("MATERIAL" to .12f, "HAMMER" to .7f, "SCALE" to .7f, "FOLD" to .8f, "MOTION" to .55f)
        val full = render("diagnostic_full", "Connected object",
            "Reference for the causal switches: wood-led contact transfers through the frame and changing chamber, with receiving ports and bounded pressure collectors enabled. Inspect collector events and geometry beside the source taps.",
            "diagnostics", "Causal switches", macros = diagnostic)
        render("diagnostic_receivers_off", "Receiving ports off · chamber audio remains",
            "Continuous receiving force and collector excitation are disabled together. Ordinary chamber radiation still sounds. Compare cross-material answers with Connected object; this isolates re-excitation from an audible echo alone.",
            "diagnostics", "Causal switches", macros = diagnostic, options = Tessera.ProbeOptions(receiversEnabled = false))
        if (!quick) {
            render("diagnostic_frame_off", "Frame coupling off",
                "Mechanical frame transfer is disabled while chamber transport and receiving ports remain. Compare the immediate shared response and the later acoustically driven answers.",
                "diagnostics", "Causal switches", macros = diagnostic, options = Tessera.ProbeOptions(frameCouplingEnabled = false))
            render("diagnostic_chamber_off", "Chamber paths off",
                "Reflected chamber transport is disabled. The primary material contact and shared frame remain. Compare the absence of delayed acoustic answers with the connected reference.",
                "diagnostics", "Causal switches", macros = diagnostic, options = Tessera.ProbeOptions(chamberEnabled = false))
            render("diagnostic_walls_off", "Wall movement off · static routes remain",
                "Gesture-driven wall motion is disabled at the reference SCALE and FOLD. Static chamber paths and receivers remain. Compare the reflected texture and the geometry trace.",
                "diagnostics", "Causal switches", macros = diagnostic, options = Tessera.ProbeOptions(wallMotionEnabled = false))
            render("diagnostic_collectors_off", "Pressure collectors off · weak returns remain",
                "Mechanical collector releases are disabled. Continuous weak receiving force, frame transfer and ordinary chamber radiation remain. Compare discrete replies and inspect their event records.",
                "diagnostics", "Causal switches", macros = diagnostic, options = Tessera.ProbeOptions(collectorsEnabled = false))
            for ((branch, title, description) in listOf(
                Triple("direct-wood", "Direct wooden bars", "The wood branch attributable to the initial gesture and frame transfer, before chamber re-excitation."),
                Triple("direct-course", "Direct paired strings", "The paired-string branch attributable to the initial gesture and frame transfer, before chamber re-excitation."),
                Triple("direct-tube", "Direct metal tubes", "The metal branch attributable to the initial gesture and frame transfer, before chamber re-excitation."),
                Triple("return-wood", "Re-excited wooden bars", "Wooden material awakened by continuous receiving force and pressure-collector releases."),
                Triple("return-course", "Re-excited paired strings", "String material awakened by continuous receiving force and pressure-collector releases."),
                Triple("return-tube", "Re-excited metal tubes", "Metal material awakened by continuous receiving force and pressure-collector releases."),
                Triple("port-wood", "Wood receiving force", "The weak continuous acoustic force entering the wood receiving port, rather than the resulting material radiation."),
                Triple("port-course", "String receiving force", "The weak continuous acoustic force entering the string receiving port, rather than the resulting material radiation."),
                Triple("port-tube", "Metal receiving force", "The weak continuous acoustic force entering the metal receiving port, rather than the resulting material radiation."),
                Triple("chamber", "Ordinary chamber radiation", "The chamber's audible reflected signal, separate from the materials it re-excites."),
                Triple("frame", "Shared frame radiation", "The shared frame's immediate mechanical response, separate from material and chamber radiation."),
            )) {
                render("diagnostic_${branch.replace('-', '_')}", title,
                    "$description This tap inherits the connected reference's playback gain; its relative level is preserved.",
                    "diagnostics", "Source and receiving-port taps", macros = diagnostic, branch = branch, existing = full)
            }
            for ((index, family) in listOf("wood", "course", "tube").withIndex()) {
                render("diagnostic_isolated_$family", "Isolated $family · pitch reference",
                    "Only the selected material receives the primary gesture. Frame coupling, chamber paths, collectors and receiving ports are disabled. This direct source helps assess tuning before the coupled full mix.",
                    "diagnostics", "Direct tuning references", macros = neutral,
                    options = Tessera.ProbeOptions(chamberEnabled = false, receiversEnabled = false, collectorsEnabled = false, wallMotionEnabled = false, frameCouplingEnabled = false, isolatedMaterial = index))
            }
            render("diagnostic_passive_ringdown", "Static passive ringdown",
                "One primary hammer, then no powered wall motion or collector releases. The static material/frame/chamber network is observed for the maximum duration; listen for decay and inspect the remaining passive energy.",
                "diagnostics", "Stability and edge cases", macros = diagnostic + mapOf("MOTION" to 0f, "SCALE" to 1f, "FOLD" to 1f),
                options = Tessera.ProbeOptions(collectorsEnabled = false, wallMotionEnabled = false, durationSeconds = Tessera.MAX_SECONDS))
            for (voice in TesseraVoice.entries) {
                render("${voice.name.lowercase()}_all_high", "${voice.name} · all timbral controls high",
                    "All five timbral macros at 1, centered TUNE and HOLD off. Listen for a recognizable root, bounded wall response, discrete replies and a finite one-shot tail.",
                    "diagnostics", "All-high extremes", voice, Tessera.defaults(voice) + timbral.associateWith { 1f })
            }
            render("diagnostic_low_wood", "WOOD · low-register edge", "Lowest supported note with soft contact and spacious returns; compare pitch and the wooden body.",
                "diagnostics", "Stability and edge cases", TesseraVoice.WOOD, Tessera.defaults(TesseraVoice.WOOD) + mapOf("TUNE" to 0f, "HAMMER" to 0f, "SCALE" to 1f))
            render("diagnostic_high_tube", "TUBE · high-register edge", "Highest supported note with hard contact; check the direct root against bright metallic upper modes.",
                "diagnostics", "Stability and edge cases", TesseraVoice.TUBE, Tessera.defaults(TesseraVoice.TUBE) + mapOf("TUNE" to 1f, "HAMMER" to 1f))
            for (voice in TesseraVoice.entries) {
                render("${voice.name.lowercase()}_hold", "${voice.name} · settled HOLD",
                    "Recurring hammer/frame excitation and replenished collector energy maintain this settled continuation. The initial isolated strike is excluded; compare more than one cycle for seam behavior and discrete answers.",
                    "hold", "Default held chambers", voice, Tessera.defaults(voice) + ("HOLD" to 1f))
                render("${voice.name.lowercase()}_hold_extreme", "${voice.name} · difficult HOLD",
                    "All timbral controls high with HOLD 1. Check bounded moving paths, recurring energy and the wrap; pressure collectors should produce small distinct answers across successive cycles.",
                    "hold", "Difficult held chambers", voice, Tessera.defaults(voice) + timbral.associateWith { 1f } + ("HOLD" to 1f))
            }
            for (step in steps) {
                render("answer_hold_${tag(step)}", "ANSWER · HOLD ${fmt(step)}",
                    if (step >= Tessera.LOOP_THRESHOLD) "The loop threshold selects settled recurring material with Repeat on. The initial isolated gesture is excluded."
                    else "A finite render below the 0.99 loop threshold. Intermediate HOLD may change restrained continuation, but does not export a repeating buffer.",
                    "hold", "HOLD transition", macros = neutral + ("HOLD" to step))
            }

        } else {
            render("answer_hold", "ANSWER · settled HOLD",
                "A settled recurring chamber for previewing repeating playback. The initial isolated strike is excluded; Repeat starts on.",
                "hold", "Preview held chamber", macros = Tessera.defaults(TesseraVoice.ANSWER) + ("HOLD" to 1f))
        }

        val manifest = linkedMapOf<String, Any?>(
            "engine" to "TESSERA", "formatVersion" to 1, "clipCount" to clips.size, "mode" to if (quick) "quick preview" else "complete audition",
            "description" to "Dry audition evidence for an invented coupled material/changing chamber model. Owner sonic verdict pending.",
            "audioStartsOff" to true, "defaultPlaybackVolume" to .85f,
            "noteRange" to "C3–C5", "materialOrder" to listOf("wood", "paired strings", "metal tubes"), "velocityContract" to "Host velocity scales gesture energy; HAMMER controls contact character.",
            "listeningTarget" to LISTENING_TARGET, "listeningPeakCeiling" to LISTENING_CEILING,
            "loudness" to "Matched full clips: loudest 200 ms RMS 0.12, peak ceiling 0.90. Taps inherit their full reference's gain; raw pairs preserve synthesis amplitude.",
            "loopPlayback" to linkedMapOf("repeatByDefault" to true, "mainControls" to "Sample-accurate buffer looping, with smooth pause, resume and stop over HTTP(S).", "localFiles" to "Native media fallback uses relative WAV assets."),
            "renderMilliseconds" to totalRenderNanos / 1_000_000.0,
            "elapsedMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "peakObservedUsedHeapBytes" to peakObservedHeap,
            "memoryMeasurement" to "JVM used-heap observations before and after renders, excluding native memory; not peak RSS or allocation count.",
            "clips" to clips.map { it.evidence },
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(root, "manifest.json").writeText(manifestText)
        writePage(root, clips, manifestText)
        val coverage = if (quick) "This quick preview covers six middle-register defaults, every factory preset, the connected/receivers-off comparison and one held chamber. Regenerate without -PtesseraQuick for the complete evidence library."
            else "The complete library covers all six voices at C3/C4/C5, every factory preset, three velocities, five macro values on every voice, five required 3×3 interaction grids, raw/matched pairs, causal switches, source taps, isolated tuning sources, all-high extremes and default/difficult held loops."
        File(root, "README.md").writeText("""
            # Tessera audition

            Open index.html in a browser. All assets are relative; no external fonts, scripts or
            samples are required. Audio starts off. Enable playback, then choose Play. A single
            clip plays at a time; Pause/Resume preserve position, and Stop all or Escape resets it.
            Volume starts at 85%. Every card includes native controls and WAV/trace downloads.
            Voice, section, listening-level and text filters support reading and comparing.

            Rebuild with ./gradlew :synth:generateTesseraAudition from the repository root.
            This set contains ${clips.size} dry mono PCM-16 WAV clips at 44.1 kHz.
            $coverage

            Full matched clips use loudest-200-ms RMS 0.12 with a 0.90 peak ceiling. Source taps
            inherit their connected reference's gain, preserving balance; raw partners preserve
            synthesis amplitude. The manifest records recipe, options, peak, loudness, render
            cost, event counts, wall work, passive energy, collector budget, loop seam and hashes.
            Per-clip traces record hammer contacts and reserved rebound energy, path arrivals, pressure-collector releases and slow chamber geometry states.
            Used-heap measurements are JVM observations, not process peak RSS or allocation counts.

            This is an invented musical DSP model inspired by material instruments and changing
            space, not validated chamber physics. Descriptions describe intended behavior;
            numerical checks do not replace the owner's listening verdict. Sonic acceptance is pending.
        """.trimIndent() + "\n")
        writeHoldNotes(root)
        println("wrote ${clips.size} clips, traces, manifest and accessible index.html under ${root.absolutePath}")
        println("render time ${number(totalRenderNanos / 1_000_000_000.0)} s; observed JVM used heap ${number(peakObservedHeap / 1048576.0)} MiB")
    }

    private fun writeHoldNotes(root: File) {
        val notes = File(root, "README.md")
        val heading = "## HOLD playback"
        val existing = notes.takeIf { it.isFile }?.readText().orEmpty()
        notes.writeText(existing.substringBefore("\n$heading").trimEnd() + "\n\n$heading\n\n$holdPlaybackNotes\n")
    }

    private fun writePage(root: File, clips: List<Clip>, manifestText: String) {
        val template = javaClass.getResourceAsStream("/audition/tessera-audition.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("TESSERA audition resource missing")
        require(template.contains("<!-- TESSERA_CARDS -->") && template.contains("<!-- TESSERA_MANIFEST -->"))
        File(root, "index.html").writeText(template.replace("<!-- TESSERA_CARDS -->", sectionsHtml(clips))
            .replace("<!-- TESSERA_MANIFEST -->", "<script type=\"application/json\" id=\"tessera-manifest\">${manifestText.replace("<", "\\u003c")}</script>"))
    }
    private fun sectionsHtml(clips: List<Clip>): String = buildString {
        for ((category, copy) in categories) {
            if (clips.none { it.category == category }) continue
            append("<section class=\"audition-section\" id=\"$category\" aria-labelledby=\"heading-$category\"><div class=\"section-heading\"><p class=\"eyebrow\">${h(category)}</p><h2 id=\"heading-$category\">${h(copy.first)}</h2><p>${h(copy.second)}</p></div>")
            for ((group, members) in clips.filter { it.category == category }.groupBy { it.group }) {
                append("<div class=\"clip-group\"><h3>${h(group)}</h3><div class=\"clip-grid\">")
                members.forEach { append(clipHtml(it)) }
                append("</div></div>")
            }
            append("</section>")
        }
    }
    private fun clipHtml(clip: Clip): String {
        val e = clip.evidence
        val title = e.getValue("title").toString()
        val description = e.getValue("description").toString()
        val voice = e.getValue("voice").toString()
        val level = e.getValue("level").toString()
        val duration = number((e.getValue("durationSeconds") as Number).toDouble())
        val loop = e.getValue("loop") as Boolean
        val macros = (e.getValue("macros") as Map<*, *>).entries.joinToString(" · ") { "${it.key} ${fmt((it.value as Number).toFloat())}" }
        val first = (e["firstCollectorSeconds"] as? Number)?.let { "first reply ${number(it.toDouble())} s" } ?: "no collector releases"
        val seam = (e["seamError"] as? Number)?.let { "<dt>Loop seam error</dt><dd>${scientific(it.toDouble())}</dd><dt>Loop state error</dt><dd>${scientific((e.getValue("loopStateError") as Number).toDouble())}</dd><dt>Loop convergence</dt><dd>${if (e["loopConverged"] == true) "passed" else "pending"}</dd>" }.orEmpty()
        return """
            <article class="clip" id="clip-${clip.id}" data-voice="$voice" data-category="${clip.category}" data-level="${h(level)}" aria-labelledby="title-${clip.id}">
              <div class="clip-badges"><span>${h(voice)}</span><span>${h(level)}</span>${if (loop) "<span>loop</span>" else ""}</div>
              <h4 id="title-${clip.id}">${h(title)}</h4><p class="description" id="desc-${clip.id}">${h(description)}</p>
              <p class="macro-line">${h(macros)}</p><p class="clip-meta">$duration s · ${e.getValue("arrivalCount")} arrivals · ${e.getValue("collectorCount")} replies · $first</p>
              <div class="clip-actions"><button class="play" type="button" aria-label="Play ${h(title)}" aria-describedby="desc-${clip.id}" aria-pressed="false" disabled>Play</button>${if (loop) "<label class=\"repeat\"><input type=\"checkbox\" class=\"loop-toggle\" checked disabled> Repeat</label>" else ""}<a class="download" href="${e.getValue("path")}" download>WAV <span class="sr-only">${h(title)}</span></a></div>
              <progress value="0" max="$duration" aria-label="Playback progress for ${h(title)}"></progress>
              <details class="evidence"><summary>Measurements and causal trace</summary><dl><dt>Raw peak</dt><dd>${number((e.getValue("rawPeak") as Number).toDouble())}</dd><dt>Export peak</dt><dd>${number((e.getValue("exportPeak") as Number).toDouble())}</dd><dt>Raw loudness</dt><dd>${number((e.getValue("rawLoudness") as Number).toDouble())}</dd><dt>Export loudness</dt><dd>${number((e.getValue("exportLoudness") as Number).toDouble())}</dd><dt>Final passive energy</dt><dd>${scientific((e.getValue("finalPassiveEnergy") as Number).toDouble())}</dd><dt>Primary work / budget</dt><dd>${scientific((e.getValue("primaryWork") as Number).toDouble())} / ${scientific((e.getValue("primaryWorkBudget") as Number).toDouble())}</dd><dt>Wall work / budget</dt><dd>${scientific((e.getValue("wallWork") as Number).toDouble())} / ${scientific((e.getValue("wallWorkBudget") as Number).toDouble())}</dd><dt>Remaining collector budget</dt><dd>${scientific((e.getValue("remainingCollectorBudget") as Number).toDouble())}</dd>$seam</dl><a href="${e.getValue("eventPath")}" download>Download causal trace <span class="sr-only">for ${h(title)}</span></a></details>
              <details class="native"><summary>Native audio controls</summary><p class="native-hint" hidden>Enable audio playback above to show these controls.</p><audio controls${if (loop) " loop" else ""} preload="none" src="${e.getValue("path")}" aria-label="${h(title)}" aria-describedby="desc-${clip.id}">Download the WAV above to listen.</audio></details>
            </article>
        """.trimIndent()
    }
    private fun listeningLevel(snip: Snip): Snip {
        val samples = snip.samples.copyOf()
        Dsp.levelTo(samples, snip.sampleRate, target = LISTENING_TARGET, ceiling = LISTENING_CEILING, channels = 1)
        return Snip(samples, 1, snip.sampleRate)
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "non-finite evidence: $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported evidence ${value.javaClass}")
    }
    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> value.items.map { fromJson(it) }
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun macroLine(macros: Map<String, Float>) = macros.entries.joinToString(", ") { "${it.key} ${fmt(it.value)}" }
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun scientific(value: Double) = h(String.format(Locale.ROOT, "%.3g", value))
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun h(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
