package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A source-selection audition, not a replacement preset bank. Each body runs through
 * RATTLE's unchanged gate network. Source recipes keep the diagnostic choice explicit.
 */
object WickSourceStudyGenerator {
    private const val REFERENCE_REVISION = "5cd0741219089f71e826a7761df8e576a1dbf81f"
    private const val RAW_GAIN = .5f
    private const val ASSEMBLY_GAIN = .25f
    private const val BPM = 100
    private val labels = mapOf(
        Wick.RattleSource.CURRENT to "Current body",
        Wick.RattleSource.WARM_WIRE to "Warm wire",
        Wick.RattleSource.HOLLOW_REED to "Hollow reed",
        Wick.RattleSource.RUBBED_GLASS to "Rubbed glass",
    )
    private val descriptions = mapOf(
        Wick.RattleSource.CURRENT to "The connected RATTLE reference, with its folded FM source.",
        Wick.RattleSource.WARM_WIRE to "A rounded harmonic body, shaped toward a soft wire or string tone.",
        Wick.RattleSource.HOLLOW_REED to "A hollow odd-harmonic body, shaped toward a soft reed tone.",
        Wick.RattleSource.RUBBED_GLASS to "A pitched body with quiet half-order color, shaped toward rubbed glass.",
    )
    private data class Note(val beat: Double, val midi: Int, val velocity: Float)
    private val score = listOf(
        Note(0.0, 48, .90f), Note(.75, 55, .55f), Note(1.5, 51, .70f), Note(2.75, 60, .40f),
        Note(4.0, 55, 1f), Note(4.75, 51, .60f), Note(5.5, 48, .80f), Note(7.0, 60, .45f),
    )
    private data class Key(val source: Wick.RattleSource, val midi: Int, val velocity: Float, val held: Boolean)
    private data class Render(val probe: Wick.Probe, val millis: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/wick-source-study").absoluteFile
        root.mkdirs()
        File(root, "source-study.json").delete()
        val cache = linkedMapOf<Key, Render>()
        val entries = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        fun render(source: Wick.RattleSource, midi: Int, velocity: Float = 1f, held: Boolean = false): Render {
            val key = Key(source, midi, velocity, held)
            return cache.getOrPut(key) {
                val began = System.nanoTime()
                val probe = Wick.inspect(WickVoice.RATTLE, macros(held), midi, velocity, Wick.Options(rattleSource = source))
                val millis = (System.nanoTime() - began) / 1e6
                check(probe.raw.isNotEmpty() && probe.raw.all { it.isFinite() } && probe.samples.all { it.isFinite() })
                check(probe.diagnostics.rejectedEvents == 0) { "$key rejected gate events" }
                check(probe.diagnostics.rawPeak in .00001..4.0 && abs(probe.samples.average()) < .015)
                if (source != Wick.RattleSource.CURRENT) {
                    val reference = render(Wick.RattleSource.CURRENT, midi, velocity, held).probe
                    check(probe.diagnostics.events == reference.diagnostics.events) { "$key changed the gate event schedule" }
                    check(probe.diagnostics.replaySourceEvents == reference.diagnostics.replaySourceEvents) { "$key changed causal replay provenance" }
                }
                Render(probe, millis)
            }
        }
        // C4 is first so a first listening pass compares bodies at one fixed pitch.
        for (midi in listOf(60, 48, 72)) for (source in Wick.RattleSource.entries) {
            val id = id(source, midi.toString())
            val rendered = render(source, midi)
            val repeated = if (midi == 60) Wick.inspect(WickVoice.RATTLE, macros(), midi,
                options = Wick.Options(rattleSource = source)) else null
            repeated?.let {
                check(it.raw.contentEquals(rendered.probe.raw) && it.samples.contentEquals(rendered.probe.samples)) { "$id is not deterministic" }
                check(it.diagnostics.events == rendered.probe.diagnostics.events)
            }
            val measured = linkedMapOf<String, Any?>("renderMs" to rendered.millis,
                "deterministic" to repeated?.let { true }, "gateEventsEqualCurrent" to true)
            entries += export(root, id, source, midi, 1f, false, "source-register", "Four bodies · same movement",
                "source-study", rendered.probe.samples, rendered.probe.raw, rendered.probe, measured,
                descriptions.getValue(source) + " Same RATTLE controls, gate events and playing velocity.")
        }
        for (source in Wick.RattleSource.entries) {
            val rendered = render(source, 60, held = true)
            val probe = rendered.probe
            val loop = requireNotNull(probe.diagnostics.loop)
            check(loop.converged && loop.seam < Keys.MAX_SEAM_ERROR)
            val repeated = Wick.inspect(WickVoice.RATTLE, macros(true), 60, options = Wick.Options(rattleSource = source))
            check(probe.raw.contentEquals(repeated.raw) && probe.samples.contentEquals(repeated.samples))
            entries += export(root, id(source, "held"), source, 60, 1f, true, "source-held", "Four bodies · held movement",
                "held-source-study", probe.samples, probe.raw, probe,
                linkedMapOf("renderMs" to rendered.millis, "deterministic" to true, "gateEventsEqualCurrent" to true),
                descriptions.getValue(source) + " A genuine converged HOLD period; repeat playback exposes the join.")
        }
        for (source in Wick.RattleSource.entries) {
            val began = System.nanoTime()
            val notes = score.map { note -> Triple(note, (note.beat * 60.0 / BPM * Dsp.RATE).roundToInt(), render(source, note.midi, note.velocity)) }
            val scoreFrames = (8 * 60.0 / BPM * Dsp.RATE).roundToInt()
            val frames = maxOf(scoreFrames, notes.maxOf { (_, at, rendered) -> at + rendered.probe.raw.size })
            val assembled = FloatArray(frames)
            for ((_, at, rendered) in notes) for (i in rendered.probe.raw.indices) assembled[at + i] += rendered.probe.raw[i] * ASSEMBLY_GAIN
            check(assembled.all { it.isFinite() } && assembled.maxOf { abs(it) } < .98f)
            val phraseId = id(source, "phrase")
            val events = notes.mapIndexed { index, (note, at, rendered) -> linkedMapOf<String, Any?>(
                "index" to index, "beat" to note.beat, "onsetFrame" to at, "midi" to note.midi,
                "velocity" to note.velocity, "sourceFrames" to rendered.probe.raw.size,
                "options" to options(source), "macros" to macros(), "gateEventsEqualCurrent" to true,
                "sourceRawFile" to savePhraseSource(root, source, note, rendered.probe),
                "sourceRawExportGain" to RAW_GAIN,
            ) }
            val recipe = linkedMapOf<String, Any?>(
                "format" to "wick-source-study-sequence-v1", "engine" to "WICK", "voice" to "RATTLE",
                "source" to source.name, "sampleRate" to Dsp.RATE, "bpm" to BPM, "bars" to 2,
                "scoreFrames" to scoreFrames, "outputFrames" to frames, "assemblyGain" to ASSEMBLY_GAIN,
                "events" to events,
                "assembly" to "Call Wick.inspect for each event with the saved macros, MIDI, velocity and diagnostic options. Sum probe.raw at onsetFrame at fixed gain 0.25, retaining complete note tails. Apply AuditionLevel only to the completed phrase. No per-note normalization or extra velocity multiplication.",
                "state" to "Each scored note starts a new gate network; its FEED and cascade remain active inside that note. This finite phrase does not share reserve state between scored notes and does not claim a seamless score loop.",
            )
            val measured = linkedMapOf<String, Any?>(
                "renderMs" to (System.nanoTime() - began) / 1e6, "noteCount" to notes.size,
                "scoreDurationSeconds" to scoreFrames.toDouble() / Dsp.RATE,
                "ringOutSeconds" to (frames - scoreFrames).toDouble() / Dsp.RATE,
                "assemblyGain" to ASSEMBLY_GAIN, "perNoteNormalization" to false,
                "gateEventsEqualCurrent" to true,
            )
            entries += export(root, phraseId, source, null, null, false, "source-phrases", "Four bodies · same short phrase",
                "pattern", assembled, assembled, null, measured,
                descriptions.getValue(source) + " The same eight notes at 100 BPM, with full finite tails and one common assembly gain.", recipe)
        }
        val groups = listOf(
            Triple("source-register", "Four bodies · same movement", "Compare Current body, Warm wire, Hollow reed and Rubbed glass at C4, then C3 and C5. Controls, velocities and gate event schedules are held fixed. The three new bodies are source studies, not accepted voice replacements."),
            Triple("source-held", "Four bodies · held movement", "The same recovering gate movement with four different continuous bodies. Each HOLD clip is independently converged and supplies its genuine preceding-period context."),
            Triple("source-phrases", "Four bodies · same short phrase", "An identical eight-note, two-bar phrase at 100 BPM. Native assemblies preserve playing velocity; only complete phrases are loudness matched."),
        ).map { (id, title, description) -> linkedMapOf("id" to id, "title" to title, "description" to description,
            "clipIds" to entries.filter { it["groupId"] == id }.map { it.getValue("id") }) }
        check(entries.size == 20 && entries.all { it["status"] == "ready" })
        writeJson(File(root, "source-study.json"), linkedMapOf(
            "format" to "wick-source-study-v1", "generatedAt" to Instant.now().toString(),
            "status" to "requires-owner-listening", "referenceRevision" to REFERENCE_REVISION,
            "sampleRate" to Dsp.RATE, "channels" to 1, "oversampling" to 4,
            "scope" to "Three diagnostic source alternatives plus the unchanged connected RATTLE reference. The other five voices and ordinary RATTLE patches are unchanged. These names describe synthetic tonal analogies, not physical instrument models.",
            "audioNote" to "Matched PCM16 previews use AuditionLevel on the complete clip. Native and common-gain raw PCM24 are retained locally. Held contexts contain an independently synthesized preceding period and current period at the matched export gain.",
            "clipCount" to entries.size, "renderSeconds" to (System.nanoTime() - started) / 1e9,
            "groups" to groups, "entries" to entries,
        ))
        println("WICK source study: ${entries.size} ready clips in $root")
    }

