package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** New model-3 experiments beside the accepted model-2 audition, never over it. */
object OhmExpandedAuditionGenerator {
    internal const val RATE = 44_100
    internal const val GAP_FRAMES = 13_230
    private const val MODEL = 3
    private const val SEED = 731
    private const val VELOCITY = .8f

    internal data class Part(
        val label: String,
        val patch: OhmPatch,
        val heldRepeats: Int = 0,
        val onsetFrames: Int? = null,
        val gain: Float = 1f,
    )

    internal data class Study(
        val id: String,
        val title: String,
        val description: String,
        val group: String,
        val feature: String,
        val context: String?,
        val parts: List<Part>,
        val matchParts: Boolean = false,
    )

    private data class AudioPart(val spec: Part, val sound: Snip, val loop: Map<String, Any?>?, val heldSource: OhmV3.HeldProbe? = null)
    private data class Rendered(val study: Study, val raw: Snip, val matched: Snip,
                                val events: List<Map<String, Any?>>, val heldSources: List<OhmV3.HeldProbe?>)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size in 3..4) { "--replay SCORE.json OUTPUT.wav [raw|matched]" }
            val mode = args.getOrNull(3) ?: "matched"
            val out = replay(Json.parse(File(args[1]).readText()), mode)
            writeWav(File(args[2]), out)
            return
        }
        require(args.size <= 1) { "supply only the output directory" }
        generate(File(args.firstOrNull() ?: "../testkit/ohm-expanded-audition"))
    }

    fun generate(output: File) {
        val root = output.canonicalFile
        require(!root.exists() || root.isDirectory) { "output must be a directory" }
        root.mkdirs()
        val sources = sourceHashes()
        val capture = OhmCapture.fromSnip(authoredHit(), "Authored felt tap")
        val studies = studies(capture)
        require(studies.size == 19 && studies.map { it.id }.distinct().size == 19)
        val rendered = studies.map(::render)
        val sourcePeak = rendered.flatMap { it.heldSources.filterNotNull() }.maxOfOrNull { probe ->
            maxOf(peak(probe.rawNote.snip), probe.physicalLoop.maxOf { abs(it).toDouble() }, probe.rawNextCycle.maxOf { abs(it).toDouble() })
        } ?: 0.0
        val rawPeak = maxOf(rendered.maxOf { peak(it.raw) }, sourcePeak)
        val rawGain = minOf(1.0, .98 / rawPeak.coerceAtLeast(1e-12))
        val clips = rendered.map { writeClip(root, it, rawGain) }
        writeWav(File(root, "assets/source/authored-felt-tap.wav"), authoredHit())
        val captureEvidence = linkedMapOf<String, Any?>(
            "description" to "Original generated audio hit: two decaying sinusoidal components and seeded noise. No external recording.",
            "file" to "assets/source/authored-felt-tap.wav", "sha256" to sha(File(root, "assets/source/authored-felt-tap.wav")),
            "preparedCapture" to capture.toJsonValue(),
        )
        check(sourceHashes() == sources) { "OHM sources changed while rendering this collection" }
        writeJson(File(root, "assets/manifest.json"), linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "OHM / Extended family", "modelVersion" to MODEL,
            "renderedAt" to Instant.now().toString(), "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(), "rendererSources" to sources,
            "sampleRate" to RATE, "channels" to 1, "format" to "PCM_16", "rawExportGain" to rawGain,
            "acceptedAudition" to "https://ohm-snipsnap-audition.bertcalm.chatgpt.site/",
            "bundle" to "assets/ohm-expanded-audition.zip", "sourceCapture" to captureEvidence,
            "description" to "Six optional features, each heard alone in independent and coupled relationships, then combined in phrases and sustained notes.",
            "notes" to listOf(
                "These are actual model-3 Kotlin renders. The five accepted factory recipes and original listening room remain model 2.",
                "Each joined comparison plays OFF, .30 seconds of silence, then ON. Note, velocity, seed and the other optional features stay fixed.",
                "Independent keeps MEDDLING .65 and COMMUNE 0. Shared keeps MEDDLING .65 and COMMUNE 1. Independent does not mean solo.",
                "Raw uses conditioned pre-level renderer audio and one common PCM headroom gain. Matched levels each comparison part separately; phrases and held studies are matched once as complete clips.",
                "Freeze captures a populated source-audio buffer. It is captured-buffer freeze, not an FFT spectral-freeze claim.",
                "Held studies preserve the attack once and repeat only the declared sustain region eight times. Bowed HOLD uses a captured, blended stretch of continuing physical bow. A final 120 ms fade belongs to the listening copy, not to the looping instrument sample.",
                "The authored captured hit is included as a WAV; its prepared PCM is embedded in every relevant recipe. No source file is needed to replay that recipe.",
                "Measurements and reproducibility checks do not establish listening approval or physical phone/MPC acceptance.",
            ),
            "groups" to listOf(
                mapOf("id" to "features", "title" to "One addition at a time"),
                mapOf("id" to "phrases", "title" to "Together in a phrase"),
                mapOf("id" to "held", "title" to "Arrival, then eight sustain passes"),
            ),
            "clips" to clips,
        ))
        writeJson(File(root, "assets/diagnostics.json"), mapOf(
            "clipCount" to clips.size, "allFinite" to true, "highestUnscaledRawPeak" to rawPeak,
            "rawExportGain" to rawGain, "clips" to clips.map { mapOf("id" to it["id"], "metrics" to it["metrics"], "hashes" to it["hashes"]) },
        ))
        File(root, "README.txt").writeText(readme(rawGain))
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val stream = requireNotNull(javaClass.getResourceAsStream("/ohm-expanded-audition/$name")) { "missing $name" }
            File(root, name).outputStream().use { destination -> stream.use { it.copyTo(destination) } }
        }
        val paths = mutableSetOf("index.html", "styles.css", "app.js", "README.txt", "assets/manifest.json",
            "assets/diagnostics.json", "assets/source/authored-felt-tap.wav")
        for (clip in clips) {
            for (field in listOf("raw", "matched", "recipe")) paths += clip.getValue(field).toString()
            @Suppress("UNCHECKED_CAST")
            for (event in clip.getValue("parts") as List<Map<String, Any?>>) {
                @Suppress("UNCHECKED_CAST")
                val loop = event["loop"] as? Map<String, Any?>
                for (key in listOf("sourceAudio", "physicalCycleAudio", "followingPhysicalCycleAudio")) loop?.get(key)?.let { paths += it.toString() }
            }
        }
        archive(root, File(root, "assets/ohm-expanded-audition.zip"), paths)
        println("OHM expanded: wrote ${clips.size} raw/matched studies, exact replay scores, source hit and full listening ZIP under ${root.path}")
    }

    internal fun studies(capture: OhmCapture): List<Study> = buildList {
        val features = listOf("bow", "capture", "elder", "grains", "freeze", "sustain")
        val titles = mapOf("bow" to "Bowed contact", "capture" to "Captured hit", "elder" to "FM elder",
            "grains" to "Audio grains", "freeze" to "Captured-buffer freeze", "sustain" to "Held relationship")
        for (feature in features) for (shared in listOf(false, true)) {
            val context = if (shared) "shared" else "independent"
            val base = patch("${titles.getValue(feature)} / off", commune = if (shared) 1f else 0f)
            val options = when (feature) {
                "bow" -> OhmOptions(exciter = OhmExciter.BOW)
                "capture" -> OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture)
                "elder" -> OhmOptions(elder = 1f, fmRatio = 1.5f, fmIndex = 3f)
                "grains" -> OhmOptions(grains = 1f, grainSeconds = .05f)
                "freeze" -> OhmOptions(freeze = 1f)
                else -> OhmOptions()
            }
            val on = base.copy(name = "${titles.getValue(feature)} / on", options = options,
                macros = base.macros + ("HOLD" to if (feature == "sustain") 1f else 0f))
            val explanation = when (feature) {
                "bow" -> "The pluck becomes continuing friction contact on the foreground string."
                "capture" -> "The included authored audio hit replaces the generated pick excitation."
                "elder" -> "A bounded two-operator FM source enters the companion excitation. Index is 3 radians, ratio 3:2."
                "grains" -> "Actual recent source-audio slices are read through short Hann windows."
                "freeze" -> "A populated source buffer is captured and read as continuing memory. Grain amount stays zero."
                else -> "The finite note becomes an arrival followed by two passes of its declared held region."
            }
            add(Study("$feature-$context", titles.getValue(feature), explanation,
                "features", feature, context,
                listOf(Part("OFF", base), Part("ON", on, heldRepeats = if (feature == "sustain") 2 else 0)), matchParts = true))
        }
        val phrases = listOf(
            Triple("bowed-gathering", "Bowed gathering", OhmOptions(exciter = OhmExciter.BOW, elder = .35f)),
            Triple("captured-memory", "A hit leaves a memory", OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture, grains = .65f, freeze = .5f)),
            Triple("elder-company", "The elder joins in", OhmOptions(elder = .85f, fmIndex = 2.4f, grains = .35f, freeze = .25f)),
        )
        for ((id, title, options) in phrases) {
            val notes = listOf(60, 63, 67, 60)
            val starts = listOf(0.0, .68, 1.36, 2.12)
            add(Study("phrase-$id", title, "The same four-note phrase, C4–E♭4–G4–C4, with complete notes and natural overlapping endings.",
                "phrases", "combined", null, notes.mapIndexed { i, midi ->
                    Part("Note ${i + 1}", patch("$title / ${i + 1}", midi = midi, length = .45f, options = options,
                        gesture = OhmGesture.GATHER), onsetFrames = (starts[i] * RATE).roundToInt(), gain = .45f)
                }))
        }
        val held = listOf(
            patch("Held pluck", hold = 1f),
            patch("Held bow / gathering", hold = 1f, options = OhmOptions(exciter = OhmExciter.BOW), gesture = OhmGesture.GATHER),
            patch("Held grains and memory", hold = 1f, options = OhmOptions(grains = .75f, freeze = .8f)),
            patch("Held high elder", midi = 72, hold = 1f, options = OhmOptions(elder = .85f, fmRatio = 2f, fmIndex = 3f)),
        )
        for ((i, p) in held.withIndex()) add(Study("held-${i + 1}", p.name,
            if (p.options.exciter == OhmExciter.BOW) "The bowed arrival plays once, followed by eight passes of a captured, blended stretch of continuing bow. The listening copy fades at the end."
            else "The authored arrival plays once. Only its saved sustain region repeats eight times; the listening copy fades at the end.",
            "held", "sustain", "shared", listOf(Part("Arrival + 8 sustain passes", p, heldRepeats = 8))))
    }

    private fun patch(name: String, midi: Int = 60, length: Float = .65f, commune: Float = 1f,
                      hold: Float = 0f, options: OhmOptions = OhmOptions(), gesture: OhmGesture = OhmGesture.STEADY) =
        OhmPatch(name, Ohm.defaults(MODEL) + mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to length,
            "MEDDLING" to .65f, "COMMUNE" to commune, "HOLD" to hold), midi, VELOCITY, SEED, gesture, MODEL, options)

    /** A short original source, included before capture preparation so the source experiment is inspectable. */
    internal fun authoredHit(): Snip {
        val noise = Dsp.Noise(731)
        val samples = FloatArray((.25 * RATE).roundToInt()) { i ->
            val t = i.toDouble() / RATE
            val attack = minOf(1.0, t / .0015)
            (attack * (.37 * exp(-t * 34) * sin(2 * PI * 317 * t) +
                .18 * exp(-t * 58) * sin(2 * PI * 863 * t) + .12 * exp(-t * 95) * noise.next())).toFloat()
        }
        Dsp.fadeTail(samples, 12f, RATE)
        return Snip(samples, 1, RATE)
    }

    private fun render(study: Study): Rendered {
        val parts = study.parts.map { spec ->
            val patch = spec.patch
            check(OhmPatch.fromJsonText(patch.toJsonText()) == patch) { "${study.id}: patch options changed on replay" }
            if (spec.heldRepeats > 0) {
                val probe = OhmV3.heldProbe(patch.macros, patch.midi, patch.velocity, patch.seed, patch.gesture, patch.options)
                val note = probe.rawNote
                val start = note.loopStartFrame.toInt()
                val metrics = linkedMapOf<String, Any?>(
                    "sourceLoopStartFrame" to start, "sourceLoopFrames" to note.snip.frameCount - start,
                    "sourceFrames" to note.snip.frameCount, "repeats" to spec.heldRepeats,
                    "storedJoinWindowError" to Keys.seamError(note.snip.samples, start),
                    "joinMetric" to "Keys.seamError over the stored preceding 256 frames; independent source-continuation evidence belongs to the renderer probes",
                    "sustainMethod" to probe.sustainMethod, "appliedBlend" to probe.appliedBlend,
                    "rawPhysicalContinuationError" to probe.rawContinuationError,
                    "outputContinuationError" to probe.continuationError,
                    "settledCycles" to probe.settledCycles, "blendFrames" to probe.blendFrames,
                    "joinCorrelation" to probe.joinCorrelation, "blendDipDb" to probe.blendDipDb,
                    "wrapStepRatio" to probe.wrapStepRatio, "wrapSlopeRatio" to probe.wrapSlopeRatio,
                    "maximumInternalState" to probe.maxState, "effectiveHz" to probe.effectiveHz,
                    "previewFadeMilliseconds" to 120,
                )
                AudioPart(spec, heldPresentation(note, spec.heldRepeats), metrics, probe)
            } else AudioPart(spec, Ohm.renderRaw(patch.macros, patch.midi, patch.velocity, patch.seed, patch.gesture, patch.model, patch.options), null)
        }
        parts.forEach { checkSound(study.id, it.sound) }
        var cursor = 0
        val events = parts.map { p ->
            val onset = p.spec.onsetFrames ?: cursor
            cursor = onset + p.sound.frameCount + GAP_FRAMES
            linkedMapOf<String, Any?>(
                "label" to p.spec.label, "onsetFrames" to onset, "endFrameExclusive" to onset + p.sound.frameCount,
                "frames" to p.sound.frameCount, "gain" to p.spec.gain,
                "source" to if (p.spec.heldRepeats > 0) "Ohm.renderHeldRaw" else "Ohm.renderRaw",
                "heldRepeats" to p.spec.heldRepeats, "loop" to p.loop, "patch" to p.spec.patch.toJsonValue(),
            )
        }
        val frames = events.maxOf { (it.getValue("endFrameExclusive") as Int) }
        val raw = mix(parts.map { it.sound }, events, frames)
        val matched = if (study.matchParts) mix(parts.map { AuditionLevel.level(it.sound) }, events, frames) else AuditionLevel.level(raw)
        checkSound(study.id, raw)
        println("OHM expanded ${study.id}: ${"%.2f".format(Locale.ROOT, raw.durationSeconds)} s")
        return Rendered(study, raw, matched, events, parts.map { it.heldSource })
    }

    /** Copy the arrival once and only the declared loop thereafter. Never fade the instrument material in place. */
    internal fun heldPresentation(note: KeyNote, repeats: Int, fadeMilliseconds: Float = 120f): Snip {
        require(repeats in 1..8)
        val start = note.loopStartFrame.toInt()
        val source = note.snip
        require(source.channels == 1 && source.sampleRate == RATE && start in 256 until source.frameCount - 1)
        val period = source.frameCount - start
        val frames = start.toLong() + period.toLong() * repeats
        require(frames <= 180L * RATE) { "held audition exceeds its bounded presentation length" }
        val samples = FloatArray(frames.toInt())
        source.samples.copyInto(samples, 0, 0, start)
        repeat(repeats) { source.samples.copyInto(samples, start + it * period, start, source.frameCount) }
        if (fadeMilliseconds > 0f) Dsp.fadeTail(samples, fadeMilliseconds, RATE)
        return Snip(samples, 1, RATE)
    }

    private fun mix(parts: List<Snip>, events: List<Map<String, Any?>>, frames: Int): Snip {
        val out = FloatArray(frames)
        for (i in parts.indices) {
            val offset = events[i].getValue("onsetFrames") as Int
            val gain = events[i].getValue("gain") as Float
            for (j in parts[i].samples.indices) out[offset + j] += parts[i].samples[j] * gain
        }
        return Snip(out, 1, RATE)
    }

    private fun writeClip(root: File, r: Rendered, rawGain: Double): Map<String, Any?> {
        val raw = scaled(r.raw, rawGain)
        check(peak(raw) <= .980001 && peak(r.matched) <= .990001) { "${r.study.id}: unsafe PCM headroom" }
        val prefix = "assets/audio/${r.study.id}"
        val rawPath = "$prefix-raw.wav"
        val matchedPath = "$prefix-matched.wav"
        val recipePath = "assets/recipes/${r.study.id}.json"
        writeWav(File(root, rawPath), raw)
        writeWav(File(root, matchedPath), r.matched)
        val events = r.events.mapIndexed { i, event ->
            val probe = r.heldSources[i]
            if (probe == null) event else {
                val note = probe.rawNote
                val sourcePath = "assets/held-source/${r.study.id}-${i + 1}.wav"
                val physicalPath = "assets/held-source/${r.study.id}-${i + 1}-physical.wav"
                val futurePath = "assets/held-source/${r.study.id}-${i + 1}-following.wav"
                writeWav(File(root, sourcePath), scaled(note.snip, rawGain))
                writeWav(File(root, physicalPath), scaled(Snip(probe.physicalLoop, 1, RATE), rawGain))
                writeWav(File(root, futurePath), scaled(Snip(probe.rawNextCycle, 1, RATE), rawGain))
                @Suppress("UNCHECKED_CAST")
                val loop = event.getValue("loop") as Map<String, Any?>
                event + ("loop" to (loop + mapOf("sourceAudio" to sourcePath,
                    "sourceAudioGain" to rawGain, "sourceAudioSha256" to sha(File(root, sourcePath)),
                    "physicalCycleAudio" to physicalPath, "physicalCycleSha256" to sha(File(root, physicalPath)),
                    "followingPhysicalCycleAudio" to futurePath, "followingPhysicalCycleSha256" to sha(File(root, futurePath)))))
            }
        }
        val score = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "type" to "OHM_EXPANDED_AUDITION", "name" to r.study.title,
            "sampleRate" to RATE, "channels" to 1, "frames" to r.raw.frameCount,
            "gapFrames" to if (r.study.matchParts) GAP_FRAMES else 0,
            "matchParts" to r.study.matchParts, "rawExportGain" to rawGain,
            "matchedPolicy" to if (r.study.matchParts) "AuditionLevel per part before concatenation" else "AuditionLevel on the complete mix",
            "events" to events,
        )
        writeJson(File(root, recipePath), score)
        return linkedMapOf(
            "id" to r.study.id, "title" to r.study.title, "description" to r.study.description,
            "group" to r.study.group, "feature" to r.study.feature, "context" to r.study.context,
            "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath, "seconds" to r.raw.durationSeconds,
            "parts" to events, "matchedLevelPolicy" to score["matchedPolicy"],
            "waveform" to waveform(r.matched.samples),
            "metrics" to mapOf("frames" to r.raw.frameCount, "finite" to true, "rawPeak" to peak(r.raw),
                "rawRms" to rms(r.raw), "rawDc" to r.raw.samples.average(), "rawLoudness" to Loudness.of(r.raw),
                "matchedPeak" to peak(r.matched), "matchedLoudness" to Loudness.of(r.matched), "pcmValidated" to true),
            "hashes" to mapOf("rawWavSha256" to sha(File(root, rawPath)), "matchedWavSha256" to sha(File(root, matchedPath)),
                "recipeSha256" to sha(File(root, recipePath))),
        )
    }

    /** Replay the ordinary JSON score without relying on the listening page or original capture file. */
    internal fun replay(value: JsonValue, mode: String): Snip {
        require(mode in setOf("raw", "matched")) { "playback mode must be raw or matched" }
        val score = value.obj()
        require(score.getValue("type").str() == "OHM_EXPANDED_AUDITION")
        require(score.getValue("sampleRate").int() == RATE && score.getValue("channels").int() == 1)
        val frames = score.getValue("frames").int()
        require(frames in 1..180 * RATE)
        val events = (score.getValue("events") as JsonValue.Arr).items.map { it.obj() }
        require(events.size in 1..8)
        val sounds = events.map { event ->
            val p = OhmPatch.fromJsonValue(event.getValue("patch")) as OhmPatch
            val repeats = event.getValue("heldRepeats").int()
            val sound = when (event.getValue("source").str()) {
                "Ohm.renderRaw" -> {
                    require(repeats == 0)
                    Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model, p.options)
                }
                "Ohm.renderHeldRaw" -> heldPresentation(Ohm.renderHeldRaw(p), repeats)
                else -> error("unknown OHM audition source")
            }
            require(sound.frameCount == event.getValue("frames").int()) { "saved renderer length changed" }
            checkSound("replay", sound)
            sound
        }
        val descriptors = events.map { event -> mapOf<String, Any?>(
            "onsetFrames" to event.getValue("onsetFrames").int(), "gain" to event.getValue("gain").num().toFloat(),
        ).also { d -> require((d.getValue("onsetFrames") as Int) >= 0) } }
        for (i in sounds.indices) require((descriptors[i].getValue("onsetFrames") as Int).toLong() + sounds[i].frameCount <= frames)
        val perPart = (score.getValue("matchParts") as JsonValue.Bool).value
        val raw = mix(sounds, descriptors, frames)
        return if (mode == "raw") scaled(raw, score.getValue("rawExportGain").num())
        else if (perPart) mix(sounds.map(AuditionLevel::level), descriptors, frames) else AuditionLevel.level(raw)
    }

    private fun writeWav(file: File, sound: Snip) {
        file.parentFile.mkdirs()
        WavWriter.write(file, sound, WavWriter.BitDepth.PCM_16)
        val decoded = WavReader.read(file)
        check(decoded.sampleRate == RATE && decoded.channels == 1 && decoded.frameCount == sound.frameCount)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - sound.samples[it]) < 2f / 32768f }) { "${file.name}: PCM16 changed more than quantization" }
    }

    private fun checkSound(id: String, sound: Snip) {
        check(sound.channels == 1 && sound.sampleRate == RATE && sound.samples.isNotEmpty()) { "$id: invalid audio format" }
        check(sound.samples.all { it.isFinite() } && Loudness.of(sound) > 1e-8f) { "$id: non-finite or silent render" }
    }
    private fun scaled(s: Snip, gain: Double) = Snip(FloatArray(s.frameCount) { (s.samples[it] * gain).toFloat() }, 1, RATE)
    private fun peak(s: Snip) = s.samples.maxOf { abs(it).toDouble() }
    private fun rms(s: Snip) = sqrt(s.samples.sumOf { it.toDouble() * it } / s.samples.size)
    private fun waveform(samples: FloatArray) = List(96) { bin ->
        val from = bin * samples.size / 96
        val until = maxOf(from + 1, (bin + 1) * samples.size / 96)
        (from until until).maxOf { abs(samples[it]).toDouble() }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git failed" }
        return result
    }
    private fun sourceHashes(): Map<String, String> {
        val repo = File(git("rev-parse", "--show-toplevel"))
        return listOf("Ohm.kt", "OhmPatch.kt", "OhmOptions.kt", "OhmCapture.kt", "OhmV2.kt", "OhmV3.kt", "OhmTextures.kt", "Strings.kt", "Dsp.kt", "Bore.kt")
            .map { "synth/src/main/kotlin/com/snipsnap/synth/$it" }
            .plus(listOf("Cleanup.kt", "Resampler.kt", "Loudness.kt", "WavWriter.kt").map { "audio/src/main/kotlin/com/snipsnap/audio/$it" })
            .plus("synth/src/test/kotlin/com/snipsnap/synth/OhmExpandedAuditionGenerator.kt")
            .plus("synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt")
            .plus(listOf("index.html", "styles.css", "app.js").map { "synth/src/test/resources/ohm-expanded-audition/$it" })
            .associateWith { sha(File(repo, it)) }
    }
    private fun archive(root: File, output: File, paths: Set<String>) {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            for (path in paths.sorted()) {
                require(".." !in path.split('/') && !path.startsWith('/') && path != output.relativeTo(root).invariantSeparatorsPath)
                val file = File(root, path)
                require(file.isFile) { "missing audition bundle input $path" }
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported audition JSON value ${value.javaClass.name}")
    }
    private fun readme(rawGain: Double) = """
        OHM / EXTENDED FAMILY — model-3 listening experiments

        Twelve joined OFF/ON comparisons cover bowing, an embedded captured hit, FM elder,
        real audio grains, captured-buffer freeze and sustained playback. Each feature is
        isolated at COMMUNE0 and COMMUNE1, with MEDDLING.65. Three four-note phrases combine
        the additions. Four held studies play an arrival once and the actual sustain region
        eight times. All files are mono44.1kHz PCM16 from the Kotlin renderer.

        Raw is conditioned pre-level renderer output with one common headroom gain: $rawGain.
        Matched applies the shared AuditionLevel rule to each OFF/ON part independently;
        phrases and held studies are matched once as complete clips. Joined parts have
        exactly13230silentframes(.30s) between them. The held listening copy has a120ms fade;
        the instrument's looping source is never modified by that presentation fade.

        assets/recipes contains complete replay scores: every patch, embedded capture,
        native note, velocity, seed, model, renderer path, gain and frame offset. Reproduce:
          ./gradlew :synth:generateOhmExpandedAudition -PohmExpandedReplay=/path/score.json \
            -PohmExpandedReplayOutput=/path/out.wav -PohmExpandedReplayMode=matched
        Select raw for common-gain raw export. Replay never substitutes current defaults.

        assets/source/authored-felt-tap.wav is the original generated capture source;
        recipes embed prepared PCM and remain usable after that file is deleted.
        assets/held-source preserves each pre-level attack+loop without a presentation
        fade, under the same raw headroom gain. Its exact loop marker, period and gain
        are recorded in the score and manifest. Repeat only the marked sustain region.
        The -physical and -following WAVs are independent continued source periods.
        Bowed HOLD uses a captured, blended physical sustain stretch; its actual
        physical continuation error and blend/join measurements stay in the manifest.
        assets/manifest.json records source hashes, exact parts, levels and WAV hashes.
        The ZIP includes every study, recipe, source file and listening-page resource.
        Serve its folder over HTTP (for example python3 -m http.server) to open the page.

        This is a new listening prototype. The accepted five factory encounters and
        original audition remain available separately. Nothing here claims listening,
        physical-phone or MPC-hardware approval. Freeze means captured source-buffer
        memory, not FFT spectral freeze. Independent notes do not share live state.
    """.trimIndent() + "\n"
}
