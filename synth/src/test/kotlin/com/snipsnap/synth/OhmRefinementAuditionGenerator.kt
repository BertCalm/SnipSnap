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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Three finite model-4 comparisons, beside byte-pinned accepted model-2 depth references. */
object OhmRefinementAuditionGenerator {
    internal const val RATE = 44_100
    internal const val MODEL = 4
    internal data class Clip(val id: String, val title: String, val patch: OhmPatch, val diagnostic: Boolean = false)
    internal data class Comparison(val id: String, val title: String, val description: String,
                                   val question: String, val changed: String, val labels: List<String>)

    internal val comparisons = listOf(
        Comparison("bow", "Plucked or bowed?", "The shared body stays the same. Only the way the main string is excited changes.",
            "Which way of starting the shared sound would you use?", "bowed", listOf("Plucked", "Bowed")),
        Comparison("grains", "A little audio texture", "Compare the shared sound with and without short slices of source audio.",
            "Does this version add a useful texture?", "grains-60", listOf("No grains", "Grains · 60%")),
        Comparison("freeze", "A memory of the sound", "Compare the shared sound with and without captured source-buffer memory. Grains stay off.",
            "Would you keep this memory of the sound?", "freeze-75", listOf("No memory", "Memory · 75%")),
    )
    internal val anchorHashes = mapOf(
        "reference-companions" to "b63d15e54f0d5dddcd7782b5034080d50c6520acdb645ac95c353ab5c9e1b8bb",
        "reference-shared" to "e2dbf07fa19271a154f840e27ffa29488fbcf58447771916f9646d4dc47a22dd",
    )

