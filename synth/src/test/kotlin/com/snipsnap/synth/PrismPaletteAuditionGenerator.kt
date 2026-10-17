package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Source-selection listening evidence: eight untouched factory patches, their static PRISM
 * rearticulations, and the same additive rearticulations moving from a shared neutral A to B.
 * This changes the listening palette only; the renderer, imprint analyzer and 32-partial limit
 * remain unchanged. Source and PRISM articulation are deliberately labeled separately.
 */
object PrismPaletteAuditionGenerator {
    private const val PRISM_VELOCITY = .8f
    private const val MOTION_SECONDS = .8f

    private data class Candidate(
        val id: String,
        val category: String,
        val categoryTitle: String,
        val patch: Patch,
        val character: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: PrismPaletteAuditionGenerator [output-directory]"
        }
        val root = File(args.firstOrNull() ?: "../testkit/prism-palette-audition").absoluteFile
        val assets = listOf("index.html", "app.js", "styles.css").associateWith { name ->
            requireNotNull(javaClass.getResourceAsStream("/audition/prism-palette/$name")) {
                "missing PRISM palette listening asset $name"
            }.use { it.readBytes() }
        }
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val repo = repositoryRoot()
        val sourceRevision = git(repo, "rev-parse", "HEAD")
        val sourceDirty = git(repo, "status", "--porcelain", "--untracked-files=normal")?.isNotEmpty()
        val sourceFiles = sourceFiles(repo)
        val started = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val ids = mutableSetOf<String>()
        val candidates = candidates()
        check(candidates.size == 8 && candidates.map { it.id }.distinct().size == 8)

