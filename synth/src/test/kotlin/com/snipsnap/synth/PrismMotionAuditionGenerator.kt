package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A compact listening experiment using the actual Kotlin renderer, never a web approximation.
 * Every family keeps its carrier, note, envelope, velocity and dry signal path while changing
 * only the second amplitude spectrum and its finite trajectory. Run
 * `./gradlew :synth:generatePrismMotionAudition` for PCM16 WAVs and replayable recipes.
 */
object PrismMotionAuditionGenerator {
    private const val VELOCITY = .8f
    private val SPEEDS = listOf("fast" to .25f, "medium" to .8f, "slow" to 1.8f)

    private data class Family(
        val id: String,
        val title: String,
        val description: String,
        val voice: PrismVoice,
        val midi: Int,
        val macros: Map<String, Float>,
        val color: PrismMotionColor? = null,
        val template: PrismTemplate = Prism.templateFor(voice),
        val endpoint: List<Float>? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: PrismMotionAuditionGenerator [output-directory]"
        }
        val root = File(args.firstOrNull() ?: "../testkit/prism-motion-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val repo = repositoryRoot()
        // Record before generation; the saved files are evidence of this working tree, not HEAD alone.
        val revision = git(repo, "rev-parse", "HEAD")
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal")?.isNotEmpty()
        val clips = mutableListOf<Map<String, Any?>>()
        val ids = mutableSetOf<String>()
        val started = System.nanoTime()

        fun clip(
            id: String,
            family: String,
            variant: String,
            title: String,
            description: String,
            patch: Patch,
            midi: Int?,
            extra: Map<String, Any?> = emptyMap(),
        ) {
            require(ids.add(id)) { "duplicate clip $id" }
            val recipe = PadRecipe(patch = patch)
            check(recipe.fx == null) { "$id is not dry" }
            val text = recipe.toJsonText()
            val began = System.nanoTime()
            val raw = recipe.render()
            requireAudio(id, raw)
            val restored = PadRecipe.fromJsonText(text)
            check(restored == recipe) { "$id recipe changed during JSON roundtrip" }
            check(raw.samples.contentEquals(restored.render().samples)) { "$id recipe replay differs" }
            val matched = AuditionLevel.level(raw)
            requireAudio(id, matched)
            val rawPath = "audio/$id-raw.wav"
            val matchedPath = "audio/$id-matched.wav"
            val recipePath = "recipes/$id.json"
            WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(text) }
            val duration = raw.durationSeconds.toDouble()
            val pitch = midi?.let { Pitch.detect(raw, .10f, minOf(.25f, raw.durationSeconds * .3f)) }
            val nominalHz = midi?.let { 440.0 * 2.0.pow((it - 69) / 12.0) }
            val windows = diagnosticWindows(raw)
            val metrics = linkedMapOf<String, Any?>(
                "peak" to matched.peak(), "rms" to rms(matched), "dc" to dc(matched),
                "loudness" to Loudness.of(matched),
                "rawPeak" to raw.peak(), "rawRms" to rms(raw), "rawDc" to dc(raw),
                "rawLoudness" to Loudness.of(raw),
                "headCentroidHz" to centroid(raw, windows.first.first, windows.first.second),
                "tailCentroidHz" to centroid(raw, windows.second.first, windows.second.second),
                "headWindowStartSeconds" to windows.first.first,
                "tailWindowStartSeconds" to windows.second.first,
                "centroidWindowSeconds" to windows.first.second,
                "nominalHz" to nominalHz, "pitchHz" to pitch?.hz,
                "pitchConfidence" to pitch?.confidence,
                "pitchCents" to if (pitch != null && nominalHz != null) 1200 * ln(pitch.hz / nominalHz) / ln(2.0) else null,
            )
            clips += linkedMapOf<String, Any?>(
                "id" to id, "family" to family, "variant" to variant,
                "title" to title, "description" to description,
                "midi" to midi, "velocity" to (patch as? PrismPatch)?.velocity, "seconds" to duration,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "metrics" to metrics,
                "recipeRoundtripExact" to true,
                "rawWavSha256" to hash(File(root, rawPath)),
                "matchedWavSha256" to hash(File(root, matchedPath)),
                "recipeSha256" to hash(File(root, recipePath)),
                "renderAndReplayMillis" to (System.nanoTime() - began) / 1e6,
            ).also { it.putAll(extra) }
            println("PRISM motion ${clips.size}: $id (${duration}s, exact recipe replay)")
        }

