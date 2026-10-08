package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Nimbus's dry listening evidence: voices, physical velocity, factory recipes, controls,
 * interactions, six persistent identities, causal switches and settled powered HOLD.
 * Render with `./gradlew :synth:generateNimbusAudition`; nothing here claims sonic acceptance.
 * The page retains MURK's accessible transport, with sample-accurate WebAudio HOLD repetition.
 */
object NimbusAuditionGenerator {
    private const val LISTENING_TARGET = .12f
    private const val LISTENING_CEILING = .90f
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val timbral = listOf("EXCITE", "SPACING", "HEIGHT", "FIELD", "FUNNEL")
    private val registers = listOf(0f to "low", .5f to "middle", 1f to "high")
    private val identities = listOf("Body", "Bell", "Paper", "Dark", "Flex", "Wire")
    private val voiceDescriptions = mapOf(
        NimbusVoice.RING to "Clear same-note metal characters with wider neighboring separation.",
        NimbusVoice.SHIMMER to "A softer distributed impulse favors fine upper-mode shimmer.",
        NimbusVoice.GATHER to "A stronger impulse and yielding field favor a spread-and-return gesture.",
        NimbusVoice.THROAT to "Low resting height and a deeper funnel concentrate the darker chamber response.",
        NimbusVoice.CONTACT to "Close spacing permits motion-dependent rim contact around the pitched metal.",
        NimbusVoice.SUSPEND to "Gentler excitation and firm suspension favor a longer supported metallic texture.",
    )
    private val identityDescriptions = listOf(
        "Rounded gong-like weight with a strong principal mode and restrained upper loss.",
        "Sharp central definition with a clear attack and selected bright modes.",
        "Thin shimmer from numerous quiet, shorter upper modes.",
        "Muted metallic wash with strong middle modes and an absorbed top.",
        "Wavering after-strike response from bounded state-dependent flexibility.",
        "A wiry buzzing edge with sparse sharp modes and contact sensitivity.",
    )
    private val macroDescriptions = mapOf(
        "EXCITE" to "Soft distributed magnetic pull becomes harder concentrated contact. Listen to attack, modal balance and stack disturbance at equal playback level.",
        "SPACING" to "Close neighbor loading becomes separated individual rings. Compare transfer, decay and contact; stack height stays independent.",
        "HEIGHT" to "The resting stack moves from the narrow throat toward the open mouth. Compare loading, source balance and early returns at a fixed spacing.",
        "FIELD" to "A yielding suspension becomes firmer restoration and limited coupling. Listen for changed gathering time and sympathetic transfer.",
        "FUNNEL" to "Open shallow loading becomes a deeper tapered enclosure. Compare the returning pressure and tail while height stays fixed.",
    )
    private val presetDescriptions = mapOf(
        "SIX RINGS" to "Clear upper-mode identities around one C4 root, with a relatively open funnel.",
        "THIN CROWN" to "A softer, higher stack with restrained upper-mode shimmer.",
        "DARK PLATE" to "A gentle impulse lower in a deeper chamber, with darker loading.",
        "GATHERING STACK" to "A strong gesture in a yielding close stack; compare its initial spread and restoration.",
        "NARROW THROAT" to "A lower stack and strongly tapered chamber concentrate the returning response.",
        "WIDE MOUTH" to "A high, separated stack emphasizes exposed metal and a shallower enclosure.",
        "SOFT FIELD" to "A compliant field permits a gentler, slower gathering gesture.",
        "RIM KISS" to "Close neighboring rims permit fine contact around the pitched resonance.",
        "HELD METAL" to "Explicit magnetic drive supports a settled repeating metal texture.",
    )
    private data class Clip(val evidence: Map<String, Any?>) {
        val id get() = evidence.getValue("id") as String
        val category get() = evidence.getValue("category") as String
        val group get() = evidence.getValue("group") as String
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1 && args.filter { it.startsWith("--") }.all {
            it == "--quick" || it == "--refresh-page" || it == "--compare-hold" ||
                it.startsWith("--baseline-dir=") || it.startsWith("--baseline-revision=")
        }) { "supply one output directory and optional --quick, --refresh-page, --baseline-dir=, --baseline-revision= or --compare-hold" }
        fun argument(prefix: String): String? = args.filter { it.startsWith(prefix) }.let {
            require(it.size <= 1) { "duplicate $prefix argument" }
            it.singleOrNull()?.removePrefix(prefix)?.also { value -> require(value.isNotBlank()) }
        }
        val root = File(positional.firstOrNull() ?: "../testkit/nimbus-audition")
        val quick = "--quick" in args
        val compareHold = "--compare-hold" in args
        val baselineDir = argument("--baseline-dir=")?.let(::File)
        val baselineRevision = argument("--baseline-revision=")
        require((baselineDir == null) == (baselineRevision == null)) { "baseline directory and full source revision must be supplied together" }
        require(baselineRevision == null || baselineRevision.matches(Regex("[0-9a-f]{40}"))) { "baseline revision must be a full Git commit SHA" }
        require(!compareHold || baselineDir != null && !quick) { "HOLD comparison requires a baseline and the full audition" }
        require(baselineDir == null || baselineDir.canonicalFile != root.canonicalFile) { "preserve the previous audition in a separate baseline directory" }
        if ("--refresh-page" in args) {
            require(!quick && baselineDir == null && !compareHold) { "refresh uses the existing audition manifest and its comparison" }
            val manifestText = File(root, "manifest.json").readText()
            val manifest = Json.parse(manifestText).obj()
            require(manifest.getValue("engine").str() == "NIMBUS")
            val clips = manifest.getValue("clips").arr().map { row ->
                Clip(row.obj().mapValues { fromJson(it.value) }).also { clip ->
                    for (key in listOf("path", "eventPath")) {
                        val asset = File(root, clip.evidence.getValue(key) as String).canonicalFile
                        require(asset.toPath().startsWith(root.canonicalFile.toPath()) && asset.isFile)
                    }
                }
            }
            require(manifest.getValue("clipCount").int() == clips.size && clips.isNotEmpty())
            writePage(root, clips, manifestText)
            println("Refreshed ${clips.size} Nimbus cards; audio and mechanical records unchanged")
            return
        }
        val baselineManifest = baselineDir?.let { Json.parse(File(it, "manifest.json").readText()).obj() }
        if (baselineManifest != null) {
            require(baselineManifest.getValue("engine").str() == "NIMBUS") { "baseline must describe Nimbus" }
            require(baselineManifest.getValue("sourceRevision").str() == baselineRevision) { "baseline source revision differs from the requested SHA" }
            require(!baselineManifest.getValue("workingTreeDirty").bool()) { "baseline must come from a clean source checkout" }
            require(abs(baselineManifest.getValue("listeningTarget").num() - LISTENING_TARGET) < 1e-6 &&
                abs(baselineManifest.getValue("listeningPeakCeiling").num() - LISTENING_CEILING) < 1e-6) {
                "baseline must use the same RMS 0.12 target and 0.90 ceiling"
            }
        }
        root.mkdirs()
        val clips = mutableListOf<Clip>()
        val started = System.nanoTime()
        var totalRenderNanos = 0L
        var peakObservedHeap = 0L
        fun observeHeap() {
            val runtime = Runtime.getRuntime()
            peakObservedHeap = maxOf(peakObservedHeap, runtime.totalMemory() - runtime.freeMemory())
        }

