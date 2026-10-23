package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Four source-derived openings; old reference audio, recipes and row metadata are preserved. */
object PrismOpeningAuditionGenerator {
    private val FAMILIES = listOf("fork-soft-keys", "tessera-paired-wire", "embouchure-relaxed-bow", "fathom-hollow-growl")
    private const val EARLY_FROM = .06
    private const val EARLY_SECONDS = .20
    private const val LATE_FROM = 1.20
    private const val LATE_SECONDS = .25

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 3 && args.none { it.startsWith("--") }) {
            "usage: PrismOpeningAuditionGenerator [output-directory] [palette-directory] [published-reference-manifest]"
        }
        val root = File(args.getOrNull(0) ?: "../testkit/prism-opening-audition").absoluteFile
        val palette = File(args.getOrNull(1) ?: "../testkit/prism-palette-audition").absoluteFile
        val paletteManifestFile = File(palette, "manifest.json")
        val paletteManifest = Json.parse(paletteManifestFile.readText()).obj()
        val paletteRows = paletteManifest.getValue("clips").arr().associateBy { it.obj().getValue("id").str() }
        val publishedFile = args.getOrNull(2)?.let { File(it) }
        val published = publishedFile?.let { Json.parse(it.readText()).obj() }
        val publishedRows = published?.getValue("clips")?.arr()?.associateBy { it.obj().getValue("id").str() }
        val assets = listOf("index.html", "app.js", "styles.css").associateWith { name ->
            requireNotNull(javaClass.getResourceAsStream("/audition/prism-opening/$name")) {
                "missing PRISM opening listening asset $name"
            }.use { it.readBytes() }
        }
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val repo = repositoryRoot()
        val revision = git(repo, "rev-parse", "HEAD")
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal")?.isNotEmpty()
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotion.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismImprint.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/PrismOpeningAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/PrismPaletteAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        ).associateWith { hash(File(repo, it)) }
        val started = System.nanoTime()
        val clips = mutableListOf<JsonValue.Obj>()
        val referenceProof = mutableListOf<Map<String, Any?>>()
        val diagnostics = mutableListOf<Map<String, Any?>>()

        fun copyReference(id: String, variant: String): JsonValue.Obj {
            val original = paletteRows.getValue(id).obj()
            val publishedRow = publishedRows?.getValue(id)?.obj()
            val hashes = linkedMapOf<String, String>()
            for ((pathKey, hashKey) in listOf("raw" to "rawWavSha256", "matched" to "matchedWavSha256", "recipe" to "recipeSha256")) {
                val path = original.getValue(pathKey).str()
                val input = childFile(palette, path)
                val digest = hash(input)
                check(digest == original.getValue(hashKey).str()) { "$id $pathKey no longer matches palette manifest" }
                if (publishedRow != null) check(digest == publishedRow.getValue(hashKey).str()) {
                    "$id $pathKey differs from the published source-selection reference"
                }
                val output = childFile(root, path)
                output.parentFile.mkdirs()
                input.copyTo(output, overwrite = true)
                check(hash(output) == digest) { "$id $pathKey changed while copying" }
                hashes[path] = digest
            }
            referenceProof += linkedMapOf(
                "id" to id, "originalVariant" to original.getValue("variant"), "variant" to variant,
                "filesSha256" to hashes, "paletteVerified" to true,
                "publishedReferenceVerified" to (publishedRow != null),
            )
            // The variant is the only existing row field changed: old titles, measurements,
            // source metadata, IDs, paths and hashes remain those of the reference pack.
            return JsonValue.Obj(LinkedHashMap(original).apply { put("variant", JsonValue.Str(variant)) })
        }

        for (family in FAMILIES) {
            val sourceRow = copyReference("$family-source", "source")
            val previousRow = copyReference("$family-motion", "previous")
            val destinationRow = copyReference("$family-imprint", "destination")
            val previousRecipe = PadRecipe.fromJsonText(childFile(palette, previousRow.entries.getValue("recipe").str()).readText())
            val destinationRecipe = PadRecipe.fromJsonText(childFile(palette, destinationRow.entries.getValue("recipe").str()).readText())
            check(previousRecipe.fx == null && destinationRecipe.fx == null) { "$family reference has an FX chain" }
            val previous = previousRecipe.patch as? PrismPatch ?: error("$family previous recipe is not PRISM")
            val destination = destinationRecipe.patch as? PrismPatch ?: error("$family destination recipe is not PRISM")
            val priorMotion = requireNotNull(previous.motion)
            check(priorMotion.amplitudesA == null && priorMotion.morph == 0f && priorMotion.evolve == 1f &&
                priorMotion.seconds == .8f && priorMotion.groupLag == 0f) { "$family does not have the expected previous trajectory" }
            check(previous.copy(name = destination.name, motion = destination.motion) == destination) {
                "$family static destination changes its carrier, template, velocity or envelope"
            }
            check(requireNotNull(destination.motion).amplitudesB == priorMotion.amplitudesB &&
                destination.motion!!.morph == 1f && destination.motion!!.evolve == 0f) { "$family static endpoint differs" }
            check(previous.template.ratioOverrides == List(Prism.PARTIALS) { it + 1f }) {
                "$family reference does not use the expected harmonic carrier map"
            }
            val opening = PrismOpeningPresets.bodyAmplitudes(priorMotion.amplitudesB)
            check(opening.indices.all { opening[it] <= priorMotion.amplitudesB[it] }) { "$family opening boosts B" }
            check(opening.first() == priorMotion.amplitudesB.first()) { "$family opening changes its root coefficient" }
            val body = previous.copy(
                name = "${previousRow.entries.getValue("sourceName").str()} source-derived opening",
                motion = priorMotion.copy(amplitudesA = opening),
            )
            // Removing the new optional A must recover the entire previous saved sound.
            check(body.copy(name = previous.name, motion = body.motion!!.copy(amplitudesA = null)) == previous)
            val recipe = PadRecipe(patch = body)
            val recipeText = recipe.toJsonText()
            val native = recipe.render()
            requireAudio(family, native)
            val reopened = PadRecipe.fromJsonText(recipeText)
            check(reopened == recipe && sameBits(native.samples, reopened.render().samples)) { "$family body recipe replay differs" }
            val matched = AuditionLevel.level(native)
            requireAudio(family, matched)
            val id = "$family-body"
            val rawPath = "audio/$id-raw.wav"
            val matchedPath = "audio/$id-matched.wav"
            val recipePath = "recipes/$id.json"
            WavWriter.write(childFile(root, rawPath), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(childFile(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            childFile(root, recipePath).apply { parentFile.mkdirs(); writeText(recipeText) }

            // Probe the actual emitted signal, including masks, root anchoring, shared headroom,
            // group contours and conditioning. The harmonic measurements remove overall gain.
            val oldProbe = probe(previous)
            val destinationProbe = probe(destination)
            val bodyProbe = probe(body)
            check(oldProbe.partialHz.contentEquals(bodyProbe.partialHz) && bodyProbe.partialHz.contentEquals(destinationProbe.partialHz))
            val oldEvidence = evidence(oldProbe)
            val destinationEvidence = evidence(destinationProbe)
            val bodyEvidence = evidence(bodyProbe)
            val tailDistance = distance(bodyEvidence.late.energyShares, destinationEvidence.late.energyShares)
            val oldTailDistance = distance(oldEvidence.late.energyShares, destinationEvidence.late.energyShares)
            check((0 until Prism.GROUPS).all { body.motion!!.positionAt(LATE_FROM, it) == 1.0 }) {
                "$family trajectory has not reached B at the late evidence window"
            }
            val earlySofter = bodyEvidence.early.centroidHz < oldEvidence.early.centroidHz
            val earlyLessUpperEnergy = bodyEvidence.early.highFrequencyShare < oldEvidence.early.highFrequencyShare
            val report = linkedMapOf<String, Any?>(
                "family" to family, "openingCoefficients" to opening,
                "destinationCoefficients" to priorMotion.amplitudesB,
                "openingNeverBoostsDestination" to true, "rootCoefficientUnchanged" to true,
                "carriersTemplatesEnvelopesVelocityUnchanged" to true,
                "earlyWindow" to listOf(EARLY_FROM, EARLY_FROM + EARLY_SECONDS),
                "lateWindow" to listOf(LATE_FROM, LATE_FROM + LATE_SECONDS),
                "previous" to oldEvidence.toMap(), "body" to bodyEvidence.toMap(), "destination" to destinationEvidence.toMap(),
                "bodyTailDistanceFromDestination" to tailDistance,
                "previousTailDistanceFromDestination" to oldTailDistance,
                "bodyEarlyCentroidBelowPrevious" to earlySofter,
                "bodyEarlyHighFrequencyShareBelowPrevious" to earlyLessUpperEnergy,
                "comparisonNote" to "Normalized measurements, not listening approval. Very dark destinations can already have little upper energy; early reductions are reported rather than used as an arbitrary acceptance gate.",
            )
            diagnostics += report
            val metrics = linkedMapOf<String, Any?>(
                "peak" to matched.peak(), "rms" to rms(matched), "dc" to dc(matched), "loudness" to Loudness.of(matched),
                "rawPeak" to native.peak(), "rawRms" to rms(native), "rawDc" to dc(native), "rawLoudness" to Loudness.of(native),
                "headCentroidHz" to bodyEvidence.early.centroidHz, "tailCentroidHz" to bodyEvidence.late.centroidHz,
                "headHighFrequencyShare" to bodyEvidence.early.highFrequencyShare,
                "tailHighFrequencyShare" to bodyEvidence.late.highFrequencyShare,
                "tailDistanceFromDestination" to tailDistance,
            )
            val bodyFields = LinkedHashMap(previousRow.entries)
            mapOf<String, Any?>(
                "id" to id, "variant" to "body", "title" to "Source-derived opening",
                "description" to "A darker opening derived from this destination's own partial balance unfolds over the same 0.8 seconds. Pitch, phase, carrier, envelope, velocity and destination are unchanged. This is a candidate for listening, not an approved sound.",
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "seconds" to native.durationSeconds, "metrics" to metrics,
                "rawSamplesSha256" to samplesHash(native.samples), "rawWavSha256" to hash(childFile(root, rawPath)),
                "matchedWavSha256" to hash(childFile(root, matchedPath)), "recipeSha256" to hash(childFile(root, recipePath)),
                "recipeRoundtripExact" to true, "openingDiagnostics" to report,
            ).forEach { (key, value) -> bodyFields[key] = json(value) }
            // The old timing value is not provenance for the newly rendered body clip.
            bodyFields.remove("renderAndReplayMillis")
            clips += listOf(JsonValue.Obj(bodyFields), previousRow, destinationRow, sourceRow)
            println("PRISM opening ${diagnostics.size}/4: $family, body tail distance $tailDistance, early centroid ${bodyEvidence.early.centroidHz} vs previous ${oldEvidence.early.centroidHz} Hz")
        }
        check(clips.size == 16 && clips.map { it.entries.getValue("id").str() }.distinct().size == 16)
        paletteManifestFile.copyTo(File(root, "reference-palette-manifest.json"), overwrite = true)
        publishedFile?.copyTo(File(root, "published-palette-manifest.json"), overwrite = true)
        File(root, "diagnostics.json").writeText(Json.write(json(diagnostics)))
        val manifest = linkedMapOf<String, Any?>(
            "title" to "PRISM · a different opening",
            "description" to "Four destinations, each with an opening derived from its own darker body. Compare it with the previous opening and the unchanged destination; the original source stays available.",
            "schemaVersion" to 1, "generatedAt" to Instant.now().toString(),
            "sourceRevision" to revision, "sourceDirty" to dirty, "sourceFilesSha256" to sourceFiles,
            "sourceNote" to "Source hashes identify this actual working tree. Reference audio and recipes are copied byte-for-byte from the palette pack, with their original clip metadata and IDs; only their stage labels change.",
            "referenceManifest" to "reference-palette-manifest.json", "referenceManifestSha256" to hash(paletteManifestFile),
            "referenceSourceRevision" to paletteManifest["sourceRevision"],
            "publishedReferenceManifest" to publishedFile?.let { "published-palette-manifest.json" },
            "publishedReferenceManifestSha256" to publishedFile?.let { hash(it) },
            "publishedReferenceSourceRevision" to published?.get("sourceRevision"),
            "referenceProof" to referenceProof, "diagnostics" to "diagnostics.json",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 16,
            "rawMeaning" to "Dry native engine output before extra listening-level matching, including normal engine conditioning and leveling.",
            "matchedMeaning" to "One scalar gain per clip: loudest 200 ms RMS target 0.03 with a 0.99 peak guard, using AuditionLevel.level.",
            "openingRule" to "A[i] = B[i] * (0.03 + 0.97 / (1 + (i/4)^4)), i=0..31. The first harmonic remains B's root; no opening coefficient exceeds B.",
            "comparisonNote" to "Only the new optional opening amplitudes differ from the previous moving recipe. The original template still owns phases, carrier ratios and envelopes; B, pitch, velocity, 0.8-second trajectory and finite duration are unchanged.",
            "metricsNote" to "New harmonic diagnostics use unlevelled conditioned Prism.probe output and Hann-windowed exact-frequency projections, normalized by root amplitude or total harmonic energy. Reference clip metrics retain their original definitions. Diagnostics support listening; they do not establish sonic approval.",
            "limitations" to "Finite mono opening experiment only. The destination and source presets are unchanged. No new partials, added FX, original-source morph, phone controls or held-motion loop is claimed.",
            "defaultVariant" to "body", "clipCount" to clips.size, "clips" to clips,
            "generationSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        assets.forEach { (name, bytes) -> File(root, name).writeBytes(bytes) }
        println("Wrote ${clips.size} PRISM opening comparisons to ${root.path}")
    }

    private data class Snapshot(val amplitudes: List<Double>, val energyShares: List<Double>, val centroidHz: Double, val highFrequencyShare: Double) {
        fun toMap() = mapOf(
            "amplitudeRelativeToRoot" to amplitudes, "energyShares" to energyShares,
            "centroidHz" to centroidHz, "highFrequencyShareAbove2kHz" to highFrequencyShare,
        )
    }
    private data class Evidence(val frequencies: List<Double>, val initialRatios: List<Double>, val early: Snapshot, val late: Snapshot) {
        fun toMap() = mapOf("partialHz" to frequencies, "initialWeightRelativeToRoot" to initialRatios, "early" to early.toMap(), "late" to late.toMap())
    }

    private fun probe(patch: PrismPatch) = Prism.probe(
        voice = patch.voice, macros = patch.macros, velocity = patch.velocity, template = patch.template,
        normalize = false, keepRaw = false, motion = patch.motion,
    )
    private fun evidence(probe: Prism.Probe): Evidence {
        val root = probe.partialWeights.first().toDouble().coerceAtLeast(1e-20)
        return Evidence(probe.partialHz.toList(), probe.partialWeights.map { it / root },
            snapshot(probe.samples, probe.partialHz, EARLY_FROM, EARLY_SECONDS),
            snapshot(probe.samples, probe.partialHz, LATE_FROM, LATE_SECONDS))
    }

    /** Exact carrier-frequency projections avoid coarse FFT bins for the low bass reference. */
    private fun snapshot(samples: FloatArray, hz: DoubleArray, from: Double, seconds: Double): Snapshot {
        val start = (from * Dsp.RATE).toInt()
        val count = (seconds * Dsp.RATE).toInt()
        require(start >= 0 && start + count <= samples.size)
        val weighted = DoubleArray(count) { i -> samples[start + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))) }
        val amplitudes = hz.map { frequency ->
            val step = 2 * PI * frequency / Dsp.RATE
            val cs = cos(step); val ss = sin(step)
            var c = 1.0; var s = 0.0; var re = 0.0; var im = 0.0
            for (sample in weighted) {
                re += sample * c; im += sample * s
                val next = c * cs - s * ss
                s = s * cs + c * ss; c = next
            }
            sqrt(re * re + im * im)
        }
        val root = amplitudes.first().coerceAtLeast(1e-20)
        val energy = amplitudes.sumOf { it * it }.coerceAtLeast(1e-20)
        val shares = amplitudes.map { it * it / energy }
        val mass = amplitudes.sum().coerceAtLeast(1e-20)
        return Snapshot(amplitudes.map { it / root }, shares,
            amplitudes.indices.sumOf { amplitudes[it] * hz[it] } / mass,
            shares.indices.filter { hz[it] >= 2000.0 }.sumOf { shares[it] })
    }

    private fun distance(a: List<Double>, b: List<Double>): Double = a.indices.sumOf { kotlin.math.abs(a[it] - b[it]) } * .5
    private fun requireAudio(id: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id invalid audio" }
        check(snip.peak() > 0f && snip.peak() < 1f) { "$id silent or clipped" }
    }
    private fun rms(snip: Snip) = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun dc(snip: Snip) = snip.samples.sumOf { it.toDouble() } / snip.samples.size
    private fun sameBits(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun childFile(root: File, path: String): File = File(root, path).canonicalFile.also {
        require(it.toPath().startsWith(root.canonicalFile.toPath())) { "audition path escapes its pack: $path" }
    }
    private fun hash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
