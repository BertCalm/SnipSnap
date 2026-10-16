package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Real JVM-engine listening evidence. These replay recipes are audition probes, not production patches. */
object CreaseAuditionGenerator {
    private const val MODEL = 1
    private const val ZIP_LIMIT = 24L * 1024 * 1024
    private val controls = listOf("FOLD", "PRESSURE", "DRAG", "SPLIT", "RELAX")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val interactions = listOf("FOLD" to "PRESSURE", "PRESSURE" to "DRAG", "SPLIT" to "DRAG", "PRESSURE" to "RELAX")

    private data class Clip(
        val id: String,
        val title: String,
        val section: String,
        val voice: CreaseVoice,
        val macros: Map<String, Float>,
        val description: String,
        val midi: Int = 60,
        val velocity: Float = .8f,
        val history: CreaseHistory = CreaseHistory.STATEFUL,
        val coupling: Boolean = true,
        val oversample: Int = 4,
        val control: String? = null,
        val step: Float? = null,
        val pair: String? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val recipeArg = args.firstOrNull { it.startsWith("--recipe=") }?.substringAfter('=')
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/crease-audition")
        root.mkdirs()
        val clips = if (recipeArg == null) cases() else listOf(readRecipe(File(recipeArg)))
        val commit = sourceCommit()
        val generatedAt = Instant.now().toString()
        val entries = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        val comparisonSamples = mutableMapOf<String, FloatArray>()

        for ((index, clip) in clips.withIndex()) {
            require(clip.macros.keys.containsAll(controls + "HOLD")) { "${clip.id}: missing controls" }
            require(clip.macros.values.all { it.isFinite() && it in 0f..1f }) { "${clip.id}: control outside 0..1" }
            require(clip.midi in 0..127 && clip.velocity in 0f..1f) { "${clip.id}: invalid performance" }
            val began = System.nanoTime()
            val probe = Crease.synthesize(clip.voice, clip.macros, clip.midi, clip.velocity,
                clip.history, clip.coupling, oversample = clip.oversample, normalize = true)
            val renderMs = (System.nanoTime() - began) / 1e6
            val raw = Snip(probe.prelevel, channels = 1, sampleRate = probe.sampleRate)
            val native = Snip(probe.samples, channels = 1, sampleRate = probe.sampleRate)
            require(raw.samples.isNotEmpty() && native.samples.isNotEmpty()) { "${clip.id}: empty render" }
            require(raw.samples.all { it.isFinite() && abs(it) <= 1f }) { "${clip.id}: raw audio is nonfinite or clips PCM16" }
            require(native.samples.all { it.isFinite() && abs(it) <= 1f }) { "${clip.id}: normalized audio is nonfinite or clips PCM16" }
            val matched = AuditionLevel.level(native)
            val audioPath = "audio/${clip.id}.wav"
            val rawPath = "raw/${clip.id}.wav"
            val recipePath = "recipes/${clip.id}.json"
            WavWriter.write(File(root, audioPath), matched, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_16)
            val decoded = WavReader.read(File(root, audioPath))
            val classification = Classifier.classify(native)
            val pitch = Pitch.detect(native, fromSec = .12f, windowSec = .3f)
            val expected = Keys.midiHz(clip.midi).toDouble()
            val fundamental = FineTuning.measuredHz(native, expected.toFloat(), .12f, .4f)
            val held = probe.diagnostics.held
            val diagnostics = diagnosticMap(probe)
            check(probe.diagnostics.maxCoefficientBudget <= Crease.COEFFICIENT_BUDGET + 1e-6 && probe.diagnostics.minMapDerivative >= .1 - 1e-6) { "${clip.id}: phase map violates the monotonic coefficient bound" }
            check(probe.diagnostics.maxAbsState <= 1.0 + 1e-6) { "${clip.id}: deformation/load state exceeds its bound" }
            val metrics = linkedMapOf<String, Any?>(
                "durationSeconds" to native.durationSeconds,
                "rawPeak" to raw.peak(), "rawRms" to rms(raw.samples), "rawDc" to raw.samples.average(),
                "rawMin" to raw.samples.minOrNull(), "rawMax" to raw.samples.maxOrNull(),
                "peak" to native.peak(), "rms" to rms(native.samples), "dc" to native.samples.average(),
                "matchedPeak" to decoded.peak(), "matchedLoudness" to Loudness.of(decoded),
                "finite" to true, "rawExportGain" to 1.0,
                "pitchHz" to pitch?.hz, "pitchCents" to pitch?.let { cents(it.hz.toDouble(), expected) },
                "pitchConfidence" to pitch?.confidence,
                "requestedRootHz" to expected, "fundamentalHz" to fundamental,
                "fundamentalErrorCents" to cents(fundamental, expected),
                "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
                "expectedClass" to if (held) "LOOP" else "TONAL",
                "renderMs" to renderMs, "renderCostRealtime" to renderMs / (native.durationSeconds * 1000),
                "normalizedSampleHash" to hash(probe.samples), "rawSampleHash" to hash(probe.prelevel),
            )
            if (held) {
                val previous = probe.previousCycle
                require(previous.isNotEmpty()) { "${clip.id}: held render has no preceding synthesis cycle" }
                require(previous.size == probe.samples.size) { "${clip.id}: held history has wrong frame count" }
                val seam = Keys.seamError(previous + probe.samples, previous.size)
                // The previous synthesis cycle receives exactly the scalar gain used by this listening file.
                // Compare encoded prior/current cycles; this cannot pass merely by repeating the exported loop.
                val nativeLoudness = Loudness.of(native).coerceAtLeast(1e-12f)
                val gain = Loudness.of(matched) / nativeLoudness
                val priorPcm = ByteArrayOutputStream().also { output ->
                    WavWriter.write(output, Snip(FloatArray(previous.size) { previous[it] * gain }, 1, probe.sampleRate), WavWriter.BitDepth.PCM_16)
                }.toByteArray()
                val exportedSeam = Keys.seamError(WavReader.read(priorPcm).samples + decoded.samples, previous.size)
                metrics["seamError"] = seam
                metrics["exportedSeamError"] = exportedSeam
                metrics["stateError"] = diagnostics["stateError"]
                check(seam < 1e-3 && exportedSeam < 1e-3) { "${clip.id}: held seam fails ($seam / $exportedSeam PCM16)" }
                check(probe.diagnostics.converged && probe.diagnostics.stateError < Crease.LOOP_STATE_TOLERANCE) { "${clip.id}: held state did not converge: $diagnostics" }
            }
            val comparison = clip.pair ?: if (clip.section == "macros") "${clip.voice}-${clip.control}" else null
            comparison?.let { key ->
                comparisonSamples[key]?.let { first ->
                    metrics["normalizedWaveformDistance"] = TangleMeasure.distance(first, probe.prelevel)
                    metrics["spectralDistance"] = TangleMeasure.spectralDistance(first, probe.prelevel)
                }
                comparisonSamples.putIfAbsent(key, probe.prelevel.copyOf())
            }
            val recipe = recipe(clip, commit)
            writeJson(File(root, recipePath), recipe)
            val trace = traceMap(probe)
            entries += linkedMapOf(
                "id" to clip.id, "title" to clip.title, "section" to clip.section,
                "voice" to clip.voice.name, "midi" to clip.midi, "velocity" to clip.velocity,
                "macros" to clip.macros, "audio" to audioPath, "rawAudio" to rawPath, "recipe" to recipePath,
                "mode" to when (clip.section) {
                    "memory" -> clip.history.name
                    "coupling" -> if (clip.coupling) "COUPLED" else "INDEPENDENT"
                    "aliasing" -> "${clip.oversample}×"
                    else -> null
                },
                "control" to clip.control, "step" to clip.step, "pair" to clip.pair,
                "description" to clip.description, "loop" to held, "oversample" to clip.oversample,
                "metrics" to metrics, "trace" to trace,
            )
            reports += linkedMapOf("id" to clip.id, "recipe" to recipe, "metrics" to metrics, "diagnostics" to diagnostics)
            if (clip.section == "voices") {
                val restored = readRecipe(File(root, recipePath))
                check(restored == clip) { "${clip.id}: recipe JSON changed the audition settings" }
                val again = Crease.synthesize(restored.voice, restored.macros, restored.midi, restored.velocity,
                    restored.history, restored.coupling, oversample = restored.oversample, normalize = true)
                check(probe.samples.contentEquals(again.samples) && probe.prelevel.contentEquals(again.prelevel)) { "${clip.id}: nondeterministic render" }
                metrics["deterministic"] = true
                metrics["recipeRoundtrip"] = true
            }
            println("CREASE ${index + 1}/${clips.size}: ${clip.id} (${renderMs.toInt()} ms)")
        }
        val measurements = entries.map { it.getValue("metrics") as Map<*, *> }
        fun maximum(name: String) = measurements.mapNotNull { (it[name] as? Number)?.toDouble() }.maxOrNull()
        val drumClasses = setOf("KICK", "SNARE", "CLAP", "HAT_OPEN", "HAT_CLOSED", "TOM")
        val standard = entries.filter { it["section"] in setOf("voices", "notes") }
        val drumReadings = standard.filter { (it.getValue("metrics") as Map<*, *>)["classifier"] in drumClasses }.map { it["id"] }
        val summary = linkedMapOf<String, Any?>(
            "title" to "Crease prototype evidence",
            "clips" to entries.size, "voices" to clips.map { it.voice.name }.distinct(),
            "sections" to entries.groupingBy { it["section"].toString() }.eachCount(),
            "finiteClips" to entries.size, "maxRawPeak" to maximum("rawPeak"),
            "maxSeamError" to maximum("seamError"), "maxExportedSeamError" to maximum("exportedSeamError"),
            "maxStateError" to maximum("stateError"),
            "maxPitchCents" to measurements.mapNotNull { (it["pitchCents"] as? Number)?.toDouble()?.let(::abs) }.maxOrNull(),
            "drumClassifications" to drumReadings,
            "pitchWarnings" to entries.filter { ((it.getValue("metrics") as Map<*, *>)["pitchCents"] as? Number)?.toDouble()?.let { c -> abs(c) > 25 } == true }.map { it["id"] },
            "renderSeconds" to (System.nanoTime() - started) / 1e9,
            "acceptance" to "Requires owner listening; pitch and classifier readings are diagnostics, not sonic acceptance.",
        )
        val header = linkedMapOf<String, Any?>(
            "engine" to "CREASE", "title" to "Crease — pressure leaves a shape", "model" to MODEL,
            "generatedAt" to generatedAt, "sourceCommit" to commit,
            "sampleRate" to Dsp.RATE, "bitDepth" to 16,
            "rawExportGain" to 1.0, "rawExportNote" to "Raw WAVs are the unscaled, prelevel engine output. Matched WAVs use SnipSnap's shared loudest-200-ms audition rule, with one scalar gain per clip.",
            "pitchMeasurement" to "House autocorrelation detector (40–1200 Hz), plus an independent spectral search within one semitone of the requested fundamental. Weak extreme textures may be uncertain.",
            "classifierNote" to "The generic classifier may label long finite notes LOOP. expectedClass records intended TONAL/LOOP behavior; the generic duration label does not establish pitched-engine acceptance.",
            "summary" to summary,
        )
        writeJson(File(root, "manifest.json"), header + ("clips" to entries))
        writeJson(File(root, "diagnostics/report.json"), header + mapOf(
            "machine" to mapOf("os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"), "java" to System.getProperty("java.version")),
            "reports" to reports,
        ))
        File(root, "README.txt").writeText("""
            CREASE model $MODEL — dry prototype listening pack
            Three original phase-deformation voices: ROUND, PINCH, RUBBER.
            audio/: mono 44.1 kHz PCM16, shared matched audition loudness.
            raw/: original prelevel output at unity gain; velocity dynamics are preserved.
            recipes/: standalone audition schema; these are not production SnipSnap Patch files.
            diagnostics/report.json: raw extrema, classifier, pitch, state convergence, sample hashes and actual render timings.
            Download ZIPs include matched audio, recipes and diagnostics. Raw WAVs remain individual downloads on the page.
            Held files contain one converged synthesis period. Use repeat on the audition page.
            Higher-rate A/B pairs isolate 4x versus 16x oversampling; their measured difference is not a direct alias-energy measurement.
            Reproduce all: ./gradlew :synth:generateCreaseAudition
            Replay one: ./gradlew :synth:generateCreaseAudition -PcreaseRecipe=/absolute/path/to/recipe.json -PcreaseOutput=/absolute/output
            Source commit: $commit (prototype changes may be uncommitted; recipes name model $MODEL).
            Final sonic acceptance requires listening.
        """.trimIndent() + "\n")
        val packs = writeDownloads(root)
        val downloads = packs.map { it.getValue("file").toString() }
        writeJson(File(root, "manifest.json"), header + mapOf("clips" to entries, "download" to downloads.firstOrNull(), "downloads" to downloads, "packs" to packs))
        println("CREASE wrote ${entries.size} clips under ${root.absolutePath}; ${downloads.size} download pack(s).")
        println(Json.write(json(summary)))
    }

    private fun cases(): List<Clip> = buildList {
        for (voice in CreaseVoice.entries) {
            val defaults = Crease.defaults(voice)
            val stem = voice.name.lowercase()
            add(Clip("voice-$stem", voice.name, "voices", voice, defaults,
                "Dry voice at middle C. Hear the pressure attack leave a moving deformation and then unfold."))
            for (control in controls) for (step in steps) {
                add(Clip("macro-$stem-${control.lowercase()}-${percent(step)}", "$control ${percent(step)}%", "macros", voice,
                    defaults + (control to step), "Only $control changes. Other controls, pitch and velocity stay at the voice defaults.",
                    control = control, step = step))
            }
            val expressive = defaults + mapOf("FOLD" to .60f, "PRESSURE" to .85f, "DRAG" to .85f, "SPLIT" to .7f, "RELAX" to .8f)
            for (history in CreaseHistory.entries) {
                add(Clip("memory-$stem-${history.name.lowercase()}", if (history == CreaseHistory.STATEFUL) "With memory" else "Instantaneous", "memory", voice,
                    expressive, "Identical controls and pressure gesture. Only deformation history changes; the instantaneous version follows the current target directly.",
                    history = history, pair = "memory-$stem"))
            }
            for (coupled in listOf(false, true)) {
                add(Clip("coupling-$stem-${if (coupled) "on" else "off"}", if (coupled) "Layers coupled" else "Layers independent", "coupling", voice,
                    expressive, "Identical layers and controls. Only shape-sensitive cross-layer loading changes; oscillator pitch rates remain fixed.",
                    coupling = coupled, pair = "coupling-$stem"))
            }
            for (midi in listOf(36, 60, 84)) for (velocity in listOf(.25f, .6f, 1f)) {
                add(Clip("note-$stem-$midi-${percent(velocity)}", "MIDI $midi · velocity ${percent(velocity)}%", "notes", voice,
                    defaults, "Voice defaults across three registers and three velocities. Matched playback compares timbre; raw WAVs retain actual velocity levels.",
                    midi = midi, velocity = velocity))
            }
            add(Clip("held-$stem", "${voice.name} held motion", "held", voice, expressive + ("HOLD" to 1f),
                "One converged loop with a repeating pressure gesture. DRAG, SPLIT and RELAX continue shaping the motion. Enable repeat to inspect the wrap."))
            for (relax in listOf(0f, 1f)) {
                add(Clip("held-relax-$stem-${percent(relax)}", "${voice.name} · ${if (relax == 0f) "fast" else "slow"} relaxation", "held", voice,
                    expressive + mapOf("HOLD" to 1f, "RELAX" to relax),
                    "The same repeating pressure gesture and constant held amplitude. Only RELAX changes: compare how long the deformation afterimage survives each push.",
                    control = "RELAX", step = relax, pair = "held-relax-$stem"))
            }
            for (midi in listOf(36, 84)) {
                add(Clip("extreme-$stem-$midi", "${voice.name} · all high · MIDI $midi", "interactions", voice,
                    defaults + controls.associateWith { 1f }, "All five timbral controls at maximum. A bounded extreme, with HOLD kept finite.", midi = midi, velocity = 1f,
                    pair = "extreme-$stem"))
            }
            for (oversample in listOf(4, 16)) {
                add(Clip("alias-$stem-${oversample}x", "${voice.name} · ${oversample}×", "aliasing", voice,
                    defaults + controls.associateWith { 1f }, "Same MIDI 84 note, maximum controls and velocity; only internal oversampling changes. Compare residual high-register texture against the 16× reference.",
                    midi = 84, velocity = 1f, oversample = oversample, pair = "alias-$stem"))
            }
        }
        val voice = CreaseVoice.RUBBER
        for ((a, b) in interactions) for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            add(Clip("interaction-${a.lowercase()}-${percent(x)}-${b.lowercase()}-${percent(y)}", "$a ${percent(x)}% · $b ${percent(y)}%", "interactions", voice,
                Crease.defaults(voice) + mapOf(a to x, b to y), "RUBBER: four corners of $a × $b. Every remaining control, note and velocity stays fixed.",
                pair = "interaction-${a.lowercase()}-${b.lowercase()}"))
        }
    }

    private fun recipe(clip: Clip, commit: String) = linkedMapOf<String, Any?>(
        "schema" to "snipsnap.crease-audition", "schemaVersion" to 1, "model" to MODEL,
        "engine" to "CREASE", "id" to clip.id, "title" to clip.title, "section" to clip.section,
        "description" to clip.description, "voice" to clip.voice.name, "macros" to clip.macros,
        "midi" to clip.midi, "velocity" to clip.velocity, "history" to clip.history.name,
        "coupling" to clip.coupling, "oversample" to clip.oversample, "normalize" to true,
        "sampleRate" to Dsp.RATE, "sourceCommit" to commit,
        "control" to clip.control, "step" to clip.step, "pair" to clip.pair,
    )

    private fun readRecipe(file: File): Clip {
        val r = Json.parse(file.readText()).obj()
        require(r.getValue("schema").str() == "snipsnap.crease-audition" && r.getValue("model").int() == MODEL) { "Unsupported audition recipe schema/model" }
        fun optionalString(key: String) = r[key]?.takeUnless { it == JsonValue.Null }?.str()
        return Clip(r.getValue("id").str(), r.getValue("title").str(), r.getValue("section").str(),
            CreaseVoice.valueOf(r.getValue("voice").str()), r.getValue("macros").obj().mapValues { it.value.num().toFloat() },
            r.getValue("description").str(), r.getValue("midi").int(), r.getValue("velocity").num().toFloat(),
            CreaseHistory.valueOf(r.getValue("history").str()), r.getValue("coupling").bool(), r.getValue("oversample").int(),
            optionalString("control"), r["step"]?.takeUnless { it == JsonValue.Null }?.num()?.toFloat(), optionalString("pair"))
    }

    // Kept explicit so reports describe the actual DSP contract rather than Java object internals.
    private fun diagnosticMap(probe: CreaseProbe): Map<String, Any?> = probe.diagnostics.let { d ->
        linkedMapOf(
            "internalRawPeak" to d.rawPeak, "internalRawRms" to d.rawRms, "internalRawDc" to d.rawDc,
            "maxCoefficientBudget" to d.maxCoefficientBudget, "minMapDerivative" to d.minMapDerivative,
            "maxAbsState" to d.maxAbsState, "requestedHz" to d.requestedHz, "renderedHz" to d.renderedHz,
            "held" to d.held, "loopFrames" to d.loopFrames, "rootCycles" to d.rootCycles,
            "seamError" to d.seamError, "stateError" to d.stateError, "converged" to d.converged,
            "prerollCycles" to d.prerollCycles, "renderNanos" to d.renderNanos,
            "stateStart" to probe.loopStateStart.toList(), "stateEnd" to probe.loopStateEnd.toList(),
        )
    }

    private fun traceMap(probe: CreaseProbe): List<Map<String, Any?>> = probe.trace.let { t ->
        val count = minOf(128, t.timeSeconds.size)
        (0 until count).map { point ->
            val i = if (count <= 1) 0 else point * (t.timeSeconds.size - 1) / (count - 1)
            linkedMapOf("time" to t.timeSeconds[i], "pressure" to t.pressure[i],
                "deformationA" to t.deformationA[i], "deformationB" to t.deformationB[i],
                "loadA" to t.loadA[i], "loadB" to t.loadB[i])
        }
    }

    private fun writeDownloads(root: File): List<Map<String, Any?>> {
        val files = root.walkTopDown().filter { it.isFile && it.extension != "zip" && it.parentFile.name != "raw" }
            .sortedBy { it.relativeTo(root).path }.toList()
        // Partition by uncompressed bytes: compressed packs cannot exceed this conservative file budget.
        val groups = mutableListOf<MutableList<File>>()
        var bytes = 0L
        for (file in files) {
            require(file.length() < ZIP_LIMIT - 1024 * 1024) { "Asset too large for publication: $file" }
            if (groups.isEmpty() || bytes + file.length() > ZIP_LIMIT - 1024 * 1024) {
                groups.add(mutableListOf())
                bytes = 0L
            }
            groups.last() += file
            bytes += file.length()
        }
        return groups.mapIndexed { index, group ->
            val name = if (groups.size == 1) "crease-audition.zip" else "crease-audition-part-${index + 1}.zip"
            ZipOutputStream(File(root, name).outputStream().buffered()).use { zip ->
                zip.setLevel(9)
                for (file in group) {
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(File(root, name).length() < ZIP_LIMIT)
            mapOf("file" to name, "label" to if (groups.size == 1) "Matched audio + recipes" else "Matched audio + recipes · part ${index + 1} of ${groups.size}", "bytes" to File(root, name).length())
        }
    }

    private fun percent(value: Float) = (value * 100).toInt()
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun cents(hz: Double, expected: Double) = if (hz > 0 && hz.isFinite()) 1200 * ln(hz / expected) / ln(2.0) else null
    private fun hash(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun sourceCommit(): String = try {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().takeIf { process.waitFor() == 0 } ?: "unknown"
    } catch (_: Exception) { "unknown" }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile?.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> value.toDouble().takeIf { it.isFinite() }?.let { JsonValue.Num(it) } ?: JsonValue.Null
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
}
