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

/** The selected ensemble recipe beside the original two direction-study references. */
object RegistersDepthDirectionsAuditionGenerator {
    private const val MODEL = 6
    private const val REPEATS = 3
    private const val REVISION = "depth-directions-2"
    private val voice = RegistersVoice.TRANSISTOR
    private data class Direction(val id: String, val label: String, val description: String,
        val changes: Map<String, Float>)
    private val directions = listOf(
        Direction("baseline", "Current Compact Keys", "The current model-6 Compact Keys recipe.", emptyMap()),
        Direction("body", "Warmer body", "Fewer upper ranks and less saturation, retaining the same root note, SPREAD and MOTION.",
            mapOf("COLOR" to .08f, "EDGE" to .10f)),
        Direction("ensemble", "Rich Ensemble", "The chosen setting: more side-bank movement and vibrato, retaining the current registration and saturation.",
            mapOf("SPREAD" to .65f, "MOTION" to .32f)),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply one optional output directory." }
        val root = File(args.firstOrNull() ?: "../testkit/registers-depth-directions").canonicalFile
        check(root.mkdirs() || root.isDirectory)
        val base = RegistersPresets.forVoice(voice).single { it.name == "Compact Keys" }.copy(model = MODEL)
        val preferred = RegistersPresets.forVoice(voice).single { it.name == "Rich Ensemble" }
        check(preferred.model == MODEL && preferred.midi == base.midi && preferred.velocity == base.velocity &&
            preferred.macros == base.macros + directions.single { it.id == "ensemble" }.changes) {
            "The named preset must retain the exact ensemble setting selected in the first direction study."
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
            "synth/src/test/resources/audition/registers-depth-directions/index.html",
            "synth/src/test/resources/audition/registers-depth-directions/app.js",
            "synth/src/test/resources/audition/registers-depth-directions/styles.css")
        val fingerprints = sourcePaths.map { path ->
            val source = File(repository, path); val target = File(root, "source/$path")
            target.parentFile.mkdirs(); source.copyTo(target, overwrite = true)
            mapOf("path" to path, "sha256" to sha256(target))
        }
        val audioHash = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
            val hashes = it.getValue("sha256") as Map<*, *>
            "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
        }.toByteArray())
        writeJson(File(root, "manifest.json"), mapOf("revision" to REVISION, "model" to MODEL, "sampleRate" to Dsp.RATE,
            "title" to "TRANSISTOR Rich Ensemble", "status" to "Owner selected the richer ensemble direction",
            "voice" to voice.name, "clipCount" to clips.size, "wavCount" to clips.size * 2,
            "audioHash" to audioHash, "factoryChanged" to true, "engineChanged" to false,
            "preferredDirection" to "ensemble", "preferredPreset" to preferred.name,
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to fingerprints, "clips" to clips, "groups" to groups,
            "directions" to directions.map { mapOf("id" to it.id, "label" to it.label,
                "description" to it.description, "macroChanges" to it.changes) },
            "metricNotes" to "All recipes use model 6, MIDI 48/60/72 and velocity 1. The owner preferred the richer ensemble direction, now also the Rich Ensemble factory preset. This adds a preset without changing engine arithmetic or existing settings. Actual Keys.registersPad attack and handoff lead into three full copies of its final held loop. Only the final 4 ms fade is audition-specific. Independent adjacent synthesis cycles prove recurrence before the playback repeats are copied. Matched files target the loudest 200 ms RMS of 0.03; loudness matching does not establish a listening verdict. Body removes upper ranks and drive; it adds no lower octave. Ensemble increases periodic side-bank motion and common vibrato. The accepted R5/R6 page remains available. Physical instrument listening is still pending."))
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val stream = javaClass.getResourceAsStream("/audition/registers-depth-directions/$asset")
                ?: error("Missing directions UI asset: $asset")
            File(root, asset).outputStream().use { output -> stream.use { it.copyTo(output) } }
        }
        File(root, "README.txt").writeText("""
            TRANSISTOR depth directions: three current-model recipes at C3, C4 and C5.
            Rich Ensemble is now a named factory preset after the owner preferred that
            direction. Existing settings and engine arithmetic are unchanged.
            Compare matched playback to remove level as a deciding factor.
            Current: Compact Keys. Body: COLOR .08 / EDGE .10. Ensemble: SPREAD .65 / MOTION .32.
            Remaining controls are unchanged. Body adds no lower octave or room effect.
            The named Rich Ensemble preset is checked against these exact ensemble macros.
            Audio is actual Keys.registersPad, through the loop marker plus three full held
            cycles, with a final 4 ms audition-only fade. It must play once in full, not loop
            from frame zero. Independent recurrence measurements are under instrumentLoop.
            Regenerate: ./gradlew :synth:generateRegistersDepthDirectionsAudition -PregistersOutput=<directory>
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