        fun render(
            id: String, title: String, description: String, category: String, group: String,
            voice: NimbusVoice = NimbusVoice.GATHER,
            macros: Map<String, Float> = Nimbus.defaults(voice),
            energy: Float = 1f,
            options: Nimbus.ProbeOptions = Nimbus.ProbeOptions(),
            branch: String = "full", matched: Boolean = true, existing: Nimbus.Probe? = null,
        ): Nimbus.Probe {
            require(clips.none { it.id == id }) { "duplicate Nimbus audition ID: $id" }
            observeHeap()
            val before = System.nanoTime()
            val probe = existing ?: Nimbus.probe(voice, macros, energy = energy, options = options)
            val renderNanos = if (existing == null) System.nanoTime() - before else 0L
            totalRenderNanos += renderNanos
            observeHeap()
            val samples = when {
                branch.startsWith("cymbal-") -> probe.cymbals[branch.substringAfter('-').toInt()]
                branch == "funnel" -> probe.funnel
                branch == "contact" -> probe.contact
                branch == "returns" -> probe.returns
                else -> probe.samples
            }
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "$id has invalid audio" }
            val raw = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
            val rawPeak = raw.peak()
            val rawLoudness = Loudness.of(raw)
            val reference = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            val gain = if (matched) {
                val referenceLoudness = Loudness.of(reference)
                if (referenceLoudness <= 1e-6f) 1f else minOf(
                    LISTENING_TARGET / referenceLoudness,
                    LISTENING_CEILING / reference.peak().coerceAtLeast(1e-9f),
                )
            } else 1f
            // An export-only reduction is recorded if a raw diagnostic exceeds PCM range.
            // Synthesis peak and energy remain unscaled evidence; no clip is silently saturated.
            val exportSafetyGain = minOf(1f, (if (matched) LISTENING_CEILING else .99f) /
                (rawPeak * gain).coerceAtLeast(1e-9f))
            val output = Snip(FloatArray(samples.size) { samples[it] * gain * exportSafetyGain },
                channels = 1, sampleRate = Dsp.RATE)
            val path = "clips/$id${if (matched) "_listen_012" else "_raw"}.wav"
            WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_16)
            val loop = Nimbus.isLoop(macros.getValue("HOLD"))
            val eventPath = "mechanics/$id.json"
            val optionsEvidence = linkedMapOf<String, Any?>(
                "normalize" to options.normalize, "couplingEnabled" to options.couplingEnabled,
                "funnelEnabled" to options.funnelEnabled, "contactsEnabled" to options.contactsEnabled,
                "primaryStrikeEnabled" to options.primaryStrikeEnabled, "selectedCymbal" to options.selectedCymbal,
                "durationSeconds" to options.durationSeconds, "seedContext" to options.seedContext,
                "recordDiagnostics" to options.recordDiagnostics, "controlStride" to options.controlStride,
                "poweredDriveEnabled" to options.poweredDriveEnabled,
            )
            val mechanics = linkedMapOf<String, Any?>(
                "id" to id, "voice" to voice.name, "macros" to macros, "energy" to energy,
                "options" to optionsEvidence, "rootHz" to probe.rootHz,
                "identities" to identities, "modeRatios" to probe.modeRatios.map { it.toList() },
                "initialEnergy" to probe.initialEnergy, "finalPassiveEnergy" to probe.finalPassiveEnergy,
                "contactEvents" to probe.contactEvents, "peakContactPenetration" to probe.peakContactPenetration,
                "poweredDriveWork" to probe.poweredDriveWork,
                "snapshots" to probe.snapshots.map { state -> linkedMapOf(
                    "timeSeconds" to state.timeSeconds, "heights" to state.heights.toList(),
                    "restingHeights" to state.restingHeights.toList(), "velocities" to state.velocities.toList(),
                    "modalEnergy" to state.modalEnergy, "passiveEnergy" to state.passiveEnergy,
                    "mechanicalEnergy" to state.mechanicalEnergy, "controllerWork" to state.controllerWork,
                    "contactPenetration" to state.contactPenetration, "geometryValid" to state.geometryValid,
                ) },
            )
            File(root, eventPath).apply { parentFile.mkdirs(); writeText(Json.write(json(mechanics)) + "\n") }
            clips += Clip(linkedMapOf(
                "id" to id, "title" to title, "description" to description, "category" to category,
                "voice" to voice.name, "group" to group, "path" to path, "eventPath" to eventPath,
                "macros" to macros, "energy" to energy, "branch" to branch,
                "level" to if (!matched) "raw" else if (branch == "full") "matched" else "reference gain",
                "loop" to loop, "durationSeconds" to output.durationSeconds, "sampleRate" to Dsp.RATE,
                "requestedMidi" to Nimbus.midiFor(voice, macros.getValue("TUNE")), "rootHz" to probe.rootHz,
                "rawPeak" to rawPeak, "exportPeak" to output.peak(), "rawLoudness" to rawLoudness,
                "exportLoudness" to Loudness.of(output), "referenceGain" to gain,
                "exportSafetyGain" to exportSafetyGain,
                "rawRms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
                "rawDc" to samples.sumOf { it.toDouble() } / samples.size,
                "renderMilliseconds" to renderNanos / 1_000_000.0,
                "initialEnergy" to probe.initialEnergy, "finalPassiveEnergy" to probe.finalPassiveEnergy,
                "contactEvents" to probe.contactEvents, "peakContactPenetration" to probe.peakContactPenetration,
                "poweredDriveWork" to probe.poweredDriveWork,
                "geometryValid" to probe.snapshots.all { it.geometryValid },
                "peakControllerWork" to probe.snapshots.maxOfOrNull { abs(it.controllerWork) },
                "seamError" to if (loop) probe.seamError else null,
                "options" to optionsEvidence,
            ))
            if (clips.size % 25 == 0) println("Rendered ${clips.size} Nimbus audition clips")
            return probe
        }

        for (voice in NimbusVoice.entries) {
            for ((tune, register) in if (quick) listOf(.5f to "middle") else registers) {
                val note = noteName(Nimbus.midiFor(voice, tune))
                render("${voice.name.lowercase()}_$register", "${voice.name} · $note",
                    voiceDescriptions.getValue(voice) + " Strong event at $note. All six cymbal identities remain in the stack; compare root recognition across registers.",
                    "voices", voice.name, voice, Nimbus.defaults(voice) + ("TUNE" to tune))
            }
        }
        if (!quick) {
            for (preset in NimbusPresets.all()) {
                val held = Nimbus.isLoop(preset.macros.getValue("HOLD"))
                render("preset_" + preset.name.lowercase().replace(' ', '_'), "${preset.name} · C4",
                    presetDescriptions.getValue(preset.name) + if (held)
                        " This is a settled powered loop; the initial isolated attack is excluded. Repeat begins on after you request playback."
                    else " This is the exact dry factory recipe, including all six identities.",
                    "presets", preset.voice.name, preset.voice, preset.macros)
            }
            for (voice in NimbusVoice.entries) for ((tune, _) in registers) {
                val note = noteName(Nimbus.midiFor(voice, tune))
                for ((energy, label) in listOf(.3f to "quiet", .65f to "medium")) {
                    render("${voice.name.lowercase()}_energy_${tag(tune)}_${tag(energy)}", "${voice.name} · $note · $label event",
                        "Host velocity ${fmt(energy)} changes modal excitation and transient stack displacement while EXCITE keeps its contact character. Compare this matched clip with the strong $note voice card; this is a physical event change rather than output attenuation.",
                        "energy", "${voice.name} · $note", voice, Nimbus.defaults(voice) + ("TUNE" to tune), energy)
                }
            }
            for (voice in NimbusVoice.entries) {
                val neutral = Nimbus.macrosFor(voice).associate { it.name to it.neutral }
                for (macro in timbral) for (step in steps) {
                    render("${voice.name.lowercase()}_${macro.lowercase()}_${tag(step)}", "${voice.name} · $macro ${fmt(step)}",
                        macroDescriptions.getValue(macro) + " All companions use declared neutral values. This three-second comparison includes the attack and gathering; full-tail voice and preset cards provide the longer ringdown.",
                        "sweeps", "${voice.name} · $macro", voice, neutral + (macro to step),
                        options = Nimbus.ProbeOptions(durationSeconds = 3f))
                }
            }
            val neutral = Nimbus.macrosFor(NimbusVoice.GATHER).associate { it.name to it.neutral }
            val interactions = listOf("EXCITE" to "FIELD", "SPACING" to "FIELD", "HEIGHT" to "FUNNEL",
                "SPACING" to "FUNNEL", "EXCITE" to "SPACING")
            for ((a, b) in interactions) for (va in listOf(0f, .5f, 1f)) for (vb in listOf(0f, .5f, 1f)) {
                render("grid_${a.lowercase()}_${tag(va)}_${b.lowercase()}_${tag(vb)}", "$a ${fmt(va)} × $b ${fmt(vb)}",
                    "GATHER interaction grid at C4. Compare the row and column for changed attack, sympathetic transfer, gathering and loading. Other controls use declared neutral values; height and spacing should remain distinct. Three-second comparison.",
                    "grids", "$a × $b", macros = neutral + (a to va) + (b to vb),
                    options = Nimbus.ProbeOptions(durationSeconds = 3f))
            }
            for (voice in NimbusVoice.entries) {
                val raw = render("${voice.name.lowercase()}_raw", "${voice.name} · raw synthesis",
                    "Dry synthesis before melodic loudness targeting or audition gain. The manifest records any export-only PCM safety reduction; compare the matched partner's quiet detail and balance.",
                    "levels", voice.name, voice, matched = false)
                render("${voice.name.lowercase()}_matched", "${voice.name} · matched synthesis",
                    "The exact same simulation as the raw partner. Linear listening gain targets loudest-200-ms RMS 0.12 with a 0.90 peak ceiling; there are no effects or added layers.",
                    "levels", voice.name, voice, existing = raw)
            }
            for ((index, identity) in identities.withIndex()) {
                render("identity_${identity.lowercase()}", "$identity · isolated C4",
                    identityDescriptions[index] + " One selected cymbal receives the impulse, with neighbor coupling, funnel and rim contacts disabled. Its own full reference sets listening gain; compare all six for a shared C4 root and distinct spectra/envelopes.",
                    "diagnostics", "Six isolated identities", NimbusVoice.RING,
                    Nimbus.defaults(NimbusVoice.RING),
                    options = Nimbus.ProbeOptions(couplingEnabled = false, funnelEnabled = false,
                        contactsEnabled = false, selectedCymbal = index), branch = "cymbal-$index")
            }
            val diagnostic = Nimbus.defaults(NimbusVoice.GATHER) + mapOf(
                "EXCITE" to .8f, "SPACING" to .15f, "HEIGHT" to .3f, "FIELD" to .35f, "FUNNEL" to .75f)
            val full = render("diagnostic_full", "Connected six-cymbal stack",
                "Reference for the causal switches and source taps. Selected excitation, reciprocal neighbor transfer, height-dependent funnel loading and optional rim reactions form one shared finite instrument.",
                "diagnostics", "Causal switches", macros = diagnostic)
            render("diagnostic_coupling_off", "Neighbor coupling off",
                "The same event and controller retain their geometry, but reciprocal neighbor transfer is disabled. Compare neighboring rings and the late balance with the connected reference.",
                "diagnostics", "Causal switches", macros = diagnostic,
                options = Nimbus.ProbeOptions(couplingEnabled = false))
            render("diagnostic_funnel_off", "Funnel loading and returns off",
                "Direct metal and suspension remain; chamber loading and returned acoustic excitation are disabled. Compare the root, decay and gathered tail with the connected reference.",
                "diagnostics", "Causal switches", macros = diagnostic,
                options = Nimbus.ProbeOptions(funnelEnabled = false))
            render("diagnostic_contacts_off", "Zero collision · rim contacts off",
                "The same close geometry and event render without compliant rim reaction. Compare this with the connected reference and its contact tap; fine sizzling should follow actual contact activity.",
                "diagnostics", "Causal switches", macros = diagnostic,
                options = Nimbus.ProbeOptions(contactsEnabled = false))
            for ((index, identity) in identities.withIndex()) {
                render("stack_${identity.lowercase()}", "$identity · connected stack tap",
                    identityDescriptions[index] + " Extracted from the connected reference's identical simulation. This source inherits full-mix gain, preserving its balance rather than separately raising quiet neighbors.",
                    "diagnostics", "Inside the connected stack", macros = diagnostic,
                    branch = "cymbal-$index", existing = full)
            }
            for ((branch, copy) in listOf(
                "funnel" to "Chamber radiation alone: listen for the enclosure's modes and their decaying response.",
                "returns" to "Returned pressure alone: the funnel's bounded paths feed the same cymbals rather than an unrelated ambience layer.",
                "contact" to "Motion-dependent rim reaction alone: compare contact activity with the zero-collision switch and mechanical record.",
            )) render("diagnostic_$branch", "$branch · isolated source tap",
                "$copy This tap inherits the full reference's listening gain; the manifest records an export-only safety reduction if required.",
                "diagnostics", "Inside the connected stack", macros = diagnostic, branch = branch, existing = full)
            render("diagnostic_passive_ringdown", "Long passive acoustic ringdown",
                "One finite event with powered acoustic drive disabled, observed through the full maximum duration. Levitation may restore geometry, but modal energy and audible output should decay. Inspect passive energy and controller work.",
                "diagnostics", "Passive decay and geometry", macros = diagnostic,
                options = Nimbus.ProbeOptions(durationSeconds = Nimbus.MAX_SECONDS, poweredDriveEnabled = false))
            render("diagnostic_unexcited", "Levitation without acoustic excitation",
                "No initial strike and no powered modal drive. The stack remains geometrically suspended; this deliberately silent control checks that stabilization alone does not manufacture an audible sustain.",
                "diagnostics", "Passive decay and geometry", macros = diagnostic,
                options = Nimbus.ProbeOptions(primaryStrikeEnabled = false, poweredDriveEnabled = false))
            for (voice in NimbusVoice.entries) {
                render("${voice.name.lowercase()}_all_high", "${voice.name} · all timbral controls high",
                    "All five timbral macros at 1, centered TUNE and HOLD off. Compare finite decay and root recognition, then inspect bounded energy and valid geometry in the mechanical record.",
                    "diagnostics", "All-high finite extremes", voice, Nimbus.defaults(voice) + timbral.associateWith { 1f })
            }
            for ((spacing, height) in listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f)) {
                render("geometry_${tag(spacing)}_${tag(height)}", "SPACING ${fmt(spacing)} · HEIGHT ${fmt(height)}",
                    "An endpoint geometry check with hard excitation and a deep funnel. Wide spacing must fit at both height endpoints; close stacks must keep valid plate ordering and bounded contact penetration.",
                    "diagnostics", "Passive decay and geometry", macros = diagnostic + mapOf(
                        "SPACING" to spacing, "HEIGHT" to height, "EXCITE" to 1f, "FUNNEL" to 1f))
            }
        }
        for (voice in NimbusVoice.entries) {
            render("${voice.name.lowercase()}_hold", "${voice.name} · settled HOLD",
                "Explicit weak powered magnetic drive maintains a settled metal texture. The isolated note-on attack is excluded by the host's loop-only contract. Repeat begins on after you enable audio and press Play; compare continuity and pitch over several cycles.",
                "hold", "Default powered sustains", voice, Nimbus.defaults(voice) + ("HOLD" to 1f))
            if (!quick) render("${voice.name.lowercase()}_hold_extreme", "${voice.name} · difficult HOLD",
                "All timbral controls high with HOLD on. Powered energy, suspension and funnel state must remain bounded and the repeated region should retain a stable root without contact or wrap clicks.",
                "hold", "Difficult powered sustains", voice,
                Nimbus.defaults(voice) + timbral.associateWith { 1f } + ("HOLD" to 1f))
        }
        if (!quick) {
            val neutral = Nimbus.macrosFor(NimbusVoice.SUSPEND).associate { it.name to it.neutral }
            for (step in steps) render("suspend_hold_${tag(step)}", "SUSPEND · HOLD ${fmt(step)}",
                if (Nimbus.isLoop(step)) "At the 0.99 loop threshold the host returns settled powered sustain with its initial attack excluded."
                else "Below the 0.99 loop threshold this is a finite event. Compare the supported tail with the settled loop at 1; intermediate values do not imply a browser loop.",
                "hold", "HOLD transition", NimbusVoice.SUSPEND, neutral + ("HOLD" to step))
            render("contact_hold_settled_rims_c3", "CONTACT · C3 · settled rim contact",
                "Close rims, hard excitation and FIELD 0.6 at C3 preserve contact activity in the returned settled cycle. Compare this repeating contact texture with default CONTACT HOLD, which can be nearly collision-free after settling. Inspect the captured-cycle contact count and seam, then listen across repeated boundaries; sonic acceptance remains open.",
                "hold", "Settled rim-contact continuity", NimbusVoice.CONTACT,
                Nimbus.defaults(NimbusVoice.CONTACT) + mapOf("TUNE" to 0f, "EXCITE" to 1f,
                    "SPACING" to 0f, "FIELD" to .6f, "HOLD" to 1f))
            for ((tune, register) in listOf(0f to "low", 1f to "high")) {
                for ((spacing, field) in listOf(0f to 0f, 0f to 1f)) {
                    render("contact_hold_${register}_${tag(field)}", "CONTACT · $register · FIELD ${fmt(field)}",
                        "Close rims, hard excitation, deep funnel and HOLD at a pitch endpoint. Compare repeated contact texture and root stability with compliant and firm fields; inspect seam error and bounded contact energy.",
                        "hold", "Close-contact pitch edges", NimbusVoice.CONTACT,
                        Nimbus.defaults(NimbusVoice.CONTACT) + mapOf("TUNE" to tune, "EXCITE" to 1f,
                            "SPACING" to spacing, "FIELD" to field, "FUNNEL" to 1f, "HOLD" to 1f))
                }
            }
        }
        val comparison = if (baselineDir == null) null else appendComparison(
            root, clips, baselineDir, requireNotNull(baselineRevision), compareHold)
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "NIMBUS", "formatVersion" to 1, "clipCount" to clips.size, "quick" to quick,
            "description" to "Dry listening evidence for the Nimbus port; sonic acceptance remains pending owner listening.",
            "sampleRate" to Dsp.RATE, "bitDepth" to 16, "noteRange" to "C3–C5", "audioStartsOff" to true,
            "listeningTarget" to LISTENING_TARGET, "listeningPeakCeiling" to LISTENING_CEILING,
            "loudness" to "Linear full-clip gain targets loudest-200-ms RMS 0.12 with a 0.90 peak ceiling. Source taps inherit full-reference gain. Raw PCM safety reductions, if needed, are explicit in exportSafetyGain.",
            "velocityContract" to "Host velocity changes finite modal impulse and transient stack displacement within EXCITE's contact character.",
            "defaultPlaybackVolume" to .85f,
            "loopPlayback" to linkedMapOf("repeatByDefault" to true,
                "mainControls" to "Sample-accurate buffer repeat with gain ramps only at playback start and stop.",
                "singlePass" to "Turn Repeat off for one cycle with a smooth exit; WAV boundaries stay unchanged."),
            "sourceRevision" to git("rev-parse", "HEAD"), "workingTreeDirty" to git("status", "--porcelain")?.isNotBlank(),
            "renderMilliseconds" to totalRenderNanos / 1_000_000.0,
            "elapsedMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "peakObservedUsedHeapBytes" to peakObservedHeap,
            "memoryMeasurement" to "JVM used heap sampled before and after renders; excludes native allocation.",
            "comparison" to comparison,
            "clips" to clips.map { it.evidence },
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        prunePreviousAssets(root, clips)
        File(root, "manifest.json").writeText(manifestText)
        writePage(root, clips, manifestText)
        File(root, "README.md").writeText("""
            # Nimbus audition

            Open index.html directly, or serve this directory over HTTP(S). Descriptions and the
            manifest are embedded; all WAVs and mechanical records use local relative paths.
            Audio starts off and only plays after a user request. Stop all or Escape stops playback.

            Begin with RING at C4, then compare the six isolated C4 identities. The voice/velocity
            matrix covers C3, C4 and C5. Every timbral control has five steps on every voice, and
            the five required interactions have 3 × 3 grids. Three-second sweeps and grids compare
            the attack and gathering; default voices and presets preserve the longer ringdown.
            Identity descriptions express intended sound, not a completed listening verdict.

            Matched full clips target loudest-200-ms RMS 0.12, capped at peak 0.90. Source taps
            inherit their reference mix gain. Raw partners retain synthesis amplitude unless an
            explicit exportSafetyGain is required to fit PCM; raw metrics remain unchanged.
            A deliberately silent unexcited diagnostic must stay silent at every playback level.

            HOLD uses the host's settled loop-only format, excluding the note-on attack. HOLD
            cards repeat by default after Enable audio playback and Play. Main controls decode
            one audio buffer and repeat sample-accurately without seeking between cycles. Pause
            and Stop use brief gain ramps; Repeat off plays one cycle with a smooth ending.
            Playback ramps leave the repeated WAV boundary unchanged. Use HTTP(S) for WebAudio
            playback; native/local-file fallback depends on the browser's media player.

            Rebuild: ./gradlew :synth:generateNimbusAudition
            Tooling smoke subset: ./gradlew :synth:generateNimbusAudition -PnimbusQuick=true
            Refresh descriptions/controls only: ./gradlew :synth:generateNimbusAudition -PnimbusRefreshPage=true

            ${if (comparison == null) "" else "A previous/revised listening comparison is included. Original WAVs are copied unchanged from source revision $baselineRevision; the previous manifest and each source WAV/mechanical record are SHA-256 identified. Revised partners reuse the new default renders at the same requested note and event velocity. Each card shows its own recipe, so changed defaults remain visible. The common listening target is RMS 0.12 with a 0.90 peak ceiling; no comparison constitutes a listening verdict."}

            Optional baseline comparison: pass --baseline-dir=/absolute/path/to/preserved-audition
            and --baseline-revision=<full clean source Git SHA> to this generator. Add --compare-hold
            for SHIMMER/SUSPEND C4 HOLD and the difficult settled C3 CONTACT pair. Standard
            generation does not depend on a preserved baseline. To reproduce the original source,
            check out the recorded SHA separately, run its ordinary generateNimbusAudition task,
            and preserve that output directory before rendering the revision elsewhere. Baselines
            may contain only the selected clips, their mechanical records and original manifest rows.

            ${clips.size} dry mono PCM16 WAVs. Factory and macro acceptance remain open for listening.
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} Nimbus WAVs, mechanical records, manifest and listening page under ${root.absolutePath} in " +
            String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9) + " s")
    }

    /** Preserve source PCM byte-for-byte and reuse revised renders, with no extra DSP simulation. */
    private fun appendComparison(
        root: File, clips: MutableList<Clip>, baselineDir: File, revision: String, compareHold: Boolean,
    ): Map<String, Any?> {
        val manifestFile = File(baselineDir, "manifest.json")
        val baseline = Json.parse(manifestFile.readText()).obj()
        val originalManifestHash = baseline["sourceManifestSha256"]?.str()?.also { expected ->
            val original = File(baselineDir, "original-manifest.json")
            require(original.isFile && sha256(original) == expected) { "preserved original manifest differs from its SHA-256" }
        }
        val rows = baseline.getValue("clips").arr().associate { row ->
            row.obj().getValue("id").str() to row.obj()
        }
        val sourceIds = NimbusVoice.entries.map { "${it.name.lowercase()}_middle" } +
            if (!compareHold) emptyList() else listOf("shimmer_hold", "suspend_hold", "contact_hold_settled_rims_c3")
        fun asset(path: String): File = File(baselineDir, path).canonicalFile.also {
            require(it.toPath().startsWith(baselineDir.canonicalFile.toPath()) && it.isFile) {
                "missing or unsafe baseline asset: $path"
            }
        }
        for (sourceId in sourceIds) {
            val source = rows[sourceId] ?: error("baseline has no $sourceId clip")
            val current = clips.singleOrNull { it.id == sourceId } ?: error("revision has no $sourceId render")
            val previous = source.mapValues { fromJson(it.value) }
            val revised = current.evidence
            require(source.getValue("voice").str() == revised.getValue("voice") &&
                source.getValue("branch").str() == "full" && source.getValue("level").str() == "matched") {
                "$sourceId baseline is not the requested matched full voice"
            }
            require(source.getValue("loop").bool() == revised.getValue("loop") &&
                source.getValue("requestedMidi").int() == (revised.getValue("requestedMidi") as Number).toInt()) {
                "$sourceId comparison must use the same requested note and HOLD mode"
            }
            val originalMacros = source.getValue("macros").obj().mapValues { it.value.num().toFloat() }
            val revisedMacros = (revised.getValue("macros") as Map<*, *>).entries.associate {
                it.key.toString() to (it.value as Number).toFloat()
            }
            require(originalMacros == revisedMacros &&
                abs(source.getValue("energy").num() - (revised.getValue("energy") as Number).toDouble()) < 1e-6) {
                "$sourceId comparison requires the same macro recipe and event velocity"
            }
            val previousWav = asset(source.getValue("path").str())
            val previousEvent = asset(source.getValue("eventPath").str())
            val preserved = WavReader.read(previousWav)
            require(preserved.channels == 1 && preserved.sampleRate == Dsp.RATE &&
                preserved.samples.isNotEmpty() && preserved.samples.all { it.isFinite() } && preserved.peak() <= .9001f) {
                "$sourceId baseline must be finite mono 44.1 kHz audio at the listening ceiling"
            }
            require(abs(preserved.peak() - source.getValue("exportPeak").num()) < .0001 &&
                abs(Loudness.of(preserved) - source.getValue("exportLoudness").num()) < .0001) {
                "$sourceId baseline WAV differs from its recorded listening level"
            }
            val voice = revised.getValue("voice").toString()
            val held = revised.getValue("loop") as Boolean
            val note = noteName((revised.getValue("requestedMidi") as Number).toInt())
            val group = "$voice · $note${if (held) " · settled HOLD" else " · default event"}"
            val question = when (voice) {
                "RING" -> "Can you hear distinct metal weight and upper-mode responses around the same note?"
                "SHIMMER" -> "Does the fine upper-mode response and tail give SHIMMER a recognizable character?"
                "GATHER" -> "Does the attack lead to an audible spread and gathering gesture?"
                "THROAT" -> "Does concentrated chamber loading give THROAT a recognizable dark body and tail?"
                "CONTACT" -> "Does the wire and rim response give CONTACT a recognizable character while the requested root remains clear?"
                else -> "Does supported metal have a recognizable texture and decay rather than a uniform bell envelope?"
            }
            for ((version, evidence) in listOf("before" to previous, "revised" to revised)) {
                val id = "comparison_${version}_$sourceId"
                val path = "clips/${id}_listen_012.wav"
                val eventPath = "mechanics/$id.json"
                val wav = if (version == "before") previousWav else File(root, revised.getValue("path") as String)
                val event = if (version == "before") previousEvent else File(root, revised.getValue("eventPath") as String)
                File(root, path).apply { parentFile.mkdirs(); wav.copyTo(this, overwrite = true) }
                val mechanics = Json.parse(event.readText()).obj().toMutableMap().apply {
                    put("id", JsonValue.Str(id))
                    put("comparisonVersion", JsonValue.Str(version))
                    put("comparisonSourceClipId", JsonValue.Str(sourceId))
                    put("comparisonBaselineRevision", JsonValue.Str(revision))
                }
                File(root, eventPath).apply { parentFile.mkdirs(); writeText(Json.write(JsonValue.Obj(mechanics)) + "\n") }
                val provenance = if (version == "before") linkedMapOf<String, Any?>(
                    "baselineSourceRevision" to revision, "baselineSourceClipId" to sourceId,
                    "baselineSourceAudioSha256" to sha256(previousWav), "baselineSourceEventSha256" to sha256(previousEvent),
                    "baselineSourceManifestSha256" to sha256(manifestFile),
                ) else linkedMapOf("currentSourceClipId" to sourceId, "currentSourceAudioSha256" to sha256(wav))
                clips += Clip(evidence + linkedMapOf(
                    "id" to id, "title" to "${if (version == "before") "BEFORE" else "REVISED"} · $voice · $note${if (held) " · HOLD" else ""}",
                    "description" to if (version == "before")
                        "Preserved previous version from ${revision.take(12)}, copied without changing its PCM audio or listening gain. Compare the revised partner at the same note, controls and event velocity. $question"
                    else "Listening revision using the same note, controls and event velocity as the previous partner. $question Compare the attack, body and tail; listening acceptance remains open.",
                    "category" to "comparison", "group" to group, "path" to path, "eventPath" to eventPath,
                    "comparisonVersion" to version, "comparisonBaselineRevision" to revision,
                    "renderMilliseconds" to 0.0,
                ) + provenance)
            }
        }
        return linkedMapOf(
            "baselineSourceRevision" to revision, "baselineManifestSha256" to sha256(manifestFile),
            "baselineOriginalManifestSha256" to originalManifestHash,
            "clipCount" to sourceIds.size * 2, "sourceClipIds" to sourceIds,
            "sameNoteMacrosAndVelocity" to true, "baselineAudioUnchanged" to true,
            "description" to "Preserved BEFORE PCM beside reused REVISED renders at the same requested note, macro recipe and event velocity. Both use the same RMS 0.12 listening target and 0.90 ceiling. Listening verdict remains open.",
        )
    }

    private val categories = linkedMapOf(
        "comparison" to ("Previous and revised: are the voices distinct?" to "The first audition was heard as predominantly bell-like across voices. Compare each preserved previous default with the revised default at the same note and velocity. Does the attack, upper-mode balance, motion and tail now give each voice a useful identity? Both versions use the same listening target; recipes and source hashes remain visible. Listening acceptance is open."),
        "voices" to ("Six voices, three registers" to "Begin with defaults at C3, C4 and C5. Every voice keeps six persistent cymbal identities around the requested root; compare the selected attack and the gathering tail."),
        "presets" to ("Nine factory starting points" to "The exact saved dry recipes in SnipSnap. HELD METAL uses explicit powered sustain; its loop starts after the isolated attack."),
        "energy" to ("Physical velocity across the note range" to "Quiet and medium events at every voice/register complement the strong voice cards. Matched playback exposes modal excitation and stack movement rather than gain alone."),
        "sweeps" to ("Five-step controls on every voice" to "EXCITE, SPACING, HEIGHT, FIELD and FUNNEL each use 0, 0.25, 0.5, 0.75 and 1 with declared neutral companions. Compare three seconds of attack and gathering."),
        "grids" to ("Five required interaction grids" to "GATHER at neutral companions. Each 3 × 3 grid tests EXCITE × FIELD, SPACING × FIELD, HEIGHT × FUNNEL, SPACING × FUNNEL or EXCITE × SPACING."),
        "levels" to ("Raw and matched partners" to "The same dry simulation appears twice: original synthesis amplitude beside linear RMS 0.12 listening gain with a peak ceiling of 0.90. Safety reductions remain explicit in the evidence."),
        "diagnostics" to ("Six identities, shared causes and finite extremes" to "First compare independently excited Body, Bell, Paper, Dark, Flex and Wire at C4. Connected source taps and causal switches then expose neighboring transfer, loading, contact and passive decay. The unexcited control is intentionally silent."),
        "hold" to ("Powered sustain and difficult seams" to "HOLD adds explicit bounded modal drive. Repeat uses one audio buffer with no seek gaps. The main Pause and Stop controls fade smoothly; Repeat off previews one cycle. Inspect seam measurements and listen across repeated boundaries."),
    )
    private fun writePage(root: File, clips: List<Clip>, manifestText: String) {
        val cacheKey = assetCacheKey(manifestText)
        val template = javaClass.getResourceAsStream("/audition/nimbus-audition.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Nimbus listening template is missing")
        require(template.contains("<!-- NIMBUS_CARDS -->") && template.contains("<!-- NIMBUS_MANIFEST -->"))
        File(root, "index.html").writeText(template
            .replace("<!-- NIMBUS_CARDS -->", sectionsHtml(clips, cacheKey))
            .replace("href=\"manifest.json\"", "href=\"${assetUrl("manifest.json", cacheKey)}\"")
            .replace("href=\"README.md\"", "href=\"${assetUrl("README.md", cacheKey)}\"")
            .replace("<!-- NIMBUS_COMPARISON_NOTE -->", if (clips.none { it.category == "comparison" }) "" else "<p class=\"comparison-note\"><strong>Listening revision.</strong> <a href=\"#comparison\">Start with the previous / revised pairs.</a> Compare the six C4 voices for distinct attacks and tails while the requested note stays recognizable. These clips ask for a listening verdict; they do not claim acceptance.</p>")
            .replace("<!-- NIMBUS_COMPARISON_FILTER -->", if (clips.none { it.category == "comparison" }) "" else "<option value=\"comparison\">Previous / revised listening</option>")
            .replace("<!-- NIMBUS_COMPARISON_NAV -->", if (clips.none { it.category == "comparison" }) "" else "<li><a href=\"#comparison\">Previous / revised</a></li>")
            .replace("<!-- NIMBUS_MANIFEST -->", "<script type=\"application/json\" id=\"nimbus-manifest\">${manifestText.replace("<", "\\u003c")}</script>"))
    }
    private fun sectionsHtml(clips: List<Clip>, cacheKey: String): String = buildString {
        for ((category, copy) in categories) {
            val members = clips.filter { it.category == category }
            if (members.isEmpty()) continue
            append("<section class=\"audition-section\" id=\"$category\" aria-labelledby=\"heading-$category\"><div class=\"section-heading\"><p class=\"eyebrow\">${h(category)}</p><h2 id=\"heading-$category\">${h(copy.first)}</h2><p>${h(copy.second)}</p></div>")
            for ((group, rows) in members.groupBy { it.group }) {
                append("<div class=\"clip-group\"><h3>${h(group)}</h3><div class=\"clip-grid\">")
                for (clip in rows) append(clipHtml(clip, cacheKey))
                append("</div></div>")
            }
            append("</section>")
        }
    }
    private fun clipHtml(clip: Clip, cacheKey: String): String {
        val e = clip.evidence
        val title = e.getValue("title") as String
        val voice = e.getValue("voice") as String
        val duration = number((e.getValue("durationSeconds") as Number).toDouble())
        val loop = e.getValue("loop") as Boolean
        val seam = (e["seamError"] as? Number)?.let {
            "<dt>Loop seam error</dt><dd>${scientific(it.toDouble())}</dd>"
        }.orEmpty()
        val macros = (e.getValue("macros") as Map<*, *>).entries.joinToString(" · ") {
            "${it.key} ${fmt((it.value as Number).toFloat())}"
        }
        val version = e["comparisonVersion"] as? String
        val provenance = (e["baselineSourceRevision"] as? String)?.let {
            "<dt>Previous source revision</dt><dd><code>${h(it.take(12))}</code></dd><dt>Preserved WAV SHA-256</dt><dd><code>${h(e.getValue("baselineSourceAudioSha256").toString().take(12))}…</code></dd>"
        }.orEmpty()
        val audioUrl = h(assetUrl(e.getValue("path") as String, cacheKey))
        val mechanicsUrl = h(assetUrl(e.getValue("eventPath") as String, cacheKey))
        return """
            <article class="clip" id="clip-${clip.id}" data-voice="$voice" data-category="${clip.category}" aria-labelledby="title-${clip.id}">
              <div class="clip-badges"><span>${h(voice)}</span><span>${h(e.getValue("level").toString())}</span>${if (version == null) "" else "<span>${h(version)}</span>"}${if (loop) "<span>loop</span>" else ""}</div>
              <h4 id="title-${clip.id}">${h(title)}</h4>
              <p class="description" id="desc-${clip.id}">${h(e.getValue("description") as String)}</p>
              <p class="macro-line">${h(macros)}</p>
              <p class="clip-meta">$duration s · velocity ${fmt((e.getValue("energy") as Number).toFloat())} · root ${number((e.getValue("rootHz") as Number).toDouble())} Hz · ${(e.getValue("contactEvents") as Number).toInt()} contacts</p>
              <div class="clip-actions"><button class="play" type="button" aria-label="Play ${h(title)}" aria-describedby="desc-${clip.id}" aria-pressed="false" disabled>Play</button>${if (loop) "<label class=\"repeat\"><input type=\"checkbox\" class=\"loop-toggle\" checked disabled> Repeat</label>" else ""}<a class="download" href="$audioUrl" download>WAV <span class="sr-only">${h(title)}</span></a></div>
              <progress value="0" max="$duration" aria-label="Playback progress for ${h(title)}"></progress>
              <details class="evidence"><summary>Measurements and mechanical record</summary><dl><dt>Raw peak</dt><dd>${number((e.getValue("rawPeak") as Number).toDouble())}</dd><dt>Export peak</dt><dd>${number((e.getValue("exportPeak") as Number).toDouble())}</dd><dt>Export listening RMS</dt><dd>${number((e.getValue("exportLoudness") as Number).toDouble())}</dd><dt>Export safety gain</dt><dd>${number((e.getValue("exportSafetyGain") as Number).toDouble())}×</dd><dt>Final passive energy</dt><dd>${scientific((e.getValue("finalPassiveEnergy") as Number).toDouble())}</dd><dt>Valid sampled geometry</dt><dd>${e.getValue("geometryValid")}</dd>$seam$provenance</dl><a href="$mechanicsUrl">Mechanical JSON <span class="sr-only">for ${h(title)}</span></a></details>
              <details class="native"><summary>Native audio controls</summary><p class="native-hint" hidden>Enable audio playback above to show these controls.</p><audio controls${if (loop) " loop" else ""} preload="none" src="$audioUrl" aria-label="${h(title)}" aria-describedby="desc-${clip.id}">Your browser can download the WAV above.</audio></details>
            </article>
        """.trimIndent()
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
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
    /** Only delete obsolete assets recorded by a prior Nimbus generation in this directory. */
    private fun prunePreviousAssets(root: File, clips: List<Clip>) {
        val oldManifest = File(root, "manifest.json")
        if (!oldManifest.isFile) return
        val old = runCatching { Json.parse(oldManifest.readText()).obj() }.getOrNull() ?: return
        if (old["engine"]?.str() != "NIMBUS") return
        val retained = clips.flatMap { listOf(it.evidence.getValue("path"), it.evidence.getValue("eventPath")) }.toSet()
        val allowed = Regex("(?:clips/[a-z0-9_]+\\.wav|mechanics/[a-z0-9_]+\\.json)")
        for (row in old.getValue("clips").arr()) for (key in listOf("path", "eventPath")) {
            val path = row.obj()[key]?.str() ?: continue
            if (path in retained || !allowed.matches(path)) continue
            val asset = File(root, path).canonicalFile
            if (asset.toPath().startsWith(root.canonicalFile.toPath()) && asset.isFile)
                check(asset.delete()) { "could not remove obsolete Nimbus asset: $path" }
        }
    }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        output.takeIf { process.waitFor() == 0 }
    }.getOrNull()
    /** URLs change across published revisions; disk paths and evidence hashes stay stable. */
    private fun assetCacheKey(manifestText: String): String {
        val manifest = Json.parse(manifestText).obj()
        val revision = (manifest["sourceRevision"] as? JsonValue.Str)?.value
            ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
        val dirty = (manifest["workingTreeDirty"] as? JsonValue.Bool)?.value
        if (revision != null && dirty == false) return revision
        val digest = sha256(manifestText.toByteArray(Charsets.UTF_8)).take(12)
        return if (revision == null) "dev-$digest" else "$revision-${if (dirty == true) "dirty" else "dev"}-$digest"
    }
    private fun assetUrl(path: String, cacheKey: String) = "$path?v=$cacheKey"
    private fun sha256(file: File): String = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun scientific(value: Double) = String.format(Locale.ROOT, "%.3g", value)
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun h(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
