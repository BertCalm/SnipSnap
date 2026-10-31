package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Finite musical evidence assembled from unlevelled, independently synthesized notes. */
internal object WickPatternGenerator {
    const val GROUP_ID = "musical-patterns"
    private const val BPM = 100
    private const val BARS = 2
    private const val ASSEMBLY_GAIN = .25f
    private const val RAW_EXPORT_GAIN = .5f
    // Keep the accepted three-voice arrangement exactly as rendered in the prior round.
    private val ensembleVoices = listOf(WickVoice.PLUCK, WickVoice.WOOD, WickVoice.BUBBLE)
    private val soloVoices = ensembleVoices + listOf(WickVoice.BREATH, WickVoice.FLICKER, WickVoice.RATTLE)
    private val options = linkedMapOf<String, Any?>(
        "disableCascade" to false, "constantAvailability" to false, "gainOnly" to false,
        "oversample" to 4, "legacyBubbleEnvelope" to false, "legacyRattleEnvelope" to false,
    )
    private data class Note(val beat: Double, val midi: Int, val velocity: Float)
    private val score = listOf(
        Note(0.0, 48, .90f), Note(.75, 55, .55f), Note(1.5, 51, .70f), Note(2.75, 60, .40f),
        Note(4.0, 55, 1f), Note(4.75, 51, .60f), Note(5.5, 48, .80f), Note(7.0, 60, .45f),
    )
    private data class Source(
        val patch: WickPatch, val probe: Wick.Probe, val patchFile: String, val rawFile: String,
        val diagnosticsFile: String, val rawFloatSha256: String, val renderMs: Double,
    )
    data class Result(
        val entries: List<Map<String, Any?>>, val failures: List<Map<String, Any?>>, val group: Map<String, Any?>,
    )