        val families = listOf(
            Family(
                "metal-vowel", "Metallic strike → vowel",
                "A bright GLASS strike changes its partial balance toward a broad vowel-like spectrum. The carrier ratios and envelope stay fixed.",
                PrismVoice.GLASS, 60,
                Prism.defaults(PrismVoice.GLASS) + mapOf("GRAIN" to .15f, "STRIKE" to .70f, "HOLD" to .35f),
                color = PrismMotionColor.VOWEL,
            ),
            Family(
                "organ-glass", "Organ → glass",
                "A dry ORGAN body moves toward a sparse glass-colored partial balance. This changes amplitudes, not the pitch or harmonic ratios.",
                PrismVoice.ORGAN, 48,
                Prism.defaults(PrismVoice.ORGAN) + mapOf("GRAIN" to .15f, "DRIFT" to .35f, "HOLD" to .40f),
                color = PrismMotionColor.GLASS,
            ),
            Family(
                "pluck-reed", "Pluck → reed",
                "A PLUCK keeps its articulation while its groups acquire a reed-colored balance. The staggered comparison delays upper groups.",
                PrismVoice.PLUCK, 60,
                Prism.defaults(PrismVoice.PLUCK) + mapOf("GRAIN" to .15f, "BODY" to .65f, "HOLD" to .45f),
                color = PrismMotionColor.REED,
            ),
        )

        fun familyClips(family: Family, midi: Int = family.midi, register: String? = null, full: Boolean = true) {
            val macros = family.macros + ("TUNE" to (midi - Prism.rootMidi(family.voice)) / Prism.TUNE_SEMITONES.toFloat())
            val endpoint = family.endpoint ?: Prism.motionEndpoint(
                voice = family.voice, color = requireNotNull(family.color), macros = macros, template = family.template,
            )
            val stem = family.id + (register?.let { "-$it" } ?: "")
            val registerLabel = register?.let { " · $it register" } ?: ""
            fun patch(name: String, motion: PrismMotion?) = PrismPatch(
                name = name, voice = family.voice, macros = macros, velocity = VELOCITY,
                template = family.template, motion = motion,
            )
            val extra = linkedMapOf<String, Any?>(
                "familyTitle" to family.title, "register" to (register ?: "main"),
                "voice" to family.voice.name,
                "endpointSource" to if (family.endpoint == null) "original synthesized spectral template" else "synthesized VOX donor",
            )
            clip("$stem-a", family.id, "baseline", "A · Original PRISM$registerLabel",
                "Exact existing PRISM path with no motion configuration. ${family.description}",
                patch("${family.title} A$registerLabel", null), midi, extra)
            clip("$stem-b", family.id, "endpoint", "B · Static destination$registerLabel",
                "Destination spectrum from the beginning; the same note, carrier, articulation and dry path as A.",
                patch("${family.title} B$registerLabel", PrismMotion(amplitudesB = endpoint, morph = 1f)), midi, extra)
            val speeds = if (full) SPEEDS else listOf("medium" to .8f)
            for ((speed, seconds) in speeds) {
                clip("$stem-$speed", family.id, "motion-$speed", "A → B · $speed$registerLabel",
                    "One finite spectral movement lasting $seconds seconds, with no pitch motion or effects.",
                    patch("${family.title} $speed$registerLabel", PrismMotion(
                        amplitudesB = endpoint, morph = 0f, evolve = 1f, seconds = seconds,
                    )), midi, extra + mapOf("motionSeconds" to seconds, "groupLag" to 0f))
            }
            if (full) {
                clip("$stem-staggered", family.id, "staggered", "A → B · staggered groups$registerLabel",
                    "The same 0.8-second motion with progressively later upper groups; the last group starts 0.8 seconds after the first.",
                    patch("${family.title} staggered$registerLabel", PrismMotion(
                        amplitudesB = endpoint, morph = 0f, evolve = 1f, seconds = .8f, groupLag = 1f,
                    )), midi, extra + mapOf("motionSeconds" to .8f, "groupLag" to 1f))
            }
        }

        families.forEach { familyClips(it) }
        // Main ORGAN comparison is C3. Recheck the same laws at C2 and C4.
        familyClips(families[1], midi = 36, register = "low", full = false)
        familyClips(families[1], midi = 60, register = "high", full = false)