        fun clip(
            candidate: Candidate,
            variant: String,
            title: String,
            description: String,
            patch: Patch,
            midi: Int,
            actualHz: Float,
            metadata: Map<String, Any?>,
            rendered: Snip? = null,
        ): Snip {
            val id = "${candidate.id}-$variant"
            check(ids.add(id)) { "duplicate clip $id" }
            val began = System.nanoTime()
            val recipe = PadRecipe(patch = patch)
            check(recipe.fx == null) { "$id adds an effect chain" }
            val recipeText = recipe.toJsonText()
            val native = rendered ?: recipe.render()
            requireAudio(id, native)
            val reopened = PadRecipe.fromJsonText(recipeText)
            check(reopened == recipe) { "$id recipe changed during JSON roundtrip" }
            check(sameBits(native.samples, reopened.render().samples)) { "$id JSON recipe replay differs" }
            if (variant != "source") check(native.durationSeconds >= 3f) { "$id PRISM note is shorter than three seconds" }
            val matched = AuditionLevel.level(native)
            requireAudio(id, matched)
            val rawPath = "audio/$id-raw.wav"
            val matchedPath = "audio/$id-matched.wav"
            val recipePath = "recipes/$id.json"
            WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(recipeText) }
            val nominalHz = Keys.midiHz(midi)
            clips += linkedMapOf<String, Any?>(
                "id" to id, "family" to candidate.id, "familyTitle" to candidate.patch.name,
                "category" to candidate.category, "categoryTitle" to candidate.categoryTitle,
                "variant" to variant, "title" to title, "description" to description,
                "sourceEngine" to candidate.patch.engine, "sourceName" to candidate.patch.name,
                "sourceVoice" to candidate.patch.voiceName,
                "engine" to patch.engine, "voice" to patch.voiceName,
                "midi" to midi, "actualHz" to actualHz,
                "nominalHz" to nominalHz, "nominalPitchOffsetCents" to cents(actualHz, nominalHz),
                "velocity" to when (patch) { is PrismPatch -> patch.velocity; is EmbouchurePatch -> patch.velocity; else -> null },
                "seconds" to native.durationSeconds, "channels" to native.channels,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "metrics" to metrics(native, matched, actualHz),
                "recipeRoundtripExact" to true,
                "rawSamplesSha256" to samplesHash(native.samples),
                "rawWavSha256" to hash(File(root, rawPath)),
                "matchedWavSha256" to hash(File(root, matchedPath)),
                "recipeSha256" to hash(File(root, recipePath)),
                "renderAndReplayMillis" to (System.nanoTime() - began) / 1e6,
            ).also { it.putAll(metadata) }
            println("PRISM palette ${clips.size}/24: $id (${native.durationSeconds}s, exact recipe replay)")
            return native
        }

        for (candidate in candidates) {
            val sourcePatch = candidate.patch
            val sourceHz = nativeHz(sourcePatch)
            val sourceMidi = (69 + 12 * ln(sourceHz / 440.0) / ln(2.0)).roundToInt()
            val donor = sourcePatch.render()
            requireAudio(candidate.id, donor)
            val prismVoice = if (sourceMidi < Prism.rootMidi(PrismVoice.KEY)) PrismVoice.BASS else PrismVoice.KEY
            val tune = (sourceMidi - Prism.rootMidi(prismVoice)) / Prism.TUNE_SEMITONES.toFloat()
            check(tune in 0f..1f) { "${candidate.id} cannot map native MIDI $sourceMidi to PRISM $prismVoice" }
            // An explicit common harmonic A avoids silently inheriting a factory source palette.
            // The 1/n slope is a softer neutral starting color, never multiplied into B.
            val template = PrismTemplate(
                spectralId = PrismSpectrum.ROUNDED,
                envelopeId = PrismContour.HELD,
                ratioOverrides = List(Prism.PARTIALS) { it + 1f },
                amplitudeOverrides = List(Prism.PARTIALS) { 1f / (it + 1f) },
                groupProfiles = List(Prism.GROUPS) { PrismGroupProfile() },
                attackSeconds = .04f, decaySeconds = 2.6f,
            )
            val macros = mapOf(
                "TUNE" to tune, "GRAIN" to 0f, "DRIFT" to 0f,
                "SHAPE" to .4f, "STRIKE" to .4f, "BODY" to .5f, "HOLD" to .8f,
            )
            val targetHz = Prism.frequencyFor(prismVoice, tune)
            val coefficients = PrismImprint.endpoint(donor, Prism.partialFrequencies(prismVoice, macros, template))
            val static = PrismPatch(
                name = "${sourcePatch.name} through PRISM", voice = prismVoice,
                macros = macros, velocity = PRISM_VELOCITY, template = template,
                motion = PrismMotion(amplitudesB = coefficients, morph = 1f, evolve = 0f, seconds = MOTION_SECONDS),
            )
            val moving = static.copy(
                name = "${sourcePatch.name} moving through PRISM",
                motion = requireNotNull(static.motion).copy(morph = 0f, evolve = 1f),
            )
            check(static.voice == moving.voice && static.macros == moving.macros && static.template == moving.template &&
                static.velocity == moving.velocity && static.model == moving.model &&
                static.motion!!.amplitudesB == moving.motion!!.amplitudesB) { "${candidate.id} static/motion comparison changed its carrier or endpoint" }
            val metadata = linkedMapOf<String, Any?>(
                "sourceMidi" to sourceMidi, "sourceHz" to sourceHz,
                "sourcePitchOffsetCents" to cents(sourceHz, Keys.midiHz(sourceMidi)),
                "targetMidi" to sourceMidi, "targetHz" to targetHz,
                "sourceToTargetCents" to cents(targetHz, sourceHz),
                "sourcePatch" to sourcePatch.toJsonValue(),
                "sourceRecipe" to "recipes/${candidate.id}-source.json",
                "sourceRaw" to "audio/${candidate.id}-source-raw.wav",
                "sourceMatched" to "audio/${candidate.id}-source-matched.wav",
                "sourceSamplesSha256" to samplesHash(donor.samples),
                "sourceSelectionStatus" to "Listening candidate; no sonic approval recorded.",
                "imprintBands" to 32, "imprintPartialCount" to Prism.PARTIALS,
                "motionSeconds" to MOTION_SECONDS, "groupLag" to 0f,
                "sourceNativeDrive" to if (sourcePatch is FathomPatch) sourcePatch.macros["DRIVE"] else null,
                "sourceNote" to "The original factory patch is unchanged. Source movement and articulation remain native; PRISM uses a separate common finite envelope.",
            )
            clip(candidate, "source", "Original source", candidate.character + " The untouched factory source, before the PRISM imprint step.",
                sourcePatch, sourceMidi, sourceHz, metadata, donor)
            val sourceWavHash = hash(File(root, "audio/${candidate.id}-source-raw.wav"))
            // Include this same donor hash on every stage, including the source row already emitted.
            clips[clips.lastIndex] = clips.last() + ("sourceWavSha256" to sourceWavHash)
            val withSourceHash = metadata + ("sourceWavSha256" to sourceWavHash)
            val staticAudio = clip(candidate, "imprint", "Static PRISM imprint",
                "The source's smoothed spectral color, rearticulated through PRISM's 32 harmonic carriers and common finite envelope. This does not replay the source's attack, friction, beating or gesture.",
                static, sourceMidi, targetHz, withSourceHash)
            val movingAudio = clip(candidate, "motion", "Moving PRISM imprint",
                "The same PRISM carrier, note, velocity, envelope and destination coefficients as the static imprint. A softer neutral harmonic A moves to the source-derived B over 0.8 seconds, with no added effects.",
                moving, sourceMidi, targetHz, withSourceHash)
            check(staticAudio.frameCount == movingAudio.frameCount) { "${candidate.id} static/motion durations differ" }
        }
        check(clips.size == 24 && clips.groupBy { it["family"] }.values.all { rows ->
            rows.map { it["variant"] }.toSet() == setOf("source", "imprint", "motion")
        }) { "incomplete three-stage source matrix" }
        val manifest = linkedMapOf<String, Any?>(
            "title" to "PRISM · choose the source",
            "description" to "Eight source candidates in four directions. Hear the original first, then its static PRISM imprint and the same imprint in motion.",
            "schemaVersion" to 1, "generatedAt" to Instant.now().toString(),
            "sourceRevision" to sourceRevision, "sourceDirty" to sourceDirty,
            "sourceFilesSha256" to sourceFiles,
            "sourceNote" to "Actual working-tree hashes identify this audition. HEAD alone does not include uncommitted source changes.",
            "selectionStatus" to "Candidate palette for the owner's listening decision; no sonic approval recorded.",
            "sampleRate" to Dsp.RATE, "bitDepth" to 16,
            "rawMeaning" to "Native dry engine output before additional audition matching, including each engine's own conditioning, level convention and intrinsic drive or acoustic chamber.",
            "matchedMeaning" to "One scalar gain per clip using AuditionLevel.level: loudest 200 ms RMS target 0.03 with a 0.99 peak guard.",
            "comparisonNote" to "Original sources retain native articulation. Static and moving PRISM pairs use the same 32 harmonic carriers, root, velocity, amplitude A, envelope and B coefficients. Only the finite interpolation law changes.",
            "imprintNote" to "The existing 32-band fixed-frequency spectral-envelope adapter is unchanged. It retains broad color through PRISM, not transient shape, noise, inharmonic modes, beating, friction or a complete source model; low-register analysis uses approximately 43 Hz FFT bins.",
            "metricsNote" to "Measurements use floating-point audio before PCM16 quantization. Source/target frequencies are engine-requested nominal references. Local autocorrelation pitch estimates can follow a harmonic and do not establish sonic quality.",
            "limitations" to "This is source selection, not a new DSP model. No added FX, 64-partial bank, original-source morph, phone controls or sustained-motion loop is claimed.",
            "defaultVariant" to "source", "clipCount" to clips.size, "clips" to clips,
            "generationSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        assets.forEach { (name, bytes) -> File(root, name).writeBytes(bytes) }
        println("Wrote ${clips.size} source-selection comparisons to ${root.path}")
    }

    private fun candidates(): List<Candidate> = listOf(
        Candidate("fork-soft-keys", "electric-keys", "Rounded electric keys",
            ForkPresets.forVoice(ForkVoice.TINE).first { it.name == "SOFT KEYS" }, "A gentle tine strike with a restrained magnetic-pickup bark."),
        Candidate("fork-mellow-reed", "electric-keys", "Rounded electric keys",
            ForkPresets.forVoice(ForkVoice.REED).first { it.name == "MELLOW REED" }, "A slower, mellow reed key with little contact rattle."),
        Candidate("tessera-wooden-arrival", "wood-strings", "Wood and strings",
            TesseraPresets.forVoice(TesseraVoice.WOOD).first { it.name == "Wooden Arrival" }, "A struck wooden object with its own coupled frame and chamber."),
        Candidate("tessera-paired-wire", "wood-strings", "Wood and strings",
            TesseraPresets.forVoice(TesseraVoice.COURSE).first { it.name == "Paired Wire" }, "Paired string courses, contact and returning acoustic paths."),
        Candidate("embouchure-soft-entry", "breath-friction", "Breath and friction",
            EmbouchurePresets.forVoice(EmbouchureVoice.SOFT).first { it.name == "Soft Entry" }, "A soft reed driver with a gradual player gesture."),
        Candidate("embouchure-relaxed-bow", "breath-friction", "Breath and friction",
            EmbouchurePresets.forVoice(EmbouchureVoice.BOW).first { it.name == "Relaxed Bow" }, "A bowed driver with restrained effort, friction and capture."),
        Candidate("fathom-hollow-growl", "dark-saturated", "Darker saturated",
            FathomPresets.forVoice(FathomVoice.GRIND).first { it.name == "HOLLOW GROWL" }, "Detuned low saws through FATHOM's own drive and dark filter."),
        Candidate("fathom-driven-sub", "dark-saturated", "Darker saturated",
            FathomPresets.forVoice(FathomVoice.DEEP).first { it.name == "DRIVEN SUB" }, "A driven low fundamental and built-in pitch gesture, contrasting with the saw pair."),
    )

    private fun nativeHz(patch: Patch): Float = when (patch) {
        is ForkPatch -> Fork.frequencyFor(patch.macros["TUNE"] ?: .5f)
        is TesseraPatch -> Tessera.frequencyFor(patch.voice, patch.macros["TUNE"] ?: Tessera.defaults(patch.voice).getValue("TUNE"))
        is EmbouchurePatch -> Keys.midiHz(patch.midi + ((patch.macros["TUNE"] ?: Embouchure.defaults(patch.voice).getValue("TUNE")) * Embouchure.TUNE_SEMITONES).roundToInt())
        is FathomPatch -> Fathom.frequencyFor(patch.voice, patch.macros["TUNE"] ?: Fathom.defaults(patch.voice).getValue("TUNE"))
        else -> error("unsupported source palette engine ${patch.engine}")
    }

    private fun metrics(raw: Snip, matched: Snip, expectedHz: Float): Map<String, Any?> {
        val duration = raw.durationSeconds.toDouble()
        val width = minOf(.2, duration * .2)
        val head = minOf(.06, duration * .05)
        val tail = minOf(duration - width, maxOf(head + width, minOf(2.0, duration * .60)))
        val pitch = Pitch.detect(raw, minOf(.1f, raw.durationSeconds * .1f), minOf(.25f, raw.durationSeconds * .3f))
        return linkedMapOf(
            "peak" to matched.peak(), "rms" to rms(matched), "dc" to dc(matched), "loudness" to Loudness.of(matched),
            "rawPeak" to raw.peak(), "rawRms" to rms(raw), "rawDc" to dc(raw), "rawLoudness" to Loudness.of(raw),
            "headCentroidHz" to centroid(raw, head, width), "tailCentroidHz" to centroid(raw, tail, width),
            "headWindowStartSeconds" to head, "tailWindowStartSeconds" to tail, "centroidWindowSeconds" to width,
            "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
            "pitchCents" to pitch?.let { cents(it.hz, expectedHz) },
        )
    }

    private fun requireAudio(id: String, snip: Snip) {
        check(snip.channels in 1..2 && snip.sampleRate == Dsp.RATE) { "$id has unexpected audio format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id has invalid audio" }
        check(snip.peak() > 0f && snip.peak() < 1f) { "$id has silent or clipped audio" }
    }

    private fun cents(actual: Float, reference: Float): Double = 1200 * ln(actual.toDouble() / reference) / ln(2.0)
    private fun rms(snip: Snip): Double = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun dc(snip: Snip): Double = snip.samples.sumOf { it.toDouble() } / snip.samples.size
    private fun sameBits(a: FloatArray, b: FloatArray): Boolean = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }

    private fun centroid(snip: Snip, from: Double, seconds: Double): Double? {
        val start = (from * snip.sampleRate).toInt().coerceIn(0, snip.frameCount - 1)
        val end = ((from + seconds) * snip.sampleRate).toInt().coerceIn(start + 1, snip.frameCount)
        val window = Snip(snip.samples.copyOfRange(start * snip.channels, end * snip.channels), snip.channels, snip.sampleRate)
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

    private fun sourceFiles(repo: File): Map<String, String> = listOf(
        "Prism.kt", "PrismPatch.kt", "PrismMotion.kt", "PrismImprint.kt", "Dsp.kt",
        "Fork.kt", "ForkPresets.kt", "Tessera.kt", "TesseraPresets.kt",
        "Embouchure.kt", "EmbouchurePatch.kt", "EmbouchurePresets.kt", "Fathom.kt", "FathomPresets.kt",
    ).map { "synth/src/main/kotlin/com/snipsnap/synth/$it" }.plus(listOf(
        "synth/src/test/kotlin/com/snipsnap/synth/PrismPaletteAuditionGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Transplant.kt",
    )).associateWith { path -> hash(File(repo, path)) }

    private fun hash(file: File): String = digest(file.readBytes())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return digest(bytes.array())
    }

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
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value ${value::class}")
    }
}