    internal fun clips(): List<Clip> {
        fun patch(name: String, commune: Float = 1f, options: OhmOptions = OhmOptions(), model: Int = MODEL) =
            OhmPatch(name, Ohm.defaults(model) + mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
                "MEDDLING" to .9f, "COMMUNE" to commune) + if (model >= 3) mapOf("HOLD" to 0f) else emptyMap(),
                midi = 60, velocity = .8f, seed = 731, gesture = OhmGesture.STEADY, model = model, options = options)
        return buildList {
            add(Clip("reference-companions", "Step 2 · Add companions", patch("Argument", commune = 0f, model = 2)))
            add(Clip("reference-shared", "Step 3 · Share vibration", patch("Communion", model = 2)))
            add(Clip("plucked", "Plucked shared sound", patch("Plucked shared sound")))
            add(Clip("bowed", "Bowed shared sound", patch("Bowed shared sound", options = OhmOptions(exciter = OhmExciter.BOW))))
            add(Clip("grains-60", "Shared sound · grains 60%", patch("Shared sound · grains 60%", options = OhmOptions(grains = .6f, grainSeconds = .08f))))
            add(Clip("freeze-75", "Shared sound · memory 75%", patch("Shared sound · memory 75%", options = OhmOptions(freeze = .75f))))
            // These contextual and full-amount probes are exported, but never become extra listening questions.
            add(Clip("grains-100", "Shared sound · grains 100%", patch("Grains maximum", options = OhmOptions(grains = 1f)), true))
            add(Clip("freeze-100", "Shared sound · memory 100%", patch("Memory maximum", options = OhmOptions(freeze = 1f)), true))
            for (source in toList().filter { it.patch.model == MODEL }) {
                add(source.copy(id = "${source.id}-c0", title = "${source.title} · independent diagnostic",
                    patch = source.patch.copy(macros = source.patch.macros + ("COMMUNE" to 0f)), diagnostic = true))
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size in 3..4) { "--replay SCORE.json OUTPUT.wav [raw|matched]" }
            val score = Json.parse(File(args[1]).readText())
            val mode = args.getOrNull(3) ?: "matched"
            val file = File(args[2])
            writeWav(file, replay(score, mode))
            val expected = score.obj().getValue("hashes").obj().getValue("${mode}WavSha256").str()
            check(sha(file) == expected) { "replayed WAV differs from the saved audition" }
            return
        }
        require(args.size <= 1) { "supply only an output directory" }
        generate(File(args.firstOrNull() ?: "../testkit/ohm-refinement-audition"))
    }

    fun generate(output: File) {
        val root = output.canonicalFile.also { it.mkdirs() }
        require(root.isDirectory)
        val sources = sourceHashes()
        val specs = clips()
        require(specs.size == 14 && specs.map { it.id }.distinct().size == specs.size)
        val raw = specs.associate { it.id to render(it.patch) }
        val rawGain = minOf(1.0, .98 / raw.values.maxOf(::peak).coerceAtLeast(1e-12))
        val written = specs.associate { spec ->
            val sound = raw.getValue(spec.id)
            val matched = AuditionLevel.level(sound)
            val matchedGain = peak(matched) / peak(sound).coerceAtLeast(1e-12)
            val rawPath = "assets/audio/${spec.id}-raw.wav"
            val matchedPath = "assets/audio/${spec.id}-matched.wav"
            val recipePath = "assets/recipes/${spec.id}.json"
            writeWav(File(root, rawPath), scaled(sound, rawGain))
            writeWav(File(root, matchedPath), matched)
            anchorHashes[spec.id]?.let { expected ->
                check(sha(File(root, matchedPath)) == expected) { "${spec.id}: accepted model-2 anchor changed" }
            }
            val hashes = mapOf("rawWavSha256" to sha(File(root, rawPath)), "matchedWavSha256" to sha(File(root, matchedPath)))
            val score = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "type" to "OHM_REFINEMENT_AUDITION", "name" to spec.title,
                "source" to "Ohm.renderRaw", "padRecipe" to PadRecipe(patch = spec.patch).toJsonValue(),
                "sampleRate" to RATE, "channels" to 1, "frames" to sound.frameCount,
                "rawExportGain" to rawGain, "matchedGainMeasured" to matchedGain,
                "matchedPolicy" to "AuditionLevel.level: loudest 200 ms target .03, peak guard .99",
                "hashes" to hashes,
            )
            writeJson(File(root, recipePath), score)
            val decoded = WavReader.read(File(root, matchedPath))
            val detected = Pitch.detect(decoded)
            val latePitch = Pitch.detect(decoded, fromSec = .5f)
            val entry = linkedMapOf<String, Any?>(
                "id" to spec.id, "title" to spec.title, "modelVersion" to spec.patch.model,
                "diagnosticOnly" to spec.diagnostic, "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "patch" to spec.patch.toJsonValue(), "seconds" to sound.durationSeconds,
                "hashes" to hashes + ("recipeSha256" to sha(File(root, recipePath))),
                "metrics" to mapOf("finite" to true, "frames" to sound.frameCount, "rawPeak" to peak(sound),
                    "rawRms" to rms(sound.samples), "rawDc" to sound.samples.average(), "rawLoudness" to Loudness.of(sound),
                    "matchedPeak" to peak(matched), "matchedLoudness" to Loudness.of(matched),
                    "matchedGainMeasured" to matchedGain, "pcmValidated" to true,
                    "targetHz" to 261.6255653005986, "pitchHz" to detected?.hz, "pitchConfidence" to detected?.confidence,
                    "latePitchHz" to latePitch?.hz, "latePitchConfidence" to latePitch?.confidence),
            )
            println("OHM refinement ${spec.id}: ${sound.frameCount} frames")
            spec.id to entry
        }
        val changes = buildList {
            for (context in listOf("", "-c0")) for (changed in listOf("bowed", "grains-60", "grains-100", "freeze-75", "freeze-100")) {
                val baseline = "plucked$context"
                val variant = "$changed$context"
                add(mapOf("baseline" to baseline, "changed" to variant,
                    "conditionedRaw" to difference(raw.getValue(baseline), raw.getValue(variant)),
                    "decodedRawPcm16" to difference(WavReader.read(File(root, written.getValue(baseline).getValue("raw").toString())),
                        WavReader.read(File(root, written.getValue(variant).getValue("raw").toString()))),
                    "decodedMatchedPcm16" to difference(WavReader.read(File(root, written.getValue(baseline).getValue("matched").toString())),
                        WavReader.read(File(root, written.getValue(variant).getValue("matched").toString())))))
            }
        }
        check(sourceHashes() == sources) { "OHM or audition sources changed during rendering" }
        writeJson(File(root, "assets/manifest.json"), linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "OHM / Shared sound refinements", "modelVersion" to MODEL,
            "renderedAt" to Instant.now().toString(), "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(), "rendererSources" to sources,
            "sampleRate" to RATE, "channels" to 1, "format" to "PCM_16", "rawExportGain" to rawGain,
            "bundle" to "assets/ohm-refinements.zip",
            "comparisons" to comparisons.map { c -> mapOf("id" to c.id, "title" to c.title, "description" to c.description,
                "question" to c.question, "variants" to listOf(mapOf("clipId" to "plucked", "label" to c.labels[0]),
                    mapOf("clipId" to c.changed, "label" to c.labels[1]))) },
            "references" to listOf(mapOf("clipId" to "reference-companions", "label" to "Step 2 · Add companions"),
                mapOf("clipId" to "reference-shared", "label" to "Step 3 · Share vibration")),
            "clips" to written.values.filter { it["diagnosticOnly"] == false },
            "notes" to listOf("Model-4 comparisons change one optional source at a time; the shared baseline WAV is reused without reprocessing.",
                "Accepted model-2 stage-2 and stage-3 references are complete, unmodified renders pinned to their published WAV hashes. They are direction references, not isolated model-4 A/B baselines.",
                "All finite notes use C4, velocity .8, seed 731, STEADY, TOUCH/BODY .5, LENGTH .65, MEDDLING .9, HOLD 0. Main comparisons use COMMUNE 1.",
                "Matched levels each complete note with AuditionLevel. Raw uses one common headroom scalar across all notes. There is no browser synthesis, cropped listening excerpt, or added effects.",
                "Freeze means captured source-buffer memory, not FFT spectral freeze. Numerical differences and pitch estimates do not establish listening approval."),
        ))
        writeJson(File(root, "assets/diagnostics.json"), mapOf("clips" to written.values, "differences" to changes,
            "differencePolicy" to "Difference RMS / baseline RMS without time alignment or fitted gain; post-onset starts at 300 ms. PCM16 maximum difference uses the decoded WAVs.",
            "allFinite" to true, "highestUnscaledRawPeak" to raw.values.maxOf(::peak), "rawExportGain" to rawGain))
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val resource = requireNotNull(javaClass.getResourceAsStream("/ohm-refinement-audition/$name"))
            File(root, name).outputStream().use { target -> resource.use { it.copyTo(target) } }
        }
        File(root, "README.txt").writeText(readme())
        val paths = mutableSetOf("index.html", "styles.css", "app.js", "README.txt", "assets/manifest.json", "assets/diagnostics.json")
        for (entry in written.values) for (field in listOf("raw", "matched", "recipe")) paths += entry.getValue(field).toString()
        ZipOutputStream(File(root, "assets/ohm-refinements.zip").outputStream().buffered()).use { zip ->
            for (path in paths.sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("OHM refinements: 3 comparisons, 2 unchanged references and 8 diagnostic notes under ${root.path}")
    }

    internal fun render(p: OhmPatch): Snip {
        check(OhmPatch.fromJsonText(p.toJsonText()) == p) { "patch changed on JSON round trip" }
        return Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model, p.options).also {
            check(it.channels == 1 && it.sampleRate == RATE && it.samples.isNotEmpty())
            check(it.samples.all(Float::isFinite) && Loudness.of(it) > 1e-8f) { "non-finite or silent OHM render" }
        }
    }

    /** Replay the saved single-note score; no current defaults, page state, or source WAV is needed. */
    internal fun replay(value: JsonValue, mode: String): Snip {
        require(mode in setOf("raw", "matched"))
        val score = value.obj()
        require(score.getValue("type").str() == "OHM_REFINEMENT_AUDITION" && score.getValue("source").str() == "Ohm.renderRaw")
        require(score.getValue("sampleRate").int() == RATE && score.getValue("channels").int() == 1)
        val recipe = PadRecipe.fromJsonValue(score.getValue("padRecipe"))
        require(recipe.fx == null && recipe.board == null && recipe.pedals == null && recipe.terminalFadeMs == null)
        val sound = render(recipe.patch as OhmPatch)
        require(sound.frameCount == score.getValue("frames").int()) { "saved renderer length changed" }
        val gain = score.getValue("rawExportGain").num()
        require(gain.isFinite() && gain > 0 && gain <= 1)
        return if (mode == "raw") scaled(sound, gain) else AuditionLevel.level(sound)
    }

    internal fun difference(base: Snip, changed: Snip): Map<String, Any?> {
        require(base.sampleRate == changed.sampleRate && base.channels == changed.channels && base.frameCount == changed.frameCount)
        fun ratio(from: Int): Double {
            val numerator = (from until base.samples.size).sumOf { val d = changed.samples[it].toDouble() - base.samples[it]; d * d }
            val denominator = (from until base.samples.size).sumOf { base.samples[it].toDouble() * base.samples[it] }
            return sqrt(numerator / denominator.coerceAtLeast(1e-24))
        }
        val whole = ratio(0)
        return mapOf("differenceRmsRelativeToBaseline" to whole,
            "differenceDbRelativeToBaseline" to (20 * ln(whole.coerceAtLeast(1e-12)) / ln(10.0)),
            "after300msDifferenceRmsRelativeToBaseline" to ratio(minOf((.3 * RATE).toInt(), base.frameCount - 1)),
            "maxPcm16Difference" to base.samples.indices.maxOf {
                abs((changed.samples[it] * 32767f).roundToInt() - (base.samples[it] * 32767f).roundToInt())
            })
    }

    private fun writeWav(file: File, sound: Snip) {
        require(peak(sound) <= .990001)
        file.parentFile.mkdirs()
        WavWriter.write(file, sound, WavWriter.BitDepth.PCM_16)
        val decoded = WavReader.read(file)
        check(decoded.channels == sound.channels && decoded.sampleRate == sound.sampleRate && decoded.frameCount == sound.frameCount)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - sound.samples[it]) < 2f / 32768f })
    }
    private fun scaled(s: Snip, gain: Double) = Snip(FloatArray(s.samples.size) { (s.samples[it] * gain).toFloat() }, 1, RATE)
    private fun peak(s: Snip) = s.samples.maxOf { abs(it).toDouble() }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0)
        return result
    }
    private fun sourceHashes(): Map<String, String> {
        val repo = File(git("rev-parse", "--show-toplevel"))
        val synth = listOf("Ohm.kt", "OhmPatch.kt", "OhmOptions.kt", "OhmCapture.kt", "OhmV2.kt", "OhmV3.kt", "OhmTextures.kt", "Strings.kt", "Modes.kt", "Keys.kt", "Dsp.kt", "Tide.kt", "Bore.kt", "PadRecipe.kt")
        return (synth.map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } +
            listOf("Cleanup.kt", "Resampler.kt", "Loudness.kt", "WavWriter.kt", "WavReader.kt", "Pitch.kt").map { "audio/src/main/kotlin/com/snipsnap/audio/$it" } +
            listOf("OhmRefinementAuditionGenerator.kt", "AuditionLevel.kt").map { "synth/src/test/kotlin/com/snipsnap/synth/$it" } +
            listOf("index.html", "styles.css", "app.js").map { "synth/src/test/resources/ohm-refinement-audition/$it" })
            .associateWith { sha(File(repo, it)) }
    }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported JSON value")
    }
    private fun readme() = """
        OHM / Shared sound refinements

        Three finite model-4 comparisons: pluck/bow, grains 0/.60, source-buffer memory
        0/.75. All compare C4, velocity .8, seed731, STEADY, TOUCH/BODY .5, LENGTH .65,
        MEDDLING .9, COMMUNE1, HOLD0. The identical plucked baseline is one shared file.
        Two complete model-2 depth references are pinned to the accepted published WAV
        hashes. References are not a model-4 isolated A/B. Nothing is cropped or looped.

        Every note has independent raw/matched mono44.1kHz PCM16 WAVs and a complete
        single-note replay score. Match uses the shared AuditionLevel loudest200ms rule.
        Raw uses one common scalar, recorded in each score and the manifest. Reproduce:
          ./gradlew :synth:generateOhmRefinementAudition -PohmRefinementReplay=/path/score.json \
            -PohmRefinementReplayOutput=/path/out.wav -PohmRefinementReplayMode=matched
        Use raw to reproduce the common-gain raw WAV. Replay checks the saved WAV hash.

        Diagnostics include independent(COMMUNE0) and maximum-amount texture probes,
        decodedPCM differences, pitch estimates, source revision and source hashes.
        These probes are downloadable evidence, not more top-level listening questions.
        Numerical checks do not establish listening approval. Freeze means captured
        source-buffer memory, not FFT spectral freeze. No browser-generated sound.

        The ZIP contains this portable page, all audio, scores and evidence. Serve the
        folder over HTTP with byte-range support for immediate seeking.
    """.trimIndent() + "\n"
}
