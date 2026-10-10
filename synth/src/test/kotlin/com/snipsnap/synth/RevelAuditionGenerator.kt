package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * REVEL's dry listening evidence. The default roster includes the complete requested acceptance
 * matrix; --quick selects a compact first listen. Every WAV comes from the actual engine. Observer
 * and source comparisons are taps of one simulation, rather than independent performances.
 * Generated files belong in testkit/revel-audition/, never in source control.
 */
object RevelAuditionGenerator {
    private val timbral = listOf("PLAY", "SKIN", "ORBIT", "WEAVE", "REACH")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val percussion = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    private val neutral = mapOf("PLAY" to .5f, "SKIN" to .5f, "ORBIT" to .3f,
        "WEAVE" to .25f, "REACH" to .6f, "HOLD" to 0f)

    private enum class Tap { MIX, MIC_1, MIC_2, MIC_3, FRICTION, ARTICULATED, ROLLING, PULSE, FLOOR }
    private data class Case(
        val id: String,
        val section: String,
        val voice: RevelVoice,
        val label: String,
        val description: String,
        val macros: Map<String, Float> = emptyMap(),
        val midi: Int = 60,
        val velocity: Float = 1f,
        val configuration: RevelConfig = RevelConfig(),
        val tap: Tap = Tap.MIX,
        val captureSources: Boolean = false,
        val captureMics: Boolean = false,
        val floor: Boolean = true,
        val coincidentMics: Boolean = false,
        val inputOffSeconds: Double? = null,
    )

    private data class RenderKey(
        val voice: RevelVoice,
        val macros: Map<String, Float>,
        val velocity: Float,
        val configuration: RevelConfig,
        val captureSources: Boolean,
        val captureMics: Boolean,
        val floor: Boolean,
        val coincidentMics: Boolean,
        val inputOffSeconds: Double?,
    )