    fun generate(root: File): Result {
        val cache = linkedMapOf<String, Source>()
        val entries = mutableListOf<Map<String, Any?>>()
        val failures = mutableListOf<Map<String, Any?>>()
        val specs = soloVoices.map { "pattern_${it.name.lowercase()}" to it } + listOf("pattern_ensemble" to null)
        for ((id, voice) in specs) {
            val title = if (voice == null) "Three voices · interlocking phrase" else "${voice.name} · short musical phrase"
            val metadata = linkedMapOf<String, Any?>(
                "id" to id, "title" to title, "kind" to "pattern", "groupId" to GROUP_ID,
                "group" to "Six voices · short musical phrases", "voice" to (voice?.name ?: "ENSEMBLE"),
                "voices" to (voice?.let { listOf(it.name) } ?: ensembleVoices.map { it.name }),
                "midi" to null, "velocity" to null, "noteRange" to linkedMapOf("minMidi" to 48, "maxMidi" to 60),
                "velocityRange" to linkedMapOf("min" to .4f, "max" to 1f), "macros" to emptyMap<String, Float>(),
                "options" to options, "loop" to false, "bpm" to BPM, "bars" to BARS,
                "description" to if (voice == null) {
                    "The same two-bar C minor pentatonic score, shared in turn between PLUCK, WOOD and BUBBLE. Dry independently rendered notes, with full finite tails."
                } else if (voice == WickVoice.RATTLE) {
                    "Eight notes at 100 BPM, C3–C4, with the same timing, pitches and playing velocities as the other five solo voices. Listen for each note as a connected body with internal rattle motion. Voice defaults and their recovering gate network remain active; no effects."
                } else {
                    "Eight notes at 100 BPM, C3–C4, with the same timing, pitches and playing velocities as the other five solo voices. Voice defaults and their internal cascades remain active; no effects."
                },
            )
            try {
                val began = System.nanoTime()
                val notes = score.mapIndexed { index, note ->
                    val assignedVoice = voice ?: ensembleVoices[index % ensembleVoices.size]
                    val key = "${assignedVoice.name.lowercase()}_${note.midi}_${(note.velocity * 100).roundToInt()}"
                    val source = cache.getOrPut(key) { renderSource(root, key, assignedVoice, note) }
                    Triple(note, (note.beat * 60.0 / BPM * Dsp.RATE).roundToInt(), source)
                }
                val scoreFrames = (BARS * 4 * 60.0 / BPM * Dsp.RATE).roundToInt()
                val frames = maxOf(scoreFrames, notes.maxOf { (_, onset, source) -> onset + source.probe.raw.size })
                val sum = FloatArray(frames)
                for ((_, onset, source) in notes) for (frame in source.probe.raw.indices) {
                    sum[onset + frame] += source.probe.raw[frame]
                }
                check(sum.all { it.isFinite() }) { "$id has non-finite assembly samples" }
                val inputPeak = sum.maxOf { abs(it).toDouble() }
                val assembled = FloatArray(sum.size) { sum[it] * ASSEMBLY_GAIN }
                val native = Snip(assembled, 1, Dsp.RATE)
                check(native.peak() < .98f) { "$id exceeds fixed assembly headroom: peak ${native.peak()} at gain $ASSEMBLY_GAIN" }
                val matched = AuditionLevel.level(native)
                check(matched.samples.all { it.isFinite() } && matched.peak() < 1f) { "$id matched audio cannot be written without clipping" }
                val raw = Snip(FloatArray(assembled.size) { assembled[it] * RAW_EXPORT_GAIN }, 1, Dsp.RATE)
                val file = "wav/$id.wav"
                val nativeFile = "native/$id.wav"
                val rawFile = "raw/$id.wav"
                WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
                WavWriter.write(File(root, nativeFile), native, WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(root, rawFile), raw, WavWriter.BitDepth.PCM_24)
                val decoded = WavReader.read(File(root, file))
                check(decoded.frameCount == native.frameCount && decoded.sampleRate == Dsp.RATE && decoded.channels == 1)
                check(decoded.samples.indices.maxOf { abs(decoded.samples[it] - matched.samples[it]) } < 1f / 16384f) { "$id PCM encoding changed the assembled phrase" }
                val classification = Classifier.classify(native)
                val onset = Classifier.classify(Snip(assembled.copyOfRange(0, minOf(assembled.size, (1.2 * Dsp.RATE).toInt())), 1, Dsp.RATE))
                val metrics = linkedMapOf<String, Any?>(
                    "durationSeconds" to native.durationSeconds, "scoreDurationSeconds" to scoreFrames.toDouble() / Dsp.RATE,
                    "ringOutSeconds" to (frames - scoreFrames).toDouble() / Dsp.RATE, "noteCount" to notes.size,
                    "finite" to true, "rawPeak" to native.peak(), "rawRms" to TangleMeasure.rms(assembled), "rawDc" to assembled.average(),
                    "peak" to native.peak(), "rms" to TangleMeasure.rms(assembled), "dc" to assembled.average(),
                    "assemblyInputPeak" to inputPeak, "assemblyGain" to ASSEMBLY_GAIN,
                    "assemblyGainDb" to 20.0 * log10(ASSEMBLY_GAIN.toDouble()), "commonAssemblyGainGroup" to GROUP_ID,
                    "nativeSamplePeakHeadroomDb" to -20.0 * log10(native.peak().toDouble().coerceAtLeast(1e-30)),
                    "clippedSamples" to 0, "rawExportGain" to RAW_EXPORT_GAIN,
                    "nativeLoudness" to Loudness.of(native), "matchedLoudness" to Loudness.of(matched), "matchedPeak" to matched.peak(),
                    "classifier" to classification.drumClass.name, "classifierConfidence" to classification.confidence,
                    "onsetClassifier" to onset.drumClass.name, "onsetClassifierConfidence" to onset.confidence,
                    "onsetClassifierWindowSeconds" to 1.2,
                    "classifierMeasurement" to "The full musical phrase has varying notes and exceeds the classifier's 1.5-second LOOP shortcut. No single-root pitch result is claimed.",
                    "renderMs" to (System.nanoTime() - began) / 1e6,
                )
                val eventScore = notes.mapIndexed { index, (note, onsetFrame, source) -> linkedMapOf<String, Any?>(
                    "index" to index, "beat" to note.beat, "onsetFrame" to onsetFrame,
                    "onsetSeconds" to onsetFrame.toDouble() / Dsp.RATE, "midi" to note.midi, "velocity" to note.velocity,
                    "voice" to source.patch.voice.name, "patch" to source.patch.toJsonValue(), "patchFile" to source.patchFile,
                    "options" to options, "playback" to "complete-finite-one-shot", "sourceFrames" to source.probe.raw.size,
                    "sourceDurationSeconds" to source.probe.raw.size.toDouble() / Dsp.RATE,
                    "sourceRawFile" to source.rawFile, "sourceRawExportGain" to RAW_EXPORT_GAIN,
                    "sourceRawFloatSha256" to source.rawFloatSha256, "sourceDiagnosticsFile" to source.diagnosticsFile,
                    "sourceRawPeak" to source.probe.diagnostics.rawPeak, "sourceRawRms" to source.probe.diagnostics.rawRms,
                    "sourceRenderMs" to source.renderMs,
                ) }
                val recipeFile = "recipes/$id.json"
                val recipe = linkedMapOf<String, Any?>(
                    "format" to "wick-sequence-recipe-v1", "engine" to "WICK", "id" to id, "title" to title,
                    "bpm" to BPM, "bars" to BARS, "beatsPerBar" to 4, "sampleRate" to Dsp.RATE,
                    "scoreFrames" to scoreFrames, "outputFrames" to frames, "assemblyGain" to ASSEMBLY_GAIN,
                    "rawExportGain" to RAW_EXPORT_GAIN, "matchedLoudnessTarget" to .03,
                    "assembly" to "For each event call Wick.inspect with the saved patch and options. Sum probe.raw at onsetFrame, preserving complete finite tails, then multiply the complete sum by the common assemblyGain. Native WAV is that assembly. Raw WAV applies rawExportGain to it; matched WAV applies AuditionLevel once to the complete assembly. Do not use independently leveled probe.samples, normalize hits, or multiply velocity again.",
                    "state" to "Each note rebuilds oscillator, gates, availability and event queue. Recovery and cascades act within each note; this score does not share a recovery state between successive notes. Default FEED can generate additional articulations between scored onsets.",
                    "tail" to "Two bars of scored time followed by the complete final one-shot tails. This is finite playback, with no note-off, HOLD period or seamless-loop claim.",
                    "events" to eventScore,
                )
                writeJson(File(root, recipeFile), recipe)
                val diagnosticsFile = "diagnostics/$id.json"
                val diagnostics = linkedMapOf<String, Any?>(
                    "noteCount" to notes.size, "sourceGateEventCount" to notes.sumOf { it.third.probe.diagnostics.events.size },
                    "rejectedEvents" to notes.sumOf { it.third.probe.diagnostics.rejectedEvents }, "loop" to null,
                    "assemblyGain" to ASSEMBLY_GAIN, "perNoteNormalization" to false, "effects" to emptyList<String>(),
                )
                writeJson(File(root, diagnosticsFile), linkedMapOf("id" to id, "kind" to "pattern", "metrics" to metrics, "diagnostics" to diagnostics, "sequence" to recipe))
                entries += metadata + linkedMapOf(
                    "file" to file, "nativeFile" to nativeFile, "rawFile" to rawFile,
                    "patchFile" to null, "patch" to null, "recipeFile" to recipeFile, "sequenceRecipeFile" to recipeFile,
                    "diagnosticsFile" to diagnosticsFile, "metrics" to metrics, "diagnostics" to diagnostics,
                    "status" to "ready", "failures" to emptyList<String>(),
                )
                println("WICK musical phrase $id: ${notes.size} notes, peak ${native.peak()}, ${native.durationSeconds}s")
            } catch (error: Exception) {
                val reason = "${error.javaClass.simpleName}: ${error.message}"
                entries += metadata + linkedMapOf("status" to "failed", "failures" to listOf(reason))
                failures += linkedMapOf("id" to id, "held" to false, "reasons" to listOf(reason))
            }
        }
        return Result(entries, failures, linkedMapOf(
            "id" to GROUP_ID, "title" to "Six voices · short musical phrases",
            "description" to "The same eight-note, two-bar C minor pentatonic phrase at 100 BPM in PLUCK, WOOD, BUBBLE, BREATH, FLICKER and RATTLE, plus the accepted phrase shared between PLUCK, WOOD and BUBBLE. Soft and strong notes use their actual playing velocity. Native playback uses one common 0.25 gain on the sum of unlevelled note renders; only the completed phrases are loudness matched. Full one-shot tails follow the score.",
            "clipIds" to entries.map { it.getValue("id") },
        ))
    }

