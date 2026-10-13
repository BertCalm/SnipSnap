package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A compact R1/R3 listening comparison. Published R1 inputs are verified and copied, never
 * rewritten in place. R3 retains the first renderer’s sea gesture and creature scene, with
 * a small adjustment to modal radiation. Model 3 is explicitly selected for every new render.
 * The page is maintained separately and consumes the paired manifest produced here.
 */
object OspreyGentleAuditionGenerator {
    private const val HISTORICAL_COMMIT = "5e2233d47f921142ee84225823ac23758c9825f8"
    private const val RATE = 44_100
    private const val CHANNELS = 2
    private const val TARGET = .12f
    private const val CEILING = .90f
    // The existing writer uses 32767 and the reader divides by 32768. Its decoded 0.12
    // reference is about 0.1199963; this sub-LSB tolerance preserves the published bytes.
    private const val ORIGINAL_PCM_RMS_TOLERANCE = 8e-6f
    private const val EXCERPT_SECONDS = 2.4f
    private const val PREVIEW_FADE_SECONDS = .01f
    private const val PREVIEW_GAP_SECONDS = .12f
    private val shores = listOf("Atlantic", "Nordic", "Mediterranean", "Pacific", "Southern")
    private val orientations = listOf(
        "Compare the familiar warm attack and continuing swell with its subtly rebalanced body.",
        "Compare the original soft sea contour with a small change in the resonant body color.",
        "Compare the original contact and creature scene with the gently rebalanced modal body.",
        "Compare the familiar rolling water and ringing body without changing their timing.",
        "Compare the original swell and creature scene with the gently rebalanced resonant body.",
    )