    private fun export(root: File, id: String, source: Wick.RattleSource, midi: Int?, velocity: Float?, held: Boolean,
                       groupId: String, group: String, kind: String, samples: FloatArray, raw: FloatArray,
                       probe: Wick.Probe?, measured: Map<String, Any?>, description: String,
                       sequenceRecipe: Map<String, Any?>? = null): Map<String, Any?> {
        val native = Snip(samples, 1, Dsp.RATE)
        val matched = AuditionLevel.level(native)
        val rawSnip = Snip(FloatArray(raw.size) { raw[it] * RAW_GAIN }, 1, Dsp.RATE)
        check(native.peak() < 1f && matched.peak() < 1f && rawSnip.peak() < 1f)
        val file = "wav/$id.wav"
        val nativeFile = "native/$id.wav"
        val rawFile = "raw/$id.wav"
        for (path in listOf(file, nativeFile, rawFile)) File(root, path).parentFile.mkdirs()
        WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root, nativeFile), native, WavWriter.BitDepth.PCM_24)
        WavWriter.write(File(root, rawFile), rawSnip, WavWriter.BitDepth.PCM_24)
        val decoded = WavReader.read(File(root, file))
        check(decoded.frameCount == matched.frameCount && decoded.channels == 1 && decoded.sampleRate == Dsp.RATE)
        val metrics = linkedMapOf<String, Any?>(
            "durationSeconds" to native.durationSeconds, "finite" to true,
            "rawPeak" to raw.maxOf { abs(it) }, "rawRms" to TangleMeasure.rms(raw), "rawDc" to raw.average(),
            "nativePeak" to native.peak(), "matchedPeak" to matched.peak(),
            "matchedRms" to TangleMeasure.rms(matched.samples), "rawExportGain" to RAW_GAIN,
        ).apply { putAll(measured) }
        if (midi != null) {
            val pitch = requireNotNull(Pitch.detect(native, .05f, .25f)) { "$id has no opening pitch" }
            metrics["pitchHz"] = pitch.hz
            metrics["pitchConfidence"] = pitch.confidence
            metrics["pitchCents"] = FineTuning.cents(pitch.hz.toDouble(), Keys.midiHz(midi).toDouble())
            metrics["pitchWindowStartSeconds"] = .05
            metrics["pitchWindowSeconds"] = .25
            check(abs(metrics.getValue("pitchCents") as Double) < 35 && pitch.confidence > .3f) { "$id lost requested root: $metrics" }
        }
        var seamContextFile: String? = null
        if (held) {
            val context = requireNotNull(probe?.seamContext)
            val gain = (TangleMeasure.rms(matched.samples) / TangleMeasure.rms(samples).coerceAtLeast(1e-20)).toFloat()
            seamContextFile = "diagnostics/${id}_seam-context.wav"
            File(root, seamContextFile).parentFile.mkdirs()
            WavWriter.write(File(root, seamContextFile), Snip(FloatArray(context.size) { context[it] * gain }, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
            val pcm = WavReader.read(File(root, seamContextFile)).samples
            val start = pcm.size - samples.size
            check(start >= 256)
            var difference = 0.0
            var energy = 0.0
            for (i in start - 256 until start) {
                difference += (pcm[i + samples.size] - pcm[i]).toDouble().let { it * it }
                energy += pcm[i].toDouble() * pcm[i]
            }
            val error = if (energy > 0) difference / energy else if (difference == 0.0) 0.0 else Double.POSITIVE_INFINITY
            var derivative = 0.0
            var count = 0
            for (range in listOf(start + 1 until minOf(start + 256, pcm.size), maxOf(start + 1, pcm.size - 255) until pcm.size)) {
                for (i in range) { derivative += (pcm[i] - pcm[i - 1]).toDouble().let { it * it }; count++ }
            }
            val wrapRatio = abs(pcm[start] - pcm.last()) / (sqrt(derivative / count.coerceAtLeast(1)) + 1.0 / 32768)
            check(error.isFinite() && error < Keys.MAX_SEAM_ERROR && wrapRatio < 8) { "$id PCM HOLD join failed: error$error wrap$wrapRatio" }
            metrics["exportedSeamError"] = error
            metrics["exportedWrapStepRatio"] = wrapRatio
            metrics["seamComparisonDifferenceEnergy"] = difference
            metrics["seamComparisonReferenceEnergy"] = energy
            metrics["seamContextFrames"] = pcm.size
            metrics["seamLoopStartFrame"] = start
        }
        val recipeFile = "recipes/$id.json"
        val patch = midi?.let { WickPatch(labels.getValue(source).take(14), WickVoice.RATTLE, macros(held), midi, velocity ?: 1f) }
        writeJson(File(root, recipeFile), sequenceRecipe ?: linkedMapOf(
            "format" to "wick-diagnostic-recipe-v1", "description" to "Call Wick.inspect with this patch and the explicit diagnostic source option. This is not a normal saved patch.",
            "patch" to patch?.toJsonValue(), "options" to options(source), "referenceRevision" to REFERENCE_REVISION,
        ))
        val diagnostics = probe?.diagnostics?.let { d -> linkedMapOf<String, Any?>(
            "events" to d.events.map(::event), "eventCount" to d.events.size, "rejectedEvents" to d.rejectedEvents,
            "minAvailability" to d.minAvailability, "maxAvailability" to d.maxAvailability,
            "maxCharge" to d.maxCharge, "maxOpening" to d.maxOpening, "maxFilterState" to d.maxFilterState,
            "loop" to d.loop?.let { l -> linkedMapOf("periodFrames" to l.periodFrames, "periodSeconds" to l.periodSeconds,
                "attempts" to l.attempts, "stateError" to l.stateError, "seam" to l.seam, "converged" to l.converged,
                "strategy" to l.strategy, "audioError" to l.audioError, "requestedHz" to l.requestedHz, "actualHz" to l.actualHz) },
            "replaySourceEvents" to d.replaySourceEvents.map(::event),
            "replaySourcePeriodStartSeconds" to d.replaySourcePeriodStartSeconds,
        ) } ?: linkedMapOf("loop" to null, "sequence" to sequenceRecipe)
        val diagnosticsFile = "diagnostics/$id.json"
        writeJson(File(root, diagnosticsFile), linkedMapOf("id" to id, "source" to source.name, "metrics" to metrics,
            "diagnostics" to diagnostics, "seamContextFile" to seamContextFile))
        println("WICK source study $id: ${native.durationSeconds}s")
        return linkedMapOf(
            "id" to id, "title" to labels.getValue(source), "description" to description, "kind" to kind,
            "voice" to "RATTLE", "sourceFamily" to source.name, "rattleSource" to source.name,
            "groupId" to groupId, "group" to group, "midi" to midi, "velocity" to velocity,
            "macros" to if (kind == "pattern") emptyMap<String, Float>() else macros(held), "options" to options(source),
            "loop" to held, "file" to file, "nativeFile" to nativeFile, "rawFile" to rawFile,
            "patchFile" to null, "recipeFile" to recipeFile,
            "sequenceRecipeFile" to if (kind == "pattern") recipeFile else null,
            "diagnosticsFile" to diagnosticsFile, "seamContextFile" to seamContextFile,
            "metrics" to metrics, "diagnostics" to diagnostics, "status" to "ready", "failures" to emptyList<String>(),
            "bpm" to if (kind == "pattern") BPM else null, "bars" to if (kind == "pattern") 2 else null,
        )
    }

    private fun id(source: Wick.RattleSource, suffix: String) = "source_${source.name.lowercase()}_$suffix"
    private fun savePhraseSource(root: File, source: Wick.RattleSource, note: Note, probe: Wick.Probe): String {
        val stem = "${source.name.lowercase()}_${note.midi}_${(note.velocity * 100).roundToInt()}"
        val file = "pattern-sources/$stem.wav"
        File(root, file).parentFile.mkdirs()
        WavWriter.write(File(root, file), Snip(FloatArray(probe.raw.size) { probe.raw[it] * RAW_GAIN }, 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
        writeJson(File(root, "pattern-sources/${stem}_diagnostics.json"), linkedMapOf(
            "midi" to note.midi, "velocity" to note.velocity, "macros" to macros(), "options" to options(source),
            "events" to probe.diagnostics.events.map(::event),
            "replaySourceEvents" to probe.diagnostics.replaySourceEvents.map(::event),
            "replaySourcePeriodStartSeconds" to probe.diagnostics.replaySourcePeriodStartSeconds,
        ))
        return file
    }
    private fun macros(held: Boolean = false) = Wick.defaults(WickVoice.RATTLE) + ("HOLD" to if (held) 1f else 0f)
    private fun options(source: Wick.RattleSource) = linkedMapOf<String, Any?>(
        "disableCascade" to false, "constantAvailability" to false, "gainOnly" to false, "oversample" to 4,
        "legacyBubbleEnvelope" to false, "legacyRattleEnvelope" to false, "rattleSource" to source.name,
    )
    private fun event(e: Wick.Event) = linkedMapOf<String, Any?>(
        "id" to e.id, "parentEventId" to e.parentEventId, "timeSeconds" to e.timeSeconds,
        "parentStage" to e.parentStage, "releaseSeconds" to e.releaseSeconds, "destination" to e.destination,
        "requestedStrength" to e.requestedStrength, "admittedStrength" to e.admittedStrength,
        "availabilityBefore" to e.availabilityBefore, "availabilityAfter" to e.availabilityAfter,
        "kind" to e.kind, "derivation" to e.derivation, "sourceEventId" to e.sourceEventId,
    )
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported WICK source-study JSON ${value.javaClass.name}")
    }
}