    private data class Section(val id: String, val title: String, val description: String)
    private val sections = listOf(
        Section("defaults", "The six voices", "Start here. Dry C4 ensembles at their voice defaults, with no rack effects."),
        Section("presets", "Ten listening positions", "The proposed factory presets, including a settled Held Revel."),
        Section("range", "Root × performer energy", "Every voice at C3, C4 and C5, each at velocities .25, .65 and 1. Velocity acts before loudness matching."),
        Section("sweeps", "Five controls, five steps", "PLAY, SKIN, ORBIT, WEAVE and REACH at 0, .25, .5, .75 and 1 for every voice. Neutral companions isolate each control."),
        Section("interactions", "Four interaction grids", "ORBIT × REACH, WEAVE × REACH, PLAY × ORBIT and SKIN × REACH. Each full matrix uses 0, .5 and 1."),
        Section("mics", "One, two, three listeners", "Stationary, circular and spirograph pickup at each microphone count. Coincident microphones check coherent summation."),
        Section("mic-comparison", "The same performance, three perspectives", "The mono mix and all three individual microphone signals captured during one simulation. Raw mode preserves their relative levels."),
        Section("sources", "Inside the circle", "Friction, two articulated heads, two rolling heads, pulse and floor: actual pre-pickup taps of the same playing ensemble."),
        Section("floor", "A shared, passive floor", "Compare coupling enabled and disabled, then stop performer input and hear the remaining acoustic decay."),
        Section("extremes", "The upper limits", "All five timbral controls high for every voice, plus zero-energy and HOLD threshold probes."),
        Section("hold", "Continuing performance", "Settled loop-only HOLD exports. Repeat several times and listen for doubled strokes, interrupted friction contours and gain changes."),
        Section("hold-edges", "Difficult held corners", "Slow close spirographs, stationary dense playing, crossing paths and all-high controls. Clock adjustments and state convergence remain inspectable."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.all { !it.startsWith("--") || it == "--quick" ||
            it.startsWith("--voice=") || it.startsWith("--sections=") }) { "Supported options: --quick, --voice=NAME, --sections=id,id" }
        val quick = "--quick" in args
        val voiceFilter = args.firstOrNull { it.startsWith("--voice=") }?.substringAfter('=')
            ?.uppercase(Locale.ROOT)?.let(RevelVoice::valueOf)
        val sectionFilter = args.firstOrNull { it.startsWith("--sections=") }?.substringAfter('=')
            ?.split(',')?.map { it.lowercase(Locale.ROOT) }?.toSet()
        val cases = roster(quick).filter { (voiceFilter == null || it.voice == voiceFilter) &&
            (sectionFilter == null || it.section in sectionFilter) }
        require(cases.isNotEmpty()) { "No REVEL cases match the supplied filters" }
        check(cases.map { it.id }.distinct().size == cases.size) { "Duplicate REVEL audition IDs" }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/revel-audition")
        check(root.isDirectory || root.mkdirs()) { "Cannot create ${root.absolutePath}" }
        val source = listOf(File("src/main/kotlin/com/snipsnap/synth/Revel.kt"),
            File("synth/src/main/kotlin/com/snipsnap/synth/Revel.kt")).firstOrNull { it.isFile }
            ?: error("Run from the synth module or repository root so the DSP source can be fingerprinted")
        val sourceHash = sha256(source.readBytes())
        val classHash = Revel::class.java.getResourceAsStream("/com/snipsnap/synth/Revel.class")
            ?.use { sha256(it.readBytes()) } ?: error("Cannot fingerprint compiled REVEL")
        val recipeHash = sha256(cases.joinToString("\n") { it.toString() }.toByteArray())
        val listeningRound = "revel-${sourceHash.take(12)}-${recipeHash.take(12)}"
        val clips = ArrayList<JsonValue>(cases.size)
        val measurements = ArrayList<JsonValue>(cases.size)
        // A default also occurs in the note/velocity matrix and sometimes as a factory preset.
        // Keep every listening position in the page while reusing identical rendered evidence.
        val exports = HashMap<Pair<RenderKey, Tap>, JsonValue.Obj>()
        val started = System.nanoTime()
        var previousKey: RenderKey? = null
        var previousReport: Revel.Report? = null
        var previousRenderMs = 0.0
        var renderCount = 0

        for ((index, case) in cases.withIndex()) {
            check(sha256(source.readBytes()) == sourceHash) { "REVEL source changed during rendering; regenerate this listening round" }
            val macros = Revel.defaults(case.voice) + case.macros +
                ("TUNE" to ((case.midi - Revel.ROOT_MIDI).toFloat() / Revel.TUNE_SEMITONES))
            val key = RenderKey(case.voice, macros, case.velocity, case.configuration,
                case.captureSources, case.captureMics, case.floor, case.coincidentMics, case.inputOffSeconds)
            val exportKey = key to case.tap
            val existing = exports[exportKey]
            if (existing != null) {
                val copy = JsonValue.Obj(LinkedHashMap(existing.entries).apply {
                    this["id"] = str(case.id)
                    this["section"] = str(case.section)
                    this["label"] = str(case.label)
                    this["description"] = str(case.description)
                    this["reusedPerformance"] = bool(true)
                })
                clips += copy
                measurements += measurement(copy)
                println("REVEL audition ${index + 1}/${cases.size}: ${case.id} (identical rendered evidence reused)")
                continue
            }
            val reusedPerformance = key == previousKey
            val report: Revel.Report
            val renderMs: Double
            if (reusedPerformance) {
                report = requireNotNull(previousReport)
                renderMs = previousRenderMs
            } else {
                previousReport = null // Keep at most one simulation's large stem arrays live.
                val before = System.nanoTime()
                report = Revel.inspect(case.voice, macros, velocity = case.velocity,
                    configuration = case.configuration, probe = Revel.Probe(
                        recordSources = case.captureSources, recordPaths = true,
                        recordMics = case.captureMics, floor = case.floor,
                        coincidentMics = case.coincidentMics, inputOffSeconds = case.inputOffSeconds))
                renderMs = (System.nanoTime() - before) / 1_000_000.0
                previousKey = key
                previousReport = report
                previousRenderMs = renderMs
                renderCount++
            }
            val samples = selectTap(report, case.tap)
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "Empty or non-finite REVEL output: ${case.id}" }
            val native = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
            val rawMetrics = metrics(native, case.midi)
            val peak = requireNotNull(rawMetrics.entries["peak"]).num()
            val exportGain = if (peak > .99) (.99 / peak).toFloat() else 1f
            val rawSnip = if (exportGain == 1f) native else
                Snip(FloatArray(samples.size) { samples[it] * exportGain }, 1, Dsp.RATE)
            val matched = AuditionLevel.level(native)
            val matchedMetrics = metrics(matched, case.midi)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val diagnosticsPath = "diagnostics/${case.id}.json"
            val eventsPath = "diagnostics/${case.id}_events.csv"
            val tracePath = "diagnostics/${case.id}_paths.csv"
            WavWriter.write(File(root, rawPath), rawSnip, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val loop = report.loop != null
            val diagnostics = diagnosticSummary(report)
            val configJson = configurationJson(case.configuration, case.voice)
            val sharedPerformanceId = sha256(key.toString().toByteArray()).take(16)
            val detail = obj(
                "id" to str(case.id), "listeningRound" to str(listeningRound),
                "performanceId" to str(sharedPerformanceId), "tap" to str(case.tap.name),
                "voice" to str(case.voice.name), "macros" to macroJson(macros),
                "configuration" to configJson, "velocity" to num(case.velocity),
                "floorEnabled" to bool(case.floor), "coincidentMics" to bool(case.coincidentMics),
                "inputOffSeconds" to nullable(case.inputOffSeconds),
                "rawMetrics" to rawMetrics, "matchedMetrics" to matchedMetrics,
                "rawExportGain" to num(exportGain), "renderMs" to num(renderMs),
                "diagnostics" to diagnostics,
                "events" to arr(report.events.map { eventJson(it) }),
                "pathsCsv" to str(tracePath), "eventsCsv" to str(eventsPath),
            )
            File(root, diagnosticsPath).also { it.parentFile.mkdirs() }.writeText(Json.write(detail) + "\n")
            writeEvents(File(root, eventsPath), report)
            writePaths(File(root, tracePath), report)
            val seam = if (case.tap == Tap.MIX) report.loop?.seamError else null
            val clip = obj(
                "id" to str(case.id), "section" to str(case.section), "voice" to str(case.voice.name),
                "label" to str(case.label), "description" to str(case.description),
                "macros" to macroJson(macros), "midi" to num(case.midi),
                "requestedMidi" to num(case.midi), "requestedHz" to num(Keys.midiHz(case.midi)),
                "velocity" to num(case.velocity), "micCount" to num(case.configuration.micCount ?: Revel.defaultMicCount(case.voice)),
                "configuration" to configJson, "held" to bool(Revel.isLoop(macros.getValue("HOLD"))),
                "loop" to bool(loop), "tap" to str(case.tap.name),
                "performanceId" to str(sharedPerformanceId), "reusedPerformance" to bool(reusedPerformance),
                "frames" to num(samples.size), "seconds" to num(native.durationSeconds),
                "rawPath" to str(rawPath), "matchedPath" to str(matchedPath),
                "diagnosticsPath" to str(diagnosticsPath), "eventsPath" to str(eventsPath), "tracePath" to str(tracePath),
                "rawExportGain" to num(exportGain), "renderMs" to num(renderMs),
                "renderMsPerAudioSecond" to num(renderMs / native.durationSeconds),
                "seamError" to nullable(seam), "diagnostics" to diagnostics,
                "rawMetrics" to rawMetrics, "matchedMetrics" to matchedMetrics,
                "recipeStatus" to str("provisional; owner listening pending"),
            )
            clips += clip
            exports[exportKey] = clip
            measurements += measurement(clip)
            println("REVEL audition ${index + 1}/${cases.size}: ${case.id} (${renderMs.roundToInt()} ms${if (reusedPerformance) ", shared performance tap" else ""})")
        }

        check(sha256(source.readBytes()) == sourceHash) { "REVEL source changed; regenerate the listening round" }
        val percussionFailures = clips.filter { clip ->
            val data = clip.obj()
            data.getValue("tap").str() == Tap.MIX.name &&
                data.getValue("matchedMetrics").obj().let { metrics ->
                    DrumClass.valueOf(metrics.getValue("classifier").str()) in percussion ||
                        DrumClass.valueOf(metrics.getValue("shortClassifier").str()) in percussion
                }
        }.map { it.obj().getValue("id") }
        val failedSeams = clips.filter { clip ->
            val data = clip.obj()
            val seam = data.getValue("seamError")
            data.getValue("loop").bool() && data.getValue("tap").str() == Tap.MIX.name &&
                (seam !is JsonValue.Num || seam.value >= Keys.MAX_SEAM_ERROR)
        }.map { it.obj().getValue("id") }
        val totalMs = (System.nanoTime() - started) / 1_000_000.0
        val manifest = Json.write(obj(
            "engine" to str("REVEL"), "sampleRate" to num(Dsp.RATE), "bitDepth" to num(24),
            "quick" to bool(quick), "fullMatrix" to bool(!quick && voiceFilter == null && sectionFilter == null),
            "dspSourceSha256" to str(sourceHash), "compiledClassSha256" to str(classHash),
            "recipeSha256" to str(recipeHash), "listeningRound" to str(listeningRound),
            "sonicAcceptance" to str("pending owner listening; presets and sound identity remain provisional"),
            "metricNotes" to str(METRIC_NOTES), "renderCount" to num(renderCount), "clipCount" to num(cases.size),
            "wavCount" to num(exports.size * 2),
            "generationMs" to num(totalMs), "percussionFailures" to arr(percussionFailures),
            "seamFailures" to arr(failedSeams),
            "sections" to arr(sections.filter { section -> cases.any { it.section == section.id } }.map {
                obj("id" to str(it.id), "title" to str(it.title), "description" to str(it.description))
            }), "clips" to arr(clips),
        )) + "\n"
        prune(root, exports.values.map { it.entries.getValue("rawPath").str().substringAfterLast('/').removeSuffix(".wav") }.toSet())
        File(root, "manifest.json").writeText(manifest)
        File(root, "measurements.json").writeText(Json.write(obj("listeningRound" to str(listeningRound),
            "metricNotes" to str(METRIC_NOTES), "clips" to arr(measurements))) + "\n")
        val page = RevelAuditionGenerator::class.java.getResourceAsStream("/audition/revel-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("Missing audition/revel-audition.html resource")
        File(root, "index.html").writeText(page.replace("__MANIFEST__", manifest.replace("</", "<\\/")))
        File(root, "README.md").writeText(readme(cases.size, quick, listeningRound))
        println("Wrote ${exports.size * 2} dry WAVs for ${cases.size} listening positions, diagnostics, measurements, manifest and standalone page under " +
            "${root.absolutePath} in %.1f seconds".format(Locale.ROOT, totalMs / 1000))
        if (percussionFailures.isNotEmpty() || failedSeams.isNotEmpty()) println(
            "Evidence requiring investigation: ${percussionFailures.size} percussion classifications; ${failedSeams.size} HOLD seam failures. See manifest.json.")
    }

    private fun measurement(clip: JsonValue.Obj) = obj(*listOf("id", "section", "voice", "tap",
        "rawMetrics", "matchedMetrics", "seamError", "renderMs", "diagnostics")
        .map { it to clip.entries.getValue(it) }.toTypedArray())

    private fun roster(quick: Boolean): List<Case> = buildList {
        for (voice in RevelVoice.entries) add(case("defaults", voice, "default", voice.name,
            voiceDescription(voice)))
        if (!quick) {
            for (preset in RevelPresets.all()) add(case("presets", preset.voice, "preset_${slug(preset.name)}",
                preset.name, "Factory preset; dry ${preset.voice.name} with its saved performer and observer configuration.", preset.macros)
                .copy(midi = Revel.ROOT_MIDI + (preset.macros.getValue("TUNE") * Revel.TUNE_SEMITONES).roundToInt(),
                    velocity = preset.velocity, configuration = preset.configuration))
            for (voice in RevelVoice.entries) for (midi in listOf(48, 60, 72)) for (velocity in listOf(.25f, .65f, 1f)) {
                add(case("range", voice, "${noteName(midi).lowercase()}_v${tag(velocity)}",
                    "${voice.name} · ${noteName(midi)} · v ${fmt(velocity)}", "Voice defaults; only requested root and performer energy change.")
                    .copy(midi = midi, velocity = velocity))
            }
            for (voice in RevelVoice.entries) for (macro in timbral) for (value in steps) add(
                case("sweeps", voice, "${macro.lowercase()}_${tag(value)}", "${voice.name} · $macro ${fmt(value)}",
                    macroDescription(macro), neutral + (macro to value)))
        } else {
            for (macro in timbral) for (value in listOf(0f, 1f)) add(case("sweeps", RevelVoice.CIRCLE,
                "${macro.lowercase()}_${tag(value)}", "$macro ${fmt(value)}", macroDescription(macro), neutral + (macro to value)))
        }
        if (!quick) for ((a, b) in listOf("ORBIT" to "REACH", "WEAVE" to "REACH", "PLAY" to "ORBIT", "SKIN" to "REACH")) {
            for (av in listOf(0f, .5f, 1f)) for (bv in listOf(0f, .5f, 1f)) add(case("interactions", RevelVoice.CIRCLE,
                "${a.lowercase()}${tag(av)}_${b.lowercase()}${tag(bv)}", "$a ${fmt(av)} × $b ${fmt(bv)}",
                "C4, velocity 1 and neutral companions. Listen for changing source emphasis and physical character at the same root.",
                neutral + mapOf(a to av, b to bv)))
        }
        if (!quick) {
            for (count in 1..3) for ((name, orbit, weave) in listOf(Triple("stationary", 0f, .25f),
                Triple("circular", .5f, 0f), Triple("spiro", .5f, 1f))) add(case("mics", RevelVoice.CIRCLE,
                    "${count}mic_$name", "$count mic${if (count > 1) "s" else ""} · $name",
                    "Same performer seed and phrase. Microphones observe one shared ensemble; geometry and mic count change.",
                    neutral + mapOf("ORBIT" to orbit, "WEAVE" to weave, "REACH" to .8f))
                    .copy(configuration = RevelConfig(micCount = count,
                        trajectories = List(count) { if (name == "spiro") RevelTrajectory.SPIRO else RevelTrajectory.CIRCLE })))
            for (count in 1..3) add(case("mics", RevelVoice.CIRCLE, "coincident_$count", "$count coincident mic${if (count > 1) "s" else ""}",
                "Identical microphone paths must reproduce the single-mic perspective at matched level; raw mode exposes count compensation.",
                neutral + mapOf("ORBIT" to .5f, "REACH" to .8f))
                .copy(configuration = RevelConfig(micCount = count), coincidentMics = true))
        }
        val micCase = case("mic-comparison", RevelVoice.SPIRO, "same_mix", "Three microphones · mono mix",
            "One actual performance, recorded simultaneously. Compare each microphone below; these are not separately synthesized ensembles.")
            .copy(configuration = RevelConfig(micCount = 3), captureMics = true)
        add(micCase)
        for ((tap, index) in listOf(Tap.MIC_1 to 1, Tap.MIC_2 to 2, Tap.MIC_3 to 3)) add(micCase.copy(
            id = "spiro_same_mic_$index", label = "Microphone $index · individual pickup", tap = tap,
            description = "Recorded from the same source states as the three-mic mix. Raw preserves relative perspective level; matched makes timbre easier to compare."))

        val sourceCase = case("sources", RevelVoice.CIRCLE, "source_mix", "All four families · shared performance",
            "The source taps below are recorded during this exact performance, before moving microphone pickup.")
            .copy(captureSources = true)
        add(sourceCase)
        for ((tap, name) in listOf(Tap.FRICTION to "Friction head", Tap.ARTICULATED to "Articulated pair",
            Tap.ROLLING to "Rolling pair", Tap.PULSE to "Pulse head", Tap.FLOOR to "Shared floor")) add(sourceCase.copy(
            id = "circle_source_${tap.name.lowercase()}", label = name, tap = tap,
            description = "Actual pre-pickup $name tap of the same ensemble; other performers and floor loading remain active. Source isolates are diagnostic and may classify as percussion."))
        add(case("floor", RevelVoice.CIRCLE, "floor_on", "Floor coupling enabled", "All four families drive the shared resonant floor; compare coupling disabled below."))
        add(case("floor", RevelVoice.CIRCLE, "floor_off", "Floor coupling disabled", "Same phrase, seed, source controls and observer paths, with floor transfer disabled.").copy(floor = false))
        if (!quick) add(case("floor", RevelVoice.PROCESSION, "performers_stop", "Performers stop after one second",
            "Scheduled performer input stops at 1 s; remaining head, floor and propagated path energy must decay without new strokes.").copy(inputOffSeconds = 1.0))
        for (voice in if (quick) listOf(RevelVoice.SPIRO) else RevelVoice.entries) add(case("extremes", voice,
            "all_high", "${voice.name} · all five controls high", "C5, maximum performer velocity and maximum timbral settings. Check raw energy, contact detail and root retention.",
            timbral.associateWith { 1f }).copy(midi = 72))
        if (!quick) {
            add(case("extremes", RevelVoice.CIRCLE, "zero_velocity", "Zero performer energy", "Zero velocity must be silent before and after matching.").copy(velocity = 0f))
            add(case("extremes", RevelVoice.CIRCLE, "hold_098", "HOLD .98 · finite phrase", "HOLD remains finite below the .99 loop threshold.", mapOf("HOLD" to .98f)))
        }
        for (voice in if (quick) listOf(RevelVoice.CIRCLE, RevelVoice.SPIRO) else RevelVoice.entries) add(case("hold", voice,
            "held", "${voice.name} · held", "Settled continuing ensemble, exported as one loop period. Repeat several cycles to assess the wrap.", mapOf("HOLD" to 1f)))
        if (!quick) for ((voice, name, macros) in listOf(
            Triple(RevelVoice.SPIRO, "slow_close_spiro", mapOf("PLAY" to .75f, "SKIN" to 0f, "ORBIT" to .02f, "WEAVE" to 1f, "REACH" to 1f)),
            Triple(RevelVoice.CIRCLE, "stationary_dense", mapOf("PLAY" to 1f, "SKIN" to 0f, "ORBIT" to 0f, "WEAVE" to 1f, "REACH" to 1f)),
            Triple(RevelVoice.CROSSING, "fast_crossings", mapOf("PLAY" to 1f, "SKIN" to .5f, "ORBIT" to 1f, "WEAVE" to .5f, "REACH" to 1f)),
            Triple(RevelVoice.SPIRO, "held_all_high", timbral.associateWith { 1f }),
        )) add(case("hold-edges", voice, name, "${voice.name} · ${name.replace('_', ' ')}",
            "Difficult settled HOLD corner. Inspect requested and actual orbit/phrase clocks, audio seam and complete state convergence; then repeat-listen.",
            macros + ("HOLD" to 1f)))
    }

    private fun selectTap(report: Revel.Report, tap: Tap): FloatArray = when (tap) {
        Tap.MIX -> report.raw
        Tap.MIC_1, Tap.MIC_2, Tap.MIC_3 -> requireNotNull(report.microphones)[tap.ordinal - Tap.MIC_1.ordinal]
        Tap.FLOOR -> requireNotNull(report.floorSignal)
        Tap.FRICTION -> requireNotNull(report.sources)[0]
        Tap.PULSE -> requireNotNull(report.sources)[5]
        Tap.ARTICULATED -> sum(requireNotNull(report.sources)[1], requireNotNull(report.sources)[2])
        Tap.ROLLING -> sum(requireNotNull(report.sources)[3], requireNotNull(report.sources)[4])
    }

    private fun sum(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == b.size) { "Source taps do not share one timeline" }
        return FloatArray(a.size) { a[it] + b[it] }
    }

