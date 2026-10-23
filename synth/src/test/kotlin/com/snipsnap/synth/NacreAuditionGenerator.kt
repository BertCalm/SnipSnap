package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Finite two-harp listening evidence. The page is maintained separately from the renderer. */
object NacreAuditionGenerator {
    private const val RATE = 44_100
    private const val SEED = 7L
    private const val MIDI = 48
    private const val TARGET_LOUDNESS = Dsp.MELODIC_LOUDNESS_TARGET
    private const val CEILING = .95f
    private const val ARCHIVE = "nacre-audition.zip"
    private const val WAVS_PER_PART = 24
    private const val HOSTED_FILE_LIMIT = 25L * 1024 * 1024

    private data class Request(
        val id: String, val title: String, val description: String, val section: String,
        val macros: Map<String, Float>, val midi: Int = MIDI, val velocity: Float = 1f,
        val options: Nacre.Options = Nacre.Options(), val component: String = "full",
        val comparisonGroup: String = id, val referenceId: String = id,
        val model: Int = Nacre.MODEL_VERSION,
    )

    // Retain only the selected 44.1 kHz tap, never all internal arrays from every probe.
    private data class Rendered(
        val request: Request, val samples: FloatArray, val events: List<Map<String, Any?>>,
        val renderMilliseconds: Double, val nativeSamplesSha256: String, val engineDiagnostics: Map<String, Double>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one NACRE audition output directory." }
        val repository = repositoryRoot()
        val root = File(args.firstOrNull() ?: "../testkit/nacre-audition").absoluteFile
        require(root.canonicalFile != repository.canonicalFile) { "Use a dedicated output directory." }
        root.mkdirs()
        val started = System.nanoTime()
        // Preserve the published model-1 control map for the original/revised A/B.
        val base = NacreModels.defaults(NacreVoice.PEARL, model = 1) + ("HOLD" to 0f)
        val requests = requests(base)
        require(requests.size == 59 && requests.map { it.id }.distinct().size == requests.size)
        val rendered = mutableListOf<Rendered>()
        var baselineProbe: Nacre.Probe? = null
        val recipeReplays = linkedMapOf<String, Boolean>()

        for (request in requests) {
            val began = System.nanoTime()
            val useBaseline = request.id == "baseline" || request.component in listOf("bass", "upper", "sympathetic", "venue") &&
                request.comparisonGroup == "anatomy"
            val probe = if (useBaseline && baselineProbe != null) baselineProbe!! else NacreModels.inspect(
                voice = NacreVoice.PEARL, macros = request.macros, midi = request.midi,
                velocity = request.velocity, seed = SEED, model = request.model, options = request.options,
            )
            if (request.id == "baseline") baselineProbe = probe
            if (request.id in setOf("baseline", "focus_original", "focus_revised")) {
                val patch = patch(request)
                val restored = NacrePatch.fromJsonText(patch.toJsonText())
                check(restored == patch) { "${request.id}: patch JSON does not round-trip." }
                val replay = restored.render()
                check(replay.samples.size == probe.snip.samples.size && replay.samples.indices.all {
                    replay.samples[it].toRawBits() == probe.snip.samples[it].toRawBits()
                }) { "${request.id}: recipe replay differs from the engine render." }
                recipeReplays[request.id] = true
            }
            val samples = if (request.component == "full") probe.raw else probe.stems.getValue(request.component)
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${request.id}: nonfinite or empty audio." }
            require(probe.snip.sampleRate == RATE && probe.snip.channels == 1) { "Unexpected NACRE audio format." }
            require(probe.diagnostics.values.all { it.isFinite() }) { "${request.id}: nonfinite engine diagnostic." }
            val events = probe.events.map { event -> linkedMapOf<String, Any?>(
                "performer" to event.performer, "stringId" to event.stringId,
                "timeSeconds" to event.timeSeconds, "frequencyHz" to event.frequencyHz,
                "strength" to event.strength, "direction" to event.direction,
            ) }
            rendered += Rendered(request, samples, events, (System.nanoTime() - began) / 1e6, samplesHash(probe.snip.samples), probe.diagnostics)
            println("NACRE ${rendered.size}/${requests.size}: ${request.id}")
        }
        baselineProbe = null

        val timingChecks = verifyLockedTiming(rendered)
        val byId = rendered.associateBy { it.request.id }
        val rawGain = minOf(1f, CEILING / rendered.maxOf { peak(it.samples) }.coerceAtLeast(1e-9f))
        val gains = rendered.groupBy { it.request.comparisonGroup }.flatMap { (_, members) ->
            if (members.all { it.request.section == "focus" && it.request.component == "full" }) {
                // Compare full-gesture color at equal loudness. One family attenuation guards
                // every normalized endpoint together, preserving equal loudness if peaks limit it.
                val normalized = members.map { member ->
                    member to TARGET_LOUDNESS / Loudness.of(snip(member.samples)).coerceAtLeast(1e-9f)
                }
                val attenuation = minOf(1f, CEILING / normalized.maxOf { (member, gain) -> peak(member.samples) * gain }.coerceAtLeast(1e-9f))
                return@flatMap normalized.map { (member, gain) -> member.request.id to gain * attenuation }
            }
            val reference = byId.getValue(members.first().request.referenceId)
            check(members.all { it.request.referenceId == reference.request.id }) { "Conflicting gain references." }
            val familyPeak = maxOf(peak(reference.samples), members.maxOf { peak(it.samples) })
            val gain = minOf(TARGET_LOUDNESS / Loudness.of(snip(reference.samples)).coerceAtLeast(1e-9f),
                CEILING / familyPeak.coerceAtLeast(1e-9f))
            members.map { it.request.id to gain }
        }.toMap()
        val clips = rendered.map { writeClip(root, it, gains.getValue(it.request.id), rawGain) }
        val focusLoudnessChecks = verifyFocusLoudness(root, clips)
        val downloads = downloads(clips)
        val source = copySources(repository, root)
        val pageTemplate = File(repository, "design/nacre-audition/index.html")
        check(pageTemplate.isFile) { "Missing maintained NACRE audition page: $pageTemplate" }
        pageTemplate.copyTo(File(root, "index.html"), overwrite = true)
        val generatedAt = Instant.now().toString()
        val manifest = linkedMapOf<String, Any?>(
            "format" to "snipsnap-nacre-audition", "version" to 2, "revision" to 2,
            "title" to "Nacre / Tone and motion", "engine" to "NACRE", "voice" to "PEARL",
            "description" to "Original and revised paired harps, followed by four timbral comparisons with identical pluck timing and order. The complete revised listening bench remains available.",
            "model" to Nacre.MODEL_VERSION, "seed" to SEED.toString(), "sampleRate" to RATE,
            "channels" to 1, "bitDepth" to 24, "midi" to MIDI, "macros" to base,
            "hold" to false, "venueModel" to "coral-to-seaweed-prototype", "clipCount" to clips.size,
            "generatedAt" to generatedAt, "download" to downloads.first().getValue("path"),
            "downloads" to downloads, "hostingExcludes" to listOf(ARCHIVE),
            "sections" to sections(), "clips" to clips, "source" to source,
            "gainPolicy" to linkedMapOf(
                "raw" to "One common safe gain across the whole pack; prelevel amplitude differences are preserved.",
                "rawGain" to rawGain, "matched" to "The ten full focus gestures are individually matched to the loudest-200-ms target, then receive one common family attenuation if any normalized endpoint exceeds peak headroom. Other comparison families share their declared reference's gain. Stems are never normalized independently.",
                "focus" to "Equal loudness within each full-gesture focus pair, verified from exported PCM24 WAVs. This removes simple level differences from the color comparison; raw mode retains natural excitation differences.",
                "targetLoudness" to TARGET_LOUDNESS, "peakCeiling" to CEILING,
                "native" to "These two listening modes derive from the engine's prelevel 44.1 kHz taps. Production patch playback uses the engine's normal conditioning and leveling.",
            ),
            "scope" to listOf("One PEARL performer profile", "Finite one-shot gestures; HOLD remains zero",
                "VENUE spans coral to seaweed only; conch and cave are deferred", "No venue feedback into the active strings"),
            "diagnostics" to linkedMapOf(
                "baselineRecipeReplayVerified" to (recipeReplays["baseline"] == true),
                "recipeReplaysVerified" to recipeReplays, "lockedTimingChecks" to timingChecks,
                "focusLoudnessChecks" to focusLoudnessChecks,
                "acceptance" to "Awaiting owner listening. Numeric measurements do not establish musical quality.",
                "pitchNote" to "A local autocorrelation estimate after the last pluck, not a pitch-lock guarantee. Isolated upper and sympathetic taps can be an octave or more above the root. The detector's lower bound is 40 Hz; MIDI 28 (E1, about 41.2 Hz) lies close to that boundary.",
                "classifierNote" to "The generic SnipSnap classifier labels sounds longer than 1.5 seconds LOOP by duration. These NACRE clips are finite gestures. onsetClassifier measures the first 750 ms separately.",
                "probeNote" to "Stem selection and mute/coupling/current/venue/timing-lock options are audition diagnostics, stored beside each recipe. They are not production patch fields; replaying a recipe produces the full ordinary engine gesture.",
                "energyTimingNote" to "energy90Seconds and energy99Seconds mark where cumulative squared sample energy reaches 90% and 99%, measured from the start of the file. They describe signal duration, not a perceptual decay threshold.",
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        writeMetrics(root, clips)
        File(root, "README.txt").writeText(readme(rawGain, recipeReplays))
        writeDownloadParts(root, downloads, clips)
        writeZip(root, ARCHIVE, clips.flatMap { listOf(it.getValue("audio").toString(), it.getValue("rawAudio").toString()) }.toSet())
        println("NACRE wrote ${clips.size} clips, PCM24 raw/matched WAVs, recipes, event traces, source snapshot and $ARCHIVE to $root")
    }

    private fun requests(base: Map<String, Float>): List<Request> = buildList {
        fun addCase(id: String, title: String, description: String, section: String,
                    macros: Map<String, Float> = base, midi: Int = MIDI, velocity: Float = 1f,
                    options: Nacre.Options = Nacre.Options(), component: String = "full",
                    group: String = id, reference: String = id, model: Int = Nacre.MODEL_VERSION) {
            add(Request(id, title, description, section, macros, midi, velocity, options, component, group, reference, model))
        }
        addCase("focus_original", "Original / model 1", "The published original synthesis, with its original PEARL controls: TOUCH .30, DUET .15, CURRENT .20, VENUE .15, BLOOM .35. Timing is not locked. Compare with the revised model at exactly the same controls.",
            "focus", group = "focus_model", reference = "focus_revised", model = 1)
        addCase("focus_revised", "Revised / model 2", "The revised harmonic plucks, longer resonant strings, current response and venue paths at exactly the original controls. Timing is not locked. This full-gesture pair is matched to equal loudness.",
            "focus", group = "focus_model", reference = "focus_revised")
        val locked = Nacre.Options(lockStrumTiming = true)
        val touchContext = base + mapOf("DUET" to .15f, "CURRENT" to .35f, "VENUE" to 0f, "BLOOM" to .45f)
        for (value in listOf(0f, 1f)) addCase("focus_touch_${tag(value)}", "TOUCH / ${fmt(value)}",
            "Change only TOUCH from 0 to 1: pick spectrum, effort and string participation. DUET .15, CURRENT .35, coral VENUE 0, BLOOM .45. Both clips use the same .30-TOUCH pluck times and order; effort and tone can change.",
            "focus", touchContext + ("TOUCH" to value), options = locked, group = "focus_touch", reference = "focus_touch_100")
        val currentContext = base + mapOf("TOUCH" to .5f, "DUET" to .15f, "VENUE" to 1f, "BLOOM" to .6f)
        for (value in listOf(0f, .7f)) addCase("focus_current_${tag(value)}", "CURRENT / ${fmt(value)}",
            "Change only CURRENT from still 0 to .70, with TOUCH .50, DUET .15, seaweed VENUE 1 and BLOOM .60. The current can alter string evolution, body excitation and transmission; pluck times and order are identical.",
            "focus", currentContext + ("CURRENT" to value), options = locked, group = "focus_current", reference = "focus_current_070")
        val venueContext = base + mapOf("TOUCH" to .5f, "DUET" to .15f, "CURRENT" to .5f, "BLOOM" to .7f)
        for (value in listOf(0f, 1f)) addCase("focus_venue_${tag(value)}", if (value == 0f) "VENUE / coral" else "VENUE / seaweed",
            "Change only VENUE from coral 0 to seaweed 1, with TOUCH .50, DUET .15, CURRENT .50 and BLOOM .70. The same pluck times and order feed the two prototype mechanisms. Conch and cave remain deferred.",
            "focus", venueContext + ("VENUE" to value), options = locked, group = "focus_venue", reference = "focus_venue_000")
        val bloomContext = base + mapOf("TOUCH" to .5f, "DUET" to .15f, "CURRENT" to .35f, "VENUE" to 0f)
        for (value in listOf(0f, 1f)) addCase("focus_bloom_${tag(value)}", "BLOOM / ${fmt(value)}",
            "Change only BLOOM from 0 to 1: sympathetic excitation, selected decays and venue contribution. TOUCH .50, DUET .15, CURRENT .35, coral VENUE 0. Pluck times and order are identical, and the full gestures are matched to equal loudness.",
            "focus", bloomContext + ("BLOOM" to value), options = locked, group = "focus_bloom", reference = "focus_bloom_100")
        addCase("baseline", "The paired gesture", "Both harps, their sympathetic strings and the prototype venue together. PEARL defaults, C3, fixed seed 7.", "anatomy", group = "anatomy", reference = "baseline")
        for ((component, title, description) in listOf(
            Triple("bass", "Bass harp alone", "The bass direct/body stem from the complete baseline, with its original event schedule and playback gain."),
            Triple("upper", "Upper harp alone", "The upper direct/body stem from the complete baseline, one octave above the bass, at its actual mix balance."),
            Triple("sympathetic", "Sympathetic strings", "Unplucked sympathetic resonators from both harps. This quiet diagnostic stem retains the complete gesture's gain."),
            Triple("venue", "Venue response", "The prototype venue's returned contribution alone, with no independent normalization."),
        )) addCase(if (component in listOf("bass", "upper")) "${component}_solo" else "${component}_stem",
            title, description, "anatomy", component = component, group = "anatomy", reference = "baseline")
        for ((id, value, title) in listOf(Triple("close", 0f, "Close together"), Triple("answer", .5f, "An answering offset"), Triple("crossing", 1f, "Crossing strums"))) {
            addCase("duet_$id", title, "DUET ${fmt(value)} changes the scheduled relation between individual string plucks. Other controls, pitch and seed stay fixed.", "duet", base + ("DUET" to value), group = "duet", reference = "duet_close")
        }
        addCase("current_still", "Still water", "CURRENT zero freezes the field at a stable alignment while the venue remains present.", "current", base + ("CURRENT" to 0f), group = "current", reference = "current_moving")
        addCase("current_moving", "Moving current", "CURRENT .70 changes internal damping and sympathetic transfer as well as the venue paths.", "current", base + ("CURRENT" to .7f), group = "current", reference = "current_moving")
        addCase("current_post_only", "Post-output current probe", "Audition ablation: current motion is applied only after the strings, to compare with current acting inside their evolution. The same common gain is retained.", "current", base + ("CURRENT" to .7f), options = Nacre.Options(postCurrentOnly = true), group = "current", reference = "current_moving")
        addCase("internal_still", "Still water / venue bypass", "Audition ablation: venue bypassed, CURRENT zero. Compare with moving current below to expose the instrument-side change.", "current", base + ("CURRENT" to 0f), options = Nacre.Options(bypassVenue = true), group = "current", reference = "current_moving")
        addCase("internal_moving", "Moving current / venue bypass", "Audition ablation: venue bypassed, CURRENT .70. Internal damping and sympathetic transfer retain their current modulation.", "current", base + ("CURRENT" to .7f), options = Nacre.Options(bypassVenue = true), group = "current", reference = "current_moving")
        addCase("venue_coral", "Coral endpoint", "VENUE zero: short local cavity replies. This is an invented acoustic model, not a recording or measured underwater response.", "venue", base + ("VENUE" to 0f), group = "venue", reference = "venue_coral")
        addCase("venue_seaweed", "Seaweed endpoint", "VENUE one: an open transmission path with changing apertures. The prototype interpolates directly from coral to seaweed; conch and cave are deferred.", "venue", base + ("VENUE" to 1f), group = "venue", reference = "venue_coral")
        val sympathy = base + ("BLOOM" to .8f)
        for ((id, disabled) in listOf("on" to false, "off" to true)) {
            val options = Nacre.Options(muteUpperPlucks = true, disableCrossHarp = disabled)
            addCase("sympathy_$id", if (disabled) "Cross-harp transfer disabled" else "Upper plucks muted / transfer on",
                "Upper direct plucks are muted; BLOOM .80. ${if (disabled) "Cross-harp sympathetic transfer is disabled." else "The bass can still awaken the other harp's sympathetic strings."} This pair retains one common gain.",
                "sympathy", sympathy, options = options, group = "sympathy", reference = "sympathy_on")
            addCase("sympathy_${id}_stem", if (disabled) "Sympathetic stem / transfer off" else "Sympathetic stem / transfer on",
                "The corresponding unplucked-resonator stem, isolated at the full comparison's gain. Upper direct plucks remain muted.",
                "sympathy", sympathy, options = options, component = "sympathetic", group = "sympathy", reference = "sympathy_on")
        }
        for ((midi, label) in listOf(28 to "E1 / low boundary", 36 to "C2", 60 to "C4", 72 to "C5 / high boundary")) {
            addCase("pitch_$midi", label, "The same paired gesture at MIDI $midi. The baseline above provides C3 / MIDI 48. Each pitch is matched separately for register comparison.", "pitch", midi = midi)
        }
        addCase("velocity_soft", "Soft gesture", "Velocity .25 changes excitation strength and attack character. Compare with the velocity-1 baseline at the same gain to retain the natural energy difference.", "velocity", velocity = .25f, group = "anatomy", reference = "baseline")
        val controlDescriptions = mapOf(
            "TOUCH" to "Pluck effort, hardness, spectrum and upper-string participation.",
            "DUET" to "Timing, overlap and direction between the two independently scheduled strums.",
            "CURRENT" to "A shared field acting on the instrument and venue; zero is still water.",
            "VENUE" to "Prototype coral-to-seaweed interpolation only. This is not the four-venue map from the full specification.",
            "BLOOM" to "Sympathetic excitation, selected decays and venue participation.",
        )
        for (macro in controlDescriptions.keys) for (value in listOf(0f, .25f, .5f, .75f, 1f)) {
            addCase("sweep_${macro.lowercase(Locale.ROOT)}_${tag(value)}", "$macro / ${fmt(value)}",
                "${controlDescriptions.getValue(macro)} All five steps share the .50 reference's gain; other controls, C3, velocity and seed stay fixed. ${if (macro == "DUET") "DUET changes the pluck schedule." else "Pluck timing and order are locked across this family."}",
                "controls", base + (macro to value), options = Nacre.Options(lockStrumTiming = macro != "DUET"),
                group = "sweep_$macro", reference = "sweep_${macro.lowercase(Locale.ROOT)}_050")
        }
    }

    private fun verifyLockedTiming(rendered: List<Rendered>): List<Map<String, Any?>> =
        rendered.filter { it.request.options.lockStrumTiming }.groupBy { it.request.comparisonGroup }.map { (group, members) ->
            check(members.size >= 2) { "$group: locked timing requires a comparison family." }
            fun signature(member: Rendered) = member.events.map { event ->
                listOf(event["performer"], event["stringId"], event["timeSeconds"], event["frequencyHz"], event["direction"])
            }
            val reference = signature(members.first())
            check(members.all { signature(it) == reference }) { "$group: locked pluck event timing/order differs." }
            linkedMapOf("comparisonGroup" to group, "ids" to members.map { it.request.id },
                "identicalEventTimingAndOrder" to true, "eventCount" to reference.size)
        }

    private fun verifyFocusLoudness(root: File, clips: List<Map<String, Any?>>): List<Map<String, Any?>> =
        clips.filter { it["section"] == "focus" }.groupBy { it["comparisonGroup"] }.map { (group, members) ->
            check(members.size == 2 && members.all { it["component"] == "full" }) { "$group: expected a full-gesture pair." }
            val loudness = members.associate { clip ->
                clip.getValue("id").toString() to Loudness.of(WavReader.read(File(root, clip.getValue("audio").toString())))
            }
            val tolerance = maxOf(1e-6f, loudness.values.max() * .0001f)
            check(loudness.values.max() - loudness.values.min() <= tolerance) { "$group: exported focus WAV loudness differs." }
            linkedMapOf("comparisonGroup" to group, "exportedLoudness" to loudness,
                "equalWithinTolerance" to true, "absoluteTolerance" to tolerance)
        }

    private fun writeClip(root: File, rendered: Rendered, matchedGain: Float, rawGain: Float): Map<String, Any?> {
        val request = rendered.request
        val matchingPolicy = if (request.section == "focus") "equal-loudness-full-gesture" else "shared-reference-gain"
        val matched = snip(FloatArray(rendered.samples.size) { rendered.samples[it] * matchedGain })
        val raw = snip(FloatArray(rendered.samples.size) { rendered.samples[it] * rawGain })
        require(matched.peak() <= CEILING + 1e-6f && raw.peak() <= CEILING + 1e-6f) { "${request.id}: PCM would clip." }
        val audioPath = "audio/${request.id}.wav"
        val rawPath = "audio/${request.id}_raw.wav"
        val recipePath = "recipes/${request.id}.json"
        val eventsPath = "traces/events/${request.id}.csv"
        val diagnosticPath = "diagnostics/${request.id}.json"
        val audioFile = WavWriter.write(File(root, audioPath), matched, WavWriter.BitDepth.PCM_24)
        val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        val recipeFile = File(root, recipePath).apply { parentFile.mkdirs(); writeText(patch(request).toJsonText() + "\n") }
        val eventsFile = File(root, eventsPath).apply {
            parentFile.mkdirs()
            writeText(buildString {
                val keys = listOf("performer", "stringId", "timeSeconds", "frequencyHz", "strength", "direction")
                appendLine(keys.joinToString(","))
                rendered.events.forEach { event -> appendLine(keys.joinToString(",") { csv(event[it]) }) }
            })
        }
        val options = options(request.options)
        val metric = metrics(matched, request.midi, rendered.events)
        val rawMetrics = meters(rendered.samples)
        val diagnostic = linkedMapOf<String, Any?>(
            "id" to request.id, "auditionProbe" to (request.component != "full" || options.values.any { it == true }),
            "model" to request.model,
            "component" to request.component, "renderOptions" to options,
            "recipeReplay" to "The patch reproduces the ordinary full gesture. Apply the explicitly listed audition options and component tap to reproduce this diagnostic.",
            "referenceId" to request.referenceId, "comparisonGroup" to request.comparisonGroup,
            "lockedEventTimingVerified" to request.options.lockStrumTiming,
            "matchingPolicy" to matchingPolicy,
            "matchedGain" to matchedGain, "rawExportGain" to rawGain,
            "rawPrelevelMetrics" to rawMetrics, "matchedMetrics" to metric,
            "renderMilliseconds" to rendered.renderMilliseconds,
            "engine" to rendered.engineDiagnostics,
            "nativeSamplesSha256" to rendered.nativeSamplesSha256,
            "prelevelSamplesSha256" to samplesHash(rendered.samples),
        )
        val diagnosticFile = File(root, diagnosticPath).apply { parentFile.mkdirs(); writeText(Json.write(json(diagnostic)) + "\n") }
        return linkedMapOf(
            "id" to request.id, "title" to request.title, "description" to request.description,
            "section" to request.section, "voice" to "PEARL", "component" to request.component,
            "macros" to request.macros, "midi" to request.midi, "velocity" to request.velocity,
            "seed" to SEED.toString(), "model" to request.model, "hold" to false,
            "options" to options, "referenceId" to request.referenceId, "comparisonGroup" to request.comparisonGroup,
            "lockedEventTimingVerified" to request.options.lockStrumTiming,
            "matchingPolicy" to matchingPolicy, "audio" to audioPath, "rawAudio" to rawPath,
            "recipe" to recipePath, "events" to eventsPath, "diagnostics" to diagnosticPath,
            "durationSeconds" to matched.durationSeconds, "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24,
            "metrics" to metric, "rawMetrics" to rawMetrics, "rawExportMetrics" to meters(raw.samples),
            "gains" to mapOf("matched" to matchedGain, "raw" to rawGain),
            "waveform" to waveform(matched.samples), "waveformKind" to "absolute-peak-per-bin",
            "renderMilliseconds" to rendered.renderMilliseconds,
            "engineDiagnostics" to rendered.engineDiagnostics,
            "hashes" to mapOf("audio" to sha256(audioFile), "rawAudio" to sha256(rawFile),
                "recipe" to sha256(recipeFile), "events" to sha256(eventsFile), "diagnostics" to sha256(diagnosticFile)),
        )
    }

    private fun patch(request: Request) = NacrePatch(request.title, NacreVoice.PEARL, request.macros, request.midi, request.velocity, SEED, request.model)
    private fun snip(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = RATE)
    private fun options(options: Nacre.Options): Map<String, Any?> = linkedMapOf(
        "muteBassPlucks" to options.muteBassPlucks, "muteUpperPlucks" to options.muteUpperPlucks,
        "disableCrossHarp" to options.disableCrossHarp, "postCurrentOnly" to options.postCurrentOnly,
        "bypassVenue" to options.bypassVenue, "lockStrumTiming" to options.lockStrumTiming,
    )

    private fun meters(samples: FloatArray): Map<String, Any?> {
        val mean = samples.sumOf { it.toDouble() } / samples.size
        return linkedMapOf("peak" to peak(samples), "rms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
            "dc" to mean, "loudness" to Loudness.of(snip(samples)), "finite" to samples.all { it.isFinite() })
    }

    private fun metrics(audio: Snip, midi: Int, events: List<Map<String, Any?>>): Map<String, Any?> {
        val duration = audio.durationSeconds.toDouble()
        val lastPluck = events.maxOfOrNull { (it.getValue("timeSeconds") as Number).toDouble() } ?: 0.0
        val pitchStart = (lastPluck + .04).coerceAtMost((duration - .30).coerceAtLeast(0.0))
        val estimate = Pitch.detect(audio, pitchStart.toFloat(), .25f)
        val requestedHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val classification = Classifier.classify(audio)
        val onset = snip(audio.samples.copyOfRange(0, minOf(audio.samples.size, (RATE * .75).toInt())))
        val onsetClass = Classifier.classify(onset)
        val tailFrames = minOf(audio.samples.size, RATE / 5)
        val tail = audio.samples.copyOfRange(audio.samples.size - tailFrames, audio.samples.size)
        return meters(audio.samples) + linkedMapOf(
            "tailRms" to sqrt(tail.sumOf { it.toDouble() * it } / tail.size),
            "energy90Seconds" to energyTime(audio.samples, .90),
            "energy99Seconds" to energyTime(audio.samples, .99),
            "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
            "onsetClassifier" to onsetClass.drumClass.name, "onsetClassifierConfidence" to onsetClass.confidence,
            "pitch" to linkedMapOf("hz" to estimate?.hz, "confidence" to estimate?.confidence,
                "requestedRootHz" to requestedHz, "errorCentsFromRoot" to estimate?.let { 1200 * ln(it.hz / requestedHz) / ln(2.0) },
                "fromSeconds" to pitchStart, "windowSeconds" to .25,
                "fundamentalWithinDetectorRange" to (requestedHz >= 40.0 && requestedHz <= 1200.0)),
        )
    }

    private fun energyTime(samples: FloatArray, fraction: Double): Double? {
        val target = samples.sumOf { it.toDouble() * it } * fraction
        if (target <= 0.0) return null
        var sum = 0.0
        for (i in samples.indices) {
            sum += samples[i].toDouble() * samples[i]
            if (sum >= target) return i.toDouble() / RATE
        }
        return samples.lastIndex.toDouble() / RATE
    }

    private fun sections(): List<Map<String, String>> = listOf(
        Triple("focus", "Tone changes at one strum pattern", "Original versus revised synthesis, then four timbral endpoint pairs. Every timbral pair has exactly the same pluck times and order, verified from its event trace. DUET stays at .15."),
        Triple("anatomy", "Inside the paired gesture", "The full gesture and its four prelevel component taps, all at the same reference gain. Quiet parts remain quiet."),
        Triple("duet", "Two performers", "Tight, answering and crossing schedules for individual string plucks. Read the event traces to see who enters and in which direction."),
        Triple("current", "Does water reach the instrument?", "Still, moving and post-output-only current, followed by venue-bypassed probes. All five retain one common gain."),
        Triple("venue", "Coral and seaweed", "Two invented acoustic endpoints. This milestone interpolates directly between them; the conch and cave mechanisms are deferred."),
        Triple("sympathy", "The unplucked answer", "Upper plucks muted, with cross-harp transfer enabled or disabled. Full mixes and isolated sympathetic taps preserve the same gain."),
        Triple("pitch", "Across the register", "MIDI 28, 36, 60 and 72. The baseline is MIDI 48. Each register is matched separately; the lowest root is near the pitch detector's 40 Hz lower bound."),
        Triple("velocity", "Gesture energy", "A soft .25 strike compared with the velocity-1 baseline, preserving the original amplitude relation."),
        Triple("controls", "Five small sweeps", "Each timbral control at 0, .25, .50, .75 and 1. Each five-step family uses one shared .50-reference gain. Pluck timing is locked except when changing DUET. HOLD remains zero."),
    ).map { (id, title, description) -> linkedMapOf("id" to id, "title" to title, "description" to description) }

    private fun copySources(repository: File, root: File): Map<String, Any?> {
        val paths = mutableListOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Nacre.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/NacreModel1.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/NacreModels.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/NacrePatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/NacrePresets.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreAuditionGenerator.kt",
            "design/nacre-audition/index.html",
        )
        // Include local DSP/serialization dependencies with their original repository paths.
        for (path in listOf("synth/src/main/kotlin/com/snipsnap/synth/Strings.kt", "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt", "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt", "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Snip.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt", "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Features.kt", "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt", "json/src/main/kotlin/com/snipsnap/json/Json.kt",
            "synth/src/test/resources/nacre-model1-reference.json",
            "synth/build.gradle.kts", "LICENSE", "LICENSE.md", "LICENSE.txt")) {
            if (File(repository, path).isFile) paths += path
        }
        val files = paths.distinct().map { path ->
            val original = File(repository, path)
            check(original.isFile) { "Cannot snapshot NACRE source: $path" }
            val destination = "source/$path"
            File(root, destination).apply { parentFile.mkdirs(); original.copyTo(this, overwrite = true) }
            linkedMapOf("repositoryPath" to path, "path" to destination, "sha256" to sha256(original))
        }
        return linkedMapOf(
            "repository" to "https://github.com/BertCalm/SnipSnap", "commit" to git(repository, "rev-parse", "HEAD"),
            "dirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "snapshotRoot" to "source/", "files" to files,
            "note" to "The bundled source hashes identify the actual source at generation. A dirty source tree is expected before the prototype commit; the parent commit alone does not identify the rendered implementation.",
        )
    }

    private fun writeMetrics(root: File, clips: List<Map<String, Any?>>) {
        val keys = listOf("id", "section", "model", "component", "midi", "velocity", "durationSeconds", "peak", "rms", "dc", "loudness", "tailRms", "energy90Seconds", "energy99Seconds", "classifier", "onsetClassifier", "pitchHz", "pitchConfidence", "pitchErrorCents", "rawPeak", "rawRms", "matchedGain", "rawGain")
        File(root, "metrics.csv").writeText(buildString {
            appendLine(keys.joinToString(","))
            for (clip in clips) {
                val metric = clip.getValue("metrics") as Map<*, *>
                val pitch = metric["pitch"] as Map<*, *>
                val raw = clip.getValue("rawMetrics") as Map<*, *>
                val gains = clip.getValue("gains") as Map<*, *>
                val row = clip + metric.entries.associate { it.key.toString() to it.value } + mapOf(
                    "pitchHz" to pitch["hz"], "pitchConfidence" to pitch["confidence"], "pitchErrorCents" to pitch["errorCentsFromRoot"],
                    "rawPeak" to raw["peak"], "rawRms" to raw["rms"], "matchedGain" to gains["matched"], "rawGain" to gains["raw"],
                )
                appendLine(keys.joinToString(",") { csv(row[it]) })
            }
        })
    }

    private fun readme(rawGain: Float, recipeReplays: Map<String, Boolean>) = """
        NACRE / Tone and motion / revision 2
        ====================================
        59 finite mono 44.1 kHz PCM24 clips; one PEARL profile, five timbral controls, HOLD zero.
        The first ten clips compare the published original with the revised engine, then isolate
        TOUCH, CURRENT, VENUE and BLOOM at identical pluck times and order. DUET remains .15.
        Timing equality is verified event-for-event within the four locked tone pairs and the
        four non-DUET sweep families. Event strength may change with touch or current.
        VENUE is a prototype coral-to-seaweed interpolation. Conch, cave and held loops are deferred.
        No listening or musical-quality verdict is asserted by this generator.

        Open index.html through a local web server, or listen to the WAVs directly.
        Focus audio: individually match each full gesture to the shared melodic loudness target,
        then apply one common family attenuation if any normalized endpoint would exceed .95 peak.
        Equal focus-pair loudness is verified from exported PCM24 WAVs. Other mechanism, stem and
        sweep families share their declared reference's gain. Stems are never matched independently.
        Raw audio: prelevel waveform at one common safe gain of $rawGain across the whole pack.
        The two exports do not contain the production output leveler's independent gain changes.

        recipes/*.json are actual NacrePatch files. They reconstruct ordinary full gestures.
        The original recipe saves model 1; the revised recipes save model 2.
        Diagnostic mute/current/venue/timing-lock options and selected stems are separate in manifest.json
        and diagnostics/*.json; they are audition probes, not serializable production patch settings.
        Serialized recipe replay verified sample-for-sample: $recipeReplays.
        All pluck event schedules are in traces/events/*.csv. Summary measurements are in metrics.csv.
        energy90Seconds and energy99Seconds mark 90% and 99% cumulative squared sample energy,
        measured from the file's start. These are signal-duration metrics, not perceptual thresholds.
        Pitch is a local estimate after the last pluck; MIDI 28 is near the detector's 40 Hz lower bound.
        The generic classifier calls >1.5-second files LOOP by duration. These clips are finite one-shots.

        source/ preserves the original/revised engines, dispatcher, generator, patch, maintained page
        and local dependencies at render time. SHA-256 hashes and the parent commit are in manifest.json.
        The committed repository and bundled snapshot together support code review; this snapshot
        is not a standalone build checkout.
        Recreate using the repository's generateNacreAudition task and its normal JDK/Gradle setup.

        Listening and raw downloads are split into numbered ZIP parts of at most 24 WAVs each.
        Every part includes the page, recipes and evidence. Extract every listening part into the
        same directory for the complete listening bench, and every raw part there for raw playback.
        manifest.downloads lists the exact part names and their clip IDs. Each hosted ZIP is checked
        to remain strictly below 25 MiB. The complete nacre-audition.zip is retained locally only,
        omitted from downloads and excluded from hosting. Obsolete unsplit ZIPs are removed.
    """.trimIndent() + "\n"

    private fun downloads(clips: List<Map<String, Any?>>): List<Map<String, Any?>> = buildList {
        val parts = clips.chunked(WAVS_PER_PART)
        for (mode in listOf("listening", "raw")) for ((index, members) in parts.withIndex()) {
            add(linkedMapOf(
                "path" to "nacre-$mode-part-${index + 1}.zip",
                "title" to "${if (mode == "listening") "Listening" else "Raw"} pack / ${index + 1} of ${parts.size}",
                "description" to "${members.size} ${if (mode == "listening") "listening" else "common-gain raw"} WAVs, page, recipes and evidence. Extract all numbered parts into the same folder.",
                "mode" to mode, "part" to index + 1, "partCount" to parts.size,
                "clipCount" to members.size, "clipIds" to members.map { it.getValue("id") },
            ))
        }
    }

    private fun writeDownloadParts(root: File, downloads: List<Map<String, Any?>>, clips: List<Map<String, Any?>>) {
        val byId = clips.associateBy { it.getValue("id").toString() }
        val currentNames = downloads.map { it.getValue("path").toString() }.toSet() + ARCHIVE
        // Only known generator archives are obsolete; unrelated files are not removed.
        root.listFiles()?.filter { file ->
            file.isFile && file.name !in currentNames &&
                (file.name in setOf("nacre-listening.zip", "nacre-raw.zip") ||
                    file.name.matches(Regex("nacre-(listening|raw)-part-[0-9]+\\.zip")))
        }?.forEach { check(it.delete()) { "Cannot remove obsolete NACRE archive: $it" } }
        for (download in downloads) {
            val ids = download.getValue("clipIds") as List<*>
            val kind = if (download["mode"] == "raw") "rawAudio" else "audio"
            val audioPaths = ids.map { byId.getValue(it.toString()).getValue(kind).toString() }.toSet()
            val archive = writeZip(root, download.getValue("path").toString(), audioPaths)
            check(archive.length() < HOSTED_FILE_LIMIT) {
                "Hosted NACRE archive ${archive.name} is ${archive.length()} bytes; must remain below 25 MiB."
            }
        }
    }

    private fun writeZip(root: File, name: String, audioPaths: Set<String>): File {
        val archive = File(root, name)
        val files = root.walkTopDown().filter { file ->
            file.isFile && file.extension != "zip" && !file.name.endsWith(".tmp") &&
                (file.extension != "wav" || file.relativeTo(root).invariantSeparatorsPath in audioPaths)
        }.toList().sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun waveform(samples: FloatArray): List<Float> = (0 until 96).map { bin ->
        val from = bin * samples.size / 96
        val to = (bin + 1) * samples.size / 96
        var value = 0f
        for (i in from until to) value = maxOf(value, abs(samples[i]))
        value
    }
    private fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
    private fun csv(value: Any?): String = value?.toString()?.let { if (it.any { char -> char in ",\"\n\r" }) "\"${it.replace("\"", "\"\"")}\"" else it } ?: ""
    private fun tag(value: Float): String = (value * 100).toInt().toString().padStart(3, '0')
    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("Run inside the SnipSnap checkout.")
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) result else null
    }.getOrNull()
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()
    private fun samplesHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            if (block.remaining() < 4) { digest.update(block.array(), 0, block.position()); block.clear() }
            block.putInt(sample.toRawBits())
        }
        digest.update(block.array(), 0, block.position())
        return digest.digest().hex()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported NACRE manifest value ${value.javaClass.name}")
    }
}
