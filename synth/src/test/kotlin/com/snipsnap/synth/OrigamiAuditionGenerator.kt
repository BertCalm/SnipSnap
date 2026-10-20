package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A small, dry first-listen experiment. No recipe is accepted by its measurements alone. */
object OrigamiAuditionGenerator {
    private const val MATCHED_TARGET = .12f
    private const val MATCHED_CEILING = .90f
    private const val VELOCITY = .85f
    private const val SEED = 20261012L
    // Pin published evidence, not an arbitrary archive claiming the same source identity.
    private const val R1_MANIFEST_SHA256 = "67d72372b5ebb25f7b79a47e5ed034f4fb7aab08e899751f2a27409ed240f39a"
    private const val R2_MANIFEST_SHA256 = "9bcfb05b22fab5412141fe8dee1e5d251a7ead8f8ff04d44bf28459c2e227820"
    private const val R3_MANIFEST_SHA256 = "f021aee3be6a60893835320d8354a1e9b06b9fb56cb203d78046970d914b654e"
    private val baselineSourceHashes = mapOf(
        1 to "82796af5b591c0db86cd4684a6c833e0735b968789d4b6c28e4d8a9931e03871",
        2 to "432900163cecf7858bd8241505e665352c88518d12f794874a8e59e0dd6e2a60",
        3 to "91a9f5decb227d64f5bfd93312867801e7e79e8a5574ed8cb7effbc35ef67bcd",
    )
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)

    private data class Case(
        val id: String, val category: String, val group: String, val groupTitle: String,
        val title: String, val description: String, val voice: OrigamiVoice,
        val macros: Map<String, Float>, val midi: Int = 60,
        val diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED,
        val featured: Boolean = false, val macro: String? = null, val value: Float? = null,
        val sharedMatchedReference: String? = null,
    )

    private data class Rendered(val case: Case, val raw: Snip, val milliseconds: Double)
    private data class ComparisonBaseline(
        val directory: File, val manifest: Map<String, Any?>, val clips: List<Map<String, Any?>>,
        val manifestSha256: String,
    ) {
        val modelVersion: Int get() = (manifest.getValue("modelVersion") as Number).toInt()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1 && args.filter { it.startsWith("--") }.all {
            it in setOf("--quick", "--refresh-page", "--embed-audio") || it.startsWith("--comparison-baseline=")
        }) { "Supply one output directory, optionally --quick, --refresh-page, --embed-audio or --comparison-baseline=<directory>" }
        require(args.count { it.startsWith("--comparison-baseline=") } <= 1) { "Supply only one comparison baseline" }
        val root = File(positional.firstOrNull() ?: "../testkit/origami-audition")
        require(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        if ("--refresh-page" in args) {
            require(File(root, "manifest.json").isFile) { "Render the audition before refreshing its page" }
            writePages(root, File(root, "manifest.json").readText(), "--embed-audio" in args)
            println("Refreshed ORIGAMI listening page; audio and manifest unchanged")
            return
        }
        val comparisonBaseline = args.firstOrNull { it.startsWith("--comparison-baseline=") }
            ?.substringAfter('=')?.let { prepareComparisonBaseline(root, it) }
        val quick = "--quick" in args
        val cases = cases(quick)
        check(cases.map { it.id }.distinct().size == cases.size)
        val sourceFiles = File("src/main/kotlin/com/snipsnap/synth").listFiles()
            .orEmpty().filter { it.name.startsWith("Origami") && it.extension == "kt" }.sortedBy { it.name }
        require(sourceFiles.isNotEmpty()) { "Run the generator with synth as its working directory" }
        val sourceHashes = sourceFiles.associate { it.name to sha256(it.readBytes()) }
        val sourceHash = sha256(sourceHashes.entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray())
        val recipeHash = sha256(buildString {
            append("model:${Origami.MODEL_VERSION}\n")
            comparisonBaseline?.let { append("comparisonBaseline:${it.manifestSha256}\n") }
            append(cases.joinToString("\n") {
                "${it.id}|${it.voice}|${it.midi}|${it.macros.toSortedMap()}|$VELOCITY|$SEED|${it.diagnostic}|${it.sharedMatchedReference}"
            })
        }.toByteArray())
        val round = "origami-v${Origami.MODEL_VERSION}-${sourceHash.take(12)}-${recipeHash.take(12)}"
        fun verifySources() = check(sourceFiles.all { sha256(it.readBytes()) == sourceHashes.getValue(it.name) }) {
            "ORIGAMI source changed during generation; regenerate the complete pack"
        }
        val began = System.nanoTime()
        val rendered = cases.mapIndexed { index, case ->
            verifySources()
            val before = System.nanoTime()
            val raw = Snip(Origami.renderRaw(case.voice, case.macros, VELOCITY, case.midi, SEED, case.diagnostic), 1, Dsp.RATE)
            val milliseconds = (System.nanoTime() - before) / 1e6
            require(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && raw.peak() > 1e-7f) {
                "Invalid or silent clip ${case.id}"
            }
            if ((index + 1) % 10 == 0 || index == cases.lastIndex) {
                println("ORIGAMI ${index + 1}/${cases.size}: ${case.id} (${milliseconds.roundToInt()} ms)")
            }
            Rendered(case, raw, milliseconds)
        }
        val rawExportGain = minOf(1f, .99f / rendered.maxOf { it.raw.peak() })
        val matchGains = rendered.associate { it.case.id to matchingGain(it.raw) }
        val inputSources = rendered.filter { it.case.diagnostic == OrigamiDiagnostic.SOURCE }
            .associate { "${it.case.voice}:${it.case.midi}" to sha256(floatBytes(it.raw.samples)) }
        val zeroFoldIdentity = linkedMapOf<String, Boolean>()
        for (source in rendered.filter { it.case.diagnostic == OrigamiDiagnostic.SOURCE }) {
            val zeroFold = Origami.renderRaw(source.case.voice, source.case.macros + ("FOLD" to 0f), VELOCITY, source.case.midi, SEED)
            zeroFoldIdentity["${source.case.voice}:${source.case.midi}"] = zeroFold.contentEquals(source.raw.samples)
        }
        check(zeroFoldIdentity.values.all { it }) { "FOLD 0 differs from identical-input SOURCE: $zeroFoldIdentity" }
        val currentClips = rendered.map { render ->
            val case = render.case
            val gain = matchGains.getValue(case.sharedMatchedReference ?: case.id)
            val rawExport = applyGain(render.raw, rawExportGain)
            val matched = applyGain(render.raw, gain)
            check(matched.peak() <= 1f) { "Diagnostic branch exceeded PCM range at its common reference gain: ${case.id}" }
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            WavWriter.write(File(root, rawPath), rawExport, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            val estimate = Pitch.detect(render.raw, fromSec = .08f, windowSec = .25f)
            val requestedHz = 440.0 * 2.0.pow((case.midi - 69) / 12.0)
            linkedMapOf<String, Any?>(
                "id" to case.id, "category" to case.category, "group" to case.group,
                "groupTitle" to case.groupTitle, "title" to case.title, "description" to case.description,
                "voice" to case.voice.name, "midi" to case.midi, "note" to noteName(case.midi),
                "macros" to case.macros, "velocity" to VELOCITY, "seed" to SEED,
                "diagnostic" to case.diagnostic.name, "featured" to case.featured,
                "macro" to case.macro, "value" to case.value, "frames" to render.raw.frameCount,
                "durationSeconds" to render.raw.durationSeconds, "renderMilliseconds" to render.milliseconds,
                "rawPath" to rawPath, "matchedPath" to matchedPath,
                "rawExportGain" to rawExportGain, "matchedGain" to gain,
                "sharedMatchedReference" to case.sharedMatchedReference,
                "rawMetrics" to metrics(render.raw), "rawExportMetrics" to metrics(rawExport), "matchedMetrics" to metrics(matched),
                "requestedHz" to requestedHz, "detectedPitchHz" to estimate?.hz,
                "pitchConfidence" to estimate?.confidence,
                "detectorErrorCents" to estimate?.let { 1200.0 * ln(it.hz / requestedHz) / ln(2.0) },
                "pitchInterpretation" to "Autocorrelation estimate; inharmonic partials may dominate it. Perceived pitch requires listening.",
                "inputSourceSha256" to inputSources.getValue("${case.voice}:${case.midi}"),
                "rawWaveformSha256" to sha256(floatBytes(render.raw.samples)),
                "rawFileSha256" to sha256(File(root, rawPath).readBytes()),
                "matchedFileSha256" to sha256(File(root, matchedPath).readBytes()),
                "recipeSha256" to sha256("model:${Origami.MODEL_VERSION}|${case.voice}|${case.midi}|${case.macros.toSortedMap()}|$VELOCITY|$SEED|${case.diagnostic}".toByteArray()),
                "modelVersion" to Origami.MODEL_VERSION, "sourceSha256" to sourceHash, "originListeningRound" to round,
            )
        }
        verifySources()
        val craneEvidence = comparisonBaseline?.let { verifyCrane(root, currentClips, it) }
        val sourceEvidence = if (Origami.MODEL_VERSION >= 4 && comparisonBaseline != null) {
            require(comparisonBaseline.modelVersion == 3) { "Warm-fold comparisons require the archived accepted R3 source palette" }
            verifySourcePalette(root, currentClips, comparisonBaseline)
        } else null
        val comparisons = comparisonBaseline?.let { addComparisons(root, currentClips, it, sourceHash, round) }.orEmpty()
        val clips = comparisons + currentClips
        check(clips.map { it.getValue("id") }.distinct().size == clips.size) { "Duplicate comparison clip IDs" }
        val sourceFirst = Origami.MODEL_VERSION == 3
        val warmFolds = Origami.MODEL_VERSION >= 4
        val previousVersion = comparisonBaseline?.let { "R${it.modelVersion}" }
        val currentVersion = "R${Origami.MODEL_VERSION}"
        val comparisonBytes = comparisons.filter { it["comparisonRole"] == "previous" }.sumOf { clip ->
            File(root, clip.getValue("rawPath").toString()).length() + File(root, clip.getValue("matchedPath").toString()).length()
        }
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "ORIGAMI", "schemaVersion" to 1, "modelVersion" to Origami.MODEL_VERSION,
            "generatedAt" to Instant.now().toString(), "listeningRound" to round, "quick" to quick,
            "sourceSha256" to sourceHash, "sourceFiles" to sourceHashes, "recipeSha256" to recipeHash,
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 16, "clipCount" to clips.size,
            "renderSeconds" to (System.nanoTime() - began) / 1e9,
            "currentRenderClipCount" to currentClips.size, "comparisonClipCount" to comparisons.size,
            "initialCategory" to if (comparisons.isNotEmpty()) "comparisons" else "pairs",
            "initialClipId" to if (sourceFirst) {
                if (comparisons.isNotEmpty()) "comparison_fan_60_revised" else "pair_fan_60_source"
            } else if (comparisons.isNotEmpty()) "comparison_fan_60_revised" else if (warmFolds) "pair_fan_60_folded" else "pair_crane_60_folded",
            "comparisonFocus" to if (comparisons.isEmpty()) null else if (sourceFirst) "unfolded-source" else "folded-voice",
            "comparisonBaseline" to comparisonBaseline?.let { baseline -> linkedMapOf(
                "modelVersion" to baseline.manifest.getValue("modelVersion"),
                "listeningRound" to baseline.manifest.getValue("listeningRound"),
                "manifestSha256" to baseline.manifestSha256,
                "sourceSha256" to baseline.manifest.getValue("sourceSha256"),
                "sourceFiles" to baseline.manifest.getValue("sourceFiles"),
                "recipeSha256" to baseline.manifest.getValue("recipeSha256"),
                "copiedWavBytes" to comparisonBytes,
                "policy" to "Previous $previousVersion WAVs copied byte for byte; their recorded gains, metrics and original provenance are retained. Revised aliases reference current $currentVersion exports without duplicating them.",
            ) },
            "craneUnchanged" to craneEvidence,
            "sourcePaletteUnchanged" to sourceEvidence,
            "foldZeroEqualsSource" to zeroFoldIdentity,
            "acceptance" to "First-listen prototype; musical identity and perceived pitch remain pending owner listening.",
            "architecture" to "Generated partials, note-relative geometric spectral transforms, phase-continuous synthesis and a stable root anchor. ${if (warmFolds) "The R3 source palette, root, envelopes and pickup weighting are preserved. At center CREASE, FAN reflects harmonic 3 to 1.5 times the root while retaining harmonic 2; STILL's two distinct axes merge harmonics 3 and 4 at 2.5 times the root. Restrained axis motion adds movement without automatic global frequency translation. Manual TURN/WARP remain available." else if (sourceFirst) "FAN's source uses a primary root and strongly declining harmonic weights. STILL's source uses twelve harmonic carriers and six faint detuned companions near harmonics 3, 5 and 8. These changes shape the excitation before any folding; they are not wet EQ." else if (Origami.MODEL_VERSION >= 2) "STILL uses harmonic carriers with quiet seeded detuned companions, not broadband noise." else "STILL uses a seeded dense partial cloud, not broadband noise."}",
            "holdPolicy" to "Finite one-shots only. No HOLD, freeze capture, certified sustain loop or pad integration in this experiment.",
            "levelPolicy" to linkedMapOf(
                "matchedTarget" to MATCHED_TARGET, "matchedPeakCeiling" to MATCHED_CEILING,
                "measurement" to "SnipSnap loudest 200 ms RMS with gentle low cut, as used by Dsp.levelTo; not LUFS.",
                "rawExportGain" to rawExportGain,
                "raw" to "Post-decimation synthesis before loudness matching. Every raw file uses one common pack-wide export gain; true pre-gain metrics remain in each clip.",
                "matched" to "Gain-only loudness matching with a 0.90 peak ceiling. Peak-limited clips can remain below target. No effects or dynamic processing.",
                "diagnostics" to "ROOT_ONLY and FOLDED_ONLY use their COMBINED reference's matching gain, preserving layer balance. They are intentionally not independently loudness matched.",
            ),
            "categories" to (if (comparisons.isNotEmpty()) listOf(category("comparisons", if (warmFolds) "Warm folds" else if (sourceFirst) "Gentler sources" else "Softer voices",
                if (warmFolds) "The folded FAN and STILL: $previousVersion versus $currentVersion at the same notes and controls. Start with FAN at C4, then check C3/C5. The accepted sources and all CRANE exports remain unchanged. Listen for a more distinct fold while retaining warmth."
                else if (sourceFirst) "The unfolded FAN and STILL sources: $previousVersion versus $currentVersion at the same notes, controls, velocity and seed. Start with the new FAN at C4, then check C3/C5. The source spectrum and balance change before folding; CRANE's exports remain unchanged. Musical acceptance remains yours to judge."
                else "FAN and STILL, previous versus revised at the same notes, controls, velocity and seed. Start at C4, then check C3 and C5. Both revisions use the same matched-level policy; CRANE's exports remain unchanged.")) else emptyList()) + listOf(
                category("pairs", "Source + fold", "Compare each current source before and after folding. Start at C4, then check C3 and C5."),
                category("sweeps", "Macro sweeps", "Five positions of CREASE and FOLD with motion off. Other geometry controls stay neutral."),
                category("motion", "Motion", "Compare frozen and moving geometry over the same source."),
                category("shape", "Turn + warp", "Single-variable TURN and WARP probes. The neutral center is 50%."),
                category("interaction", "Crease × fold", "Two corner comparisons: CREASE at 25/75%, with FOLD at 25/75%."),
                category("diagnostics", "Layers", "Root anchor, folded upper spectrum and their combination at a common reference gain."),
            ),
            "clips" to clips,
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(root, "manifest.json").writeText(manifestText)
        writePages(root, manifestText, "--embed-audio" in args)
        File(root, "README.md").writeText("""
            # ORIGAMI first listen

            ${clips.size} dry ${if (quick) "smoke-test" else "first-listen"} cases, 44.1 kHz mono 16-bit WAVs. Recipes are provisional; machine pitch estimates do not establish perceived pitch or musical acceptance.

            Open index.html directly, or serve this directory over HTTP. The manifest is embedded in the page, so opening a local file does not fetch JSON. Playback starts only after a click through the browser's audio player, including mobile and embedded browsers. Every new selection stops the previous sound. ${if ("--embed-audio" in args) "start-here.html embeds the six current C4 source/fold references: two clips per voice${if (warmFolds) ", opening on the revised FAN fold" else if (sourceFirst) ", opening on the new FAN source" else ""}. portable.html embeds the full matrix, including any previous-version comparisons. Both play without a server." else "Add --embed-audio when generating or refreshing to produce compact start-here.html and full portable.html with embedded WAVs for fully offline playback."}

            ${if (comparisons.isNotEmpty()) "Begin with ${if (warmFolds) "Warm folds: previous and revised FAN/STILL folds" else if (sourceFirst) "Gentler sources: previous and revised unfolded FAN/STILL sources" else "Softer voices: previous and revised FAN/STILL"} at C4, then C3/C5. Previous raw/matched WAVs are copied unchanged from the pinned $previousVersion archive, and Revised points to the current $currentVersion references. Every rendered CRANE raw/matched export is verified byte-identical to the archived baseline.${if (sourceEvidence != null) " All ${sourceEvidence.getValue("verifiedCases")} unfolded source cases also retain identical raw/matched exports and pre-level waveforms." else ""} Source + fold and the full macro matrix remain available." else if (warmFolds) "Begin with the revised FAN C4 fold, then compare its unchanged source in Source + fold. All three voices remain available at C3/C4/C5." else if (sourceFirst) "Begin with the new FAN C4 unfolded source, then compare its folded version. Source + fold includes all three voices at C3/C4/C5." else "Begin with the three C4 source/fold A/B pairs. Check C3/C5 root clarity, then the five-step CREASE/FOLD sweeps."} Motion is off except in the explicit motion pairs. TURN/WARP are centered except in their probes. ${if (warmFolds) "The accepted R3 source spectra and envelopes are preserved while R4 revises the folded upper spectrum." else if (sourceFirst) "FAN uses a primary root with strongly declining harmonic weights. STILL uses twelve harmonic carriers with six faint detuned companions. The redesigned excitation is heard before folding." else if (Origami.MODEL_VERSION >= 2) "STILL uses harmonic carriers with quiet seeded detuned companions." else "STILL is a seeded dense partial cloud."} These are finite one-shots; there is no HOLD or loop claim.

            Matched clips use SnipSnap's loudest-window loudness measurement, target $MATCHED_TARGET and peak ceiling $MATCHED_CEILING. Diagnostic layers retain the gain of their combined reference rather than receiving independent boosts. Raw clips share one export gain ($rawExportGain); the manifest records the actual pre-gain peak, RMS, DC and loudness. No rack effects are present.

            Notes and verdicts stay in this browser's local storage, keyed by the source fingerprint, recipe and clip. Export feedback saves a JSON file with the listening round and clip evidence.

            Regenerate from the repository root: ./gradlew :synth:generateOrigamiAudition
            The JVM main also accepts [output-directory] --quick --refresh-page --embed-audio --comparison-baseline=<directory>. Comparison renders require a pinned published R1, R2 or R3 manifest, its archived source files and all referenced raw/matched WAVs. Model 3 compares unfolded sources; model 4 compares folds against R3 and verifies the accepted source palette. The quick matrix includes C4 comparisons only.
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} ORIGAMI clips under ${root.absolutePath} in %.1f s".format(Locale.ROOT, (System.nanoTime() - began) / 1e9))
        if (comparisonBaseline != null) println("$previousVersion comparison adds $comparisonBytes WAV bytes; all ${currentClips.count { it["voice"] == "CRANE" }} rendered CRANE cases retain identical raw and matched exports")
    }

    private fun prepareComparisonBaseline(output: File, path: String): ComparisonBaseline {
        require(path.isNotBlank()) { "Comparison baseline directory is empty" }
        val directory = File(path).canonicalFile
        require(directory.isDirectory && directory != output.canonicalFile) { "Archive the previous model separately from the output directory" }
        val manifestFile = File(directory, "manifest.json")
        require(manifestFile.isFile) { "Missing archived manifest.json: $directory" }
        val manifestSha = sha256(manifestFile.readBytes())
        val expectedVersion = when (manifestSha) {
            R1_MANIFEST_SHA256 -> 1
            R2_MANIFEST_SHA256 -> 2
            R3_MANIFEST_SHA256 -> 3
            else -> error("Baseline manifest differs from published R1/R2/R3 evidence ($manifestSha)")
        }
        val manifest = Json.parse(manifestFile.readText()).obj().mapValues { fromJson(it.value) }
        require(manifest["engine"] == "ORIGAMI" && (manifest["modelVersion"] as? Number)?.toInt() == expectedVersion &&
            (manifest["sampleRate"] as? Number)?.toInt() == Dsp.RATE &&
            (manifest["channels"] as? Number)?.toInt() == 1 && (manifest["bitDepth"] as? Number)?.toInt() == 16) {
            "Baseline must be a pinned published mono 44.1 kHz 16-bit audition"
        }
        require(expectedVersion < Origami.MODEL_VERSION) { "Comparison baseline must precede the current model" }
        require(Origami.MODEL_VERSION < 4 || expectedVersion == 3) { "Warm-fold comparisons require the archived accepted R3 source palette" }
        val sourceFiles = (manifest.getValue("sourceFiles") as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() }
        val expectedRoster = when (expectedVersion) {
            1 -> linkedSetOf("Origami.kt", "OrigamiPatch.kt")
            2 -> linkedSetOf("Origami.kt", "OrigamiPatch.kt", "OrigamiV1.kt")
            else -> linkedSetOf("Origami.kt", "OrigamiPatch.kt", "OrigamiV1.kt", "OrigamiV2.kt")
        }
        require(sourceFiles.keys == expectedRoster) { "Unexpected R$expectedVersion source roster" }
        for ((name, hash) in sourceFiles) {
            val source = archiveFile(directory, name)
            require(source.isFile && sha256(source.readBytes()) == hash) { "Archived R$expectedVersion source differs: $name" }
        }
        require(manifest["sourceSha256"] == baselineSourceHashes.getValue(expectedVersion) &&
            sha256(sourceFiles.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray()) == manifest["sourceSha256"]) {
            "R$expectedVersion combined source fingerprint does not match its files"
        }
        val level = manifest.getValue("levelPolicy") as Map<*, *>
        require((level["matchedTarget"] as Number).toDouble() == MATCHED_TARGET.toDouble() &&
            (level["matchedPeakCeiling"] as Number).toDouble() == MATCHED_CEILING.toDouble()) {
            "Previous and current audition matching policies differ"
        }
        val clips = (manifest.getValue("clips") as List<*>).map { row ->
            (row as Map<*, *>).entries.associate { it.key.toString() to it.value }
        }
        val expectedClipCount = (manifest.getValue("clipCount") as Number).toInt()
        require(clips.size == expectedClipCount && clips.map { it.getValue("id") }.distinct().size == expectedClipCount &&
            clips.count { it["category"] != "comparisons" } == 70) { "Incomplete R$expectedVersion case roster" }
        // Verify the complete archive before new renders can replace any current references.
        for (clip in clips) for ((pathKey, hashKey) in listOf("rawPath" to "rawFileSha256", "matchedPath" to "matchedFileSha256")) {
            val file = archiveFile(directory, clip.getValue(pathKey).toString())
            require(file.isFile && sha256(file.readBytes()) == clip.getValue(hashKey)) { "Archived R$expectedVersion audio differs: ${clip["id"]}/$pathKey" }
        }
        return ComparisonBaseline(directory, manifest, clips, manifestSha)
    }

    private fun archiveFile(directory: File, path: String): File {
        val file = File(directory, path).canonicalFile
        require(file.toPath().startsWith(directory.toPath()) && file != directory) { "Archive path escapes its directory: $path" }
        return file
    }

    private fun verifyCrane(output: File, current: List<Map<String, Any?>>, baseline: ComparisonBaseline): Map<String, Any?> {
        val archived = baseline.clips.associateBy { it.getValue("id").toString() }
        val crane = current.filter { it["voice"] == "CRANE" }
        require(crane.isNotEmpty()) { "No CRANE cases to verify" }
        require(current.size != 70 || crane.size == 24) { "The full matrix must verify all 24 CRANE cases" }
        for (clip in crane) {
            val previous = archived.getValue(clip.getValue("id").toString())
            requireSameControls(previous, clip)
            for ((pathKey, hashKey) in listOf("rawPath" to "rawFileSha256", "matchedPath" to "matchedFileSha256")) {
                val hash = sha256(File(output, clip.getValue(pathKey).toString()).readBytes())
                require(hash == previous.getValue(hashKey)) { "CRANE changed in ${clip["id"]}/$pathKey; preserve its published sound" }
            }
            require(clip["rawWaveformSha256"] == previous["rawWaveformSha256"]) { "CRANE pre-level waveform changed: ${clip["id"]}" }
        }
        return linkedMapOf("verifiedCases" to crane.size, "verifiedWavFiles" to crane.size * 2,
            "allRawAndMatchedByteIdentical" to true, "allPreLevelWaveformsIdentical" to true,
            "baselineSourceSha256" to baseline.manifest.getValue("sourceSha256"))
    }

    private fun verifySourcePalette(output: File, current: List<Map<String, Any?>>, baseline: ComparisonBaseline): Map<String, Any?> {
        val archived = baseline.clips.associateBy { it.getValue("id").toString() }
        val sources = current.filter { it["diagnostic"] == "SOURCE" }
        require(sources.isNotEmpty()) { "No unfolded source cases to verify" }
        require(current.size != 70 || sources.size == 9) { "The full matrix must verify all nine unfolded sources" }
        for (clip in sources) {
            val previous = archived.getValue(clip.getValue("id").toString())
            requireSameControls(previous, clip)
            require(clip["rawWaveformSha256"] == previous["rawWaveformSha256"]) {
                "Accepted R3 source pre-level waveform changed: ${clip["id"]}"
            }
            require(clip["inputSourceSha256"] == previous["inputSourceSha256"]) {
                "Accepted R3 input source changed: ${clip["id"]}"
            }
            for ((pathKey, hashKey) in listOf("rawPath" to "rawFileSha256", "matchedPath" to "matchedFileSha256")) {
                require(sha256(File(output, clip.getValue(pathKey).toString()).readBytes()) == previous.getValue(hashKey)) {
                    "Accepted R3 source export changed: ${clip["id"]}/$pathKey"
                }
            }
        }
        return linkedMapOf("verifiedCases" to sources.size, "verifiedWavFiles" to sources.size * 2,
            "allRawAndMatchedByteIdentical" to true, "allPreLevelWaveformsIdentical" to true,
            "baselineModelVersion" to baseline.modelVersion, "baselineSourceSha256" to baseline.manifest.getValue("sourceSha256"))
    }

    private fun addComparisons(
        output: File, current: List<Map<String, Any?>>, baseline: ComparisonBaseline,
        sourceSha: String, round: String,
    ): List<Map<String, Any?>> = buildList {
        val sourceFirst = Origami.MODEL_VERSION == 3
        val warmFolds = Origami.MODEL_VERSION >= 4
        val previousVersion = "R${baseline.modelVersion}"
        val currentVersion = "R${Origami.MODEL_VERSION}"
        val previousById = baseline.clips.associateBy { it.getValue("id").toString() }
        val currentById = current.associateBy { it.getValue("id").toString() }
        for (midi in listOf(60, 48, 72)) for (voice in listOf("FAN", "STILL")) {
            val referenceId = "pair_${voice.lowercase(Locale.ROOT)}_${midi}_${if (sourceFirst) "source" else "folded"}"
            val revised = currentById[referenceId] ?: continue // The quick matrix supplies only C4.
            val previous = previousById.getValue(referenceId)
            requireSameControls(previous, revised)
            val group = "comparison_${voice.lowercase(Locale.ROOT)}_$midi"
            val rawPath = "comparison/r${baseline.modelVersion}/${previous.getValue("rawPath")}"
            val matchedPath = "comparison/r${baseline.modelVersion}/${previous.getValue("matchedPath")}"
            for ((pathKey, destination, hashKey) in listOf(
                Triple("rawPath", rawPath, "rawFileSha256"), Triple("matchedPath", matchedPath, "matchedFileSha256"),
            )) {
                val archived = archiveFile(baseline.directory, previous.getValue(pathKey).toString())
                val target = File(output, destination).also { it.parentFile.mkdirs() }
                archived.copyTo(target, overwrite = true)
                require(sha256(target.readBytes()) == previous.getValue(hashKey)) { "$previousVersion copy changed: $referenceId/$pathKey" }
            }
            val common = linkedMapOf<String, Any?>(
                "category" to "comparisons", "group" to group, "groupTitle" to "$voice · ${noteName(midi)}",
                "featured" to (midi == 60), "comparisonOriginalId" to referenceId,
                "comparisonFocus" to if (sourceFirst) "unfolded-source" else "folded-voice",
                "baselineSourceSha256" to baseline.manifest.getValue("sourceSha256"),
            )
            add(previous + common + linkedMapOf(
                "id" to "${group}_previous", "title" to if (warmFolds) "A · Previous fold" else "A · Previous", "comparisonVersion" to previousVersion,
                "comparisonRole" to "previous",
                "description" to "The $previousVersion $voice ${if (sourceFirst) "unfolded source" else if (warmFolds) "folded sound" else "voice"}, preserved byte for byte at its original raw and matched gains.",
                "rawPath" to rawPath, "matchedPath" to matchedPath,
                "originalRawPath" to previous.getValue("rawPath"), "originalMatchedPath" to previous.getValue("matchedPath"),
                "modelVersion" to baseline.modelVersion, "sourceSha256" to baseline.manifest.getValue("sourceSha256"),
                "originListeningRound" to baseline.manifest.getValue("listeningRound"),
                "originPackRecipeSha256" to baseline.manifest.getValue("recipeSha256"),
            ))
            add(revised + common + linkedMapOf(
                "id" to "${group}_revised", "title" to if (warmFolds) "B · Revised fold" else "B · Revised", "comparisonVersion" to currentVersion,
                "comparisonRole" to "revised",
                "description" to if (warmFolds) "The $currentVersion $voice fold at the same note and controls. The accepted source is preserved; compare the spectral shape and warmth after folding. Source + fold includes the unchanged starting sound."
                    else if (sourceFirst) "The $currentVersion $voice unfolded source at the same note and controls. This comparison isolates the starting spectrum and balance before folding. Source + fold contains the current folded version."
                    else "The revised $voice voice at the same note and controls. Listen for a gentler upper spectrum while the folded shape remains useful.",
                "modelVersion" to Origami.MODEL_VERSION, "sourceSha256" to sourceSha, "originListeningRound" to round,
            ))
        }
    }

    private fun requireSameControls(previous: Map<String, Any?>, current: Map<String, Any?>) {
        for (key in listOf("voice", "midi", "macros", "velocity", "seed", "diagnostic")) {
            require(sameEvidence(previous[key], current[key])) { "Comparison changes $key in ${current["id"]}" }
        }
    }

    private fun sameEvidence(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { sameEvidence(a[it], b[it]) }
        else -> a == b
    }

    private fun baseline(voice: OrigamiVoice) = Origami.defaults(voice) + mapOf(
        "CREASE" to .5f, "FOLD" to .75f, "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f,
    )

    private fun cases(quick: Boolean): List<Case> = buildList {
        // Same seed, note, velocity and generated source on both sides of every pair.
        for (midi in if (quick) listOf(60) else listOf(60, 48, 72)) for (voice in OrigamiVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            val group = "pair_${slug}_$midi"
            val macros = baseline(voice) + ("TUNE" to (midi - Origami.MIDI_MIN).toFloat() / Origami.TUNE_SEMITONES)
            add(Case("${group}_source", "pairs", group, "${voice.name} · ${noteName(midi)}", "A · Unfolded source",
                "Current generated source, heard before folding, with the same envelope, note and seed as B.", voice, macros, midi, OrigamiDiagnostic.SOURCE, midi == 60))
            add(Case("${group}_folded", "pairs", group, "${voice.name} · ${noteName(midi)}", "B · Folded",
                "CREASE 50%, FOLD 75%, neutral TURN/WARP and frozen geometry. Listen for a changed upper spectrum and a recognizable note.", voice, macros, midi, featured = midi == 60))
        }
        for (voice in if (quick) listOf(OrigamiVoice.CRANE) else OrigamiVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            for (macro in listOf("CREASE", "FOLD")) for (value in steps) {
                add(Case("sweep_${slug}_${macro.lowercase()}_${tag(value)}", "sweeps", "sweep_${slug}_${macro.lowercase()}",
                    "${voice.name} · $macro", "$macro ${tag(value).toInt()}%",
                    if (macro == "CREASE") "Only the crease moves; FOLD stays at 75%. Motion is off." else "Only fold amount changes; CREASE stays at 50%. Motion is off.",
                    voice, baseline(voice) + (macro to value), macro = macro, value = value))
            }
        }
        for (voice in OrigamiVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            for (motion in listOf(0f, .75f)) add(Case("motion_${slug}_${tag(motion)}", "motion", "motion_$slug", "${voice.name} · Motion",
                if (motion == 0f) "A · Frozen geometry" else "B · Moving geometry", "Same source and geometry; only MOTION changes.",
                voice, baseline(voice) + ("MOTION" to motion), macro = "MOTION", value = motion))
            val reference = "pair_${slug}_60_folded"
            for (diagnostic in listOf(OrigamiDiagnostic.ROOT_ONLY, OrigamiDiagnostic.FOLDED_ONLY)) {
                add(Case("layer_${slug}_${diagnostic.name.lowercase()}", "diagnostics", "layers_$slug", "${voice.name} · Layers",
                    if (diagnostic == OrigamiDiagnostic.ROOT_ONLY) "Root anchor" else "Folded spectrum",
                    "Uses the combined reference's matched gain, so the original layer balance is preserved.", voice, baseline(voice),
                    diagnostic = diagnostic, sharedMatchedReference = reference))
            }
        }
        if (!quick) {
            for ((voice, macro) in listOf(OrigamiVoice.FAN to "TURN", OrigamiVoice.STILL to "WARP")) for (value in listOf(.2f, .5f, .8f)) {
                val slug = voice.name.lowercase(Locale.ROOT)
                add(Case("shape_${slug}_${macro.lowercase()}_${tag(value)}", "shape", "shape_${slug}_${macro.lowercase()}", "${voice.name} · $macro",
                    "$macro ${tag(value).toInt()}%", "Only $macro changes. Neutral is 50%; CREASE 50%, FOLD 75%, MOTION off.",
                    voice, baseline(voice) + (macro to value), macro = macro, value = value))
            }
            for (fold in listOf(.25f, .75f)) for (crease in listOf(.25f, .75f)) {
                add(Case("interaction_crane_fold_${tag(fold)}_crease_${tag(crease)}", "interaction", "interaction_crane_${tag(fold)}",
                    "CRANE · FOLD ${tag(fold).toInt()}%", "CREASE ${tag(crease).toInt()}%",
                    "Compare low and high crease at this fold amount; neutral TURN/WARP, no motion.", OrigamiVoice.CRANE,
                    baseline(OrigamiVoice.CRANE) + mapOf("CREASE" to crease, "FOLD" to fold)))
            }
        }
    }

    private fun matchingGain(snip: Snip): Float {
        val loudness = Loudness.of(snip)
        val loudnessGain = if (loudness > 1e-6f) MATCHED_TARGET / loudness else 1f
        return minOf(loudnessGain, MATCHED_CEILING / snip.peak())
    }

    private fun applyGain(snip: Snip, gain: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, 1, snip.sampleRate)
    private fun metrics(snip: Snip): Map<String, Any> {
        var sum = 0.0
        var squares = 0.0
        for (sample in snip.samples) { sum += sample; squares += sample.toDouble() * sample }
        return linkedMapOf("peak" to snip.peak(), "rms" to sqrt(squares / snip.samples.size), "dc" to sum / snip.samples.size,
            "loudness" to Loudness.of(snip), "finite" to snip.samples.all { it.isFinite() })
    }

    private fun category(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun floatBytes(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).also {
        for (sample in samples) it.putFloat(sample)
    }.array()

    private fun writePages(root: File, manifest: String, embedAudio: Boolean) {
        File(root, "index.html").writeText(OrigamiAuditionPage.render(manifest))
        if (embedAudio) {
            val parent = Json.parse(manifest).obj()
            val clips = parent.getValue("clips").arr()
            val modelVersion = parent.getValue("modelVersion").int()
            val featured = clips.filter { it.obj().getValue("featured").bool() && it.obj().getValue("category").str() == "pairs" }
            check(featured.size == 6) { "The standalone first listen requires six featured C4 references" }
            val categories = featured.map { it.obj().getValue("category").str() }.toSet()
            val starter = JsonValue.Obj(parent + mapOf(
                "clips" to JsonValue.Arr(featured), "clipCount" to JsonValue.Num(featured.size.toDouble()),
                "initialCategory" to JsonValue.Str("pairs"),
                "initialClipId" to JsonValue.Str(if (modelVersion >= 4) "pair_fan_60_folded" else if (modelVersion == 3) "pair_fan_60_source" else "pair_crane_60_folded"),
                "categories" to JsonValue.Arr(parent.getValue("categories").arr().filter { it.obj().getValue("id").str() in categories }),
                "edition" to JsonValue.Str("featured-c4-first-listen"),
                "parentPackClipCount" to parent.getValue("clipCount"),
                "editionNote" to JsonValue.Str("Six current C4 source/fold references from the parent matrix, two per voice. Source, listening round and recipe fingerprint identify that full parent pack."),
            ))
            File(root, "start-here.html").writeText(OrigamiAuditionPage.render(Json.write(starter), embeddedAudio(root, featured)))
            File(root, "portable.html").writeText(OrigamiAuditionPage.render(manifest, embeddedAudio(root, clips)))
        } else {
            // Never leave an older, apparently current embedded-audio pack beside fresh WAVs.
            File(root, "portable.html").takeIf { it.exists() }?.delete()
            File(root, "start-here.html").takeIf { it.exists() }?.delete()
        }
    }

    private fun embeddedAudio(root: File, clips: List<JsonValue>): String {
        val audio = linkedMapOf<String, String>()
        for (clip in clips) for (key in listOf("rawPath", "matchedPath")) {
            val path = clip.obj().getValue(key).str()
            audio[path] = Base64.getEncoder().encodeToString(File(root, path).readBytes())
        }
        return Json.write(json(audio))
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported manifest value ${value.javaClass}")
    }

    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> value.items.map { fromJson(it) }
    }
}
