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
        val joinedWith: BellowsPatch? = null)
    private data class Comparison(val parameter: String, val endpoint: String, val value: Float?,
        val pairId: String, val lowLabel: String, val highLabel: String)
    private const val COMPARISON_GAP_SECONDS = .25

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.none { it.startsWith("--") }) { "BELLOWS audition accepts an output directory only" }
        val root = File(args.firstOrNull() ?: "../testkit/bellows-audition").also { it.mkdirs() }
        File(root, "wav").mkdirs()
        val specs = bodyBreathComparisons() + firstListen() + comparisons()
        val clips = ArrayList<JsonValue>()
        val patches = ArrayList<JsonValue>()
        val firstListenFiles = ArrayList<File>()
        val bodyBreathFiles = ArrayList<File>()
        for ((index, spec) in specs.withIndex()) {
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
            println("BELLOWS ${index + 1}/${specs.size}: $id (${String.format(Locale.ROOT, "%.2f", decoded.frameCount.toDouble() / 44_100)} s)")
        }
        val patchFile = File(root, "patches-m${Bellows.MODEL_VERSION}.json")
        patchFile.writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "engine" to str("BELLOWS"), "model" to num(Bellows.MODEL_VERSION), "patches" to JsonValue.Arr(patches),
        ))))
        patchFile.copyTo(File(root, "patches.json"), overwrite = true)
        val manifest = JsonValue.Obj(linkedMapOf(
            "engine" to str("BELLOWS"), "title" to str("Bellows — first listen"),
            "model" to num(Bellows.MODEL_VERSION), "sampleRate" to num(44_100), "bitDepth" to num(16),
            "rawSourceHeadroom" to num(Bellows.RAW_SOURCE_HEADROOM),
            "description" to str("Four original reed-and-wheel voices, dry from the SnipSnap Kotlin engine. Matched clips share the repository's audition loudness; raw clips preserve source dynamics. Gestures are rendered performances, and held examples repeat a settled source loop four times."),
            "firstListenZip" to str("Bellows-first-listen-m${Bellows.MODEL_VERSION}.zip"),
            "bodyBreathZip" to str("Bellows-body-breath-m${Bellows.MODEL_VERSION}.zip"), "patches" to str(patchFile.name),
            "clips" to JsonValue.Arr(clips),
        ))
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
        File(root, "README.txt").writeText("""
            BELLOWS MODEL ${Bellows.MODEL_VERSION} — FIRST LISTEN
            Eight original dry presets from SnipSnap, plus their raw source renders.
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
            WHEEL's tracking octave/fifth bed is a rendered source component. When a sampler repitches the whole WAV,
            that bed follows it. These examples do not implement a separate global drone or live bellows input.
            Reproduce: ./gradlew :synth:generateBellowsAudition
        """.trimIndent() + "\n")
        writeZip(root, "Bellows-first-listen-m${Bellows.MODEL_VERSION}.zip",
            firstListenFiles + listOf(patchFile, firstListenManifest, File(root, "README.txt")))
        writeZip(root, "Bellows-body-breath-m${Bellows.MODEL_VERSION}.zip",
            bodyBreathFiles + listOf(patchFile, focusedManifest, File(root, "README.txt")))
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val resource = BellowsAuditionGenerator::class.java.getResourceAsStream("/bellows-audition/$asset")
                ?: error("missing BELLOWS audition resource /bellows-audition/$asset")
            resource.use { input -> File(root, asset).outputStream().use { input.copyTo(it) } }
        }
        println("BELLOWS audition: ${clips.size} clips, ${clips.size * 2} WAVs, ${root.absolutePath}/manifest.json")
    }

    private fun firstListen(): List<Clip> = BellowsPresets.all().mapIndexed { i, p ->
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