    private fun metrics(snip: Snip, midi: Int): JsonValue.Obj {
        var peak = 0.0
        var energy = 0.0
        var total = 0.0
        for (sample in snip.samples) {
            val value = sample.toDouble()
            peak = maxOf(peak, abs(value))
            energy += value * value
            total += value
        }
        val classification = Classifier.classify(snip)
        // Whole phrases legitimately take the classifier's >1.5 s LOOP branch. A short head
        // measurement exposes the pitched-routing risk without relabeling the exported phrase.
        val shortClassification = Classifier.classify(Snip(snip.samples.copyOfRange(0,
            minOf(snip.samples.size, (1.35 * snip.sampleRate).toInt())), 1, snip.sampleRate))
        val pitchWindows = listOf(.08f, .6f, 1.2f).mapNotNull { time ->
            Pitch.detect(snip, fromSec = time, windowSec = .25f)?.let { time to it }
        }
        val pitch = pitchWindows.maxByOrNull { it.second.confidence }?.second
        return obj("peak" to num(peak), "rms" to num(sqrt(energy / snip.samples.size)),
            "dc" to num(total / snip.samples.size), "loudness" to num(Loudness.of(snip)),
            "centroidHz" to num(classification.features.centroidHz),
            "pitchHz" to nullable(pitch?.hz), "pitchConfidence" to nullable(pitch?.confidence),
            "pitchErrorCents" to nullable(pitch?.let { 1200 * ln(it.hz / Keys.midiHz(midi).toDouble()) / ln(2.0) }),
            "classifier" to str(classification.drumClass.name),
            "classifierConfidence" to num(classification.confidence),
            "shortClassifier" to str(shortClassification.drumClass.name),
            "shortClassifierConfidence" to num(shortClassification.confidence),
            "pitchWindows" to arr(pitchWindows.map { (time, value) -> obj(
                "fromSeconds" to num(time), "hz" to num(value.hz), "confidence" to num(value.confidence)) }))
    }