    private data class OspreyGentleSource(
        val patch: OspreyPatch,
        val raw: Snip,
        val sourceCommit: String?,
        val original: Map<String, JsonValue>? = null,
        val originalRawFile: File? = null,
        val originalMatchedFile: File? = null,
        val originalPatchFile: File? = null,
        val probe: Osprey.Probe? = null,
    )
    private data class OspreyGentleSummary(
        val raw: Snip,
        val matchedInput: Snip,
        val segments: List<Map<String, Any?>>,
        val excerptGains: List<Float>,
        val sourceClipIds: List<String>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.filter { it.startsWith("--") }.all { it == "--include-bodies" }) { "Unknown OSPREY gentle refinement option." }
        require(args.count { it == "--include-bodies" } <= 1) { "Duplicate --include-bodies option." }
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 2) { "Supply an output directory, optional preserved R1 directory, and optional --include-bodies." }
        val output = File(positional.getOrNull(0) ?: "../testkit/osprey-gentle-audition").canonicalFile
        val baseline = File(positional.getOrNull(1) ?: "../testkit/osprey-audition").canonicalFile
        require(!output.toPath().startsWith(baseline.toPath()) && !baseline.toPath().startsWith(output.toPath())) {
            "Keep the preserved R1 library and gentle-refinement output in separate directories."
        }
        val baselineManifestFile = File(baseline, "manifest.json")
        require(baselineManifestFile.isFile) {
            "The original published R1 pack at $HISTORICAL_COMMIT is required. Supply its preserved directory as the second positional argument (or use -PospreyBaselineDir with the Gradle task); do not regenerate or overwrite the original pack."
        }
        val originalManifest = Json.parse(baselineManifestFile.readText()).obj()
        require(originalManifest.getValue("engine").str() == "OSPREY") { "The baseline is not OSPREY." }
        require(originalManifest.getValue("sourceCommit").str() == HISTORICAL_COMMIT) { "Use the preserved published R1 library, not a current rerender." }
        require(originalManifest.getValue("renderModel").int() == 1) { "The original library must describe model 1." }
        val baselineRows = originalManifest.getValue("clips").arr().associate { it.obj().getValue("id").str() to it.obj() }
        require(baselineRows.size == 46) { "The published R1 baseline has 46 clips." }
        val repository = repositoryRoot()
        val currentCommit = git(repository, "rev-parse", "HEAD")
        val workingTreeDirty = git(repository, "status", "--porcelain")?.isNotBlank() ?: true
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Osprey.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyV1.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyV3.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyMaterial.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyShore.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyPresets.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OspreyGentleAuditionGenerator.kt",
        ).associateWith { path -> sha256(File(repository, path)) }
        val legacySourceHash = sourceFiles.getValue("synth/src/main/kotlin/com/snipsnap/synth/OspreyV1.kt")
        output.mkdirs()
        val clips = linkedMapOf<String, Map<String, Any?>>()
        val pairs = mutableListOf<Map<String, Any?>>()
        var renderCount = 0
        var renderNanos = 0L
        val started = System.nanoTime()

        fun checkedAsset(row: Map<String, JsonValue>, pathKey: String, hashKey: String): File {
            val asset = File(baseline, row.getValue(pathKey).str()).canonicalFile
            require(asset.toPath().startsWith(baseline.toPath()) && asset.isFile) { "Missing or unsafe original $pathKey." }
            require(sha256(asset) == row.getValue(hashKey).str()) { "Original $pathKey no longer matches its published SHA-256." }
            return asset
        }

        fun originalSource(id: String): OspreyGentleSource {
            val row = baselineRows.getValue(id)
            require(row.getValue("sourceCommit").str() == HISTORICAL_COMMIT && row.getValue("renderModel").int() == 1)
            val rawFile = checkedAsset(row, "rawFile", "rawSha256")
            val matchedFile = checkedAsset(row, "file", "matchedSha256")
            val patchFile = checkedAsset(row, "patchFile", "patchSha256")
            val patch = OspreyPatch.fromJsonText(patchFile.readText())
            require(patch.modelVersion == 1 && patch.midi == 60 && patch.seed == 731 && patch.durationSeconds == 8f && patch.velocity == 1f) {
                "$id does not contain the published common-root comparison recipe."
            }
            val raw = WavReader.read(rawFile)
            validate(raw, id)
            require(raw.samples.size == RATE * CHANNELS * 8) { "$id is not the full eight-second original." }
            return OspreyGentleSource(patch, raw, HISTORICAL_COMMIT, row, rawFile, matchedFile, patchFile)
        }

        fun renderSource(patch: OspreyPatch): OspreyGentleSource {
            val before = System.nanoTime()
            val probe = Osprey.probe(
                patch.voice, patch.macros, midi = patch.midi, velocity = patch.velocity,
                seed = patch.seed, durationSeconds = patch.durationSeconds, modelVersion = patch.modelVersion,
            )
            renderNanos += System.nanoTime() - before
            renderCount++
            require(probe.metrics.nonFiniteRecoveries == 0) { "${patch.name}: numerical recovery occurred." }
            val raw = Snip(probe.samples, CHANNELS, RATE)
            validate(raw, patch.name)
            return OspreyGentleSource(patch, raw, currentCommit, probe = probe)
        }

        fun writeSource(
            id: String, pairId: String, title: String, description: String, section: String,
            source: OspreyGentleSource, commonRms: Float,
        ): Map<String, Any?> {
            require(id !in clips) { "Duplicate gentle-refinement clip $id." }
            val filePath = "audio/$id.wav"
            val rawPath = "audio/${id}_raw.wav"
            val patchPath = "patches/$id.json"
            val rawFile = File(output, rawPath)
            val matchedFile = File(output, filePath)
            val patchFile = File(output, patchPath)
            rawFile.parentFile.mkdirs(); patchFile.parentFile.mkdirs()
            source.originalRawFile?.copyTo(rawFile, overwrite = true)
                ?: WavWriter.write(rawFile, source.raw, WavWriter.BitDepth.PCM_16)
            val originalMatched = source.originalMatchedFile?.let(WavReader::read)
            val copyMatched = originalMatched != null && abs(listeningRms(originalMatched.samples) - commonRms) <= ORIGINAL_PCM_RMS_TOLERANCE && peak(originalMatched.samples) <= CEILING + 1e-6f
            val gain: Float
            if (copyMatched) {
                source.originalMatchedFile!!.copyTo(matchedFile, overwrite = true)
                gain = source.original!!.getValue("gain").num().toFloat()
            } else {
                gain = commonRms / listeningRms(source.raw.samples)
                val samples = FloatArray(source.raw.samples.size) { source.raw.samples[it] * gain }
                require(peak(samples) <= CEILING + 1e-6f) { "$id listening match would exceed the peak ceiling." }
                WavWriter.write(matchedFile, Snip(samples, CHANNELS, RATE), WavWriter.BitDepth.PCM_16)
            }
            source.originalPatchFile?.copyTo(patchFile, overwrite = true)
                ?: patchFile.writeText(source.patch.toJsonText() + "\n")
            val exportedRaw = WavReader.read(rawFile)
            val matched = WavReader.read(matchedFile)
            val patch = source.patch
            val probe = source.probe
            val historical = source.original
            val row = linkedMapOf<String, Any?>(
                "id" to id, "comparisonPairId" to pairId, "title" to title,
                "description" to description, "section" to section, "component" to "full",
                "phase" to if (patch.modelVersion == 1) "original" else "revision",
                "renderModel" to patch.modelVersion, "voice" to patch.voiceName,
                "shore" to patch.macros.getValue("SHORE") * 4f,
                "shoreName" to shoreName(patch.macros.getValue("SHORE")),
                "sea" to patch.macros.getValue("SEA"), "seaName" to seaName(patch.macros.getValue("SEA")),
                "midi" to patch.midi, "velocity" to patch.velocity, "seed" to patch.seed, "macros" to patch.macros,
                "file" to filePath, "rawFile" to rawPath, "patchFile" to patchPath,
                "duration" to matched.samples.size.toDouble() / CHANNELS / RATE,
                "sampleRate" to RATE, "channels" to CHANNELS, "loop" to false, "excerpt" to false,
                "peak" to peak(matched.samples), "rms" to rms(matched.samples), "listeningRms" to listeningRms(matched.samples),
                "rawPeak" to peak(exportedRaw.samples), "rawRms" to rms(exportedRaw.samples), "rawListeningRms" to listeningRms(exportedRaw.samples),
                "gain" to gain, "gainBasis" to "raw-source", "pairListeningTarget" to commonRms,
                "waveform" to waveform(matched.samples), "waveformKind" to "relative-channel-peak",
                "waveformPeakReference" to peak(matched.samples),
                "sourceCommit" to source.sourceCommit, "renderSourceCommit" to source.sourceCommit,
                "exportSourceCommit" to currentCommit,
                "workingTreeDirty" to if (historical != null) false else workingTreeDirty,
                "sourceFilesSha256" to if (historical != null) native(originalManifest.getValue("sourceFilesSha256")) else sourceFiles,
                "currentModel1SourceSha256" to if (patch.modelVersion == 1) legacySourceHash else null,
                "historicalEngineSourceCommit" to if (patch.modelVersion == 1) HISTORICAL_COMMIT else null,
                "matchedSha256" to sha256(matchedFile), "rawSha256" to sha256(rawFile), "patchSha256" to sha256(patchFile),
                "baselineOriginal" to historical?.let {
                    linkedMapOf("id" to it.getValue("id").str(), "sourceCommit" to HISTORICAL_COMMIT,
                        "file" to it.getValue("file").str(), "rawFile" to it.getValue("rawFile").str(),
                        "matchedSha256" to it.getValue("matchedSha256").str(), "rawSha256" to it.getValue("rawSha256").str(),
                        "patchSha256" to it.getValue("patchSha256").str(), "matchedCopiedByteForByte" to copyMatched,
                        "rawCopiedByteForByte" to true, "listeningRms" to it.getValue("listeningRms").num())
                },
                "events" to (probe?.let(::callEvents) ?: historical?.get("events")?.let(::native)),
                "contacts" to probe?.contactEvents?.map { linkedMapOf(
                    "timeSeconds" to it.timeSeconds, "kind" to it.kind.name, "material" to it.material,
                    "activity" to it.activity, "energy" to it.energy, "widthMs" to it.widthMs,
                ) },
                "weatherWeights" to probe?.weatherWeights?.toList(),
                "rootHz" to probe?.rootHz, "modeHz" to probe?.modeHz?.toList(),
                "diagnostics" to (probe?.let(::diagnostics) ?: historical?.get("diagnostics")?.let(::native)),
                "provenanceNote" to when {
                    historical != null -> "Published R1 raw audio and patch copied byte for byte. Matched playback is copied unless a lower common pair target requires a separate listening copy. The preserved original library is unchanged."
                    patch.modelVersion == 1 -> "New diagnostic render of frozen model 1. It was not a clip in the published R1 library; the historical engine commit and current frozen-source hash are recorded separately."
                    else -> "New CPU-engine render with model 3 explicitly selected. The owner's listening verdict remains open."
                },
            )
            clips[id] = row
            println("$id · model ${patch.modelVersion} · ${fmt(listeningRms(matched.samples))} listening RMS · copied original match $copyMatched")
            return row
        }

        fun addPair(
            id: String, title: String, description: String, section: String,
            before: OspreyGentleSource, after: OspreyGentleSource,
        ) {
            require(before.patch.modelVersion == 1 && after.patch.modelVersion == 3) { "$id must compare models 1 and 3." }
            require(before.patch.macros == after.patch.macros && before.patch.midi == after.patch.midi &&
                before.patch.velocity == after.patch.velocity && before.patch.seed == after.patch.seed &&
                before.patch.durationSeconds == after.patch.durationSeconds) { "$id changes recipe fields beside the model." }
            val common = commonTarget(before.raw.samples, after.raw.samples)
            val beforeId = "r1_$id"; val afterId = "r3_$id"
            writeSource(beforeId, id, "$title · Original R1", description, section, before, common)
            writeSource(afterId, id, "$title · Gentle R3", description, section, after, common)
            pairs += linkedMapOf("id" to id, "title" to title, "description" to description, "section" to section,
                "beforeId" to beforeId, "afterId" to afterId, "excerpt" to false, "listeningTarget" to common)
        }

        for ((index, name) in shores.withIndex()) {
            val id = "shore_${name.lowercase(Locale.ROOT)}_rough"
            val before = originalSource(id)
            val after = renderSource(before.patch.copy(name = "$name Rough · Gentle R3", modelVersion = 3))
            addPair(id, "$name · Rough", "${orientations[index]} The eight-second gesture, C4 root, seed, macros and dry SPACE are identical before and after; compare the attack and continuing body without expecting a new articulation.",
                "comparisonshores", before, after)
        }
        for (name in shores.take(3)) {
            for (state in listOf("calm", "storm")) {
                val id = "shore_${name.lowercase(Locale.ROOT)}_$state"
                val before = originalSource(id)
                val after = renderSource(before.patch.copy(name = "$name $state · Gentle R3", modelVersion = 3))
                addPair(id, "$name · ${state.replaceFirstChar { it.uppercaseChar() }}",
                    "At ${fmt(before.patch.macros.getValue("SEA"))} SEA, the original ocean forcing, creature calls and gesture timing are retained. Compare only the small change in body balance through the complete sea state.",
                    "weather", before, after)
            }
        }

        if ("--include-bodies" in args) {
            val archivedBodies = mapOf(0 to "control_depth_000", 1 to "depth_nordic_000", 4 to "depth_southern_000")
            for ((index, name) in shores.withIndex()) {
                val rough = originalSource("shore_${name.lowercase(Locale.ROOT)}_rough").patch
                val legacyPatch = rough.copy(name = "$name body · R1", macros = rough.macros + ("DEPTH" to 0f), modelVersion = 1)
                val before = archivedBodies[index]?.let(::originalSource) ?: renderSource(legacyPatch)
                require(before.patch.macros.size == 6 && before.patch.macros == legacyPatch.macros)
                val after = renderSource(before.patch.copy(name = "$name body · Gentle R3", modelVersion = 3))
                addPair("body_${name.lowercase(Locale.ROOT)}", "$name · creatures muted",
                    "DEPTH zero mutes creatures in both models. Compare the small modal body adjustment with the same original wave forcing. This control also changes modal radiation and sympathetic decay; it is not a source tap.",
                    "bodies", before, after)
            }
        }

        fun buildSummary(model: Int, recipeIds: List<String>): OspreyGentleSummary {
            val sourceIds = recipeIds.map { "r${model}_$it" }
            val raws = sourceIds.map { WavReader.read(File(output, clips.getValue(it).getValue("rawFile") as String)) }
            val matches = sourceIds.map { WavReader.read(File(output, clips.getValue(it).getValue("file") as String)) }
            val excerptFrames = (EXCERPT_SECONDS * RATE).roundToInt()
            val gapFrames = (PREVIEW_GAP_SECONDS * RATE).roundToInt()
            val segments = sourceIds.mapIndexed { index, sourceId ->
                val startFrame = index * (excerptFrames + gapFrames)
                linkedMapOf<String, Any?>("label" to (clips.getValue(sourceId).getValue("title") as String).substringBefore(" · Original").substringBefore(" · Gentle"),
                    "startSeconds" to startFrame.toDouble() / RATE, "endSeconds" to (startFrame + excerptFrames).toDouble() / RATE,
                    "sourceClipId" to sourceId, "sourceStartSeconds" to 0, "sourceEndSeconds" to EXCERPT_SECONDS,
                    "sourceRawSha256" to clips.getValue(sourceId).getValue("rawSha256"),
                    "sourceMatchedSha256" to clips.getValue(sourceId).getValue("matchedSha256"))
            }
            return OspreyGentleSummary(
                Snip(lineup(raws), CHANNELS, RATE), Snip(lineup(matches), CHANNELS, RATE), segments,
                sourceIds.map { (clips.getValue(it).getValue("gain") as Number).toFloat() }, sourceIds,
            )
        }

        fun writeSummary(
            id: String, pairId: String, title: String, description: String, model: Int,
            summary: OspreyGentleSummary, common: Float,
        ) {
            val gain = common / listeningRms(summary.matchedInput.samples)
            val samples = FloatArray(summary.matchedInput.samples.size) { summary.matchedInput.samples[it] * gain }
            require(peak(samples) <= CEILING + 1e-6f)
            val filePath = "audio/$id.wav"; val rawPath = "audio/${id}_raw.wav"
            val matchedFile = WavWriter.write(File(output, filePath), Snip(samples, CHANNELS, RATE), WavWriter.BitDepth.PCM_16)
            val rawFile = WavWriter.write(File(output, rawPath), summary.raw, WavWriter.BitDepth.PCM_16)
            val matched = WavReader.read(matchedFile); val raw = WavReader.read(rawFile)
            clips[id] = linkedMapOf(
                "id" to id, "comparisonPairId" to pairId, "title" to title, "description" to description,
                "section" to "summary", "phase" to if (model == 1) "original" else "revision", "renderModel" to model,
                "voice" to "SWELL", "component" to "lineup", "excerpt" to true, "loop" to false,
                "shore" to null, "shoreName" to "Lineup", "sea" to null, "seaName" to "Lineup",
                "midi" to 60, "velocity" to 1f, "seed" to 731, "macros" to emptyMap<String, Float>(),
                "file" to filePath, "rawFile" to rawPath, "patchFile" to null,
                "duration" to samples.size.toDouble() / CHANNELS / RATE, "sampleRate" to RATE, "channels" to CHANNELS,
                "peak" to peak(matched.samples), "rms" to rms(matched.samples), "listeningRms" to listeningRms(matched.samples),
                "rawPeak" to peak(raw.samples), "rawRms" to rms(raw.samples), "rawListeningRms" to listeningRms(raw.samples),
                "gain" to gain, "gainBasis" to "post-excerpt-listening-match", "pairListeningTarget" to common,
                "perExcerptGains" to summary.excerptGains, "segments" to summary.segments, "sourceClipIds" to summary.sourceClipIds,
                "excerptSeconds" to EXCERPT_SECONDS, "fadeSeconds" to PREVIEW_FADE_SECONDS, "gapSeconds" to PREVIEW_GAP_SECONDS,
                "waveform" to waveform(matched.samples), "waveformKind" to "relative-channel-peak", "waveformPeakReference" to peak(matched.samples),
                "matchedSha256" to sha256(matchedFile), "rawSha256" to sha256(rawFile),
                "sourceCommit" to if (model == 1) HISTORICAL_COMMIT else currentCommit,
                "renderSourceCommit" to if (model == 1) HISTORICAL_COMMIT else currentCommit,
                "exportSourceCommit" to currentCommit, "workingTreeDirty" to workingTreeDirty,
                "sourceFilesSha256" to if (model == 1) native(originalManifest.getValue("sourceFilesSha256")) else sourceFiles,
                "montageSourceFilesSha256" to sourceFiles, "currentModel1SourceSha256" to if (model == 1) legacySourceHash else null,
                "provenanceNote" to "Preview montage of actual full CPU-rendered comparison WAVs. Identical 0–2.4-second source windows use 10 ms edge fades and 120 ms silent gaps. Matched excerpts inherit their full comparison's gain before a final shared-target lineup match; Raw excerpts retain the source amplitude. This montage has no single saved patch and does not replace the full eight-second renders.",
            )
        }

        val summaryRecipes = listOf(
            Triple("summary_shores", "Five shores · Rough", shores.map { "shore_${it.lowercase(Locale.ROOT)}_rough" }),
            Triple("summary_weather", "Atlantic · Calm → Rough → Storm", listOf("calm", "rough", "storm").map { "shore_atlantic_$it" }),
        )
        for ((id, title, recipes) in summaryRecipes) {
            val before = buildSummary(1, recipes); val after = buildSummary(3, recipes)
            val common = commonTarget(before.matchedInput.samples, after.matchedInput.samples)
            val description = "A short orientation made from identical 0–2.4-second excerpts of the full paired renders, separated by 120 ms silence with 10 ms preview fades. Use the full cards to judge behavior through the complete eight-second gesture."
            writeSummary("r1_$id", id, "$title · Original R1", description, 1, before, common)
            writeSummary("r3_$id", id, "$title · Gentle R3", description, 3, after, common)
            pairs += linkedMapOf("id" to id, "title" to title, "description" to description, "section" to "summary",
                "beforeId" to "r1_$id", "afterId" to "r3_$id", "excerpt" to true, "listeningTarget" to common)
        }

        val includeBodies = "--include-bodies" in args
        require(pairs.size == if (includeBodies) 18 else 13)
        require(clips.size == pairs.size * 2)
        val sections = mutableListOf(
            section("summary", "Two short lineups", "Start with the excerpted shore and sea-state lineups, then use the full comparison cards below. Every A/B switch changes only the render model; R3 keeps the original gesture and creature scene."),
            section("comparisonshores", "Five shores · same note", "Five full rough-sea gestures at C4, seed 731 and the same controls. Compare the familiar attack and the small change in continuing body color."),
            section("weather", "Calm and storm", "Atlantic, Nordic and Mediterranean at calm and storm. Their rough versions are above; each model has the same full eight-second comparison recipe."),
        )
        if (includeBodies) sections += section("bodies", "Diagnostic · creatures muted", "Five coast bodies with DEPTH zero in both models. These let the body adjustment be heard without creature forms; both models retain the original water forcing and timing.")
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 2, "engine" to "OSPREY", "title" to "OSPREY · The same sea, a little more color",
            "description" to "The original sound was positively received; the owner wanted the voices a little more distinct. R2 was rejected as harsh and for losing the original character. R3 returns to the original sea, calls, envelopes and defaults, with only a small change to coast modal body balance. Its listening verdict remains open.",
            "renderModel" to 3, "sourceCommit" to currentCommit, "workingTreeDirty" to workingTreeDirty,
            "sourceFilesSha256" to sourceFiles, "currentModel1SourceSha256" to legacySourceHash,
            "baseline" to linkedMapOf("sourceCommit" to HISTORICAL_COMMIT, "manifestSha256" to sha256(baselineManifestFile),
                "clipCount" to 46, "sourceFilesSha256" to native(originalManifest.getValue("sourceFilesSha256")),
                "policy" to "The published R1 library is read-only. Original raw and patch bytes are copied with verified hashes; original matched files are copied unless a lower common A/B listening target requires a distinct playback copy."),
            "reviewStatus" to linkedMapOf("original" to "Positive sound; small voice distinction requested",
                "rejectedRevision" to "R2 rejected: harsh and lost the original character",
                "revision" to "Awaiting owner listening review", "concept" to "Original sound positively received",
                "acceptedModels" to emptyList<Int>()),
            "sampleRate" to RATE, "channels" to CHANNELS, "bitDepth" to 16,
            "clipCount" to clips.size, "comparisonPairCount" to pairs.size, "audioFileCount" to clips.size * 2,
            "listeningTarget" to TARGET, "listeningPeakCeiling" to CEILING, "masterVolume" to .85f,
            "playbackInitiallyEnabled" to false, "audioStartsOff" to true,
            "loudness" to "Each full A/B pair uses the same achievable loudest-200-ms stereo RMS, capped at 0.12 and 0.90 peak. Matched WAVs already contain their gain; playback applies only the master volume. Raw WAVs retain engine amplitude. Excerpt lineups compose level-balanced full clips and record their two-stage gain separately.",
            "seedPolicy" to "Every full recipe retains MIDI 60, seed 731, velocity 1 and eight seconds. SHORE and SEA identify the comparison; all other original controls are preserved.",
            "loopPolicy" to "All renders and excerpt montages are finite. HOLD extends the source gesture; no seamless loop is claimed.",
            "summaryPolicy" to "Identical 0–2.4-second windows from full comparison WAVs, with 10 ms edge fades and 120 ms silent gaps. The complete renders remain unchanged.",
            "shoreNames" to shores, "sections" to sections, "comparisonPairs" to pairs, "clips" to clips.values.toList(),
            "renderCount" to renderCount, "renderMilliseconds" to renderNanos / 1_000_000.0,
            "elapsedMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
        )
        File(output, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        println("OSPREY gentle refinement: ${pairs.size} paired cards, ${clips.size} logical clips and ${clips.size * 2} WAVs at ${output.path}.")
    }

    private fun validate(snip: Snip, name: String) {
        require(snip.sampleRate == RATE && snip.channels == CHANNELS && snip.samples.isNotEmpty() && snip.samples.size % CHANNELS == 0)
        require(snip.samples.all { it.isFinite() }) { "$name has non-finite audio." }
        require(peak(snip.samples) <= 1f) { "$name raw audio would clip in PCM." }
        require(listeningRms(snip.samples) > 1e-8f) { "$name is silent." }
    }
    private fun commonTarget(before: FloatArray, after: FloatArray): Float = minOf(
        TARGET, CEILING * listeningRms(before) / peak(before).coerceAtLeast(1e-9f),
        CEILING * listeningRms(after) / peak(after).coerceAtLeast(1e-9f),
    )
    private fun lineup(sources: List<Snip>): FloatArray {
        val frames = (EXCERPT_SECONDS * RATE).roundToInt()
        val gap = (PREVIEW_GAP_SECONDS * RATE).roundToInt()
        val fade = (PREVIEW_FADE_SECONDS * RATE).roundToInt()
        val output = FloatArray((sources.size * frames + (sources.size - 1) * gap) * CHANNELS)
        for ((index, source) in sources.withIndex()) {
            require(source.sampleRate == RATE && source.channels == CHANNELS && source.samples.size >= frames * CHANNELS)
            val offset = index * (frames + gap) * CHANNELS
            for (frame in 0 until frames) {
                val envelope = minOf(1f, frame.toFloat() / fade, (frames - 1 - frame).toFloat() / fade)
                for (channel in 0 until CHANNELS) output[offset + frame * CHANNELS + channel] = source.samples[frame * CHANNELS + channel] * envelope
            }
        }
        return output
    }
    private fun callEvents(probe: Osprey.Probe): List<Map<String, Any?>> = probe.callEvents.map { linkedMapOf(
        "timeSeconds" to it.timeSeconds, "kind" to it.kind.name, "shore" to it.shore,
        "activity" to it.activity, "strength" to it.strength,
    ) }
    private fun diagnostics(probe: Osprey.Probe): Map<String, Any?> = linkedMapOf(
        "rawPeak" to probe.metrics.rawPeak, "finalModalEnergy" to probe.metrics.finalModalEnergy,
        "peakModalEnergy" to probe.metrics.peakModalEnergy, "nonFiniteRecoveries" to probe.metrics.nonFiniteRecoveries,
        "excitationStopSeconds" to probe.metrics.excitationStopSeconds, "injectedEnergy" to probe.metrics.injectedEnergy,
        "pressureFrames" to probe.metrics.pressureFrames,
    )
    private fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
    private fun rms(samples: FloatArray): Float = if (samples.isEmpty()) 0f else sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()
    private fun listeningRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        val window = minOf(samples.size, RATE / 5 * CHANNELS)
        val hop = RATE / 20 * CHANNELS
        var sum = 0.0
        for (index in 0 until window) sum += samples[index].toDouble() * samples[index]
        var best = sum / window
        var start = 0
        while (start + window + hop <= samples.size) {
            for (index in start until start + hop) sum -= samples[index].toDouble() * samples[index]
            for (index in start + window until start + window + hop) sum += samples[index].toDouble() * samples[index]
            start += hop
            best = maxOf(best, sum / window)
        }
        return sqrt(best.coerceAtLeast(0.0)).toFloat()
    }
    private fun waveform(samples: FloatArray): List<Float> {
        val peak = peak(samples).coerceAtLeast(1e-9f)
        return (0 until 64).map { bin ->
            val start = (samples.size.toLong() * bin / 64).toInt()
            val end = (samples.size.toLong() * (bin + 1) / 64).toInt()
            var highest = 0f
            for (index in start until end) highest = maxOf(highest, abs(samples[index]))
            highest / peak
        }
    }
    private fun shoreName(normalized: Float): String = shores[(normalized * 4f).roundToInt().coerceIn(0, 4)]
    private fun seaName(value: Float): String = if (value < .3f) "Calm" else if (value < .7f) "Rough" else "Storm"
    private fun section(id: String, title: String, description: String): Map<String, Any?> = linkedMapOf("id" to id, "title" to title, "description" to description)
    private fun repositoryRoot(): File {
        var file = File(".").canonicalFile
        while (!File(file, ".git").exists()) file = file.parentFile ?: error("Run inside the SnipSnap checkout.")
        return file
    }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
    private fun native(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { native(it.value) }
        is JsonValue.Arr -> value.items.map(::native)
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported OSPREY gentle refinement manifest value ${value.javaClass.name}.")
    }
}
