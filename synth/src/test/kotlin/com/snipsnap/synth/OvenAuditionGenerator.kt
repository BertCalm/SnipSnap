package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The small, dry OVEN listening gate, rendered by the actual JVM engine.
 * Native clips preserve relative strike energy with one shared PCM gain;
 * matched clips use the repository's [AuditionLevel] rule. Keyboard notes are
 * individually rendered at three velocities, rather than transposing one WAV.
 * Run `./gradlew :synth:generateOvenAudition` to reproduce this manifest and pack.
 */
object OvenAuditionGenerator {
    private val positions = listOf(0f, .25f, .5f, .75f, 1f)
    private val velocities = linkedMapOf("soft" to .3f, "medium" to .65f, "hard" to 1f)
    private val guides = linkedMapOf(
        "TUNE" to "Transpose the requested note across two octaves; the midpoint keeps its saved root.",
        "HAMMER" to "Move from a soft, dark contact to a short, bright strike.",
        "BODY" to "Add fixed body resonances and darker, heavier coloration around the strings.",
        "RING" to "Lengthen the finite string decay. Upper partials disappear before the low ring.",
        "BLOOM" to "Move from an immediate strike toward a swelling entrance.",
        "HALO" to "Add the note's baked sympathetic resonance tail.",
    )
    private data class ProbeOptions(val bodyEnabled: Boolean = true, val haloEnabled: Boolean = true)
    private data class Event(val at: Double, val patch: OvenPatch)
    private data class OriginalPreset(val id: String, val patch: OvenPatch, val rawSha256: String, val matchedSha256: String, val rawGain: Float)
    private data class RevisionOne(val presets: List<OriginalPreset>, val legacyMatchedHashes: Map<String, String>)
    private data class Case(
        val id: String,
        val title: String,
        val section: String,
        val description: String,
        val patch: OvenPatch,
        val comparison: String? = null,
        val position: Int? = null,
        val velocityLabel: String? = null,
        val options: ProbeOptions? = null,
        val events: List<Event>? = null,
        val mixGain: Float = 1f,
        val presetName: String? = null,
        val revision: String? = null,
        val audioRevision: Int = 2,
        val originalId: String? = null,
        val archive: OriginalPreset? = null,
    )
    private data class Rendered(val case: Case, val snip: Snip, val recipe: JsonValue, val metrics: Map<String, Any?>, val spectrum: DoubleArray)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/oven-audition")
        root.mkdirs()
        val revisionOne = revisionOne()
        val changedPresets = OvenPresets.all().filter { patch ->
            revisionOne.presets.firstOrNull { it.patch.name == patch.name }?.let {
                common(it.patch).voice != common(patch).voice || common(it.patch).macros != common(patch).macros
            } ?: false
        }.sortedBy { when (it.name) { "Felt Ledger" -> 0; "Quiet Room" -> 1; else -> 2 } }
        val cases = cases(revisionOne, changedPresets)
        check(cases.map { it.id }.distinct().size == cases.size) { "duplicate OVEN audition ID" }
        val rendered = cases.mapIndexed { index, case ->
            val snip = render(case)
            check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty())
            val nonFinite = snip.samples.count { !it.isFinite() }
            check(nonFinite == 0) { "${case.id}: $nonFinite non-finite raw samples" }
            val spectrum = spectrum(snip.samples)
            val result = Rendered(case, snip, recipe(case), metrics(snip, spectrum, case.patch.takeIf { case.events == null }), spectrum)
            if ((index + 1) % 10 == 0 || index == cases.lastIndex) {
                println("OVEN audition: rendered ${index + 1}/${cases.size} dry cases")
            }
            result
        }
        // A single gain retains soft/medium/hard level differences across the pack.
        // Record the original float metrics as well as this exact PCM export gain.
        val originalPeak = rendered.maxOf { it.snip.peak() }
        val rawGain = minOf(1f, .99f / originalPeak.coerceAtLeast(1e-9f))
        val clips = rendered.map { entry ->
            val case = entry.case
            val exportGain = case.archive?.rawGain ?: rawGain
            val raw = gain(entry.snip, exportGain)
            val matched = AuditionLevel.level(entry.snip)
            // Revision-specific media URLs prevent a browser from serving the
            // first audition's cached recording under a stable preset ID.
            val rawPath = "audio/r2/${case.id}-raw.wav"
            val matchedPath = "audio/r2/${case.id}-matched.wav"
            val recipePath = "patches/${case.id}.json"
            val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_16)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            val rawHash = sha256(rawFile)
            val matchedHash = sha256(matchedFile)
            case.archive?.let {
                check(rawHash == it.rawSha256 && matchedHash == it.matchedSha256) {
                    "Original published audio changed for ${it.patch.name}; preserve the revision-one DSP before rebuilding this archive"
                }
            }
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(Json.write(entry.recipe) + "\n") }
            linkedMapOf<String, Any?>(
                "id" to case.id, "title" to case.title, "section" to case.section,
                "description" to case.description, "voice" to case.patch.voice.name,
                "note" to if (case.events == null) noteName(case.patch.midi) else "score",
                "midi" to if (case.events == null) case.patch.midi else null,
                "velocity" to if (case.events == null) case.patch.velocity else null,
                "velocityLabel" to case.velocityLabel, "macros" to case.patch.macros,
                "comparison" to case.comparison, "position" to case.position,
                "comparisonTitle" to case.presetName, "presetName" to case.presetName,
                "revision" to case.revision, "revisionLabel" to case.revision?.replaceFirstChar { it.uppercase() },
                "audioRevision" to case.audioRevision, "originalId" to case.originalId,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "recipeKind" to when { case.events != null -> "oven-offline-score"; case.options != null -> "oven-probe"; else -> "oven-patch" },
                "duration" to raw.durationSeconds, "peak" to raw.peak(), "loudness" to Loudness.of(raw),
                "matchedPeak" to matched.peak(), "matchedLoudness" to Loudness.of(matched),
                "rawGain" to exportGain, "rawSha256" to rawHash, "matchedSha256" to matchedHash,
                "rawMetrics" to entry.metrics,
                "probe" to case.options?.let { mapOf("bodyEnabled" to it.bodyEnabled, "haloEnabled" to it.haloEnabled) },
            )
        }.toMutableList()
        // A revised comparison points to its same-note recording, so the two views
        // cannot diverge and the downloadable pack does not duplicate those WAVs.
        for (patch in changedPresets) {
            val tag = slug(patch.name)
            val commonClip = clips.single { it["id"] == "common_preset_$tag" }
            val revised = LinkedHashMap(commonClip)
            revised["id"] = "revision_revised_$tag"
            revised["title"] = "Revised · ${patch.name}"
            revised["section"] = "revision"
            revised["description"] = role(patch.name) + ". Revised recipe at C4 and medium velocity; compare with its original at the same playback level."
            revised["comparison"] = "revision_$tag"
            revised["position"] = 1
            revised["revision"] = "revised"
            revised["revisionLabel"] = "Revised"
            revised["originalId"] = "preset_$tag"
            clips += revised
        }
        val keyboard = (60..72).map { midi ->
            linkedMapOf<String, Any?>("midi" to midi, "note" to noteName(midi)).apply {
                for (label in velocities.keys) put(label, noteId(midi, label))
            }
        }
        check(keyboard.all { row -> velocities.keys.all { label -> clips.any { it["id"] == row[label] } } })
        val wavPaths = clips.flatMap { listOf(it.getValue("raw") as String, it.getValue("matched") as String) }.distinct()
        val wavBytes = wavPaths.sumOf { File(root, it).length() }
        check(wavBytes <= 100L * 1024 * 1024) { "OVEN WAV assets exceed 100 MiB: $wavBytes bytes" }
        val diagnostics = diagnostics(rendered, rawGain, wavBytes)
        writeJson(File(root, "diagnostics.json"), diagnostics)
        writeJson(File(root, "manifest.json"), linkedMapOf(
            "engine" to "OVEN", "version" to 1, "model" to Oven.MODEL_VERSION,
            "audioRevision" to 2,
            "revisionSummary" to "Preset revision: compare the roster at one pitch, one velocity and a shared playback level, then hear each changed recipe beside its original.",
            "revisionNotes" to revisionNotes(revisionOne, changedPresets),
            "commonConditions" to mapOf("midi" to 60, "note" to "C4", "velocity" to .65f, "tune" to .5f, "matchedTarget" to .03),
            "legacyMatchedHashes" to revisionOne.legacyMatchedHashes,
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 16,
            "status" to "Revised dry audition; listening review is pending.",
            "controls" to guides.map { (name, description) -> mapOf("name" to name, "description" to description, "default" to Oven.defaults(OvenVoice.GRAND).getValue(name)) },
            "sections" to sections(), "clips" to clips, "keyboard" to keyboard,
            "rawGain" to rawGain, "matchedTarget" to .03, "matchedPeakCeiling" to .99,
            "diagnostics" to "diagnostics.json", "clipCount" to clips.size,
            "downloads" to mapOf("audio" to "oven-audition.zip", "source" to "oven-source.zip", "kit" to "oven-kit.zip"),
            "metricNotes" to METRIC_NOTES,
        ))
        println("Wrote ${clips.size} listening entries / ${wavPaths.size} WAVs (${wavBytes / 1048576.0} MiB), exact recipes, manifest and diagnostics under ${root.absolutePath}")
    }

    private fun cases(revisionOne: RevisionOne, changedPresets: List<OvenPatch>): List<Case> = buildList {
        for (patch in OvenPresets.all()) add(Case(
            "preset_" + slug(patch.name), patch.name, "first-listen",
            role(patch.name) + ". ${patch.voice.name} at ${noteName(patch.midi)}, velocity ${patch.velocity}.", patch, presetName = patch.name,
        ))
        for (patch in OvenPresets.all()) add(Case(
            "common_preset_" + slug(patch.name), patch.name, "same-note",
            role(patch.name) + ". C4 at medium velocity and the shared matched playback level.", common(patch), presetName = patch.name,
        ))
        for (patch in changedPresets) {
            val original = revisionOne.presets.single { it.patch.name == patch.name }
            val tag = slug(patch.name)
            add(Case("revision_original_$tag", "Original · ${patch.name}", "revision",
                "The original recipe at C4 and medium velocity. Compare with the revised recipe at the same playback level.",
                common(original.patch), comparison = "revision_$tag", position = 0, presetName = patch.name,
                revision = "original", audioRevision = 1, originalId = original.id))
        }
        for (original in revisionOne.presets) add(Case(
            "archived_" + original.id, original.patch.name, "archive",
            "Original published recording at its saved ${noteName(original.patch.midi)} and velocity ${original.patch.velocity}. Audio hashes are checked against the first audition.",
            original.patch, presetName = original.patch.name, revision = "original-as-played", audioRevision = 1,
            originalId = original.id, archive = original,
        ))
        for (midi in listOf(36, 60, 84)) for ((label, velocity) in velocities) {
            val patch = OvenPatch("GRAND ${noteName(midi)} $label", OvenVoice.GRAND, Oven.defaults(OvenVoice.GRAND), midi, velocity)
            add(Case(noteId(midi, label), "${noteName(midi)} · $label", if (midi == 60) "touch" else "register",
                "Same GRAND controls at velocity $velocity. Raw preserves strike level; matched exposes the change in hammer color.", patch, velocityLabel = label))
        }
        val defaults = Oven.defaults(OvenVoice.GRAND)
        for (name in guides.keys.filter { it != "TUNE" }) for ((position, value) in positions.withIndex()) {
            val patch = OvenPatch("$name $value", OvenVoice.GRAND, defaults + (name to value), 60, .65f)
            add(Case("control_${name.lowercase()}_$position", "$name · ${(value * 100).toInt()}%", "controls",
                guides.getValue(name) + " Other GRAND controls, C4 and medium velocity stay fixed.", patch, name, position))
        }
        val causal = OvenPatch("Body and halo probe", OvenVoice.GRAND,
            defaults + mapOf("HAMMER" to .5f, "BODY" to .8f, "HALO" to .6f, "BLOOM" to 0f), 60, .65f)
        for ((id, title, options) in listOf(
            Triple("full", "Strings + body + halo", ProbeOptions()),
            Triple("body_off", "Body bypassed", ProbeOptions(bodyEnabled = false)),
            Triple("halo_off", "Halo bypassed", ProbeOptions(haloEnabled = false)),
            Triple("strings", "Strings alone", ProbeOptions(bodyEnabled = false, haloEnabled = false)),
        )) {
            val description = when (id) {
                "full" -> "Same C4 strike and controls. Hear the strings, body coloration and sympathetic halo together."
                "body_off" -> "Same C4 strike and controls. Compare the body coloration with the full sound while the halo rings."
                "halo_off" -> "Same C4 strike and controls. Compare the halo's contribution to the tail with the full sound."
                else -> "Same C4 strike and controls. Hear the string partials and their gentle beating alone."
            }
            add(Case("causal_$id", title, "causal", description,
                causal, comparison = "body-halo", options = options))
        }
        val phrasePatch = OvenPatch("GRAND ascending phrase", OvenVoice.GRAND, defaults, 60, .65f)
        val ascending = listOf(60, 62, 64, 67, 72).mapIndexed { index, midi ->
            Event(index * .42, phrasePatch.copy(name = "Ascending note ${index + 1}", midi = midi, velocity = listOf(.45f, .55f, .65f, .8f, .9f)[index]))
        }
        add(Case("phrase_ascending", "An ascending line", "phrases",
            "Five separately rendered strikes, mixed offline. Each note carries its own baked halo; notes do not excite a shared live resonance network.",
            phrasePatch, events = ascending, mixGain = .6f))
        val bloomPatch = requireNotNull(OvenPresets.all().firstOrNull { it.name == "Afterglow" })
        val chords = listOf(
            0.0 to listOf(48, 55, 60, 64),
            1.35 to listOf(53, 60, 65, 69),
            2.7 to listOf(55, 62, 67, 71),
        ).flatMapIndexed { chord, (at, notes) -> notes.mapIndexed { index, midi ->
            Event(at + index * .018, bloomPatch.copy(name = "Bloom chord ${chord + 1}, note ${index + 1}", midi = midi, velocity = .6f))
        } }
        add(Case("phrase_bloom_chords", "Three blooming chords", "phrases",
            "Three gently spread chords mixed from independent Afterglow note renders. Their halo tails overlap without live note-to-note coupling.",
            bloomPatch, events = chords, mixGain = .22f))
        // C4 reuses the real touch clips above; the other twelve keys are new renders.
        for (midi in 61..72) for ((label, velocity) in velocities) {
            val patch = OvenPatch("GRAND ${noteName(midi)} $label", OvenVoice.GRAND, defaults, midi, velocity)
            add(Case(noteId(midi, label), "${noteName(midi)} · $label", "keyboard",
                "Individually rendered GRAND note at velocity $velocity, with its own finite tail.", patch, velocityLabel = label))
        }
    }

    private fun render(case: Case): Snip {
        fun strike(patch: OvenPatch, options: ProbeOptions? = null): Snip {
            val probe = Oven.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
                bodyEnabled = options?.bodyEnabled ?: true, haloEnabled = options?.haloEnabled ?: true)
            return Snip(probe.samples, 1, Dsp.RATE)
        }
        val events = case.events ?: return strike(case.patch, case.options)
        val notes = events.map { event -> event to strike(event.patch) }
        val frames = notes.maxOf { (it.first.at * Dsp.RATE).toInt() + it.second.frameCount }
        val out = FloatArray(frames)
        for ((event, snip) in notes) {
            val offset = (event.at * Dsp.RATE).toInt()
            for (i in snip.samples.indices) out[offset + i] += snip.samples[i] * case.mixGain
        }
        return Snip(out, 1, Dsp.RATE)
    }

    private fun recipe(case: Case): JsonValue {
        case.events?.let { events -> return JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str("oven-offline-score"), "version" to JsonValue.Num(1.0),
            "sampleRate" to JsonValue.Num(Dsp.RATE.toDouble()), "mixGain" to JsonValue.Num(case.mixGain.toDouble()),
            "samplePath" to JsonValue.Str("Oven.probe(...).samples"),
            "notes" to JsonValue.Arr(events.map { JsonValue.Obj(linkedMapOf(
                "atSeconds" to JsonValue.Num(it.at), "patch" to it.patch.toJsonValue(),
            )) }),
        )) }
        case.options?.let { options -> return JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str("oven-probe"), "version" to JsonValue.Num(1.0),
            "patch" to case.patch.toJsonValue(),
            "probe" to json(mapOf("bodyEnabled" to options.bodyEnabled, "haloEnabled" to options.haloEnabled)),
            "samplePath" to JsonValue.Str("Oven.probe(...).samples"),
        )) }
        return case.patch.toJsonValue()
    }

    private fun diagnostics(rendered: List<Rendered>, rawGain: Float, wavBytes: Long): Map<String, Any?> {
        val full = rendered.single { it.case.id == "causal_full" }
        val causal = rendered.filter { it.case.section == "causal" && it !== full }.map { entry ->
            var deltaEnergy = 0.0
            var fullEnergy = 0.0
            var candidateEnergy = 0.0
            for (i in 0 until maxOf(full.snip.samples.size, entry.snip.samples.size)) {
                val a = full.snip.samples.getOrElse(i) { 0f }.toDouble()
                val b = entry.snip.samples.getOrElse(i) { 0f }.toDouble()
                deltaEnergy += (a - b) * (a - b); fullEnergy += a * a; candidateEnergy += b * b
            }
            val fullTotal = full.spectrum.sum().coerceAtLeast(1e-30)
            val candidateTotal = entry.spectrum.sum().coerceAtLeast(1e-30)
            val distance = full.spectrum.indices.sumOf { abs(full.spectrum[it] / fullTotal - entry.spectrum[it] / candidateTotal) } * .5
            linkedMapOf("reference" to full.case.id, "candidate" to entry.case.id,
                "relativeWaveformRmsDifference" to sqrt(deltaEnergy / fullEnergy.coerceAtLeast(1e-30)),
                "energyRatio" to candidateEnergy / fullEnergy.coerceAtLeast(1e-30),
                "normalizedSpectrumDistance" to distance,
                "centroidShiftHz" to ((entry.metrics.getValue("centroidHz") as Number).toDouble() - (full.metrics.getValue("centroidHz") as Number).toDouble()))
        }
        val touch = velocities.keys.map { label -> rendered.single { it.case.id == noteId(60, label) } }
        return linkedMapOf(
            "engine" to "OVEN", "model" to Oven.MODEL_VERSION, "sampleRate" to Dsp.RATE,
            "clipCount" to rendered.size, "wavBytes" to wavBytes, "sharedRawPcmGain" to rawGain,
            "maxRawPeak" to rendered.maxOf { it.snip.peak() },
            "rawNonFiniteCount" to rendered.sumOf { it.snip.samples.count { sample -> !sample.isFinite() } },
            "durationRangeSeconds" to listOf(rendered.minOf { it.snip.durationSeconds }, rendered.maxOf { it.snip.durationSeconds }),
            "maxAbsoluteDc" to rendered.maxOf { abs((it.metrics.getValue("dc") as Number).toDouble()) },
            "c4StrikeLoudness" to touch.map { mapOf("velocity" to it.case.patch.velocity, "loudness" to it.metrics["loudestWindowRms"], "peak" to it.metrics["peak"], "centroidHz" to it.metrics["centroidHz"]) },
            "causalComparisons" to causal,
            "clips" to rendered.map { linkedMapOf("id" to it.case.id, "section" to it.case.section, "metrics" to it.metrics) },
            "metricNotes" to METRIC_NOTES,
        )
    }

    private fun metrics(snip: Snip, spectrum: DoubleArray, patch: OvenPatch?): Map<String, Any?> {
        val samples = snip.samples
        var energy = 0.0; var sum = 0.0; var peakIndex = 0
        for (i in samples.indices) {
            val value = samples[i].toDouble(); energy += value * value; sum += value
            if (abs(samples[i]) > abs(samples[peakIndex])) peakIndex = i
        }
        val lastCount = minOf(samples.size, Dsp.RATE / 20)
        val tailEnergy = (samples.size - lastCount until samples.size).sumOf { samples[it].toDouble() * samples[it] }
        val weighted = spectrum.indices.sumOf { it.toDouble() * Dsp.RATE / FFT_SIZE * spectrum[it] }
        val total = spectrum.sum().coerceAtLeast(1e-30)
        val estimate = if (patch != null) Pitch.detect(snip, fromSec = .12f, windowSec = .35f) else null
        val expectedHz = patch?.let { Oven.frequencyFor(it.voice, it.macros.getValue("TUNE"), it.midi) }
        return linkedMapOf(
            "duration" to snip.durationSeconds, "frames" to snip.frameCount,
            "peak" to snip.peak(), "rms" to sqrt(energy / samples.size), "dc" to sum / samples.size,
            "loudestWindowRms" to Loudness.of(snip), "centroidHz" to weighted / total,
            "rawNonFiniteCount" to samples.count { !it.isFinite() },
            "last50msRms" to sqrt(tailEnergy / lastCount), "lastSample" to samples.last(),
            "peakAtSeconds" to peakIndex.toDouble() / Dsp.RATE,
            "requestedHz" to expectedHz, "detectedHz" to estimate?.hz, "pitchConfidence" to estimate?.confidence,
            "detectedErrorCents" to if (estimate != null && expectedHz != null) 1200 * ln(estimate.hz / expectedHz.toDouble()) / ln(2.0) else null,
        )
    }

    /** Mean Hann-windowed power over the opening two seconds; shared windowing for causal A/Bs. */
    private fun spectrum(samples: FloatArray): DoubleArray {
        val limit = minOf(samples.size, Dsp.RATE * 2)
        val result = DoubleArray(FFT_SIZE / 2 + 1)
        var count = 0
        var from = 0
        while (from < limit) {
            val frame = FloatArray(FFT_SIZE) { if (from + it < limit) samples[from + it] else 0f }
            val magnitude = Fft.magnitudeSpectrum(frame, FFT_SIZE)
            for (bin in magnitude.indices) result[bin] += magnitude[bin].toDouble() * magnitude[bin]
            count++
            if (from + FFT_SIZE >= limit) break
            from += FFT_SIZE / 2
        }
        for (bin in result.indices) result[bin] /= count.coerceAtLeast(1)
        return result
    }

    private fun sections() = listOf(
        mapOf("id" to "first-listen", "title" to "The revised roster", "description" to "Distinct dry recipes share one struck-string core. Start with Open Lid, then hear felt, forged and blooming versions."),
        mapOf("id" to "same-note", "title" to "The roster at C4", "description" to "Every current preset at C4, velocity 0.65 and neutral TUNE. Matched playback gives each the same loudest-moment level."),
        mapOf("id" to "revision", "title" to "Original and revised", "description" to "Each changed preset beside its original, both at C4, velocity 0.65 and neutral TUNE. Listen for articulation, body and decay."),
        mapOf("id" to "touch", "title" to "Touch", "description" to "C4 at three real strike velocities. Raw retains dynamics; matched lets you compare color."),
        mapOf("id" to "register", "title" to "Low and high", "description" to "C2 and C6 at soft, medium and hard strikes, with the same GRAND controls."),
        mapOf("id" to "controls", "title" to "One control at a time", "description" to "Five positions, from 0 to 100%, at C4 and medium velocity. Other controls stay fixed."),
        mapOf("id" to "causal", "title" to "Hear the body and halo", "description" to "One identical strike with body and halo DSP paths bypassed in turn."),
        mapOf("id" to "phrases", "title" to "Notes together", "description" to "Offline notes overlap with their independent baked tails. There is no shared live sympathetic network."),
        mapOf("id" to "keyboard", "title" to "C4–C5 keyboard", "description" to "Thirteen individually rendered notes, each at three velocities."),
        mapOf("id" to "archive", "title" to "Original takes", "description" to "The ten original published preset recordings, at their original notes and velocities. Their raw and matched audio hashes are preserved."),
    )

    /** Tracked original recipes make the comparison pack reproducible without local audit files. */
    private fun revisionOne(): RevisionOne {
        val text = OvenAuditionGenerator::class.java.getResourceAsStream("/audition/oven-roster-r1.json")
            ?.bufferedReader()?.use { it.readText() } ?: error("OVEN revision-one recipe snapshot is missing")
        val data = Json.parse(text).obj()
        check(data.getValue("engine").str() == "OVEN" && data.getValue("audioRevision").int() == 1)
        check(data.getValue("model").int() == Oven.MODEL_VERSION) { "preserve the revision-one model to regenerate the original recordings" }
        val hashes = data.getValue("legacyMatchedHashes").obj().mapValues { it.value.str() }
        val presets = data.getValue("presets").arr().map { value ->
            val entry = value.obj()
            OriginalPreset(entry.getValue("id").str(), OvenPatch.fromJsonValue(entry.getValue("patch")) as OvenPatch,
                entry.getValue("rawSha256").str(), entry.getValue("matchedSha256").str(), entry.getValue("rawGain").num().toFloat())
        }
        check(presets.map { it.id }.distinct().size == presets.size)
        check(presets.all { hashes[it.id] == it.matchedSha256 })
        return RevisionOne(presets, hashes)
    }

    private fun common(patch: OvenPatch) = patch.copy(midi = 60, velocity = .65f, macros = patch.macros + ("TUNE" to .5f))

    private fun role(name: String): String = OvenPresets.roleDescription(name)?.removeSuffix(".")
        ?: "An original dry struck-string recipe"

    private fun revisionNotes(original: RevisionOne, changed: List<OvenPatch>): List<String> = buildList {
        add("The preset comparisons use C4, velocity 0.65 and neutral TUNE; matched playback shares one level.")
        for (patch in changed) add(patch.name + ": " + role(patch.name).replaceFirstChar { it.lowercase() } + ".")
        val currentNames = OvenPresets.all().map { it.name }.toSet()
        for (removed in original.presets.filter { it.patch.name !in currentNames }) {
            add(removed.patch.name + " remains in Original takes and is removed from the current roster.")
        }
        add("The original published recordings and recipes remain available in Original takes, with their audio hashes preserved.")
    }

    private fun gain(snip: Snip, gain: Float) = if (gain == 1f) snip else Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, snip.channels, snip.sampleRate)
    private fun noteId(midi: Int, label: String) = "grand_m${midi}_$label"
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported OVEN manifest value: ${value.javaClass}")
    }
    private const val FFT_SIZE = 8192
    private const val METRIC_NOTES = "Raw metrics describe finite mono 44.1 kHz float samples from Oven.probe(...).samples before PCM encoding or audition leveling. All raw WAVs share one recorded PCM gain, retaining velocity relationships. Matched WAVs use SnipSnap's AuditionLevel target: loudest gently low-cut 200 ms RMS 0.03, with peak ceiling 0.99. Raw and matched are 16-bit PCM. Centroid uses mean Hann-windowed power spectra (8192 frames, 50% overlap) over the opening two seconds; it measures brightness, not pitch. Pitch uses the shared detector over .12–.47 seconds; uncertainty and possible octave errors remain visible. Causal energy compares full finite buffers with zero padding; normalized spectrum distance is half the L1 difference of power distributions, independent of overall level. These diagnostics support listening and do not establish piano identity. Ordinary patch recipes rebuild the strike: probe yields the raw sample path, while normal render applies engine loudness conditioning. Probe recipes additionally save bypass flags; score recipes save all note recipes and sample offsets. Notes have finite tails and no loop; the engine includes a bounded end fade. Phrases and keyboard voices carry independent baked halos without real-time note-to-note resonance."
}
