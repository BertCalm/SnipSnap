package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Dry, reproducible source renders for listening. The website reads manifest.json directly. */
object BellowsAuditionGenerator {
    private data class Clip(val id: String, val title: String, val section: String,
        val description: String, val patch: BellowsPatch, val comparison: Comparison? = null,
        val joinedWith: BellowsPatch? = null, val role: String? = null, val performance: Performance? = null)
    private data class Comparison(val parameter: String, val endpoint: String, val value: Float?,
        val pairId: String, val lowLabel: String, val highLabel: String)
    private data class PlayedNote(val midi: Int, val startBeat: Double, val holdBeats: Double, val velocity: Float)
    private data class Performance(val kind: String, val bpm: Int, val notes: List<PlayedNote>,
        val releaseSeconds: Double, val tonicMidi: Int, val tonicBedMidi: Int? = null)
    private const val COMPARISON_GAP_SECONDS = .25
    private const val INSTRUMENT_ZIP = "Bellows-phone-mpc-m2.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.none { it.startsWith("--") }) { "BELLOWS audition accepts an output directory only" }
        val root = File(args.firstOrNull() ?: "../testkit/bellows-audition").also { it.mkdirs() }
        File(root, "wav").mkdirs()
        val specs = library() + musicalDemos() + bodyBreathComparisons() + firstListen() + comparisons()
        val clips = ArrayList<JsonValue>()
        val patches = ArrayList<JsonValue>()
        val firstListenFiles = ArrayList<File>()
        val bodyBreathFiles = ArrayList<File>()
        val libraryFiles = ArrayList<File>()
        for ((index, spec) in specs.withIndex()) {
            if (spec.performance != null) {
                val (clip, patch) = renderMusicalClip(spec, root)
                clips += clip
                patches += patch
                println("BELLOWS ${index + 1}/${specs.size}: ${spec.id}-m${spec.patch.model} (musical ${spec.performance.kind})")
                continue
            }
            val p = spec.patch
            val id = "${spec.id}-m${p.model}"
            val rawSource = Bellows.renderRaw(p.voice, p.macros, p.midi, p.velocity, p.gesture, p.model)
            val nativeSource = p.render()
            val secondRaw = spec.joinedWith?.let { Bellows.renderRaw(it.voice, it.macros, it.midi, it.velocity, it.gesture, it.model) }
            val secondNative = spec.joinedWith?.render()
            require(rawSource.sampleRate == 44_100 && nativeSource.sampleRate == 44_100) { "${spec.id}: wrong sample rate" }
            require(rawSource.channels == 1 && nativeSource.channels == 1) { "${spec.id}: wrong channel count" }
            require(rawSource.samples.all { it.isFinite() && abs(it) <= 1f }) { "${spec.id}: unsafe raw PCM" }
            require(nativeSource.samples.all { it.isFinite() && abs(it) <= 1f }) { "${spec.id}: unsafe native PCM" }
            require(secondRaw?.samples?.all { it.isFinite() && abs(it) <= 1f } != false) { "${spec.id}: unsafe second raw PCM" }
            require(secondNative?.samples?.all { it.isFinite() && abs(it) <= 1f } != false) { "${spec.id}: unsafe second native PCM" }
            val held = Bellows.isLoop(p.macros.getValue("HOLD"))
            // Four passes give ears time to catch a click or a loop that loses its motion.
            val raw = if (secondRaw != null) join(rawSource, secondRaw) else if (held) repeat(rawSource, 4) else rawSource
            val native = if (secondNative != null) join(nativeSource, secondNative) else if (held) repeat(nativeSource, 4) else nativeSource
            // Each A/B note reaches the same shared loudest-window target before concatenation.
            val matched = if (secondNative != null) join(AuditionLevel.level(nativeSource), AuditionLevel.level(secondNative)) else AuditionLevel.level(native)
            val midi = p.midi + (p.macros.getValue("TUNE") * Bellows.TUNE_SEMITONES).roundToInt()
            val sampler = SmplChunk(midi,
                if (held) SmplChunk.Loop(nativeSource.frameCount.toLong(), native.frameCount.toLong()) else null)
            val file = "wav/$id.wav"
            val rawFile = "wav/$id-raw.wav"
            WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16, smpl = sampler)
            WavWriter.write(File(root, rawFile), raw, WavWriter.BitDepth.PCM_16, smpl = sampler)
            val decoded = WavReader.read(File(root, file))
            require(decoded.samples.all { it.isFinite() && abs(it) <= 1f }) { "${spec.id}: exported PCM failed" }
            val metrics = linkedMapOf<String, JsonValue>(
                "peak" to num(native.peak()), "rms" to num(BellowsTestAudio.rms(native.samples)),
                "loudest200ms" to num(Loudness.of(native)), "rawPeak" to num(raw.peak()),
                "rawRms" to num(BellowsTestAudio.rms(raw.samples)), "matchedPeak" to num(decoded.peak()),
                "matchedLoudest200ms" to num(Loudness.of(decoded)), "finite" to JsonValue.Bool(true),
                "sourceDurationSeconds" to num((if (secondNative != null) native.frameCount else nativeSource.frameCount).toDouble() / 44_100),
            )
            if (held) {
                val quantizedLoop = decoded.samples.copyOfRange(0, nativeSource.frameCount)
                val wrap = BellowsTestAudio.wrap(quantizedLoop)
                metrics["wrapStepRatio"] = num(wrap.first)
                metrics["wrapKinkRatio"] = num(wrap.second)
                require(wrap.first <= 1.5 && wrap.second <= 1.5) { "${spec.id}: PCM16 loop does not close ($wrap)" }
            }
            if (spec.section == "pitch") {
                val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val hz = FineTuning.measuredHz(nativeSource, expected.toFloat(), .35f, .3f)
                metrics["measuredHz"] = num(hz)
                metrics["pitchCents"] = num(FineTuning.cents(hz, expected))
            }
            val pressure = (listOf(p) + listOfNotNull(spec.joinedWith)).flatMap { source ->
                (0..400).map { Bellows.pressureAt(source.voice, source.macros, it / 400.0, source.velocity, source.gesture, held) }
            }
            metrics["pressureMin"] = num(pressure.min())
            metrics["pressureMax"] = num(pressure.max())
            val clipFields = linkedMapOf<String, JsonValue>(
                "id" to str(id), "title" to str(spec.title), "voice" to str(p.voice.name),
                "section" to str(spec.section), "description" to str(spec.description),
                "file" to str(file), "rawFile" to str(rawFile),
                "durationSeconds" to num(decoded.frameCount.toDouble() / 44_100),
                "midi" to num(midi), "velocity" to num(p.velocity), "gesture" to str(p.gesture.name),
                "model" to num(p.model), "loop" to JsonValue.Bool(held), "loopable" to JsonValue.Bool(held),
                "loopStartFrame" to num(if (held) nativeSource.frameCount else 0),
                "macros" to JsonValue.Obj(LinkedHashMap(p.macros.mapValues { num(it.value) })),
                "metrics" to JsonValue.Obj(metrics),
            )
            spec.role?.let { clipFields["role"] = str(it) }
            spec.comparison?.let { comparison ->
                val fields = linkedMapOf<String, JsonValue>(
                    "parameter" to str(comparison.parameter), "endpoint" to str(comparison.endpoint),
                    "value" to (comparison.value?.let { num(it) } ?: JsonValue.Null), "pairId" to str(comparison.pairId),
                    "lowLabel" to str(comparison.lowLabel), "highLabel" to str(comparison.highLabel),
                )
                val lowSpectrum = BellowsTestAudio.spectrum(decoded.samples.copyOfRange(0, nativeSource.frameCount), Keys.midiHz(midi).toDouble())
                if (secondNative != null) {
                    val secondStart = nativeSource.frameCount + (COMPARISON_GAP_SECONDS * 44_100).roundToInt()
                    val highSpectrum = BellowsTestAudio.spectrum(decoded.samples.copyOfRange(secondStart, decoded.samples.size), Keys.midiHz(midi).toDouble())
                    metrics["lowSpectralCentroidHz"] = num(lowSpectrum.centroidHz)
                    metrics["highSpectralCentroidHz"] = num(highSpectrum.centroidHz)
                    metrics["comparisonHarmonicLogDistanceDb"] = num(BellowsTestAudio.logDistance(lowSpectrum, highSpectrum))
                    val firstSeconds = nativeSource.frameCount.toDouble() / 44_100
                    fields["segments"] = JsonValue.Arr(listOf(
                        JsonValue.Obj(linkedMapOf("label" to str("A · ${comparison.lowLabel}"), "startSeconds" to num(0),
                            "durationSeconds" to num(firstSeconds), "value" to num(0))),
                        JsonValue.Obj(linkedMapOf("label" to str("B · ${comparison.highLabel}"),
                            "startSeconds" to num(firstSeconds + COMPARISON_GAP_SECONDS),
                            "durationSeconds" to num(secondNative.frameCount.toDouble() / 44_100), "value" to num(1))),
                    ))
                    fields["patches"] = JsonValue.Arr(listOf(p.toJsonValue(), requireNotNull(spec.joinedWith).toJsonValue()))
                    clipFields["pairSegments"] = fields.getValue("segments")
                } else metrics["matchedSpectralCentroidHz"] = num(lowSpectrum.centroidHz)
                clipFields["comparison"] = JsonValue.Obj(fields)
            }
            clips += JsonValue.Obj(clipFields)
            val patchFields = linkedMapOf<String, JsonValue>("id" to str(id), "patch" to p.toJsonValue())
            spec.joinedWith?.let { patchFields["joinedWith"] = it.toJsonValue() }
            patches += JsonValue.Obj(patchFields)
            if (spec.section == "first-listen") {
                firstListenFiles += File(root, file)
                firstListenFiles += File(root, rawFile)
            }
            if (spec.section == "body-breath") bodyBreathFiles += listOf(File(root, file), File(root, rawFile))
            if (spec.section == "library") libraryFiles += listOf(File(root, file), File(root, rawFile))
            println("BELLOWS ${index + 1}/${specs.size}: $id (${String.format(Locale.ROOT, "%.2f", decoded.frameCount.toDouble() / 44_100)} s)")
        }
        val patchFile = File(root, "patches-m${Bellows.MODEL_VERSION}.json")
        patchFile.writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "engine" to str("BELLOWS"), "model" to num(Bellows.MODEL_VERSION), "patches" to JsonValue.Arr(patches),
        ))))
        patchFile.copyTo(File(root, "patches.json"), overwrite = true)
        val manifestFields = linkedMapOf(
            "engine" to str("BELLOWS"), "title" to str("Bellows — play the library"),
            "model" to num(Bellows.MODEL_VERSION), "sampleRate" to num(44_100), "bitDepth" to num(16),
            "rawSourceHeadroom" to num(Bellows.RAW_SOURCE_HEADROOM),
            "description" to str("Twenty-four original dry presets, four phrases and four held chords, plus the body/breath and control studies. Musical demos play native key-note attacks, sustain their real loop markers and apply a note-off release; their unmatched files preserve within-performance touch. Preset and control raw files preserve source dynamics."),
            "firstListenZip" to str("Bellows-first-listen-m${Bellows.MODEL_VERSION}.zip"),
            "libraryZip" to str("Bellows-library-m${Bellows.MODEL_VERSION}.zip"),
            "bodyBreathZip" to str("Bellows-body-breath-m${Bellows.MODEL_VERSION}.zip"), "patches" to str(patchFile.name),
            "clips" to JsonValue.Arr(clips),
        )
        val instrumentDownloads = File(root, "instrument-downloads.json")
        if (instrumentDownloads.isFile) manifestFields["instrumentDownloads"] = Json.parse(instrumentDownloads.readText())
        else if (File(root, INSTRUMENT_ZIP).isFile && File(root, INSTRUMENT_ZIP).length() <= 25L * 1024 * 1024)
            manifestFields["instrumentZip"] = str(INSTRUMENT_ZIP)
        val manifest = JsonValue.Obj(manifestFields)
        File(root, "manifest.json").writeText(Json.write(manifest))
        val firstListenManifest = File(root, "first-listen-manifest.json").also {
            it.writeText(Json.write(JsonValue.Obj(LinkedHashMap(manifest.entries).also { fields ->
                fields["clips"] = JsonValue.Arr(clips.filter { it.obj()["section"]?.str() == "first-listen" })
            })))
        }
        val focusedManifest = File(root, "body-breath-manifest.json").also {
            it.writeText(Json.write(JsonValue.Obj(LinkedHashMap(manifest.entries).also { fields ->
                fields["clips"] = JsonValue.Arr(clips.filter { it.obj()["section"]?.str() == "body-breath" })
            })))
        }
        val libraryManifest = File(root, "library-manifest.json").also {
            it.writeText(Json.write(JsonValue.Obj(LinkedHashMap(manifest.entries).also { fields ->
                fields["clips"] = JsonValue.Arr(clips.filter { it.obj()["section"]?.str() == "library" })
            })))
        }
        File(root, "README.txt").writeText("""
            BELLOWS MODEL ${Bellows.MODEL_VERSION} — PLAY THE LIBRARY
            The full audition contains 24 original dry presets, four family phrases and four chord demos.
            Each ZIP includes the selection in its accompanying manifest plus the full recipe list.
            WAV: mono, 44.1 kHz, PCM16. Matched uses AuditionLevel.level, the shared repository listening rule.
            Raw preserves source excitation dynamics before the engine's output leveling.
            Body & breath compares true 0/1 endpoints and 0/.25/.5/.75/1 sweeps with STEADY gesture,
            MOTION/BEAT/DRONE/BUZZ at zero and the other controls held at voice defaults.
            Joined files play matched A (zero), a 250 ms gap, then matched B (full).
            BODY zero bypasses enclosure coloring; PRESSURE zero means gentle excitation, not silence.
            No external rack effects or external sound source was applied. Nonlinearity inside a voice is part of its source model.
            ${patchFile.name} records every audition recipe, including MIDI base, velocity, gesture and model.
            The manifest's MIDI field is the sounded root after TUNE. The patch's MIDI field is its saved base.
            Held clips play four source periods; WAV smpl metadata carries root and loop frames.
            Musical demos use BellowsKeys.note: its genuine note attack, the settled sustain loop,
            the saved gesture and velocity, and a linear note-off release. At least one 6.25-second
            held note in every musical demo crosses the source's loop boundary. No extra velocity
            multiplier is applied: velocity timbre and level are already present in key-note PCM.
            Musical raw files are the native mixed performance before whole-clip audition matching;
            matching uses a single gain for that entire performance, preserving its internal touch.
            WHEEL melodies disable the tracking drone. The wheel chord adds a separate fixed C3
            tonic bed once; that bed does not follow the chord's independently played melody notes.
            WHEEL's tracking octave/fifth bed is a rendered source component. When a sampler repitches the whole WAV,
            that bed follows it. These examples do not implement a separate global drone or live bellows input.
            Reproduce: ./gradlew :synth:generateBellowsAudition
        """.trimIndent() + "\n")
        writeZip(root, "Bellows-first-listen-m${Bellows.MODEL_VERSION}.zip",
            firstListenFiles + listOf(patchFile, firstListenManifest, File(root, "README.txt")))
        writeZip(root, "Bellows-body-breath-m${Bellows.MODEL_VERSION}.zip",
            bodyBreathFiles + listOf(patchFile, focusedManifest, File(root, "README.txt")))
        writeZip(root, "Bellows-library-m${Bellows.MODEL_VERSION}.zip",
            libraryFiles + listOf(patchFile, libraryManifest, File(root, "README.txt")))
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val resource = BellowsAuditionGenerator::class.java.getResourceAsStream("/bellows-audition/$asset")
                ?: error("missing BELLOWS audition resource /bellows-audition/$asset")
            resource.use { input -> File(root, asset).outputStream().use { input.copyTo(it) } }
        }
        println("BELLOWS audition: ${clips.size} clips, ${clips.size * 2} WAVs, ${root.absolutePath}/manifest.json")
    }

    private val libraryRoles = mapOf(
        "Amber Breath" to "Warm swell", "Silver Ranks" to "Bright reed ranks",
        "Midnight Pull" to "Dark pull", "Forward Push" to "Bright accent",
        "Village Stab" to "Short folk accent", "Dancing Reeds" to "Pulsing paired reeds",
        "Quiet Wheel" to "Gentle tracking bed", "Wrist Sparks" to "Pulsing bridge buzz",
        "Bare Reed" to "Direct soft reed", "Chest Tone" to "Deep enclosure",
        "Reed Tap" to "Concise accent", "Slow Bloom" to "Long swell",
        "Paper Reed" to "Soft pull", "Round Push" to "Enclosed push",
        "Reed Knock" to "Concise accent", "Reed Tread" to "Pressure pulse",
        "Soft Grain" to "Quiet rounded reed", "Wooden Chest" to "Enclosed folk tone",
        "Reed Reply" to "Open accent", "Harvest Air" to "Long paired swell",
        "Solo Thread" to "Clean melody", "Hollow Bow" to "Enclosed wheel tone",
        "Fifth Breath" to "Long tracking bed", "Bridge Tap" to "Short buzz accent",
    )

    private fun library(): List<Clip> = BellowsPresets.all().map { patch ->
        val role = libraryRoles.getValue(patch.name)
        Clip("library-${slug(patch.name)}", patch.name, "library",
            "$role. One dry C4 note with this preset's saved pressure gesture and finite HOLD.", patch, role = role)
    }

    private fun musicalDemos(): List<Clip> = buildList {
        for (voice in BellowsVoice.entries) {
            val patch = BellowsPresets.forVoice(voice).first()
            val melody = listOf(60, 63, 67, 65, 63, 60, 58, 60).mapIndexed { i, midi ->
                PlayedNote(midi, i * .5, .38, listOf(.35f, .65f, 1f, .65f)[i % 4])
            } + PlayedNote(60, 4.5, 10.0, .65f)
            add(Clip("phrase-${slug(voice.name)}", "${display(voice.name)} · a little air", "musical",
                "A C-minor phrase at 96 BPM, from soft to firm touch, then a long C4 hold and release. " +
                    if (voice == BellowsVoice.WHEEL) "Melody only; the tracking drone is off." else
                        "Listen for the attack, the changing touch and the settled sustain.",
                patch, role = "Phrase · C minor · 96 BPM",
                performance = Performance("phrase", 96, melody, .6, 60)))
            val chord = listOf(60, 63, 67, 70).mapIndexed { i, midi ->
                PlayedNote(midi, 0.0, 10.0, listOf(.65f, .55f, .8f, .45f)[i])
            } + listOf(60, 65, 67).map { midi -> PlayedNote(midi, 12.0, 3.0, .65f) }
            add(Clip("chord-${slug(voice.name)}", "${display(voice.name)} · room to hold", "musical",
                "Cm7, a breath, then Csus4 at 96 BPM. The first chord holds for 6.25 seconds and crosses the loop seam. " +
                    if (voice == BellowsVoice.WHEEL) "One separate C3 tonic bed stays fixed beneath both chords." else
                        "Each note keeps its own attack, touch and release; no external effects.",
                patch, role = "Chords · Cm7 / Csus4",
                performance = Performance("chord", 96, chord, .6, 60,
                    if (voice == BellowsVoice.WHEEL) 48 else null)))
        }
    }

    /** Plays the same [start,end) loop and linear note-off fade as the keygroup hosts. */
    private fun renderMusicalClip(spec: Clip, root: File): Pair<JsonValue, JsonValue> {
        val p = spec.patch
        val performance = requireNotNull(spec.performance)
        val beatSeconds = 60.0 / performance.bpm
        val gateEnd = performance.notes.maxOf { (it.startBeat + it.holdBeats) * beatSeconds }
        val samples = FloatArray(((gateEnd + performance.releaseSeconds + .1) * 44_100).roundToInt())
        val macros = p.macros + mapOf("TUNE" to 0f, "HOLD" to 1f) +
            if (p.voice == BellowsVoice.WHEEL) mapOf("DRONE" to 0f) else emptyMap()
        val cache = HashMap<Pair<Int, Float>, KeyNote>()
        var loopCrossings = 0
        fun mix(note: KeyNote, startSeconds: Double, holdSeconds: Double, bedGain: Float = 1f) {
            val source = note.snip.samples
            val loopStart = note.loopStartFrame.toInt()
            require(loopStart > 0 && loopStart < source.size)
            val loopLength = source.size - loopStart
            val start = (startSeconds * 44_100).roundToInt()
            val gate = (holdSeconds * 44_100).roundToInt()
            val release = (performance.releaseSeconds * 44_100).roundToInt()
            if (gate > source.size) loopCrossings++
            for (frame in 0 until gate + release) {
                val index = if (frame < source.size) frame else loopStart + (frame - loopStart) % loopLength
                val envelope = if (frame < gate) 1f else (1f - (frame - gate + 1f) / release).coerceAtLeast(0f)
                samples[start + frame] += source[index] * envelope * .35f * bedGain
            }
        }
        for (event in performance.notes) {
            val note = cache.getOrPut(event.midi to event.velocity) {
                BellowsKeys.note(p.voice, macros, event.midi, event.velocity, p.gesture, p.model)
            }
            mix(note, event.startBeat * beatSeconds, event.holdBeats * beatSeconds)
        }
        performance.tonicBedMidi?.let { tonic ->
            mix(BellowsKeys.tonicBed(tonic, p.macros + ("BUZZ" to 0f), .65f,
                BellowsGesture.STEADY, p.model), 0.0, gateEnd, .45f)
        }
        require(loopCrossings > 0) { "${spec.id}: the musical demo never crossed its sustain loop" }
        val native = Snip(samples, 1, 44_100)
        require(native.samples.all { it.isFinite() && abs(it) <= 1f }) { "${spec.id}: unsafe performance mix" }
        val matched = AuditionLevel.level(native)
        val id = "${spec.id}-m${p.model}"
        val file = "wav/$id.wav"
        val rawFile = "wav/$id-raw.wav"
        WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root, rawFile), native, WavWriter.BitDepth.PCM_16)
        val events = JsonValue.Arr(performance.notes.map { note -> JsonValue.Obj(linkedMapOf(
            "midi" to num(note.midi), "startBeat" to num(note.startBeat),
            "holdBeats" to num(note.holdBeats), "velocity" to num(note.velocity),
        )) })
        val playing = JsonValue.Obj(linkedMapOf(
            "kind" to str(performance.kind), "bpm" to num(performance.bpm),
            "releaseSeconds" to num(performance.releaseSeconds), "notes" to events,
            "tonicBedMidi" to (performance.tonicBedMidi?.let { num(it) } ?: JsonValue.Null),
            "voiceGain" to num(.35), "bedGain" to num(.45), "adapter" to str("BellowsKeys.note"),
            "tonicBed" to (performance.tonicBedMidi?.let { tonic -> JsonValue.Obj(linkedMapOf(
                "midi" to num(tonic), "velocity" to num(.65), "gesture" to str("STEADY"),
                "adapter" to str("BellowsKeys.tonicBed"), "relativeGain" to num(.45),
                "macros" to JsonValue.Obj(LinkedHashMap((p.macros + mapOf("TUNE" to 0f,
                    "HOLD" to 1f, "DRONE" to 1f, "BUZZ" to 0f)).mapValues { num(it.value) })),
            )) } ?: JsonValue.Null),
            "macros" to JsonValue.Obj(LinkedHashMap(macros.mapValues { num(it.value) })),
        ))
        val clip = JsonValue.Obj(linkedMapOf(
            "id" to str(id), "title" to str(spec.title), "voice" to str(p.voice.name),
            "section" to str(spec.section), "description" to str(spec.description), "role" to str(spec.role ?: "Musical demo"),
            "file" to str(file), "rawFile" to str(rawFile), "rawKind" to str("native-performance"),
            "durationSeconds" to num(samples.size / 44_100.0), "midi" to num(performance.tonicMidi),
            "velocity" to num(1), "gesture" to str(p.gesture.name), "model" to num(p.model),
            "loop" to JsonValue.Bool(false), "loopable" to JsonValue.Bool(false), "loopStartFrame" to num(0),
            "macros" to JsonValue.Obj(LinkedHashMap(macros.mapValues { num(it.value) })), "performance" to playing,
            "metrics" to JsonValue.Obj(linkedMapOf(
                "peak" to num(native.peak()), "rms" to num(BellowsTestAudio.rms(samples)),
                "rawPeak" to num(native.peak()), "matchedPeak" to num(matched.peak()),
                "loopCrossings" to num(loopCrossings), "finite" to JsonValue.Bool(true),
            )),
        ))
        val recipe = JsonValue.Obj(linkedMapOf("id" to str(id), "patch" to p.toJsonValue(), "performance" to playing))
        return clip to recipe
    }

    private fun firstListen(): List<Clip> = BellowsPresets.all().take(8).mapIndexed { i, p ->
        val explanation = when (p.voice) {
            BellowsVoice.CHAMBER -> "Listen for the reed chest, register blend and gentle reed beating under one breath."
            BellowsVoice.COUNTER -> "Listen for the focused concertina reed color and the direction of the rendered bellows."
            BellowsVoice.FOLK -> "Listen for the brighter paired reeds, wooden body and a breath with some rough edge."
            BellowsVoice.WHEEL -> "Listen for the bowed reed-like line, tracking octave/fifth drone and pressure-gated bridge chatter."
        }
        Clip("preset-${i + 1}-${slug(p.name)}", p.name, "first-listen", explanation, p)
    }

    /** Same phase seed and player contour at every control position; only the named macro changes. */
    private fun bodyBreathComparisons(): List<Clip> {
        val joined = ArrayList<Clip>()
        val steps = ArrayList<Clip>()
        for (voice in BellowsVoice.entries) for (macro in listOf("BODY", "PRESSURE")) {
            val parameter = slug(macro)
            val pairId = "${slug(voice.name)}-$parameter-m${Bellows.MODEL_VERSION}"
            val lowLabel = if (macro == "BODY") "No body" else "Gentle breath"
            val highLabel = if (macro == "BODY") "Full body" else "Full breath"
            val base = BellowsPatch("${display(voice.name)} ${if (macro == "BODY") "body" else "breath"}", voice,
                BellowsTestAudio.diagnosticMacros(voice), gesture = BellowsGesture.STEADY)
            fun patch(value: Float) = base.copy(macros = base.macros + (macro to value))
            joined += Clip("${slug(voice.name)}-$parameter-ab", "${display(voice.name)} · $lowLabel → $highLabel",
                "body-breath", "A plays $lowLabel at ${macro}=0, then a 250 ms gap, then B plays $highLabel at ${macro}=1. " +
                    "Each note uses the same matched loudness and fixed player gesture; compare the tone, not its volume.",
                patch(0f), Comparison(parameter, "joined", null, pairId, lowLabel, highLabel), patch(1f))
            for (value in listOf(0f, .25f, .5f, .75f, 1f)) {
                val percent = (value * 100).roundToInt()
                val label = when (value) {
                    0f -> lowLabel
                    1f -> highLabel
                    else -> "${if (macro == "BODY") "Body" else "Breath"} $percent%"
                }
                steps += Clip("${slug(voice.name)}-$parameter-step-$percent", "${display(voice.name)} · $label",
                    "body-breath", "${macro}=$value. STEADY player, no motion, reed beat, drone or bridge buzz; all other controls stay at the voice's defaults. " +
                        if (macro == "PRESSURE" && value == 0f) "Zero is gentle positive excitation, so the reed can still be compared at matched loudness." else
                            "Compare the source spectrum at the same listening level.",
                    patch(value), Comparison(parameter, if (value == 0f) "low" else if (value == 1f) "high" else "step",
                        value, pairId, lowLabel, highLabel))
            }
        }
        return joined + steps
    }

    private fun comparisons(): List<Clip> = buildList {
        fun base(voice: BellowsVoice) = BellowsPatch("Audition $voice", voice,
            Bellows.defaults(voice) + mapOf("HOLD" to .45f, "TUNE" to .5f))
        fun addPair(voice: BellowsVoice, macro: String, low: Float, high: Float, quiet: String, strong: String,
                    lowHear: String, highHear: String) {
            for ((value, name, hear) in listOf(Triple(low, quiet, lowHear), Triple(high, strong, highHear))) {
                val p = base(voice).copy(macros = base(voice).macros + (macro to value))
                add(Clip("${slug(voice.name)}-${slug(macro)}-${slug(name)}", "${display(voice.name)} · $name", slug(macro), hear, p))
            }
        }
        for (voice in BellowsVoice.entries) {
            addPair(voice, "MOTION", 0f, .85f, "Still motion", "Moving breath",
                "Listen to the still source before adding periodic breath or wrist movement.",
                "Listen for motion within the sustained note; its rate is independent of the TUNE pitch.")
        }
        for (voice in listOf(BellowsVoice.CHAMBER, BellowsVoice.COUNTER, BellowsVoice.FOLK)) {
            addPair(voice, "REGISTER", .08f, .92f, "Low register", "Open register",
                "Listen for the lower register blend while the melodic fundamental stays fixed.",
                "Listen for a different reed rank blend, rather than a transposed keyboard note.")
        }
        for (voice in listOf(BellowsVoice.CHAMBER, BellowsVoice.FOLK)) {
            addPair(voice, "BEAT", 0f, .85f, "Unison reeds", "Beating reeds",
                "The paired reeds are close to unison here. Compare their steady center with the wet setting.",
                "Listen for the beating between paired reeds, independent of a recorded tremolo treatment.")
        }
        addPair(BellowsVoice.COUNTER, "DIRECTION", 0f, 1f, "Pull bellows", "Push bellows",
            "Hear the pull-side reed spectrum at the same requested MIDI root.",
            "Hear the push-side reed spectrum. This is a saved rendered gesture, not live MIDI pressure.")
        addPair(BellowsVoice.WHEEL, "DRONE", 0f, .8f, "Melody alone", "Octave and fifth",
            "The melodic source alone makes the drone's contribution easy to compare.",
            "Listen for the tracking octave/fifth bed under the melody; whole-sample playback will repitch this bed too.")
        addPair(BellowsVoice.WHEEL, "BUZZ", 0f, .9f, "Quiet bridge", "Buzz bridge",
            "Listen to the wheel source without the pressure-sensitive bridge chatter.",
            "Listen for bridge chatter above the smooth pressure threshold. The pulse gesture opens and closes this source contribution.")
        for ((id, title, pressure, motion, gesture) in listOf(
            Threshold("below", "Below threshold", .2f, 0f, BellowsGesture.STEADY),
            Threshold("above", "Above threshold", .95f, 0f, BellowsGesture.STEADY),
            Threshold("crossing", "Crossing threshold", .84f, .66f, BellowsGesture.PULSE),
        )) {
            val p = base(BellowsVoice.WHEEL).copy(gesture = gesture,
                macros = base(BellowsVoice.WHEEL).macros + mapOf("PRESSURE" to pressure, "MOTION" to motion, "BUZZ" to 1f, "DRONE" to 0f))
            add(Clip("threshold-$id", "Wheel · $title", "threshold",
                when (id) {
                    "below" -> "Full BUZZ below the bridge's pressure threshold: its chatter contribution is exactly silent."
                    "above" -> "Full BUZZ above threshold with steady pressure: hear the continuous source contribution."
                    else -> "The pulse crosses the smooth pressure threshold repeatedly; hear the bridge contribution enter and leave."
                }, p))
        }
        for (gesture in BellowsGesture.entries) {
            val p = base(BellowsVoice.CHAMBER).copy(gesture = gesture)
            add(Clip("gesture-${slug(gesture.name)}", "Chamber · ${display(gesture.name)}", "gestures",
                "The same source and controls with a ${gesture.name.lowercase(Locale.ROOT)} rendered pressure contour. Listen to the arrival and breath shape.", p))
        }
        for ((midi, title) in listOf(48 to "C3", 60 to "C4", 72 to "C5")) {
            val p = base(BellowsVoice.CHAMBER).copy(midi = midi, gesture = BellowsGesture.STEADY,
                macros = base(BellowsVoice.CHAMBER).macros + mapOf("TUNE" to 0f, "MOTION" to 0f, "BEAT" to 0f))
            add(Clip("pitch-${title.lowercase(Locale.ROOT)}", "Chamber · $title", "pitch",
                "Exact MIDI $midi, with still motion and unison reeds. Compare register and verify the root by ear.", p))
        }
        for ((velocity, title) in listOf(.25f to "Soft", .6f to "Medium", 1f to "Firm")) {
            val p = base(BellowsVoice.FOLK).copy(velocity = velocity, gesture = BellowsGesture.STEADY)
            add(Clip("velocity-${slug(title)}", "Folk · $title", "velocity",
                "Matched files expose the change in reed spectrum; raw files retain how $title excitation changes level.", p))
        }
        for (voice in BellowsVoice.entries) {
            val p = base(voice).copy(macros = base(voice).macros + ("HOLD" to 1f))
            add(Clip("held-${slug(voice.name)}", "${display(voice.name)} · held loop", "held",
                "Four passes of the engine's settled loop. Listen through the joins and check that motion stays inside the loop.", p))
        }
    }

    private data class Threshold(val id: String, val title: String, val pressure: Float, val motion: Float,
        val gesture: BellowsGesture)

    private fun join(first: Snip, second: Snip): Snip {
        require(first.sampleRate == second.sampleRate && first.channels == second.channels)
        val gap = (COMPARISON_GAP_SECONDS * first.sampleRate).roundToInt() * first.channels
        val samples = FloatArray(first.samples.size + gap + second.samples.size)
        first.samples.copyInto(samples)
        second.samples.copyInto(samples, first.samples.size + gap)
        return Snip(samples, first.channels, first.sampleRate)
    }

    private fun writeZip(root: File, name: String, files: List<File>) {
        ZipOutputStream(File(root, name).outputStream().buffered()).use { zip ->
            for (file in files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0 }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun repeat(snip: Snip, count: Int): Snip = Snip(FloatArray(snip.samples.size * count) {
        snip.samples[it % snip.samples.size]
    }, snip.channels, snip.sampleRate)
    private fun slug(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun display(value: String) = value.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number): JsonValue.Num = JsonValue.Num(value.toDouble())
}
