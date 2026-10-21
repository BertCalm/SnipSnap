package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
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
    private const val LISTENING_ARCHIVE = "nacre-listening.zip"
    private const val RAW_ARCHIVE = "nacre-raw.zip"

    private data class Request(
        val id: String, val title: String, val description: String, val section: String,
        val macros: Map<String, Float>, val midi: Int = MIDI, val velocity: Float = 1f,
        val options: Nacre.Options = Nacre.Options(), val component: String = "full",
        val comparisonGroup: String = id, val referenceId: String = id,
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
        val base = Nacre.defaults(NacreVoice.PEARL) + ("HOLD" to 0f)
        val requests = requests(base)
        require(requests.size == 49 && requests.map { it.id }.distinct().size == requests.size)
        val rendered = mutableListOf<Rendered>()
        var baselineProbe: Nacre.Probe? = null
        var replayVerified = false

        for (request in requests) {
            val began = System.nanoTime()
            val useBaseline = request.id == "baseline" || request.component in listOf("bass", "upper", "sympathetic", "venue") &&
                request.comparisonGroup == "anatomy"
            val probe = if (useBaseline && baselineProbe != null) baselineProbe!! else Nacre.inspect(
                voice = NacreVoice.PEARL, macros = request.macros, midi = request.midi,
                velocity = request.velocity, seed = SEED, options = request.options,
            )
            if (request.id == "baseline") {
                baselineProbe = probe
                val patch = patch(request)
                val restored = NacrePatch.fromJsonText(patch.toJsonText())
                check(restored == patch) { "NACRE baseline patch JSON does not round-trip." }
                val replay = restored.render()
                check(replay.samples.size == probe.snip.samples.size && replay.samples.indices.all {
                    replay.samples[it].toRawBits() == probe.snip.samples[it].toRawBits()
                }) { "NACRE baseline recipe replay differs from the engine render." }
                replayVerified = true
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

        val byId = rendered.associateBy { it.request.id }
        val rawGain = minOf(1f, CEILING / rendered.maxOf { peak(it.samples) }.coerceAtLeast(1e-9f))
        val gains = rendered.groupBy { it.request.comparisonGroup }.mapValues { (_, members) ->
            val reference = byId.getValue(members.first().request.referenceId)
            check(members.all { it.request.referenceId == reference.request.id }) { "Conflicting gain references." }
            val familyPeak = maxOf(peak(reference.samples), members.maxOf { peak(it.samples) })
            minOf(TARGET_LOUDNESS / Loudness.of(snip(reference.samples)).coerceAtLeast(1e-9f),
                CEILING / familyPeak.coerceAtLeast(1e-9f))
        }
        val clips = rendered.map { writeClip(root, it, gains.getValue(it.request.comparisonGroup), rawGain) }
        val source = copySources(repository, root)
        val pageTemplate = File(repository, "design/nacre-audition/index.html")
        check(pageTemplate.isFile) { "Missing maintained NACRE audition page: $pageTemplate" }
        pageTemplate.copyTo(File(root, "index.html"), overwrite = true)
        val generatedAt = Instant.now().toString()
        val manifest = linkedMapOf<String, Any?>(
            "format" to "snipsnap-nacre-audition", "version" to 1,
            "title" to "Nacre / First audition", "engine" to "NACRE", "voice" to "PEARL",
            "description" to "A finite paired-harp prototype: independent octave-separated strums, shared current, sympathetic response, and two venue endpoints.",
            "model" to Nacre.MODEL_VERSION, "seed" to SEED.toString(), "sampleRate" to RATE,
            "channels" to 1, "bitDepth" to 24, "midi" to MIDI, "macros" to base,
            "hold" to false, "venueModel" to "coral-to-seaweed-prototype", "clipCount" to clips.size,
            "generatedAt" to generatedAt, "download" to LISTENING_ARCHIVE,
            "downloads" to listOf(
                mapOf("path" to LISTENING_ARCHIVE, "title" to "Listening pack", "description" to "49 listening WAVs, page, recipes and evidence"),
                mapOf("path" to RAW_ARCHIVE, "title" to "Raw pack", "description" to "49 common-gain raw WAVs; extract into the listening pack"),
            ),
            "sections" to sections(), "clips" to clips, "source" to source,
            "gainPolicy" to linkedMapOf(
                "raw" to "One common safe gain across the whole pack; prelevel amplitude differences are preserved.",
                "rawGain" to rawGain, "matched" to "Each comparison family shares its declared reference's loudest-200-ms target, with a peak guard computed for the entire family. Stems are never normalized independently.",
                "targetLoudness" to TARGET_LOUDNESS, "peakCeiling" to CEILING,
                "native" to "These two listening modes derive from the engine's prelevel 44.1 kHz taps. Production patch playback uses the engine's normal conditioning and leveling.",
            ),
            "scope" to listOf("One PEARL performer profile", "Finite one-shot gestures; HOLD remains zero",
                "VENUE spans coral to seaweed only; conch and cave are deferred", "No venue feedback into the active strings"),
            "diagnostics" to linkedMapOf(
                "baselineRecipeReplayVerified" to replayVerified,
                "acceptance" to "Awaiting owner listening. Numeric measurements do not establish musical quality.",
                "pitchNote" to "A local autocorrelation estimate after the last pluck, not a pitch-lock guarantee. Isolated upper and sympathetic taps can be an octave or more above the root. The detector's lower bound is 40 Hz; MIDI 28 (E1, about 41.2 Hz) lies close to that boundary.",
                "classifierNote" to "The generic SnipSnap classifier labels sounds longer than 1.5 seconds LOOP by duration. These NACRE clips are finite gestures. onsetClassifier measures the first 750 ms separately.",
                "probeNote" to "Stem selection and mute/coupling/current/venue options are audition diagnostics, stored beside each recipe. They are not production patch fields; replaying a recipe produces the full ordinary engine gesture.",
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        writeMetrics(root, clips)
        File(root, "README.txt").writeText(readme(rawGain, replayVerified))
        writeZip(root, LISTENING_ARCHIVE, raw = false)
        writeZip(root, RAW_ARCHIVE, raw = true)
        writeZip(root, ARCHIVE)
        println("NACRE wrote ${clips.size} clips, PCM24 raw/matched WAVs, recipes, event traces, source snapshot and $ARCHIVE to $root")
    }

    private fun requests(base: Map<String, Float>): List<Request> = buildList {
        fun addCase(id: String, title: String, description: String, section: String,
                    macros: Map<String, Float> = base, midi: Int = MIDI, velocity: Float = 1f,
                    options: Nacre.Options = Nacre.Options(), component: String = "full",
                    group: String = id, reference: String = id) {
            add(Request(id, title, description, section, macros, midi, velocity, options, component, group, reference))
        }
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
                "${controlDescriptions.getValue(macro)} All five steps share the .50 reference's gain; other controls, C3, velocity and seed stay fixed.",
                "controls", base + (macro to value), group = "sweep_$macro", reference = "sweep_${macro.lowercase(Locale.ROOT)}_050")
        }
    }

    private fun writeClip(root: File, rendered: Rendered, matchedGain: Float, rawGain: Float): Map<String, Any?> {
        val request = rendered.request
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
            "component" to request.component, "renderOptions" to options,
            "recipeReplay" to "The patch reproduces the ordinary full gesture. Apply the explicitly listed audition options and component tap to reproduce this diagnostic.",
            "referenceId" to request.referenceId, "comparisonGroup" to request.comparisonGroup,
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
            "seed" to SEED.toString(), "model" to Nacre.MODEL_VERSION, "hold" to false,
            "options" to options, "referenceId" to request.referenceId, "comparisonGroup" to request.comparisonGroup,
            "matchingPolicy" to "shared-reference-gain", "audio" to audioPath, "rawAudio" to rawPath,
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

    private fun patch(request: Request) = NacrePatch(request.title, NacreVoice.PEARL, request.macros, request.midi, request.velocity, SEED)
    private fun snip(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = RATE)
    private fun options(options: Nacre.Options): Map<String, Any?> = linkedMapOf(
        "muteBassPlucks" to options.muteBassPlucks, "muteUpperPlucks" to options.muteUpperPlucks,
        "disableCrossHarp" to options.disableCrossHarp, "postCurrentOnly" to options.postCurrentOnly,
        "bypassVenue" to options.bypassVenue,
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
            "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
            "onsetClassifier" to onsetClass.drumClass.name, "onsetClassifierConfidence" to onsetClass.confidence,
            "pitch" to linkedMapOf("hz" to estimate?.hz, "confidence" to estimate?.confidence,
                "requestedRootHz" to requestedHz, "errorCentsFromRoot" to estimate?.let { 1200 * ln(it.hz / requestedHz) / ln(2.0) },
                "fromSeconds" to pitchStart, "windowSeconds" to .25,
                "fundamentalWithinDetectorRange" to (requestedHz >= 40.0 && requestedHz <= 1200.0)),
        )
    }

    private fun sections(): List<Map<String, String>> = listOf(
        Triple("anatomy", "Inside the paired gesture", "The full gesture and its four prelevel component taps, all at the same reference gain. Quiet parts remain quiet."),
        Triple("duet", "Two performers", "Tight, answering and crossing schedules for individual string plucks. Read the event traces to see who enters and in which direction."),
        Triple("current", "Does water reach the instrument?", "Still, moving and post-output-only current, followed by venue-bypassed probes. All five retain one common gain."),
        Triple("venue", "Coral and seaweed", "Two invented acoustic endpoints. This milestone interpolates directly between them; the conch and cave mechanisms are deferred."),
        Triple("sympathy", "The unplucked answer", "Upper plucks muted, with cross-harp transfer enabled or disabled. Full mixes and isolated sympathetic taps preserve the same gain."),
        Triple("pitch", "Across the register", "MIDI 28, 36, 60 and 72. The baseline is MIDI 48. Each register is matched separately; the lowest root is near the pitch detector's 40 Hz lower bound."),
        Triple("velocity", "Gesture energy", "A soft .25 strike compared with the velocity-1 baseline, preserving the original amplitude relation."),
        Triple("controls", "Five small sweeps", "Each timbral control at 0, .25, .50, .75 and 1. Each five-step family uses one shared .50-reference gain. HOLD remains zero."),
    ).map { (id, title, description) -> linkedMapOf("id" to id, "title" to title, "description" to description) }

    private fun copySources(repository: File, root: File): Map<String, Any?> {
        val paths = mutableListOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Nacre.kt",
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
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt", "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Features.kt", "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt", "json/src/main/kotlin/com/snipsnap/json/Json.kt",
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
        val keys = listOf("id", "section", "component", "midi", "velocity", "durationSeconds", "peak", "rms", "dc", "loudness", "tailRms", "classifier", "onsetClassifier", "pitchHz", "pitchConfidence", "pitchErrorCents", "rawPeak", "rawRms", "matchedGain", "rawGain")
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

    private fun readme(rawGain: Float, replayVerified: Boolean) = """
        NACRE / First audition
        ======================
        49 finite mono 44.1 kHz PCM24 clips; one PEARL profile, five timbral controls, HOLD zero.
        VENUE is a prototype coral-to-seaweed interpolation. Conch, cave and held loops are deferred.
        No listening or musical-quality verdict is asserted by this generator.

        Open index.html through a local web server, or listen to the WAVs directly.
        Default audio: shared reference gain within each comparison family, the shared melodic loudness target,
        with .95 peak headroom checked against every family member. Diagnostic stems preserve balance.
        Raw audio: prelevel waveform at one common safe gain of $rawGain across the whole pack.
        The two exports do not contain the production output leveler's independent gain changes.

        recipes/*.json are actual NacrePatch files. They reconstruct ordinary full gestures.
        Diagnostic mute/current/venue options and selected stems are explicitly separate in manifest.json
        and diagnostics/*.json; they are audition probes, not serializable production patch settings.
        Baseline serialized recipe replay verified sample-for-sample: $replayVerified.
        All pluck event schedules are in traces/events/*.csv. Summary measurements are in metrics.csv.
        Pitch is a local estimate after the last pluck; MIDI 28 is near the detector's 40 Hz lower bound.
        The generic classifier calls >1.5-second files LOOP by duration. These clips are finite one-shots.

        source/ preserves the generator, engine, patch, maintained page and local dependencies at render
        time. SHA-256 hashes and the parent commit are in manifest.json. The committed repository and
        bundled snapshot together support code review; this snapshot is not a standalone build checkout.
        Recreate using the repository's generateNacreAudition task and its normal JDK/Gradle setup.

        nacre-listening.zip contains the page, recipes, evidence and 49 listening WAVs.
        nacre-raw.zip contains the same supporting files and the 49 common-gain raw WAVs.
        Extract both into the same directory for all offline playback modes. The complete
        nacre-audition.zip is also retained locally; the hosted download uses two smaller packs
        to keep every static asset under the hosting provider's single-file limit.
    """.trimIndent() + "\n"

    private fun writeZip(root: File, name: String, raw: Boolean? = null) {
        val archive = File(root, name)
        val files = root.walkTopDown().filter { file ->
            file.isFile && file.extension != "zip" && !file.name.endsWith(".tmp") &&
                (raw == null || file.extension != "wav" || file.name.endsWith("_raw.wav") == raw)
        }.toList().sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
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
