package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A small, deterministic listening pack for the ORPHICA-inspired SnipSnap prototype.
 * The matched files use the same loudest-200-ms rule as SnipSnap's other auditions.
 * Every raw file receives one common safety gain, retaining excitation and texture levels.
 * This is evidence for listening, not a claim of human sonic acceptance or live integration.
 *
 * Run `./gradlew :synth:generateOrphicaAudition`; the page is provided separately.
 */
object OrphicaAuditionGenerator {
    private const val RATE = 44_100
    private const val VERSION = "0.1"
    private const val TAIL_FADE_SECONDS = 0.025f

    private data class Profile(
        val id: String,
        val title: String,
        val description: String,
        val config: OrphicaConfig,
    )

    private data class Event(val offsetSeconds: Float, val config: OrphicaConfig)

    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val group: String,
        val kind: String = "processed",
        val config: OrphicaConfig? = null,
        val events: List<Event> = emptyList(),
        val seconds: Float = config?.seconds ?: 5f,
        val pairId: String? = null,
    )

    private data class Metrics(val peak: Float, val loudness: Float, val rms: Double, val dc: Double) {
        fun json() = obj("peak" to num(peak), "loudness" to num(loudness), "rms" to num(rms), "dc" to num(dc))
    }

    private data class Rendered(val case: Case, val native: Snip, val metrics: Metrics)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/orphica-audition")
        check(root.mkdirs() || root.isDirectory) { "could not create ${root.absolutePath}" }
        val cases = cases()
        check(cases.size in 25..40 && cases.map { it.id }.distinct().size == cases.size)
        val began = System.nanoTime()
        val renders = cases.map { case ->
            val rendered = if (case.events.isNotEmpty()) mix(case.events, case.seconds) else {
                val config = checkNotNull(case.config)
                if (case.kind == "dry") Orphica.dry(config) else Orphica.render(config)
            }
            val native = fadeTail(rendered)
            validate(native, case.id)
            Rendered(case, native, metrics(native))
        }
        val highestNativePeak = renders.maxOf { it.metrics.peak }
        val commonRawGain = minOf(1f, 0.95f / highestNativePeak)
        check(commonRawGain.isFinite() && commonRawGain > 0f)
        val entries = ArrayList<JsonValue>(cases.size)
        var maximumPcmError = 0f
        var maximumRawGainError = 0f
        var maximumPatchReplayError = 0f
        var highestMatchedPeak = 0f
        var highestRawPeak = 0f
        for ((index, rendered) in renders.withIndex()) {
            val case = rendered.case
            val raw = withGain(rendered.native, commonRawGain)
            val matched = AuditionLevel.level(rendered.native)
            validate(raw, "${case.id} raw")
            validate(matched, "${case.id} matched")
            check(raw.peak() <= 0.950001f && matched.peak() < 0.991f) { "PCM clipping risk: ${case.id}" }
            val matchedMetrics = metrics(matched)
            val rawMetrics = metrics(raw)
            highestMatchedPeak = maxOf(highestMatchedPeak, matchedMetrics.peak)
            highestRawPeak = maxOf(highestRawPeak, rawMetrics.peak)
            val file = "wav/${case.id}.wav"
            val rawFile = "raw/${case.id}.wav"
            val patchFile = "patches/${case.id}.json"
            WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, rawFile), raw, WavWriter.BitDepth.PCM_24)
            // Read the actual delivery files: a valid float render alone does not verify export.
            maximumPcmError = maxOf(maximumPcmError, verifyPcm(File(root, file), matched))
            maximumPcmError = maxOf(maximumPcmError, verifyPcm(File(root, rawFile), raw))
            for (i in raw.samples.indices) {
                maximumRawGainError = maxOf(maximumRawGainError,
                    abs(raw.samples[i] - rendered.native.samples[i] * commonRawGain))
            }
            val patch = File(root, patchFile)
            check(patch.parentFile.mkdirs() || patch.parentFile.isDirectory)
            patch.writeText(Json.write(recipe(case)) + "\n")
            // Replay through the registered patch dispatcher, including each phrase's note patches.
            val replayed = replay(Json.parse(patch.readText()))
            check(replayed.channels == rendered.native.channels &&
                replayed.samples.size == rendered.native.samples.size) { "patch replay format changed: ${case.id}" }
            for (i in replayed.samples.indices) {
                maximumPatchReplayError = maxOf(maximumPatchReplayError,
                    abs(replayed.samples[i] - rendered.native.samples[i]))
            }
            check(maximumPatchReplayError == 0f) { "saved patch changed native audio: ${case.id}" }
            val entry = linkedMapOf<String, JsonValue>(
                "id" to str(case.id), "title" to str(case.title), "description" to str(case.description),
                "group" to str(case.group), "kind" to str(case.kind),
                "file" to str(file), "rawFile" to str(rawFile), "patchFile" to str(patchFile),
                "config" to recipeConfig(case), "durationSeconds" to num(matched.durationSeconds),
                "peak" to num(matchedMetrics.peak), "loudness" to num(matchedMetrics.loudness),
                "channels" to num(matched.channels), "sampleRate" to num(matched.sampleRate),
                "nativeMetrics" to rendered.metrics.json(), "rawMetrics" to rawMetrics.json(),
                "matchedMetrics" to matchedMetrics.json(), "rawGain" to num(commonRawGain),
            )
            if (case.pairId != null) entry["pairId"] = str(case.pairId)
            entries += JsonValue.Obj(entry)
            if ((index + 1) % 7 == 0 || index == renders.lastIndex) {
                println("ORPHICA audition: ${index + 1}/${renders.size} clips exported and verified")
            }
        }
        check(maximumRawGainError == 0f) { "raw export did not use the common gain" }
        val notes = listOf(
            "Rendered SnipSnap prototype inspired by ORPHICA: a clear plucked attack disperses into grains drawn from that same note's recorded history.",
            "All clips are dry: no reverb, shimmer, delay rack, or low/high effects routing. Stereo grain placement is part of the renderer.",
            "Matched playback uses the loudest 200 ms RMS at 0.03 with a peak guard. Raw playback uses one common safety gain across the entire pack, preserving relative levels; raw can sound substantially louder.",
            "Freeze records the note first, then stops its history write at the specified capture time. The rolling history is 0.25 s: late captures hold the preceding quarter-second. Each clip is a finite rendered snapshot, with a 25 ms ending fade; it is not a seamless held loop or live performance latch.",
            "The overlap phrase sums independent note renders. New notes do not enter an already frozen note's history. Capturing a played chord and latching it beneath later notes would require a live engine extension.",
            "Fracture zero and CLEAR are explicit dry endpoints. Same-source A/B clips preserve material, MIDI, velocity, source seed and duration. Each retains its engine termination fade and receives the same additional 25 ms delivery fade.",
            "Seeds are stored as decimal strings in the patch recipes so replay does not lose integer precision. Human sonic acceptance remains pending listening.",
        )
        val manifest = obj(
            "title" to str("ORPHICA / SnipSnap"), "engine" to str("ORPHICA"),
            "prototypeVersion" to str(VERSION), "sampleRate" to num(RATE), "bitDepth" to num(24),
            "rawGain" to num(commonRawGain), "matchedLoudnessTarget" to num(0.03f),
            "sonicAcceptance" to str("pending owner listening"),
            "notes" to arr(notes.map(::str)),
            "sections" to arr(cases.map { it.group }.distinct().map(::str)), "entries" to arr(entries),
        )
        File(root, "manifest.json").writeText(Json.write(manifest) + "\n")
        val summary = obj(
            "engine" to str("ORPHICA"), "prototypeVersion" to str(VERSION),
            "clipCount" to num(cases.size), "wavCount" to num(cases.size * 2), "sampleRate" to num(RATE),
            "channels" to num(2), "bitDepth" to num(24),
            "totalClipSeconds" to num(renders.sumOf { it.native.durationSeconds.toDouble() }),
            "maximumClipSeconds" to num(renders.maxOf { it.native.durationSeconds }),
            "rawGain" to num(commonRawGain), "highestNativePeak" to num(highestNativePeak),
            "highestRawPeak" to num(highestRawPeak), "highestMatchedPeak" to num(highestMatchedPeak),
            "maximumPcmRoundTripError" to num(maximumPcmError),
            "maximumCommonRawGainError" to num(maximumRawGainError),
            "maximumSavedPatchReplayError" to num(maximumPatchReplayError),
            "finiteNonemptyStereoClips" to JsonValue.Bool(true), "rawClipping" to JsonValue.Bool(false),
            "sonicAcceptance" to str("pending owner listening"),
        )
        File(root, "summary.json").writeText(Json.write(summary) + "\n")
        for (name in listOf("index.html", "style.css", "app.js")) {
            val resource = checkNotNull(javaClass.getResourceAsStream("/audition/orphica/$name")) {
                "missing ORPHICA audition page asset: $name"
            }
            resource.use { File(root, name).writeBytes(it.readBytes()) }
        }
        println("Wrote ${cases.size} stereo clips in matched/raw forms, replay patches, manifest.json, summary.json and audition page under " +
            "${root.absolutePath} in %.1f s".format(Locale.ROOT, (System.nanoTime() - began) / 1e9))
    }

    private fun profiles(): List<Profile> {
        val base = OrphicaConfig(material = OrphicaMaterial.NYLON, mode = OrphicaMode.SCATTER,
            midi = 60, velocity = 0.8f, pluck = 0.6f, fracture = 0.5f,
            grainSizeMs = 65f, grainRateHz = 32f, scatter = 0.55f, damping = 0.25f,
            seconds = 3.6f, seed = 810_013L, captureSeconds = 0.18f)
        return listOf(
            Profile("clear", "Clear harp", "Hear the source alone: a distinct nylon-string attack and its natural decay. CLEAR is an explicit dry endpoint.",
                base.copy(mode = OrphicaMode.CLEAR, fracture = 0f)),
            Profile("gentle", "Gentle scatter", "Follow the intact pluck into a light stereo cloud. Fracture 0.25 keeps the note in front of its scattered history.",
                base.copy(fracture = 0.25f, grainSizeMs = 80f, grainRateHz = 28f, scatter = 0.35f)),
            Profile("shattered", "Shattered scatter", "Listen for a clear attack followed by dense, short pieces of the same note. Fracture 0.9 exposes the granulated sustain.",
                base.copy(fracture = 0.9f, grainSizeMs = 40f, grainRateHz = 45f, scatter = 0.9f)),
            Profile("reverse", "Reverse string", "The steel pluck remains readable while backward history fragments swell behind it. Listen for rising details inside the reversed string fragments.",
                base.copy(material = OrphicaMaterial.STEEL, mode = OrphicaMode.REVERSE,
                    fracture = 0.72f, grainSizeMs = 115f, grainRateHz = 26f, scatter = 0.65f)),
            Profile("freeze", "Captured crystal sustain", "A crystal string is recorded for 0.18 s, then its history is held for later grains. This 3.6 s snapshot ends with a short fade; it is not a live latch or a seamless loop.",
                base.copy(material = OrphicaMaterial.CRYSTAL, mode = OrphicaMode.FREEZE,
                    fracture = 0.85f, grainSizeMs = 95f, grainRateHz = 36f, scatter = 0.55f)),
            Profile("stutter", "History stutter", "Repeated short reads revisit one recent slice of the nylon note. Compare the patterned tail with scatter's less regular travel.",
                base.copy(mode = OrphicaMode.STUTTER, fracture = 0.65f,
                    grainSizeMs = 70f, grainRateHz = 18f, scatter = 0.1f)),
        )
    }

    private fun cases(): List<Case> = buildList {
        val profiles = profiles()
        for (profile in profiles) add(Case("core_${profile.id}", profile.title, profile.description,
            "Core profiles", config = profile.config))
        for (profile in profiles) {
            val common = "Same ${profile.config.material.name.lowercase()} source, C4, velocity 0.8, seed 810013 and 3.6 s duration. "
            add(Case("ab_${profile.id}_dry", "${profile.title} · dry source",
                common + "Hear the string before the history grains; compare immediately with its processed partner.",
                "Same-source A/B", kind = "dry", config = profile.config, pairId = profile.id))
            add(Case("ab_${profile.id}_processed", "${profile.title} · processed",
                common + profile.description,
                "Same-source A/B", config = profile.config, pairId = profile.id))
        }
        val fixed = profiles.first { it.id == "gentle" }.config.copy(fracture = 0.5f,
            grainSizeMs = 65f, grainRateHz = 32f, scatter = 0.55f)
        for ((index, fracture) in listOf(0f, 0.25f, 0.5f, 0.8f, 1f).withIndex()) {
            add(Case("fracture_$index", "FRACTURE ${"%.2f".format(Locale.ROOT, fracture)}",
                "Only FRACTURE changes; nylon C4, size 65 ms, rate 32 Hz and scatter 0.55 remain fixed. " +
                    if (fracture == 0f) "Zero is the dry source endpoint." else "Listen for the balance and evolving texture behind the preserved attack.",
                "Fracture travel", config = fixed.copy(fracture = fracture)))
        }
        for ((note, midi) in listOf("C3" to 48, "C4" to 60, "C5" to 72)) {
            add(Case("pitch_${note.lowercase()}", "$note · same scatter recipe",
                "Only the rendered MIDI note changes. Grain duration and rate stay fixed in milliseconds and Hz; listen for the cloud's relationship to the string pitch.",
                "Pitch and material", config = fixed.copy(midi = midi)))
        }
        for (material in OrphicaMaterial.entries) {
            add(Case("material_${material.name.lowercase()}", "${material.name.lowercase().replaceFirstChar { it.uppercase() }} · C4",
                "Same C4 scatter recipe and excitation; only the string material changes. Compare the pluck colour and the grains made from that colour.",
                "Pitch and material", config = fixed.copy(material = material)))
        }
        for ((label, velocity) in listOf("Soft" to 0.3f, "Hard" to 1f)) {
            add(Case("excitation_${label.lowercase()}", "$label excitation",
                "Only velocity changes: ${"%.2f".format(Locale.ROOT, velocity)}. Matched playback reveals articulation; raw playback preserves the change in strength.",
                "Excitation", config = fixed.copy(velocity = velocity)))
        }
        val freeze = profiles.first { it.id == "freeze" }.config
        for ((label, capture) in listOf("middle" to 0.65f, "late" to 0.95f)) {
            add(Case("capture_$label", "Freeze capture · ${"%.2f".format(Locale.ROOT, capture)} s",
                "Only the time at which history recording stops changes. The held quarter-second ends at the capture time; listen for a later, quieter portion of the crystal decay. Grains continue from that history until this finite clip fades.",
                "Freeze capture", config = freeze.copy(captureSeconds = capture)))
        }
        val arp = listOf(48, 55, 60, 64, 67, 72).mapIndexed { i, midi ->
            Event(i * 0.24f, fixed.copy(midi = midi, velocity = 0.72f, fracture = 0.4f,
                seconds = 5f - i * 0.24f, seed = 810_100L + i))
        }
        add(Case("phrase_arpeggio", "Staggered C major arpeggio",
            "Six independently rendered notes enter 240 ms apart from C3 to C5. Each string supplies its own history and grain cloud; the voices are summed with no reverb.",
            "Musical phrases", kind = "phrase", events = arp, seconds = 5f))
        val chord = listOf(48, 55, 60).mapIndexed { i, midi ->
            Event(i * 0.025f, freeze.copy(midi = midi, velocity = 0.56f, seconds = 5f - i * 0.025f,
                seed = 810_200L + i))
        } + listOf(
            Event(1.5f, fixed.copy(midi = 64, velocity = 0.8f, fracture = 0.35f, seconds = 3.5f, seed = 810_210L)),
            Event(2.25f, fixed.copy(midi = 67, velocity = 0.75f, fracture = 0.45f, seconds = 2.75f, seed = 810_211L)),
        )
        add(Case("phrase_overlap", "Captured chord beneath new notes",
            "Three crystal notes capture their own histories after 0.18 s; E4 and G4 enter at 1.5 and 2.25 s. This sums separate finite voices. New notes never enter the held histories, and the chord is not captured by a live performance latch.",
            "Musical phrases", kind = "phrase", events = chord, seconds = 5f))
    }

    private fun mix(events: List<Event>, seconds: Float): Snip = mixVoices(
        events.map { it.offsetSeconds to Orphica.render(it.config) }, seconds)

    private fun mixVoices(voices: List<Pair<Float, Snip>>, seconds: Float): Snip {
        val frames = (seconds * RATE).roundToInt()
        val out = FloatArray(frames * 2)
        for ((offsetSeconds, voice) in voices) {
            check(voice.sampleRate == RATE && voice.channels == 2)
            val start = (offsetSeconds * RATE).roundToInt()
            for (frame in 0 until minOf(voice.frameCount, frames - start)) {
                out[(start + frame) * 2] += voice.samples[frame * 2]
                out[(start + frame) * 2 + 1] += voice.samples[frame * 2 + 1]
            }
        }
        return Snip(out, channels = 2, sampleRate = RATE)
    }

    private fun fadeTail(snip: Snip): Snip {
        val samples = snip.samples.copyOf()
        val fade = minOf(snip.frameCount, (TAIL_FADE_SECONDS * snip.sampleRate).roundToInt())
        val first = snip.frameCount - fade
        for (frame in first until snip.frameCount) {
            val gain = (snip.frameCount - 1 - frame).toFloat() / (fade - 1).coerceAtLeast(1)
            for (channel in 0 until snip.channels) samples[frame * snip.channels + channel] *= gain
        }
        return Snip(samples, snip.channels, snip.sampleRate)
    }

    private fun withGain(snip: Snip, gain: Float) = Snip(
        FloatArray(snip.samples.size) { snip.samples[it] * gain }, snip.channels, snip.sampleRate)

    private fun validate(snip: Snip, id: String) {
        check(snip.sampleRate == RATE && snip.channels == 2) { "$id must be stereo 44.1 kHz" }
        check(snip.frameCount > 0 && snip.durationSeconds <= 5.0001f) { "$id is empty or too long" }
        check(snip.samples.all { it.isFinite() }) { "$id has non-finite samples" }
        check(snip.peak() > 1e-5f && Loudness.of(snip) > 1e-7f) { "$id is silent" }
    }

    private fun metrics(snip: Snip): Metrics {
        var energy = 0.0
        var sum = 0.0
        for (sample in snip.samples) {
            energy += sample.toDouble() * sample
            sum += sample
        }
        return Metrics(snip.peak(), Loudness.of(snip), sqrt(energy / snip.samples.size), sum / snip.samples.size)
    }

    private fun verifyPcm(file: File, expected: Snip): Float {
        val decoded = WavReader.read(file)
        check(decoded.channels == expected.channels && decoded.sampleRate == expected.sampleRate &&
            decoded.frameCount == expected.frameCount) { "bad WAV format: ${file.name}" }
        validate(decoded, file.name)
        check(decoded.peak() < 0.991f) { "clipped PCM: ${file.name}" }
        var error = 0f
        for (i in decoded.samples.indices) error = maxOf(error, abs(decoded.samples[i] - expected.samples[i]))
        // The writer maps to 8388607 while the reader divides by 8388608; allow quantization plus scale.
        check(error <= 2.5e-7f) { "PCM changed samples beyond 24-bit quantization: ${file.name}: $error" }
        return error
    }

    private fun recipe(case: Case): JsonValue {
        if (case.config != null) {
            val replayConfig = if (case.kind == "dry") case.config.copy(mode = OrphicaMode.CLEAR, fracture = 0f) else case.config
            val values = LinkedHashMap(OrphicaPatch(case.title, replayConfig).toJsonValue().entries)
            values["audition"] = auditionMetadata(case.kind)
            return JsonValue.Obj(values)
        }
        return obj(
            "format" to str("orphica-audition-phrase"), "formatVersion" to num(1),
            "prototypeVersion" to str(VERSION), "name" to str(case.title), "seconds" to num(case.seconds),
            "events" to arr(case.events.mapIndexed { index, event -> obj(
                "offsetSeconds" to num(event.offsetSeconds),
                "patch" to OrphicaPatch("${case.title} note ${index + 1}", event.config).toJsonValue()) }),
            "audition" to auditionMetadata(case.kind),
        )
    }

    private fun auditionMetadata(kind: String) = obj(
        "kind" to str(kind), "prototypeVersion" to str(VERSION), "tailFadeSeconds" to num(TAIL_FADE_SECONDS),
        "gain" to str("Native recipe level. Raw delivery uses manifest.rawGain; matched uses AuditionLevel.level."),
    )

    private fun replay(recipe: JsonValue): Snip {
        val values = recipe.obj()
        val native = if (values["format"]?.str() == "orphica-audition-phrase") {
            val voices = values.getValue("events").arr().map { event ->
                val item = event.obj()
                item.getValue("offsetSeconds").num().toFloat() to Patches.fromJsonValue(item.getValue("patch")).render()
            }
            mixVoices(voices, values.getValue("seconds").num().toFloat())
        } else Patches.fromJsonValue(recipe).render()
        return fadeTail(native)
    }

    private fun recipeConfig(case: Case): JsonValue = case.config?.let(::configJson) ?: obj(
        "seconds" to num(case.seconds),
        "events" to arr(case.events.map { event -> obj(
            "offsetSeconds" to num(event.offsetSeconds), "config" to configJson(event.config)) }),
    )

    private fun configJson(config: OrphicaConfig): JsonValue =
        OrphicaPatch("Audition config", config).toJsonValue().entries.getValue("config")

    private fun obj(vararg values: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*values))
    private fun arr(values: List<JsonValue>) = JsonValue.Arr(values)
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number): JsonValue = JsonValue.Num(value.toDouble().also { check(it.isFinite()) })
}
