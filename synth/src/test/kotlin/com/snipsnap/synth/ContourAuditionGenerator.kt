package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reproducible dry listening matrix. Recipes remain provisional until owner listening. */
object ContourAuditionGenerator {
    // The complete published listening matrix must fit Sites' archive limit.
    private val AUDITION_DEPTH = WavWriter.BitDepth.PCM_16
    private const val MATCHED_TARGET = .12f
    private const val MATCHED_CEILING = .90f
    private const val VOICING_TARGET = "Expanded sound-design range: independent body, edge, articulation, motion speed and filter contour."
    private const val VOICING_METHODOLOGY = "R05 expands the engine controls and reauthors the nine dry presets. Control range uses three reference patches per control: bass, key and lead. The bass filter-contour example uses Dark Table for its lower base cutoff; the other bass examples use Moving Corner. Each saved configuration, note and velocity stays fixed; all seven added controls start at 0.5, then only the selected control changes. Previous preserves published R04 audio byte for byte. Listening decides whether these changes are useful."
    private data class ExpandedControl(
        val name: String, val label: String, val description: String,
        val low: String, val neutral: String, val high: String,
    ) {
        fun endpoint(value: Float) = when (value) { 0f -> low; .5f -> neutral; 1f -> high; else -> error("Unexpected control endpoint") }
        fun metadata(): Map<String, Any> = linkedMapOf("name" to name, "label" to label,
            "description" to description, "low" to low, "neutral" to neutral, "high" to high,
            "values" to listOf(0f, .5f, 1f))
    }
    private val expandedControls = listOf(
        ExpandedControl("BODY", "Body shape", "Change the body root and partial projection independently of the edge.",
            "Root 1.5×, partials 0.5×", "Saved body balance", "Root 0.25×, saved partials"),
        ExpandedControl("EDGE", "Edge level", "Change the related edge layer independently of body level.",
            "No edge layer", "Saved edge level", "Edge level 0.8"),
        ExpandedControl("ATTACK", "Attack", "Change the amplitude, edge and filter entrance times together.",
            "Attack times 0.125×", "Saved attack times", "Attack times up to 8×"),
        ExpandedControl("DECAY", "Decay and release", "Change amplitude decay and release together with edge and filter decay. Finite notes stay within eight seconds.",
            "Decay and release times 0.25×", "Saved decay and release times", "Decay and release times up to 4×"),
        ExpandedControl("RATE", "Motion rate", "Change scan movement speed independently of its depth. Zero MOTION stays frozen; held loops fit whole cycles.",
            "Scan clock 0.125×", "Saved motion rate", "Scan clock 8×"),
        ExpandedControl("RESO", "Resonance", "Change the shared filter's resonance independently of cutoff.",
            "Soft damping 2.5", "Saved filter damping", "Resonant damping 0.4"),
        ExpandedControl("ENV", "Filter contour", "Change the filter envelope amount independently of its base cutoff. The cutoff stays below 18 kHz.",
            "No filter contour", "Saved filter contour", "Saved contour plus two octaves"),
    )
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val timbral = listOf("SCAN", "OFFSET", "MOTION", "FILTER", "DRIVE")
    private val interactions = listOf("SCAN" to "OFFSET", "OFFSET" to "MOTION", "SCAN" to "FILTER", "FILTER" to "DRIVE")
    private val presetDescriptions = mapOf(
        "Rounded Edge" to "Intended character: a rounded pulse bass.",
        "Moving Corner" to "Intended character: a woody, pulsing bass.",
        "Dark Table" to "Intended character: a dark bass with octave color.",
        "Copper Scan" to "Intended character: a mellow copper key with a delayed bloom.",
        "Soft Digital" to "Intended character: a soft key with short bell detail.",
        "Bright Margin" to "Intended character: a rounded wooden pluck.",
        "Twin Outline" to "Intended character: a rounded copper lead with an octave edge.",
        "Hollow Step" to "Intended character: a hollow, reed-like tone with a gradual entrance.",
        "Long Contour" to "Intended character: a slowly moving formant sustain.",
    )
    private val macroNotes = mapOf(
        "SCAN" to "Hear the spectral path change while the root stays recognizable. Motion is frozen for this sweep.",
        "OFFSET" to "Hear the edge take a different spectral perspective. The body should keep carrying the note.",
        "MOTION" to "Hear deeper and faster movement before the filter. Low nonzero settings should already move.",
        "FILTER" to "Hear the shared low-pass open, including its restrained resonance and contour.",
        "DRIVE" to "Hear clean excitation gain weight and grit. Compare tone and root clarity at matched level.",
    )