    private fun renderSource(root: File, key: String, voice: WickVoice, note: Note): Source {
        val patch = WickPatch("${voice.name} ${note.midi}".take(14), voice, Wick.defaults(voice) + ("HOLD" to 0f), note.midi, note.velocity)
        val started = System.nanoTime()
        val probe = Wick.inspect(voice, patch.macros, patch.midi, patch.velocity)
        val elapsed = (System.nanoTime() - started) / 1e6
        check(probe.raw.isNotEmpty() && probe.raw.all { it.isFinite() } && probe.diagnostics.loop == null)
        check(probe.diagnostics.rejectedEvents == 0) { "$key rejected source events" }
        val sourceRaw = Snip(FloatArray(probe.raw.size) { probe.raw[it] * RAW_EXPORT_GAIN }, 1, Dsp.RATE)
        check(sourceRaw.peak() < 1f) { "$key source raw WAV would clip" }
        val rawFile = "pattern-sources/$key.wav"
        val patchFile = "pattern-patches/$key.json"
        val diagnosticsFile = "pattern-sources/${key}_diagnostics.json"
        WavWriter.write(File(root, rawFile), sourceRaw, WavWriter.BitDepth.PCM_24)
        File(root, patchFile).also { it.parentFile.mkdirs() }.writeText(patch.toJsonText())
        val floatBytes = ByteBuffer.allocate(probe.raw.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in probe.raw) floatBytes.putFloat(sample)
        val hash = MessageDigest.getInstance("SHA-256").digest(floatBytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val d = probe.diagnostics
        writeJson(File(root, diagnosticsFile), linkedMapOf(
            "patch" to patch.toJsonValue(), "options" to options, "sourceRawFloatSha256" to hash,
            "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
            "frames" to probe.raw.size, "maxQueueDepth" to d.maxQueueDepth, "rejectedEvents" to d.rejectedEvents,
            "endedAdmissions" to d.endedAdmissions, "minAvailability" to d.minAvailability, "maxAvailability" to d.maxAvailability,
            "maxCharge" to d.maxCharge, "maxOpening" to d.maxOpening, "maxFilterState" to d.maxFilterState,
            "events" to d.events.map { e -> linkedMapOf(
                "id" to e.id, "parentEventId" to e.parentEventId, "timeSeconds" to e.timeSeconds,
                "parentStage" to e.parentStage, "releaseSeconds" to e.releaseSeconds, "destination" to e.destination,
                "requestedStrength" to e.requestedStrength, "admittedStrength" to e.admittedStrength,
                "availabilityBefore" to e.availabilityBefore, "availabilityAfter" to e.availabilityAfter,
                "kind" to e.kind, "derivation" to e.derivation, "sourceEventId" to e.sourceEventId,
            ) },
        ))
        return Source(patch, probe, patchFile, rawFile, diagnosticsFile, hash, elapsed)
    }

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
        else -> error("Unsupported WICK pattern JSON ${value.javaClass.name}")
    }
}