    private fun diagnosticSummary(report: Revel.Report) = obj(
        "maxEnergy" to num(report.maxEnergy), "maxFloorEnergy" to num(report.maxFloorEnergy),
        "poweredWork" to num(report.poweredWork), "passiveLoss" to num(report.passiveLoss),
        "recoveries" to num(report.recoveries), "maxMicGain" to num(report.maxMicGain),
        "maxDelayRate" to num(report.maxDelayRate), "eventCount" to num(report.events.size),
        "eventCounts" to obj(*report.events.groupingBy { it.family.toString() }.eachCount().entries.map {
            it.key to num(it.value) }.toTypedArray()),
        "pathFrames" to num(report.paths.size),
        "rates" to obj("requestedOrbitHz" to num(report.rates.requestedOrbitHz),
            "orbitHz" to num(report.rates.orbitHz), "requestedPhraseSeconds" to num(report.rates.requestedPhraseSeconds),
            "phraseSeconds" to num(report.rates.phraseSeconds), "loopSeconds" to num(report.rates.loopSeconds)),
        "loop" to (report.loop?.let { loop -> obj("seamError" to num(loop.seamError),
            "convergenceError" to num(loop.convergenceError), "stateConvergenceError" to num(loop.stateConvergenceError),
            "prerollCycles" to num(loop.prerollCycles)) } ?: JsonValue.Null),
    )

