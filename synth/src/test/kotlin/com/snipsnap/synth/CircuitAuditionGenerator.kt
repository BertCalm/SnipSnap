package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CIRCUIT listener pack: raw beside matched loudness, with deterministic event/path
 * diagnostics. The full pack follows docs/CIRCUIT.md; --quick is a development smoke
 * pack, not the acceptance gate. No clip or numerical check supplies an owner's verdict.
 */
object CircuitAuditionGenerator {
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val timbral = listOf("BREATH", "DIAMETER", "ORBIT", "PACE", "CANYON")
    private val neutral = mapOf(
        "TUNE" to 0.5f, "BREATH" to 0.45f, "DIAMETER" to 0.40f,
        "ORBIT" to 0f, "PACE" to 0.35f, "CANYON" to 0.40f, "HOLD" to 0f,
    )
    private val percussion = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.all { !it.startsWith("--") || it == "--quick" }) { "only --quick is supported" }
        val quick = "--quick" in args
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/circuit-audition")
        root.mkdirs()
        File(root, "measurements.json").delete()
        var count = 0
        val sections = mutableListOf<JsonValue>()
        val measurements = Measurements()

        fun clip(
            section: String,
            id: String,
            name: String,
            description: String,
            voice: CircuitVoice = CircuitVoice.ROOT,
            macros: Map<String, Float> = Circuit.defaults(voice),
            velocity: Float = 1f,
            probe: Circuit.Probe = Circuit.Probe(recordPaths = true),
            guard: Boolean = true,
            gesture: CircuitInstruments.Kind? = null,
        ): JsonValue {
            val runtime = Runtime.getRuntime()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val start = System.nanoTime()
            val report = if (gesture == null) Circuit.inspect(voice, macros, velocity = velocity, probe = probe) else {
                val wave = CircuitInstruments.gesture(gesture, Circuit.frequencyFor(macros.getValue("TUNE")).toDouble(),
                    Dsp.RATE * Dsp.OVERSAMPLE, velocity.toDouble(), Dsp.seedFor("CIRCUIT-AUDITION-VOCAL", gesture).toLong(),
                    macros.getValue("BREATH").toDouble())
                val raw = Dsp.decimate(wave, Dsp.RATE)
                val normalized = raw.copyOf()
                Dsp.levelTo(normalized, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
                Circuit.Report(Snip(normalized, 1, Dsp.RATE), raw,
                    listOf(Circuit.Event(6, gesture.name, 0.0, velocity.toDouble())), emptyList(),
                    Circuit.Rates(0.0, 0.0, 0.0, 0.0, 0.0), Snip(raw, 1, Dsp.RATE).peak().toDouble(), 0, null)
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            require(report.raw.all { it.isFinite() } && report.snip.samples.all { it.isFinite() }) {
                "$section/$id has a non-finite sample"
            }
            val rawStats = audioStats(report.raw)
            require(rawStats.peak <= 1.0) { "$section/$id raw peak ${rawStats.peak} would clip in PCM" }
            val classification = Classifier.classify(report.snip).drumClass
            if (guard) require(classification !in percussion) { "$section/$id classified as $classification" }

            val dir = File(root, section).also { it.mkdirs() }
            val rawName = "$id-raw.wav"
            val matchedName = "$id-matched.wav"
            val matched = AuditionLevel.level(report.snip)
            WavWriter.write(File(dir, rawName), Snip(report.raw, 1, report.snip.sampleRate), WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(dir, matchedName), matched, WavWriter.BitDepth.PCM_24)
            val diagnosticName = "$id-diagnostics.json"
            val diagnostics = diagnosticJson(report, elapsedMs, heapBefore, heapAfter)
            File(dir, diagnosticName).writeText(Json.write(diagnostics) + "\n")
            measurements.record("$section/$id", report, rawStats, audioStats(report.snip.samples), audioStats(matched.samples),
                elapsedMs, heapBefore, heapAfter, guard, classification)
            count++
            println("[$count] $section/$id (${elapsedMs.roundToInt()} ms, $classification)")
            return obj(
                "id" to str(id), "name" to str(name), "description" to str(description),
                "voice" to str(voice.name), "macros" to macroJson(macros), "velocity" to num(velocity),
                "raw" to str("$section/$rawName"), "matched" to str("$section/$matchedName"),
                "diagnostics" to str("$section/$diagnosticName"), "classification" to str(classification.name),
                "seconds" to num(report.snip.durationSeconds), "renderMs" to num(elapsedMs),
            )
        }

        fun group(label: String, clips: List<JsonValue>) = obj("label" to str(label), "clips" to arr(clips))
        fun section(id: String, title: String, description: String, groups: List<JsonValue>) {
            sections += obj("id" to str(id), "title" to str(title), "description" to str(description), "groups" to arr(groups))
        }

        val defaultClips = CircuitVoice.entries.map { voice ->
            clip("DEFAULTS", voice.name.lowercase(), voice.name, "Voice defaults; render velocity 1.", voice)
        }
        section("DEFAULTS", "Six ensemble voices", "The pitched trio stays present beneath the surrounding instruments.", listOf(group("Defaults", defaultClips)))

        val rangeVoices = if (quick) listOf(CircuitVoice.ROOT) else CircuitVoice.entries
        for (voice in rangeVoices) {
            val sectionId = "RANGE_${voice.name}"
            val notes = if (quick) listOf(0f to "C2", 1f to "C4") else listOf(0f to "C2", 0.5f to "C3", 1f to "C4")
            val velocities = if (quick) listOf(0.25f, 1f) else listOf(0.25f, 0.6f, 1f)
            val clips = notes.flatMap { (tune, note) ->
                velocities.map { velocity ->
                    clip(sectionId, "${note.lowercase()}_v${tag(velocity)}", "$note / velocity ${fmt(velocity)}",
                        "All other controls at ${voice.name} defaults.", voice,
                        Circuit.defaults(voice) + ("TUNE" to tune), velocity)
                }
            }
            section(sectionId, "${voice.name}: register and energy", "TUNE spans C2–C4. Velocity is finite performer energy before shared loudness targeting.", listOf(group("Notes × velocity", clips)))
        }

        val sweepVoices = if (quick) listOf(CircuitVoice.ROOT) else CircuitVoice.entries
        for (voice in sweepVoices) {
            val sectionId = "SWEEPS_${voice.name}"
            val knobs = if (quick) listOf("ORBIT", "PACE") else listOf("TUNE") + timbral + "HOLD"
            val sweepSteps = if (quick) listOf(0f, 1f) else steps
            val groups = knobs.map { knob ->
                group(knob, sweepSteps.map { value ->
                    clip(sectionId, "${knob.lowercase()}_${tag(value)}", "$knob ${fmt(value)}",
                        "Companions: BREATH .45, DIAMETER .40, ORBIT 0, PACE .35, CANYON .40, HOLD 0; TUNE C3.",
                        voice, neutral + (knob to value))
                })
            }
            section(sectionId, "${voice.name}: controls", "Neutral companions isolate each control. HOLD 1 is a settled loop without an initiating gesture.", groups)
        }

        if (!quick) {
            val pairs = listOf("DIAMETER" to "ORBIT", "DIAMETER" to "CANYON", "PACE" to "CANYON", "ORBIT" to "PACE", "BREATH" to "CANYON")
            val groups = pairs.map { (a, b) ->
                group("$a × $b", listOf(0f, 0.5f, 1f).flatMap { av ->
                    listOf(0f, 0.5f, 1f).map { bv ->
                        clip("GRIDS", "${a.lowercase()}_${tag(av)}_${b.lowercase()}_${tag(bv)}", "$a ${fmt(av)} / $b ${fmt(bv)}",
                            "ROOT voice; neutral companions.", macros = neutral + mapOf(a to av, b to bv))
                    }
                })
            }
            section("GRIDS", "Five interactions", "Each 3 × 3 grid uses 0, .5 and 1. Listen for changed arrival timing and answer gaps.", groups)
        }

        val diagnosticVoice = CircuitVoice.VOICED
        val diagnosticMacros = Circuit.defaults(diagnosticVoice)
        val solos = listOf("ANCHOR", "PULSE", "VOICE", "STONE RATTLE", "WOOD CLAPPER", "CLAY VESSEL", "BREATH VOICE").mapIndexed { i, name ->
            clip("DIAGNOSTICS", "source_$i", name, "Source $i alone through its acoustic paths; classifier guard omitted for source isolates.",
                diagnosticVoice, diagnosticMacros, probe = Circuit.Probe(solo = i, recordPaths = true), guard = false)
        }
        val responseClips = listOf(
            clip("DIAGNOSTICS", "answers_on", "Replies enabled", "ANSWER voice defaults; canyon audio remains present.", CircuitVoice.ANSWER),
            clip("DIAGNOSTICS", "answers_off", "No behavioral replies", "Same acoustic paths, with replies disabled.", CircuitVoice.ANSWER,
                probe = Circuit.Probe(responses = false, recordPaths = true)),
        )
        val vocalGestures = if (quick) emptyList() else listOf(CircuitInstruments.Kind.GRUNT, CircuitInstruments.Kind.UH_HUH).map { kind ->
            clip("DIAGNOSTICS", "dry_${kind.name.lowercase()}", "Dry ${kind.name.replace('_', '-')}",
                "One synthesized coordination gesture before acoustic paths; classifier guard omitted.",
                diagnosticVoice, diagnosticMacros, guard = false, gesture = kind)
        }
        val couplingClips = if (quick) emptyList() else listOf(
            clip("DIAGNOSTICS", "coupling_on", "Local coupling", "ROOT defaults; ordinary local tube influence."),
            clip("DIAGNOSTICS", "coupling_off", "No local coupling", "Same source and canyon settings; local influence disabled.",
                probe = Circuit.Probe(coupling = 0.0, recordPaths = true)),
            clip("DIAGNOSTICS", "input_off", "Performer input off at 1 s", "Listen for passive tube and canyon decay; this is a diagnostic envelope.",
                probe = Circuit.Probe(inputOffSeconds = 1.0, recordPaths = true), guard = false),
        )
        section("DIAGNOSTICS", "Inside the ensemble", "Solo branches include synthesized vocal gestures. Numerical sidecars record events, paths and actual clock rates.",
            listOf(group("Seven sources", solos), group("Echo conversation", responseClips)) +
                (if (vocalGestures.isEmpty()) emptyList() else listOf(group("Isolated vocal gestures", vocalGestures))) +
                if (couplingClips.isEmpty()) emptyList() else listOf(group("Local influence and release", couplingClips)))

        val cases = listOf(
            Triple("stationary_fast", "Stationary / fast playing", mapOf("ORBIT" to 0f, "PACE" to 1f)),
            Triple("moving_sparse", "Fast movement / sparse playing", mapOf("ORBIT" to 1f, "PACE" to 0f)),
            Triple("close", "Close formation", mapOf("DIAMETER" to 0f, "ORBIT" to 0.65f)),
            Triple("wide", "Wide formation", mapOf("DIAMETER" to 1f, "ORBIT" to 0.65f)),
        )
        if (!quick) section("CASES", "Independent motion and playing", "Movement and gesture clocks remain independent in finite phrases.",
            listOf(group("Formation contrasts", cases.map { (id, name, macros) -> clip("CASES", id, name, "ROOT, neutral companions.", macros = neutral + macros) })))

        val extremes = (if (quick) listOf(CircuitVoice.CONFLUENCE) else CircuitVoice.entries).map { voice ->
            clip("EXTREMES", voice.name.lowercase(), "${voice.name}: all high", "C4; all five timbral controls at 1; finite phrase.",
                voice, neutral + timbral.associateWith { 1f } + ("TUNE" to 1f))
        }
        section("EXTREMES", "Upper limits", "The phrase must stay bounded, keep its root and terminate.", listOf(group("All high", extremes)))

        val holdCases = listOf(
            Triple("slow_orbit_fast_pace", "Slow orbit / fast playing", mapOf("ORBIT" to 0.01f, "PACE" to 0.93f, "DIAMETER" to 0.95f, "CANYON" to 0.85f)),
            Triple("fast_orbit_sparse_pace", "Fast orbit / sparse playing", mapOf("ORBIT" to 0.87f, "PACE" to 0.01f, "CANYON" to 0.90f)),
            Triple("awkward_clocks", "Two intermediate clocks", mapOf("ORBIT" to 0.37f, "PACE" to 0.61f, "BREATH" to 0.83f, "DIAMETER" to 0.73f)),
        )
        section("HOLD", "Difficult held clocks", "Enable repeat for each pair. Read the diagnostic rates: held clocks quantize to compatible nonzero cycles; the opening gesture is omitted.",
            listOf(group("Settled processions", (if (quick) holdCases.take(1) else holdCases).map { (id, name, macros) ->
                clip("HOLD", id, name, "HOLD 1; ROOT with the named clock/formation overrides.", macros = Circuit.defaults(CircuitVoice.ROOT) + macros + ("HOLD" to 1f))
            })))

        if (!quick) {
            val presets = CircuitPresets.all().map { patch ->
                clip("PRESETS", slug(patch.name), patch.name, "${patch.voice.name}; dry preset, internal canyon retained.", patch.voice, patch.macros)
            }
            section("PRESETS", "Twelve starting points", "Preset names and sound remain candidates pending owner listening.", listOf(group("Roster", presets)))
            val kit = SynthKits.circuit().mapIndexed { i, pad ->
                val arranged = requireNotNull(pad) { "CIRCUIT kit pad ${i + 1} is empty" }
                val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe))
                val patch = requireNotNull(recipe.patch) as CircuitPatch
                clip("KIT", "a%02d_%s".format(i + 1, slug(patch.name)), "A%02d %s".format(i + 1, patch.name),
                    "Kit recipe before rack treatment; dry Circuit synthesis.", patch.voice, patch.macros)
            }
            section("KIT", "Sixteen-pad kit", "Each kit recipe rendered dry beside its raw source level.", listOf(group("Pad order", kit)))
        }

        val manifest = Json.write(obj(
            "engine" to str("CIRCUIT"), "formatVersion" to num(1), "quick" to JsonValue.Bool(quick),
            "variants" to num(count), "acceptance" to str("Owner listening verdict pending"), "sections" to arr(sections),
        ))
        File(root, "manifest.json").writeText(manifest + "\n")
        val resource = CircuitAuditionGenerator::class.java.getResourceAsStream("/audition/circuit-audition.html")
            ?: error("missing CIRCUIT listening page")
        val template = resource.use { it.readBytes().toString(Charsets.UTF_8) }
        val marker = "/* CIRCUIT_MANIFEST */null"
        require(marker in template) { "the listening page has no manifest marker" }
        File(root, "index.html").writeText(template.replace(marker, manifest.replace("<", "\\u003c")))
        File(root, "measurements.json").writeText(Json.write(measurements.toJson(quick)) + "\n")
        println("wrote $count variants (${count * 2} WAVs), diagnostics, measurements.json, manifest.json and index.html under ${root.absolutePath}")
        if (quick) println("development smoke pack only; run without --quick for the acceptance matrix")
    }

    private data class AudioStats(val peak: Double, val dc: Double, val rms: Double)

    /** Statistics of the output-rate floats handed to the WAV writer, before PCM quantization. */
    private fun audioStats(samples: FloatArray): AudioStats {
        var peak = 0.0
        var sum = 0.0
        var energy = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            peak = maxOf(peak, abs(value))
            sum += value
            energy += value * value
        }
        val count = samples.size.coerceAtLeast(1)
        return AudioStats(peak, sum / count, sqrt(energy / count))
    }

    private class MeasuredRange {
        private var minimum = Double.POSITIVE_INFINITY
        private var maximum = Double.NEGATIVE_INFINITY
        private var minimumClip = ""
        private var maximumClip = ""

        fun add(value: Double, clip: String) {
            if (value < minimum) { minimum = value; minimumClip = clip }
            if (value > maximum) { maximum = value; maximumClip = clip }
        }

        fun toJson(): JsonValue = if (!minimum.isFinite()) JsonValue.Null else obj(
            "min" to num(minimum), "max" to num(maximum),
            "minClip" to str(minimumClip), "maxClip" to str(maximumClip),
        )
    }

    /** Accumulates one already-rendered report per variant, including cheap whole-buffer measures. */
    private class Measurements {
        private var variants = 0
        private var guardedVariants = 0
        private var loopCount = 0
        private var recoveries = 0L
        private var totalRenderMs = 0.0
        private val durations = MeasuredRange()
        private val fullMixDurations = MeasuredRange()
        private val renderTimes = MeasuredRange()
        private val heapBoundaries = MeasuredRange()
        private val seam = MeasuredRange()
        private val convergence = MeasuredRange()
        private val rawPeak = MeasuredRange()
        private val enginePeak = MeasuredRange()
        private val matchedPeak = MeasuredRange()
        private val rawDc = MeasuredRange()
        private val engineDc = MeasuredRange()
        private val matchedDc = MeasuredRange()
        private val rawRms = MeasuredRange()
        private val engineRms = MeasuredRange()
        private val matchedRms = MeasuredRange()
        private val classes = linkedMapOf<String, Int>()

        fun record(clip: String, report: Circuit.Report, raw: AudioStats, engine: AudioStats, matched: AudioStats,
                   renderMs: Double, heapBefore: Long, heapAfter: Long, guard: Boolean, classification: DrumClass) {
            variants++
            if (guard) {
                guardedVariants++
                fullMixDurations.add(report.snip.frameCount.toDouble() / report.snip.sampleRate, clip)
            }
            recoveries += report.recoveries
            totalRenderMs += renderMs
            durations.add(report.snip.frameCount.toDouble() / report.snip.sampleRate, clip)
            renderTimes.add(renderMs, clip)
            heapBoundaries.add(heapBefore.toDouble(), "$clip:before")
            heapBoundaries.add(heapAfter.toDouble(), "$clip:after")
            rawPeak.add(raw.peak, clip)
            enginePeak.add(engine.peak, clip)
            matchedPeak.add(matched.peak, clip)
            rawDc.add(abs(raw.dc), clip)
            engineDc.add(abs(engine.dc), clip)
            matchedDc.add(abs(matched.dc), clip)
            rawRms.add(raw.rms, clip)
            engineRms.add(engine.rms, clip)
            matchedRms.add(matched.rms, clip)
            classes[classification.name] = (classes[classification.name] ?: 0) + 1
            report.loop?.let {
                loopCount++
                seam.add(it.seamError, clip)
                convergence.add(it.convergenceError, clip)
            }
        }

        fun toJson(quick: Boolean): JsonValue = obj(
            "engine" to str("CIRCUIT"), "formatVersion" to num(1), "quick" to JsonValue.Bool(quick),
            "complete" to JsonValue.Bool(true),
            "variants" to num(variants), "wavFiles" to num(variants * 2), "classifierGuardedVariants" to num(guardedVariants),
            "recoveriesTotal" to num(recoveries), "loopCount" to num(loopCount), "totalRenderMs" to num(totalRenderMs),
            "durationSeconds" to durations.toJson(), "fullMixDurationSeconds" to fullMixDurations.toJson(), "renderMs" to renderTimes.toJson(),
            "seamError" to seam.toJson(), "convergenceError" to convergence.toJson(),
            "audioMeasure" to str("Output-rate floating point before PCM encoding; DC is whole-buffer arithmetic mean; RMS is whole-buffer RMS"),
            "raw" to obj("peak" to rawPeak.toJson(), "absoluteDc" to rawDc.toJson(), "rms" to rawRms.toJson()),
            "engineNormalized" to obj("peak" to enginePeak.toJson(), "absoluteDc" to engineDc.toJson(), "rms" to engineRms.toJson()),
            "auditionMatched" to obj("peak" to matchedPeak.toJson(), "absoluteDc" to matchedDc.toJson(), "rms" to matchedRms.toJson()),
            "heapBoundaryBytes" to heapBoundaries.toJson(), "memoryMeasure" to str("JVM used heap at render boundaries, not peak memory"),
            "classifications" to JsonValue.Obj(classes.mapValues { num(it.value) }),
            "listeningVerdict" to str("Owner listening verdict pending"),
        )
    }

    private fun diagnosticJson(report: Circuit.Report, elapsedMs: Double, heapBefore: Long, heapAfter: Long): JsonValue = obj(
        "renderMs" to num(elapsedMs), "heapBeforeBytes" to num(heapBefore), "heapAfterBytes" to num(heapAfter),
        "memoryMeasure" to str("JVM used heap at render boundaries, not peak memory"),
        "rawSampleBytes" to num(report.raw.size.toLong() * 4), "rawPeak" to num(report.rawPeak),
        "recoveries" to num(report.recoveries), "eventCount" to num(report.events.size), "pathCount" to num(report.paths.size),
        "rates" to obj(
            "orbitHz" to num(report.rates.orbitHz), "paceHz" to num(report.rates.paceHz),
            "requestedOrbitHz" to num(report.rates.requestedOrbitHz), "requestedPaceHz" to num(report.rates.requestedPaceHz),
            "loopSeconds" to num(report.rates.loopSeconds),
        ),
        "loop" to (report.loop?.let {
            obj("seamError" to num(it.seamError), "convergenceError" to num(it.convergenceError), "prerollCycles" to num(it.prerollCycles))
        } ?: JsonValue.Null),
        "events" to arr(report.events.map {
            obj("source" to num(it.source), "kind" to str(it.kind), "timeSeconds" to num(it.timeSeconds),
                "energy" to num(it.energy), "reply" to JsonValue.Bool(it.reply), "depth" to num(it.depth),
                "trigger" to str(it.reason),
                "emittedSeconds" to (it.emittedSeconds?.let(::num) ?: JsonValue.Null),
                "observerCueSeconds" to (it.observerCueSeconds?.let(::num) ?: JsonValue.Null),
                "cueSeconds" to (it.cueSeconds?.let(::num) ?: JsonValue.Null),
                "cueSource" to (it.cueSource?.let(::num) ?: JsonValue.Null),
                "reflection" to (it.reflection?.let(::num) ?: JsonValue.Null))
        }),
        "paths" to arr(report.paths.map {
            obj("timeSeconds" to num(it.timeSeconds), "source" to num(it.source), "x" to num(it.x), "y" to num(it.y),
                "directMeters" to num(it.directMeters), "reflectedMeters" to arr(it.reflectedMeters.map(::num)))
        }),
    )

    private fun macroJson(macros: Map<String, Float>) = JsonValue.Obj(macros.mapValues { num(it.value) })
    private fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*entries))
    private fun arr(items: List<JsonValue>) = JsonValue.Arr(items)
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = if (value == 0f || value == 1f) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)
    private fun slug(name: String) = name.lowercase(java.util.Locale.ROOT).replace(' ', '_')
}
