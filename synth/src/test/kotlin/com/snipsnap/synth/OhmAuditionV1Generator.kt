package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Frozen model-1 first listen: preserves the original 35 dry comparisons and their WAV audio. */
object OhmAuditionV1Generator {
    private const val SEED = 731
    private const val VELOCITY = .8f
    private const val RATE = 44_100
    private const val PHRASE_GAIN = .5f

    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val group: String,
        val changes: Map<String, Float> = emptyMap(),
        val midi: Int = 60,
        val velocity: Float = VELOCITY,
        val gesture: OhmGesture = OhmGesture.STEADY,
        val sweepMacro: String? = null,
        val sweepValue: Float? = null,
    )

    private data class Rendered(
        val spec: Case,
        val raw: Snip,
        val recipe: JsonValue,
        val midi: Int?,
        val events: List<Map<String, Any?>>? = null,
    )

    private fun baseline(): Map<String, Float> = OhmV1.defaults() + mapOf(
        "TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
        "MEDDLING" to 0f, "COMMUNE" to 0f,
    )

    private fun cases(): List<Case> = buildList {
        add(Case("solo", "Solo", "The unaccompanied string: MEDDLING 0, COMMUNE 0.", "identities"))
        add(Case("argument", "Argument", "The same string with independent companions: MEDDLING .9, COMMUNE 0.",
            "identities", mapOf("MEDDLING" to .9f)))
        add(Case("communion", "Communion", "The same companions absorbed into the string: MEDDLING .9, COMMUNE 1.",
            "identities", mapOf("MEDDLING" to .9f, "COMMUNE" to 1f)))
        add(Case("quiet-influence", "Quiet influence", "A restrained absorbed influence: MEDDLING .25, COMMUNE 1.",
            "identities", mapOf("MEDDLING" to .25f, "COMMUNE" to 1f)))
        add(Case("gather", "Gather", "A saved finite gesture brings the companions in and gathers them into the string. This is rendered motion, not a live control recording.",
            "identities", mapOf("MEDDLING" to .9f, "COMMUNE" to 1f), gesture = OhmGesture.GATHER))

        for ((m, meddling) in listOf(0f, .45f, .9f).withIndex()) {
            for ((c, commune) in listOf(0f, .5f, 1f).withIndex()) {
                add(Case("matrix-m$m-c$c", "M ${short(meddling)} / C ${short(commune)}",
                    "MEDDLING ${short(meddling)}, COMMUNE ${short(commune)}. Note, touch, body, length, velocity and seed stay fixed.",
                    "matrix", mapOf("MEDDLING" to meddling, "COMMUNE" to commune)))
            }
        }
        for (macro in listOf("TOUCH", "BODY", "LENGTH")) {
            for ((i, value) in listOf(0f, .5f, 1f).withIndex()) {
                add(Case("${macro.lowercase(Locale.ROOT)}-$i", "$macro / ${short(value)}",
                    "Only $macro changes. MEDDLING .65 and COMMUNE .5 retain both string and companion character.",
                    "controls", mapOf("MEDDLING" to .65f, "COMMUNE" to .5f, macro to value),
                    sweepMacro = macro, sweepValue = value))
            }
        }
        for (midi in listOf(48, 60, 72)) {
            for ((i, velocity) in listOf(.3f, .65f, 1f).withIndex()) {
                add(Case("note-$midi-v$i", "${noteName(midi)} / velocity ${short(velocity)}",
                    "One explicit note and excitation velocity. Raw playback preserves the level response; matched playback helps compare its color.",
                    "register", mapOf("MEDDLING" to .65f, "COMMUNE" to .5f), midi, velocity))
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only an output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/ohm-audition/original").canonicalFile
        require(!root.exists() || root.isDirectory) { "output must be a directory" }
        root.mkdirs()
        val assets = File(root, "assets").also { it.mkdirs() }
        val rendererSources = sourceHashes()
        val rendered = cases().map(::render).toMutableList()
        rendered += renderPhrases()
        require(rendered.size == 35 && rendered.map { it.spec.id }.distinct().size == rendered.size)

        // One fixed headroom gain for the entire raw collection retains relative engine levels.
        // The internal peak is reported separately, so this cannot conceal an unstable source.
        val highestRawPeak = rendered.maxOf { peak(it.raw) }
        val rawExportGain = minOf(1.0, .98 / highestRawPeak.coerceAtLeast(1e-12))
        val clips = rendered.map { writeClip(root, it, rawExportGain) }
        check(sourceHashes() == rendererSources) { "OHM renderer sources changed while generating the audition" }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "OHM", "subtitle" to "SnipSnap · Original model 1",
            "description" to "One string, several opinions. Listen for the change from separate companions to a single altered instrument.",
            "modelVersion" to 1, "renderedAt" to Instant.now().toString(),
            "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "rendererSources" to rendererSources,
            "renderSource" to "OhmV1.renderRaw",
            "sampleRate" to RATE, "channels" to 1, "format" to "PCM_16",
            "bundle" to "assets/ohm-first-listen.zip", "rawExportGain" to rawExportGain,
            "notes" to listOf(
                "35 finite dry clips from the actual OHM Kotlin renderer. No effects rack, external recordings or browser synthesis.",
                "The first five comparisons use C4, velocity .8, seed 731, TOUCH .5, BODY .5 and LENGTH .65. Only the documented relationship or gesture changes.",
                "Raw WAVs precede the engine's output loudness processing and share one fixed PCM headroom gain of $rawExportGain. Metrics retain the unscaled raw-engine measurements.",
                "Matched WAVs use the repository AuditionLevel rule: loudest-200-ms mono RMS .03, with a .99 peak guard. This removes level as an easy preference cue; use raw playback to judge excitation dynamics.",
                "Each note starts fresh; exported samples do not share a live ensemble. GATHER saves a deterministic finite trajectory. None of these files claims a seamless sustain loop.",
                "The three phrases use the same six notes, onset times, per-note velocity and seeds. Their JSON scores preserve each constituent OHM patch and the .5 mixing gain.",
                "Pitch estimates are measurements of the full mix, not proof of perceived pitch. Numerical checks do not establish listening approval or phone/MPC playback acceptance.",
            ),
            "groups" to listOf(
                group("identities", "Start with the relationship", "Solo, independent companions, absorbed companions, quiet influence and the saved gathering gesture."),
                group("matrix", "MEDDLING × COMMUNE", "Nine static relationships. All other settings remain fixed."),
                group("controls", "Touch, body and length", "Three steps per control; the relationship remains at MEDDLING .65 and COMMUNE .5."),
                group("register", "Notes and velocity", "C3, C4 and C5 at three excitation velocities. Raw files retain their dynamics."),
                group("phrases", "The same small melody", "Identical notes and timings under solo, argument and communion settings. Tails overlap naturally."),
            ),
            "clips" to clips,
        )
        writeJson(File(assets, "manifest.json"), manifest)
        writeJson(File(assets, "diagnostics.json"), linkedMapOf(
            "modelVersion" to 1,
            "clipCount" to clips.size,
            "allFinite" to true,
            "rawExportGain" to rawExportGain,
            "highestUnscaledRawPeak" to highestRawPeak,
            "clips" to clips.map { linkedMapOf("id" to it["id"], "metrics" to it["metrics"], "hashes" to it["hashes"]) },
        ))
        File(root, "README.txt").writeText(readme(rawExportGain))
        copyPage(root)
        zip(root, File(assets, "ohm-first-listen.zip"), clips)
        println("OHM: wrote ${clips.size} raw/matched clips, recipes, manifest and first-listen ZIP under ${root.path}")
    }

    private fun render(c: Case): Rendered {
        val macros = baseline() + c.changes
        val patch = OhmPatch(c.title, macros, c.midi, c.velocity, SEED, c.gesture, 1)
        val recipe = PadRecipe(patch = patch)
        check(PadRecipe.fromJsonText(recipe.toJsonText()) == recipe) { "${c.id}: recipe fields changed on replay" }
        val raw = OhmV1.renderRaw(macros, c.midi, c.velocity, SEED, c.gesture)
        checkRaw(c.id, raw)
        println("OHM render ${c.id}: ${short(raw.durationSeconds)} s")
        return Rendered(c, raw, recipe.toJsonValue(), c.midi)
    }

    private fun renderPhrases(): List<Rendered> {
        val onsets = listOf(0.0, .64, 1.28, 2.08, 2.72, 3.36)
        val notes = listOf(60, 63, 67, 65, 63, 60)
        return listOf(
            Triple("solo", 0f, 0f), Triple("argument", .9f, 0f), Triple("communion", .9f, 1f),
        ).map { (relationship, meddling, commune) ->
            val title = "${relationship.replaceFirstChar { it.uppercase() }} / melody"
            val c = Case("phrase-$relationship", title,
                "C4–E♭4–G4–F4–E♭4–C4, with identical note onsets and natural overlapping tails. MEDDLING ${short(meddling)}, COMMUNE ${short(commune)}.",
                "phrases", mapOf("MEDDLING" to meddling, "COMMUNE" to commune, "LENGTH" to .45f))
            val macros = baseline() + c.changes
            val patches = notes.mapIndexed { i, note ->
                OhmPatch("$title / note ${i + 1}", macros, note, VELOCITY, SEED,
                    OhmGesture.STEADY, 1)
            }
            val sounds = patches.map { p -> OhmV1.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture) }
            sounds.forEachIndexed { i, snip -> checkRaw("${c.id}/$i", snip) }
            val frames = sounds.indices.maxOf { (onsets[it] * RATE).roundToInt() + sounds[it].frameCount }
            val out = FloatArray(frames)
            for (i in sounds.indices) {
                val offset = (onsets[i] * RATE).roundToInt()
                for (j in sounds[i].samples.indices) out[offset + j] += sounds[i].samples[j] * PHRASE_GAIN
            }
            val events = patches.indices.map { i -> linkedMapOf<String, Any?>(
                "onsetSeconds" to onsets[i], "onsetFrames" to (onsets[i] * RATE).roundToInt(),
                "gain" to PHRASE_GAIN, "patch" to patches[i].toJsonValue(),
                "durationSeconds" to sounds[i].durationSeconds,
            ) }
            val recipe = json(linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "type" to "OHM_PHRASE", "modelVersion" to 1,
                "name" to title, "sampleRate" to RATE, "channels" to 1,
                "source" to "OhmV1.renderRaw", "events" to events,
                "frames" to frames, "loop" to false,
                "note" to "A score of independent complete OHM patches, not a single pad patch. Mix full raw renders at the saved frame offsets and gains; audition leveling follows the mix.",
            ))
            val raw = Snip(out, 1, RATE)
            checkRaw(c.id, raw)
            println("OHM render ${c.id}: ${short(raw.durationSeconds)} s")
            Rendered(c, raw, recipe, null, events)
        }
    }

    private fun writeClip(root: File, rendered: Rendered, rawExportGain: Double): Map<String, Any?> {
        val (c, raw, recipe, midi, events) = rendered
        val rawFile = gain(raw, rawExportGain)
        val matched = AuditionLevel.level(raw)
        check(peak(rawFile) <= .980001 && peak(matched) <= .990001) { "${c.id}: exported PCM would clip" }
        val rawPath = "assets/audio/${c.id}-raw.wav"
        val matchedPath = "assets/audio/${c.id}-matched.wav"
        val recipePath = "assets/recipes/${c.id}.json"
        WavWriter.write(File(root, rawPath), rawFile, WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
        val decodedRaw = validateWav(File(root, rawPath), rawFile)
        val decodedMatched = validateWav(File(root, matchedPath), matched)
        File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(Json.write(recipe))
        val target = midi?.let { 440.0 * 2.0.pow((it - 69) / 12.0) }
        val pitch = if (midi != null) Pitch.detect(raw) else null
        val metrics = linkedMapOf<String, Any?>(
            "finite" to raw.samples.all { it.isFinite() }, "frames" to raw.frameCount,
            "durationSeconds" to raw.durationSeconds,
            "rawPeak" to peak(raw), "rawRms" to rms(raw), "rawLoudness" to Loudness.of(raw),
            "rawDc" to raw.samples.sumOf { it.toDouble() } / raw.samples.size,
            "rawExportGain" to rawExportGain, "rawExportPeak" to peak(rawFile),
            "rawExportRms" to rms(rawFile), "rawExportLoudness" to Loudness.of(rawFile),
            "matchedPeak" to peak(matched), "matchedRms" to rms(matched),
            "matchedLoudness" to Loudness.of(matched),
            "matchedGain" to (Loudness.of(matched).toDouble() / Loudness.of(raw)),
            "decodedRawPeak" to peak(decodedRaw), "decodedMatchedPeak" to peak(decodedMatched),
            "pcmValidated" to true,
            "targetHz" to target, "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
            "pitchCents" to if (target != null && pitch != null) 1200.0 * ln(pitch.hz / target) / ln(2.0) else null,
        )
        val hashes = linkedMapOf("rawWavSha256" to sha(File(root, rawPath)),
            "matchedWavSha256" to sha(File(root, matchedPath)), "recipeSha256" to sha(File(root, recipePath)))
        return linkedMapOf(
            "id" to c.id, "title" to c.title, "description" to c.description, "group" to c.group,
            "modelVersion" to 1,
            "macros" to (baseline() + c.changes), "midi" to midi, "noteLabel" to midi?.let(::noteName),
            "velocity" to c.velocity, "seed" to SEED, "gesture" to c.gesture.name,
            "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
            "recipeType" to if (events == null) "PadRecipe" else "OHM_PHRASE",
            "seconds" to raw.durationSeconds, "metrics" to metrics, "hashes" to hashes,
            "renderSource" to "OhmV1.renderRaw",
            "sweepMacro" to c.sweepMacro, "sweepValue" to c.sweepValue,
            "events" to events, "waveform" to waveform(matched.samples),
        )
    }

    private fun checkRaw(id: String, raw: Snip) {
        check(raw.channels == 1 && raw.sampleRate == RATE && raw.samples.isNotEmpty()) { "$id: not mono 44.1 kHz" }
        check(raw.samples.all { it.isFinite() }) { "$id: non-finite raw samples" }
        check(Loudness.of(raw) > 1e-8f) { "$id: silent raw render" }
    }

    private fun validateWav(file: File, expected: Snip): Snip {
        val decoded = WavReader.read(file)
        check(decoded.channels == 1 && decoded.sampleRate == RATE && decoded.frameCount == expected.frameCount) {
            "${file.name}: PCM format or length changed on export"
        }
        check(decoded.samples.all { it.isFinite() } && peak(decoded) < 1.0) { "${file.name}: invalid or clipped PCM" }
        check(Loudness.of(decoded) > 1e-8f) { "${file.name}: silent PCM" }
        // Writer/reader use their existing signed-PCM conventions; allow their scale difference
        // plus one quantization step, while catching truncation, wrong gain and damaged samples.
        check(expected.samples.indices.all { abs(decoded.samples[it] - expected.samples[it]) < 2f / 32768f }) {
            "${file.name}: decoded samples differ by more than PCM16 quantization"
        }
        return decoded
    }

    private fun group(id: String, title: String, description: String) =
        linkedMapOf("id" to id, "title" to title, "description" to description)

    private fun copyPage(root: File) {
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val stream = OhmAuditionV1Generator::class.java.getResourceAsStream("/ohm-audition-v1/$name") ?: continue
            File(root, name).outputStream().use { output -> stream.use { it.copyTo(output) } }
        }
    }

    private fun zip(root: File, output: File, clips: List<Map<String, Any?>>) {
        // Enumerate inputs before opening the archive. Never walk an existing archive into itself.
        val paths = mutableSetOf("assets/manifest.json", "assets/diagnostics.json", "README.txt")
        for (clip in clips) for (field in listOf("raw", "matched", "recipe")) paths += clip.getValue(field).toString()
        for (page in listOf("index.html", "styles.css", "app.js")) if (File(root, page).isFile) paths += page
        ZipOutputStream(output.outputStream().buffered()).use { archive ->
            for (path in paths.sorted()) {
                require(path != output.relativeTo(root).invariantSeparatorsPath)
                archive.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }
    }

    private fun readme(rawExportGain: Double) = """
        OHM / SnipSnap original model-1 first listen

        35 finite dry mono 44.1 kHz PCM16 comparisons from the frozen model-1 Kotlin renderer.
        Begin with Solo, Argument and Communion. Compare Gather against Communion.
        No seamless loop, shared live ensemble, phone or MPC acceptance is claimed.

        assets/manifest.json gives every clip's settings, paths, measured levels and SHA-256 hashes.
        assets/recipes/ stores ordinary PadRecipe JSON for the single notes.
        Standard PadRecipe.render() returns the engine's normal leveled pad output; the audition
        deliberately records an earlier point. To reproduce a downloaded single-note WAV:
          1. Read the JSON with PadRecipe.fromJsonText and take recipe.patch as OhmPatch.
          2. Call OhmV1.renderRaw(patch.macros, patch.midi, patch.velocity, patch.seed, patch.gesture).
          3. For the raw WAV, multiply every sample by rawExportGain from the manifest.
             For the matched WAV, pass that unscaled raw Snip to AuditionLevel.level instead.
          4. Write mono 44.1 kHz PCM16 with WavWriter, using model 1 and the recorded renderer sources.
        The three phrase JSON files store explicit frame offsets, gains and each complete OHM patch.
        Their source is OhmV1.renderRaw; PHRASE_GAIN is .5, then matching follows the summed audio.

        Raw audio precedes output loudness processing. One common raw PCM gain: $rawExportGain.
        Matched files use AuditionLevel: loudest 200 ms RMS .03, with a .99 peak guard.
        Raw-engine peaks and dynamics remain in the manifest before that common export gain.

        Open index.html through a local HTTP server to load the manifest and audio.
        Playback begins only when selected by the listener. Listening approval remains open.
    """.trimIndent() + "\n"

    private fun peak(snip: Snip): Double = snip.samples.maxOf { abs(it).toDouble() }
    private fun rms(snip: Snip): Double = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun gain(snip: Snip, gain: Double) = Snip(FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() }, snip.channels, snip.sampleRate)
    private fun short(value: Number): String = "%.2f".format(Locale.ROOT, value.toDouble()).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[midi % 12] + (midi / 12 - 1)
    private fun waveform(samples: FloatArray): List<Double> = (0 until 96).map { bin ->
        val start = bin * samples.size / 96
        val end = ((bin + 1) * samples.size / 96).coerceAtLeast(start + 1)
        (start until end).maxOf { abs(samples[it]).toDouble() }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun sourceHashes(): Map<String, String> {
        val fromGit = File(git("rev-parse", "--show-toplevel"))
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val repo = fromGit.takeIf { File(it, "synth/src").isDirectory }
            ?: cwd.parentFile.takeIf { File(it, "synth/src").isDirectory }
            ?: cwd.takeIf { File(it, "synth/src").isDirectory }
            ?: error("cannot locate the SnipSnap renderer sources")
        return listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Ohm.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmV1.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OhmAuditionV1Generator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        ).associateWith { path -> sha(File(repo, path)) }
    }
    private fun git(vararg args: String): String = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { process ->
            val out = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0)
            out
        }
    }.getOrDefault("unavailable")
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)))
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported OHM audition manifest value ${value::class.simpleName}")
    }
}