    private data class Case(
        val id: String, val category: String, val group: String, val title: String,
        val description: String, val voice: ContourVoice,
        val macros: Map<String, Float> = ContourEngine.defaults(voice), val velocity: Float = 1f,
        val config: ContourConfig = ContourConfig.forVoice(voice),
        val diagnostic: ContourDiagnostic? = null, val includeNormalized: Boolean = false,
        val featured: Boolean = false,
        val control: ExpandedControl? = null, val controlValue: Float? = null,
        val controlBase: String? = null, val controlBaseLabel: String? = null,
    )

    private data class ComparisonBaseline(
        val metadata: Map<String, Any?>,
        val clips: List<Map<String, Any?>>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1 && args.filter { it.startsWith("--") }.all {
            it == "--quick" || it == "--refresh-page" || it.startsWith("--comparison-baseline=")
        }) {
            "Supply one output directory, optionally --quick, --refresh-page or --comparison-baseline=<directory>"
        }
        require(args.count { it.startsWith("--comparison-baseline=") } <= 1) { "Supply only one comparison baseline" }
        val root = File(positional.firstOrNull() ?: "../testkit/contour-audition")
        require(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        if ("--refresh-page" in args) {
            require(File(root, "manifest.json").isFile) { "Render the audition before refreshing its page" }
            writePage(root)
            println("Refreshed CONTOUR page; audio and manifest unchanged")
            return
        }
        val quick = "--quick" in args
        val baselinePath = args.firstOrNull { it.startsWith("--comparison-baseline=") }?.substringAfter('=')
        val comparisonBaseline = prepareComparisonBaseline(root, baselinePath)
        val cases = cases(quick)
        check(cases.map { it.id }.distinct().size == cases.size) { "Duplicate clip IDs" }
        val sourceFiles = listOf("ContourEngine.kt", "ContourTables.kt", "ContourPresets.kt")
            .associateWith { File("src/main/kotlin/com/snipsnap/synth/$it") }
        val sourceHashes = sourceFiles.mapValues { sha256(it.value) }
        val sourceHash = sha256(sourceHashes.entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray())
        val recipeHash = sha256(cases.joinToString("\n") { "${it.id}|${it.voice}|${it.macros.toSortedMap()}|${it.velocity}|${it.config}|${it.diagnostic}" }.toByteArray())
        val listeningRound = "contour-r05-${sourceHash.take(12)}-${recipeHash.take(12)}"
        val started = System.nanoTime()
        // Neutral positions sharing the same saved configuration reuse one exact render.
        val controlCenters = mutableMapOf<String, LinkedHashMap<String, Any?>>()
        val clips = cases.mapIndexed { index, case ->
            check(sourceFiles.all { sha256(it.value) == sourceHashes.getValue(it.key) }) {
                "CONTOUR source changed during generation; regenerate the complete pack"
            }
            val cachedCenter = if (case.controlValue == .5f) case.controlBase?.let(controlCenters::get) else null
            if (cachedCenter != null) {
                val evidence = LinkedHashMap(cachedCenter).apply {
                    putAll(caseMetadata(case))
                    put("renderMilliseconds", 0.0)
                    put("assetReference", cachedCenter.getValue("id"))
                }
                if ((index + 1) % 20 == 0 || index == cases.lastIndex) println("CONTOUR audition: ${index + 1}/${cases.size} cases")
                return@mapIndexed evidence
            }
            val before = System.nanoTime()
            val loop = case.macros.getValue("HOLD") >= ContourEngine.HOLD_LOOP
            val loopEvidence = if (loop) ContourEngine.renderLoopMeasured(case.voice, case.macros, case.velocity, case.config,
                normalize = false, diagnostic = case.diagnostic ?: ContourDiagnostic.COMBINED) else null
            val raw = Snip(if (loopEvidence != null) {
                loopEvidence.samples.copyOfRange(loopEvidence.loopStart, loopEvidence.samples.size)
            } else if (case.diagnostic == null) {
                ContourEngine.renderRaw(case.voice, case.macros, case.velocity, case.config)
            } else {
                ContourEngine.renderDiagnosticRaw(case.voice, case.diagnostic, case.macros, case.velocity, case.config)
            }, 1, Dsp.RATE)
            val renderMs = (System.nanoTime() - before) / 1_000_000.0
            require(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && raw.peak() > 1e-7f) { "Invalid or silent clip ${case.id}" }
            val normalized = level(raw, Dsp.MELODIC_LOUDNESS_TARGET, .99f)
            val matched = level(raw, MATCHED_TARGET, MATCHED_CEILING)
            val rawExportGain = minOf(1f, .99f / raw.peak())
            val rawExport = if (rawExportGain == 1f) raw else Snip(FloatArray(raw.samples.size) { raw.samples[it] * rawExportGain }, 1, Dsp.RATE)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            writeWav(root, rawPath, rawExport)
            writeWav(root, matchedPath, matched)
            val normalizedPath = if (case.includeNormalized) {
                if (normalized.samples.contentEquals(matched.samples)) matchedPath
                else "normalized/${case.id}.wav".also { writeWav(root, it, normalized) }
            } else null
            val repeatPath = if (loop) "repeated/${case.id}_two_cycles.wav".also { path ->
                val repeated = FloatArray(matched.samples.size * 2) { matched.samples[it % matched.samples.size] }
                writeWav(root, path, Snip(repeated, 1, Dsp.RATE))
            } else null
            val evidence = linkedMapOf<String, Any?>(
                "loop" to loop, "frames" to raw.frameCount, "durationSeconds" to raw.durationSeconds,
                "rawPath" to rawPath, "matchedPath" to matchedPath, "normalizedPath" to normalizedPath,
                "repeatPath" to repeatPath, "repeatCycles" to if (loop) 2 else null, "rawExportGain" to rawExportGain,
                "rawMetrics" to metrics(raw), "matchedMetrics" to metrics(matched),
                "normalizedMetrics" to if (normalizedPath != null) metrics(normalized) else null,
                "seamError" to loopEvidence?.let { Keys.seamError(it.samples, it.loopStart) },
                "seamBeforeBlend" to loopEvidence?.seamBeforeBlend, "periodicError" to loopEvidence?.periodicError,
                "stateError" to loopEvidence?.stateError, "prerollCycles" to loopEvidence?.prerollCycles,
                "requestedHz" to loopEvidence?.noteHz, "actualHz" to loopEvidence?.actualHz,
                "motionCycles" to loopEvidence?.motionCycles, "edgeRatio" to loopEvidence?.edgeRatio,
                "renderMilliseconds" to renderMs, "audioSha256" to sha256(File(root, matchedPath)),
                "rawSha256" to sha256(File(root, rawPath)),
                "recipeStatus" to "provisional; owner listening pending",
                "provenance" to linkedMapOf(
                    "listeningRound" to listeningRound, "sourceSha256" to sourceHash,
                    "sourceFiles" to sourceHashes, "recipeSha256" to recipeHash,
                ),
            )
            evidence.putAll(caseMetadata(case))
            if (case.controlValue == .5f) controlCenters[checkNotNull(case.controlBase)] = evidence
            if ((index + 1) % 20 == 0 || index == cases.lastIndex) println("CONTOUR audition: ${index + 1}/${cases.size} cases")
            evidence
        }.toMutableList()
        check(sourceFiles.all { sha256(it.value) == sourceHashes.getValue(it.key) }) { "CONTOUR source changed during generation" }
        if (comparisonBaseline != null) addComparisons(clips, comparisonBaseline)
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "CONTOUR", "schemaVersion" to 1, "full" to !quick,
            "listeningRound" to listeningRound,
            "sourceSha256" to sourceHash, "sourceFiles" to sourceHashes, "recipeSha256" to recipeHash,
            "sampleRate" to Dsp.RATE, "bitDepth" to AUDITION_DEPTH.bits, "channels" to 1,
            "clipCount" to clips.size, "sonicAcceptance" to "Pending owner listening. Nine dry presets and the expanded control ranges are provisional.",
            "voicingTarget" to VOICING_TARGET, "voicingMethodology" to VOICING_METHODOLOGY,
            "controls" to expandedControls.map { it.metadata() },
            "levelPolicy" to linkedMapOf(
                "matchedTarget" to MATCHED_TARGET, "matchedPeakCeiling" to MATCHED_CEILING,
                "hostNormalizedTarget" to Dsp.MELODIC_LOUDNESS_TARGET, "hostNormalizedPeakCeiling" to .99f,
                "measurement" to "SnipSnap loudest 200 ms RMS with its gentle low cut; this is not LUFS.",
                "raw" to "Output-conditioned synthesis before shared melodic loudness. A raw file exceeding PCM range is attenuated only for export; the gain and original measurements are recorded.",
                "normalized" to "Shared melodic loudness processing as used by SnipSnap. Available for saved presets and the six center-note voice references.",
                "matched" to "Independent gain only, targeting 0.12 with a 0.90 peak ceiling; no rack effects or dynamic processing. A peak-limited clip may remain quieter than the target.",
            ),
            "architecture" to "Original generated band-limited table pairs, related body and edge scans, a generic low-pass filter, and oversampled bounded drive. No imported factory waves or circuit emulation claims.",
            "holdPolicy" to "Loop-only exports contain settled sustain, without the initial attack. Custom playback repeats the decoded buffer sample accurately. The two-cycle WAV repeats its exact samples without seam fades.",
            "comparisonBaseline" to comparisonBaseline?.metadata,
            "categories" to (if (comparisonBaseline != null) listOf(category("revision", "Revision A/B",
                "Nine Previous and New pairs at the same note and velocity. Previous is the last published voicing, preserved byte for byte; both sides use the same matched-level policy.")) else emptyList()) + listOf(
                category("presets", "Start here", "Nine saved dry recipes. Compare their characters first; every recipe remains provisional."),
                category("controls", "Control range", "Seven added controls at 0, 0.5 and 1 on bass, key and lead references. Bass uses Dark Table for filter contour and Moving Corner for the other controls. Only the selected control changes; the other added controls stay at 0.5. Note, velocity and original controls stay fixed."),
                category("voices", "Six voices", "Reference voices for checking register and velocity, using the existing engine defaults. Revision A/B compares the nine newly authored presets."),
                category("sweeps", "Original macro sweeps", "Five positions per original timbral control and voice. Companion controls are neutral; SCAN uses frozen motion."),
                category("interactions", "Interactions", "Four required control pairs, each as a 3 × 3 grid for every voice. Compare adjacent settings at matched level."),
                category("corners", "Original controls high", "The original five timbral controls at maximum for each voice; the seven added controls stay neutral. Listen for root clarity and bounded roughness."),
                category("diagnostics", "Body + edge", "Isolate the two related layers, then compare frozen scans, the open filter and clean drive. Every branch is level matched."),
                category("hold", "Held loops", "Settled loops at normal and demanding settings. Listen across several wraps; two-cycle downloads preserve exact repetitions."),
            ),
            "clips" to clips,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        writePage(root)
        File(root, "README.md").writeText("""
            # CONTOUR audition

            ${clips.size} dry cases, 44.1 kHz mono 16-bit audition WAV. ${if (quick) "Development smoke pack; it does not cover the complete acceptance matrix." else "Complete three-point expanded-control, five-point original macro, interaction, register, velocity, diagnostic and HOLD listening matrix."}

            Start an HTTP server in this directory and open index.html. The page loads manifest.json; it does not embed the full matrix. Custom playback uses sample-accurate decoded buffer loops. Two-cycle WAV previews contain exact repetitions without seam fades. Native controls are also available. Feedback is stored only in the current browser; Export verdicts saves the listening round, clip and group answers as JSON.

            Default playback is independently level matched to SnipSnap loudness 0.12 with a 0.90 peak ceiling. Presets and center-note voice references also expose shared host normalization. Raw files preserve the synthesis level, except recorded attenuation when PCM headroom requires it. The manifest records original and exported measurements. No rack effects are used.

            Nine recipes are provisional. A passing build and finite measurements do not establish sonic acceptance. Owner listening approval is pending before preset expansion.

            $VOICING_TARGET $VOICING_METHODOLOGY

            Regenerate: ./gradlew :synth:generateContourAudition
            Refresh page only: ./gradlew :synth:generateContourAudition -PcontourRefreshPage=true
            Smoke pack: ./gradlew :synth:generateContourAudition -PcontourQuick=true

            ${if (comparisonBaseline != null) "Revision A/B includes nine byte-identical Previous references from the last published voicing and nine New preset references. Their patch JSON and source, recipe and audio hashes remain with each clip. Previous normalized and repeated-preview paths are omitted because only the previous raw/matched WAVs were archived. Every comparison render requires an explicit --comparison-baseline=<archive-directory>, where baseline.json, source/, raw/ and matched/ contain the verified previous pack. Use -PcontourComparisonBaseline=<archive-directory> with the Gradle generator task. Never reconstruct the baseline with the current engine." else "To add a Previous-versus-New comparison, supply --comparison-baseline=<archive-directory> to this generator's Java entry point, or -PcontourComparisonBaseline=<archive-directory> to its Gradle task. The directory must contain baseline.json, source/ and its archived raw/matched WAVs with valid previous hashes."}
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} CONTOUR cases under ${root.absolutePath} in %.1f s".format(Locale.ROOT, (System.nanoTime() - started) / 1e9))
    }

    /** Copy and verify the archived sound before new renders can overwrite the current preset paths. */
    private fun prepareComparisonBaseline(root: File, requestedPath: String?): ComparisonBaseline? {
        val directory = if (requestedPath != null) {
            require(requestedPath.isNotBlank()) { "Comparison baseline directory is empty" }
            File(requestedPath)
        } else return null
        require(directory.isDirectory) { "Missing comparison baseline directory: $directory" }
        val baselineFile = File(directory, "baseline.json")
        require(baselineFile.isFile) { "Missing archived baseline.json: $directory" }
        val original = Json.parse(baselineFile.readText()).obj().mapValues { fromJson(it.value) }
        require(original["engine"] == "CONTOUR" && (original["bitDepth"] as? Number)?.toInt() == AUDITION_DEPTH.bits &&
            (original["sampleRate"] as? Number)?.toInt() == Dsp.RATE) { "Baseline must be a CONTOUR 44.1 kHz 16-bit audition" }
        val identityKeys = listOf("listeningRound", "sourceSha256", "sourceFiles", "recipeSha256", "bitDepth", "sampleRate", "levelPolicy")
        require(identityKeys.all { original[it] != null }) { "Baseline provenance is incomplete" }
        val hashes = (original["sourceFiles"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
            ?: error("Baseline source fingerprints are missing")
        require(hashes.keys == linkedSetOf("ContourEngine.kt", "ContourTables.kt", "ContourPresets.kt")) { "Unexpected baseline source roster" }
        require(sha256(hashes.entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray()) == original["sourceSha256"]) {
            "Baseline combined source fingerprint does not match its files"
        }
        val policy = original["levelPolicy"] as? Map<*, *> ?: error("Missing baseline level policy")
        require(kotlin.math.abs((policy["matchedTarget"] as? Number)?.toDouble()?.minus(MATCHED_TARGET.toDouble()) ?: 1.0) < 1e-7 &&
            kotlin.math.abs((policy["matchedPeakCeiling"] as? Number)?.toDouble()?.minus(MATCHED_CEILING.toDouble()) ?: 1.0) < 1e-7) {
            "Baseline matched-level policy differs; an honest comparison requires the same policy"
        }
        val archived = (original["clips"] as? List<*>)?.map { row ->
            (row as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: error("Invalid archived preset")
        } ?: error("Baseline preset roster is missing")
        require(archived.size == 9 && archived.all { it["category"] == "presets" } && archived.map { it["id"] }.distinct().size == 9) {
            "Baseline must contain exactly nine unique previous presets"
        }
        val targetDirectory = File(root, "comparison").apply { mkdirs() }
        fun verifiedCopy(relativePath: String, expectedHash: String): File {
            val source = File(directory, relativePath).canonicalFile
            require(source.toPath().startsWith(directory.canonicalFile.toPath()) && source.isFile) { "Missing or unsafe archived asset $relativePath" }
            require(sha256(source) == expectedHash) { "Archived asset hash does not match: $relativePath" }
            val target = File(targetDirectory, relativePath).canonicalFile
            require(target.toPath().startsWith(targetDirectory.canonicalFile.toPath())) { "Unsafe baseline output path: $relativePath" }
            if (source != target) {
                target.parentFile.mkdirs()
                source.copyTo(target, overwrite = true)
            }
            require(sha256(target) == expectedHash) { "Copied baseline asset changed: $relativePath" }
            return target
        }
        for ((filename, hash) in hashes) verifiedCopy("source/$filename", hash)
        for (clip in archived) {
            require(clip["patch"] is Map<*, *> && clip["macros"] is Map<*, *>) { "Archived patch is incomplete: ${clip["id"]}" }
            for ((pathKey, hashKey) in listOf("rawPath" to "rawSha256", "matchedPath" to "audioSha256")) {
                val path = clip[pathKey] as? String ?: error("Missing baseline $pathKey")
                val hash = clip[hashKey] as? String ?: error("Missing baseline $hashKey")
                require(path.startsWith(if (pathKey == "rawPath") "raw/" else "matched/")) { "Unexpected archived audio path $path" }
                require(verifiedCopy(path, hash).length() < 25 * 1024 * 1024) { "Baseline WAV exceeds publication file limit" }
            }
        }
        if (baselineFile.canonicalFile != File(targetDirectory, "baseline.json").canonicalFile)
            File(targetDirectory, "baseline.json").writeText(Json.write(json(original)) + "\n")
        return ComparisonBaseline(identityKeys.associateWith { original[it] } + ("presetCount" to archived.size), archived)
    }

    private fun addComparisons(clips: MutableList<LinkedHashMap<String, Any?>>, baseline: ComparisonBaseline) {
        val currentPresets = clips.filter { it["category"] == "presets" }.associateBy { it.getValue("id") as String }
        require(currentPresets.size == baseline.clips.size) { "Previous and new preset rosters differ" }
        for (previous in baseline.clips) {
            val presetId = previous.getValue("id") as String
            val current = currentPresets[presetId] ?: error("Previous preset has no new partner: $presetId")
            require(previous["voice"] == current["voice"] && previous["note"] == current["note"] &&
                (previous["velocity"] as? Number)?.toDouble() == (current["velocity"] as? Number)?.toDouble()) {
                "Comparison note, voice or velocity changed for $presetId"
            }
            val name = previous.getValue("title") as String
            val previousReference = LinkedHashMap(previous).apply {
                put("id", "previous_$presetId"); put("category", "revision"); put("group", name)
                put("title", "$name · Previous"); put("featured", false)
                put("description", "The last published dry voicing, preserved at the same note, velocity and matched-level policy as New.")
                put("referencePresetId", presetId); put("comparisonVersion", "previous")
                put("comparisonRevision", "previous"); put("baselineRound", baseline.metadata["listeningRound"])
                put("rawPath", "comparison/${previous.getValue("rawPath")}")
                put("matchedPath", "comparison/${previous.getValue("matchedPath")}")
                put("normalizedPath", null); put("normalizedMetrics", null); put("repeatPath", null); put("repeatCycles", null)
                put("provenance", baseline.metadata)
            }
            val newReference = LinkedHashMap(current).apply {
                put("id", "revised_$presetId"); put("category", "revision"); put("group", name)
                put("title", "$name · New"); put("featured", false)
                put("description", current.getValue("description"))
                put("referencePresetId", presetId); put("comparisonVersion", "new")
                put("comparisonRevision", "new"); put("baselineRound", baseline.metadata["listeningRound"])
            }
            clips += previousReference
            clips += newReference
        }
        check(clips.map { it["id"] }.distinct().size == clips.size) { "Duplicate comparison IDs" }
    }

    private fun cases(quick: Boolean): List<Case> = buildList {
        for (preset in ContourPresets.all()) add(Case("preset_${slug(preset.name)}", "presets", "Nine provisional recipes", preset.name,
            presetDescriptions.getValue(preset.name),
            preset.voice, preset.macros, preset.velocity, preset.config, includeNormalized = true, featured = true))
        val controlReferences = listOf("Moving Corner", "Copper Scan", "Twin Outline")
        for (referenceName in controlReferences) for (control in expandedControls) {
            // Dark Table's lower base cutoff leaves room to hear the ENV sweep.
            val name = if (referenceName == "Moving Corner" && control.name == "ENV") "Dark Table" else referenceName
            val preset = ContourPresets.all().single { it.name == name }
            val neutral = ContourEngine.defaults(preset.voice) + preset.macros + expandedControls.associate { it.name to .5f }
            for (value in listOf(0f, .5f, 1f)) {
                add(Case("control_${slug(name)}_${control.name.lowercase()}_${tag(value)}", "controls", "$name · ${control.name}",
                    "$name · ${control.name} ${fmt(value)}",
                    "${control.description} ${control.endpoint(value)}. The saved configuration, note and velocity stay fixed; all other added controls are neutral at 0.5.",
                    preset.voice, neutral + (control.name to value), preset.velocity, preset.config,
                    control = control, controlValue = value, controlBase = "preset_${slug(name)}", controlBaseLabel = name))
            }
        }
        for (voice in ContourVoice.entries) {
            for (tune in if (quick) listOf(.5f) else listOf(.25f, .5f, .75f)) for (velocity in if (quick) listOf(1f) else listOf(.3f, 1f)) {
                val note = noteName(rootMidi(voice) + ((tune - .5f) * 48f).roundToInt())
                add(Case("voice_${voice.name.lowercase()}_${tag(tune)}_${tag(velocity)}", "voices", voice.name,
                    "${voice.name} · $note · ${if (velocity < .5f) "soft" else "hard"}",
                    "Voice defaults at $note. Velocity changes excitation, filter motion and spectral emphasis; playback level is matched.",
                    voice, ContourEngine.defaults(voice) + ("TUNE" to tune), velocity,
                    includeNormalized = tune == .5f && velocity == 1f, featured = tune == .5f && velocity == 1f))
            }
            val neutral = ContourEngine.macrosFor(voice).associate { it.name to it.neutral } + mapOf("TUNE" to .5f, "HOLD" to 0f)
            if (!quick) {
                for (macro in timbral) for (step in steps) {
                    val companions = if (macro == "SCAN") neutral + ("MOTION" to 0f) else neutral
                    add(Case("sweep_${voice.name.lowercase()}_${macro.lowercase()}_${tag(step)}", "sweeps", "${voice.name} · $macro",
                        "${voice.name} · $macro ${fmt(step)}", macroNotes.getValue(macro), voice, companions + (macro to step)))
                }
                for ((a, b) in interactions) for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
                    add(Case("interaction_${voice.name.lowercase()}_${a.lowercase()}_${b.lowercase()}_${tag(x)}_${tag(y)}", "interactions", "${voice.name} · $a × $b",
                        "$a ${fmt(x)} · $b ${fmt(y)}", interactionNote(a, b), voice, neutral + mapOf(a to x, b to y)))
                }
            }
            add(Case("corner_${voice.name.lowercase()}_all_high", "corners", "Every voice at the corner", "${voice.name} · original controls high",
                "Maximum SCAN, OFFSET, MOTION, FILTER and DRIVE; the seven added controls stay neutral. Listen for a recognizable root, finite output and useful roughness.", voice,
                neutral + timbral.associateWith { 1f }))
            for (diagnostic in ContourDiagnostic.entries) {
                val macros = ContourEngine.defaults(voice) + mapOf("OFFSET" to .72f, "MOTION" to .68f, "FILTER" to .67f, "DRIVE" to .42f)
                add(Case("diagnostic_${voice.name.lowercase()}_${diagnostic.name.lowercase()}", "diagnostics", "${voice.name} · body and moving edge",
                    "${voice.name} · ${diagnostic.name.lowercase().replace('_', ' ')}", diagnosticNote(diagnostic), voice,
                    macros, diagnostic = diagnostic, featured = voice == ContourVoice.KEY))
            }
            val holdStates = if (quick) listOf("normal" to emptyMap()) else listOf(
                "normal" to emptyMap(), "fast separated" to mapOf("SCAN" to .75f, "OFFSET" to 1f, "MOTION" to 1f),
                "driven closed" to mapOf("FILTER" to 0f, "DRIVE" to 1f, "OFFSET" to .85f, "MOTION" to 1f),
                "all high" to timbral.associateWith { 1f },
            )
            for ((label, changes) in holdStates) add(Case("hold_${voice.name.lowercase()}_${slug(label)}", "hold", "${voice.name} · settled sustain",
                "${voice.name} · $label HOLD", "Settled sustain without the initial attack. Listen across repeated wraps for clicks, level jumps or unstable motion.",
                voice, ContourEngine.defaults(voice) + changes + ("HOLD" to 1f)))
        }
    }

    private fun caseMetadata(case: Case): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to case.id, "category" to case.category, "group" to case.group,
        "title" to case.title, "description" to case.description, "voice" to case.voice.name,
        "macros" to case.macros, "velocity" to case.velocity,
        "note" to noteName(rootMidi(case.voice) + ((case.macros.getValue("TUNE") - .5f) * 48f).roundToInt()),
        "diagnostic" to case.diagnostic?.name, "featured" to case.featured,
        "config" to case.config.toString(),
        "patch" to ContourPatch(case.title, case.voice, case.macros, case.config, case.velocity).toJsonValue(),
    ).apply {
        case.control?.let { control ->
            val value = checkNotNull(case.controlValue)
            put("control", control.name); put("controlValue", value)
            put("controlBase", case.controlBase); put("controlBaseLabel", case.controlBaseLabel)
            put("controlLabel", control.label); put("controlEndpoint", control.endpoint(value))
        }
    }

    private fun category(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)
    private fun interactionNote(a: String, b: String) = when (a to b) {
        "SCAN" to "OFFSET" -> "Compare how base spectral position changes the edge's separated perspective while the body retains the note."
        "OFFSET" to "MOTION" -> "Compare layer separation against moving trajectories. Listen for different edge travel rather than a louder mix."
        "SCAN" to "FILTER" -> "Compare source spectral changes against filtering. The open filter should reveal SCAN's own role."
        else -> "Compare cutoff against excitation drive. Opening the filter and increasing drive should each have an audible role."
    }
    private fun diagnosticNote(d: ContourDiagnostic) = when (d) {
        ContourDiagnostic.BODY_ONLY -> "Body layer alone. Listen for the root and stable musical core."
        ContourDiagnostic.EDGE_ONLY -> "Edge layer alone. Hear its related but different spectral path."
        ContourDiagnostic.COMBINED -> "Both layers together. The body should remain recognizable while the edge moves."
        ContourDiagnostic.FROZEN -> "Both scans frozen. Compare against combined to hear spectral motion before the filter."
        ContourDiagnostic.OPEN -> "Shared filter open. Compare against combined to separate wavetable movement from filter contour."
        ContourDiagnostic.CLEAN -> "Drive clean. Compare against combined to hear the independent weight and grit from excitation."
    }
    private fun level(snip: Snip, target: Float, ceiling: Float): Snip = Snip(snip.samples.copyOf().also {
        Dsp.levelTo(it, snip.sampleRate, target, ceiling = ceiling)
    }, 1, snip.sampleRate)
    private fun metrics(snip: Snip): Map<String, Any> {
        var sum = 0.0; var squares = 0.0
        for (sample in snip.samples) { sum += sample; squares += sample.toDouble() * sample }
        return mapOf("peak" to snip.peak(), "rms" to sqrt(squares / snip.samples.size), "dc" to sum / snip.samples.size, "loudness" to Loudness.of(snip))
    }
    private fun rootMidi(voice: ContourVoice) = when (voice) {
        ContourVoice.BASS -> 36
        ContourVoice.KEY, ContourVoice.PLUCK, ContourVoice.LEAD -> 60
        ContourVoice.HOLLOW, ContourVoice.SWELL -> 48
    }
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    private fun slug(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun tag(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(v: Float) = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun writeWav(root: File, path: String, snip: Snip) {
        require(snip.peak() <= 1f && snip.samples.all { it.isFinite() }) { "Invalid PCM output $path" }
        val file = WavWriter.write(File(root, path), snip, AUDITION_DEPTH)
        require(file.length() < 25 * 1024 * 1024) { "$path exceeds the publishing file limit" }
    }
    private fun writePage(root: File) {
        val page = checkNotNull(javaClass.getResourceAsStream("/contour-audition/index.html")) { "Missing audition page resource" }.bufferedReader().use { it.readText() }
        require(page.contains("manifest.json") && page.contains("exportVerdicts")) { "Incomplete audition page" }
        val starterLink = if (File(root, "instruments/manifest.json").isFile)
            "<a class=\"download\" href=\"instruments/\">Hear the held instruments ↗</a>" else ""
        File(root, "index.html").writeText(page.replace("<!--STARTER_INSTRUMENTS-->", starterLink))
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported evidence ${value.javaClass}")
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