    private fun eventJson(event: Revel.Event) = obj("head" to num(event.head),
        "family" to str(event.family.toString()), "kind" to str(event.kind.toString()),
        "timeSeconds" to num(event.timeSeconds), "durationSeconds" to num(event.durationSeconds), "energy" to num(event.energy))

    private fun writeEvents(file: File, report: Revel.Report) {
        file.bufferedWriter().use { writer ->
            writer.write("head,family,kind,timeSeconds,durationSeconds,energy\n")
            for (event in report.events) writer.write("${event.head},${event.family},${event.kind},${event.timeSeconds},${event.durationSeconds},${event.energy}\n")
        }
    }

    private fun writePaths(file: File, report: Revel.Report) {
        file.bufferedWriter().use { writer ->
            writer.write("timeSeconds,mic,head,x,y,distanceMeters,gain,delaySamples\n")
            // Compact geometry evidence without changing the audio or full-precision scalar
            // diagnostics. Fixed decimal formatting also avoids locale-dependent separators.
            for (frame in report.paths) writer.write(String.format(Locale.ROOT,
                "%.6f,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                frame.timeSeconds, frame.mic, frame.head, frame.x, frame.y,
                frame.distanceMeters, frame.gain, frame.delaySamples))
        }
    }

    private fun configurationJson(config: RevelConfig, voice: RevelVoice) = obj(
        "micCount" to num(config.micCount ?: Revel.defaultMicCount(voice)),
        "phraseTempo" to num(config.phraseTempo), "phraseBeats" to num(config.phraseBeats),
        "trajectories" to arr(config.trajectories.map { str(it.name) }),
        "phaseOffsets" to arr(config.phaseOffsets.map { num(it) }), "directions" to arr(config.directions.map { num(it) }),
        "speedRatios" to arr(config.speedRatios.map { num(it) }), "seed" to str(config.seed.toString()),
    )

