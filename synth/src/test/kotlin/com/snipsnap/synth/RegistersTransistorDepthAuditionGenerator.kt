package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Compare the original TRANSISTOR with a fuller model without changing any saved sound. */
object RegistersTransistorDepthAuditionGenerator {
    private const val REVISION = "r5-transistor-depth"
    private const val BASELINE_REVISION = "r4-transistor"
    private const val BASELINE_AUDIO_HASH = "456940839b461d542ba156bfa8a031fe0fc3eb8cacbf331297ef68e8ca753035"
    private const val BASELINE_MANIFEST_HASH = "51cb881ac0dabe090cb20001bf56343f0118a26861c0694f69c05c98caf4ceb7"
    private const val MODEL = 5
    private const val TARGET = .03
    private const val SEAM_LIMIT = .001
    private const val CYCLE_LIMIT = .00001
    private val voice = RegistersVoice.TRANSISTOR

    private data class Baseline(
        val root: File, val manifest: JsonValue, val clips: List<Map<String, Any?>>,
        val recipeHashes: Map<String, String>, val sourceSnapshots: List<Map<String, String>>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } <= 1 &&
            args.filter { it.startsWith("--") }.all { it.startsWith("--baseline=") }) {
            "Supply one output directory and optional --baseline=<published R4 archive>."
        }
        val root = File(args.firstOrNull { !it.startsWith("--") }
            ?: "../testkit/registers-audition-r5").canonicalFile
        val baseline = loadBaseline(File(args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')
            ?: "/workspace/scratch/registers-r4-published"))
        check(root != baseline.root && !root.toPath().startsWith(baseline.root.toPath()) &&
            !baseline.root.toPath().startsWith(root.toPath())) { "The R4 archive and R5 output must be separate directories." }
        check(root.mkdirs() || root.isDirectory)
        preserveArchive(root, baseline)
        val originals = baseline.clips.filter { it["voice"] == voice.name }
        check(originals.size == 31 && originals.all { (it.getValue("recipe") as Map<*, *>)["model"] == 4.0 })
        val clips = baseline.clips.map { old ->
            LinkedHashMap(old).also { clip ->
                // R1/R2/R3 scopes remain intact. Implicit R4 scopes become explicit before
                // the old TRANSISTOR ids move, so local reviews retain their sound identity.
                if (clip["reviewAudioHash"] == null) {
                    clip["reviewClipId"] = old.getValue("id")
                    clip["reviewRevision"] = BASELINE_REVISION
                    clip["reviewAudioHash"] = BASELINE_AUDIO_HASH
                }
                clip["recipeSha256"] = baseline.recipeHashes.getValue(old.getValue("id") as String)
                clip["retainedFromR4"] = true
                if (clip["voice"] == voice.name) {
                    clip["id"] = "r4_${old.getValue("id")}"
                    clip["section"] = "depth-comparison-baselines"
                }
            }
        }.toMutableList<Map<String, Any?>>()
        for ((index, old) in originals.withIndex()) {
            clips += render(root, old)
            println("REGISTERS R5 ${index + 1}/${originals.size}: ${old.getValue("id")}")
        }
        check(clips.size == 147 && clips.map { it.getValue("id") }.distinct().size == clips.size)
        validateRetainedAssets(root, baseline)
        validateComparisonReferences(baseline.manifest, clips)
        validateTouch(clips)
        val pairs = depthPairs(root, clips)
        val repository = repositoryRoot()
        val sources = sourceFingerprints(repository, root)
        val audioHash = audioHash(clips)
        val old = baseline.manifest.obj()
        val metadata = baselineMetadata(baseline)
        @Suppress("UNCHECKED_CAST")
        val manifest = LinkedHashMap(plain(baseline.manifest) as Map<String, Any?>)
        manifest.putAll(linkedMapOf(
            "revision" to REVISION, "model" to MODEL,
            "status" to "PIPE and MUSETTE working audition baseline; fuller TRANSISTOR depth comparison awaiting listening; physical phone and MPC listening pending",
            "voiceStatus" to mapOf("PIPE" to "working-audition-baseline", "MUSETTE" to "working-audition-baseline",
                "TRANSISTOR" to "depth-listening-pending"),
            "clipCount" to clips.size, "audioHash" to audioHash,
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to sources,
            "sourceFingerprint" to sha256(Json.write(json(sources)).toByteArray(Charsets.UTF_8)),
            "previous" to metadata, "baselines" to old.getValue("baselines").arr().map(::plain) + metadata,
            "metricNotes" to "All 116 published R4 recordings and recipes are copied byte-identical. The 31 new TRANSISTOR cases use precisely the same note, velocity, macros and patch names, changing only model 4 to model 5. Model 5 adds a same-pitch foundation and gentler upper-rank balance; it adds no sub-octave, effects or reverb. Raw preserves conditioned pre-level engine samples with any PCM guard recorded as rawExportGain. Matched playback uses AuditionLevel: loudest 200 ms RMS 0.03 after SnipSnap's gentle low cut. Metrics and depth spectra are measured from decoded PCM24 WAVs. Fundamental-band fractions compare power within 12 Hz of the requested note against 20 Hz–12 kHz power in the same settled Hann window; they are spectral evidence, not a perceptual score. Held regions are copied three times only after independent adjacent synthesis cycles pass recurrence checks. Existing PIPE, MUSETTE, R1/R2 comparisons and all review histories remain available. Physical phone and MPC listening remain pending.",
            "sections" to sections(old.getValue("sections")),
            "voices" to voices(old.getValue("voices")), "clips" to clips,
            "depthComparisonPairs" to pairs,
            "summary" to mapOf(
                "wavCount" to clips.size * 2, "heldCases" to clips.count { it["loop"] != null },
                "allDeterministic" to clips.all { (it.getValue("diagnostics") as Map<*, *>)["deterministic"] == true },
                "allLoopsPassed" to clips.filter { it["loop"] != null }.all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "allAirflowLoopsPassed" to clips.filter { it["loop"] != null && it["airflow"] != null }
                    .all { (it["loop"] as Map<*, *>)["airflowCyclePass"] == true },
                "maxRawPeak" to clips.maxOf { number(nested(it, "metrics", "raw", "peak")) },
                "totalAudioSeconds" to clips.sumOf { number(it.getValue("seconds")) },
                "airflowPairs" to old.getValue("comparisonPairs").arr().size,
                "preservedChiffHistoryPairs" to old.getValue("comparisonHistoryPairs").arr().size,
                "depthPairs" to pairs.size, "preservedR4Clips" to baseline.clips.size,
                "preservedR4TransistorClips" to originals.size, "newTransistorClips" to originals.size,
                "allRetainedWavsByteIdentical" to true, "allRetainedRecipesByteIdentical" to true,
            ),
        ))
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val resource = RegistersTransistorDepthAuditionGenerator::class.java
                .getResourceAsStream("/audition/registers-audition/$asset") ?: error("Missing listening page resource $asset")
            File(root, asset).outputStream().use { output -> resource.use { it.copyTo(output) } }
        }
        File(root, "README.md").writeText("""
            # REGISTERS audition · $REVISION

            116 published R4 cases and every prior comparison are retained byte-identical.
            31 new model-5 TRANSISTOR cases use the same settings as their original model-4
            recipes. Eleven direct depth pairs cover six register/gesture identities, three
            factory presets and two independently verified held loops. 294 mono PCM24 WAVs
            at 44.1 kHz. The browser plays exported audio without synthesis or added effects.

            The fuller sound adds a same-pitch foundation and softens upper-rank balance.
            It does not add a sub-octave or room effect. Compare matched playback first:
            both sides use the loudest 200 ms RMS target of 0.03. Raw remains available.
            Spectral fundamental-band fractions are measured from the decoded matched WAVs
            using the same settled Hann window and ±12 Hz fundamental band on both sides.

            Original R4 TRANSISTOR ids move to r4_transistor_* while their WAV/recipe paths,
            bytes and review scopes remain exact. New TRANSISTOR clips keep transistor_*
            ids and use r5_transistor_* asset paths. R5 reviews save in a separate scope.
            The complete published R4 manifest and all 13 verified R4 source snapshots are
            archived in baseline/r4-manifest.json and baseline/source/r4 respectively.
            Historical R1/R2/R3 manifests, available snapshots and review scopes survive.
            PIPE and MUSETTE remain the accepted working audition baseline. Physical phone
            and MPC listening, and approval of the fuller TRANSISTOR, remain pending.

            Regenerate: ./gradlew :synth:generateRegistersTransistorDepthAudition -PregistersR4Baseline=<published R4 archive>
            Published R4 manifest SHA-256: $BASELINE_MANIFEST_HASH
            Published R4 audio fingerprint: $BASELINE_AUDIO_HASH
            R5 audio fingerprint: $audioHash
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} cases / ${clips.size * 2} WAVs and the $REVISION audition under ${root.absolutePath}")
    }

    private fun render(root: File, original: Map<String, Any?>): Map<String, Any?> {
        val id = original.getValue("id") as String
        val oldPatch = RegistersPatch.fromJsonValue(json(original.getValue("recipe")))
        check(oldPatch.voice == voice && oldPatch.model == 4)
        val patch = oldPatch.copy(model = MODEL)
        val recipe = patch.toJsonValue()
        check(recipe.entries - "model" == oldPatch.toJsonValue().entries - "model")
        val factoryName = original["factoryPreset"] as String?
        if (factoryName != null) check(RegistersPresets.forVoice(voice).single { it.name == factoryName }.copy(model = MODEL) == patch) {
            "$id: the published comparison settings no longer match the current factory patch"
        }
        val probe = patch.probe()
        check(probe.samples.contentEquals(RegistersPatch.fromJsonValue(recipe).probe().samples)) { "$id: nondeterministic recipe" }
        check(probe.rawPeak.isFinite() && probe.rawPeak <= Registers.RAW_PEAK_CEILING)
        val loop = if (probe.isLoop) loopEvidence(id, probe) else null
        val samples = if (probe.isLoop) FloatArray(probe.samples.size * 3) { probe.samples[it % probe.samples.size] } else probe.samples
        val source = Snip(samples, 1, Dsp.RATE)
        validateSnip(source, id)
        val exportGain = minOf(1f, .99f / source.peak().coerceAtLeast(1e-9f))
        val raw = Snip(FloatArray(source.frameCount) { source.samples[it] * exportGain }, 1, Dsp.RATE)
        val matched = AuditionLevel.level(source)
        val rawPath = "raw/r5_$id.wav"
        val matchedPath = "matched/r5_$id.wav"
        val recipePath = "recipes/r5_$id.json"
        val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
        val rawMetrics = metrics(validateExport(rawFile, raw))
        val decodedMatched = validateExport(matchedFile, matched)
        val matchedMetrics = metrics(decodedMatched)
        check(abs(number(matchedMetrics.getValue("loudestWindowRms")) - TARGET) < .00001) { "$id: matched target not reached" }
        val recipeFile = File(root, recipePath).also { it.parentFile.mkdirs() }
        recipeFile.writeText(Json.write(recipe) + "\n")
        check(RegistersPatch.fromJsonText(recipeFile.readText()) == patch)
        return LinkedHashMap(original).also { clip ->
            for (key in listOf("reviewClipId", "reviewRevision", "reviewAudioHash", "retainedFromR3", "retainedFromR4")) clip.remove(key)
            clip.putAll(mapOf(
                "raw" to rawPath, "matched" to matchedPath, "recipeUrl" to recipePath,
                "frames" to raw.frameCount, "seconds" to raw.frameCount.toDouble() / Dsp.RATE,
                "metrics" to mapOf("raw" to rawMetrics, "matched" to matchedMetrics),
                "sourceMetrics" to metrics(source), "rawExportGain" to exportGain,
                "sha256" to mapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile)),
                "recipe" to plain(recipe), "recipeSha256" to sha256(recipeFile),
                "diagnostics" to mapOf(
                    "model" to MODEL, "requestedHz" to probe.requestedHz, "effectiveHz" to probe.effectiveHz,
                    "tuningErrorCents" to 1200.0 * ln(probe.effectiveHz / probe.requestedHz) / ln(2.0),
                    "rawPeak" to probe.rawPeak, "effectiveSpreadHz" to probe.effectiveSpreadHz,
                    "motionHz" to probe.motionHz, "deterministic" to true,
                    "recipeRoundtripSamplesExact" to true, "sameControlsAsR4" to true,
                ),
                "loop" to loop, "auditionRevision" to REVISION, "listeningStatus" to "depth-pending",
                "depthSpectrum" to depthSpectrum(decodedMatched, probe.requestedHz),
            ))
        }
    }

    private fun depthPairs(root: File, clips: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val byId = clips.associateBy { it.getValue("id") as String }
        val current = clips.filter { it["voice"] == voice.name && it["auditionRevision"] == REVISION &&
            it["section"] in listOf("identities", "presets", "loops") }
        check(current.size == 11)
        return current.map { revised ->
            val id = revised.getValue("id") as String
            val originalId = "r4_$id"
            val original = byId.getValue(originalId)
            val oldRecipe = (original.getValue("recipe") as Map<*, *>) - "model"
            val newRecipe = (revised.getValue("recipe") as Map<*, *>) - "model"
            check(oldRecipe == newRecipe) { "$id: controls changed across depth comparison" }
            check(nested(original, "sha256", "raw") != nested(revised, "sha256", "raw")) { "$id: depth did not change the sound" }
            val originalSpectrum = depthSpectrum(WavReader.read(archiveAsset(root, original.getValue("matched") as String)),
                number(nested(original, "diagnostics", "requestedHz")))
            linkedMapOf(
                "id" to "depth_$id", "group" to "depth", "label" to revised.getValue("label"),
                "description" to "Same note, velocity and controls. Compare the original with a fuller same-pitch foundation and gentler upper ranks at matched listening level.",
                "originalId" to originalId, "revisedId" to id,
                "originalLabel" to "Original", "revisedLabel" to "More body",
                "sameControls" to true, "originalModel" to 4, "revisedModel" to MODEL,
                "spectralBody" to mapOf("original" to originalSpectrum, "revised" to revised.getValue("depthSpectrum")),
            )
        }
    }

    /** A normalized low-partial measurement, independent of the overall playback gain. */
    private fun depthSpectrum(snip: Snip, rootHz: Double): Map<String, Any> {
        val from = (Dsp.RATE * .3).toInt()
        val size = Fft.floorPowerOfTwo(minOf(32768, snip.frameCount - from))
        check(size >= 4096)
        val spectrum = Fft.magnitudeSpectrum(snip.samples.copyOfRange(from, from + size), size)
        var total = 0.0
        var fundamental = 0.0
        for (bin in 1 until spectrum.size) {
            val hz = bin * Dsp.RATE.toDouble() / size
            if (hz !in 20.0..12000.0) continue
            val power = spectrum[bin].toDouble() * spectrum[bin]
            total += power
            if (abs(hz - rootHz) <= 12.0) fundamental += power
        }
        check(total > 0 && fundamental.isFinite())
        return mapOf("fromFrame" to from, "windowFrames" to size, "fftSize" to size,
            "window" to "Hann", "binHz" to Dsp.RATE.toDouble() / size,
            "rootHz" to rootHz, "fundamentalHalfWidthHz" to 12.0,
            "bandLowHz" to 20, "bandHighHz" to 12000,
            "fundamentalPowerFraction" to fundamental / total,
            "measurementMode" to "decoded matched PCM24")
    }

    private fun loadBaseline(directory: File): Baseline {
        val root = directory.canonicalFile
        val file = archiveAsset(root, "manifest.json")
        check(sha256(file) == BASELINE_MANIFEST_HASH) { "The R4 manifest differs from the published revision." }
        val manifest = Json.parse(file.readText())
        val obj = manifest.obj()
        check(obj.getValue("audioHash").str() == BASELINE_AUDIO_HASH && obj.getValue("revision").str() == BASELINE_REVISION &&
            obj.getValue("model").int() == 4 && obj.getValue("clipCount").int() == 116)
        val recipes = linkedMapOf<String, String>()
        val clips = obj.getValue("clips").arr().map { entry ->
            val clip = entry.obj()
            val id = clip.getValue("id").str()
            check(id !in recipes)
            for (mode in listOf("raw", "matched")) check(sha256(archiveAsset(root, clip.getValue(mode).str())) ==
                clip.getValue("sha256").obj().getValue(mode).str()) { "R4 $id $mode differs from its published WAV hash" }
            val recipe = archiveAsset(root, clip.getValue("recipeUrl").str())
            check(Json.parse(recipe.readText()) == clip.getValue("recipe")) { "R4 $id recipe differs from its manifest" }
            recipes[id] = sha256(recipe)
            @Suppress("UNCHECKED_CAST")
            (plain(entry) as Map<String, Any?>)
        }
        check(clips.size == 116 && audioHash(clips) == BASELINE_AUDIO_HASH)
        val snapshots = obj.getValue("sourceFingerprints").arr().map { source ->
            val fingerprint = source.obj()
            val path = fingerprint.getValue("path").str()
            val hash = sha256(archiveAsset(root, "source/$path"))
            check(hash == fingerprint.getValue("sha256").str()) { "R4 source snapshot changed: $path" }
            mapOf("path" to path, "sha256" to hash)
        }
        check(snapshots.size == 13)
        for (metadata in obj.getValue("baselines").arr()) {
            val historical = metadata.obj()
            val archived = archiveAsset(root, historical.getValue("manifest").str())
            check(sha256(archived) == historical.getValue("manifestSha256").str())
            val sourceRoot = historical["sourceRoot"]
            // Published R3 honestly reports that no complete source snapshot accompanied it.
            if (sourceRoot != null && sourceRoot != JsonValue.Null) {
                for (source in Json.parse(archived.readText()).obj().getValue("sourceFingerprints").arr()) {
                    val fingerprint = source.obj()
                    check(sha256(archiveAsset(root, "${sourceRoot.str()}/${fingerprint.getValue("path").str()}")) ==
                        fingerprint.getValue("sha256").str())
                }
            }
        }
        println("Verified the published R4 manifest, 232 WAVs, 116 recipes, 13 R4 source snapshots and historical archives.")
        return Baseline(root, manifest, clips, recipes, snapshots)
    }

    private fun preserveArchive(root: File, baseline: Baseline) {
        baseline.root.walkTopDown().forEach { file ->
            val relative = file.relativeTo(baseline.root).path
            val target = File(root, relative)
            if (file.isDirectory) check(target.mkdirs() || target.isDirectory)
            else if (file.isFile) {
                check(file.canonicalFile.toPath().startsWith(baseline.root.toPath())) { "Archive link escapes R4 root: $relative" }
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
                check(sha256(file) == sha256(target)) { "R4 $relative changed during copy" }
            }
        }
        File(baseline.root, "manifest.json").copyTo(File(root, "baseline/r4-manifest.json"), overwrite = true)
        for (source in baseline.sourceSnapshots) {
            val path = source.getValue("path")
            val target = File(root, "baseline/source/r4/$path")
            target.parentFile.mkdirs()
            archiveAsset(baseline.root, "source/$path").copyTo(target, overwrite = true)
            check(sha256(target) == source.getValue("sha256"))
        }
        check(sha256(File(root, "baseline/r4-manifest.json")) == BASELINE_MANIFEST_HASH)
    }

    private fun validateRetainedAssets(root: File, baseline: Baseline) {
        for (clip in baseline.clips) {
            val id = clip.getValue("id") as String
            val hashes = clip.getValue("sha256") as Map<*, *>
            for (mode in listOf("raw", "matched")) check(sha256(archiveAsset(root, clip.getValue(mode) as String)) == hashes[mode]) {
                "$id $mode changed in R5"
            }
            check(sha256(archiveAsset(root, clip.getValue("recipeUrl") as String)) == baseline.recipeHashes.getValue(id)) {
                "$id recipe changed in R5"
            }
        }
    }

    private fun validateComparisonReferences(manifest: JsonValue, clips: List<Map<String, Any?>>) {
        val ids = clips.map { it.getValue("id") as String }.toSet()
        for (key in listOf("comparisonPairs", "comparisonHistoryPairs")) {
            val pairs = manifest.obj().getValue(key).arr()
            check(pairs.size == 12)
            for (pair in pairs) for (side in listOf("originalId", "revisedId")) check(pair.obj().getValue(side).str() in ids)
        }
    }

    private fun validateTouch(clips: List<Map<String, Any?>>) {
        val touch = listOf(25, 60, 100).map { velocity -> clips.single { it["id"] == "transistor_velocity_$velocity" } }
        val rms = touch.map { number(nested(it, "metrics", "raw", "loudestWindowRms")) }
        check(rms.zipWithNext().all { (low, high) -> high > low }) { "TRANSISTOR raw velocity levels are not increasing: $rms" }
        check(touch.map { nested(it, "sha256", "raw") }.distinct().size == 3)
    }

    private fun baselineMetadata(baseline: Baseline): Map<String, Any?> = mapOf(
        "revision" to BASELINE_REVISION, "audioHash" to BASELINE_AUDIO_HASH,
        "manifest" to "baseline/r4-manifest.json", "manifestSha256" to BASELINE_MANIFEST_HASH,
        "sourceRoot" to "baseline/source/r4", "sourceFingerprint" to baseline.manifest.obj().getValue("sourceFingerprint"),
        "sourceFingerprintStatus" to "retained-published-metadata", "sourceSnapshotStatus" to "complete-and-verified",
        "sourceSnapshotFingerprints" to baseline.sourceSnapshots,
        "sourceNotes" to "All 13 declared R4 source fingerprints are present and verified against the exact published R4 manifest. Earlier baseline metadata is retained unchanged.",
        "verifiedWavCount" to 232, "verifiedRecipeCount" to 116, "copiedOriginalCases" to 116,
        "recipeFingerprints" to baseline.recipeHashes.map { (id, hash) -> mapOf("id" to id, "sha256" to hash) },
    )

    private fun sections(original: JsonValue): List<Any?> = original.arr().map { value ->
        @Suppress("UNCHECKED_CAST")
        LinkedHashMap(plain(value) as Map<String, Any?>).also { section ->
            if (section["id"] == "presets") section["description"] =
                "Compact Keys, Clean Combo and Garage Buzz: the same factory settings with the fuller model-5 foundation."
        }
    } + listOf(
        mapOf("id" to "depth-comparisons", "label" to "More body", "description" to "Eleven matched original / fuller TRANSISTOR pairs. Note, velocity and controls stay fixed."),
        mapOf("id" to "depth-comparison-baselines", "label" to "Original Transistor", "description" to "All 31 published model-4 recordings retained exactly, with their original recipes and review histories."),
    )

    private fun voices(original: JsonValue): List<Any?> = original.arr().map { value ->
        @Suppress("UNCHECKED_CAST")
        LinkedHashMap(plain(value) as Map<String, Any?>).also { item ->
            if (item["id"] == voice.name) item["description"] =
                "Combo-organ registrations with a fuller same-pitch foundation, immediate keyer speech, drive and vibrato. Compare original and more body."
        }
    }

    private fun sourceFingerprints(repository: File, output: File): List<Map<String, String>> = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Registers.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/RegistersTransistor.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/RegistersPatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/RegistersPresets.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/RegistersTransistorDepthAuditionGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
        "json/src/main/kotlin/com/snipsnap/json/Json.kt",
        "synth/src/test/resources/audition/registers-audition/index.html",
        "synth/src/test/resources/audition/registers-audition/styles.css",
        "synth/src/test/resources/audition/registers-audition/app.js",
    ).also {
        // R4's complete, verified snapshot is already in baseline/source/r4. The
        // current source directory must contain only this revision's declared files.
        check(File(output, "source").deleteRecursively()) { "Could not replace current source snapshots" }
    }.map { path ->
        val source = File(repository, path)
        val target = File(output, "source/$path")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        check(sha256(source) == sha256(target))
        mapOf("path" to path, "sha256" to sha256(source))
    }

    private fun loopEvidence(id: String, probe: RegistersProbe): Map<String, Any?> {
        val frames = requireNotNull(probe.loopFrames)
        val previous = requireNotNull(probe.previousCycle)
        check(frames == probe.samples.size && previous.size == frames && previous !== probe.samples)
        val measuredCycle = relativeError(previous, probe.samples)
        val measuredSeam = relativeError(previous, probe.samples, (frames - 256).coerceAtLeast(0))
        check(measuredCycle == probe.cycleError && measuredSeam == probe.seamError)
        check(measuredCycle.isFinite() && measuredCycle < CYCLE_LIMIT && measuredSeam.isFinite() && measuredSeam < SEAM_LIMIT) {
            "$id: independently synthesized cycles failed (seam=$measuredSeam, cycle=$measuredCycle)"
        }
        return mapOf(
            "regionFrames" to frames, "regionSeconds" to frames.toDouble() / Dsp.RATE,
            "playbackRepetitions" to 3, "seamError" to measuredSeam, "cycleError" to measuredCycle,
            "seamPass" to true, "cyclePass" to true,
            "independentPreviousCycle" to true,
            "independentCycleNotes" to "Measured from adjacent regions of continuous synthesis after preroll, before any playback copying.",
            "previousCycleFloatSha256" to floatHash(previous), "currentCycleFloatSha256" to floatHash(probe.samples),
            "wrapStep" to abs(probe.samples.first().toDouble() - probe.samples.last()),
            "wrapSlopeDifference" to abs((probe.samples[1].toDouble() - probe.samples[0]) -
                (probe.samples.last().toDouble() - probe.samples[probe.samples.lastIndex - 1])),
        )
    }

    private fun relativeError(a: FloatArray, b: FloatArray, start: Int = 0): Double {
        var error = 0.0
        var energy = 0.0
        for (i in start until a.size) {
            val difference = a[i] - b[i].toDouble()
            error += difference * difference
            energy += a[i].toDouble() * a[i]
        }
        return error / energy.coerceAtLeast(1e-30)
    }


    private fun validateSnip(snip: Snip, id: String) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 2)
        check(snip.samples.all { it.isFinite() } && snip.peak() > 1e-6f) { "$id: invalid or silent render" }
    }

    private fun validateExport(file: File, expected: Snip): Snip {
        val bytes = file.readBytes()
        check((bytes[20].toInt() and 255) == 1 && (bytes[21].toInt() and 255) == 0 &&
            (bytes[34].toInt() and 255) == 24 && (bytes[35].toInt() and 255) == 0)
        val decoded = WavReader.read(bytes)
        validateSnip(decoded, file.name)
        check(decoded.frameCount == expected.frameCount && decoded.peak() <= .990001f)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - expected.samples[it]) <= 1.3e-7f }) {
            "${file.name}: PCM roundtrip exceeded quantization tolerance"
        }
        return decoded
    }

    private fun metrics(snip: Snip): Map<String, Any> {
        var sum = 0.0
        var energy = 0.0
        var differences = 0.0
        for (i in snip.samples.indices) {
            val sample = snip.samples[i].toDouble()
            sum += sample
            energy += sample * sample
            if (i > 0) { val difference = sample - snip.samples[i - 1]; differences += difference * difference }
        }
        val rms = sqrt(energy / snip.frameCount)
        return mapOf("peak" to snip.peak().toDouble(), "rms" to rms,
            "loudestWindowRms" to Loudness.of(snip).toDouble(), "dcMean" to sum / snip.frameCount,
            "firstDifferenceRms" to sqrt(differences / (snip.frameCount - 1)),
            "crestDb" to 20 * log10(snip.peak().toDouble() / rms.coerceAtLeast(1e-20)))
    }


    private fun archiveAsset(root: File, path: String): File {
        val file = File(root, path).canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file.isFile) { "Invalid archived asset $path" }
        return file
    }
    private fun audioHash(clips: List<Map<String, Any?>>): String = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
        val hashes = it.getValue("sha256") as Map<*, *>
        "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
    }.toByteArray(Charsets.UTF_8))
    private fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return sha256(bytes.array())
    }
    private fun nested(value: Map<String, Any?>, vararg keys: String): Any? = keys.fold(value as Any?) { node, key ->
        val map = node as Map<*, *>
        (map.entries.firstOrNull { it.key == key } ?: error("Missing manifest field $key")).value
    }
    private fun number(value: Any?): Double = (value as Number).toDouble()
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun repositoryRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun plain(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { plain(it.value) }
        is JsonValue.Arr -> value.items.map(::plain)
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported REGISTERS manifest value ${value.javaClass}")
    }
}