        val donorMacros = Vox.defaults(VoxVoice.ROBOT) + mapOf("VOWEL" to 0f, "GLIDE" to .5f, "DECAY" to .6f, "TUNE" to .5f)
        val donorPatch = VoxPatch("Synthesized VOX donor", VoxVoice.ROBOT, donorMacros)
        val donor = donorPatch.render()
        clip("donor-vox-source", "donor-imprint", "donor", "Synthesized VOX donor",
            "Generated by SnipSnap's VOX ROBOT engine. This is a synthesized fixture, not a recorded person, recording or voice clone.",
            donorPatch, null, mapOf("familyTitle" to "Synthesized donor imprint", "sourceKind" to "synthesized VOX ROBOT"))
        val donorVoice = PrismVoice.KEY
        val donorMidi = 60
        val donorPrismMacros = Prism.defaults(donorVoice) + mapOf("TUNE" to .5f, "GRAIN" to .15f, "HOLD" to .35f)
        val donorTemplate = Prism.templateFor(donorVoice)
        val endpoint = PrismImprint.endpoint(
            donor = donor,
            partialHz = Prism.partialFrequencies(donorVoice, donorPrismMacros, donorTemplate),
        )
        familyClips(Family(
            "donor-imprint", "Synthesized donor imprint",
            "The destination copies a smoothed fixed-frequency color from the generated VOX donor. PRISM supplies every audible carrier and its own envelope; this is a spectral imprint, not donor playback.",
            donorVoice, donorMidi, donorPrismMacros, template = donorTemplate, endpoint = endpoint,
        ))

        check(clips.size == 31) { "expected 31 clips, generated ${clips.size}" }
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotion.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismImprint.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/PrismMotionAuditionGenerator.kt",
        ).mapNotNull { path -> File(repo, path).takeIf { it.isFile }?.let { path to hash(it) } }.toMap()
        val manifest = linkedMapOf<String, Any?>(
            "title" to "PRISM · a spectrum in motion",
            "description" to "Compare dry spectral transitions at a steady pitch. Start with matched playback, then try a different speed.",
            "schemaVersion" to 1, "generatedAt" to Instant.now().toString(),
            "sourceRevision" to revision, "sourceDirty" to dirty,
            "sourceNote" to "Working-tree source hashes identify this prototype. HEAD alone does not contain uncommitted changes.",
            "sourceFilesSha256" to sourceFiles,
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 16,
            "rawMeaning" to "Normal dry engine output before extra audition matching, including the engine's standard conditioning and level convention. Not the internal unconditioned additive sum.",
            "matchedMeaning" to "One scalar gain per clip: loudest 200 ms RMS target 0.03 with a 0.99 peak guard, using AuditionLevel.level.",
            "metricsNote" to "Metrics describe floating-point buffers before PCM16 quantization. Centroids are magnitude-weighted STFT estimates in the stated windows; pitch is a local estimate, not a stability proof.",
            "limitations" to "Finite mono motion only. No wet effects, recorded donor, continuous live modulation, held motion or looping claim. Sonic acceptance remains the owner's listening decision.",
            "clipCount" to clips.size, "clips" to clips,
            "generationSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        for (name in listOf("index.html", "app.js", "styles.css")) {
            val resource = requireNotNull(PrismMotionAuditionGenerator::class.java
                .getResourceAsStream("/audition/prism-motion/$name")) { "missing motion audition page $name" }
            resource.use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
        }
        println("Wrote ${clips.size} dry PRISM comparisons to ${root.path}")
    }

    private fun requireAudio(id: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE) { "$id has unexpected audio format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id has invalid audio" }
        check(snip.peak() > 0f && snip.peak() < 1f) { "$id has silent or clipped audio" }
    }

    private fun rms(snip: Snip): Double = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun dc(snip: Snip): Double = snip.samples.sumOf { it.toDouble() } / snip.samples.size

    private fun diagnosticWindows(snip: Snip): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val seconds = snip.durationSeconds.toDouble()
        val width = minOf(.2, seconds * .2)
        val head = minOf(.06, seconds * .05)
        val tail = minOf(seconds - width, maxOf(head + width, minOf(2.0, seconds * .60)))
        return (head to width) to (tail to width)
    }

    private fun centroid(snip: Snip, from: Double, seconds: Double): Double? {
        val start = (from * snip.sampleRate).toInt().coerceIn(0, snip.frameCount - 1)
        val end = ((from + seconds) * snip.sampleRate).toInt().coerceIn(start + 1, snip.frameCount)
        val window = Snip(snip.samples.copyOfRange(start, end), channels = 1, sampleRate = snip.sampleRate)
        var weighted = 0.0
        var mass = 0.0
        Spectral.forEachFrame(window) { _, _, magnitudes ->
            for (bin in 1 until magnitudes.size) {
                val hz = Spectral.binHz(bin, snip.sampleRate).toDouble()
                if (hz >= 40.0) { weighted += hz * magnitudes[bin]; mass += magnitudes[bin] }
            }
        }
        return if (mass > 1e-12) weighted / mass else null
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun repositoryRoot(): File {
        var directory = File(".").canonicalFile
        while (!File(directory, ".git").exists() && directory.parentFile != null) directory = directory.parentFile
        return directory
    }

    private fun git(repo: File, vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments).directory(repo).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value ${value::class}")
    }
}