    private fun prune(root: File, ids: Set<String>) {
        for (directory in listOf("raw", "matched", "diagnostics")) File(root, directory).listFiles()?.forEach { file ->
            val id = file.nameWithoutExtension.removeSuffix("_events").removeSuffix("_paths")
            if (file.isFile && id !in ids) check(file.delete()) { "Cannot remove stale generated file ${file.path}" }
        }
    }

    private fun case(section: String, voice: RevelVoice, suffix: String, label: String,
        description: String, macros: Map<String, Float> = emptyMap()) =
        Case("${voice.name.lowercase(Locale.ROOT)}_$suffix", section, voice, label, description, macros)
    private fun voiceDescription(voice: RevelVoice) = when (voice) {
        RevelVoice.CIRCLE -> "One listener follows a clear rotating conversation among friction, articulated strokes, rolls and broad pulses."
        RevelVoice.CLOSE -> "One listener passes close to individual players, exposing contact and membrane details."
        RevelVoice.CROSSING -> "Two counter-moving perspectives hear the same interlocking performance."
        RevelVoice.SPIRO -> "Three listeners trace intricate loops through one shared circle of performers."
        RevelVoice.FRICTION -> "Elastic vocal gestures speak above an ensemble that retains all four source families."
        RevelVoice.PROCESSION -> "Broad pulses and alternating rolling answers anchor the friction and articulated responses."
    }
    private fun macroDescription(macro: String) = when (macro) {
        "PLAY" -> "Sparse conversation to lively overlaps; participation, density and stroke energy change while the microphone clock remains separate."
        "SKIN" -> "Loose rounded membranes to taut bright articulation; listen for contact and loss changes while the requested root stays stable."
        "ORBIT" -> "Stationary listening to faster travel. Actual local gestures change emphasis; the phrase clock and requested root stay separate."
        "WEAVE" -> "Circular to intricate looping paths. Listen for changed encounters rather than a global modulation pasted onto the mix."
        "REACH" -> "Near-center blended listening to close player encounters, with bounded source clearance and microphone gain."
        else -> error("Unknown macro $macro")
    }
    private fun macroJson(macros: Map<String, Float>) = obj(*macros.toSortedMap().map { it.key to num(it.value) }.toTypedArray())
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))
    private fun arr(items: List<JsonValue>) = JsonValue.Arr(items)
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number): JsonValue = value.toDouble().let { if (it.isFinite()) JsonValue.Num(it) else JsonValue.Null }
    private fun nullable(value: Number?): JsonValue = value?.let { num(it) } ?: JsonValue.Null
    private fun bool(value: Boolean) = JsonValue.Bool(value)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun slug(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private const val METRIC_NOTES = "Dry mono audio, 44.1 kHz, PCM24, generated by the actual REVEL engine with no rack FX. Raw metrics describe the unattenuated floating-point simulation; rawExportGain records only PCM safety attenuation when an over-range source would clip. Matched audio uses the shared AuditionLevel target: loudest 200 ms RMS 0.03, with a peak guard. RMS and DC cover the whole exported clip. Centroid is the project's power-weighted head measurement. Pitch is the highest-confidence project Pitch.detect result from 250 ms windows beginning at .08, .6 and 1.2 seconds; every successful estimate is retained, and null means no periodic estimate. The actual classifier is recorded without relabeling; source taps can legitimately be percussion diagnostics. HOLD exports contain the settled loop region only. seamError is the engine's Keys metric against the actual preceding period, not an endpoint-step approximation; convergenceError and stateConvergenceError provide additional closure evidence. Events/path traces may include hidden preroll before the exported region. Path CSV floating-point fields are rounded to six decimal places using Locale.ROOT; microphone and head indices remain integers. WAV audio and scalar diagnostic measurements retain their original precision. Source taps are pre-pickup acoustic states with all performers/floor loading still active; paired heads are summed. Individual mic taps share exactly one performance and preserve their pre-mono-summation relative level in raw mode. Render time measures synthesis and final-rate processing, excludes file encoding, and is repeated on taps that reuse one render. Numerical evidence does not establish dry source identity, musical interest or sonic acceptance."

    private fun readme(count: Int, quick: Boolean, round: String) = """
        # SnipSnap REVEL audition

        $count dry clips, each as a raw and a loudness-matched PCM24 WAV. Listening round: $round.
        ${if (quick) "This is the compact first-listen pack; it is not the full acceptance matrix." else "This is the full requested acceptance matrix when generated without voice/section filters."}

        Open index.html directly in a browser, or serve this folder. The manifest is embedded in the
        page so local file playback works without fetching JSON. Listen through the defaults first,
        compare raw/matched levels, then audition control sweeps, actual shared-performance taps and
        repeated held loops. Notes and verdicts stay in this browser; export them before changing devices.

        Build the complete pack with ./gradlew :synth:generateRevelAudition.
        Add -Pquick for a smaller first-listen pack. The generator also accepts --voice=NAME and
        --sections=defaults,hold when invoked directly. Generated WAVs and diagnostics are gitignored.

        manifest.json contains recipes, paths, source/compiled fingerprints and per-clip metrics.
        measurements.json is the numerical summary. diagnostics/ contains full event JSON and
        event/path CSV evidence. Owner listening approval remains pending.

        $METRIC_NOTES
    """.trimIndent() + "\n"
}
