package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
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

/** Direction studies share one actual keyboard render and independent recurrence checks. */
object RegistersDepthDirectionsAuditionGenerator {
    private const val MODEL = 6
    private const val REPEATS = 3
    private val voice = RegistersVoice.TRANSISTOR
    private data class Direction(val id: String, val label: String, val description: String,
        val changes: Map<String, Float>)
    private data class Study(val flag: String?, val resourceDirectory: String, val revision: String,
        val title: String, val status: String, val directions: List<Direction>, val initialDirection: String,
        val factoryChanged: Boolean = false, val preferredPreset: String? = null,
        val preferredDirection: String? = null, val metricNotes: String, val instructions: String)
    private val originalDirections = listOf(
        Direction("baseline", "Current Compact Keys", "The current model-6 Compact Keys recipe.", emptyMap()),
        Direction("body", "Warmer body", "Fewer upper ranks and less saturation, retaining the same root note, SPREAD and MOTION.",
            mapOf("COLOR" to .08f, "EDGE" to .10f)),
        Direction("ensemble", "Rich Ensemble", "The chosen setting: more side-bank movement and vibrato, retaining the current registration and saturation.",
            mapOf("SPREAD" to .65f, "MOTION" to .32f)),
    )
    private val refinementDirections = listOf(
        Direction("ensemble", "Rich Ensemble", "The current preset, retained exactly as the comparison reference.",
            mapOf("SPREAD" to .65f, "MOTION" to .32f)),
        Direction("steady", "Less vibrato", "The same ensemble spread, with less common pitch vibrato.",
            mapOf("SPREAD" to .65f, "MOTION" to .08f)),
        Direction("calm", "Calmer ensemble", "Less vibrato and gentler separation between the banks.",
            mapOf("SPREAD" to .40f, "MOTION" to .08f)),
    )
    private val octaveDirections = listOf(
        Direction("calm", "Calmer ensemble", "The published calmer setting, retained exactly as the comparison reference.",
            mapOf("SPREAD" to .40f, "MOTION" to .08f, "COLOR" to .42f)),
        Direction("octave", "Octave Keys", "The selected octave registration at the same calm SPREAD and MOTION settings.",
            mapOf("SPREAD" to .40f, "MOTION" to .08f, "COLOR" to .65f)),
    )
    private val studies = listOf(
        Study(null, "registers-depth-directions", "depth-directions-2", "TRANSISTOR Rich Ensemble",
            "Owner selected the richer ensemble direction", originalDirections, "ensemble", factoryChanged = true,
            preferredPreset = "Rich Ensemble", preferredDirection = "ensemble",
            metricNotes = "All recipes use model 6, MIDI 48/60/72 and velocity 1. The owner preferred the richer ensemble direction, now also the Rich Ensemble factory preset. This adds a preset without changing engine arithmetic or existing settings. Actual Keys.registersPad attack and handoff lead into three full copies of its final held loop. Only the final 4 ms fade is audition-specific. Independent adjacent synthesis cycles prove recurrence before the playback repeats are copied. Matched files target the loudest 200 ms RMS of 0.03; loudness matching does not establish a listening verdict. Body removes upper ranks and drive; it adds no lower octave. Ensemble increases periodic side-bank motion and common vibrato. The accepted R5/R6 page remains available. Physical instrument listening is still pending.",
            instructions = """
                TRANSISTOR depth directions: three current-model recipes at C3, C4 and C5.
                Rich Ensemble is now a named factory preset after the owner preferred that
                direction. Existing settings and engine arithmetic are unchanged.
                Current: Compact Keys. Body: COLOR .08 / EDGE .10. Ensemble: SPREAD .65 / MOTION .32.
                Remaining controls are unchanged. Body adds no lower octave or room effect.
                The named Rich Ensemble preset is checked against these exact ensemble macros.
                Regenerate: ./gradlew :synth:generateRegistersDepthDirectionsAudition -PregistersOutput=<directory>
            """.trimIndent()),
        Study("--refine-ensemble", "registers-ensemble-refinement", "ensemble-refinement-1",
            "TRANSISTOR calmer ensemble comparison", "Calmer candidates awaiting listening", refinementDirections, "calm",
            metricNotes = "All recipes use model 6, MIDI 48/60/72 and velocity 1. The current Rich Ensemble reference is unchanged. Less vibrato retains SPREAD .65 and reduces MOTION from .32 to .08. Calmer ensemble also reduces SPREAD to .40. These are audition-only candidates following the owner's report that Rich Ensemble feels annoying; neither is an accepted preset. Registration, drive, click and all other controls remain fixed. Actual Keys.registersPad attack and handoff lead into three full copies of its final held loop, with only a final 4 ms audition fade. Independent adjacent synthesis cycles prove recurrence before repeats are copied. Matched files target loudest 200 ms RMS .03; measurements do not establish perceived comfort. Factory settings and engine arithmetic do not change.",
            instructions = """
                TRANSISTOR ensemble refinement: three current-model recipes at C3, C4 and C5.
                Current Rich Ensemble: SPREAD .65 / MOTION .32, the unchanged reference.
                Less vibrato: SPREAD .65 / MOTION .08. Calmer ensemble: SPREAD .40 / MOTION .08.
                Remaining controls, pitch and velocity are unchanged. These are audition-only
                candidates; no factory preset or engine arithmetic changes.
                Regenerate: ./gradlew :synth:generateRegistersEnsembleRefinementAudition -PregistersOutput=<directory>
            """.trimIndent()),
        Study("--octave-balance", "registers-octave-balance", "octave-balance-2",
            "TRANSISTOR Octave Keys", "Owner selected More octave; saved as Octave Keys", octaveDirections, "octave",
            factoryChanged = true, preferredPreset = "Octave Keys", preferredDirection = "octave",
            metricNotes = "All recipes use model 6, MIDI 48/60/72 and velocity 1. The owner selected More octave, now the Octave Keys factory preset. Calmer ensemble is the published reference at COLOR .42, SPREAD .40 and MOTION .08. Octave Keys changes only COLOR to .65; all remaining controls, pitch and velocity stay fixed. COLOR raises the 4-foot and 2-foot rank weights, opens their tone filter and slightly reduces the round foundation mix. This is a comparison of the existing COLOR control, not an isolated harmonic-gain experiment. One named preset is added; existing settings and engine arithmetic stay unchanged. Actual Keys.registersPad attack and handoff lead into three full copies of its final held loop, with only a final 4 ms audition fade. Independent adjacent synthesis cycles prove recurrence before repeats are copied. Matched files target loudest 200 ms RMS .03. The owner's audition supplies the preference; physical phone and MPC listening remain pending.",
            instructions = """
                TRANSISTOR octave balance: two current-model recipes at C3, C4 and C5.
                Calmer ensemble: COLOR .42. Octave Keys: COLOR .65.
                Both retain SPREAD .40 / MOTION .08 and all other controls, pitch and velocity.
                COLOR changes octave-rank weights, tone-filter cutoff and foundation mix together.
                The owner selected More octave, now named Octave Keys in the factory roster.
                The exact selected controls are checked against the named preset.
                Existing presets and engine arithmetic are unchanged.
                Regenerate: ./gradlew :synth:generateRegistersOctaveBalanceAudition -PregistersOutput=<directory>
            """.trimIndent()),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val flags = args.filter { it.startsWith("--") }
        val paths = args.filterNot { it.startsWith("--") }
        require(paths.size <= 1 && flags.size <= 1 && flags.all { flag -> studies.any { it.flag == flag } }) {
            "Supply one optional output directory and at most one of --refine-ensemble or --octave-balance."
        }
        val study = studies.single { it.flag == flags.singleOrNull() }
        val resourceDirectory = study.resourceDirectory
        val directions = study.directions
        val root = File(paths.firstOrNull() ?: "../testkit/$resourceDirectory").canonicalFile
        check(root.mkdirs() || root.isDirectory)
        val base = RegistersPresets.forVoice(voice).single { it.name == "Compact Keys" }.copy(model = MODEL)
        val preferred = RegistersPresets.forVoice(voice).single { it.name == "Rich Ensemble" }
        check(preferred.model == MODEL && preferred.midi == base.midi && preferred.velocity == base.velocity &&
            preferred.macros == base.macros + originalDirections.single { it.id == "ensemble" }.changes) {
            "The named preset must retain the exact ensemble setting selected in the first direction study."
        }
        if (study.flag == "--octave-balance") {
            val selected = RegistersPresets.forVoice(voice).single { it.name == "Octave Keys" }
            check(selected.model == MODEL && selected.midi == base.midi && selected.velocity == base.velocity &&
                selected.macros == base.macros + octaveDirections.single { it.id == "octave" }.changes) {
                "Octave Keys must retain the exact More octave setting selected by the owner."
            }
        }
        val clips = listOf(48, 60, 72).flatMap { midi -> directions.map { render(root, base, it, midi) } }
        val groups = listOf(48, 60, 72).map { midi -> mapOf("id" to "note_$midi", "midi" to midi,
            "noteName" to noteName(midi), "clipIds" to directions.map { "${it.id}_$midi" }) }
        val repository = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sourcePaths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Registers.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersTransistor.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/RegistersDepthDirectionsAuditionGenerator.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
            "synth/src/test/resources/audition/$resourceDirectory/index.html",
            "synth/src/test/resources/audition/$resourceDirectory/app.js",
            "synth/src/test/resources/audition/$resourceDirectory/styles.css")
        val fingerprints = sourcePaths.map { path ->
            val source = File(repository, path); val target = File(root, "source/$path")
            target.parentFile.mkdirs(); source.copyTo(target, overwrite = true)
            mapOf("path" to path, "sha256" to sha256(target))
        }
        val audioHash = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
            val hashes = it.getValue("sha256") as Map<*, *>
            "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
        }.toByteArray())
        writeJson(File(root, "manifest.json"), mapOf(
            "revision" to study.revision,
            "model" to MODEL, "sampleRate" to Dsp.RATE,
            "title" to study.title, "status" to study.status,
            "voice" to voice.name, "clipCount" to clips.size, "wavCount" to clips.size * 2,
            "audioHash" to audioHash, "factoryChanged" to study.factoryChanged, "engineChanged" to false,
            "preferredDirection" to study.preferredDirection,
            "preferredPreset" to study.preferredPreset,
            "initialDirection" to study.initialDirection,
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to fingerprints, "clips" to clips, "groups" to groups,
            "directions" to directions.map { mapOf("id" to it.id, "label" to it.label,
                "description" to it.description, "macroChanges" to it.changes) },
            "metricNotes" to study.metricNotes))
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val stream = javaClass.getResourceAsStream("/audition/$resourceDirectory/$asset")
                ?: error("Missing directions UI asset: $asset")
            File(root, asset).outputStream().use { output -> stream.use { it.copyTo(output) } }
        }
        File(root, "README.txt").writeText(study.instructions + "\n\n" + """
            Compare matched playback to remove level as a deciding factor.
            Audio is actual Keys.registersPad, through the loop marker plus three full held
            cycles, with a final 4 ms audition-only fade. It must play once in full, not loop
            from frame zero. Independent recurrence measurements are under instrumentLoop.
            Audio fingerprint: $audioHash
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} clips / ${clips.size * 2} WAVs under $root; audio fingerprint $audioHash")
    }

    private fun render(root: File, base: RegistersPatch, direction: Direction, midi: Int): Map<String, Any?> {
        val id = "${direction.id}_$midi"
        val patch = base.copy(name = direction.label, midi = midi, macros = base.macros + direction.changes)
        val replay = RegistersPatch.fromJsonValue(patch.toJsonValue())
        check(replay == patch)
        val note = Keys.registersPad(patch, midi)
        val duplicate = Keys.registersPad(replay, midi)
        check(note.loopStartFrame == duplicate.loopStartFrame && note.snip.samples.contentEquals(duplicate.snip.samples))
        val held = patch.copy(macros = patch.macros + ("HOLD" to 1f)).probe()
        val previous = requireNotNull(held.previousCycle)
        val cycleError = relativeError(previous, held.samples)
        val seamError = relativeError(previous, held.samples, previous.size - 256)
        check(previous !== held.samples && cycleError == held.cycleError && seamError == held.seamError)
        check(cycleError < 1e-5 && seamError < Keys.MAX_SEAM_ERROR)
        val start = note.loopStartFrame.toInt()
        val frames = note.snip.frameCount - start
        check(start > 0 && frames == held.samples.size)
        val samples = FloatArray(start + REPEATS * frames) { i ->
            if (i < start) note.snip.samples[i] else note.snip.samples[start + (i - start) % frames]
        }
        val fadeFrames = (.004 * Dsp.RATE).roundToInt()
        for (i in 0 until fadeFrames) samples[samples.size - fadeFrames + i] *= 1f - i.toFloat() / (fadeFrames - 1)
        val source = Snip(samples, 1, Dsp.RATE)
        check(samples.all { it.isFinite() } && source.peak() > 1e-6f)
        val exportGain = minOf(1f, .99f / source.peak())
        val raw = Snip(FloatArray(samples.size) { samples[it] * exportGain }, 1, Dsp.RATE)
        val matched = AuditionLevel.level(source)
        val hashes = linkedMapOf<String, String>(); val metrics = linkedMapOf<String, Any>()
        for ((mode, snip) in listOf("raw" to raw, "matched" to matched)) {
            val file = WavWriter.write(File(root, "$mode/$id.wav"), snip, WavWriter.BitDepth.PCM_24)
            val decoded = WavReader.read(file)
            check(decoded.sampleRate == Dsp.RATE && decoded.channels == 1 && decoded.frameCount == snip.frameCount)
            check(decoded.peak() <= .990001f && decoded.samples.indices.all { abs(decoded.samples[it] - snip.samples[it]) <= 1.3e-7f })
            if (mode == "matched") check(abs(Loudness.of(decoded) - .03f) < 1e-5)
            hashes[mode] = sha256(file); metrics[mode] = metrics(decoded)
        }
        val recipePath = "recipes/$id.json"
        val recipeFile = File(root, recipePath)
        writeJson(recipeFile, patch.toJsonValue())
        check(RegistersPatch.fromJsonText(recipeFile.readText()) == patch)
        println("$id: cycle=$cycleError seam=$seamError")
        return linkedMapOf("id" to id, "direction" to direction.id, "label" to direction.label,
            "description" to direction.description, "voice" to voice.name, "model" to MODEL,
            "note" to midi, "noteName" to noteName(midi), "velocity" to patch.velocity,
            "macros" to patch.macros, "macroChanges" to direction.changes,
            "factoryPreset" to "Compact Keys", "raw" to "raw/$id.wav", "matched" to "matched/$id.wav",
            "recipe" to patch.toJsonValue(), "recipeUrl" to recipePath, "recipeSha256" to sha256(recipeFile),
            "frames" to samples.size, "seconds" to samples.size.toDouble() / Dsp.RATE,
            "sha256" to hashes, "metrics" to metrics, "sourceMetrics" to metrics(source),
            "rawExportGain" to exportGain, "renderOrigin" to "Keys.registersPad",
            "recipeRoundtripSamplesExact" to true, "auditionOnlyTerminalFadeFrames" to fadeFrames,
            "instrumentLoop" to mapOf("startFrame" to start, "regionFrames" to frames,
                "regionSeconds" to frames.toDouble() / Dsp.RATE, "playbackRepetitions" to REPEATS,
                "handoffStartSeconds" to .350, "handoffEndSeconds" to .450,
                "cycleError" to cycleError, "seamError" to seamError, "independentPreviousCycle" to true),
            "diagnostics" to mapOf("requestedHz" to held.requestedHz, "effectiveHz" to held.effectiveHz,
                "tuningErrorCents" to 1200.0 * ln(held.effectiveHz / held.requestedHz) / ln(2.0),
                "effectiveSpreadHz" to held.effectiveSpreadHz, "spreadModulationHz" to held.spreadModulationHz,
                "motionHz" to held.motionHz, "rawPeak" to held.rawPeak))
    }

    private fun relativeError(a: FloatArray, b: FloatArray, from: Int = 0): Double {
        var energy = 0.0; var difference = 0.0
        for (i in from until a.size) { val d = a[i] - b[i].toDouble(); difference += d * d; energy += a[i].toDouble() * a[i] }
        return difference / energy.coerceAtLeast(1e-30)
    }
    private fun metrics(snip: Snip): Map<String, Any> {
        val energy = snip.samples.sumOf { it.toDouble() * it }
        return mapOf("peak" to snip.peak(), "rms" to sqrt(energy / snip.frameCount),
            "dcMean" to snip.samples.sumOf { it.toDouble() } / snip.frameCount,
            "loudestWindowRms" to Loudness.of(snip))
    }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null; is JsonValue -> value; is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value); is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json)); else -> error("Unsupported manifest value")
    }
    private fun noteName(midi: Int) = "C${midi / 12 - 1}"
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) result else null
    }.getOrNull()
}
